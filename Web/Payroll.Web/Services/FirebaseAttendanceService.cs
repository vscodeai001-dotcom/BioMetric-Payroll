using System.Globalization;
using System.Text.Json;
using Payroll.Shared.Data;
using Payroll.Shared;
using Microsoft.EntityFrameworkCore;

namespace Payroll.Web.Services;

/// <summary>
/// Firebase SSOT read boundary for attendance history.
/// Final calculated DailySummary records and canonical AttendancePunch records
/// are read from Firebase; SQL remains the authoritative mutation/calculation
/// boundary for operations that still require the existing attendance engine.
/// In Offline Standalone Mode, reads directly from local SQLite database.
/// </summary>
public sealed class FirebaseAttendanceService
{
    private readonly FirebaseRealtimeService _firebase;
    private readonly IConfiguration _configuration;
    private readonly IServiceScopeFactory? _scopeFactory;
    private readonly ILogger<FirebaseAttendanceService>? _logger;

    public FirebaseAttendanceService(
        FirebaseRealtimeService firebase,
        IConfiguration configuration,
        IServiceScopeFactory? scopeFactory = null,
        ILogger<FirebaseAttendanceService>? logger = null)
    {
        _firebase = firebase;
        _configuration = configuration;
        _scopeFactory = scopeFactory;
        _logger = logger;
    }

    private string OwnerUid => _firebase.ResolveOwnerUid("attendance-service", "Admin");

    public async Task<List<DailySummary>> GetDailySummariesAsync(
        DateOnly from,
        DateOnly to,
        CancellationToken ct = default)
    {
        if (from > to) return new();

        List<DailySummary> localList = new();
        if (_scopeFactory != null)
        {
            using var scope = _scopeFactory.CreateScope();
            var dbFactory = scope.ServiceProvider.GetService<IDbContextFactory<AppDbContext>>();
            if (dbFactory != null)
            {
                using var db = await dbFactory.CreateDbContextAsync(ct);
                localList = await db.DailySummaries.AsNoTracking()
                    .Where(x => x.ShiftDate >= from && x.ShiftDate <= to)
                    .OrderBy(x => x.ShiftDate).ThenBy(x => x.EmployeeID)
                    .ToListAsync(ct);

                var appMode = scope.ServiceProvider.GetService<IAppModeService>();
                var isOffline = appMode != null && await appMode.IsOfflineModeAsync();

                if (isOffline || localList.Count > 0)
                {
                    return localList
                        .GroupBy(x => (x.EmployeeID, x.ShiftDate))
                        .Select(g => g.OrderByDescending(s => s.EarnedStandardHours).ThenByDescending(s => s.SummaryID).First())
                        .OrderBy(x => x.ShiftDate).ThenBy(x => x.EmployeeID)
                        .ToList();
                }
            }
        }

        var json = await _firebase.GetOwnerTableAsync(OwnerUid, "daily_summaries", ct);
        if ((json is null || (json.Value.ValueKind != JsonValueKind.Object && json.Value.ValueKind != JsonValueKind.Array)) &&
            !string.Equals(OwnerUid, Payroll.Shared.Firebase.FirebaseSsotSchema.DefaultOwnerUid, StringComparison.OrdinalIgnoreCase))
        {
            json = await _firebase.GetOwnerTableAsync(Payroll.Shared.Firebase.FirebaseSsotSchema.DefaultOwnerUid, "daily_summaries", ct);
        }
        if (json is null || (json.Value.ValueKind != JsonValueKind.Object && json.Value.ValueKind != JsonValueKind.Array))
        {
            return localList
                .GroupBy(x => (x.EmployeeID, x.ShiftDate))
                .Select(g => g.OrderByDescending(s => s.EarnedStandardHours).ThenByDescending(s => s.SummaryID).First())
                .OrderBy(x => x.ShiftDate).ThenBy(x => x.EmployeeID)
                .ToList();
        }

        var result = new List<DailySummary>();
        if (json.Value.ValueKind == JsonValueKind.Object)
        {
            foreach (var item in json.Value.EnumerateObject())
            {
                if (item.Value.ValueKind != JsonValueKind.Object) continue;
                var summary = ParseDailySummary(item.Value, item.Name, from, to);
                if (summary != null) result.Add(summary);
            }
        }
        else
        {
            var index = 0;
            foreach (var row in json.Value.EnumerateArray())
            {
                var fallbackId = index.ToString(CultureInfo.InvariantCulture);
                index++;
                if (row.ValueKind != JsonValueKind.Object) continue;
                var summary = ParseDailySummary(row, fallbackId, from, to);
                if (summary != null) result.Add(summary);
            }
        }

        if (localList.Count > 0)
        {
            var existingKeys = new HashSet<(int EmployeeID, DateOnly ShiftDate)>(result.Select(r => (r.EmployeeID, r.ShiftDate)));
            foreach (var ls in localList)
            {
                if (existingKeys.Add((ls.EmployeeID, ls.ShiftDate)))
                {
                    result.Add(ls);
                }
            }
        }

        var sortedResult = result
            .GroupBy(x => (x.EmployeeID, x.ShiftDate))
            .Select(g => g.OrderByDescending(s => s.EarnedStandardHours).ThenByDescending(s => s.SummaryID).First())
            .OrderBy(x => x.ShiftDate).ThenBy(x => x.EmployeeID)
            .ToList();

        if (sortedResult.Count > 0 && _scopeFactory != null)
        {
            try
            {
                using var cacheScope = _scopeFactory.CreateScope();
                var dbFactory = cacheScope.ServiceProvider.GetService<IDbContextFactory<AppDbContext>>();
                if (dbFactory != null)
                {
                    using var db = await dbFactory.CreateDbContextAsync(ct);
                    foreach (var s in sortedResult)
                    {
                        var exists = await db.DailySummaries.AnyAsync(x => x.SummaryID == s.SummaryID || (x.EmployeeID == s.EmployeeID && x.ShiftDate == s.ShiftDate), ct);
                        if (!exists)
                        {
                            db.DailySummaries.Add(s);
                        }
                    }
                    await db.SaveChangesAsync(ct);
                }
            }
            catch (Exception ex)
            {
                _logger?.LogDebug(ex, "Failed to cache fetched daily summaries to local SQLite");
            }
        }

        return sortedResult;
    }

