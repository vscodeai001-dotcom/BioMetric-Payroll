using System.Globalization;
using System.Text.Json;
using Payroll.Shared.Data;
using Payroll.Shared;

namespace Payroll.Web.Services;

/// <summary>
/// Firebase SSOT read boundary for attendance history.
/// Final calculated DailySummary records and canonical AttendancePunch records
/// are read from Firebase; SQL remains the authoritative mutation/calculation
/// boundary for operations that still require the existing attendance engine.
/// </summary>
public sealed class FirebaseAttendanceService
{
    private readonly FirebaseRealtimeService _firebase;
    private readonly IConfiguration _configuration;

    public FirebaseAttendanceService(FirebaseRealtimeService firebase, IConfiguration configuration)
    {
        _firebase = firebase;
        _configuration = configuration;
    }

    private string OwnerUid =>
        (_configuration["Firebase:OwnerUid"]
         ?? Environment.GetEnvironmentVariable("FIREBASE_OWNER_UID")
         ?? Payroll.Shared.Firebase.FirebaseSsotSchema.DefaultOwnerUid).Trim();

    public async Task<List<DailySummary>> GetDailySummariesAsync(
        DateOnly from,
        DateOnly to,
        CancellationToken ct = default)
    {
        if (from > to) return new();

        var json = await _firebase.GetOwnerTableAsync(OwnerUid, "daily_summaries", ct);
        if (json is null || json.Value.ValueKind != JsonValueKind.Object) return new();

        var result = new List<DailySummary>();
        foreach (var item in json.Value.EnumerateObject())
        {
            if (item.Value.ValueKind != JsonValueKind.Object) continue;
            var emp = Int(item.Value, "employeeId", "EmployeeID", "staffId", "StaffID");
            var date = Date(item.Value, "shiftDate", "ShiftDate", "date", "Date");
            if (!emp.HasValue || date is null || date.Value < from || date.Value > to) continue;

            result.Add(new DailySummary
            {
                SummaryID = Int(item.Value, "summaryId", "SummaryID") ?? IntFromKey(item.Name) ?? 0,
                EmployeeID = emp.Value,
                ShiftDate = date.Value,
                Status = String(item.Value, "status", "Status") ?? "Absent",
                EarnedStandardHours = Decimal(item.Value, "earnedStandardHours", "EarnedStandardHours") ?? 0m,
                TotalOvertimeDuration = Duration(item.Value, "totalOvertimeMs", "totalOvertimeDuration", "TotalOvertimeDuration"),
                TotalPenaltyDuration = Duration(item.Value, "totalPenaltyMs", "totalPenaltyDuration", "TotalPenaltyDuration"),
                TotalLateness = Duration(item.Value, "totalLatenessMs", "totalLateness", "TotalLateness"),
                TotalBreakPenalty = Duration(item.Value, "totalBreakPenaltyMs", "totalBreakPenalty", "TotalBreakPenalty"),
                ScheduledShiftDuration = Duration(item.Value, "scheduledShiftDurationMs", "scheduledShiftDuration", "ScheduledShiftDuration"),
                ShiftAllowanceEarned = Decimal(item.Value, "shiftAllowanceEarned", "ShiftAllowanceEarned") ?? 0m,
                IsManualOverride = Bool(item.Value, "isManualOverride", "IsManualOverride") ?? false
            });
        }

        return result.OrderBy(x => x.ShiftDate).ThenBy(x => x.EmployeeID).ToList();
    }

