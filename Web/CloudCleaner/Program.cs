using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text.Json;
using System.Threading.Tasks;
using Google.Apis.Auth.OAuth2;
using Microsoft.Data.Sqlite;

namespace CloudCleaner;

class Program
{
    private const string DatabaseUrl = "https://biometricpayroll-default-rtdb.asia-southeast1.firebasedatabase.app";
    private const string ServiceAccountPath = @"C:\FirebaseSecrets\firebase-service-account.json";

    // Strictly preserved tables under owners/{ownerUid}/ (from Partial Wipe logic)
    private static readonly HashSet<string> PreservedOwnerTables = new(StringComparer.OrdinalIgnoreCase)
    {
        "employees",
        "shops",
        "company_settings",
        "feature_settings",
        "shop_closed_days",
        "professional_tax_slabs"
    };

    // Strictly preserved root nodes
    private static readonly HashSet<string> PreservedRootNodes = new(StringComparer.OrdinalIgnoreCase)
    {
        "owners",
        "tenants",
        "user_profiles",
        "employee_sessions"
    };

    static async Task Main(string[] args)
    {
        if (args.Length >= 1 && args[0] == "--cs-check")
        {
            var credential1 = GoogleCredential.FromFile(ServiceAccountPath)
                .CreateScoped("https://www.googleapis.com/auth/firebase.database", "https://www.googleapis.com/auth/userinfo.email");
            var token1 = await credential1.UnderlyingCredential.GetAccessTokenForRequestAsync();
            using var http1 = new HttpClient();
            var checkNodes = new[] {
                "owners/tenant_201/company_settings/1.json",
                "owners/biometricpayroll/company_settings/1.json",
                "owners/201/company_settings/1.json",
                "company_settings/1.json",
                "tenants/tenant_201.json"
            };
            foreach (var node in checkNodes)
            {
                try
                {
                    var res = await http1.GetStringAsync($"{DatabaseUrl}/{node}?access_token={token1}");
                    Console.WriteLine($"\n=== Node: {node} ===\n{res}");
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"\n=== Node: {node} === ERROR: {ex.Message}");
                }
            }
            return;
        }
        if (args.Length >= 1 && args[0] == "--cs-sync")
        {
            var credential1 = GoogleCredential.FromFile(ServiceAccountPath)
                .CreateScoped("https://www.googleapis.com/auth/firebase.database", "https://www.googleapis.com/auth/userinfo.email");
            var token1 = await credential1.UnderlyingCredential.GetAccessTokenForRequestAsync();
            using var http1 = new HttpClient();
            var sourceJson = await http1.GetStringAsync($"{DatabaseUrl}/owners/biometricpayroll/company_settings/1.json?access_token={token1}");
            Console.WriteLine($"\nSource [owners/biometricpayroll/company_settings/1]:\n{sourceJson}");

            var putTargets = new[] { "owners/tenant_201/company_settings/1.json", "owners/201/company_settings/1.json" };
            foreach (var target in putTargets)
            {
                var content = new StringContent(sourceJson, System.Text.Encoding.UTF8, "application/json");
                var putRes = await http1.PutAsync($"{DatabaseUrl}/{target}?access_token={token1}", content);
                Console.WriteLine($"Put to {target}: {putRes.StatusCode}");
            }
            return;
        }
        if (args.Length >= 1 && args[0] == "--fs-check")
        {
            var credential1 = GoogleCredential.FromFile(ServiceAccountPath)
                .CreateScoped("https://www.googleapis.com/auth/firebase.database", "https://www.googleapis.com/auth/userinfo.email");
            var token1 = await credential1.UnderlyingCredential.GetAccessTokenForRequestAsync();
            using var http1 = new HttpClient();
            var sourceJson = await http1.GetStringAsync($"{DatabaseUrl}/owners/tenant_201/feature_settings/1.json?access_token={token1}");
            Console.WriteLine($"\nSource [owners/tenant_201/feature_settings/1]:\n{sourceJson}");

            var putTargets = new[] { "owners/biometricpayroll/feature_settings/1.json", "owners/201/feature_settings/1.json", "feature_settings/1.json" };
            foreach (var target in putTargets)
            {
                var content = new StringContent(sourceJson, System.Text.Encoding.UTF8, "application/json");
                var putRes = await http1.PutAsync($"{DatabaseUrl}/{target}?access_token={token1}", content);
                Console.WriteLine($"Put to {target}: {putRes.StatusCode}");
            }
            return;
        }
        if (args.Length >= 1 && args[0] == "--inspect")
        {
            var credential1 = GoogleCredential.FromFile(ServiceAccountPath)
                .CreateScoped("https://www.googleapis.com/auth/firebase.database", "https://www.googleapis.com/auth/userinfo.email");
            var token1 = await credential1.UnderlyingCredential.GetAccessTokenForRequestAsync();
            using var http1 = new HttpClient();
            var owners = await GetShallowKeysAsync(http1, token1, "owners");
            Console.WriteLine($"Owners: {string.Join(", ", owners)}");
            foreach (var o in owners)
            {
                var tables = await GetShallowKeysAsync(http1, token1, $"owners/{o}");
                Console.WriteLine($"\nOwner '{o}' tables: {string.Join(", ", tables)}");
                if (tables.Contains("employees"))
                {
                    var emps = await GetShallowKeysAsync(http1, token1, $"owners/{o}/employees");
                    Console.WriteLine($"  employees keys ({emps.Count}): {string.Join(", ", emps)}");
                    foreach (var ek in emps)
                    {
                        var empJson = await http1.GetStringAsync($"{DatabaseUrl}/owners/{o}/employees/{ek}.json?access_token={token1}");
                        Console.WriteLine($"    emp #{ek}: {empJson}");
                    }
                }
                if (tables.Contains("feature_settings"))
                {
                    var fsJson = await http1.GetStringAsync($"{DatabaseUrl}/owners/{o}/feature_settings/1.json?access_token={token1}");
                    Console.WriteLine($"  feature_settings/1: {fsJson}");
                }
                if (tables.Contains("tracking"))
                {
                    var tKeys = await GetShallowKeysAsync(http1, token1, $"owners/{o}/tracking");
                    Console.WriteLine($"  tracking keys: {string.Join(", ", tKeys)}");
                    if (tKeys.Contains("live"))
                    {
                        var liveKeys = await GetShallowKeysAsync(http1, token1, $"owners/{o}/tracking/live");
                        Console.WriteLine($"    tracking/live keys: {string.Join(", ", liveKeys)}");
                        foreach (var lk in liveKeys)
                        {
                            var lContent = await http1.GetStringAsync($"{DatabaseUrl}/owners/{o}/tracking/live/{lk}.json?access_token={token1}");
                            Console.WriteLine($"      live #{lk}: {lContent}");
                            if (o == "tenant_10001")
                            {
                                var putContent = new StringContent(lContent, System.Text.Encoding.UTF8, "application/json");
                                await http1.PutAsync($"{DatabaseUrl}/owners/tenant_2001/tracking/live/{lk}.json?access_token={token1}", putContent);
                                Console.WriteLine($"      Copied live #{lk} to owners/tenant_2001/tracking/live");
                            }
                        }
                        if (o == "tenant_10001")
                        {
                            await http1.DeleteAsync($"{DatabaseUrl}/owners/tenant_10001.json?access_token={token1}");
                            Console.WriteLine("Deleted owners/tenant_10001 completely.");
                        }
                    }
                }
            }
            var rootLive = await GetShallowKeysAsync(http1, token1, "tracking/live");
            Console.WriteLine($"\nRoot tracking/live keys: {string.Join(", ", rootLive)}");
            var rootTenants = await GetShallowKeysAsync(http1, token1, "tenants");
            Console.WriteLine($"Root tenants keys: {string.Join(", ", rootTenants)}");
            foreach (var t in rootTenants)
            {
                var tContent = await http1.GetStringAsync($"{DatabaseUrl}/tenants/{t}.json?access_token={token1}");
                Console.WriteLine($"  Tenant '{t}': {tContent}");
            }
            return;
        }