    private static DailySummary? ParseDailySummary(JsonElement row, string key, DateOnly from, DateOnly to)
    {
        var emp = Int(row, "employeeId", "EmployeeID", "staffId", "StaffID");
        var date = Date(row, "shiftDate", "ShiftDate", "date", "Date");
        if (!emp.HasValue || date is null || date.Value < from || date.Value > to) return null;

        return new DailySummary
        {
            SummaryID = Int(row, "summaryId", "SummaryID") ?? IntFromKey(key) ?? 0,
            EmployeeID = emp.Value,
            ShiftDate = date.Value,
            Status = String(row, "status", "Status") ?? "Absent",
            EarnedStandardHours = Decimal(row, "earnedStandardHours", "EarnedStandardHours") ?? 0m,
            TotalOvertimeDuration = Duration(row, "totalOvertimeMs", "totalOvertimeDuration", "TotalOvertimeDuration"),
            TotalPenaltyDuration = Duration(row, "totalPenaltyMs", "totalPenaltyDuration", "TotalPenaltyDuration"),
            TotalLateness = Duration(row, "totalLatenessMs", "totalLateness", "TotalLateness"),
            TotalBreakPenalty = Duration(row, "totalBreakPenaltyMs", "totalBreakPenalty", "TotalBreakPenalty"),
            ScheduledShiftDuration = Duration(row, "scheduledShiftDurationMs", "scheduledShiftDuration", "ScheduledShiftDuration"),
            ShiftAllowanceEarned = Decimal(row, "shiftAllowanceEarned", "ShiftAllowanceEarned") ?? 0m,
            IsManualOverride = Bool(row, "isManualOverride", "IsManualOverride") ?? false
        };
    }

    public async Task<List<DailySummary>> GetDailySummariesAsync(
        int employeeId,
        DateOnly from,
        DateOnly to,
        CancellationToken ct = default)
    {
        if (employeeId <= 0 || from > to) return new();

        if (_scopeFactory != null)
        {
            using var scope = _scopeFactory.CreateScope();
            var dbFactory = scope.ServiceProvider.GetService<IDbContextFactory<AppDbContext>>();
            if (dbFactory != null)
            {
                using var db = await dbFactory.CreateDbContextAsync(ct);
                var localList = await db.DailySummaries.AsNoTracking()
                    .Where(x => x.EmployeeID == employeeId && x.ShiftDate >= from && x.ShiftDate <= to)
                    .OrderBy(x => x.ShiftDate).ThenBy(x => x.EmployeeID)
                    .ToListAsync(ct);

                var appMode = scope.ServiceProvider.GetService<IAppModeService>();
                var isOffline = appMode != null && await appMode.IsOfflineModeAsync();

                if (localList.Count > 0 || isOffline)
                {
                    return localList
                        .GroupBy(x => (x.EmployeeID, x.ShiftDate))
                        .Select(g => g.OrderByDescending(s => s.EarnedStandardHours).ThenByDescending(s => s.SummaryID).First())
                        .OrderBy(x => x.ShiftDate)
                        .ToList();
                }
            }
        }

        var json = await _firebase.GetOwnerTableByChildValueAsync(
            OwnerUid, "daily_summaries", "employeeId", employeeId, ct);
        if ((json is null || (json.Value.ValueKind != JsonValueKind.Object && json.Value.ValueKind != JsonValueKind.Array)) &&
            !string.Equals(OwnerUid, Payroll.Shared.Firebase.FirebaseSsotSchema.DefaultOwnerUid, StringComparison.OrdinalIgnoreCase))
        {
            json = await _firebase.GetOwnerTableByChildValueAsync(
                Payroll.Shared.Firebase.FirebaseSsotSchema.DefaultOwnerUid, "daily_summaries", "employeeId", employeeId, ct);
        }

        if (json is null || (json.Value.ValueKind != JsonValueKind.Object && json.Value.ValueKind != JsonValueKind.Array)) return new();

        var result = new List<DailySummary>();
        if (json.Value.ValueKind == JsonValueKind.Object)
        {
            foreach (var item in json.Value.EnumerateObject())
            {
                if (item.Value.ValueKind != JsonValueKind.Object) continue;
                var summary = ParseDailySummaryForEmployee(item.Value, item.Name, employeeId, from, to);
                if (summary != null) result.Add(summary);
            }
        }
        else
        {
            var index = 0;
            foreach (var row in json.Value.EnumerateArray())
            {
                var fallbackId = index.ToString(CultureInfo.InvariantCulture);
                index++;
                if (row.ValueKind != JsonValueKind.Object) continue;
                var summary = ParseDailySummaryForEmployee(row, fallbackId, employeeId, from, to);
                if (summary != null) result.Add(summary);
            }
        }

        var sortedResult = result
            .GroupBy(x => (x.EmployeeID, x.ShiftDate))
            .Select(g => g.OrderByDescending(s => s.EarnedStandardHours).ThenByDescending(s => s.SummaryID).First())
            .OrderBy(x => x.ShiftDate)
            .ToList();

        if (sortedResult.Count > 0 && _scopeFactory != null)
        {
            try
            {
                using var cacheScope = _scopeFactory.CreateScope();
                var dbFactory = cacheScope.ServiceProvider.GetService<IDbContextFactory<AppDbContext>>();
                if (dbFactory != null)
                {
                    using var db = await dbFactory.CreateDbContextAsync(ct);
                    foreach (var s in sortedResult)
                    {
                        var exists = await db.DailySummaries.AnyAsync(x => x.SummaryID == s.SummaryID || (x.EmployeeID == s.EmployeeID && x.ShiftDate == s.ShiftDate), ct);
                        if (!exists)
                        {
                            db.DailySummaries.Add(s);
                        }
                    }
                    await db.SaveChangesAsync(ct);
                }
            }
            catch (Exception ex)
            {
                _logger?.LogDebug(ex, "Failed to cache employee daily summaries to local SQLite");
            }
        }

        return sortedResult;
    }

