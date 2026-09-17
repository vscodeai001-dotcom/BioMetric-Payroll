using System.Globalization;
using System.Text.Json;
using Payroll.Shared;

namespace Payroll.Web.Services;

/// <summary>
/// Firebase SSOT boundary for Bonus records. The existing BonusRecord model,
/// UI structure and payroll calculations remain unchanged. FirebaseKey is only
/// transport metadata for Firebase records.
/// </summary>
public sealed class FirebaseBonusService
{
    private const string Table = "bonus_records";
    private const string EmployeeTable = "employees";
    private const string FeatureTable = "feature_settings";

    private readonly FirebaseRealtimeService _firebase;
    private readonly IConfiguration _configuration;

    public FirebaseBonusService(FirebaseRealtimeService firebase, IConfiguration configuration)
    {
        _firebase = firebase;
        _configuration = configuration;
    }

    private string OwnerUid =>
        _configuration["Firebase:OwnerUid"]
        ?? Environment.GetEnvironmentVariable("FIREBASE_OWNER_UID")
        ?? "biometricpayroll";

    public async Task<bool> IsEnabledAsync(CancellationToken ct = default)
    {
        var row = await _firebase.GetOwnerRecordAsync(OwnerUid, FeatureTable, "1", ct);
        if (row is null || row.Value.ValueKind != JsonValueKind.Object) return true;
        return Bool(row.Value, "enableBonusManagement")
            ?? Bool(row.Value, "EnableBonusManagement")
            ?? true;
    }

    public async Task<bool> CanEmployeeViewAsync(CancellationToken ct = default)
    {
        var row = await _firebase.GetOwnerRecordAsync(OwnerUid, FeatureTable, "1", ct);
        if (row is null || row.Value.ValueKind != JsonValueKind.Object) return true;
        return Bool(row.Value, "employeeCanViewBonus")
            ?? Bool(row.Value, "EmployeeCanViewBonus")
            ?? true;
    }

    public async Task<List<Employee>> GetActiveEmployeesAsync(CancellationToken ct = default)
    {
        var json = await _firebase.GetOwnerTableAsync(OwnerUid, EmployeeTable, ct);
        if (json is null || json.Value.ValueKind != JsonValueKind.Object) return new();
        var result = new List<Employee>();
        foreach (var item in json.Value.EnumerateObject())
        {
            if (item.Value.ValueKind != JsonValueKind.Object) continue;
            var id = Int(item.Value, "employeeId") ?? IntFromKey(item.Name);
            if (!id.HasValue || id <= 0) continue;
            if (!(Bool(item.Value, "isActive") ?? true)) continue;
            result.Add(new Employee
            {
                EmployeeID = id.Value,
                Name = String(item.Value, "name") ?? "Employee",
                Email = String(item.Value, "email"),
                Role = String(item.Value, "role"),
                IsDeleted = false
            });
        }
        return result.OrderBy(e => e.Name).ToList();
    }

    public async Task<List<BonusRecord>> GetAsync(int employeeId = 0, DateTime? from = null, DateTime? to = null, CancellationToken ct = default)
    {
        var json = employeeId > 0
            ? await _firebase.GetOwnerTableByChildValueAsync(OwnerUid, Table, "employeeId", employeeId, ct)
            : await _firebase.GetOwnerTableAsync(OwnerUid, Table, ct);
        if (json is null || json.Value.ValueKind != JsonValueKind.Object) return new();

        var result = new List<BonusRecord>();
        foreach (var item in json.Value.EnumerateObject())
        {
            if (item.Value.ValueKind != JsonValueKind.Object) continue;
            var row = item.Value;
            var empId = Int(row, "employeeId") ?? Int(row, "EmployeeID");
            if (!empId.HasValue || empId <= 0 || (employeeId > 0 && empId != employeeId)) continue;
            var date = Date(row, "bonusDate") ?? Date(row, "BonusDate");
            if (!date.HasValue) continue;
            if (from.HasValue && date.Value.Date < from.Value.Date) continue;
            if (to.HasValue && date.Value.Date > to.Value.Date) continue;
            var key = item.Name;
            result.Add(new BonusRecord
            {
                BonusID = Int(row, "bonusId") ?? Int(row, "BonusID") ?? IntFromKey(key) ?? StableInt(key),
                EmployeeID = empId.Value,
                BonusDate = date.Value,
                Amount = Decimal(row, "amount") ?? Decimal(row, "Amount") ?? 0m,
                Description = String(row, "description") ?? String(row, "Description"),
                PayrollID_Paid = Int(row, "payrollIdPaid") ?? Int(row, "PayrollID_Paid"),
                FirebaseKey = key
            });
        }
        return result.OrderByDescending(x => x.BonusDate).ThenBy(x => x.EmployeeID).ToList();
    }

