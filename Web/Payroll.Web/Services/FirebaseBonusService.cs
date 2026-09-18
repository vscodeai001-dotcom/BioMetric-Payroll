using System.Globalization;
using System.Text.Json;
using Payroll.Shared;
using Payroll.Shared.Data;

namespace Payroll.Web.Services;

/// <summary>
/// Firebase SSOT boundary for Bonus records.
///
/// The existing BonusRecord model, UI structure and payroll calculations
/// remain unchanged. FirebaseKey is transport metadata used only for
/// Firebase records.
/// </summary>
public sealed class FirebaseBonusService
{
    private const string Table = "bonus_records";
    private const string EmployeeTable = "employees";
    private const string FeatureTable = "feature_settings";

    private readonly FirebaseRealtimeService _firebase;
    private readonly IConfiguration _configuration;

    public FirebaseBonusService(
        FirebaseRealtimeService firebase,
        IConfiguration configuration)
    {
        _firebase = firebase;
        _configuration = configuration;
    }

    private string OwnerUid =>
        _configuration["Firebase:OwnerUid"]
        ?? Environment.GetEnvironmentVariable("FIREBASE_OWNER_UID")
        ?? "biometricpayroll";

    public async Task<bool> IsEnabledAsync(
        CancellationToken ct = default)
    {
        var row = await _firebase.GetOwnerRecordAsync(
            OwnerUid,
            FeatureTable,
            "1",
            ct);

        if (row is null ||
            row.Value.ValueKind != JsonValueKind.Object)
        {
            return true;
        }

        return Bool(row.Value, "enableBonusManagement")
            ?? Bool(row.Value, "EnableBonusManagement")
            ?? true;
    }

    public async Task<bool> CanEmployeeViewAsync(
        CancellationToken ct = default)
    {
        var row = await _firebase.GetOwnerRecordAsync(
            OwnerUid,
            FeatureTable,
            "1",
            ct);

        if (row is null ||
            row.Value.ValueKind != JsonValueKind.Object)
        {
            return true;
        }

        return Bool(row.Value, "employeeCanViewBonus")
            ?? Bool(row.Value, "EmployeeCanViewBonus")
            ?? true;
    }

    public async Task<List<Employee>> GetActiveEmployeesAsync(
        CancellationToken ct = default)
    {
        var json = await _firebase.GetOwnerTableAsync(
            OwnerUid,
            EmployeeTable,
            ct);

        if (json is null || (json.Value.ValueKind != JsonValueKind.Object && json.Value.ValueKind != JsonValueKind.Array))
        {
            return new List<Employee>();
        }

        var result = new List<Employee>();

        if (json.Value.ValueKind == JsonValueKind.Object)
        {
            foreach (var item in json.Value.EnumerateObject())
            {
                if (item.Value.ValueKind != JsonValueKind.Object) continue;
                var employee = ParseActiveEmployee(item.Value, item.Name);
                if (employee != null) result.Add(employee);
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
                var employee = ParseActiveEmployee(row, fallbackId);
                if (employee != null) result.Add(employee);
            }
        }

        return result
            .OrderBy(e => e.Name)
            .ToList();
    }

    private static Employee? ParseActiveEmployee(JsonElement row, string key)
    {
        var id =
            Int(row, "employeeId")
            ?? Int(row, "EmployeeID")
            ?? IntFromKey(key);

        if (!id.HasValue || id.Value <= 0) return null;

        var isActive =
            Bool(row, "isActive")
            ?? Bool(row, "IsActive")
            ?? true;

        if (!isActive) return null;

        return new Employee
        {
            EmployeeID = id.Value,
            Name =
                String(row, "name")
                ?? String(row, "Name")
                ?? "Employee",

            Email =
                String(row, "email")
                ?? String(row, "Email"),

            Role =
                String(row, "role")
                ?? String(row, "Role"),

            IsDeleted = false
        };
    }