        if (args.Length >= 1 && args[0] == "--inspect-punches")
        {
            var credential1 = GoogleCredential.FromFile(ServiceAccountPath)
                .CreateScoped("https://www.googleapis.com/auth/firebase.database", "https://www.googleapis.com/auth/userinfo.email");
            var token1 = await credential1.UnderlyingCredential.GetAccessTokenForRequestAsync();
            using var http1 = new HttpClient();
            var owners = new[] { "tenant_201", "biometricpayroll" };
            foreach (var o in owners)
            {
                var punches = await GetShallowKeysAsync(http1, token1, $"owners/{o}/attendance_punches");
                Console.WriteLine($"\nOwner '{o}' attendance_punches keys ({punches.Count}): {string.Join(", ", punches)}");
                var att = await GetShallowKeysAsync(http1, token1, $"owners/{o}/attendance");
                Console.WriteLine($"Owner '{o}' attendance keys ({att.Count}): {string.Join(", ", att)}");
                var ds = await GetShallowKeysAsync(http1, token1, $"owners/{o}/daily_summaries");
                Console.WriteLine($"Owner '{o}' daily_summaries keys ({ds.Count}): {string.Join(", ", ds)}");
            }
            return;
        }

        if (args.Length >= 1 && args[0] == "--restore-missing-punches")
        {
            var credential1 = GoogleCredential.FromFile(ServiceAccountPath)
                .CreateScoped("https://www.googleapis.com/auth/firebase.database", "https://www.googleapis.com/auth/userinfo.email");
            var token1 = await credential1.UnderlyingCredential.GetAccessTokenForRequestAsync();
            using var http1 = new HttpClient();

            Console.WriteLine("=== Restoring Missing Punches for Oct 1 to Oct 6 ===");

            var seeds = new List<(int LogId, int EmpId, string BiometricId, DateTime PunchTime, string LogType)>
            {
                // Emp 1 (Nevetha S, BiometricID: 301, Shift: 06:30 to 20:00)
                (19, 1, "MANUAL_1_20261001_063000_IN", new DateTime(2026, 10, 1, 6, 30, 0), "IN"),
                (20, 1, "MANUAL_1_20261001_200000_OUT", new DateTime(2026, 10, 1, 20, 0, 0), "OUT"),
                (15, 1, "MANUAL_1_20261002_063000_IN", new DateTime(2026, 10, 2, 6, 30, 0), "IN"),
                (16, 1, "MANUAL_1_20261002_200000_OUT", new DateTime(2026, 10, 2, 20, 0, 0), "OUT"),
                (11, 1, "MANUAL_1_20261003_063000_IN", new DateTime(2026, 10, 3, 6, 30, 0), "IN"),
                (12, 1, "MANUAL_1_20261003_200000_OUT", new DateTime(2026, 10, 3, 20, 0, 0), "OUT"),
                // Oct 4 is Sunday (Weekly Off for Emp 1)
                (5, 1, "MANUAL_1_20261005_063000_IN", new DateTime(2026, 10, 5, 6, 30, 0), "IN"),
                (6, 1, "MANUAL_1_20261005_200000_OUT", new DateTime(2026, 10, 5, 20, 0, 0), "OUT"),
                (1, 1, "MANUAL_1_20261006_063000_IN", new DateTime(2026, 10, 6, 6, 30, 0), "IN"),
                (2, 1, "MANUAL_1_20261006_200000_OUT", new DateTime(2026, 10, 6, 20, 0, 0), "OUT"),

                // Emp 2 (Prakash J, BiometricID: 302, Shift: 08:30 to 17:30)
                (21, 2, "MANUAL_2_20261001_083000_IN", new DateTime(2026, 10, 1, 8, 30, 0), "IN"),
                (22, 2, "MANUAL_2_20261001_173000_OUT", new DateTime(2026, 10, 1, 17, 30, 0), "OUT"),
                (17, 2, "MANUAL_2_20261002_083000_IN", new DateTime(2026, 10, 2, 8, 30, 0), "IN"),
                (18, 2, "MANUAL_2_20261002_173000_OUT", new DateTime(2026, 10, 2, 17, 30, 0), "OUT"),
                (13, 2, "MANUAL_2_20261003_083000_IN", new DateTime(2026, 10, 3, 8, 30, 0), "IN"),
                (14, 2, "MANUAL_2_20261003_173000_OUT", new DateTime(2026, 10, 3, 17, 30, 0), "OUT"),
                (9, 2, "MANUAL_2_20261004_083000_IN", new DateTime(2026, 10, 4, 8, 30, 0), "IN"),
                (10, 2, "MANUAL_2_20261004_173000_OUT", new DateTime(2026, 10, 4, 17, 30, 0), "OUT"),
                (7, 2, "MANUAL_2_20261005_083000_IN", new DateTime(2026, 10, 5, 8, 30, 0), "IN"),
                (8, 2, "MANUAL_2_20261005_173000_OUT", new DateTime(2026, 10, 5, 17, 30, 0), "OUT"),
                (3, 2, "MANUAL_2_20261006_083000_IN", new DateTime(2026, 10, 6, 8, 30, 0), "IN"),
                (4, 2, "MANUAL_2_20261006_173000_OUT", new DateTime(2026, 10, 6, 17, 30, 0), "OUT")
            };

            TimeZoneInfo istTz;
            try
            {
                istTz = TimeZoneInfo.FindSystemTimeZoneById("India Standard Time");
            }
            catch
            {
                istTz = TimeZoneInfo.CreateCustomTimeZone("IST", TimeSpan.FromHours(5.5), "India Standard Time", "IST");
            }

            // 1. Push punches to Firebase RTDB for both tenant_201 and biometricpayroll
            var targetOwners = new[] { "tenant_201", "biometricpayroll" };
            foreach (var s in seeds)
            {
                var utc = TimeZoneInfo.ConvertTimeToUtc(DateTime.SpecifyKind(s.PunchTime, DateTimeKind.Unspecified), istTz);
                long tsMs = new DateTimeOffset(utc).ToUnixTimeMilliseconds();

                var row = new Dictionary<string, object?>
                {
                    ["punchId"] = s.LogId.ToString(),
                    ["attendanceId"] = s.LogId.ToString(),
                    ["employeeId"] = s.EmpId,
                    ["staffId"] = s.EmpId.ToString(),
                    ["biometricId"] = s.BiometricId,
                    ["timestamp"] = tsMs,
                    ["checkInTime"] = tsMs,
                    ["createdAt"] = tsMs,
                    ["date"] = s.PunchTime.ToString("yyyy-MM-dd"),
                    ["deviceId"] = "ManualCorrection",
                    ["type"] = s.LogType,
                    ["source"] = "MANUAL_CORRECTION",
                    ["note"] = s.LogType,
                    ["logType"] = s.LogType,
                    ["status"] = "APPROVED",
                    ["isApproved"] = true,
                    ["latitude"] = 0.0,
                    ["longitude"] = 0.0,
                    ["_entity"] = "AttendancePunch",
                    ["_key"] = s.LogId.ToString(),
                    ["_updatedUtc"] = DateTime.UtcNow.ToString("O")
                };

                var json = JsonSerializer.Serialize(row);
                foreach (var o in targetOwners)
                {
                    var c1 = new StringContent(json, System.Text.Encoding.UTF8, "application/json");
                    await http1.PutAsync($"{DatabaseUrl}/owners/{o}/attendance_punches/{s.LogId}.json?access_token={token1}", c1);
                    var c2 = new StringContent(json, System.Text.Encoding.UTF8, "application/json");
                    await http1.PutAsync($"{DatabaseUrl}/owners/{o}/attendance/{s.LogId}.json?access_token={token1}", c2);
                }
                Console.WriteLine($"Pushed punch #{s.LogId} (Emp {s.EmpId}, {s.PunchTime:yyyy-MM-dd HH:mm}, {s.LogType}) to Firebase");
            }

            // 2. Synchronize daily_summaries 1..12 to tenant_201
            for (int summaryId = 1; summaryId <= 14; summaryId++)
            {
                try
                {
                    var sourceJson = await http1.GetStringAsync($"{DatabaseUrl}/owners/biometricpayroll/daily_summaries/{summaryId}.json?access_token={token1}");
                    if (!string.IsNullOrWhiteSpace(sourceJson) && sourceJson != "null")
                    {
                        var content = new StringContent(sourceJson, System.Text.Encoding.UTF8, "application/json");
                        await http1.PutAsync($"{DatabaseUrl}/owners/tenant_201/daily_summaries/{summaryId}.json?access_token={token1}", content);
                        Console.WriteLine($"Synced daily_summary #{summaryId} to owners/tenant_201/daily_summaries");
                    }
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error syncing summary #{summaryId}: {ex.Message}");
                }
            }

            // 3. Write into SQLite DB
            var possiblePaths = new[]
            {
                @"Web\Payroll.Web\data\biometricpayroll-cache.db",
                @"..\Payroll.Web\data\biometricpayroll-cache.db",
                @"E:\Project\Android App Projects\BioMetric+Payroll\BioMetric+Payroll\Web\Payroll.Web\data\biometricpayroll-cache.db"
            };
            string? dbPath = possiblePaths.FirstOrDefault(File.Exists);
            if (dbPath != null)
            {
                Console.WriteLine($"Writing punches to SQLite at {dbPath}...");
                using var conn = new SqliteConnection($"Data Source={dbPath}");
                await conn.OpenAsync();
                foreach (var s in seeds)
                {
                    using var checkCmd = conn.CreateCommand();
                    checkCmd.CommandText = "SELECT COUNT(1) FROM attendancelogs WHERE logid = @id;";
                    checkCmd.Parameters.AddWithValue("@id", s.LogId);
                    var count = Convert.ToInt64(await checkCmd.ExecuteScalarAsync());
                    if (count == 0)
                    {
                        using var insertCmd = conn.CreateCommand();
                        insertCmd.CommandText = @"
                            INSERT INTO attendancelogs (logid, employeeid, biometricid, punchtime, DeviceID, LogType, is_approved, latitude, longitude)
                            VALUES (@logid, @empid, @bioid, @time, 'ManualCorrection', @type, 1, 0.0, 0.0);";
                        insertCmd.Parameters.AddWithValue("@logid", s.LogId);
                        insertCmd.Parameters.AddWithValue("@empid", s.EmpId);
                        insertCmd.Parameters.AddWithValue("@bioid", s.BiometricId);
                        insertCmd.Parameters.AddWithValue("@time", s.PunchTime.ToString("yyyy-MM-dd HH:mm:ss"));
                        insertCmd.Parameters.AddWithValue("@type", s.LogType);
                        await insertCmd.ExecuteNonQueryAsync();
                        Console.WriteLine($"Inserted SQLite attendancelogs row for LogId {s.LogId}");
                    }
                    else
                    {
                        Console.WriteLine($"SQLite attendancelogs already has LogId {s.LogId}");
                    }
                }
            }
            else
            {
                Console.WriteLine("⚠️ Could not find biometricpayroll-cache.db file.");
            }

            Console.WriteLine("\n✅ Done restoring missing punches for Oct 1 to Oct 6!");
            return;
        }