    public async Task<bool> SaveAsync(BonusRecord bonus, CancellationToken ct = default)
    {
        if (bonus.EmployeeID <= 0 || bonus.Amount <= 0m) return false;
        var key = !string.IsNullOrWhiteSpace(bonus.FirebaseKey)
            ? bonus.FirebaseKey!
            : bonus.BonusID > 0 ? bonus.BonusID.ToString(CultureInfo.InvariantCulture) : Guid.NewGuid().ToString("N");

        var employee = await _firebase.GetOwnerRecordAsync(OwnerUid, EmployeeTable, bonus.EmployeeID.ToString(CultureInfo.InvariantCulture), ct);
        var shopId = employee.HasValue && employee.Value.ValueKind == JsonValueKind.Object
            ? String(employee.Value, "shopId") ?? String(employee.Value, "ShopId") ?? "" : "";

        var row = new Dictionary<string, object?>
        {
            ["bonusId"] = bonus.BonusID > 0 ? bonus.BonusID : key,
            ["employeeId"] = bonus.EmployeeID,
            ["shopId"] = shopId,
            ["amount"] = bonus.Amount,
            ["bonusDate"] = new DateTimeOffset(bonus.BonusDate).ToUnixTimeMilliseconds(),
            ["description"] = bonus.Description,
            ["payrollIdPaid"] = bonus.PayrollID_Paid,
            ["_entity"] = "BonusRecord",
            ["_key"] = key,
            ["_updatedUtc"] = DateTime.UtcNow.ToString("O", CultureInfo.InvariantCulture)
        };
        var ok = await _firebase.SetOwnerRecordAsync(OwnerUid, Table, key, row, ct);
        if (ok)
        {
            bonus.FirebaseKey = key;
            await _firebase.PublishLocalApplicationChangeAsync(OwnerUid, "BonusRecord", "MODIFIED", key, ct);
        }
        return ok;
    }

    public async Task<bool> DeleteAsync(BonusRecord bonus, CancellationToken ct = default)
    {
        if (bonus.PayrollID_Paid.HasValue) return false;
        var key = bonus.FirebaseKey ?? (bonus.BonusID > 0 ? bonus.BonusID.ToString(CultureInfo.InvariantCulture) : null);
        if (string.IsNullOrWhiteSpace(key)) return false;
        var ok = await _firebase.DeleteOwnerRecordAsync(OwnerUid, Table, key, ct);
        if (ok) await _firebase.PublishLocalApplicationChangeAsync(OwnerUid, "BonusRecord", "DELETED", key, ct);
        return ok;
    }

    private static string? String(JsonElement row, string name) => TryGet(row, name, out var p) ? p.ToString() : null;
    private static int? Int(JsonElement row, string name) => TryGet(row, name, out var p) && (p.ValueKind == JsonValueKind.Number ? p.TryGetInt32(out var n) ? n : null : int.TryParse(p.ToString(), out var v) ? v : null);
    private static int? IntFromKey(string key) => int.TryParse(key, out var v) ? v : null;
    private static decimal? Decimal(JsonElement row, string name) => TryGet(row, name, out var p) && decimal.TryParse(p.ToString(), NumberStyles.Any, CultureInfo.InvariantCulture, out var v) ? v : null;
    private static bool? Bool(JsonElement row, string name) => TryGet(row, name, out var p) ? p.ValueKind == JsonValueKind.True ? true : p.ValueKind == JsonValueKind.False ? false : bool.TryParse(p.ToString(), out var v) ? v : null : null;
    private static DateTime? Date(JsonElement row, string name)
    {
        if (!TryGet(row, name, out var p)) return null;
        if (p.ValueKind == JsonValueKind.Number && p.TryGetInt64(out var ms)) return DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime;
        if (DateTime.TryParse(p.ToString(), CultureInfo.InvariantCulture, DateTimeStyles.AssumeLocal, out var dt)) return dt;
        if (long.TryParse(p.ToString(), out ms)) return DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime;
        return null;
    }
    private static int StableInt(string value) => value.GetHashCode() & int.MaxValue;
    private static bool TryGet(JsonElement row, string name, out JsonElement value)
    {
        if (row.TryGetProperty(name, out value)) return true;
        var pascal = char.ToUpperInvariant(name[0]) + name[1..];
        return row.TryGetProperty(pascal, out value);
    }
}