    public async Task<List<BonusRecord>> GetAsync(
        int employeeId = 0,
        DateTime? from = null,
        DateTime? to = null,
        CancellationToken ct = default)
    {
        JsonElement? json;

        if (employeeId > 0)
        {
            json = await _firebase.GetOwnerTableByChildValueAsync(
                OwnerUid,
                Table,
                "employeeId",
                employeeId,
                ct);
        }
        else
        {
            json = await _firebase.GetOwnerTableAsync(
                OwnerUid,
                Table,
                ct);
        }

        if (json is null || (json.Value.ValueKind != JsonValueKind.Object && json.Value.ValueKind != JsonValueKind.Array))
        {
            return new List<BonusRecord>();
        }

        var result = new List<BonusRecord>();

        if (json.Value.ValueKind == JsonValueKind.Object)
        {
            foreach (var item in json.Value.EnumerateObject())
            {
                if (item.Value.ValueKind != JsonValueKind.Object) continue;
                var bonus = ParseBonus(item.Value, item.Name, employeeId, from, to);
                if (bonus != null) result.Add(bonus);
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
                var bonus = ParseBonus(row, fallbackId, employeeId, from, to);
                if (bonus != null) result.Add(bonus);
            }
        }

        return result
            .OrderByDescending(x => x.BonusDate)
            .ThenBy(x => x.EmployeeID)
            .ToList();
    }

    private static BonusRecord? ParseBonus(JsonElement row, string key, int employeeId, DateTime? from, DateTime? to)
    {
        var empId =
            Int(row, "employeeId")
            ?? Int(row, "EmployeeID");

        if (!empId.HasValue || empId.Value <= 0) return null;
        if (employeeId > 0 && empId.Value != employeeId) return null;

        var date =
            Date(row, "bonusDate")
            ?? Date(row, "BonusDate");

        if (!date.HasValue) return null;
        if (from.HasValue && date.Value.Date < from.Value.Date) return null;
        if (to.HasValue && date.Value.Date > to.Value.Date) return null;

        var bonusId =
            Int(row, "bonusId")
            ?? Int(row, "BonusID")
            ?? IntFromKey(key);

        if (!bonusId.HasValue)
        {
            bonusId = StableInt(key);
        }

        var amount =
            Decimal(row, "amount")
            ?? Decimal(row, "Amount")
            ?? 0m;

        var description =
            String(row, "description")
            ?? String(row, "Description");

        var payrollIdPaid =
            Int(row, "payrollIdPaid")
            ?? Int(row, "PayrollID_Paid");

        return new BonusRecord
        {
            BonusID = bonusId.Value,
            EmployeeID = empId.Value,
            BonusDate = date.Value,
            Amount = amount,
            Description = description,
            PayrollID_Paid = payrollIdPaid,
            FirebaseKey = key
        };
    }