    public async Task<List<DailySummary>> GetDailySummariesAsync(
        int employeeId,
        DateOnly from,
        DateOnly to,
        CancellationToken ct = default)
    {
        if (employeeId <= 0 || from > to) return new();

        var json = await _firebase.GetOwnerTableByChildValueAsync(
            OwnerUid, "daily_summaries", "employeeId", employeeId, ct);

        if (json is null || json.Value.ValueKind != JsonValueKind.Object) return new();

        var result = new List<DailySummary>();
        foreach (var item in json.Value.EnumerateObject())
        {
            if (item.Value.ValueKind != JsonValueKind.Object) continue;
            var emp = Int(item.Value, "employeeId", "EmployeeID");
            var date = Date(item.Value, "shiftDate", "ShiftDate");
            if (emp != employeeId || date is null || date.Value < from || date.Value > to) continue;

            result.Add(new DailySummary
            {
                SummaryID = Int(item.Value, "summaryId", "SummaryID") ?? IntFromKey(item.Name) ?? 0,
                EmployeeID = employeeId,
                ShiftDate = date.Value,
                Status = String(item.Value, "status", "Status") ?? "Absent",
                EarnedStandardHours = Decimal(item.Value, "earnedStandardHours", "EarnedStandardHours") ?? 0m,
                TotalOvertimeDuration = Duration(item.Value, "totalOvertimeMs", "totalOvertimeDuration", "TotalOvertimeDuration"),
                TotalPenaltyDuration = Duration(item.Value, "totalPenaltyMs", "totalPenaltyDuration", "TotalPenaltyDuration"),
                TotalLateness = Duration(item.Value, "totalLatenessMs", "totalLateness", "TotalLateness"),
                TotalBreakPenalty = Duration(item.Value, "totalBreakPenaltyMs", "totalBreakPenalty", "TotalBreakPenalty"),
                ScheduledShiftDuration = Duration(item.Value, "scheduledShiftDurationMs", "scheduledShiftDuration", "ScheduledShiftDuration"),
                ShiftAllowanceEarned = Decimal(item.Value, "shiftAllowanceEarned", "ShiftAllowanceEarned") ?? 0m,
                IsManualOverride = Bool(item.Value, "isManualOverride", "IsManualOverride") ?? false
            });
        }

        return result.OrderBy(x => x.ShiftDate).ToList();
    }

    public async Task<List<AttendanceLog>> GetAttendancePunchesAsync(
        DateOnly from,
        DateOnly to,
        int? employeeId = null,
        CancellationToken ct = default)
    {
        if (from > to) return new();

        var json = await _firebase.GetOwnerTableAsync(OwnerUid, "attendance_punches", ct);
        if (json is null || json.Value.ValueKind != JsonValueKind.Object) return new();

        var result = new List<AttendanceLog>();
        foreach (var item in json.Value.EnumerateObject())
        {
            if (item.Value.ValueKind != JsonValueKind.Object) continue;
            var emp = Int(item.Value, "staffId", "employeeId", "EmployeeID");
            var timestamp = UnixDateTime(item.Value, "timestamp", "createdAt", "checkInTime");
            if (!emp.HasValue || timestamp is null) continue;
            if (employeeId.HasValue && emp.Value != employeeId.Value) continue;

            var localDate = TimeZoneInfo.ConvertTimeBySystemTimeZoneId(timestamp.Value, "Asia/Kolkata").Date;
            if (localDate < from.ToDateTime(TimeOnly.MinValue).Date || localDate > to.ToDateTime(TimeOnly.MinValue).Date) continue;

            result.Add(new AttendanceLog
            {
                LogID = Int(item.Value, "punchId", "attendanceId", "LogID") ?? IntFromKey(item.Name) ?? 0,
                EmployeeID = emp.Value,
                BiometricID = String(item.Value, "biometricId", "BiometricID") ?? string.Empty,
                PunchTime = timestamp.Value,
                DeviceID = String(item.Value, "deviceId", "DeviceID"),
                LogType = String(item.Value, "type", "source", "note", "LogType"),
                IsApproved = !string.Equals(String(item.Value, "status"), "REJECTED", StringComparison.OrdinalIgnoreCase),
                Latitude = Double(item.Value, "latitude", "Latitude"),
                Longitude = Double(item.Value, "longitude", "Longitude")
            });
        }

        return result.OrderBy(x => x.PunchTime).ThenBy(x => x.EmployeeID).ToList();
    }

