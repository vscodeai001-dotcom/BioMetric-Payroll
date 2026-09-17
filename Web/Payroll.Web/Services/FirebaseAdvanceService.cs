using System.Globalization;
using System.Text.Json;
using Payroll.Shared;
using Payroll.Shared.Data;

namespace Payroll.Web.Services;

/// <summary>
/// Firebase SSOT boundary for Salary Advances.
/// Existing SalaryAdvance model, screens and payroll calculations remain intact.
/// FirebaseKey is only the transport key for records created by Android using UUIDs.
/// </summary>
public sealed class FirebaseAdvanceService
{
    private const string Table = "advance_payments";
    private const string EmployeeTable = "employees";
    private const string FeatureTable = "feature_settings";

    private readonly FirebaseRealtimeService _firebase;
    private readonly IConfiguration _configuration;

    public FirebaseAdvanceService(FirebaseRealtimeService firebase, IConfiguration configuration)
    {
        _firebase = firebase;
        _configuration = configuration;
    }

    private string OwnerUid =>
        _configuration["Firebase:OwnerUid"]
        ?? Environment.GetEnvironmentVariable("FIREBASE_OWNER_UID")
        ?? "biometricpayroll";

    private static bool IsAdmin(System.Security.Claims.ClaimsPrincipal user) =>
        user.IsInRole("Admin") || user.IsInRole("SuperAdmin") ||
        user.IsInRole("ADMIN") || user.IsInRole("SUPER_ADMIN");

    public async Task<bool> IsEnabledAsync(CancellationToken ct = default)
    {
        var row = await _firebase.GetOwnerRecordAsync(OwnerUid, FeatureTable, "1", ct);
        if (row is null || row.Value.ValueKind != JsonValueKind.Object) return true;
        return Bool(row.Value, "enableSalaryAdvance")
            ?? Bool(row.Value, "EnableSalaryAdvance")
            ?? true;
    }

    public async Task<bool> CanEmployeeViewAsync(CancellationToken ct = default)
    {
        var row = await _firebase.GetOwnerRecordAsync(OwnerUid, FeatureTable, "1", ct);
        if (row is null || row.Value.ValueKind != JsonValueKind.Object) return true;
        return Bool(row.Value, "employeeCanViewAdvance")
            ?? Bool(row.Value, "EmployeeCanViewAdvance")
            ?? true;
    }

    public async Task<List<Employee>> GetActiveEmployeesAsync(CancellationToken ct = default)
    {
        var json = await _firebase.GetOwnerTableAsync(OwnerUid, EmployeeTable, ct);
        if (json is null || json.Value.ValueKind != JsonValueKind.Object) return new();

        var result = new List<Employee>();
        foreach (var item in json.Value.EnumerateObject())
        {
            var row = item.Value;
            if (row.ValueKind != JsonValueKind.Object) continue;
            var id = Int(row, "employeeId") ?? IntFromKey(item.Name);
            if (!id.HasValue || id <= 0) continue;
            if (!(Bool(row, "isActive") ?? true)) continue;
            result.Add(new Employee
            {
                EmployeeID = id.Value,
                Name = String(row, "name") ?? "Employee",
                Email = String(row, "email"),
                Role = String(row, "role"),
                IsDeleted = false
            });
        }
        return result.OrderBy(e => e.Name).ToList();
    }