    public async Task<bool> SaveAsync(
        BonusRecord bonus,
        CancellationToken ct = default)
    {
        if (bonus is null)
        {
            return false;
        }

        if (bonus.EmployeeID <= 0)
        {
            return false;
        }

        if (bonus.Amount <= 0m)
        {
            return false;
        }

        string key;

        if (!string.IsNullOrWhiteSpace(bonus.FirebaseKey))
        {
            key = bonus.FirebaseKey!;
        }
        else if (bonus.BonusID > 0)
        {
            key = bonus.BonusID.ToString(
                CultureInfo.InvariantCulture);
        }
        else
        {
            key = Guid.NewGuid().ToString("N");
        }

        var employee =
            await _firebase.GetOwnerRecordAsync(
                OwnerUid,
                EmployeeTable,
                bonus.EmployeeID.ToString(
                    CultureInfo.InvariantCulture),
                ct);

        string shopId = string.Empty;

        if (employee.HasValue &&
            employee.Value.ValueKind == JsonValueKind.Object)
        {
            shopId =
                String(employee.Value, "shopId")
                ?? String(employee.Value, "ShopId")
                ?? string.Empty;
        }

        var row = new Dictionary<string, object?>
        {
            ["bonusId"] =
                bonus.BonusID > 0
                    ? bonus.BonusID
                    : key,

            ["employeeId"] = bonus.EmployeeID,

            ["shopId"] = shopId,

            ["amount"] = bonus.Amount,

            ["bonusDate"] =
                new DateTimeOffset(
                    bonus.BonusDate)
                .ToUnixTimeMilliseconds(),

            ["description"] = bonus.Description,

            ["payrollIdPaid"] =
                bonus.PayrollID_Paid,

            ["_entity"] = "BonusRecord",

            ["_key"] = key,

            ["_updatedUtc"] =
                DateTime.UtcNow.ToString(
                    "O",
                    CultureInfo.InvariantCulture)
        };

        var ok =
            await _firebase.SetOwnerRecordAsync(
                OwnerUid,
                Table,
                key,
                row,
                ct);

        if (!ok)
        {
            return false;
        }

        bonus.FirebaseKey = key;

        await _firebase.PublishLocalApplicationChangeAsync(
            OwnerUid,
            "BonusRecord",
            "MODIFIED",
            key,
            ct);

        return true;
    }

    public async Task<bool> DeleteAsync(
        BonusRecord bonus,
        CancellationToken ct = default)
    {
        if (bonus is null)
        {
            return false;
        }

        // A bonus already attached to payroll cannot be deleted.
        if (bonus.PayrollID_Paid.HasValue)
        {
            return false;
        }

        string? key = bonus.FirebaseKey;

        if (string.IsNullOrWhiteSpace(key) &&
            bonus.BonusID > 0)
        {
            key = bonus.BonusID.ToString(
                CultureInfo.InvariantCulture);
        }

        if (string.IsNullOrWhiteSpace(key))
        {
            return false;
        }

        var ok =
            await _firebase.DeleteOwnerRecordAsync(
                OwnerUid,
                Table,
                key,
                ct);

        if (!ok)
        {
            return false;
        }

        await _firebase.PublishLocalApplicationChangeAsync(
            OwnerUid,
            "BonusRecord",
            "DELETED",
            key,
            ct);

        return true;
    }

    private static string? String(
        JsonElement row,
        string name)
    {
        if (!TryGet(row, name, out var value))
        {
            return null;
        }

        if (value.ValueKind == JsonValueKind.Null ||
            value.ValueKind == JsonValueKind.Undefined)
        {
            return null;
        }

        return value.ToString();
    }

    private static int? Int(
        JsonElement row,
        string name)
    {
        if (!TryGet(row, name, out var value))
        {
            return null;
        }

        if (value.ValueKind == JsonValueKind.Number)
        {
            if (value.TryGetInt32(out var number))
            {
                return number;
            }

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

    private static decimal? Decimal(
        JsonElement row,
        string name)
    {
        if (!TryGet(row, name, out var value))
        {
            return null;
        }

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

    private static bool? Bool(
        JsonElement row,
        string name)
    {
        if (!TryGet(row, name, out var value))
        {
            return null;
        }

        if (value.ValueKind == JsonValueKind.True)
        {
            return true;
        }

        if (value.ValueKind == JsonValueKind.False)
        {
            return false;
        }

        var text = value.ToString();

        if (bool.TryParse(text, out var parsed))
        {
            return parsed;
        }

        return null;
    }

    private static DateTime? Date(
        JsonElement row,
        string name)
    {
        if (!TryGet(row, name, out var value))
        {
            return null;
        }

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
            int hash = 23;

            foreach (char character in value)
            {
                hash = hash * 31 + character;
            }

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
        {
            return true;
        }

        if (string.IsNullOrEmpty(name))
        {
            value = default;
            return false;
        }

        var pascal =
            char.ToUpperInvariant(name[0]) +
            name[1..];

        if (row.TryGetProperty(pascal, out value))
        {
            return true;
        }

        value = default;
        return false;
    }
}