    public async Task<List<AttendanceLog>> GetAttendancePunchesAsync(
        int employeeId,
        DateOnly from,
        DateOnly to,
        CancellationToken ct = default)
    {
        if (employeeId <= 0 || from > to) return new();

        var json = await _firebase.GetOwnerTableByChildValueAsync(
            OwnerUid, "attendance_punches", "staffId", employeeId, ct);

        if (json is null || json.Value.ValueKind != JsonValueKind.Object) return new();

        var result = new List<AttendanceLog>();
        foreach (var item in json.Value.EnumerateObject())
        {
            if (item.Value.ValueKind != JsonValueKind.Object) continue;
            var emp = Int(item.Value, "staffId", "employeeId", "EmployeeID");
            var timestamp = UnixDateTime(item.Value, "timestamp", "createdAt", "checkInTime");
            if (emp != employeeId || timestamp is null) continue;
            var localDate = TimeZoneInfo.ConvertTimeBySystemTimeZoneId(timestamp.Value, "Asia/Kolkata").Date;
            if (localDate < from.ToDateTime(TimeOnly.MinValue).Date || localDate > to.ToDateTime(TimeOnly.MinValue).Date) continue;

            result.Add(new AttendanceLog
            {
                LogID = Int(item.Value, "punchId", "attendanceId", "LogID") ?? IntFromKey(item.Name) ?? 0,
                EmployeeID = employeeId,
                BiometricID = String(item.Value, "biometricId", "BiometricID") ?? string.Empty,
                PunchTime = timestamp.Value,
                DeviceID = String(item.Value, "deviceId", "DeviceID"),
                LogType = String(item.Value, "type", "source", "note", "LogType"),
                IsApproved = !string.Equals(String(item.Value, "status"), "REJECTED", StringComparison.OrdinalIgnoreCase),
                Latitude = Double(item.Value, "latitude", "Latitude"),
                Longitude = Double(item.Value, "longitude", "Longitude")
            });
        }

        return result.OrderBy(x => x.PunchTime).ToList();
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
        if (value is null) return null;
        if (value.Value.TryGetInt32(out var i)) return i;
        return int.TryParse(value.Value.ToString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out i) ? i : null;
    }

    private static int? IntFromKey(string key) => int.TryParse(key, NumberStyles.Integer, CultureInfo.InvariantCulture, out var i) ? i : null;

    private static decimal? Decimal(JsonElement element, params string[] names)
    {
        var value = Raw(element, names);
        if (value is null) return null;
        if (value.Value.TryGetDecimal(out var d)) return d;
        return decimal.TryParse(value.Value.ToString(), NumberStyles.Any, CultureInfo.InvariantCulture, out d) ? d : null;
    }

    private static double? Double(JsonElement element, params string[] names)
    {
        var value = Raw(element, names);
        if (value is null) return null;
        if (value.Value.TryGetDouble(out var d)) return d;
        return double.TryParse(value.Value.ToString(), NumberStyles.Any, CultureInfo.InvariantCulture, out d) ? d : null;
    }

    private static bool? Bool(JsonElement element, params string[] names)
    {
        var value = Raw(element, names);
        if (value is null) return null;
        if (value.Value.ValueKind is JsonValueKind.True or JsonValueKind.False) return value.Value.GetBoolean();
        return bool.TryParse(value.Value.ToString(), out var b) ? b : null;
    }

    private static DateOnly? Date(JsonElement element, params string[] names)
    {
        var value = Raw(element, names);
        if (value is null) return null;
        var text = value.Value.ToString();
        if (DateOnly.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.None, out var d)) return d;
        if (value.Value.TryGetInt64(out var ms))
            return DateTimeOffset.FromUnixTimeMilliseconds(ms).ToOffset(TimeSpan.FromHours(5.5)).Date;
        return null;
    }

    private static DateTime? UnixDateTime(JsonElement element, params string[] names)
    {
        var value = Raw(element, names);
        if (value is null) return null;
        if (value.Value.TryGetInt64(out var ms)) return DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime;
        var text = value.Value.ToString();
        if (DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var dto)) return dto.LocalDateTime;
        return DateTime.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.AssumeLocal, out var dt) ? dt : null;
    }

    private static TimeSpan Duration(JsonElement element, params string[] names)
    {
        var value = Raw(element, names);
        if (value is null) return TimeSpan.Zero;
        if (value.Value.TryGetDouble(out var number)) return TimeSpan.FromMilliseconds(number);
        var text = value.Value.ToString();
        if (TimeSpan.TryParse(text, CultureInfo.InvariantCulture, out var ts)) return ts;
        return double.TryParse(text, NumberStyles.Any, CultureInfo.InvariantCulture, out var ms)
            ? TimeSpan.FromMilliseconds(ms)
            : TimeSpan.Zero;
    }
}