    public async Task<List<SalaryAdvance>> GetAsync(
        int employeeId = 0,
        DateTime? from = null,
        DateTime? to = null,
        bool unpaidOnly = false,
        CancellationToken ct = default)
    {
        var json = employeeId > 0
            ? await _firebase.GetOwnerTableByChildValueAsync(OwnerUid, Table, "employeeId", employeeId, ct)
            : await _firebase.GetOwnerTableAsync(OwnerUid, Table, ct);
        if (json is null || json.Value.ValueKind != JsonValueKind.Object) return new();

        var result = new List<SalaryAdvance>();
        foreach (var item in json.Value.EnumerateObject())
        {
            if (item.Value.ValueKind != JsonValueKind.Object) continue;
            var row = item.Value;
            var empId = Int(row, "employeeId") ?? Int(row, "EmployeeId");
            if (!empId.HasValue || empId <= 0) continue;
            if (employeeId > 0 && empId != employeeId) continue;

            var date = Date(row, "date") ?? Date(row, "advanceDate");
            if (from.HasValue && (!date.HasValue || date.Value.Date < from.Value.Date)) continue;
            if (to.HasValue && (!date.HasValue || date.Value.Date > to.Value.Date)) continue;

            var recovered = Bool(row, "isRecovered") ?? Bool(row, "IsRecovered") ?? false;
            var payrollId = Int(row, "payrollIdPaid") ?? Int(row, "PayrollID_Paid");
            if (unpaidOnly && (recovered || payrollId.HasValue)) continue;

            var key = item.Name;
            var numericId = Int(row, "advanceId") ?? Int(row, "AdvanceID") ?? IntFromKey(key) ?? StableInt(key);
            result.Add(new SalaryAdvance
            {
                AdvanceID = numericId,
                EmployeeID = empId.Value,
                AdvanceDate = date,
                Amount = Decimal(row, "amount") ?? Decimal(row, "Amount") ?? 0m,
                AdvanceType = String(row, "advanceType") ?? String(row, "AdvanceType") ?? String(row, "type") ?? "General",
                PayrollID_Paid = payrollId,
                FirebaseKey = key
            });
        }

        return result.OrderByDescending(x => x.AdvanceDate).ThenBy(x => x.EmployeeID).ToList();
    }

    public async Task<bool> SaveAsync(SalaryAdvance advance, CancellationToken ct = default)
    {
        if (advance.EmployeeID <= 0 || advance.Amount <= 0m || !advance.AdvanceDate.HasValue) return false;

        var key = !string.IsNullOrWhiteSpace(advance.FirebaseKey)
            ? advance.FirebaseKey!
            : advance.AdvanceID > 0
                ? advance.AdvanceID.ToString(CultureInfo.InvariantCulture)
                : Guid.NewGuid().ToString("N");

        var employee = await _firebase.GetOwnerRecordAsync(OwnerUid, EmployeeTable, advance.EmployeeID.ToString(CultureInfo.InvariantCulture), ct);
        var shopId = employee.HasValue && employee.Value.ValueKind == JsonValueKind.Object
            ? String(employee.Value, "shopId") ?? String(employee.Value, "ShopId") ?? ""
            : "";

        var row = new Dictionary<string, object?>
        {
            ["advanceId"] = advance.AdvanceID > 0 ? advance.AdvanceID : key,
            ["employeeId"] = advance.EmployeeID,
            ["shopId"] = shopId,
            ["amount"] = advance.Amount,
            ["date"] = new DateTimeOffset(advance.AdvanceDate.Value).ToUnixTimeMilliseconds(),
            ["isRecovered"] = advance.PayrollID_Paid.HasValue,
            ["recoveryPaymentId"] = advance.PayrollID_Paid?.ToString(CultureInfo.InvariantCulture),
            ["advanceType"] = advance.AdvanceType ?? "General",
            ["payrollIdPaid"] = advance.PayrollID_Paid,
            ["_entity"] = "SalaryAdvance",
            ["_key"] = key,
            ["_updatedUtc"] = DateTime.UtcNow.ToString("O", CultureInfo.InvariantCulture)
        };

        var ok = await _firebase.SetOwnerRecordAsync(OwnerUid, Table, key, row, ct);
        if (ok)
        {
            advance.FirebaseKey = key;
            await _firebase.PublishLocalApplicationChangeAsync(OwnerUid, "AdvancePayment", "MODIFIED", key, ct);
        }
        return ok;
    }

    public async Task<bool> DeleteAsync(SalaryAdvance advance, CancellationToken ct = default)
    {
        if (advance.PayrollID_Paid.HasValue) return false;
        var key = advance.FirebaseKey ?? (advance.AdvanceID > 0 ? advance.AdvanceID.ToString(CultureInfo.InvariantCulture) : null);
        if (string.IsNullOrWhiteSpace(key)) return false;
        var ok = await _firebase.DeleteOwnerRecordAsync(OwnerUid, Table, key, ct);
        if (ok) await _firebase.PublishLocalApplicationChangeAsync(OwnerUid, "AdvancePayment", "DELETED", key, ct);
        return ok;
    }

