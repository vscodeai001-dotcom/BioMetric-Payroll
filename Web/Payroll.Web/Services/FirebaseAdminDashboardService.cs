using System.Globalization;
using System.Text.Json;

namespace Payroll.Web.Services;

/// <summary>
/// Firebase-backed source for the Admin Dashboard. This service intentionally
/// aggregates only already-published SSOT records. It does not recalculate
/// payroll or attendance business rules.
/// </summary>
public sealed class FirebaseAdminDashboardService
{
    private readonly FirebaseRealtimeService _firebase;
    private readonly ILogger<FirebaseAdminDashboardService> _logger;

    public FirebaseAdminDashboardService(
        FirebaseRealtimeService firebase,
        ILogger<FirebaseAdminDashboardService> logger)
    {
        _firebase = firebase;
        _logger = logger;
    }

    public async Task<FirebaseAdminDashboardSnapshot> GetSnapshotAsync(
        string actorUid,
        CancellationToken cancellationToken = default)
    {
        var ownerUid = _firebase.ResolveOwnerUid(actorUid, "Admin");

        var results = await Task.WhenAll(
            _firebase.GetOwnerTableAsync(ownerUid, "employees", cancellationToken),
            _firebase.GetOwnerTableAsync(ownerUid, "attendance", cancellationToken),
            _firebase.GetOwnerTableAsync(ownerUid, "advance_payments", cancellationToken),
            _firebase.GetOwnerTableAsync(ownerUid, "payroll_history", cancellationToken),
            _firebase.GetOwnerTableAsync(ownerUid, "shift_schedules", cancellationToken),
            _firebase.GetOwnerTableAsync(ownerUid, "daily_summaries", cancellationToken),
            _firebase.GetOwnerTrackingLiveAsync(ownerUid, cancellationToken));

        try
        {
            var employees = Items(results[0]).ToList();
            var attendance = Items(results[1]).ToList();
            var advances = Items(results[2]).ToList();
            var payroll = Items(results[3]).ToList();
            var shifts = Items(results[4]).ToList();
            var summaries = Items(results[5]).ToList();
            var tracking = Items(results[6]).ToList();

            var now = DateTime.Now;
            var today = DateOnly.FromDateTime(now);
            var activeIds = employees
                .Where(e => Bool(e, "isActive", true))
                .Select(e => Int(e, "employeeId"))
                .Where(id => id.HasValue)
                .Select(id => id!.Value)
                .ToHashSet();

            var employeeNames = employees
                .Select(e => (Id: Int(e, "employeeId"), Name: String(e, "name")))
                .Where(x => x.Id.HasValue)
                .GroupBy(x => x.Id!.Value)
                .ToDictionary(
                    g => g.Key,
                    g => string.IsNullOrWhiteSpace(g.Last().Name) ? $"ID:{g.Key}" : g.Last().Name!);

            var presentIds = attendance
                .Where(a =>
                    activeIds.Contains(Int(a, "employeeId") ?? int.MinValue) &&
                    UnixDateTime(a, "checkInTime") is DateTime dt &&
                    dt >= now.Date && dt < now.Date.AddDays(1))
                .Select(a => Int(a, "employeeId"))
                .Where(id => id.HasValue)
                .Select(id => id!.Value)
                .ToHashSet();

            var unpaid = advances
                .Where(a => !Bool(a, "isRecovered", false) && !HasValue(a, "recoveryPaymentId"))
                .Select(a => new FirebaseDashboardAdvance
                {
                    EmployeeId = Int(a, "employeeId") ?? 0,
                    Amount = Decimal(a, "amount"),
                    AdvanceDate = UnixDateTime(a, "date"),
                    AdvanceType = String(a, "advanceType") ?? "Advance"
                })
                .OrderByDescending(a => a.AdvanceDate ?? DateTime.MinValue)
                .ToList();

            var target = now.AddMonths(-1);
            var previous = now.AddMonths(-2);
            var currentPayrollCost = payroll
                .Where(p => Int(p, "payMonth") == target.Month && Int(p, "payYear") == target.Year)
                .Sum(p => Decimal(p, "netSalary"));
            var previousPayrollCost = payroll
                .Where(p => Int(p, "payMonth") == previous.Month && Int(p, "payYear") == previous.Year)
                .Sum(p => Decimal(p, "netSalary"));
            var variance = previousPayrollCost == 0m
                ? 100m
                : ((currentPayrollCost - previousPayrollCost) / previousPayrollCost) * 100m;

            var shiftsToday = shifts.Count(s => String(s, "shiftDate") == today.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture));
            var scheduledMs = summaries
                .Where(s => DateOnlyValue(s, "shiftDate") is DateOnly d && d.Year == today.Year && d.Month == today.Month)
                .Sum(s => TimeSpanMilliseconds(s, "scheduledShiftDuration", "scheduledShiftDurationMs"));

            var hasLastMonthPayroll = payroll.Any(p => Int(p, "payMonth") == target.Month && Int(p, "payYear") == target.Year);

            return new FirebaseAdminDashboardSnapshot
            {
                TotalEmployees = employees.Count,
                ActiveEmployees = activeIds.Count,
                PresentToday = presentIds.Count,
                AbsentToday = Math.Max(0, activeIds.Count - presentIds.Count),
                UnpaidAdvanceAmount = unpaid.Sum(x => x.Amount),
                RecentAdvances = unpaid.Take(4).ToList(),
                EmployeeNames = employeeNames,
                PendingPayrolls = hasLastMonthPayroll ? 0 : 1,
                CurrentPayrollCost = currentPayrollCost,
                PreviousPayrollCost = previousPayrollCost,
                PayrollVariancePercent = variance,
                ShiftsScheduledToday = shiftsToday,
                TotalMonthScheduledMs = scheduledMs,
                LiveTrackingCount = tracking.Count
            };
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Firebase Admin Dashboard aggregation failed for owner {OwnerUid}", ownerUid);
            throw;
        }
    }

    private static IEnumerable<JsonElement> Items(JsonElement? json)
    {
        if (json is not { } root)
            return Enumerable.Empty<JsonElement>();

        // Realtime Database REST can represent numeric-keyed collections as
        // either objects or arrays. Treat both forms as records.
        if (root.ValueKind == JsonValueKind.Object)
        {
            return root.EnumerateObject()
                .Where(x => x.Value.ValueKind == JsonValueKind.Object)
                .Select(x => x.Value);
        }

        if (root.ValueKind == JsonValueKind.Array)
        {
            return root.EnumerateArray()
                .Where(x => x.ValueKind == JsonValueKind.Object);
        }

        return Enumerable.Empty<JsonElement>();
    }

    private static bool TryGetProperty(
        JsonElement e,
        string name,
        out JsonElement property)
    {
        if (e.TryGetProperty(name, out property))
            return true;

        var alternate = char.IsUpper(name[0])
            ? char.ToLowerInvariant(name[0]) + name[1..]
            : char.ToUpperInvariant(name[0]) + name[1..];

        return e.TryGetProperty(alternate, out property);
    }

    private static string? String(JsonElement e, string name)
        => TryGetProperty(e, name, out var p) &&
           p.ValueKind != JsonValueKind.Null
            ? p.ToString()
            : null;

    private static bool HasValue(JsonElement e, string name)
        => TryGetProperty(e, name, out var p) &&
           p.ValueKind != JsonValueKind.Null &&
           p.ToString() != "";

    private static int? Int(JsonElement e, string name)
    {
        if (!TryGetProperty(e, name, out var p) ||
            p.ValueKind == JsonValueKind.Null)
            return null;

        if (p.TryGetInt32(out var i))
            return i;

        return int.TryParse(
            p.ToString(),
            NumberStyles.Integer,
            CultureInfo.InvariantCulture,
            out i)
            ? i
            : null;
    }

    private static decimal Decimal(JsonElement e, string name)
    {
        if (!TryGetProperty(e, name, out var p) ||
            p.ValueKind == JsonValueKind.Null)
            return 0m;

        if (p.TryGetDecimal(out var d))
            return d;

        return decimal.TryParse(
            p.ToString(),
            NumberStyles.Any,
            CultureInfo.InvariantCulture,
            out d)
            ? d
            : 0m;
    }

    private static bool Bool(JsonElement e, string name, bool fallback)
    {
        if (!TryGetProperty(e, name, out var p) ||
            p.ValueKind == JsonValueKind.Null)
            return fallback;
        if (p.ValueKind == JsonValueKind.True) return true;
        if (p.ValueKind == JsonValueKind.False) return false;
        return bool.TryParse(p.ToString(), out var b) ? b : fallback;
    }

    private static DateTime? UnixDateTime(JsonElement e, string name)
    {
        if (!TryGetProperty(e, name, out var p) || p.ValueKind == JsonValueKind.Null) return null;
        if (p.TryGetInt64(out var ms)) return DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime;
        if (long.TryParse(p.ToString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out ms))
            return DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime;
        if (DateTime.TryParse(p.ToString(), CultureInfo.InvariantCulture, DateTimeStyles.AssumeLocal, out var dt)) return dt;
        return null;
    }

    private static DateOnly? DateOnlyValue(JsonElement e, string name)
    {
        var text = String(e, name);
        if (DateOnly.TryParseExact(text, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var d)) return d;
        return DateOnly.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.None, out d) ? d : null;
    }

    private static long TimeSpanMilliseconds(JsonElement e, params string[] names)
    {
        foreach (var name in names)
        {
            if (!TryGetProperty(e, name, out var p) || p.ValueKind == JsonValueKind.Null) continue;
            if (p.TryGetInt64(out var ms)) return ms;
            if (p.TryGetDouble(out var numeric)) return (long)numeric;
            if (TimeSpan.TryParse(p.ToString(), CultureInfo.InvariantCulture, out var ts)) return (long)ts.TotalMilliseconds;
        }
        return 0;
    }
}

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

public sealed class FirebaseDashboardAdvance
{
    public int EmployeeId { get; init; }
    public decimal Amount { get; init; }
    public DateTime? AdvanceDate { get; init; }
    public string AdvanceType { get; init; } = "Advance";
}