    private static DailySummary? ParseDailySummaryForEmployee(JsonElement row, string key, int employeeId, DateOnly from, DateOnly to)
    {
        var emp = Int(row, "employeeId", "EmployeeID");
        var date = Date(row, "shiftDate", "ShiftDate");
        if (emp != employeeId || date is null || date.Value < from || date.Value > to) return null;

        return new DailySummary
        {
            SummaryID = Int(row, "summaryId", "SummaryID") ?? IntFromKey(key) ?? 0,
            EmployeeID = employeeId,
            ShiftDate = date.Value,
            Status = String(row, "status", "Status") ?? "Absent",
            EarnedStandardHours = Decimal(row, "earnedStandardHours", "EarnedStandardHours") ?? 0m,
            TotalOvertimeDuration = Duration(row, "totalOvertimeMs", "totalOvertimeDuration", "TotalOvertimeDuration"),
            TotalPenaltyDuration = Duration(row, "totalPenaltyMs", "totalPenaltyDuration", "TotalPenaltyDuration"),
            TotalLateness = Duration(row, "totalLatenessMs", "totalLateness", "TotalLateness"),
            TotalBreakPenalty = Duration(row, "totalBreakPenaltyMs", "totalBreakPenalty", "TotalBreakPenalty"),
            ScheduledShiftDuration = Duration(row, "scheduledShiftDurationMs", "scheduledShiftDuration", "ScheduledShiftDuration"),
            ShiftAllowanceEarned = Decimal(row, "shiftAllowanceEarned", "ShiftAllowanceEarned") ?? 0m,
            IsManualOverride = Bool(row, "isManualOverride", "IsManualOverride") ?? false
        };
    }

