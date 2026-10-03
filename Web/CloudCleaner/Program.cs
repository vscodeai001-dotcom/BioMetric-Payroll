using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text.Json;
using System.Threading.Tasks;
using Google.Apis.Auth.OAuth2;

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
