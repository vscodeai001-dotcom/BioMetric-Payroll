using System.Globalization;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Payroll.Shared.Data;
using Payroll.Shared;

namespace Payroll.Web.Services;

/// <summary>
/// Firebase-backed source for the Admin Dashboard. This service intentionally
/// aggregates only already-published SSOT records. It does not recalculate
/// payroll or attendance business rules.
/// </summary>
public sealed class FirebaseAdminDashboardService
{
    private static readonly TimeZoneInfo s_indiaTimeZone = TimeZoneInfo.FindSystemTimeZoneById(
        OperatingSystem.IsWindows() ? "India Standard Time" : "Asia/Kolkata");

    private readonly FirebaseRealtimeService _firebase;
    private readonly ILogger<FirebaseAdminDashboardService> _logger;
    private readonly IDbContextFactory<AppDbContext>? _dbFactory;

    private FirebaseAdminDashboardSnapshot? _cachedSnapshot;
    private DateTime _cacheExpiryUtc = DateTime.MinValue;
    private readonly SemaphoreSlim _gate = new(1, 1);

    public FirebaseAdminDashboardService(
        FirebaseRealtimeService firebase,
        ILogger<FirebaseAdminDashboardService> logger,
        IDbContextFactory<AppDbContext>? dbFactory = null)
    {
        _firebase = firebase;
        _logger = logger;
        _dbFactory = dbFactory;
    }

    public void InvalidateCache()
    {
        _cachedSnapshot = null;
        _cacheExpiryUtc = DateTime.MinValue;
    }