    public async Task<List<AttendanceLog>> GetAttendancePunchesAsync(
        DateOnly from,
        DateOnly to,
        int? employeeId = null,
        CancellationToken ct = default)
    {
        if (from > to) return new();

        List<AttendanceLog> localPunches = new();
        if (_scopeFactory != null)
        {
            using var scope = _scopeFactory.CreateScope();
            var dbFactory = scope.ServiceProvider.GetService<IDbContextFactory<AppDbContext>>();
            if (dbFactory != null)
            {
                using var db = await dbFactory.CreateDbContextAsync(ct);
                var startDt = from.ToDateTime(TimeOnly.MinValue);
                var endDt = to.AddDays(1).ToDateTime(TimeOnly.MinValue);
                var query = db.AttendanceLogs.AsNoTracking()
                    .Where(x => x.PunchTime >= startDt && x.PunchTime < endDt);
                if (employeeId.HasValue && employeeId.Value > 0)
                {
                    query = query.Where(x => x.EmployeeID == employeeId.Value);
                }
                localPunches = await query.OrderBy(x => x.PunchTime).ThenBy(x => x.EmployeeID).ToListAsync(ct);

                var appMode = scope.ServiceProvider.GetService<IAppModeService>();
                var isOffline = appMode != null && await appMode.IsOfflineModeAsync();

                if (isOffline)
                {
                    return localPunches;
                }
            }
        }

        var indiaZone = TimeZoneInfo.FindSystemTimeZoneById(GetIndiaTimeZoneId());
        var startLocal = from.ToDateTime(TimeOnly.MinValue);
        var endLocal = to.AddDays(1).ToDateTime(TimeOnly.MinValue);
        var startUtcMs = new DateTimeOffset(TimeZoneInfo.ConvertTimeToUtc(startLocal, indiaZone)).ToUnixTimeMilliseconds();
        var endUtcMs = new DateTimeOffset(TimeZoneInfo.ConvertTimeToUtc(endLocal, indiaZone)).ToUnixTimeMilliseconds() - 1;

        // Build biometric to EmployeeID mapping for string staffId resolution
        var biometricMap = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        if (_scopeFactory != null)
        {
            using var scope = _scopeFactory.CreateScope();
            var dbFactory = scope.ServiceProvider.GetService<IDbContextFactory<AppDbContext>>();
            if (dbFactory != null)
            {
                using var db = await dbFactory.CreateDbContextAsync(ct);
                var emps = await db.Employees.AsNoTracking().ToListAsync(ct);
                foreach (var e in emps)
                {
                    biometricMap[e.EmployeeID.ToString(CultureInfo.InvariantCulture)] = e.EmployeeID;
                    if (!string.IsNullOrWhiteSpace(e.BiometricID))
                    {
                        biometricMap[e.BiometricID.Trim()] = e.EmployeeID;
                    }
                }
            }
        }

        // Try reading punches across active OwnerUid and fallback tenant owners
        var candidateOwners = new List<string>();
        if (!string.IsNullOrWhiteSpace(OwnerUid))
            candidateOwners.Add(OwnerUid);
        if (!candidateOwners.Contains(Payroll.Shared.Firebase.FirebaseSsotSchema.DefaultOwnerUid, StringComparer.OrdinalIgnoreCase))
            candidateOwners.Add(Payroll.Shared.Firebase.FirebaseSsotSchema.DefaultOwnerUid);
        if (!candidateOwners.Contains("tenant_201", StringComparer.OrdinalIgnoreCase))
            candidateOwners.Add("tenant_201");
        if (!candidateOwners.Contains("201", StringComparer.OrdinalIgnoreCase))
            candidateOwners.Add("201");

        var result = new List<AttendanceLog>();

        foreach (var owner in candidateOwners)
        {
            // 1. Try attendance_punches by range
            var json = await _firebase.GetOwnerTableByChildRangeAsync(
                owner,
                "attendance_punches",
                "timestamp",
                startUtcMs,
                endUtcMs,
                limitToLast: 10000,
                cancellationToken: ct);

            // If range query returns null or empty, read full table as fallback
            if (json is null || (json.Value.ValueKind != JsonValueKind.Object && json.Value.ValueKind != JsonValueKind.Array) ||
                (json.Value.ValueKind == JsonValueKind.Object && !json.Value.EnumerateObject().Any()) ||
                (json.Value.ValueKind == JsonValueKind.Array && json.Value.GetArrayLength() == 0))
            {
                json = await _firebase.GetOwnerTableAsync(owner, "attendance_punches", ct);
            }

            ParseAndCollectPunches(json, from, to, employeeId, result, biometricMap);

            // 2. Also inspect the companion attendance table (where checkInTime / checkOutTime reside)
            var attJson = await _firebase.GetOwnerTableByChildRangeAsync(
                owner,
                "attendance",
                "checkInTime",
                startUtcMs,
                endUtcMs,
                limitToLast: 10000,
                cancellationToken: ct);

            if (attJson is null || (attJson.Value.ValueKind != JsonValueKind.Object && attJson.Value.ValueKind != JsonValueKind.Array) ||
                (attJson.Value.ValueKind == JsonValueKind.Object && !attJson.Value.EnumerateObject().Any()) ||
                (attJson.Value.ValueKind == JsonValueKind.Array && attJson.Value.GetArrayLength() == 0))
            {
                attJson = await _firebase.GetOwnerTableAsync(owner, "attendance", ct);
            }

            ParseAndCollectPunches(attJson, from, to, employeeId, result, biometricMap);
        }

        if (localPunches.Count > 0)
        {
            var existingKeys = new HashSet<string>(result.Select(r => $"{r.EmployeeID}_{r.PunchTime:yyyyMMdd_HHmm}_{r.LogType?.ToUpperInvariant()}"));
            var existingLogIds = new HashSet<int>(result.Where(r => r.LogID > 0).Select(r => r.LogID));
            var existingBioIds = new HashSet<string>(result.Where(r => !string.IsNullOrEmpty(r.BiometricID) && !r.BiometricID.Equals("GEOFENCE_AUTO", StringComparison.OrdinalIgnoreCase)).Select(r => $"{r.EmployeeID}_{r.BiometricID.ToUpperInvariant()}"));

            foreach (var lp in localPunches)
            {
                if (lp.LogID > 0 && existingLogIds.Contains(lp.LogID))
                    continue;

                if (!string.IsNullOrEmpty(lp.BiometricID) &&
                    !lp.BiometricID.Equals("GEOFENCE_AUTO", StringComparison.OrdinalIgnoreCase) &&
                    existingBioIds.Contains($"{lp.EmployeeID}_{lp.BiometricID.ToUpperInvariant()}"))
                    continue;

                var key = $"{lp.EmployeeID}_{lp.PunchTime:yyyyMMdd_HHmm}_{lp.LogType?.ToUpperInvariant()}";
                if (existingKeys.Add(key))
                {
                    result.Add(lp);
                }
            }
        }

        return result
            .GroupBy(x => $"{x.EmployeeID}_{x.PunchTime:yyyyMMdd_HHmmss}_{x.LogType?.ToUpperInvariant()}")
            .Select(g => g.First())
            .OrderBy(x => x.PunchTime)
            .ThenBy(x => x.EmployeeID)
            .ToList();
    }

