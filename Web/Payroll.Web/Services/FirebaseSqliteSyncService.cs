using Payroll.Shared.Firebase;
using System.Globalization;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata;
using Payroll.Shared.Data;

namespace Payroll.Web.Services;

public sealed class FirebaseSqliteSyncService : BackgroundService
{
    private static IReadOnlyDictionary<string, string> Tables => FirebaseSsotSchema.Tables;

    private readonly IServiceScopeFactory _scopeFactory;
    private readonly FirebaseRealtimeService _firebase;
    private readonly IConfiguration _configuration;
    private readonly ILogger<FirebaseSqliteSyncService> _logger;
    private readonly FirebaseSyncWriteScope _firebaseSyncWriteScope;
    private readonly AttendanceRefreshService _refreshService;

    public FirebaseSqliteSyncService(
        IServiceScopeFactory scopeFactory,
        FirebaseRealtimeService firebase,
        IConfiguration configuration,
        ILogger<FirebaseSqliteSyncService> logger,
        FirebaseSyncWriteScope firebaseSyncWriteScope,
        AttendanceRefreshService refreshService)
    {
        _scopeFactory = scopeFactory;
        _firebase = firebase;
        _configuration = configuration;
        _logger = logger;
        _firebaseSyncWriteScope = firebaseSyncWriteScope;
        _refreshService = refreshService;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await Task.Delay(TimeSpan.FromSeconds(2), stoppingToken);

        var ownerUid = _configuration["Firebase:OwnerUid"]?.Trim()
            ?? Environment.GetEnvironmentVariable("FIREBASE_OWNER_UID")?.Trim()
            ?? "biometricpayroll";

        await SyncAllTablesAsync(ownerUid, stoppingToken);

        var ownerTask = RunOwnerStreamLoopAsync(ownerUid, stoppingToken);
        var trackingTask = RunGlobalStreamLoopAsync($"owners/{ownerUid}/tracking", async (path, data, ct) =>
            await ProcessFirebaseTrackingEventAsync(path, data, ct), stoppingToken);
        var authTask = RunGlobalStreamLoopAsync("mobile_auth_events", async (path, data, ct) =>
            await ProcessFirebaseMobileAuthEventAsync(path, data, ct), stoppingToken);

        await Task.WhenAll(ownerTask, trackingTask, authTask);
    }

