using System.Globalization;
using System.Text.Json;
using Payroll.Shared;
using Payroll.Shared.Data;

namespace Payroll.Web.Services;

/// <summary>
/// Firebase SSOT read model for employee-scoped history shown by Web employee
/// screens. This service contains no EF/SQL dependency. Existing UI models and
/// business calculations are preserved; only the history read source changes.
/// </summary>
public sealed class FirebaseEmployeeHistoryService
{
    private readonly FirebaseRealtimeService _firebase;
    private readonly IConfiguration _configuration;

    public FirebaseEmployeeHistoryService(FirebaseRealtimeService firebase, IConfiguration configuration)
    {
        _firebase = firebase;
        _configuration = configuration;
    }

    private string OwnerUid =>
        (_configuration["Firebase:OwnerUid"]
         ?? Environment.GetEnvironmentVariable("FIREBASE_OWNER_UID")
         ?? Payroll.Shared.Firebase.FirebaseSsotSchema.DefaultOwnerUid).Trim();

    public async Task<List<SalaryAdvance>> GetAdvancesAsync(int employeeId, CancellationToken ct = default)
    {
        var service = new FirebaseAdvanceService(_firebase, _configuration);
        return await service.GetAsync(employeeId, ct: ct);
    }

    public async Task<List<BonusRecord>> GetBonusesAsync(int employeeId, CancellationToken ct = default)
    {
        var service = new FirebaseBonusService(_firebase, _configuration);
        return await service.GetAsync(employeeId, ct: ct);
    }

    public async Task<List<LeaveRequest>> GetLeaveAsync(int employeeId, CancellationToken ct = default)
    {
        var json = await _firebase.GetOwnerTableAsync(OwnerUid, "leave_requests", ct);
        if (json is null || json.Value.ValueKind != JsonValueKind.Object) return new();

        var result = new List<LeaveRequest>();
        foreach (var item in json.Value.EnumerateObject())
        {
            if (item.Value.ValueKind != JsonValueKind.Object) continue;
            var id = Int(item.Value, "id") ?? Int(item.Value, "leaveRequestId") ?? IntFromKey(item.Name);
            var emp = Int(item.Value, "employeeId") ?? Int(item.Value, "staffId");
            if (id is null || emp != employeeId) continue;
            var leaveDate = UnixDate(item.Value, "startDate") ?? DateTimeFrom(item.Value, "leaveDate");
            var endDate = UnixDate(item.Value, "endDate");
            var status = String(item.Value, "status") ?? "Pending";
            result.Add(new LeaveRequest
            {
                LeaveRequestID = id.Value,
                EmployeeID = employeeId,
                LeaveDate = leaveDate,
                EndDate = endDate,
                LeaveType = String(item.Value, "leaveType") ?? string.Empty,
                IsHalfDay = Bool(item.Value, "isHalfDay") ?? false,
                IsApproved = status.Equals("Approved", StringComparison.OrdinalIgnoreCase),
                Notes = String(item.Value, "reason") ?? String(item.Value, "notes")
            });
        }
        return result.OrderByDescending(x => x.LeaveDate).ToList();
    }