    private static void ParseAndCollectPunches(
        JsonElement? json,
        DateOnly from,
        DateOnly to,
        int? employeeId,
        List<AttendanceLog> destination,
        Dictionary<string, int>? biometricMap = null)
    {
        if (json is null || (json.Value.ValueKind != JsonValueKind.Object && json.Value.ValueKind != JsonValueKind.Array))
            return;

        var existingKeys = new HashSet<string>(destination.Select(r => $"{r.EmployeeID}_{r.PunchTime:yyyyMMdd_HHmmss}_{r.LogType?.ToUpperInvariant()}"));
        var seenBioKeys = new HashSet<string>(
            destination
                .Where(r => !string.IsNullOrEmpty(r.BiometricID) &&
                            (r.BiometricID.StartsWith("AUTO_", StringComparison.OrdinalIgnoreCase) ||
                             r.BiometricID.Equals("GEOFENCE_AUTO", StringComparison.OrdinalIgnoreCase)))
                .Select(r => $"{r.EmployeeID}_{r.PunchTime:yyyyMMdd_HHmm}_{r.BiometricID.ToUpperInvariant()}")
        );
        var seenMinuteDirKeys = new HashSet<string>(
            destination.Select(r =>
            {
                bool isOut = (r.LogType ?? string.Empty).ToUpperInvariant().Contains("OUT");
                return $"{r.EmployeeID}_{r.PunchTime:yyyyMMdd_HHmm}_{(isOut ? "OUT" : "IN")}";
            })
        );

        void AddIfNew(AttendanceLog? log)
        {
            if (log == null) return;
            var key = $"{log.EmployeeID}_{log.PunchTime:yyyyMMdd_HHmmss}_{log.LogType?.ToUpperInvariant()}";
            bool isOut = (log.LogType ?? string.Empty).ToUpperInvariant().Contains("OUT");
            var minDirKey = $"{log.EmployeeID}_{log.PunchTime:yyyyMMdd_HHmm}_{(isOut ? "OUT" : "IN")}";

            string? bioKey = null;
            if (!string.IsNullOrEmpty(log.BiometricID) &&
                (log.BiometricID.StartsWith("AUTO_", StringComparison.OrdinalIgnoreCase) ||
                 log.BiometricID.Equals("GEOFENCE_AUTO", StringComparison.OrdinalIgnoreCase)))
            {
                bioKey = $"{log.EmployeeID}_{log.PunchTime:yyyyMMdd_HHmm}_{log.BiometricID.ToUpperInvariant()}";
            }

            // If punch with identical BiometricID or exact minute+direction was already collected
            if ((bioKey != null && seenBioKeys.Contains(bioKey)) || seenMinuteDirKeys.Contains(minDirKey))
            {
                // Check if existing punch is a fallback ManualCorrection while incoming is genuine GeofenceAuto/Physical
                var existingIdx = destination.FindIndex(x =>
                    x.EmployeeID == log.EmployeeID &&
                    Math.Abs((x.PunchTime - log.PunchTime).TotalMinutes) < 1.0 &&
                    ((x.LogType?.ToUpperInvariant().Contains("OUT") ?? false) == isOut));

                if (existingIdx >= 0)
                {
                    var existing = destination[existingIdx];
                    bool existingIsFallback = string.Equals(existing.DeviceID, "ManualCorrection", StringComparison.OrdinalIgnoreCase);
                    bool incomingIsSpecific = string.Equals(log.DeviceID, "GeofenceAuto", StringComparison.OrdinalIgnoreCase) ||
                                             string.Equals(log.DeviceID, "AndroidGeofenceAuto", StringComparison.OrdinalIgnoreCase) ||
                                             (log.DeviceID != null && (log.DeviceID.StartsWith("ZKTeco", StringComparison.OrdinalIgnoreCase) ||
                                                                      log.DeviceID.StartsWith("Machine", StringComparison.OrdinalIgnoreCase)));

                    if (existingIsFallback && incomingIsSpecific)
                    {
                        // Replace fallback with genuine punch
                        destination[existingIdx] = log;
                        if (bioKey != null) seenBioKeys.Add(bioKey);
                        existingKeys.Add(key);
                        seenMinuteDirKeys.Add(minDirKey);
                    }
                }
                return;
            }

            if (existingKeys.Add(key))
            {
                if (bioKey != null) seenBioKeys.Add(bioKey);
                seenMinuteDirKeys.Add(minDirKey);
                destination.Add(log);
            }
        }

        if (json.Value.ValueKind == JsonValueKind.Object)
        {
            foreach (var item in json.Value.EnumerateObject())
            {
                if (item.Value.ValueKind != JsonValueKind.Object) continue;
                var punches = ParseAttendancePunches(item.Value, item.Name, from, to, employeeId, biometricMap, destination);
                foreach (var p in punches) AddIfNew(p);
            }
        }
        else
        {
            var index = 0;
            foreach (var row in json.Value.EnumerateArray())
            {
                var fallbackId = index.ToString(CultureInfo.InvariantCulture);
                index++;
                if (row.ValueKind != JsonValueKind.Object) continue;
                var punches = ParseAttendancePunches(row, fallbackId, from, to, employeeId, biometricMap, destination);
                foreach (var p in punches) AddIfNew(p);
            }
        }
    }