    public async Task<FirebaseAdminDashboardSnapshot> GetSnapshotAsync(
        string actorUid,
        CancellationToken cancellationToken = default)
    {
        if (_cachedSnapshot != null && DateTime.UtcNow < _cacheExpiryUtc)
        {
            return _cachedSnapshot;
        }

        await _gate.WaitAsync(cancellationToken);
        try
        {
            if (_cachedSnapshot != null && DateTime.UtcNow < _cacheExpiryUtc)
            {
                return _cachedSnapshot;
            }

            var indiaTimeZone = s_indiaTimeZone;
            var now = TimeZoneInfo.ConvertTimeFromUtc(DateTime.UtcNow, indiaTimeZone);
            var today = DateOnly.FromDateTime(now);
            var monthStart = new DateOnly(today.Year, today.Month, 1);
            var nextMonthStart = monthStart.AddMonths(1);

            // 1. FAST LOCAL PROJECTION: Check if SQLite local cache is available
            if (_dbFactory != null)
            {
                try
                {
                    await using var db = await _dbFactory.CreateDbContextAsync(cancellationToken);
                    var localEmployees = await db.Employees.AsNoTracking().Where(e => !e.IsDeleted).ToListAsync(cancellationToken);
                    if (localEmployees.Count > 0)
                    {
                        var startOfToday = today.ToDateTime(TimeOnly.MinValue);
                        var endOfToday = today.AddDays(1).ToDateTime(TimeOnly.MinValue);

                        var activeEmployees = localEmployees.Where(e => e.TerminationDate == null || e.TerminationDate > today).ToList();
                        var activeIds = activeEmployees.Select(e => e.EmployeeID).ToHashSet();

                        var presentFromAttendance = await db.AttendanceLogs.AsNoTracking()
                            .Where(a => a.PunchTime >= startOfToday && a.PunchTime < endOfToday && a.EmployeeID.HasValue)
                            .Select(a => a.EmployeeID!.Value)
                            .ToListAsync(cancellationToken);

                        var presentFromAudits = await db.GeoPunchAudits.AsNoTracking()
                            .Where(p => p.PunchTimeUtc >= startOfToday && p.PunchTimeUtc < endOfToday && p.Success)
                            .Select(p => p.EmployeeId)
                            .ToListAsync(cancellationToken);

                        var presentIds = presentFromAttendance.Concat(presentFromAudits)
                            .Where(id => activeIds.Contains(id))
                            .ToHashSet();

                        var advances = await db.SalaryAdvances.AsNoTracking()
                            .Where(a => a.PayrollID_Paid == null)
                            .OrderByDescending(a => a.AdvanceDate)
                            .ToListAsync(cancellationToken);

                        var target = now.AddMonths(-1);
                        var previous = now.AddMonths(-2);

                        var currentPayrollCost = (await db.PayrollHistories.AsNoTracking()
                            .Where(p => p.PayMonth == target.Month && p.PayYear == target.Year)
                            .Select(p => p.NetSalary)
                            .ToListAsync(cancellationToken)).Sum();

                        var previousPayrollCost = (await db.PayrollHistories.AsNoTracking()
                            .Where(p => p.PayMonth == previous.Month && p.PayYear == previous.Year)
                            .Select(p => p.NetSalary)
                            .ToListAsync(cancellationToken)).Sum();

                        var variance = previousPayrollCost == 0m
                            ? 100m
                            : ((currentPayrollCost - previousPayrollCost) / previousPayrollCost) * 100m;

                        var hasLastMonthPayroll = await db.PayrollHistories.AsNoTracking()
                            .AnyAsync(p => p.PayMonth == target.Month && p.PayYear == target.Year, cancellationToken);

                        var shiftsToday = await db.ShiftSchedules.AsNoTracking()
                            .CountAsync(s => s.ShiftDate == today, cancellationToken);

                        var summaries = await db.DailySummaries.AsNoTracking()
                            .Where(s => s.ShiftDate >= monthStart && s.ShiftDate < nextMonthStart)
                            .ToListAsync(cancellationToken);

                        var scheduledMs = (long)summaries.Sum(s => s.ScheduledShiftDuration.TotalMilliseconds);
                        var liveTrackingCount = LiveLocationStore.GetAll().Count(l => activeIds.Contains(l.EmployeeId));

                        var localSnapshot = new FirebaseAdminDashboardSnapshot
                        {
                            TotalEmployees = localEmployees.Count,
                            ActiveEmployees = activeEmployees.Count,
                            PresentToday = presentIds.Count,
                            AbsentToday = Math.Max(0, activeEmployees.Count - presentIds.Count),
                            UnpaidAdvanceAmount = advances.Sum(a => a.Amount),
                            RecentAdvances = advances.Take(4).Select(a => new FirebaseDashboardAdvance
                            {
                                EmployeeId = a.EmployeeID,
                                Amount = a.Amount,
                                AdvanceDate = a.AdvanceDate,
                                AdvanceType = a.AdvanceType
                            }).ToList(),
                            EmployeeNames = localEmployees.ToDictionary(e => e.EmployeeID, e => string.IsNullOrWhiteSpace(e.Name) ? $"ID:{e.EmployeeID}" : e.Name),
                            PendingPayrolls = hasLastMonthPayroll ? 0 : 1,
                            CurrentPayrollCost = currentPayrollCost,
                            PreviousPayrollCost = previousPayrollCost,
                            PayrollVariancePercent = variance,
                            ShiftsScheduledToday = shiftsToday,
                            TotalMonthScheduledMs = scheduledMs,
                            LiveTrackingCount = liveTrackingCount
                        };

                        _cachedSnapshot = localSnapshot;
                        _cacheExpiryUtc = DateTime.UtcNow.AddSeconds(15);
                        return localSnapshot;
                    }
                }
                catch (Exception dbEx)
                {
                    _logger.LogWarning(dbEx, "Local DB snapshot read deferred; falling back to Firebase SSOT read.");
                }
            }

            // 2. FALLBACK TO FIREBASE REALTIME REST (Cold boot or empty SQLite)
            var ownerUid = _firebase.ResolveOwnerUid(actorUid, "Admin");
            var todayStartUtc = TimeZoneInfo.ConvertTimeToUtc(today.ToDateTime(TimeOnly.MinValue), indiaTimeZone);
            var todayEndUtc = TimeZoneInfo.ConvertTimeToUtc(today.AddDays(1).ToDateTime(TimeOnly.MinValue), indiaTimeZone);
            var todayStartMs = new DateTimeOffset(todayStartUtc).ToUnixTimeMilliseconds();
            var todayEndMs = new DateTimeOffset(todayEndUtc).ToUnixTimeMilliseconds() - 1;

            var results = await Task.WhenAll(
                _firebase.GetOwnerTableAsync(
                    ownerUid,
                    "employees",
                    cancellationToken),

                _firebase.GetOwnerTableByChildRangeAsync(
                    ownerUid,
                    "attendance",
                    "checkInTime",
                    startAt: todayStartMs,
                    endAt: todayEndMs,
                    cancellationToken: cancellationToken),

                _firebase.GetOwnerTableAsync(
                    ownerUid,
                    "advance_payments",
                    cancellationToken),

                _firebase.GetOwnerTableAsync(
                    ownerUid,
                    "payroll_history",
                    cancellationToken),

                _firebase.GetOwnerTableByChildRangeAsync(
                    ownerUid,
                    "shift_schedules",
                    "shiftDate",
                    startAt: today.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                    endAt: today.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                    cancellationToken: cancellationToken),

                _firebase.GetOwnerTableByChildRangeAsync(
                    ownerUid,
                    "daily_summaries",
                    "shiftDate",
                    startAt: monthStart.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                    endAt: nextMonthStart.AddDays(-1).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                    cancellationToken: cancellationToken),

                Task.FromResult<JsonElement?>(null), // Tracking live already maintained by LiveLocationStore

                _firebase.GetOwnerTableByChildRangeAsync(
                    ownerUid,
                    "attendance_punches",
                    "timestamp",
                    startAt: todayStartMs,
                    endAt: todayEndMs,
                    cancellationToken: cancellationToken));

        try
        {
            var employees = Items(results[0]).ToList();
            var attendance = Items(results[1]).ToList();
            var advances = Items(results[2]).ToList();
            var payroll = Items(results[3]).ToList();
            var shifts = Items(results[4]).ToList();
            var summaries = Items(results[5]).ToList();
            var tracking = Items(results[6]).ToList();
            var punches = Items(results[7]).ToList();

            // Authoritative Dashboard Date: calculated above in India Timezone.


            /*
             * Firebase contains legacy rows from several schema revisions.
             *
             * employeeId/isActive can be:
             *   Number
             *   String
             *   Boolean
             *   Null
             *
             * Parse every row independently so one legacy value cannot
             * collapse the complete Admin Dashboard.
             */
            var activeIds = employees
                .Where(e => Bool(e, "isActive", true))
                .Select(e => Int(e, "employeeId") ?? 0)
                .Where(id => id > 0)
                .ToHashSet();

            /*
             * Only active employees belonging to the current owner and having
             * fresh ACTIVE tracking records are considered live.
             */
            tracking = tracking
                .Where(t =>
                    Int(t, "EmployeeId") is int id &&
                    activeIds.Contains(id))
                .Where(IsFreshActiveTracking)
                .ToList();

            /*
             * Build employee ID -> display name map.
             */
            var employeeNames = employees
                .Select(e => (
                    Id: Int(e, "employeeId"),
                    Name: String(e, "name")))
                .Where(x => x.Id.HasValue)
                .GroupBy(x => x.Id!.Value)
                .ToDictionary(
                    g => g.Key,
                    g =>
                        string.IsNullOrWhiteSpace(g.Last().Name)
                            ? $"ID:{g.Key}"
                            : g.Last().Name!);

            /*
             * Present employees today (checks both legacy attendance and live mobile attendance punches).
             */
            var presentIds = attendance
                .Where(a =>
                    activeIds.Contains(
                        Int(a, "employeeId") ?? int.MinValue) &&
                    UnixDateTime(a, "checkInTime") is DateTime dt &&
                    dt.Date == now.Date)
                .Select(a => Int(a, "employeeId"))
                .Where(id => id.HasValue)
                .Select(id => id!.Value)
                .ToHashSet();

            var presentFromPunches = punches
                .Where(p =>
                {
                    var empId = Int(p, "employeeId") ?? Int(p, "staffId");
                    if (!empId.HasValue || !activeIds.Contains(empId.Value))
                        return false;

                    var type = String(p, "type") ?? String(p, "punchType") ?? "IN";
                    if (!type.Equals("IN", StringComparison.OrdinalIgnoreCase))
                        return false;

                    var status = String(p, "status");
                    if (!string.IsNullOrEmpty(status) && status.Equals("REJECTED", StringComparison.OrdinalIgnoreCase))
                        return false;

                    var punchTime = UnixDateTime(p, "timestamp") ?? UnixDateTime(p, "checkInTime");
                    return punchTime is DateTime dt && dt.Date == now.Date;
                })
                .Select(p => (Int(p, "employeeId") ?? Int(p, "staffId"))!.Value);

            presentIds.UnionWith(presentFromPunches);

            /*
             * Unpaid advances.
             */
            var unpaid = advances
                .Where(a =>
                    !Bool(a, "isRecovered", false) &&
                    !HasValue(a, "recoveryPaymentId"))
                .Select(a => new FirebaseDashboardAdvance
                {
                    EmployeeId = Int(a, "employeeId") ?? 0,
                    Amount = Decimal(a, "amount"),
                    AdvanceDate = UnixDateTime(a, "date"),
                    AdvanceType =
                        String(a, "advanceType") ?? "Advance"
                })
                .OrderByDescending(
                    a => a.AdvanceDate ?? DateTime.MinValue)
                .ToList();

            /*
             * Payroll comparison.
             */
            var target = now.AddMonths(-1);
            var previous = now.AddMonths(-2);

            var currentPayrollCost = payroll
                .Where(p =>
                    Int(p, "payMonth") == target.Month &&
                    Int(p, "payYear") == target.Year)
                .Sum(p => Decimal(p, "netSalary"));

            var previousPayrollCost = payroll
                .Where(p =>
                    Int(p, "payMonth") == previous.Month &&
                    Int(p, "payYear") == previous.Year)
                .Sum(p => Decimal(p, "netSalary"));

            var variance =
                previousPayrollCost == 0m
                    ? 100m
                    : ((currentPayrollCost - previousPayrollCost)
                       / previousPayrollCost) * 100m;

            /*
             * Today's scheduled shifts.
             */
            var shiftsToday = shifts.Count(
                s =>
                    String(s, "shiftDate") ==
                    today.ToString(
                        "yyyy-MM-dd",
                        CultureInfo.InvariantCulture));

            /*
             * Monthly scheduled shift duration.
             *
             * Firebase legacy data can contain:
             *
             *   3600000
             *   "3600000"
             *   "01:00:00"
             *
             * TimeSpanMilliseconds handles all of them.
             */
            var scheduledMs = summaries
                .Where(s =>
                    DateOnlyValue(s, "shiftDate") is DateOnly d &&
                    d.Year == today.Year &&
                    d.Month == today.Month)
                .Sum(s =>
                    TimeSpanMilliseconds(
                        s,
                        "scheduledShiftDuration",
                        "scheduledShiftDurationMs"));

            var hasLastMonthPayroll = payroll.Any(
                p =>
                    Int(p, "payMonth") == target.Month &&
                    Int(p, "payYear") == target.Year);

            var fallbackSnapshot = new FirebaseAdminDashboardSnapshot
            {
                TotalEmployees = employees.Count,

                ActiveEmployees = activeIds.Count,

                PresentToday = presentIds.Count,

                AbsentToday =
                    Math.Max(
                        0,
                        activeIds.Count - presentIds.Count),

                UnpaidAdvanceAmount =
                    unpaid.Sum(x => x.Amount),

                RecentAdvances =
                    unpaid.Take(4).ToList(),

                EmployeeNames =
                    employeeNames,

                PendingPayrolls =
                    hasLastMonthPayroll ? 0 : 1,

                CurrentPayrollCost =
                    currentPayrollCost,

                PreviousPayrollCost =
                    previousPayrollCost,

                PayrollVariancePercent =
                    variance,

                ShiftsScheduledToday =
                    shiftsToday,

                TotalMonthScheduledMs =
                    scheduledMs,

                LiveTrackingCount =
                    LiveLocationStore.GetAll().Count(l => activeIds.Contains(l.EmployeeId))
            };

            _cachedSnapshot = fallbackSnapshot;
            _cacheExpiryUtc = DateTime.UtcNow.AddSeconds(15);
            return fallbackSnapshot;
        }
        catch (Exception ex)
        {
            _logger.LogError(
                ex,
                "Firebase Admin Dashboard aggregation failed for owner {OwnerUid}",
                ownerUid);

            throw;
        }
    }
    finally
    {
        _gate.Release();
    }
}

