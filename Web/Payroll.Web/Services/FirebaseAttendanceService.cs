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
        if (json is null || (json.Value.ValueKind != JsonValueKind.Object && json.Value.ValueKind != JsonValueKind.Array)) return new();

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

        return result.OrderBy(x => x.ShiftDate).ThenBy(x => x.EmployeeID).ToList();
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

        var json = await _firebase.GetOwnerTableByChildValueAsync(
            OwnerUid, "daily_summaries", "employeeId", employeeId, ct);

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

        return result.OrderBy(x => x.ShiftDate).ToList();
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

        var json = await _firebase.GetOwnerTableAsync(OwnerUid, "attendance_punches", ct);
        if (json is null || (json.Value.ValueKind != JsonValueKind.Object && json.Value.ValueKind != JsonValueKind.Array)) return new();

        var result = new List<AttendanceLog>();
        if (json.Value.ValueKind == JsonValueKind.Object)
        {
            foreach (var item in json.Value.EnumerateObject())
            {
                if (item.Value.ValueKind != JsonValueKind.Object) continue;
                var punch = ParseAttendancePunch(item.Value, item.Name, from, to, employeeId);
                if (punch != null) result.Add(punch);
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
                var punch = ParseAttendancePunch(row, fallbackId, from, to, employeeId);
                if (punch != null) result.Add(punch);
            }
        }

        return result.OrderBy(x => x.PunchTime).ThenBy(x => x.EmployeeID).ToList();
    }

    private static AttendanceLog? ParseAttendancePunch(JsonElement row, string key, DateOnly from, DateOnly to, int? employeeId)
    {
        var emp = Int(row, "staffId", "employeeId", "EmployeeID");
        var timestamp = UnixDateTime(row, "timestamp", "createdAt", "checkInTime");
        if (!emp.HasValue || timestamp is null) return null;
        if (employeeId.HasValue && emp.Value != employeeId.Value) return null;

        var localDate = TimeZoneInfo.ConvertTimeBySystemTimeZoneId(timestamp.Value, GetIndiaTimeZoneId()).Date;
        if (localDate < from.ToDateTime(TimeOnly.MinValue).Date || localDate > to.ToDateTime(TimeOnly.MinValue).Date) return null;

        return new AttendanceLog
        {
            LogID = Int(row, "punchId", "attendanceId", "LogID") ?? IntFromKey(key) ?? 0,
            EmployeeID = emp.Value,
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
        if (employeeId <= 0 || from > to) return new();

        var json = await _firebase.GetOwnerTableByChildValueAsync(
            OwnerUid, "attendance_punches", "staffId", employeeId, ct);

        if (json is null || (json.Value.ValueKind != JsonValueKind.Object && json.Value.ValueKind != JsonValueKind.Array)) return new();

        var result = new List<AttendanceLog>();
        if (json.Value.ValueKind == JsonValueKind.Object)
        {
            foreach (var item in json.Value.EnumerateObject())
            {
                if (item.Value.ValueKind != JsonValueKind.Object) continue;
                var punch = ParseAttendancePunchForEmployee(item.Value, item.Name, employeeId, from, to);
                if (punch != null) result.Add(punch);
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
                var punch = ParseAttendancePunchForEmployee(row, fallbackId, employeeId, from, to);
                if (punch != null) result.Add(punch);
            }
        }

        return result.OrderBy(x => x.PunchTime).ToList();
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