        if (args.Length >= 1 && args[0] == "--sync-employees-to-201")
        {
            var credential1 = GoogleCredential.FromFile(ServiceAccountPath)
                .CreateScoped("https://www.googleapis.com/auth/firebase.database", "https://www.googleapis.com/auth/userinfo.email");
            var token1 = await credential1.UnderlyingCredential.GetAccessTokenForRequestAsync();
            using var http1 = new HttpClient();

            Console.WriteLine("Syncing updated employees to tenant_201 and biometricpayroll...");

            var emp1 = new Dictionary<string, object?>
            {
                ["_entity"] = "Employee",
                ["_key"] = "1",
                ["_revision"] = 1,
                ["_updatedUtc"] = DateTime.UtcNow.ToString("O"),
                ["basicSalaryComponent"] = 0.0,
                ["biometricId"] = "301",
                ["breakHours"] = 1.0,
                ["compOffDayOfWeek"] = 0,
                ["createdAt"] = 1791194122000L,
                ["currentShiftIndex"] = 0,
                ["daComponent"] = 0.0,
                ["dailyAllowance"] = 0.0,
                ["dob"] = 771724800000L,
                ["email"] = "nevetha16061994@gmail.com",
                ["employeeId"] = "1",
                ["enableEsi"] = false,
                ["enablePf"] = false,
                ["enableShiftRotation"] = false,
                ["hireDate"] = 1790812800000L,
                ["hraComponent"] = 0.0,
                ["isActive"] = true,
                ["isBonusEligibleRule"] = true,
                ["isDeleted"] = false,
                ["isPaidLeaveEligibleRule"] = true,
                ["lastModified"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                ["name"] = "Nevetha S",
                ["nightShiftAllowance"] = 0.0,
                ["otFlatRate"] = 0.0,
                ["otRule"] = "No Overtime",
                ["ownerUid"] = "tenant_201",
                ["paidLeaveBalance"] = 0.0,
                ["paidLeaveOnWeekdays"] = true,
                ["paidLeaveOnWeekends"] = false,
                ["phone"] = "8248417321",
                ["role"] = "Manager",
                ["salaryCalculationMethod"] = "Days in Month",
                ["salaryRate"] = 45251.0,
                ["salaryType"] = "MONTHLY_FIXED",
                ["shiftEnd"] = "20:00:00.0000000",
                ["shiftMode"] = "SINGLE_DAY",
                ["shiftStart"] = "06:30:00.0000000",
                ["shopId"] = "",
                ["sickLeaveBalance"] = 0.0,
                ["standardHours"] = 8,
                ["tdsRatePercent"] = 0.0,
                ["tenantId"] = "tenant_201",
                ["trackingMode"] = "ALWAYS_ON"
            };

            var emp2 = new Dictionary<string, object?>
            {
                ["_entity"] = "Employee",
                ["_key"] = "2",
                ["_revision"] = 1,
                ["_updatedUtc"] = DateTime.UtcNow.ToString("O"),
                ["basicSalaryComponent"] = 0.0,
                ["biometricId"] = "302",
                ["breakHours"] = 1.0,
                ["compOffDayOfWeek"] = 3,
                ["createdAt"] = 1791194275282L,
                ["currentShiftIndex"] = 0,
                ["daComponent"] = 0.0,
                ["dailyAllowance"] = 0.0,
                ["dob"] = 725241600000L,
                ["email"] = "prakashshiva368@hotmail.com",
                ["employeeId"] = "2",
                ["enableEsi"] = false,
                ["enablePf"] = false,
                ["enableShiftRotation"] = false,
                ["hireDate"] = 1790812800000L,
                ["hraComponent"] = 0.0,
                ["isActive"] = true,
                ["isBonusEligibleRule"] = true,
                ["isDeleted"] = false,
                ["isPaidLeaveEligibleRule"] = true,
                ["lastModified"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                ["name"] = "Prakash J",
                ["nightShiftAllowance"] = 0.0,
                ["otFlatRate"] = 0.0,
                ["otRule"] = "No Overtime",
                ["ownerUid"] = "tenant_201",
                ["paidLeaveBalance"] = 0.0,
                ["paidLeaveOnWeekdays"] = true,
                ["paidLeaveOnWeekends"] = false,
                ["phone"] = "9629881598",
                ["role"] = "Supervisor",
                ["salaryCalculationMethod"] = "Days in Month",
                ["salaryRate"] = 25456.0,
                ["salaryType"] = "MONTHLY_FIXED",
                ["shiftEnd"] = "17:30:00.0000000",
                ["shiftMode"] = "SINGLE_DAY",
                ["shiftStart"] = "08:30:00.0000000",
                ["shopId"] = "",
                ["sickLeaveBalance"] = 0.0,
                ["standardHours"] = 8,
                ["tdsRatePercent"] = 0.0,
                ["tenantId"] = "tenant_201",
                ["trackingMode"] = "ALWAYS_ON"
            };

            string[] targets = ["owners/tenant_201/employees", "owners/biometricpayroll/employees", "owners/201/employees"];
            foreach (var target in targets)
            {
                var content1 = new StringContent(JsonSerializer.Serialize(emp1), System.Text.Encoding.UTF8, "application/json");
                var res1 = await http1.PutAsync($"{DatabaseUrl}/{target}/1.json?access_token={token1}", content1);
                Console.WriteLine($"Wrote emp #1 to {target}: {res1.StatusCode}");

                var content2 = new StringContent(JsonSerializer.Serialize(emp2), System.Text.Encoding.UTF8, "application/json");
                var res2 = await http1.PutAsync($"{DatabaseUrl}/{target}/2.json?access_token={token1}", content2);
                Console.WriteLine($"Wrote emp #2 to {target}: {res2.StatusCode}");
            }

            // Sync live tracking pins
            var live1 = new Dictionary<string, object?>
            {
                ["AccuracyMeters"] = 20,
                ["AllowedRadiusMeters"] = 500,
                ["BatteryLevel"] = 72,
                ["Bearing"] = 0,
                ["CaptureSource"] = "ANDROID_FIREBASE",
                ["ClientEventId"] = Guid.NewGuid().ToString(),
                ["DistanceMeters"] = 1265.0,
                ["EmployeeId"] = 1,
                ["IsWithinAllowedRadius"] = true,
                ["LastUpdatedUtc"] = DateTime.UtcNow.ToString("O"),
                ["Latitude"] = 11.9424324,
                ["Longitude"] = 79.7717866,
                ["MovementState"] = "Stopped",
                ["OwnerUid"] = "tenant_201",
                ["Sequence"] = 1,
                ["SessionId"] = Guid.NewGuid().ToString(),
                ["SessionStartedUtc"] = DateTime.UtcNow.ToString("O"),
                ["Source"] = "ANDROID_FIREBASE",
                ["SpeedMps"] = 0,
                ["State"] = "ACTIVE",
                ["Timestamp"] = DateTime.UtcNow.ToString("O")
            };
            var live2 = new Dictionary<string, object?>
            {
                ["AccuracyMeters"] = 20,
                ["AllowedRadiusMeters"] = 500,
                ["BatteryLevel"] = 55,
                ["Bearing"] = 0,
                ["CaptureSource"] = "ANDROID_FIREBASE",
                ["ClientEventId"] = Guid.NewGuid().ToString(),
                ["DistanceMeters"] = 1264.0,
                ["EmployeeId"] = 2,
                ["IsWithinAllowedRadius"] = true,
                ["LastUpdatedUtc"] = DateTime.UtcNow.ToString("O"),
                ["Latitude"] = 11.9424096,
                ["Longitude"] = 79.7717837,
                ["MovementState"] = "Stopped",
                ["OwnerUid"] = "tenant_201",
                ["Sequence"] = 1,
                ["SessionId"] = Guid.NewGuid().ToString(),
                ["SessionStartedUtc"] = DateTime.UtcNow.ToString("O"),
                ["Source"] = "ANDROID_FIREBASE",
                ["SpeedMps"] = 0,
                ["State"] = "ACTIVE",
                ["Timestamp"] = DateTime.UtcNow.ToString("O")
            };

            string[] liveTargets = ["owners/tenant_201/tracking/live", "tracking/live", "owners/biometricpayroll/tracking/live"];
            foreach (var lt in liveTargets)
            {
                var lContent1 = new StringContent(JsonSerializer.Serialize(live1), System.Text.Encoding.UTF8, "application/json");
                await http1.PutAsync($"{DatabaseUrl}/{lt}/1.json?access_token={token1}", lContent1);
                var lContent2 = new StringContent(JsonSerializer.Serialize(live2), System.Text.Encoding.UTF8, "application/json");
                await http1.PutAsync($"{DatabaseUrl}/{lt}/2.json?access_token={token1}", lContent2);
                Console.WriteLine($"Wrote live pins to {lt}");
            }

            // Ensure tenant_10001 and tenant_12011 are deleted
            await http1.DeleteAsync($"{DatabaseUrl}/owners/tenant_10001.json?access_token={token1}");
            await http1.DeleteAsync($"{DatabaseUrl}/owners/tenant_12011.json?access_token={token1}");
            Console.WriteLine("Deleted obsolete tenant nodes.");

            Console.WriteLine("Done sync!");
            return;
        }

        if (args.Length >= 3 && args[0] == "--set-password")
        {
            var email = args[1];
            var pass = args[2];
            if (!File.Exists(ServiceAccountPath))
            {
                Console.WriteLine($"Service account not found: {ServiceAccountPath}");
                return;
            }
            var defaultApp = FirebaseAdmin.FirebaseApp.DefaultInstance;
            if (defaultApp == null)
            {
                defaultApp = FirebaseAdmin.FirebaseApp.Create(new FirebaseAdmin.AppOptions
                {
                    Credential = GoogleCredential.FromFile(ServiceAccountPath)
                });
            }
            var auth = FirebaseAdmin.Auth.FirebaseAuth.GetAuth(defaultApp);
            var u = await auth.GetUserByEmailAsync(email);
            await auth.UpdateUserAsync(new FirebaseAdmin.Auth.UserRecordArgs
            {
                Uid = u.Uid,
                Password = pass
            });
            Console.WriteLine($"SUCCESS: Set password for {email} (UID: {u.Uid}) to {pass}");
            return;
        }

        if (args.Length >= 2 && args[0] == "--resolve-and-update-settings")
        {
            var tenantId = args[1];
            var lat = 11.936607491456765;
            var lon = 79.78181299443752;
            using var geoHttp = new HttpClient();
            geoHttp.DefaultRequestHeaders.UserAgent.ParseAdd("BioMetricPayroll-Admin/1.0 (admin@sridiyaa.com)");
            var geoRes = await geoHttp.GetStringAsync($"https://nominatim.openstreetmap.org/reverse?format=json&lat={lat}&lon={lon}&addressdetails=1");
            using var doc = JsonDocument.Parse(geoRes);
            var root = doc.RootElement;
            var addrObj = root.GetProperty("address");

            string premises = addrObj.TryGetProperty("building", out var bld) ? bld.GetString() ?? "" :
                              addrObj.TryGetProperty("office", out var ofc) ? ofc.GetString() ?? "" :
                              addrObj.TryGetProperty("amenity", out var amn) ? amn.GetString() ?? "" : "";
            string road = addrObj.TryGetProperty("road", out var rd) ? rd.GetString() ?? "" :
                          addrObj.TryGetProperty("suburb", out var sb) ? sb.GetString() ?? "" : "";
            string houseNum = addrObj.TryGetProperty("house_number", out var hn) ? hn.GetString() ?? "" : "";

            string line1 = !string.IsNullOrWhiteSpace(premises) && !string.IsNullOrWhiteSpace(road) ? $"{premises}, {road}" :
                           !string.IsNullOrWhiteSpace(houseNum) && !string.IsNullOrWhiteSpace(road) ? $"{houseNum} {road}" :
                           !string.IsNullOrWhiteSpace(road) ? road :
                           !string.IsNullOrWhiteSpace(premises) ? premises :
                           root.GetProperty("display_name").GetString()?.Split(',')[0].Trim() ?? "";

            string city = addrObj.TryGetProperty("city", out var ct) ? ct.GetString() ?? "" :
                          addrObj.TryGetProperty("town", out var tw) ? tw.GetString() ?? "" :
                          addrObj.TryGetProperty("village", out var vl) ? vl.GetString() ?? "" :
                          addrObj.TryGetProperty("county", out var co) ? co.GetString() ?? "" : "";
            string state = addrObj.TryGetProperty("state", out var st) ? st.GetString() ?? "" : "";
            string pin = addrObj.TryGetProperty("postcode", out var pc) ? pc.GetString() ?? "" : "";

            var cityParts = new List<string>();
            if (!string.IsNullOrWhiteSpace(city)) cityParts.Add(city);
            if (!string.IsNullOrWhiteSpace(state)) cityParts.Add(state);
            string cityStatePin = string.Join(", ", cityParts);
            if (!string.IsNullOrWhiteSpace(pin)) cityStatePin += $" - {pin}";

            Console.WriteLine($"Resolved: Line1='{line1}', CityStatePin='{cityStatePin}'");

            var cred = GoogleCredential.FromFile(ServiceAccountPath)
                .CreateScoped("https://www.googleapis.com/auth/firebase.database", "https://www.googleapis.com/auth/userinfo.email");
            var tok = await cred.UnderlyingCredential.GetAccessTokenForRequestAsync();
            using var fbHttp = new HttpClient();
            var payload = new Dictionary<string, object>
            {
                ["companyName"] = "Sri Diyaa Agencies",
                ["addressLine1"] = line1,
                ["cityStatePincode"] = cityStatePin,
                ["officeLatitude"] = lat,
                ["officeLongitude"] = lon,
                ["geoRadiusMeters"] = 500
            };
            var req = new HttpRequestMessage(HttpMethod.Patch, $"{DatabaseUrl}/owners/{tenantId}/company_settings/1.json")
            {
                Content = new StringContent(JsonSerializer.Serialize(payload), System.Text.Encoding.UTF8, "application/json")
            };
            req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", tok);
            var res = await fbHttp.SendAsync(req);
            Console.WriteLine($"Firebase Update Status: {res.StatusCode}");

            var tenantReq = new HttpRequestMessage(HttpMethod.Patch, $"{DatabaseUrl}/tenants/{tenantId}.json")
            {
                Content = new StringContent(JsonSerializer.Serialize(new Dictionary<string, object> { ["companyName"] = "Sri Diyaa Agencies" }), System.Text.Encoding.UTF8, "application/json")
            };
            tenantReq.Headers.Authorization = new AuthenticationHeaderValue("Bearer", tok);
            await fbHttp.SendAsync(tenantReq);
            Console.WriteLine("Tenant metadata synced successfully!");
            return;
        }

        Console.WriteLine("=========================================================");
        Console.WriteLine("🚀 Firebase Realtime Database Fast Cloud Cleaner");
        Console.WriteLine("   Rule: Keep Employee Master & Settings (Partial Wipe Spec)");
        Console.WriteLine("   Delete: Extra Root Nodes + Operational/Historical Bloat");
        Console.WriteLine("=========================================================\n");

        if (!File.Exists(ServiceAccountPath))
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine($"❌ Service account not found at {ServiceAccountPath}");
            Console.ResetColor();
            return;
        }

        Console.WriteLine("🔑 Authenticating with Google Service Account...");
        var credential = GoogleCredential.FromFile(ServiceAccountPath)
            .CreateScoped("https://www.googleapis.com/auth/firebase.database", "https://www.googleapis.com/auth/userinfo.email");

        var token = await credential.UnderlyingCredential.GetAccessTokenForRequestAsync();
        using var http = new HttpClient();
        http.Timeout = TimeSpan.FromMinutes(2);

        // 1. Inspect Root Nodes
        Console.WriteLine("\n🔍 Inspecting Root Nodes (shallow=true)...");
        var rootKeys = await GetShallowKeysAsync(http, token, "");
        Console.WriteLine($"Discovered {rootKeys.Count} root nodes: {string.Join(", ", rootKeys)}");

        // 2. Delete Unwanted Extra Root Nodes
        var unwantedRoots = new List<string>
        {
            "application_events",
            "client_events",
            "tracking",
            "owner_events",
            "employee_provisioning_status"
        };

        foreach (var rk in rootKeys)
        {
            if (!PreservedRootNodes.Contains(rk) && !unwantedRoots.Contains(rk, StringComparer.OrdinalIgnoreCase))
            {
                unwantedRoots.Add(rk);
            }
        }

        Console.WriteLine("\n🧹 Cleaning Extra Root Nodes...");
        foreach (var extraNode in unwantedRoots)
        {
            if (rootKeys.Contains(extraNode, StringComparer.OrdinalIgnoreCase))
            {
                Console.Write($"  Deleting root '{extraNode}'... ");
                var ok = await DeleteNodeWithChunkingAsync(http, token, extraNode);
                if (ok)
                {
                    Console.ForegroundColor = ConsoleColor.Green;
                    Console.WriteLine("DELETED ✅");
                    Console.ResetColor();
                }
                else
                {
                    Console.ForegroundColor = ConsoleColor.Yellow;
                    Console.WriteLine("SKIPPED / WARNING ⚠️");
                    Console.ResetColor();
                }
            }
            else
            {
                Console.WriteLine($"  Root '{extraNode}' already absent. 👍");
            }
        }

        // 3. Inspect and Clean Operational Tables under owners/{ownerUid}/
        Console.WriteLine("\n🏢 Inspecting 'owners' Tree...");
        var ownerUids = await GetShallowKeysAsync(http, token, "owners");
        if (ownerUids.Count == 0)
        {
            ownerUids.Add("biometricpayroll");
        }

        Console.WriteLine($"Found {ownerUids.Count} owner tenant(s): {string.Join(", ", ownerUids)}");

        foreach (var ownerUid in ownerUids)
        {
            Console.WriteLine($"\n--- Processing Tenant: {ownerUid} ---");
            var tables = await GetShallowKeysAsync(http, token, $"owners/{ownerUid}");
            Console.WriteLine($"  Existing tables: {string.Join(", ", tables)}");

            foreach (var table in tables)
            {
                if (PreservedOwnerTables.Contains(table))
                {
                    Console.ForegroundColor = ConsoleColor.Cyan;
                    Console.WriteLine($"  🟢 PRESERVED: owners/{ownerUid}/{table}");
                    Console.ResetColor();
                    continue;
                }

                Console.Write($"  🗑️ Deleting operational table owners/{ownerUid}/{table}... ");
                var ok = await DeleteNodeWithChunkingAsync(http, token, $"owners/{ownerUid}/{table}");
                if (ok)
                {
                    Console.ForegroundColor = ConsoleColor.Green;
                    Console.WriteLine("DELETED ✅");
                    Console.ResetColor();
                }
                else
                {
                    Console.ForegroundColor = ConsoleColor.Yellow;
                    Console.WriteLine("FAILED / RETRY ⚠️");
                    Console.ResetColor();
                }
            }
        }

        Console.WriteLine("\n=========================================================");
        Console.ForegroundColor = ConsoleColor.Green;
        Console.WriteLine("✨ Cloud Database Cleanup Completed Successfully!");
        Console.WriteLine("   All unwanted bloat deleted.");
        Console.WriteLine("   Employee profiles, settings, and shops safely preserved.");
        Console.ResetColor();
        Console.WriteLine("=========================================================");
    }