    // ---------------------------------------------------------------------
    // LIVE TRACKING
    // ---------------------------------------------------------------------

    private static bool IsFreshActiveTracking(JsonElement e)
    {
        var state =
            String(e, "State") ??
            String(e, "state");

        if (!string.IsNullOrWhiteSpace(state) &&
            !state.Equals(
                "ACTIVE",
                StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        // Android/Web live records carry both Timestamp (GPS capture time)
        // and LastUpdatedUtc (server write time). Prefer capture time when it
        // is valid, but fall back to LastUpdatedUtc. Older live records can
        // omit Timestamp while still being a healthy ACTIVE session.
        var timestamp =
            String(e, "Timestamp") ??
            String(e, "timestamp") ??
            String(e, "LastUpdatedUtc") ??
            String(e, "lastUpdatedUtc");

        if (DateTimeOffset.TryParse(
                timestamp,
                CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeUniversal |
                DateTimeStyles.AdjustToUniversal,
                out var ts))
        {
            return DateTimeOffset.UtcNow - ts <=
                   TimeSpan.FromMinutes(5);
        }

        /*
         * Legacy records without a valid timestamp are not considered live.
         */
        return false;
    }

    // ---------------------------------------------------------------------
    // FIREBASE COLLECTION NORMALIZATION
    // ---------------------------------------------------------------------

    private static IEnumerable<JsonElement> Items(JsonElement? json)
    {
        if (json is not { } root)
            return Enumerable.Empty<JsonElement>();

        /*
         * Firebase REST can represent numeric-keyed collections as:
         *
         * {
         *   "1": {...},
         *   "2": {...}
         * }
         *
         * or:
         *
         * [
         *   {...},
         *   {...}
         * ]
         */

        if (root.ValueKind == JsonValueKind.Object)
        {
            return root
                .EnumerateObject()
                .Where(x =>
                    x.Value.ValueKind ==
                    JsonValueKind.Object)
                .Select(x => x.Value);
        }

        if (root.ValueKind == JsonValueKind.Array)
        {
            return root
                .EnumerateArray()
                .Where(x =>
                    x.ValueKind ==
                    JsonValueKind.Object);
        }

        return Enumerable.Empty<JsonElement>();
    }

    // ---------------------------------------------------------------------
    // PROPERTY HELPERS
    // ---------------------------------------------------------------------

    private static bool TryGetProperty(
        JsonElement e,
        string name,
        out JsonElement property)
    {
        if (e.TryGetProperty(name, out property))
            return true;

        /*
         * Support first-letter casing differences:
         *
         * employeeId
         * EmployeeId
         *
         * isActive
         * IsActive
         */
        if (string.IsNullOrEmpty(name))
        {
            property = default;
            return false;
        }

        var alternate =
            char.IsUpper(name[0])
                ? char.ToLowerInvariant(name[0]) +
                  name[1..]
                : char.ToUpperInvariant(name[0]) +
                  name[1..];

        return e.TryGetProperty(
            alternate,
            out property);
    }

    private static string? String(
        JsonElement e,
        string name)
    {
        if (!TryGetProperty(
                e,
                name,
                out var p))
        {
            return null;
        }

        if (p.ValueKind ==
            JsonValueKind.Null ||
            p.ValueKind ==
            JsonValueKind.Undefined)
        {
            return null;
        }

        /*
         * ToString() safely handles:
         *
         * String
         * Number
         * Boolean
         *
         * without requiring the JSON token to be a
         * particular primitive type.
         */
        return p.ToString();
    }

    private static bool HasValue(
        JsonElement e,
        string name)
    {
        if (!TryGetProperty(
                e,
                name,
                out var p))
        {
            return false;
        }

        if (p.ValueKind ==
            JsonValueKind.Null ||
            p.ValueKind ==
            JsonValueKind.Undefined)
        {
            return false;
        }

        return !string.IsNullOrWhiteSpace(
            p.ToString());
    }

    // ---------------------------------------------------------------------
    // INTEGER HELPER
    // ---------------------------------------------------------------------

    private static int? Int(
        JsonElement e,
        string name)
    {
        if (!TryGetProperty(
                e,
                name,
                out var p) ||
            p.ValueKind ==
                JsonValueKind.Null ||
            p.ValueKind ==
                JsonValueKind.Undefined)
        {
            return null;
        }

        switch (p.ValueKind)
        {
            case JsonValueKind.Number:

                if (p.TryGetInt32(
                        out var numberValue))
                {
                    return numberValue;
                }

                if (p.TryGetInt64(
                        out var longValue) &&
                    longValue >= int.MinValue &&
                    longValue <= int.MaxValue)
                {
                    return (int)longValue;
                }

                if (p.TryGetDouble(
                        out var doubleValue) &&
                    doubleValue >= int.MinValue &&
                    doubleValue <= int.MaxValue)
                {
                    return (int)Math.Round(doubleValue);
                }

                return null;

            case JsonValueKind.String:

                var text =
                    p.GetString()?.Trim();

                if (string.IsNullOrWhiteSpace(text))
                    return null;

                if (int.TryParse(
                        text,
                        NumberStyles.Integer,
                        CultureInfo.InvariantCulture,
                        out var parsedInt))
                {
                    return parsedInt;
                }

                if (long.TryParse(
                        text,
                        NumberStyles.Integer,
                        CultureInfo.InvariantCulture,
                        out var parsedLong) &&
                    parsedLong >= int.MinValue &&
                    parsedLong <= int.MaxValue)
                {
                    return (int)parsedLong;
                }

                if (double.TryParse(
                        text,
                        NumberStyles.Float |
                        NumberStyles.AllowThousands,
                        CultureInfo.InvariantCulture,
                        out var parsedDouble) &&
                    parsedDouble >= int.MinValue &&
                    parsedDouble <= int.MaxValue)
                {
                    return (int)Math.Round(parsedDouble);
                }

                return null;

            case JsonValueKind.True:
                return 1;

            case JsonValueKind.False:
                return 0;

            default:
                return null;
        }
    }

    // ---------------------------------------------------------------------
    // DECIMAL HELPER
    // ---------------------------------------------------------------------

    private static decimal Decimal(
        JsonElement e,
        string name)
    {
        if (!TryGetProperty(
                e,
                name,
                out var p) ||
            p.ValueKind ==
                JsonValueKind.Null ||
            p.ValueKind ==
                JsonValueKind.Undefined)
        {
            return 0m;
        }

        switch (p.ValueKind)
        {
            case JsonValueKind.Number:

                if (p.TryGetDecimal(
                        out var numberValue))
                {
                    return numberValue;
                }

                if (p.TryGetDouble(
                        out var doubleValue))
                {
                    return (decimal)doubleValue;
                }

                return 0m;

            case JsonValueKind.String:

                var text =
                    p.GetString()?.Trim();

                if (string.IsNullOrWhiteSpace(text))
                    return 0m;

                return decimal.TryParse(
                        text,
                        NumberStyles.Any,
                        CultureInfo.InvariantCulture,
                        out var parsed)
                    ? parsed
                    : 0m;

            default:
                return 0m;
        }
    }

    // ---------------------------------------------------------------------
    // BOOLEAN HELPER
    // ---------------------------------------------------------------------

    private static bool Bool(
        JsonElement e,
        string name,
        bool fallback)
    {
        if (!TryGetProperty(
                e,
                name,
                out var p) ||
            p.ValueKind ==
                JsonValueKind.Null ||
            p.ValueKind ==
                JsonValueKind.Undefined)
        {
            return fallback;
        }

        if (p.ValueKind == JsonValueKind.True) return true;
        if (p.ValueKind == JsonValueKind.False) return false;

        var text = p.ToString().Trim();
        if (string.IsNullOrWhiteSpace(text)) return fallback;

        if (bool.TryParse(text, out var b)) return b;

        if (int.TryParse(
                text,
                NumberStyles.Integer,
                CultureInfo.InvariantCulture,
                out var n))
        {
            return n != 0;
        }

        return fallback;
    }

    // ---------------------------------------------------------------------
    // DATE / TIME HELPER
    // ---------------------------------------------------------------------

    private static DateTime? UnixDateTime(
        JsonElement e,
        string name)
    {
        if (!TryGetProperty(
                e,
                name,
                out var p) ||
            p.ValueKind ==
                JsonValueKind.Null ||
            p.ValueKind ==
                JsonValueKind.Undefined)
        {
            return null;
        }

        /*
         * Numeric Unix milliseconds.
         */
        if (p.ValueKind ==
            JsonValueKind.Number)
        {
            if (p.TryGetInt64(
                    out var ms))
            {
                return TimeZoneInfo.ConvertTimeFromUtc(
                    DateTimeOffset.FromUnixTimeMilliseconds(ms).UtcDateTime,
                    s_indiaTimeZone);
            }

            if (p.TryGetDouble(
                    out var numeric))
            {
                return TimeZoneInfo.ConvertTimeFromUtc(
                    DateTimeOffset.FromUnixTimeMilliseconds((long)numeric).UtcDateTime,
                    s_indiaTimeZone);
            }

            return null;
        }

        /*
         * Legacy Firebase may contain Unix milliseconds
         * as a String.
         */
        if (p.ValueKind ==
            JsonValueKind.String)
        {
            var text =
                p.GetString()?.Trim();

            if (string.IsNullOrWhiteSpace(text))
                return null;

            if (long.TryParse(
                    text,
                    NumberStyles.Integer,
                    CultureInfo.InvariantCulture,
                    out var parsedMs))
            {
                return TimeZoneInfo.ConvertTimeFromUtc(
                    DateTimeOffset.FromUnixTimeMilliseconds(parsedMs).UtcDateTime,
                    s_indiaTimeZone);
            }

            if (double.TryParse(
                    text,
                    NumberStyles.Float |
                    NumberStyles.AllowThousands,
                    CultureInfo.InvariantCulture,
                    out var parsedNumeric))
            {
                return TimeZoneInfo.ConvertTimeFromUtc(
                    DateTimeOffset.FromUnixTimeMilliseconds((long)parsedNumeric).UtcDateTime,
                    s_indiaTimeZone);
            }

            /*
             * Also support ordinary ISO date strings.
             */
            if (DateTimeOffset.TryParse(
                    text,
                    CultureInfo.InvariantCulture,
                    DateTimeStyles.AssumeUniversal,
                    out var dto))
            {
                return TimeZoneInfo.ConvertTimeFromUtc(
                    dto.UtcDateTime,
                    s_indiaTimeZone);
            }

            if (DateTime.TryParse(
                    text,
                    CultureInfo.InvariantCulture,
                    DateTimeStyles.AssumeLocal,
                    out var parsedDate))
            {
                return parsedDate;
            }
        }

        return null;
    }

    // ---------------------------------------------------------------------
    // DATE ONLY HELPER
    // ---------------------------------------------------------------------

    private static DateOnly? DateOnlyValue(
        JsonElement e,
        string name)
    {
        var text =
            String(e, name);

        if (DateOnly.TryParseExact(
                text,
                "yyyy-MM-dd",
                CultureInfo.InvariantCulture,
                DateTimeStyles.None,
                out var d))
        {
            return d;
        }

        if (DateOnly.TryParse(
                text,
                CultureInfo.InvariantCulture,
                DateTimeStyles.None,
                out d))
        {
            return d;
        }

        return null;
    }

    // ---------------------------------------------------------------------
    // DURATION HELPER
    // ---------------------------------------------------------------------

    private static long TimeSpanMilliseconds(
        JsonElement e,
        params string[] names)
    {
        foreach (var name in names)
        {
            if (!TryGetProperty(
                    e,
                    name,
                    out var p) ||
                p.ValueKind ==
                    JsonValueKind.Null ||
                p.ValueKind ==
                    JsonValueKind.Undefined)
            {
                continue;
            }

            /*
             * JSON Number:
             *
             * 3600000
             */
            if (p.ValueKind ==
                JsonValueKind.Number)
            {
                if (p.TryGetInt64(
                        out var ms))
                {
                    return ms;
                }

                if (p.TryGetDouble(
                        out var numeric))
                {
                    return (long)numeric;
                }

                continue;
            }

            /*
             * Firebase legacy String:
             *
             * "3600000"
             *
             * or:
             *
             * "01:00:00"
             */
            if (p.ValueKind ==
                JsonValueKind.String)
            {
                var text =
                    p.GetString()?.Trim();

                if (string.IsNullOrWhiteSpace(text))
                    continue;

                if (long.TryParse(
                        text,
                        NumberStyles.Integer,
                        CultureInfo.InvariantCulture,
                        out var parsedMs))
                {
                    return parsedMs;
                }

                if (double.TryParse(
                        text,
                        NumberStyles.Float |
                        NumberStyles.AllowThousands,
                        CultureInfo.InvariantCulture,
                        out var parsedNumeric))
                {
                    return (long)parsedNumeric;
                }

                if (TimeSpan.TryParse(
                        text,
                        CultureInfo.InvariantCulture,
                        out var ts))
                {
                    return (long)ts.TotalMilliseconds;
                }
            }
        }

        return 0;
    }
}

// =====================================================================
// DASHBOARD SNAPSHOT
// =====================================================================

public sealed class FirebaseAdminDashboardSnapshot
{
    public int TotalEmployees { get; init; }

    public int ActiveEmployees { get; init; }

    public int PresentToday { get; init; }

    public int AbsentToday { get; init; }

    public decimal UnpaidAdvanceAmount { get; init; }

    public int PendingPayrolls { get; init; }

    public decimal CurrentPayrollCost { get; init; }

    public decimal PreviousPayrollCost { get; init; }

    public decimal PayrollVariancePercent { get; init; }

    public int ShiftsScheduledToday { get; init; }

    public long TotalMonthScheduledMs { get; init; }

    public int LiveTrackingCount { get; init; }

    public Dictionary<int, string> EmployeeNames { get; init; } = new();

    public List<FirebaseDashboardAdvance> RecentAdvances { get; init; } = new();
}

// =====================================================================
// DASHBOARD ADVANCE
// =====================================================================

public sealed class FirebaseDashboardAdvance
{
    public int EmployeeId { get; init; }

    public decimal Amount { get; init; }

    public DateTime? AdvanceDate { get; init; }

    public string AdvanceType { get; init; } = "Advance";
}