    public async Task<List<PayrollHistory>> GetPayrollAsync(int employeeId, int minimumYear, CancellationToken ct = default)
    {
        var json = await _firebase.GetOwnerTableByChildValueAsync(OwnerUid, "payroll_history", "employeeId", employeeId, ct);
        if (json is null || json.Value.ValueKind != JsonValueKind.Object)
            return new();

        var result = new List<PayrollHistory>();
        foreach (var item in json.Value.EnumerateObject())
        {
            if (item.Value.ValueKind != JsonValueKind.Object) continue;
            var emp = Int(item.Value, "employeeId");
            var year = Int(item.Value, "payYear");

            // Both employeeId and payYear must be present.
            // This guarantees year has a value before using year.Value below.
            if (emp != employeeId ||
                !year.HasValue ||
                year.Value < minimumYear)
            {
                continue;
            }

            result.Add(new PayrollHistory
            {
                PayrollID = Int(item.Value, "payrollId")
                            ?? IntFromKey(item.Name)
                            ?? 0,

                EmployeeID = employeeId,

                PayMonth = Int(item.Value, "payMonth") ?? 0,

                PayYear = year.Value,

                BaseSalary = Decimal(item.Value, "baseSalary"),

                TotalHoursWorked =
                    Decimal(item.Value, "totalHoursWorked"),

                OvertimePay =
                    Decimal(item.Value, "overtimePay"),

                Deductions_Hours =
                    Decimal(item.Value, "deductionsHours"),

                Deductions_Advance =
                    Decimal(item.Value, "deductionsAdvance"),

                Bonus =
                    Decimal(item.Value, "bonus"),

                NetSalary =
                    Decimal(item.Value, "netSalary") ?? 0m,

                ManualLeaveDays =
                    Int(item.Value, "manualLeaveDays") ?? 0,

                AbsentDays =
                    Int(item.Value, "absentDays") ?? 0,

                TotalPenaltyDuration =
                    DurationFromMilliseconds(
                        item.Value,
                        "totalPenaltyMs"),

                TotalOvertimeDuration =
                    DurationFromMilliseconds(
                        item.Value,
                        "totalOvertimeMs"),

                HourlyRate =
                    Decimal(item.Value, "hourlyRate") ?? 0m,

                BasicComponent =
                    Decimal(item.Value, "basicComponent") ?? 0m,

                PfDeduction =
                    Decimal(item.Value, "pfDeduction") ?? 0m,

                EsiDeduction =
                    Decimal(item.Value, "esiDeduction") ?? 0m,

                EmployerPfContribution =
                    Decimal(item.Value, "employerPfContribution") ?? 0m,

                EmployerEsiContribution =
                    Decimal(item.Value, "employerEsiContribution") ?? 0m,

                PtDeduction =
                    Decimal(item.Value, "ptDeduction") ?? 0m,

                TdsDeduction =
                    Decimal(item.Value, "tdsDeduction") ?? 0m,

                TotalShiftAllowance =
                    Decimal(item.Value, "totalShiftAllowance") ?? 0m
            });
        }
        return result.OrderByDescending(x => x.PayYear).ThenByDescending(x => x.PayMonth).ToList();
    }

    public async Task<List<AuditLog>> GetEmployeeAuditAsync(int employeeId, int take = 20, CancellationToken ct = default)
    {
        var json = await _firebase.GetOwnerTableAsync(OwnerUid, "audit_logs", ct);
        if (json is null || json.Value.ValueKind != JsonValueKind.Object) return new();
        var result = new List<AuditLog>();
        foreach (var item in json.Value.EnumerateObject())
        {
            if (item.Value.ValueKind != JsonValueKind.Object) continue;
            var entityId = String(item.Value, "entityId") ?? String(item.Value, "targetId");
            var entityType = String(item.Value, "entityType") ?? string.Empty;
            if (!string.Equals(entityType, "Employee", StringComparison.OrdinalIgnoreCase) || entityId != employeeId.ToString(CultureInfo.InvariantCulture)) continue;
            result.Add(new AuditLog
            {
                LogID = Long(item.Value, "logId") ?? LongFromKey(item.Name),
                Timestamp = DateTimeFrom(item.Value, "timestamp") ?? DateTime.UtcNow,
                UserID = String(item.Value, "userId") ?? "SYSTEM",
                UserEmail = String(item.Value, "userEmail") ?? String(item.Value, "userDisplayName") ?? "System",
                ActionType = String(item.Value, "actionType") ?? String(item.Value, "action") ?? string.Empty,
                EntityType = entityType,
                EntityID = entityId,
                Details = String(item.Value, "details") ?? String(item.Value, "message")
            });
        }
        return result.OrderByDescending(x => x.Timestamp).Take(Math.Max(1, take)).ToList();
    }