    private async Task RunOwnerStreamLoopAsync(string ownerUid, CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await _firebase.StreamOwnerChangesAsync(ownerUid, async (relativePath, eventData, ct) =>
                {
                    var target = ParseFirebasePath(relativePath);
                    if (target is null) return;

                    if (target.Value.EntityName == "TrackingHistory") { await ProcessFirebaseTrackingEventAsync(relativePath, eventData, ct); return; }
                    if (target.Value.EntityName == "MobileAuthEvent") { await ProcessFirebaseMobileAuthEventAsync(relativePath, eventData, ct); return; }

                    if (!Tables.TryGetValue(target.Value.EntityName, out var firebaseTable)) return;

                    bool changed;
                    if (eventData.HasValue && eventData.Value.ValueKind == JsonValueKind.Null)
                        changed = await DeleteLocalFirebaseRecordAsync(target.Value.EntityName, target.Value.RecordKey, ct);
                    else
                        changed = await SyncTableAsync(target.Value.EntityName, firebaseTable, ownerUid, ct);

                    if (changed)
                    {
                        await _refreshService.NotifyApplicationDataChangedAsync(new[] { target.Value.EntityName });
                        if (target.Value.EntityName == "CompanySetting")
                        {
                            var settings = await _firebase.GetOwnerRecordAsync(ownerUid, firebaseTable, "1", ct);
                            if (settings.HasValue && settings.Value.ValueKind == JsonValueKind.Object)
                            {
                                var lat = GetDouble(settings.Value, "officeLatitude", "Latitude");
                                var lon = GetDouble(settings.Value, "officeLongitude", "Longitude");
                                var radius = GetInt(settings.Value, "geoRadiusMeters", "GeoRadiusMeters");
                                await _refreshService.NotifyGeoSettingsChangedAsync(lat, lon, radius);
                            }
                        }
                    }
                }, stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { return; }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Firebase owner realtime stream disconnected. Reconnecting.");
                await Task.Delay(TimeSpan.FromSeconds(3), stoppingToken);
            }
        }
    }

    private async Task RunGlobalStreamLoopAsync(string rootNode, Func<string, JsonElement?, CancellationToken, Task> handler, CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try { await _firebase.StreamGlobalChangesAsync(rootNode, handler, stoppingToken); }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { return; }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Firebase {RootNode} realtime stream disconnected. Reconnecting.", rootNode);
                await Task.Delay(TimeSpan.FromSeconds(3), stoppingToken);
            }
        }
    }

    private async Task ProcessFirebaseTrackingEventAsync(string relativePath, JsonElement? eventData, CancellationToken ct, bool evaluateAttendance = true)
    {
        using var geoScope = _scopeFactory.CreateScope();
        var geoLocationService = geoScope.ServiceProvider.GetRequiredService<GeoLocationService>();
        if (!eventData.HasValue || eventData.Value.ValueKind != JsonValueKind.Object) return;

        var path = (relativePath ?? "/").Trim('/');
        var parts = path.Split('/', StringSplitOptions.RemoveEmptyEntries);

        if (parts.Length == 0)
        {
            if (eventData.Value.TryGetProperty("live", out var live) && (live.ValueKind == JsonValueKind.Object || live.ValueKind == JsonValueKind.Array))
            {
                if (live.ValueKind == JsonValueKind.Object)
                    foreach (var emp in live.EnumerateObject()) if (emp.Value.ValueKind == JsonValueKind.Object) await ProcessFirebaseLiveLocationAsync($"/live/{emp.Name}", emp.Value.Clone(), ct);
                else { var i = 0; foreach (var node in live.EnumerateArray()) { if (node.ValueKind == JsonValueKind.Object) await ProcessFirebaseLiveLocationAsync($"/live/{i}", node.Clone(), ct); i++; } }
            }
            if (eventData.Value.TryGetProperty("sessions", out var sessions) && (sessions.ValueKind == JsonValueKind.Object || sessions.ValueKind == JsonValueKind.Array))
            {
                if (sessions.ValueKind == JsonValueKind.Object)
                    foreach (var emp in sessions.EnumerateObject()) if (emp.Value.ValueKind == JsonValueKind.Object) foreach (var sess in emp.Value.EnumerateObject()) await ProcessFirebaseTrackingSessionEventAsync($"/sessions/{emp.Name}/{sess.Name}", sess.Value.Clone(), ct);
                else { var i = 0; foreach (var node in sessions.EnumerateArray()) { if (node.ValueKind == JsonValueKind.Object) foreach (var sess in node.EnumerateObject()) await ProcessFirebaseTrackingSessionEventAsync($"/sessions/{i}/{sess.Name}", sess.Value.Clone(), ct); i++; } }
            }
            if (eventData.Value.TryGetProperty("history", out var history) && (history.ValueKind == JsonValueKind.Object || history.ValueKind == JsonValueKind.Array))
            {
                if (history.ValueKind == JsonValueKind.Object)
                    foreach (var emp in history.EnumerateObject()) if (emp.Value.ValueKind == JsonValueKind.Object) foreach (var evt in emp.Value.EnumerateObject()) await ProcessFirebaseTrackingEventAsync($"/history/{emp.Name}/{evt.Name}", evt.Value.Clone(), ct, false);
                else { var i = 0; foreach (var node in history.EnumerateArray()) { if (node.ValueKind == JsonValueKind.Object) foreach (var evt in node.EnumerateObject()) await ProcessFirebaseTrackingEventAsync($"/history/{i}/{evt.Name}", evt.Value.Clone(), ct, false); i++; } }
            }
            return;
        }

        if (parts.Any(p => p.Equals("events", StringComparison.OrdinalIgnoreCase))) { await ProcessFirebaseTrackingLifecycleEventAsync(eventData.Value, ct); return; }
        if (parts.Any(p => p.Equals("live", StringComparison.OrdinalIgnoreCase))) { await ProcessFirebaseLiveLocationAsync(relativePath ?? "/", eventData.Value, ct); return; }
        if (parts.Any(p => p.Equals("sessions", StringComparison.OrdinalIgnoreCase))) { await ProcessFirebaseTrackingSessionEventAsync(relativePath ?? "/", eventData.Value, ct); return; }

        if (!parts.Any(p => p.Equals("history", StringComparison.OrdinalIgnoreCase))) return;

        var employeeId = GetInt(eventData.Value, "EmployeeId", "employeeId");
        var sessionText = GetString(eventData.Value, "SessionId", "sessionId");
        if (employeeId <= 0 || !Guid.TryParse(sessionText, out var sessionId) || sessionId == Guid.Empty) return;

        var lat = GetDouble(eventData.Value, "Latitude", "latitude");
        var lon = GetDouble(eventData.Value, "Longitude", "longitude");
        var acc = GetDouble(eventData.Value, "AccuracyMeters", "accuracyMeters");
        var dist = GetDouble(eventData.Value, "DistanceMeters", "distanceMeters");
        var rad = GetInt(eventData.Value, "AllowedRadiusMeters", "allowedRadiusMeters");
        var within = GetBool(eventData.Value, "IsWithinAllowedRadius", "isWithinAllowedRadius");
        var cap = GetDateTime(eventData.Value, "Timestamp", "timestamp") ?? DateTime.UtcNow;
        var src = GetString(eventData.Value, "CaptureSource", "Source") ?? "Online";

        if (rad <= 0 || dist < 0) { var res = await geoLocationService.GetDistanceFromOfficeAsync(lat, lon); if (res.Success) { dist = res.DistanceMeters; rad = res.AllowedRadiusMeters; within = rad > 0 && dist <= rad + 2; } }
        if (evaluateAttendance) await geoLocationService.UpdateGpsSessionAsync(employeeId, sessionId, lat, lon, acc, dist, rad, within, cap);
        await geoLocationService.SaveLocationHistoryAsync(employeeId, sessionId, lat, lon, dist, rad, within, acc, cap, src);
    }

    private async Task ProcessFirebaseTrackingLifecycleEventAsync(JsonElement eventData, CancellationToken ct)
    {
        if (eventData.ValueKind != JsonValueKind.Object) return;
        var nested = eventData.TryGetProperty("event", out var node) && node.ValueKind == JsonValueKind.Object ? node : eventData;
        var type = GetString(nested, "eventType", "EventType") ?? string.Empty;
        var empId = GetInt(eventData, "employeeId", "EmployeeId");
        if (empId <= 0) empId = GetInt(nested, "employeeId", "EmployeeId");
        var sess = GetString(nested, "sessionId", "SessionId");
        if (empId <= 0 || !Guid.TryParse(sess, out var sid) || sid == Guid.Empty) return;

        using var scope = _scopeFactory.CreateScope();
        var geo = scope.ServiceProvider.GetRequiredService<GeoLocationService>();

        if (type.Equals("SESSION_STARTED", StringComparison.OrdinalIgnoreCase))
        {
            if (await geo.StartGpsSessionAsync(empId, sid)) await _refreshService.NotifyLocationChangedAsync(empId);
        }
        else if (type.Equals("SESSION_ENDED", StringComparison.OrdinalIgnoreCase))
        {
            var msg = GetString(nested, "message", "Message") ?? string.Empty;
            var reason = msg.Contains("Reason:") ? msg.Split("Reason:").Last().Trim() : "OFFLINE_SYNC";
            await geo.EndGpsSessionAsync(empId, sid, reason);
            await _refreshService.NotifyLocationChangedAsync(empId);
        }
    }

    private async Task ProcessFirebaseTrackingSessionEventAsync(string relativePath, JsonElement? eventData, CancellationToken ct)
    {
        using var scope = _scopeFactory.CreateScope();
        var geo = scope.ServiceProvider.GetRequiredService<GeoLocationService>();
        if (!eventData.HasValue || eventData.Value.ValueKind != JsonValueKind.Object) return;

        var empId = GetInt(eventData.Value, "EmployeeId", "employeeId");
        var sess = GetString(eventData.Value, "SessionId", "sessionId");
        if (empId <= 0 || !Guid.TryParse(sess, out var sid) || sid == Guid.Empty) return;

        var ended = GetString(eventData.Value, "EndedAtUtc", "endedAtUtc");
        if (string.IsNullOrWhiteSpace(ended))
        {
            if (await geo.StartGpsSessionAsync(empId, sid)) await _refreshService.NotifyLocationChangedAsync(empId);
        }
        else
        {
            var reason = GetString(eventData.Value, "EndReason", "endReason") ?? "LOGGED_OUT";
            await geo.EndGpsSessionAsync(empId, sid, reason);
            await _refreshService.NotifyLocationChangedAsync(empId);
        }
    }

    private async Task ProcessFirebaseLiveLocationAsync(string relativePath, JsonElement? eventData, CancellationToken ct)
    {
        using var scope = _scopeFactory.CreateScope();
        var geo = scope.ServiceProvider.GetRequiredService<GeoLocationService>();
        if (!eventData.HasValue)
        {
            var key = (relativePath ?? "/").Trim('/').Split('/').LastOrDefault();
            if (int.TryParse(key, out var id))
            {
                var currentSid = LiveLocationStore.GetSessionId(id);
                if (currentSid.HasValue) LiveLocationStore.Remove(id, currentSid.Value);
                await _refreshService.NotifyLocationChangedAsync(id);
            }
            return;
        }

        var empId = GetInt(eventData.Value, "EmployeeId", "employeeId");
        if (empId <= 0) { var key = (relativePath ?? "/").Trim('/').Split('/').LastOrDefault(); empId = int.TryParse(key, out var id) ? id : 0; }
        if (empId <= 0) return;

        var sess = GetString(eventData.Value, "SessionId", "sessionId");
        if (!Guid.TryParse(sess, out var sid) || sid == Guid.Empty) return;

        var lat = GetDouble(eventData.Value, "Latitude", "latitude");
        var lon = GetDouble(eventData.Value, "Longitude", "longitude");
        var acc = Math.Max(0, GetDouble(eventData.Value, "AccuracyMeters", "accuracyMeters"));
        var cap = GetDateTime(eventData.Value, "Timestamp", "timestamp") ?? DateTime.UtcNow;

        if (!double.IsFinite(lat) || !double.IsFinite(lon) || Math.Abs(lat) > 90 || Math.Abs(lon) > 180) return;

        var res = await geo.GetDistanceFromOfficeAsync(lat, lon);
        if (!res.Success) return;

        if (await geo.UpdateGpsSessionAsync(empId, sid, lat, lon, acc, res.DistanceMeters, res.AllowedRadiusMeters, res.IsWithinAllowedRadius, cap))
            await _refreshService.NotifyLocationChangedAsync(empId);
    }

    private static string? GetString(JsonElement el, params string[] names)
    {
        foreach (var n in names) if (el.TryGetProperty(n, out var v) && v.ValueKind == JsonValueKind.String) return v.GetString();
        return null;
    }

    private static int GetInt(JsonElement el, params string[] names)
    {
        foreach (var n in names)
        {
            if (!el.TryGetProperty(n, out var v)) continue;
            if (v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out var i)) return i;
            if (v.ValueKind == JsonValueKind.String && int.TryParse(v.GetString(), NumberStyles.Any, CultureInfo.InvariantCulture, out i)) return i;
        }
        return 0;
    }

    private static double GetDouble(JsonElement el, params string[] names)
    {
        foreach (var n in names)
        {
            if (!el.TryGetProperty(n, out var v)) continue;
            if (v.ValueKind == JsonValueKind.Number && v.TryGetDouble(out var d)) return d;
            if (v.ValueKind == JsonValueKind.String && double.TryParse(v.GetString(), NumberStyles.Any, CultureInfo.InvariantCulture, out d)) return d;
        }
        return 0;
    }

    private static bool GetBool(JsonElement el, params string[] names)
    {
        foreach (var n in names)
        {
            if (!el.TryGetProperty(n, out var v)) continue;
            if (v.ValueKind == JsonValueKind.True) return true;
            if (v.ValueKind == JsonValueKind.False) return false;
            if (v.ValueKind == JsonValueKind.String && bool.TryParse(v.GetString(), out var b)) return b;
        }
        return false;
    }

    private static DateTime? GetDateTime(JsonElement el, params string[] names)
    {
        var text = GetString(el, names);
        return DateTime.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var dt) ? dt.ToUniversalTime() : null;
    }

    private async Task ProcessFirebaseMobileAuthEventAsync(string relativePath, JsonElement? eventData, CancellationToken ct)
    {
        if (!eventData.HasValue || eventData.Value.ValueKind != JsonValueKind.Object) return;
        var path = (relativePath ?? "/").Trim('/');
        if (string.IsNullOrWhiteSpace(path))
        {
            foreach (var node in eventData.Value.EnumerateObject())
                if (node.Value.ValueKind == JsonValueKind.Object)
                    foreach (var evt in node.Value.EnumerateObject())
                        if (evt.Value.ValueKind == JsonValueKind.Object)
                            await ProcessFirebaseMobileAuthEventAsync($"/{node.Name}/{evt.Name}", evt.Value.Clone(), ct);
            return;
        }

        var empId = GetInt(eventData.Value, "employeeId", "EmployeeId");
        var type = GetString(eventData.Value, "eventType", "EventType") ?? "MOBILE_AUTH_EVENT";
        var uid = GetString(eventData.Value, "firebaseUid", "FirebaseUid") ?? "";
        var email = GetString(eventData.Value, "email", "Email") ?? "";
        var devId = GetString(eventData.Value, "deviceId", "DeviceId") ?? "";
        var platform = GetString(eventData.Value, "platform", "Platform") ?? "Android";
        var ts = GetDateTime(eventData.Value, "timestamp", "Timestamp") ?? DateTime.UtcNow;
        var eid = GetString(eventData.Value, "eventId", "EventId") ?? path.Split('/').LastOrDefault();

        if (empId <= 0 || string.IsNullOrWhiteSpace(eid)) return;

        await using var scope = _scopeFactory.CreateAsyncScope();
        var factory = scope.ServiceProvider.GetRequiredService<IDbContextFactory<AppDbContext>>();
        await using var db = await factory.CreateDbContextAsync(ct);

        var marker = $"FirebaseMobileAuth:{eid}";
        if (await db.AuditLogs.AsNoTracking().AnyAsync(x => x.EntityID == marker, ct)) return;

        var log = new AuditLog
        {
            Timestamp = ts,
            UserID = string.IsNullOrWhiteSpace(uid) ? $"ANDROID_EMPLOYEE_{empId}" : uid,
            UserEmail = string.IsNullOrWhiteSpace(email) ? "Android" : email,
            ActionType = type.Length > 50 ? type[..50] : type,
            EntityType = "EmployeeSession",
            EntityID = marker,
            Details = JsonSerializer.Serialize(new { EventType = type, Platform = platform, DeviceId = devId, FirebaseUid = uid, RecordedAtUtc = ts.ToString("O"), EventId = eid })
        };

        using var syncScope = _firebaseSyncWriteScope.Enter();
        db.AuditLogs.Add(log);
        await db.SaveChangesAsync(ct);
        await _refreshService.NotifyApplicationDataChangedAsync(new[] { "AuditLog" });
    }

    private async Task SyncAllTablesAsync(string ownerUid, CancellationToken ct)
    {
        foreach (var table in Tables) { ct.ThrowIfCancellationRequested(); await SyncTableAsync(table.Key, table.Value, ownerUid, ct); }
    }

    private async Task<bool> SyncTableAsync(string entityName, string firebaseTable, string ownerUid, CancellationToken ct)
    {
        if (entityName == "EmployeeLocationHistory") return await SyncTrackingHistoryAsync(ownerUid, ct);
        var json = await _firebase.GetOwnerTableAsync(ownerUid, firebaseTable, ct);
        if (!json.HasValue) return false;

        if (json.Value.ValueKind == JsonValueKind.Array)
        {
            var normalized = new Dictionary<string, JsonElement>(StringComparer.Ordinal);
            var i = 0; foreach (var item in json.Value.EnumerateArray()) { if (item.ValueKind != JsonValueKind.Null) normalized[i.ToString()] = item.Clone(); i++; }
            return await UpsertTableAsync(entityName, JsonSerializer.SerializeToElement(normalized), ct);
        }

        if (json.Value.ValueKind != JsonValueKind.Object) return false;
        return await UpsertTableAsync(entityName, json.Value, ct);
    }

    private async Task<bool> SyncTrackingHistoryAsync(string ownerUid, CancellationToken ct)
    {
        var json = await _firebase.GetOwnerTableAsync(ownerUid, "tracking/history", ct);
        if (!json.HasValue || (json.Value.ValueKind != JsonValueKind.Object && json.Value.ValueKind != JsonValueKind.Array)) return false;

        var changed = false;
        async Task ProcessNode(JsonElement node)
        {
            if (node.ValueKind != JsonValueKind.Object) return;
            await using var scope = _scopeFactory.CreateAsyncScope();
            var factory = scope.ServiceProvider.GetRequiredService<IDbContextFactory<AppDbContext>>();
            await using var db = await factory.CreateDbContextAsync(ct);
            var et = db.Model.GetEntityTypes().First(x => x.ClrType.Name == "EmployeeLocationHistory");
            var keys = et.FindPrimaryKey()?.Properties;
            var empChanged = false; var count = 0;
            foreach (var evt in node.EnumerateObject())
            {
                if (evt.Value.ValueKind != JsonValueKind.Object) continue;
                try
                {
                    empChanged |= await UpsertRecordAsync(db, et, keys!, evt.Name, evt.Value, ct);
                    if (++count % 100 == 0) { if (empChanged) { using var syncScope = _firebaseSyncWriteScope.Enter(); await db.SaveChangesAsync(ct); empChanged = false; changed = true; } db.ChangeTracker.Clear(); }
                }
                catch { }
            }
            if (empChanged) { changed = true; using var syncScope = _firebaseSyncWriteScope.Enter(); await db.SaveChangesAsync(ct); }
        }

        if (json.Value.ValueKind == JsonValueKind.Object) foreach (var node in json.Value.EnumerateObject()) await ProcessNode(node.Value);
        else foreach (var node in json.Value.EnumerateArray()) await ProcessNode(node);
        return changed;
    }

    private async Task<bool> DeleteLocalFirebaseRecordAsync(string entityName, string? firebaseKey, CancellationToken ct)
    {
        await using var scope = _scopeFactory.CreateAsyncScope();
        var factory = scope.ServiceProvider.GetRequiredService<IDbContextFactory<AppDbContext>>();
        await using var db = await factory.CreateDbContextAsync(ct);
        var et = db.Model.GetEntityTypes().FirstOrDefault(x => x.ClrType.Name == entityName);
        if (et == null) return false;
        if (string.IsNullOrWhiteSpace(firebaseKey))
        {
            var rows = await GetEntitySet(db, et.ClrType).Cast<object>().ToListAsync(ct);
            if (rows.Count == 0) return false;
            using var tableScope = _firebaseSyncWriteScope.Enter();
            db.RemoveRange(rows); await db.SaveChangesAsync(ct); return true;
        }
        var keys = et.FindPrimaryKey()?.Properties;
        if (keys == null || keys.Count == 0) return false;
        var parts = firebaseKey.Split('|');
        if (parts.Length < keys.Count) return false;
        var keyValues = new object?[keys.Count];
        for (var i = 0; i < keys.Count; i++) { keyValues[i] = ConvertStringValue(parts[i], keys[i].ClrType); if (keyValues[i] == null) return false; }
        var existing = await db.FindAsync(et.ClrType, keyValues, ct);
        if (existing == null) return false;
        using var syncScope = _firebaseSyncWriteScope.Enter();
        db.Remove(existing); await db.SaveChangesAsync(ct); return true;
    }

    private async Task<bool> UpsertTableAsync(string entityName, JsonElement table, CancellationToken ct)
    {
        await using var scope = _scopeFactory.CreateAsyncScope();
        var factory = scope.ServiceProvider.GetRequiredService<IDbContextFactory<AppDbContext>>();
        await using var db = await factory.CreateDbContextAsync(ct);
        var et = db.Model.GetEntityTypes().FirstOrDefault(x => x.ClrType.Name == entityName);
        if (et == null) return false;
        var keys = et.FindPrimaryKey()?.Properties;
        if (keys == null || keys.Count == 0) return false;

        var changedAny = false; var count = 0;
        foreach (var child in table.EnumerateObject())
        {
            if (child.Value.ValueKind != JsonValueKind.Object) continue;
            try
            {
                changedAny |= await UpsertRecordAsync(db, et, keys, child.Name, child.Value, ct);
                if (++count % 100 == 0) { if (changedAny) { using var syncScope = _firebaseSyncWriteScope.Enter(); await db.SaveChangesAsync(ct); changedAny = false; } db.ChangeTracker.Clear(); }
            }
            catch (Exception ex) { _logger.LogDebug(ex, "Skipping Firebase row {Entity}/{Key}.", entityName, child.Name); }
        }
        if (changedAny) { using var syncScope = _firebaseSyncWriteScope.Enter(); await db.SaveChangesAsync(ct); }
        return true;
    }

    private static async Task<bool> UpsertRecordAsync(AppDbContext db, IEntityType et, IReadOnlyList<IProperty> keys, string firebaseKey, JsonElement json, CancellationToken ct)
    {
        var keyParts = firebaseKey.Split('|');
        var keyValues = new object?[keys.Count];
        for (var i = 0; i < keys.Count; i++)
        {
            var v = FindJsonValue(json, keys[i].Name);
            if (v.HasValue) keyValues[i] = ConvertValue(v.Value, keys[i].ClrType);
            else if (keyParts.Length > i) keyValues[i] = ConvertStringValue(keyParts[i], keys[i].ClrType);
            if (keyValues[i] != null && keyValues[i]!.GetType() != keys[i].ClrType)
                try { keyValues[i] = Convert.ChangeType(keyValues[i], Nullable.GetUnderlyingType(keys[i].ClrType) ?? keys[i].ClrType, CultureInfo.InvariantCulture); } catch { }
        }

        if (keyValues.Any(x => x == null)) return false;
        var existing = await db.FindAsync(et.ClrType, keyValues, ct);
        if (existing == null)
            existing = db.ChangeTracker.Entries().Where(e => e.Metadata == et).Select(e => e.Entity).FirstOrDefault(e =>
            {
                for (int i = 0; i < keys.Count; i++) { var val = db.Entry(e).Property(keys[i]!.Name).CurrentValue; if (!AreKeysEqual(val, keyValues[i])) return false; }
                return true;
            });

        var target = existing ?? Activator.CreateInstance(et.ClrType);
        if (target == null) return false;
        if (existing == null) for (int i = 0; i < keys.Count; i++) if (keys[i].PropertyInfo != null) keys[i].PropertyInfo.SetValue(target, keyValues[i]);

        var changed = false;
        foreach (var prop in et.GetProperties())
        {
            if (prop.IsShadowProperty() || prop.PropertyInfo == null || keys.Any(k => k.Name == prop.Name)) continue;
            var v = FindJsonValue(json, prop.Name);
            if (!v.HasValue) continue;
            var conv = ConvertValue(v.Value, prop.ClrType);
            if (et.ClrType.Name == "Employee" && prop.Name == "StandardBreakMinutes" && v.Value.ValueKind == JsonValueKind.Number && v.Value.TryGetDouble(out var h)) conv = (int)Math.Round(h * 60d);
            if (et.ClrType.Name == "Employee" && prop.Name == "IsDeleted" && conv is bool active) conv = !active;
            if (conv == null && Nullable.GetUnderlyingType(prop.ClrType) == null && prop.ClrType.IsValueType) continue;
            var cur = prop.PropertyInfo.GetValue(target);
            if (!Equals(cur, conv)) { prop.PropertyInfo.SetValue(target, conv); changed = true; }
        }

        if (existing == null && changed) db.Add(target);
        else if (existing != null && changed && db.Entry(target).State == EntityState.Unchanged) db.Entry(target).State = EntityState.Modified;
        return changed;
    }

    private static JsonElement? FindJsonValue(JsonElement json, string propName)
    {
        var wanted = Normalize(propName);
        foreach (var p in json.EnumerateObject()) { if (Normalize(p.Name) == wanted || AliasMatches(propName, p.Name)) return p.Value; }
        return null;
    }

    private static bool AliasMatches(string clr, string fb) => (Normalize(clr), Normalize(fb)) switch
    {
        ("logid", "attendanceid") => true, ("leaverequestid", "id") => true, ("regularizationid", "id") => true,
        ("employeeid", "staffid") => true, ("monthlysalary", "salaryrate") => true, ("standardbreakminutes", "breakhours") => true,
        ("payrolltypeoverride", "salarytype") => true, ("terminationdate", "terminatedate") => true, ("isdeleted", "isactive") => true,
        _ => false
    };

    private static string Normalize(string v) => new(v.Where(char.IsLetterOrDigit).Select(char.ToLowerInvariant).ToArray());

    private static IQueryable GetEntitySet(DbContext db, Type et)
    {
        var method = typeof(DbContext).GetMethods().First(m => m.Name == "Set" && m.IsGenericMethodDefinition && m.GetGenericArguments().Length == 1 && m.GetParameters().Length == 0);
        return (IQueryable)method.MakeGenericMethod(et).Invoke(db, null)!;
    }

    private static object? ConvertStringValue(string v, Type target)
    {
        var type = Nullable.GetUnderlyingType(target) ?? target;
        if (string.IsNullOrWhiteSpace(v)) return null;
        try
        {
            if (type == typeof(string)) return v; if (type == typeof(int)) return int.Parse(v, CultureInfo.InvariantCulture);
            if (type == typeof(long)) return long.Parse(v, CultureInfo.InvariantCulture); if (type == typeof(decimal)) return decimal.Parse(v, CultureInfo.InvariantCulture);
            if (type == typeof(double)) return double.Parse(v, CultureInfo.InvariantCulture); if (type == typeof(bool)) return bool.Parse(v);
            if (type == typeof(Guid)) return Guid.Parse(v); if (type == typeof(DateTime)) return DateTime.Parse(v, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind);
            if (type.IsEnum) return Enum.Parse(type, v, true); return v;
        }
        catch { return null; }
    }

    private static object? ConvertValue(JsonElement v, Type target)
    {
        if (v.ValueKind == JsonValueKind.Null) return null;
        var type = Nullable.GetUnderlyingType(target) ?? target;
        try
        {
            if (type == typeof(string)) return v.ValueKind == JsonValueKind.String ? v.GetString() : v.ToString();
            if (type == typeof(int)) { if (v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out var i)) return i; var s = v.ToString(); if (int.TryParse(s, out i)) return i; if (double.TryParse(s, out var d)) return (int)Math.Round(d); return 0; }
            if (type == typeof(long)) { if (v.ValueKind == JsonValueKind.Number && v.TryGetInt64(out var l)) return l; var s = v.ToString(); if (long.TryParse(s, out l)) return l; if (double.TryParse(s, out var d)) return (long)Math.Round(d); return 0L; }
            if (type == typeof(bool)) { if (v.ValueKind == JsonValueKind.True) return true; if (v.ValueKind == JsonValueKind.False) return false; var s = v.ToString(); if (bool.TryParse(s, out var b)) return b; return false; }
            if (type == typeof(DateTime)) { if (v.ValueKind == JsonValueKind.Number && v.TryGetInt64(out var ms)) return DateTimeOffset.FromUnixTimeMilliseconds(ms).UtcDateTime; var s = v.ToString(); if (long.TryParse(s, out var pms)) return DateTimeOffset.FromUnixTimeMilliseconds(pms).UtcDateTime; if (DateTime.TryParse(s, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var dt)) return dt.ToUniversalTime(); return DateTime.MinValue; }
            if (type.IsEnum) return Enum.Parse(type, v.ToString(), true);
            return JsonSerializer.Deserialize(v.GetRawText(), type);
        }
        catch { return null; }
    }

    private static bool AreKeysEqual(object? a, object? b)
    {
        if (a == null && b == null) return true; if (a == null || b == null) return false; if (Equals(a, b)) return true;
        if (IsNumeric(a) && IsNumeric(b)) try { return Convert.ToInt64(a) == Convert.ToInt64(b); } catch { try { return Convert.ToDouble(a) == Convert.ToDouble(b); } catch { } }
        return string.Equals(a.ToString(), b.ToString(), StringComparison.OrdinalIgnoreCase);
    }

    private static bool IsNumeric(object? o)
    {
        if (o == null) return false; var t = o.GetType();
        return t == typeof(int) || t == typeof(long) || t == typeof(short) || t == typeof(byte) || t == typeof(uint) || t == typeof(ulong) || t == typeof(float) || t == typeof(double) || t == typeof(decimal);
    }

    private readonly record struct FirebasePathTarget(string EntityName, string? RecordKey);
    private static FirebasePathTarget? ParseFirebasePath(string path)
    {
        var norm = (path ?? "/").Trim('/'); if (string.IsNullOrWhiteSpace(norm)) return null;
        var parts = norm.Split('/'); var tbl = parts[0];
        if (tbl.Equals("tracking", StringComparison.OrdinalIgnoreCase)) return new FirebasePathTarget("TrackingHistory", parts.Length >= 4 ? parts[3] : null);
        if (tbl.Equals("mobile_auth_events", StringComparison.OrdinalIgnoreCase)) return new FirebasePathTarget("MobileAuthEvent", parts.Length >= 3 ? parts[2] : null);
        var ent = Tables.FirstOrDefault(x => string.Equals(x.Value, tbl, StringComparison.Ordinal));
        if (string.IsNullOrWhiteSpace(ent.Key)) return null;
        if (parts.Length == 1) return new FirebasePathTarget(ent.Key, null);
        var key = Uri.UnescapeDataString(parts[1]).Replace("%2E", ".").Replace("%23", "#").Replace("%24", "$").Replace("%5B", "[").Replace("%5D", "]").Replace("%2F", "/");
        return new FirebasePathTarget(ent.Key, key);
    }
}