    public async Task<SalaryAdvance?> GetEmployeeAdvanceAsync(int employeeId, string firebaseKey, CancellationToken ct = default)
    {
        if (employeeId <= 0 || string.IsNullOrWhiteSpace(firebaseKey)) return null;
        var row = await _firebase.GetOwnerRecordAsync(OwnerUid, Table, firebaseKey, ct);
        if (row is null || row.Value.ValueKind != JsonValueKind.Object) return null;
        var emp = Int(row.Value, "employeeId") ?? Int(row.Value, "EmployeeId");
        if (emp != employeeId) return null;
        return (await GetAsync(employeeId, ct: ct)).FirstOrDefault(x => x.FirebaseKey == firebaseKey);
    }

    private static string? String(JsonElement row, string name)
    {
        if (!TryGet(row, name, out var value))
            return null;

        if (value.ValueKind == JsonValueKind.Null ||
            value.ValueKind == JsonValueKind.Undefined)
            return null;

        return value.ToString();
    }

    private static int? Int(JsonElement row, string name)
    {
        if (!TryGet(row, name, out var value))
            return null;

        if (value.ValueKind == JsonValueKind.Number)
        {
            if (value.TryGetInt32(out var number))
                return number;

            return null;
        }

        var text = value.ToString();

        if (int.TryParse(
                text,
                NumberStyles.Integer,
                CultureInfo.InvariantCulture,
                out var parsed))
        {
            return parsed;
        }

        return null;
    }

    private static int? IntFromKey(string key)
    {
        if (int.TryParse(
                key,
                NumberStyles.Integer,
                CultureInfo.InvariantCulture,
                out var value))
        {
            return value;
        }

        return null;
    }

    private static decimal? Decimal(JsonElement row, string name)
    {
        if (!TryGet(row, name, out var value))
            return null;

        if (value.ValueKind == JsonValueKind.Number &&
            value.TryGetDecimal(out var number))
        {
            return number;
        }

        var text = value.ToString();

        if (decimal.TryParse(
                text,
                NumberStyles.Any,
                CultureInfo.InvariantCulture,
                out var parsed))
        {
            return parsed;
        }

        return null;
    }

    private static bool? Bool(JsonElement row, string name)
    {
        if (!TryGet(row, name, out var value))
            return null;

        if (value.ValueKind == JsonValueKind.True)
            return true;

        if (value.ValueKind == JsonValueKind.False)
            return false;

        var text = value.ToString();

        if (bool.TryParse(text, out var parsed))
            return parsed;

        return null;
    }

    private static DateTime? Date(JsonElement row, string name)
    {
        if (!TryGet(row, name, out var value))
            return null;

        if (value.ValueKind == JsonValueKind.Number)
        {
            if (value.TryGetInt64(out var milliseconds))
            {
                try
                {
                    return DateTimeOffset
                        .FromUnixTimeMilliseconds(milliseconds)
                        .LocalDateTime;
                }
                catch (ArgumentOutOfRangeException)
                {
                    return null;
                }
            }

            return null;
        }

        var text = value.ToString();

        if (DateTime.TryParse(
                text,
                CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeLocal,
                out var dateTime))
        {
            return dateTime;
        }

        if (long.TryParse(
                text,
                NumberStyles.Integer,
                CultureInfo.InvariantCulture,
                out var millisecondsFromText))
        {
            try
            {
                return DateTimeOffset
                    .FromUnixTimeMilliseconds(millisecondsFromText)
                    .LocalDateTime;
            }
            catch (ArgumentOutOfRangeException)
            {
                return null;
            }
        }

        return null;
    }

    private static int StableInt(string value)
    {
        unchecked
        {
            var hash = 23;

            foreach (var character in value)
                hash = hash * 31 + character;

            hash &= int.MaxValue;

            return hash == 0 ? 1 : hash;
        }
    }

    private static bool TryGet(
        JsonElement row,
        string name,
        out JsonElement value)
    {
        if (row.TryGetProperty(name, out value))
            return true;

        if (string.IsNullOrEmpty(name))
        {
            value = default;
            return false;
        }

        var pascal =
            char.ToUpperInvariant(name[0]) +
            name[1..];

        return row.TryGetProperty(pascal, out value);
    }
}