    public async Task<List<DailySummary>> GetDailySummariesAsync(int employeeId, DateOnly from, DateOnly to, CancellationToken ct = default)
    {
        var json = await _firebase.GetOwnerTableByChildValueAsync(OwnerUid, "daily_summaries", "employeeId", employeeId, ct);
        if (json is null || json.Value.ValueKind != JsonValueKind.Object) return new();
        var result = new List<DailySummary>();
        foreach (var item in json.Value.EnumerateObject())
        {
            if (item.Value.ValueKind != JsonValueKind.Object) continue;
            var emp = Int(item.Value, "employeeId");
            var date = DateOnlyFrom(item.Value, "shiftDate") ?? DateOnlyFrom(item.Value, "date");
            if (emp != employeeId || date is null || date < from || date > to) continue;
            result.Add(new DailySummary
            {
                SummaryID = Int(item.Value, "summaryId") ?? IntFromKey(item.Name) ?? 0,
                EmployeeID = employeeId,
                ShiftDate = date.Value,
                Status = String(item.Value, "status") ?? "Absent",
                EarnedStandardHours = Decimal(item.Value, "earnedStandardHours") ?? 0m,
                TotalOvertimeDuration = Duration(item.Value, "totalOvertimeMs", "totalOvertimeDuration"),
                TotalPenaltyDuration = Duration(item.Value, "totalPenaltyMs", "totalPenaltyDuration"),
                TotalLateness = Duration(item.Value, "totalLatenessMs", "totalLateness"),
                TotalBreakPenalty = Duration(item.Value, "totalBreakPenaltyMs", "totalBreakPenalty"),
                ScheduledShiftDuration = Duration(item.Value, "scheduledShiftMs", "scheduledShiftDuration"),
                ShiftAllowanceEarned = Decimal(item.Value, "shiftAllowanceEarned") ?? 0m,
                IsManualOverride = Bool(item.Value, "isManualOverride") ?? false
            });
        }
        return result.OrderBy(x => x.ShiftDate).ToList();
    }

    private static bool TryGet(JsonElement e, string name, out JsonElement value) => e.TryGetProperty(name, out value);
    private static string? String(JsonElement e, string name) => TryGet(e, name, out var v) && v.ValueKind != JsonValueKind.Null ? v.ToString() : null;
    private static int? Int(JsonElement e, string name)
    {
        if (!TryGet(e, name, out var v)) return null;
        if (v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out var number)) return number;
        return int.TryParse(v.ToString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var parsed)
            ? (int?)parsed
            : null;
    }

    private static long? Long(JsonElement e, string name)
    {
        if (!TryGet(e, name, out var v)) return null;
        if (v.ValueKind == JsonValueKind.Number && v.TryGetInt64(out var number)) return number;
        return long.TryParse(v.ToString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var parsed)
            ? (long?)parsed
            : null;
    }

    private static decimal? Decimal(JsonElement e, string name)
    {
        if (!TryGet(e, name, out var v)) return null;
        if (v.ValueKind == JsonValueKind.Number && v.TryGetDecimal(out var number)) return number;
        return decimal.TryParse(v.ToString(), NumberStyles.Any, CultureInfo.InvariantCulture, out var parsed)
            ? (decimal?)parsed
            : null;
    }

    private static bool? Bool(JsonElement e, string name)
    {
        if (!TryGet(e, name, out var v)) return null;
        if (v.ValueKind == JsonValueKind.True) return true;
        if (v.ValueKind == JsonValueKind.False) return false;
        return bool.TryParse(v.ToString(), out var parsed)
            ? (bool?)parsed
            : null;
    }
    private static int? IntFromKey(string key) => int.TryParse(key, NumberStyles.Integer, CultureInfo.InvariantCulture, out var n) ? n : null;
    private static long LongFromKey(string key) => long.TryParse(key, NumberStyles.Integer, CultureInfo.InvariantCulture, out var n) ? n : 0L;
    private static DateTime? UnixDate(JsonElement e, string name) { var n = Long(e, name); return n.HasValue && n.Value > 0 ? DateTimeOffset.FromUnixTimeMilliseconds(n.Value).LocalDateTime : null; }
    private static DateTime? DateTimeFrom(JsonElement e, string name) { var s = String(e, name); return DateTime.TryParse(s, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var d) ? d : null; }
    private static DateOnly? DateOnlyFrom(JsonElement e, string name) { var d = UnixDate(e, name); if (d.HasValue) return DateOnly.FromDateTime(d.Value); var s = String(e, name); return DateOnly.TryParse(s, CultureInfo.InvariantCulture, DateTimeStyles.None, out var x) ? x : null; }
    private static TimeSpan DurationFromMilliseconds(JsonElement e, string name)
    {
        var milliseconds = Decimal(e, name) ?? 0m;

        return TimeSpan.FromMilliseconds(
            (double)milliseconds
        );
    }
    private static TimeSpan Duration(
     JsonElement e,
     string millisecondsName,
     string durationName)
    {
        var milliseconds = Decimal(e, millisecondsName);

        if (milliseconds.HasValue)
        {
            return TimeSpan.FromMilliseconds(
                (double)milliseconds.Value
            );
        }

        var text = String(e, durationName);

        return TimeSpan.TryParse(
            text,
            CultureInfo.InvariantCulture,
            out var parsed)
            ? parsed
            : TimeSpan.Zero;
    }
}