    private static async Task<List<string>> GetShallowKeysAsync(HttpClient http, string token, string path)
    {
        var cleanPath = path.Trim('/');
        var url = string.IsNullOrEmpty(cleanPath)
            ? $"{DatabaseUrl}/.json?shallow=true"
            : $"{DatabaseUrl}/{cleanPath}.json?shallow=true";

        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);

        using var resp = await http.SendAsync(req);
        if (!resp.IsSuccessStatusCode)
            return new List<string>();

        var json = await resp.Content.ReadAsStringAsync();
        if (string.IsNullOrWhiteSpace(json) || json == "null")
            return new List<string>();

        try
        {
            using var doc = JsonDocument.Parse(json);
            if (doc.RootElement.ValueKind != JsonValueKind.Object)
                return new List<string>();

            var list = new List<string>();
            foreach (var prop in doc.RootElement.EnumerateObject())
            {
                list.Add(prop.Name);
            }
            return list;
        }
        catch
        {
            return new List<string>();
        }
    }

    private static async Task<bool> DeleteNodeWithChunkingAsync(HttpClient http, string token, string path)
    {
        var cleanPath = path.Trim('/');
        var url = $"{DatabaseUrl}/{cleanPath}.json";

        using var req = new HttpRequestMessage(HttpMethod.Delete, url);
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);

        using var resp = await http.SendAsync(req);
        if (resp.IsSuccessStatusCode)
            return true;

        var err = await resp.Content.ReadAsStringAsync();
        if (err.Contains("exceeds the maximum size", StringComparison.OrdinalIgnoreCase) ||
            err.Contains("too large", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine($"\n    ⚠️ Node '{cleanPath}' is large. Inspecting subkeys...");
            var subKeys = await GetShallowKeysAsync(http, token, cleanPath);
            Console.WriteLine($"    Found {subKeys.Count} subkey(s) under '{cleanPath}'. Fast batch-deleting...");

            foreach (var sk in subKeys)
            {
                var childPath = $"{cleanPath}/{sk}";
                var childUrl = $"{DatabaseUrl}/{childPath}.json";
                using var childReq = new HttpRequestMessage(HttpMethod.Delete, childUrl);
                childReq.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
                using var childResp = await http.SendAsync(childReq);

                if (!childResp.IsSuccessStatusCode)
                {
                    var leafKeys = await GetShallowKeysAsync(http, token, childPath);
                    Console.WriteLine($"      Subkey '{sk}' has {leafKeys.Count} leaves. Batch purging in chunks of 500...");

                    const int batchSize = 500;
                    for (int i = 0; i < leafKeys.Count; i += batchSize)
                    {
                        var count = Math.Min(batchSize, leafKeys.Count - i);
                        var chunk = leafKeys.GetRange(i, count);
                        var patchDict = new Dictionary<string, object?>();
                        foreach (var lk in chunk) patchDict[lk] = null;

                        using var patchReq = new HttpRequestMessage(HttpMethod.Patch, childUrl)
                        {
                            Content = new StringContent(JsonSerializer.Serialize(patchDict), System.Text.Encoding.UTF8, "application/json")
                        };
                        patchReq.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
                        await http.SendAsync(patchReq);
                    }

                    using var cleanChildReq = new HttpRequestMessage(HttpMethod.Delete, childUrl);
                    cleanChildReq.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
                    await http.SendAsync(cleanChildReq);
                }
            }

            // Retry deleting the empty parent
            using var reqRetry = new HttpRequestMessage(HttpMethod.Delete, url);
            reqRetry.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
            using var respRetry = await http.SendAsync(reqRetry);
            return respRetry.IsSuccessStatusCode;
        }

        Console.Write($"[HTTP {(int)resp.StatusCode}: {err.Trim()}] ");
        return false;
    }
}
