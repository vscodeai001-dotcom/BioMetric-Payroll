using System.Globalization;
using System.Text.Json;
using FirebaseAdmin.Auth;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Payroll.Shared.Data;
using Payroll.Shared.Firebase;

namespace Payroll.Web.Services;

/// <summary>
/// Firebase and Local employee dependency checking and force deletion service.
/// Provides safe cascading deletion across both Firebase RTDB (SSOT) and SQLite (EF Core).
/// </summary>
public sealed class FirebaseEmployeeDeletionService
{
    private readonly FirebaseRealtimeService _firebase;
    private readonly IConfiguration _configuration;
    private readonly ILogger<FirebaseEmployeeDeletionService> _logger;
    private readonly IServiceScopeFactory _scopeFactory;

    public FirebaseEmployeeDeletionService(
        FirebaseRealtimeService firebase,
        IConfiguration configuration,
        ILogger<FirebaseEmployeeDeletionService> logger,
        IServiceScopeFactory scopeFactory)
    {
        _firebase = firebase;
        _configuration = configuration;
        _logger = logger;
        _scopeFactory = scopeFactory;
    }

    private string OwnerUid => _firebase.ResolveOwnerUid("employee-deletion", "Admin");

    public sealed class EmployeeDeletionDependencies
    {
        public int EmployeeID { get; set; }
        public string EmployeeName { get; set; } = "";
        public bool CanDelete { get; set; }
        public string BlockReason { get; set; } = "";
        public int AttendanceLogsCount { get; set; }
        public int PayrollHistoryCount { get; set; }
        public int SalaryAdvancesCount { get; set; }
        public int LeaveRequestsCount { get; set; }
        public int ShiftSchedulesCount { get; set; }
        public int BonusRecordsCount { get; set; }
        public int DailySummariesCount { get; set; }

        public int GetTotalDependencies() =>
            AttendanceLogsCount + PayrollHistoryCount + SalaryAdvancesCount +
            LeaveRequestsCount + ShiftSchedulesCount + BonusRecordsCount + DailySummariesCount;

        public List<(string Category, int Count)> GetDependenciesList()
        {
            var list = new List<(string, int)>();
            if (AttendanceLogsCount > 0) list.Add(("Attendance Logs", AttendanceLogsCount));
            if (PayrollHistoryCount > 0) list.Add(("Payroll History", PayrollHistoryCount));
            if (SalaryAdvancesCount > 0) list.Add(("Salary Advances", SalaryAdvancesCount));
            if (LeaveRequestsCount > 0) list.Add(("Leave Requests", LeaveRequestsCount));
            if (ShiftSchedulesCount > 0) list.Add(("Shift Schedules", ShiftSchedulesCount));
            if (BonusRecordsCount > 0) list.Add(("Bonus Records", BonusRecordsCount));
            if (DailySummariesCount > 0) list.Add(("Daily Summaries", DailySummariesCount));
            return list;
        }
    }