    private static List<AttendanceLog> ParseAttendancePunches(
        JsonElement row,
        string key,
        DateOnly from,
        DateOnly to,
        int? employeeId,
        Dictionary<string, int>? biometricMap = null,
        List<AttendanceLog>? existingDestination = null)
    {
        var punches = new List<AttendanceLog>();
        var emp = Int(row, "staffId", "employeeId", "EmployeeID");
        if (!emp.HasValue)
        {
            var rawStaff = String(row, "staffId", "employeeId", "EmployeeID", "biometricId", "BiometricID");
            if (!string.IsNullOrWhiteSpace(rawStaff) && biometricMap != null && biometricMap.TryGetValue(rawStaff.Trim(), out var mappedId))
            {
                emp = mappedId;
            }
        }
        if (!emp.HasValue) return punches;
        if (employeeId.HasValue && emp.Value != employeeId.Value) return punches;

        var startLocal = from.ToDateTime(TimeOnly.MinValue).Date;
        var endLocal = to.ToDateTime(TimeOnly.MinValue).Date;

        var checkInTs = ParseToIndiaDateTime(row, "timestamp", "createdAt", "checkInTime");
        var checkOutTs = ParseToIndiaDateTime(row, "checkOutTime");

        var rawDevice = String(row, "deviceId", "DeviceID");
        var rawSource = String(row, "source", "punchSource", "Source");
        var rawBioId = String(row, "biometricId", "BiometricID", "punchId", "attendanceId") ?? key;
        
        string resolvedDevice;
        if (!string.IsNullOrWhiteSpace(rawDevice))
        {
            resolvedDevice = rawDevice;
        }
        else if ((rawSource != null && rawSource.Contains("GEOFENCE", StringComparison.OrdinalIgnoreCase)) ||
                 (rawBioId != null && rawBioId.StartsWith("AUTO_", StringComparison.OrdinalIgnoreCase)) ||
                 key.StartsWith("AUTO_", StringComparison.OrdinalIgnoreCase) ||
                 key.StartsWith("ATT_", StringComparison.OrdinalIgnoreCase))
        {
            resolvedDevice = "GeofenceAuto";
        }
        else
        {
            resolvedDevice = "ManualCorrection";
        }

        var effectiveBioId = !string.IsNullOrWhiteSpace(String(row, "biometricId", "BiometricID"))
            ? String(row, "biometricId", "BiometricID")!
            : (!string.IsNullOrWhiteSpace(rawBioId) ? rawBioId : string.Empty);

        // If this is an ATT_ summary session record and raw punches already exist for this employee on this date,
        // do not duplicate or corrupt the punch stream.
        bool isSummarySession = key.StartsWith("ATT_", StringComparison.OrdinalIgnoreCase) ||
                                (String(row, "attendanceId")?.StartsWith("ATT_", StringComparison.OrdinalIgnoreCase) ?? false);
        if (isSummarySession && existingDestination != null &&
            existingDestination.Any(x => x.EmployeeID == emp.Value &&
                                          x.PunchTime.Date >= startLocal && x.PunchTime.Date <= endLocal &&
                                          !string.IsNullOrEmpty(x.BiometricID) &&
                                          (x.BiometricID.StartsWith("AUTO_", StringComparison.OrdinalIgnoreCase) ||
                                           x.BiometricID.StartsWith("MANUAL_", StringComparison.OrdinalIgnoreCase))))
        {
            return punches;
        }

        // Primary punch (or check-in)
        if (checkInTs.HasValue)
        {
            var punchDate = checkInTs.Value.Date;
            if (punchDate >= startLocal && punchDate <= endLocal)
            {
                var explicitType = String(row, "type", "logType", "note", "source", "LogType");
                string resolvedType;
                if (!string.IsNullOrWhiteSpace(explicitType) &&
                    (explicitType.Contains("OUT", StringComparison.OrdinalIgnoreCase) ||
                     explicitType.Contains("EXIT", StringComparison.OrdinalIgnoreCase) ||
                     explicitType.Equals("CHECKOUT", StringComparison.OrdinalIgnoreCase)))
                {
                    resolvedType = "OUT";
                }
                else
                {
                    resolvedType = "IN";
                }
                punches.Add(new AttendanceLog
                {
                    LogID = Int(row, "punchId", "attendanceId", "LogID") ?? IntFromKey(key) ?? 0,
                    EmployeeID = emp.Value,
                    BiometricID = effectiveBioId,
                    PunchTime = checkInTs.Value,
                    DeviceID = resolvedDevice,
                    LogType = resolvedType,
                    IsApproved = Bool(row, "isApproved", "IsApproved")
                        ?? !string.Equals(String(row, "status"), "PENDING", StringComparison.OrdinalIgnoreCase),
                    Latitude = Double(row, "latitude", "Latitude"),
                    Longitude = Double(row, "longitude", "Longitude")
                });
            }
        }

        // Secondary check-out punch if row contains paired checkOutTime
        if (checkOutTs.HasValue)
        {
            var punchDate = checkOutTs.Value.Date;
            if (punchDate >= startLocal && punchDate <= endLocal)
            {
                punches.Add(new AttendanceLog
                {
                    LogID = (Int(row, "punchId", "attendanceId", "LogID") ?? IntFromKey(key) ?? 0) + 1,
                    EmployeeID = emp.Value,
                    BiometricID = effectiveBioId,
                    PunchTime = checkOutTs.Value,
                    DeviceID = resolvedDevice,
                    LogType = "OUT",
                    IsApproved = Bool(row, "isApproved", "IsApproved")
                        ?? !string.Equals(String(row, "status"), "PENDING", StringComparison.OrdinalIgnoreCase),
                    Latitude = Double(row, "latitude", "Latitude"),
                    Longitude = Double(row, "longitude", "Longitude")
                });
            }
        }

        return punches;
    }

    private static DateTime? ParseToIndiaDateTime(JsonElement element, params string[] names)
    {
        var value = Raw(element, names);
        if (value is null || value.Value.ValueKind == JsonValueKind.Null) return null;
        var indiaZone = TimeZoneInfo.FindSystemTimeZoneById(GetIndiaTimeZoneId());

        if (value.Value.ValueKind == JsonValueKind.Number && value.Value.TryGetInt64(out var ms))
        {
            var utc = DateTimeOffset.FromUnixTimeMilliseconds(ms).UtcDateTime;
            return TimeZoneInfo.ConvertTimeFromUtc(utc, indiaZone);
        }

        var text = value.Value.ToString();
        if (string.IsNullOrWhiteSpace(text)) return null;

        if (long.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out var pMs))
        {
            var utc = DateTimeOffset.FromUnixTimeMilliseconds(pMs).UtcDateTime;
            return TimeZoneInfo.ConvertTimeFromUtc(utc, indiaZone);
        }

        if (DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.None, out var dto))
        {
            return TimeZoneInfo.ConvertTimeFromUtc(dto.UtcDateTime, indiaZone);
        }

        if (DateTime.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.None, out var dt))
        {
            return dt;
        }

        return null;
    }

    private static AttendanceLog? ParseAttendancePunch(JsonElement row, string key, DateOnly from, DateOnly to, int? employeeId)
    {
        var list = ParseAttendancePunches(row, key, from, to, employeeId);
        return list.FirstOrDefault();
    }

    private static string GetIndiaTimeZoneId()
    {
        try
        {
            TimeZoneInfo.FindSystemTimeZoneById("Asia/Kolkata");
            return "Asia/Kolkata";
        }
        catch (TimeZoneNotFoundException)
        {
            return "India Standard Time";
        }
    }

    public async Task<List<AttendanceLog>> GetAttendancePunchesAsync(
        int employeeId,
        DateOnly from,
        DateOnly to,
        CancellationToken ct = default)
    {
        return await GetAttendancePunchesAsync(from, to, employeeId, ct);
    }

    private static AttendanceLog? ParseAttendancePunchForEmployee(JsonElement row, string key, int employeeId, DateOnly from, DateOnly to)
    {
        var emp = Int(row, "staffId", "employeeId", "EmployeeID");
        var timestamp = UnixDateTime(row, "timestamp", "createdAt", "checkInTime");
        if (emp != employeeId || timestamp is null) return null;
        var localDate = TimeZoneInfo.ConvertTimeBySystemTimeZoneId(timestamp.Value, GetIndiaTimeZoneId()).Date;
        if (localDate < from.ToDateTime(TimeOnly.MinValue).Date || localDate > to.ToDateTime(TimeOnly.MinValue).Date) return null;

        return new AttendanceLog
        {
            LogID = Int(row, "punchId", "attendanceId", "LogID") ?? IntFromKey(key) ?? 0,
            EmployeeID = employeeId,
            BiometricID = String(row, "biometricId", "BiometricID") ?? string.Empty,
            PunchTime = timestamp.Value,
            DeviceID = String(row, "deviceId", "DeviceID"),
            LogType = String(row, "type", "source", "note", "LogType"),
            IsApproved = Bool(row, "isApproved", "IsApproved")
                ?? !string.Equals(String(row, "status"), "PENDING", StringComparison.OrdinalIgnoreCase),
            Latitude = Double(row, "latitude", "Latitude"),
            Longitude = Double(row, "longitude", "Longitude")
        };
    }

    private static JsonElement? Raw(JsonElement element, params string[] names)
    {
        foreach (var name in names)
            if (element.TryGetProperty(name, out var value)) return value;
        return null;
    }

    private static string? String(JsonElement element, params string[] names)
    {
        var value = Raw(element, names);
        if (value is null) return null;
        return value.Value.ValueKind == JsonValueKind.String ? value.Value.GetString() : value.Value.ToString();
    }

    private static int? Int(JsonElement element, params string[] names)
    {
        var value = Raw(element, names);
        if (value is null || value.Value.ValueKind == JsonValueKind.Null) return null;
        if (value.Value.ValueKind == JsonValueKind.Number && value.Value.TryGetInt32(out var i)) return i;
        var text = value.Value.ToString();
        if (string.IsNullOrWhiteSpace(text)) return null;
        if (int.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out i)) return i;
        if (double.TryParse(text, NumberStyles.Any, CultureInfo.InvariantCulture, out var d)) return (int)Math.Round(d);
        return null;
    }

    private static int? IntFromKey(string key) => int.TryParse(key, NumberStyles.Integer, CultureInfo.InvariantCulture, out var i) ? i : null;

    private static decimal? Decimal(JsonElement element, params string[] names)
    {
        var value = Raw(element, names);
        if (value is null || value.Value.ValueKind == JsonValueKind.Null) return null;
        if (value.Value.ValueKind == JsonValueKind.Number && value.Value.TryGetDecimal(out var d)) return d;
        var text = value.Value.ToString();
        if (string.IsNullOrWhiteSpace(text)) return null;
        if (decimal.TryParse(text, NumberStyles.Any, CultureInfo.InvariantCulture, out d)) return d;
        return null;
    }

    private static double? Double(JsonElement element, params string[] names)
    {
        var value = Raw(element, names);
        if (value is null || value.Value.ValueKind == JsonValueKind.Null) return null;
        if (value.Value.ValueKind == JsonValueKind.Number && value.Value.TryGetDouble(out var d)) return d;
        var text = value.Value.ToString();
        if (string.IsNullOrWhiteSpace(text)) return null;
        if (double.TryParse(text, NumberStyles.Any, CultureInfo.InvariantCulture, out d)) return d;
        return null;
    }

    private static bool? Bool(JsonElement element, params string[] names)
    {
        var value = Raw(element, names);
        if (value is null || value.Value.ValueKind == JsonValueKind.Null) return null;
        if (value.Value.ValueKind == JsonValueKind.True) return true;
        if (value.Value.ValueKind == JsonValueKind.False) return false;
        var text = value.Value.ToString();
        if (string.IsNullOrWhiteSpace(text)) return null;
        if (bool.TryParse(text, out var b)) return b;
        if (int.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out var n)) return n != 0;
        return null;
    }

    private static DateOnly? Date(JsonElement element, params string[] names)
    {
        var value = Raw(element, names);
        if (value is null) return null;
        var text = value.Value.ToString();
        if (DateOnly.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.None, out var d)) return d;
        if (value.Value.TryGetInt64(out var ms))
            return DateOnly.FromDateTime(DateTimeOffset.FromUnixTimeMilliseconds(ms).ToOffset(TimeZoneInfo.FindSystemTimeZoneById(GetIndiaTimeZoneId()).BaseUtcOffset).Date);
        return null;
    }

    private static DateTime? UnixDateTime(JsonElement element, params string[] names)
    {
        var value = Raw(element, names);
        if (value is null || value.Value.ValueKind == JsonValueKind.Null) return null;
        if (value.Value.ValueKind == JsonValueKind.Number && value.Value.TryGetInt64(out var ms)) return DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime;
        var text = value.Value.ToString();
        if (string.IsNullOrWhiteSpace(text)) return null;
        if (DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var dto)) return dto.LocalDateTime;
        if (long.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out var pMs)) return DateTimeOffset.FromUnixTimeMilliseconds(pMs).LocalDateTime;
        return DateTime.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.AssumeLocal, out var dt) ? dt : null;
    }

    private static TimeSpan Duration(JsonElement element, params string[] names)
    {
        var value = Raw(element, names);
        if (value is null || value.Value.ValueKind == JsonValueKind.Null) return TimeSpan.Zero;
        if (value.Value.ValueKind == JsonValueKind.Number && value.Value.TryGetDouble(out var number)) return TimeSpan.FromMilliseconds(number);
        var text = value.Value.ToString();
        if (string.IsNullOrWhiteSpace(text)) return TimeSpan.Zero;
        if (TimeSpan.TryParse(text, CultureInfo.InvariantCulture, out var ts)) return ts;
        return double.TryParse(text, NumberStyles.Any, CultureInfo.InvariantCulture, out var ms)
            ? TimeSpan.FromMilliseconds(ms)
            : TimeSpan.Zero;
    }
}