    public async Task<EmployeeDeletionDependencies> CheckDeletionDependenciesAsync(
        int employeeId,
        CancellationToken ct = default)
    {
        var result = new EmployeeDeletionDependencies { EmployeeID = employeeId };
        try
        {
            var employees = await _firebase.GetOwnerTableAsync(OwnerUid, "employees", ct);
            if (!employees.HasValue || employees.Value.ValueKind != JsonValueKind.Object)
            {
                result.CanDelete = false;
                result.BlockReason = "Firebase employee data is unavailable.";
                return result;
            }

            var employee = employees.Value.EnumerateObject()
                .FirstOrDefault(x => string.Equals(x.Name, employeeId.ToString(), StringComparison.Ordinal));
            if (employee.Equals(default(JsonProperty)))
            {
                result.CanDelete = false;
                result.BlockReason = "Employee not found in Firebase.";
                return result;
            }

            result.EmployeeName = GetString(employee.Value, "name") ?? "Employee";

            result.AttendanceLogsCount = await CountForEmployeeAsync("attendance", employeeId, ct);
            result.PayrollHistoryCount = await CountForEmployeeAsync("payroll_history", employeeId, ct);
            result.SalaryAdvancesCount = await CountForEmployeeAsync("advance_payments", employeeId, ct);
            result.LeaveRequestsCount = await CountForEmployeeAsync("leave_requests", employeeId, ct, "employeeId", "staffId");
            result.ShiftSchedulesCount = await CountForEmployeeAsync("shift_schedules", employeeId, ct);
            result.BonusRecordsCount = await CountForEmployeeAsync("bonus_records", employeeId, ct);
            result.DailySummariesCount = await CountForEmployeeAsync("daily_summaries", employeeId, ct);

            var total = result.GetTotalDependencies();
            result.CanDelete = total == 0;
            if (!result.CanDelete)
            {
                var summary = string.Join(", ", result.GetDependenciesList().Select(x => $"{x.Count} {x.Category}"));
                result.BlockReason = $"Cannot delete employee. Related records exist: {summary}. Please delete these records first.";
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Firebase deletion dependency check failed for employee {EmployeeId}.", employeeId);
            result.CanDelete = false;
            result.BlockReason = $"Error checking dependencies: {ex.Message}";
        }

        return result;
    }

    /// <summary>
    /// Permanently force-deletes an employee and cascade-deletes all associated records across
    /// both the local SQLite database (EF Core) and Firebase Realtime Database (Cloud SSOT).
    /// </summary>
    public async Task<(bool Success, string Message, int TotalDeletedRecords)> ForceDeleteEmployeeAsync(
        int employeeId,
        CancellationToken ct = default)
    {
        if (employeeId <= 0)
            return (false, "Invalid employee ID.", 0);

        int totalDeleted = 0;
        string employeeName = $"Employee #{employeeId}";
        string? employeeEmail = null;

        try
        {
            _logger.LogInformation("Beginning Force Delete for employee {EmployeeId} across SQLite and Firebase RTDB.", employeeId);

            // Step 1: Read employee name and email from Firebase if available
            try
            {
                var employeeSnapshot = await _firebase.GetOwnerRecordAsync(
                    OwnerUid, "employees", employeeId.ToString(CultureInfo.InvariantCulture), ct);

                if (employeeSnapshot.HasValue && employeeSnapshot.Value.ValueKind == JsonValueKind.Object)
                {
                    if (TryGet(employeeSnapshot.Value, "name", out var nameVal))
                        employeeName = GetString(nameVal) ?? employeeName;
                    if (TryGet(employeeSnapshot.Value, "email", out var emailVal))
                        employeeEmail = GetString(emailVal);
                }
            }
            catch (Exception ex)
            {
                _logger.LogDebug(ex, "Could not fetch employee snapshot from Firebase before force delete.");
            }

            // Step 2: Delete all child records and employee entity from Local SQLite Database (EF Core)
            using (var scope = _scopeFactory.CreateScope())
            {
                var dbFactory = scope.ServiceProvider.GetService<IDbContextFactory<AppDbContext>>();
                if (dbFactory != null)
                {
                    using var db = await dbFactory.CreateDbContextAsync(ct);

                    var localEmp = await db.Employees.FirstOrDefaultAsync(e => e.EmployeeID == employeeId, ct);
                    if (localEmp != null)
                    {
                        if (string.IsNullOrWhiteSpace(employeeEmail))
                            employeeEmail = localEmp.Email;
                        if (employeeName == $"Employee #{employeeId}" && !string.IsNullOrWhiteSpace(localEmp.Name))
                            employeeName = localEmp.Name;
                    }

                    // 1. AttendanceLogs
                    var logs = await db.AttendanceLogs.Where(x => x.EmployeeID == employeeId).ToListAsync(ct);
                    if (logs.Count > 0) { db.AttendanceLogs.RemoveRange(logs); totalDeleted += logs.Count; }

                    // 2. DailySummaries
                    var summaries = await db.DailySummaries.Where(x => x.EmployeeID == employeeId).ToListAsync(ct);
                    if (summaries.Count > 0) { db.DailySummaries.RemoveRange(summaries); totalDeleted += summaries.Count; }

                    // 3. PayrollHistories
                    var payrolls = await db.PayrollHistories.Where(x => x.EmployeeID == employeeId).ToListAsync(ct);
                    if (payrolls.Count > 0) { db.PayrollHistories.RemoveRange(payrolls); totalDeleted += payrolls.Count; }

                    // 4. SalaryAdvances
                    var advances = await db.SalaryAdvances.Where(x => x.EmployeeID == employeeId).ToListAsync(ct);
                    if (advances.Count > 0) { db.SalaryAdvances.RemoveRange(advances); totalDeleted += advances.Count; }

                    // 5. LeaveRequests
                    var leaves = await db.LeaveRequests.Where(x => x.EmployeeID == employeeId).ToListAsync(ct);
                    if (leaves.Count > 0) { db.LeaveRequests.RemoveRange(leaves); totalDeleted += leaves.Count; }

                    // 6. ShiftSchedules
                    var shifts = await db.ShiftSchedules.Where(x => x.EmployeeID == employeeId).ToListAsync(ct);
                    if (shifts.Count > 0) { db.ShiftSchedules.RemoveRange(shifts); totalDeleted += shifts.Count; }

                    // 7. BonusRecords
                    var bonuses = await db.BonusRecords.Where(x => x.EmployeeID == employeeId).ToListAsync(ct);
                    if (bonuses.Count > 0) { db.BonusRecords.RemoveRange(bonuses); totalDeleted += bonuses.Count; }

                    // 8. AttendanceRegularizations
                    var regularizations = await db.AttendanceRegularizations.Where(x => x.EmployeeId == employeeId).ToListAsync(ct);
                    if (regularizations.Count > 0) { db.AttendanceRegularizations.RemoveRange(regularizations); totalDeleted += regularizations.Count; }

                    // 9. GeoPunchAudits
                    var geoAudits = await db.GeoPunchAudits.Where(x => x.EmployeeId == employeeId).ToListAsync(ct);
                    if (geoAudits.Count > 0) { db.GeoPunchAudits.RemoveRange(geoAudits); totalDeleted += geoAudits.Count; }

                    // 10. EmployeeLocationHistory
                    var locHist = await db.EmployeeLocationHistory.Where(x => x.EmployeeId == employeeId).ToListAsync(ct);
                    if (locHist.Count > 0) { db.EmployeeLocationHistory.RemoveRange(locHist); totalDeleted += locHist.Count; }

                    // 11. EmployeeGpsSessions
                    var gpsSessions = await db.EmployeeGpsSessions.Where(x => x.EmployeeId == employeeId).ToListAsync(ct);
                    if (gpsSessions.Count > 0) { db.EmployeeGpsSessions.RemoveRange(gpsSessions); totalDeleted += gpsSessions.Count; }

                    // 12. EmployeeDeviceLocks (linked via AspNetUserId or numeric employee ID)
                    if (!string.IsNullOrWhiteSpace(localEmp?.AspNetUserId))
                    {
                        var deviceLocks = await db.EmployeeDeviceLocks.Where(x => x.UserId == localEmp.AspNetUserId).ToListAsync(ct);
                        if (deviceLocks.Count > 0) { db.EmployeeDeviceLocks.RemoveRange(deviceLocks); totalDeleted += deviceLocks.Count; }
                    }

                    // 13. ResignationRequests
                    var resignations = await db.ResignationRequests.Where(x => x.EmployeeId == employeeId).ToListAsync(ct);
                    if (resignations.Count > 0) { db.ResignationRequests.RemoveRange(resignations); totalDeleted += resignations.Count; }

                    // 14. FnFSettlements
                    var fnfs = await db.FnFSettlements.Where(x => x.EmployeeId == employeeId).ToListAsync(ct);
                    if (fnfs.Count > 0) { db.FnFSettlements.RemoveRange(fnfs); totalDeleted += fnfs.Count; }

                    // 15. TaxDeclarations
                    var taxDecls = await db.TaxDeclarations.Where(x => x.EmployeeId == employeeId).ToListAsync(ct);
                    if (taxDecls.Count > 0) { db.TaxDeclarations.RemoveRange(taxDecls); totalDeleted += taxDecls.Count; }

                    // 16. FlexibleBenefitDeclarations
                    var fbps = await db.FlexibleBenefitDeclarations.Where(x => x.EmployeeId == employeeId).ToListAsync(ct);
                    if (fbps.Count > 0) { db.FlexibleBenefitDeclarations.RemoveRange(fbps); totalDeleted += fbps.Count; }

                    // 17. YearEndSummaries
                    var yearSummaries = await db.YearEndSummaries.Where(x => x.EmployeeID == employeeId).ToListAsync(ct);
                    if (yearSummaries.Count > 0) { db.YearEndSummaries.RemoveRange(yearSummaries); totalDeleted += yearSummaries.Count; }

                    // 18. Employees table
                    if (localEmp != null)
                    {
                        db.Employees.Remove(localEmp);
                        totalDeleted++;
                    }

                    await db.SaveChangesAsync(ct);
                }
            }

            // Step 3: Delete all records from Firebase Realtime Database
            var updates = new Dictionary<string, object?>(StringComparer.Ordinal);
            var tablesToClean = new[]
            {
                "attendance",
                "attendance_punches",
                "daily_summaries",
                "payroll_history",
                "advance_payments",
                "leave_requests",
                "shift_schedules",
                "bonus_records",
                "regularizations",
                "geo_punch_audits",
                "salary_snapshots",
                "tax_declarations",
                "fbp_declarations",
                "fnf_settlements",
                "resignation_requests",
                "tracking/sessions",
                "tracking/history"
            };

            foreach (var table in tablesToClean)
            {
                try
                {
                    var keys = await GetRecordKeysForEmployeeAsync(table, employeeId, ct);
                    foreach (var key in keys)
                    {
                        updates[$"owners/{OwnerUid}/{table}/{EscapeFirebaseKey(key)}"] = null;
                        totalDeleted++;

                        if (updates.Count >= 100)
                        {
                            await _firebase.UpdateAsync(updates, ct);
                            updates.Clear();
                        }
                    }
                }
                catch (Exception tblEx)
                {
                    _logger.LogWarning(tblEx, "Failed to clean table {Table} in Firebase for employee {EmployeeId}", table, employeeId);
                }
            }

            // Remove Employee, Presence, and Live tracking nodes
            updates[$"owners/{OwnerUid}/employees/{employeeId}"] = null;
            updates[$"owners/{OwnerUid}/presence/{employeeId}"] = null;
            updates[$"owners/{OwnerUid}/tracking/live/{employeeId}"] = null;
            totalDeleted++;

            if (updates.Count > 0)
            {
                await _firebase.UpdateAsync(updates, ct);
                updates.Clear();
            }

            // Step 4: Publish Application Data Changed event in Firebase
            try
            {
                await _firebase.PublishApplicationDataChangedAsync(new[]
                {
                    new Dictionary<string, object?>
                    {
                        ["Entity"] = "Employee",
                        ["Action"] = "DELETED",
                        ["RecordId"] = employeeId.ToString(CultureInfo.InvariantCulture),
                        ["WriteId"] = Guid.NewGuid().ToString("N")
                    }
                }, OwnerUid, ct);
            }
            catch (Exception pubEx)
            {
                _logger.LogWarning(pubEx, "Failed to publish Firebase employee deletion event for {EmployeeId}", employeeId);
            }

            // Step 5: Clean up Firebase Auth user and session nodes
            if (!string.IsNullOrWhiteSpace(employeeEmail))
            {
                try
                {
                    var auth = await _firebase.GetFirebaseAuthAsync(ct);
                    if (auth != null)
                    {
                        try
                        {
                            var user = await auth.GetUserByEmailAsync(employeeEmail.Trim(), ct);
                            if (user != null)
                            {
                                await auth.RevokeRefreshTokensAsync(user.Uid, ct);
                                await _firebase.DeleteGlobalRecordAsync($"employee_sessions/{user.Uid}", ct);
                                await _firebase.DeleteGlobalRecordAsync($"user_profiles/{user.Uid}", ct);
                                await auth.DeleteUserAsync(user.Uid, ct);
                                _logger.LogInformation("Firebase Auth user {Uid} permanently deleted for employee {EmployeeId}", user.Uid, employeeId);
                            }
                        }
                        catch (FirebaseAuthException fex) when (fex.AuthErrorCode == AuthErrorCode.UserNotFound)
                        {
                            // Already deleted or never existed
                        }
                    }
                }
                catch (Exception authEx)
                {
                    _logger.LogWarning(authEx, "Error during Firebase Auth deletion for employee {EmployeeId}", employeeId);
                }
            }

            _logger.LogInformation(
                "Force Delete completed for employee {EmployeeId} ({EmployeeName}). Total records purged: {TotalDeleted}",
                employeeId, employeeName, totalDeleted);

            return (true, $"Employee '{employeeName}' and all {totalDeleted} associated records were permanently deleted.", totalDeleted);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Critical failure during Force Delete for employee {EmployeeId}", employeeId);
            return (false, $"Force deletion failed: {ex.Message}", totalDeleted);
        }
    }

    private async Task<List<string>> GetRecordKeysForEmployeeAsync(
        string table,
        int employeeId,
        CancellationToken ct,
        params string[] preferredFields)
    {
        var snapshot = await _firebase.GetOwnerTableAsync(OwnerUid, table, ct);
        if (!snapshot.HasValue || snapshot.Value.ValueKind != JsonValueKind.Object)
            return new List<string>();

        var fields = preferredFields.Length == 0
            ? new[] { "employeeId", "staffId", "EmployeeID", "EmployeeId", "empId" }
            : preferredFields;
        var id = employeeId.ToString(CultureInfo.InvariantCulture);

        var keys = new List<string>();
        foreach (var item in snapshot.Value.EnumerateObject())
        {
            if (item.Name.StartsWith($"{id}_", StringComparison.OrdinalIgnoreCase) ||
                string.Equals(item.Name, id, StringComparison.OrdinalIgnoreCase))
            {
                keys.Add(item.Name);
                continue;
            }

            if (fields.Any(field => TryGet(item.Value, field, out var value) &&
                string.Equals(GetString(value), id, StringComparison.OrdinalIgnoreCase)))
            {
                keys.Add(item.Name);
            }
        }
        return keys;
    }

    private async Task<int> CountForEmployeeAsync(
        string table,
        int employeeId,
        CancellationToken ct,
        params string[] preferredFields)
    {
        var snapshot = await _firebase.GetOwnerTableAsync(OwnerUid, table, ct);
        if (!snapshot.HasValue || snapshot.Value.ValueKind != JsonValueKind.Object)
            return 0;

        var fields = preferredFields.Length == 0
            ? new[] { "employeeId", "staffId", "EmployeeID", "EmployeeId", "empId" }
            : preferredFields;
        var id = employeeId.ToString(CultureInfo.InvariantCulture);

        return snapshot.Value.EnumerateObject().Count(item =>
            item.Name.StartsWith($"{id}_", StringComparison.OrdinalIgnoreCase) ||
            string.Equals(item.Name, id, StringComparison.OrdinalIgnoreCase) ||
            fields.Any(field => TryGet(item.Value, field, out var value) &&
                string.Equals(GetString(value), id, StringComparison.OrdinalIgnoreCase)));
    }

    private static string EscapeFirebaseKey(string value)
        => value.Replace(".", "%2E").Replace("#", "%23").Replace("$", "%24")
                .Replace("[", "%5B").Replace("]", "%5D").Replace("/", "%2F");

    private static string? GetString(JsonElement value, string? ignored = null)
        => value.ValueKind == JsonValueKind.String ? value.GetString() : value.ToString();

    private static bool TryGet(JsonElement element, string name, out JsonElement value)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            foreach (var property in element.EnumerateObject())
            {
                if (string.Equals(property.Name, name, StringComparison.OrdinalIgnoreCase))
                {
                    value = property.Value;
                    return true;
                }
            }
        }
        value = default;
        return false;
    }
}
