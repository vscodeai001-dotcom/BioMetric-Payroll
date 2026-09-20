using Payroll.Shared.Firebase;
using System.Globalization;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata;

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

        var ownerUid = _configuration["Firebase:OwnerUid"]?.Trim();
        if (string.IsNullOrWhiteSpace(ownerUid))
            ownerUid = Environment.GetEnvironmentVariable("FIREBASE_OWNER_UID")?.Trim();
        if (string.IsNullOrWhiteSpace(ownerUid))
            ownerUid = "biometricpayroll";

        // First hydrate the local compatibility projection so the existing Web
        // screens have a complete initial view of the Firebase SSOT.
        await SyncAllTablesAsync(ownerUid, stoppingToken);

        // From this point forward Firebase's REST event streams are the trigger.
        // Owner CRUD and native Android GPS are separate Firebase trees but are
        // consumed by this same compatibility bridge so the existing Web UI
        // updates without a browser refresh.
        var ownerTask = RunOwnerStreamLoopAsync(ownerUid, stoppingToken);

        // REQUIREMENT: Sync tracking history and events from the owner-scoped node.
        // Android now writes history to /owners/{ownerUid}/tracking/history to
        // maintain tenant isolation.
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
                await _firebase.StreamOwnerChangesAsync(
                    ownerUid,
                    async (relativePath, eventData, ct) =>
                    {
                        var target = ParseFirebasePath(relativePath);
                        if (target is null) return;

                        if (target.Value.EntityName.Equals("TrackingHistory", StringComparison.Ordinal))
                        {
                            await ProcessFirebaseTrackingEventAsync(relativePath, eventData, ct);
                            return;
                        }

                        if (target.Value.EntityName.Equals("MobileAuthEvent", StringComparison.Ordinal))
                        {
                            await ProcessFirebaseMobileAuthEventAsync(relativePath, eventData, ct);
                            return;
                        }

                        if (!Tables.TryGetValue(target.Value.EntityName, out var firebaseTable)) return;

                        bool changed;
                        if (eventData.HasValue && eventData.Value.ValueKind == JsonValueKind.Null)
                        {
                            changed = await DeleteLocalFirebaseRecordAsync(
                                target.Value.EntityName, target.Value.RecordKey, ct);
                        }
                        else
                        {
                            changed = await SyncTableAsync(
                                target.Value.EntityName, firebaseTable, ownerUid, ct);
                        }

                        if (changed)
                        {
                            await _refreshService.NotifyApplicationDataChangedAsync(
                                new[] { target.Value.EntityName });

                            // MIRROR: If company settings changed, notify live maps to update the geofence circle.
                            if (target.Value.EntityName.Equals("CompanySetting", StringComparison.Ordinal))
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
                    },
                    stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Firebase owner realtime stream disconnected. Reconnecting.");
                await Task.Delay(TimeSpan.FromSeconds(3), stoppingToken);
            }
        }
    }

    private async Task RunGlobalStreamLoopAsync(
        string rootNode,
        Func<string, JsonElement?, CancellationToken, Task> handler,
        CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await _firebase.StreamGlobalChangesAsync(rootNode, handler, stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Firebase {RootNode} realtime stream disconnected. Reconnecting.", rootNode);
                await Task.Delay(TimeSpan.FromSeconds(3), stoppingToken);
            }
        }
    }

    private async Task ProcessFirebaseTrackingEventAsync(
        string relativePath,
        JsonElement? eventData,
        CancellationToken ct,
        bool evaluateAttendance = true)
    {
        // GeoLocationService is scoped. FirebaseSqliteSyncService is a singleton
        // hosted service, so resolve the scoped service inside a short-lived scope
        // for each Firebase event instead of injecting it into the hosted service.
        using var geoScope = _scopeFactory.CreateScope();
        var geoLocationService = geoScope.ServiceProvider.GetRequiredService<GeoLocationService>();

        if (!eventData.HasValue || eventData.Value.ValueKind != JsonValueKind.Object) return;

        var path = (relativePath ?? "/").Trim('/');
        var parts = path.Split('/', StringSplitOptions.RemoveEmptyEntries);

        // Firebase sends the initial tracking snapshot at /. Hydrate both the
        // current live branch and immutable history branch. History replay is
        // deliberately attendance-neutral; live data only updates the Web
        // in-memory live store and never creates a second punch/history row.
        if (parts.Length == 0)
        {
            if (eventData.Value.TryGetProperty("live", out var live) &&
                (live.ValueKind == JsonValueKind.Object || live.ValueKind == JsonValueKind.Array))
            {
                if (live.ValueKind == JsonValueKind.Object)
                {
                    foreach (var employeeNode in live.EnumerateObject())
                    {
                        if (employeeNode.Value.ValueKind == JsonValueKind.Object)
                            await ProcessFirebaseLiveLocationAsync(
                                $"/live/{employeeNode.Name}",
                                employeeNode.Value.Clone(), ct);
                    }
                }
                else
                {
                    var index = 0;
                    foreach (var node in live.EnumerateArray())
                    {
                        if (node.ValueKind == JsonValueKind.Object)
                            await ProcessFirebaseLiveLocationAsync(
                                $"/live/{index}",
                                node.Clone(), ct);
                        index++;
                    }
                }
            }

            if (eventData.Value.TryGetProperty("sessions", out var sessions) &&
                (sessions.ValueKind == JsonValueKind.Object || sessions.ValueKind == JsonValueKind.Array))
            {
                if (sessions.ValueKind == JsonValueKind.Object)
                {
                    foreach (var employeeNode in sessions.EnumerateObject())
                    {
                        if (employeeNode.Value.ValueKind != JsonValueKind.Object) continue;
                        foreach (var sessionNode in employeeNode.Value.EnumerateObject())
                        {
                            if (sessionNode.Value.ValueKind == JsonValueKind.Object)
                                await ProcessFirebaseTrackingSessionEventAsync(
                                    $"/sessions/{employeeNode.Name}/{sessionNode.Name}",
                                    sessionNode.Value.Clone(), ct);
                        }
                    }
                }
                else
                {
                    var index = 0;
                    foreach (var employeeNode in sessions.EnumerateArray())
                    {
                        if (employeeNode.ValueKind == JsonValueKind.Object)
                        {
                            foreach (var sessionNode in employeeNode.EnumerateObject())
                            {
                                if (sessionNode.Value.ValueKind == JsonValueKind.Object)
                                    await ProcessFirebaseTrackingSessionEventAsync(
                                        $"/sessions/{index}/{sessionNode.Name}",
                                        sessionNode.Value.Clone(), ct);
                            }
                        }
                        index++;
                    }
                }
            }

            if (eventData.Value.TryGetProperty("history", out var history) &&
                (history.ValueKind == JsonValueKind.Object || history.ValueKind == JsonValueKind.Array))
            {
                if (history.ValueKind == JsonValueKind.Object)
                {
                    foreach (var employeeNode in history.EnumerateObject())
                    {
                        if (employeeNode.Value.ValueKind != JsonValueKind.Object) continue;
                        foreach (var eventNode in employeeNode.Value.EnumerateObject())
                        {
                            if (eventNode.Value.ValueKind == JsonValueKind.Object)
                                await ProcessFirebaseTrackingEventAsync(
                                    $"/history/{employeeNode.Name}/{eventNode.Name}",
                                    eventNode.Value.Clone(), ct, evaluateAttendance: false);
                        }
                    }
                }
                else
                {
                    var index = 0;
                    foreach (var employeeNode in history.EnumerateArray())
                    {
                        if (employeeNode.ValueKind == JsonValueKind.Object)
                        {
                            foreach (var eventNode in employeeNode.EnumerateObject())
                            {
                                if (eventNode.Value.ValueKind == JsonValueKind.Object)
                                    await ProcessFirebaseTrackingEventAsync(
                                        $"/history/{index}/{eventNode.Name}",
                                        eventNode.Value.Clone(), ct, evaluateAttendance: false);
                            }
                        }
                        index++;
                    }
                }
            }
            return;
        }

        // 1200-M: Background tracking lifecycle events are durably queued on
        // Android when Firebase is temporarily unavailable. Replaying these
        // events restores the exact GPS session boundary before/after queued
        // location points are processed, without changing attendance formulas.
        if (parts.Any(p => p.Equals("events", StringComparison.OrdinalIgnoreCase)))
        {
            await ProcessFirebaseTrackingLifecycleEventAsync(eventData.Value, ct);
            return;
        }

        if (parts.Any(p => p.Equals("live", StringComparison.OrdinalIgnoreCase)))
        {
            await ProcessFirebaseLiveLocationAsync(
                relativePath ?? "/",
                eventData.Value,
                ct);
            return;
        }

        if (parts.Any(p => p.Equals("sessions", StringComparison.OrdinalIgnoreCase)))
        {
            await ProcessFirebaseTrackingSessionEventAsync(
                relativePath ?? "/",
                eventData.Value,
                ct);
            return;
        }

        if (!parts.Any(p => p.Equals("history", StringComparison.OrdinalIgnoreCase)))
            return;

        var employeeId = GetInt(eventData.Value, "EmployeeId", "employeeId");
        if (employeeId <= 0) return;

        var sessionText = GetString(eventData.Value, "SessionId", "sessionId");
        if (!Guid.TryParse(sessionText, out var sessionId) || sessionId == Guid.Empty) return;

        var latitude = GetDouble(eventData.Value, "Latitude", "latitude");
        var longitude = GetDouble(eventData.Value, "Longitude", "longitude");
        var accuracy = GetDouble(eventData.Value, "AccuracyMeters", "accuracyMeters");
        var distance = GetDouble(eventData.Value, "DistanceMeters", "distanceMeters");
        var radius = GetInt(eventData.Value, "AllowedRadiusMeters", "allowedRadiusMeters");
        var within = GetBool(eventData.Value, "IsWithinAllowedRadius", "isWithinAllowedRadius");
        var captured = GetDateTime(eventData.Value, "Timestamp", "timestamp");
        var source = GetString(eventData.Value, "CaptureSource", "captureSource", "Source", "source") ?? "Online";

        if (radius <= 0 || distance < 0)
        {
            var calculated = await geoLocationService.GetDistanceFromOfficeAsync(latitude, longitude);
            if (calculated.Success)
            {
                distance = calculated.DistanceMeters;
                radius = calculated.AllowedRadiusMeters;
                within = radius > 0 && distance <= radius + 2;
            }
        }

        var captureSource = source.Equals("ANDROID_FIREBASE", StringComparison.OrdinalIgnoreCase)
            ? "Online"
            : source;

        if (evaluateAttendance)
        {
            // Feed the existing, already-tested geofence/session/attendance
            // engine. Firebase is the transport/SSOT; the existing attendance
            // rules remain unchanged. Historical replay never evaluates
            // attendance, preventing an old GPS snapshot from creating a new
            // punch or resurrecting an old session.
            await geoLocationService.UpdateGpsSessionAsync(
                employeeId, sessionId, latitude, longitude, accuracy, distance, radius, within, captured);
        }

        await geoLocationService.SaveLocationHistoryAsync(
            employeeId, sessionId, latitude, longitude, distance, radius, within,
            accuracy, captured, captureSource);

        _logger.LogDebug(
            "Processed Firebase GPS event. EmployeeId={EmployeeId}, SessionId={SessionId}, Distance={Distance}m, Radius={Radius}m, Within={Within}",
            employeeId, sessionId, Math.Round(distance, 1), radius, within);
    }

    private async Task ProcessFirebaseTrackingLifecycleEventAsync(
        JsonElement eventData,
        CancellationToken ct)
    {
        if (eventData.ValueKind != JsonValueKind.Object)
            return;

        var nested = eventData.TryGetProperty("event", out var eventNode) &&
                     eventNode.ValueKind == JsonValueKind.Object
            ? eventNode
            : eventData;

        var eventType = GetString(nested, "eventType", "EventType") ?? string.Empty;
        if (!eventType.Equals("SESSION_STARTED", StringComparison.OrdinalIgnoreCase) &&
            !eventType.Equals("SESSION_ENDED", StringComparison.OrdinalIgnoreCase))
            return;

        var employeeId = GetInt(eventData, "employeeId", "EmployeeId");
        if (employeeId <= 0)
            employeeId = GetInt(nested, "employeeId", "EmployeeId");

        var sessionText = GetString(nested, "sessionId", "SessionId");
        if (employeeId <= 0 || !Guid.TryParse(sessionText, out var sessionId) || sessionId == Guid.Empty)
            return;

        using var scope = _scopeFactory.CreateScope();
        var geo = scope.ServiceProvider.GetRequiredService<GeoLocationService>();

        if (eventType.Equals("SESSION_STARTED", StringComparison.OrdinalIgnoreCase))
        {
            var started = await geo.StartGpsSessionAsync(employeeId, sessionId);
            if (started)
                await _refreshService.NotifyLocationChangedAsync(employeeId);

            _logger.LogDebug(
                "Processed durable Android tracking session start. EmployeeId={EmployeeId}, SessionId={SessionId}",
                employeeId, sessionId);
            return;
        }

        var message = GetString(nested, "message", "Message") ?? string.Empty;
        var reason = "OFFLINE_SYNC";
        const string prefix = "Tracking session end deferred. Reason:";
        if (message.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
        {
            var parsed = message[prefix.Length..].Trim();
            if (!string.IsNullOrWhiteSpace(parsed))
                reason = parsed;
        }

        await geo.EndGpsSessionAsync(employeeId, sessionId, reason);
        await _refreshService.NotifyLocationChangedAsync(employeeId);

        _logger.LogDebug(
            "Processed durable Android tracking session end. EmployeeId={EmployeeId}, SessionId={SessionId}, Reason={Reason}",
            employeeId, sessionId, reason);
    }

    private async Task ProcessFirebaseTrackingSessionEventAsync(
        string relativePath,
        JsonElement? eventData,
        CancellationToken ct)
    {
        // GeoLocationService is scoped. FirebaseSqliteSyncService is a singleton
        // hosted service, so resolve the scoped service inside a short-lived scope
        // for each Firebase event instead of injecting it into the hosted service.
        using var geoScope = _scopeFactory.CreateScope();
        var geoLocationService = geoScope.ServiceProvider.GetRequiredService<GeoLocationService>();

        if (!eventData.HasValue || eventData.Value.ValueKind != JsonValueKind.Object)
            return;

        var employeeId = GetInt(eventData.Value, "EmployeeId", "employeeId");
        if (employeeId <= 0)
        {
            var parts = (relativePath ?? "").Trim('/').Split('/', StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length >= 2)
                int.TryParse(parts[1], out employeeId);
        }

        var sessionText = GetString(eventData.Value, "SessionId", "sessionId");
        if (employeeId <= 0 || !Guid.TryParse(sessionText, out var sessionId) || sessionId == Guid.Empty)
            return;

        var endedText = GetString(eventData.Value, "EndedAtUtc", "endedAtUtc");
        var endReason = GetString(eventData.Value, "EndReason", "endReason") ?? "LOGGED_OUT";

        if (string.IsNullOrWhiteSpace(endedText))
        {
            var started = await geoLocationService.StartGpsSessionAsync(employeeId, sessionId);
            if (started)
            {
                await _refreshService.NotifyLocationChangedAsync(employeeId);

                _logger.LogInformation(
                    "Firebase GPS session started/provisioned in Web compatibility layer. EmployeeId={EmployeeId}, SessionId={SessionId}",
                    employeeId, sessionId);
            }
            else
            {
                _logger.LogWarning(
                    "Firebase GPS session start could not be provisioned in the Web compatibility layer. EmployeeId={EmployeeId}, SessionId={SessionId}",
                    employeeId, sessionId);
            }
            return;
        }

        await geoLocationService.EndGpsSessionAsync(employeeId, sessionId, endReason);
        await _refreshService.NotifyLocationChangedAsync(employeeId);

        _logger.LogInformation(
            "Firebase GPS session ended in Web compatibility layer. EmployeeId={EmployeeId}, SessionId={SessionId}, Reason={Reason}",
            employeeId, sessionId, endReason);
    }

    private async Task ProcessFirebaseLiveLocationAsync(
        string relativePath,
        JsonElement? eventData,
        CancellationToken ct)
    {
        // Firebase is the realtime transport/SSOT for Android GPS. Every live
        // fix must pass through the same authoritative geofence + attendance
        // engine used by browser/HTTP GPS updates. The live branch must not
        // merely paint the admin map, otherwise Android GPS would never drive
        // automatic geofence attendance reconciliation.
        using var geoScope = _scopeFactory.CreateScope();
        var geoLocationService = geoScope.ServiceProvider.GetRequiredService<GeoLocationService>();

        if (!eventData.HasValue)
        {
            var key = (relativePath ?? "/")
                .Trim('/')
                .Split('/', StringSplitOptions.RemoveEmptyEntries)
                .LastOrDefault();

            if (int.TryParse(key, out var removedEmployeeId))
            {
                var currentSession = LiveLocationStore.GetSessionId(removedEmployeeId);
                if (currentSession.HasValue)
                    LiveLocationStore.Remove(removedEmployeeId, currentSession.Value);

                await _refreshService.NotifyLocationChangedAsync(removedEmployeeId);
            }

            return;
        }

        if (eventData.Value.ValueKind != JsonValueKind.Object)
            return;

        var employeeId = GetInt(eventData.Value, "EmployeeId", "employeeId");
        if (employeeId <= 0)
        {
            var key = (relativePath ?? "/")
                .Trim('/')
                .Split('/', StringSplitOptions.RemoveEmptyEntries)
                .LastOrDefault();

            employeeId = int.TryParse(key, out var parsed) ? parsed : 0;
        }

        if (employeeId <= 0)
            return;

        var sessionText = GetString(eventData.Value, "SessionId", "sessionId");
        if (!Guid.TryParse(sessionText, out var sessionId) || sessionId == Guid.Empty)
            return;

        var latitude = GetDouble(eventData.Value, "Latitude", "latitude");
        var longitude = GetDouble(eventData.Value, "Longitude", "longitude");
        var accuracy = Math.Max(0, GetDouble(eventData.Value, "AccuracyMeters", "accuracyMeters"));
        var captured = GetDateTime(eventData.Value, "Timestamp", "timestamp") ?? DateTime.UtcNow;

        if (!double.IsFinite(latitude) || !double.IsFinite(longitude) ||
            latitude is < -90 or > 90 || longitude is < -180 or > 180)
        {
            _logger.LogWarning(
                "Rejected Firebase GPS live event with invalid coordinates. EmployeeId={EmployeeId}, SessionId={SessionId}",
                employeeId,
                sessionId);
            return;
        }

        // Never trust client-supplied distance/radius/within values. Recompute
        // against the current Admin-configured office location and radius.
        // This also makes a live Admin configuration change effective for the
        // next Firebase GPS fix without requiring Android to restart tracking.
        var distanceResult = await geoLocationService.GetDistanceFromOfficeAsync(
            latitude,
            longitude);

        if (!distanceResult.Success)
        {
            _logger.LogWarning(
                "Firebase GPS live event could not be geofence-validated. EmployeeId={EmployeeId}, SessionId={SessionId}, Message={Message}",
                employeeId,
                sessionId,
                distanceResult.Message);
            return;
        }

        var distance = distanceResult.DistanceMeters;
        var radius = distanceResult.AllowedRadiusMeters;
        var within = distanceResult.IsWithinAllowedRadius;

        // 1200-L integration point: use the existing GeoLocationService as
        // the sole attendance authority. This preserves payroll locks, manual
        // overrides, automatic geofence reconciliation, GPS session lifecycle,
        // cross-day attendance rules, and existing refresh behavior.
        var accepted = await geoLocationService.UpdateGpsSessionAsync(
            employeeId,
            sessionId,
            latitude,
            longitude,
            accuracy,
            distance,
            radius,
            within,
            captured);

        if (!accepted)
        {
            _logger.LogDebug(
                "Firebase GPS live event ignored because the session is inactive or stale. EmployeeId={EmployeeId}, SessionId={SessionId}",
                employeeId,
                sessionId);
            return;
        }

        // UpdateGpsSessionAsync already updates LiveLocationStore only after
        // validating session ownership/order. Avoid a second competing writer.
        await _refreshService.NotifyLocationChangedAsync(employeeId);

        _logger.LogDebug(
            "Processed Firebase live GPS event through authoritative geofence engine. EmployeeId={EmployeeId}, SessionId={SessionId}, Distance={Distance}m, Radius={Radius}, Within={Within}",
            employeeId,
            sessionId,
            Math.Round(distance, 1),
            radius,
            within);
    }

    private static string? GetString(JsonElement element, params string[] names)
    {
        foreach (var name in names)
            if (element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String)
                return value.GetString();
        return null;
    }

    private static int GetInt(JsonElement element, params string[] names)
    {
        foreach (var name in names)
        {
            if (!element.TryGetProperty(name, out var value)) continue;
            if (value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out var i)) return i;
            if (value.ValueKind == JsonValueKind.String && int.TryParse(value.GetString(), NumberStyles.Any, CultureInfo.InvariantCulture, out i)) return i;
        }
        return 0;
    }

    private static double GetDouble(JsonElement element, params string[] names)
    {
        foreach (var name in names)
        {
            if (!element.TryGetProperty(name, out var value)) continue;
            if (value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out var d)) return d;
            if (value.ValueKind == JsonValueKind.String && double.TryParse(value.GetString(), NumberStyles.Any, CultureInfo.InvariantCulture, out d)) return d;
        }
        return 0;
    }

    private static bool GetBool(JsonElement element, params string[] names)
    {
        foreach (var name in names)
        {
            if (!element.TryGetProperty(name, out var value)) continue;
            if (value.ValueKind == JsonValueKind.True) return true;
            if (value.ValueKind == JsonValueKind.False) return false;
            if (value.ValueKind == JsonValueKind.String && bool.TryParse(value.GetString(), out var b)) return b;
        }
        return false;
    }

    private static DateTime? GetDateTime(JsonElement element, params string[] names)
    {
        var text = GetString(element, names);
        return DateTime.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var dt)
            ? dt.ToUniversalTime()
            : null;
    }

    private async Task ProcessFirebaseMobileAuthEventAsync(
        string relativePath,
        JsonElement? eventData,
        CancellationToken ct)
    {
        if (!eventData.HasValue || eventData.Value.ValueKind != JsonValueKind.Object) return;

        // The first Firebase SSE event for mobile_auth_events is the complete
        // current snapshot at /. Walk that snapshot so existing Android login
        // events are projected into the Web audit screen after startup/reconnect.
        var normalizedPath = (relativePath ?? "/").Trim('/');
        if (string.IsNullOrWhiteSpace(normalizedPath))
        {
            foreach (var employeeNode in eventData.Value.EnumerateObject())
            {
                if (employeeNode.Value.ValueKind != JsonValueKind.Object) continue;
                foreach (var eventNode in employeeNode.Value.EnumerateObject())
                {
                    if (eventNode.Value.ValueKind != JsonValueKind.Object) continue;
                    await ProcessFirebaseMobileAuthEventAsync(
                        $"/{employeeNode.Name}/{eventNode.Name}",
                        eventNode.Value.Clone(),
                        ct);
                }
            }
            return;
        }

        var employeeId = GetInt(eventData.Value, "employeeId", "EmployeeId");
        var eventType = GetString(eventData.Value, "eventType", "EventType") ?? "MOBILE_AUTH_EVENT";
        var uid = GetString(eventData.Value, "firebaseUid", "FirebaseUid") ?? string.Empty;
        var email = GetString(eventData.Value, "email", "Email") ?? string.Empty;
        var deviceId = GetString(eventData.Value, "deviceId", "DeviceId") ?? string.Empty;
        var platform = GetString(eventData.Value, "platform", "Platform") ?? "Android";
        var timestamp = GetDateTime(eventData.Value, "timestamp", "Timestamp") ?? DateTime.UtcNow;
        var normalizedRelativePath =
    (relativePath ?? string.Empty).Trim('/');

        var eventId = GetString(
            eventData.Value,
            "eventId",
            "EventId")
            ?? normalizedRelativePath
                .Split(
                    '/',
                    StringSplitOptions.RemoveEmptyEntries)
                .LastOrDefault();

        if (employeeId <= 0 || string.IsNullOrWhiteSpace(eventId)) return;

        await using var scope = _scopeFactory.CreateAsyncScope();
        var factory = scope.ServiceProvider.GetRequiredService<IDbContextFactory<AppDbContext>>();
        await using var db = await factory.CreateDbContextAsync(ct);

        // Avoid duplicating an Android event when the Firebase stream reconnects.
        var marker = $"FirebaseMobileAuth:{eventId}";
        var exists = await db.AuditLogs.AsNoTracking()
            .AnyAsync(x => x.EntityID == marker, ct);
        if (exists) return;

        var details = new Dictionary<string, object?>
        {
            ["EventType"] = eventType,
            ["Platform"] = platform,
            ["DeviceId"] = deviceId,
            ["FirebaseUid"] = uid,
            ["RecordedAtUtc"] = timestamp.ToString("O"),
            ["EventId"] = eventId
        };

        var log = new Payroll.Shared.Data.AuditLog
        {
            Timestamp = timestamp,
            UserID = string.IsNullOrWhiteSpace(uid) ? $"ANDROID_EMPLOYEE_{employeeId}" : uid,
            UserEmail = string.IsNullOrWhiteSpace(email) ? "Android" : email,
            ActionType = eventType.Length > 50 ? eventType[..50] : eventType,
            EntityType = "EmployeeSession",
            EntityID = marker,
            Details = JsonSerializer.Serialize(details)
        };

        using var syncScope = _firebaseSyncWriteScope.Enter();
        db.AuditLogs.Add(log);
        await db.SaveChangesAsync(ct);

        await _refreshService.NotifyApplicationDataChangedAsync(new[] { "AuditLog" });
        _logger.LogInformation(
            "Android Firebase auth event projected to Web audit monitor. EmployeeId={EmployeeId}, Event={EventType}, DeviceId={DeviceId}",
            employeeId, eventType, deviceId);
    }

    private async Task SyncAllTablesAsync(string ownerUid, CancellationToken ct)
    {
        foreach (var table in Tables)
        {
            ct.ThrowIfCancellationRequested();
            await SyncTableAsync(table.Key, table.Value, ownerUid, ct);
        }
    }

    private async Task<bool> SyncTableAsync(
        string entityName,
        string firebaseTable,
        string ownerUid,
        CancellationToken ct)
    {
        if (entityName == "EmployeeLocationHistory")
            return await SyncTrackingHistoryAsync(ownerUid, ct);

        var json = await _firebase.GetOwnerTableAsync(ownerUid, firebaseTable, ct);
        if (json is null)
            return false;

        // Firebase REST can serialize numeric-keyed collections as arrays.
        // Normalize that transport representation to the existing object-shaped
        // upsert pipeline without changing the database schema.
        if (json.Value.ValueKind == JsonValueKind.Array)
        {
            var normalized = new Dictionary<string, JsonElement>(StringComparer.Ordinal);
            var index = 0;
            foreach (var item in json.Value.EnumerateArray())
            {
                if (item.ValueKind != JsonValueKind.Null)
                    normalized[index.ToString(CultureInfo.InvariantCulture)] = item.Clone();
                index++;
            }

            var normalizedJson = JsonSerializer.SerializeToElement(normalized);
            return await UpsertTableAsync(entityName, normalizedJson, ct);
        }

        if (json.Value.ValueKind != JsonValueKind.Object)
            return false;

        return await UpsertTableAsync(entityName, json.Value, ct);
    }

    private async Task<bool> SyncTrackingHistoryAsync(string ownerUid, CancellationToken ct)
    {
        var json = await _firebase.GetOwnerTableAsync(ownerUid, "tracking/history", ct);
        if (json is null || (json.Value.ValueKind != JsonValueKind.Object && json.Value.ValueKind != JsonValueKind.Array)) return false;

        var changed = false;

        async Task ProcessEmployeeNode(string empIdKey, JsonElement employeeNode)
        {
            if (employeeNode.ValueKind != JsonValueKind.Object) return;

            await using var scope = _scopeFactory.CreateAsyncScope();
            var factory = scope.ServiceProvider.GetRequiredService<IDbContextFactory<AppDbContext>>();
            await using var db = await factory.CreateDbContextAsync(ct);
            var entityType = db.Model.GetEntityTypes().First(x => x.ClrType.Name == "EmployeeLocationHistory");
            var keys = entityType.FindPrimaryKey()?.Properties;

            var empChanged = false;
            foreach (var eventNode in employeeNode.EnumerateObject())
            {
                if (eventNode.Value.ValueKind != JsonValueKind.Object) continue;
                try
                {
                    empChanged |= await UpsertRecordAsync(db, entityType, keys!, eventNode.Name, eventNode.Value, ct);
                }
                catch { }
            }

            if (empChanged)
            {
                changed = true;
                using var syncScope = _firebaseSyncWriteScope.Enter();
                await db.SaveChangesAsync(ct);
            }
        }

        if (json.Value.ValueKind == JsonValueKind.Object)
        {
            foreach (var employeeNode in json.Value.EnumerateObject())
            {
                await ProcessEmployeeNode(employeeNode.Name, employeeNode.Value);
            }
        }
        else
        {
            var index = 0;
            foreach (var node in json.Value.EnumerateArray())
            {
                await ProcessEmployeeNode(index.ToString(), node);
                index++;
            }
        }
        return changed;
    }

    private readonly record struct FirebasePathTarget(string EntityName, string? RecordKey);

    private static FirebasePathTarget? ParseFirebasePath(string relativePath)
    {
        var normalized = (relativePath ?? "/").Trim('/');
        if (string.IsNullOrWhiteSpace(normalized))
            return null;

        var parts = normalized.Split('/', StringSplitOptions.RemoveEmptyEntries);
        var firebaseTable = parts[0];

        if (firebaseTable.Equals("tracking", StringComparison.OrdinalIgnoreCase) &&
            parts.Length >= 4 &&
            parts[1].Equals("history", StringComparison.OrdinalIgnoreCase))
        {
            return new FirebasePathTarget("TrackingHistory", parts[3]);
        }

        if (firebaseTable.Equals("tracking", StringComparison.OrdinalIgnoreCase))
            return new FirebasePathTarget("TrackingHistory", null);

        if (firebaseTable.Equals("mobile_auth_events", StringComparison.OrdinalIgnoreCase))
            return new FirebasePathTarget("MobileAuthEvent", parts.Length >= 3 ? parts[2] : null);

        var entity = Tables.FirstOrDefault(x =>
            string.Equals(x.Value, firebaseTable, StringComparison.Ordinal));

        if (string.IsNullOrWhiteSpace(entity.Key))
            return null;

        if (parts.Length == 1)
            return new FirebasePathTarget(entity.Key, null);

        var key = Uri.UnescapeDataString(parts[1]);
        key = key.Replace("%2E", ".", StringComparison.OrdinalIgnoreCase)
            .Replace("%23", "#", StringComparison.OrdinalIgnoreCase)
            .Replace("%24", "$", StringComparison.OrdinalIgnoreCase)
            .Replace("%5B", "[", StringComparison.OrdinalIgnoreCase)
            .Replace("%5D", "]", StringComparison.OrdinalIgnoreCase)
            .Replace("%2F", "/", StringComparison.OrdinalIgnoreCase);

        return new FirebasePathTarget(entity.Key, key);
    }

    private async Task<bool> DeleteLocalFirebaseRecordAsync(
        string entityName,
        string? firebaseKey,
        CancellationToken ct)
    {
        await using var scope = _scopeFactory.CreateAsyncScope();
        var factory = scope.ServiceProvider.GetRequiredService<IDbContextFactory<AppDbContext>>();
        await using var db = await factory.CreateDbContextAsync(ct);

        var entityType = db.Model.GetEntityTypes()
            .FirstOrDefault(x => x.ClrType.Name == entityName);
        if (entityType == null)
            return false;

        if (string.IsNullOrWhiteSpace(firebaseKey))
        {
            var rows = await GetEntitySet(db, entityType.ClrType).Cast<object>().ToListAsync(ct);
            if (rows.Count == 0)
                return false;

            using var tableScope = _firebaseSyncWriteScope.Enter();
            db.RemoveRange(rows);
            await db.SaveChangesAsync(ct);
            return true;
        }

        var keys = entityType.FindPrimaryKey()?.Properties;
        if (keys == null || keys.Count == 0)
            return false;

        var keyParts = firebaseKey.Split('|');
        if (keyParts.Length < keys.Count)
            return false;

        var keyValues = new object?[keys.Count];
        for (var i = 0; i < keys.Count; i++)
        {
            keyValues[i] = ConvertStringValue(keyParts[i], keys[i].ClrType);
            if (keyValues[i] is null)
                return false;
        }

        var existing = await db.FindAsync(entityType.ClrType, keyValues, ct);
        if (existing == null)
            return false;

        using var recordScope = _firebaseSyncWriteScope.Enter();
        db.Remove(existing);
        await db.SaveChangesAsync(ct);
        return true;
    }

    private async Task<bool> UpsertTableAsync(
        string entityName,
        JsonElement table,
        CancellationToken ct)
    {
        await using var scope =
            _scopeFactory.CreateAsyncScope();

        var factory =
            scope.ServiceProvider
                .GetRequiredService<IDbContextFactory<AppDbContext>>();

        await using var db =
            await factory.CreateDbContextAsync(ct);

        var entityType =
            db.Model.GetEntityTypes()
                .FirstOrDefault(x => x.ClrType.Name == entityName);

        if (entityType == null)
            return false;

        var keys = entityType.FindPrimaryKey()?.Properties;
        if (keys == null || keys.Count == 0)
            return false;

        var changedAny = false;
        var processedCount = 0;

        foreach (var child in table.EnumerateObject())
        {
            if (child.Value.ValueKind != JsonValueKind.Object)
                continue;

            try
            {
                changedAny |= await UpsertRecordAsync(
                    db,
                    entityType,
                    keys,
                    child.Name,
                    child.Value,
                    ct);

                processedCount++;

                // Save periodically to prevent the identity map from becoming too
                // large and to isolate identity conflicts between disparate records.
                if (processedCount % 100 == 0 && changedAny)
                {
                    using var syncScope = _firebaseSyncWriteScope.Enter();
                    await db.SaveChangesAsync(ct);
                    changedAny = false;
                }
            }
            catch (Exception ex)
            {
                _logger.LogDebug(
                    ex,
                    "Skipping Firebase row {Entity}/{Key}.",
                    entityName,
                    child.Name);
            }
        }

        if (changedAny)
        {
            using var syncScope = _firebaseSyncWriteScope.Enter();
            await db.SaveChangesAsync(ct);
        }

        return true;
    }

    private static async Task<bool> UpsertRecordAsync(
        AppDbContext db,
        IEntityType entityType,
        IReadOnlyList<IProperty> keys,
        string firebaseKey,
        JsonElement json,
        CancellationToken ct)
    {
        var keyParts = firebaseKey.Split('|');
        var keyValues = new object?[keys.Count];

        for (var i = 0; i < keys.Count; i++)
        {
            var value =
                FindJsonValue(
                    json,
                    keys[i].Name);

            if (value is not null)
            {
                keyValues[i] =
                    ConvertValue(
                        value,
                        keys[i].ClrType);
            }
            else if (keyParts.Length > i)
            {
                keyValues[i] =
                    ConvertStringValue(
                        keyParts[i],
                        keys[i].ClrType);
            }
            else
            {
                keyValues[i] = null;
            }
        }

        if (keyValues.Any(x => x is null))
            return false;

        var existing =
            await db.FindAsync(
                entityType.ClrType,
                keyValues,
                ct);

        var target =
            existing ??
            Activator.CreateInstance(entityType.ClrType);

        if (target == null)
            return false;

        // If the entity is new, initialize its primary key properties from the
        // lookup values. We do this BEFORE the loop so they are established
        // as the authoritative keys for this instance.
        if (existing == null)
        {
            for (int i = 0; i < keys.Count; i++)
            {
                if (keys[i].PropertyInfo != null)
                {
                    keys[i].PropertyInfo.SetValue(target, keyValues[i]);
                }
            }
        }

        var changed = false;

        foreach (var property in entityType.GetProperties())
        {
            if (property.IsShadowProperty() ||
                property.PropertyInfo == null)
                continue;

            // REQUIREMENT: Avoid setting primary key properties in the generic loop.
            // This prevents "cannot be tracked because another instance..." errors
            // caused by marking key properties as modified or changing them
            // after the entity is already tracked by the context.
            if (keys.Any(k => k.Name == property.Name))
                continue;

            var value =
                FindJsonValue(
                    json,
                    property.Name);

            if (value is null)
                continue;

            var converted =
                ConvertValue(
                    value,
                    property.ClrType);

            // Special mapping for Employee break hours (Firebase) to minutes (SQL)
            if (entityType.ClrType.Name == "Employee" && property.Name == "StandardBreakMinutes")
            {
                if (value.Value.ValueKind == JsonValueKind.Number && value.Value.TryGetDouble(out var hours))
                {
                    converted = (int)Math.Round(hours * 60d);
                }
            }

            // Special mapping for Employee isActive (Firebase) to IsDeleted (SQL) - REVERSED
            if (entityType.ClrType.Name == "Employee" && property.Name == "IsDeleted")
            {
                if (converted is bool isActuallyActive)
                {
                    converted = !isActuallyActive;
                }
            }

            if (converted is null &&
                Nullable.GetUnderlyingType(property.ClrType) == null &&
                property.ClrType.IsValueType)
                continue;

            var current =
                property.PropertyInfo.GetValue(target);

            if (!Equals(current, converted))
            {
                property.PropertyInfo.SetValue(
                    target,
                    converted);

                changed = true;
            }
        }

        if (existing == null && changed)
            db.Add(target);
        else if (existing != null && changed)
            db.Entry(target).State = EntityState.Modified;

        return changed;
    }

    private static JsonElement? FindJsonValue(
        JsonElement json,
        string propertyName)
    {
        var wanted = Normalize(propertyName);

        foreach (var property in json.EnumerateObject())
        {
            if (Normalize(property.Name) == wanted)
                return property.Value;

            if (AliasMatches(propertyName, property.Name))
                return property.Value;
        }

        return null;
    }

    private static bool AliasMatches(
        string clrName,
        string firebaseName)
        => (Normalize(clrName), Normalize(firebaseName)) switch
        {
            ("logid", "attendanceid") => true,
            ("leaverequestid", "id") => true,
            ("regularizationid", "id") => true,
            ("employeeid", "staffid") => true,
            ("monthlysalary", "salaryrate") => true,
            ("standardbreakminutes", "breakhours") => true,
            ("payrolltypeoverride", "salarytype") => true,
            ("terminationdate", "terminatedate") => true,
            ("isdeleted", "isactive") => true,
            ("shiftstarttime", "shiftstart") => true,
            ("shiftendtime", "shiftend") => true,
            ("hiredate", "hiredate") => true,
            ("dob", "dob") => true,
            ("standardhours", "standardhours") => true,
            ("otrule", "otrule") => true,
            ("otflatrate", "otflatrate") => true,
            ("compoffdayofweek", "compoffdayofweek") => true,
            _ => false
        };

    private static string Normalize(string value) =>
        new(value.Where(char.IsLetterOrDigit)
            .Select(char.ToLowerInvariant)
            .ToArray());

    private static IQueryable GetEntitySet(
        DbContext db,
        Type entityType)
    {
        var setMethod = typeof(DbContext)
            .GetMethods()
            .First(method =>
                method.Name == nameof(DbContext.Set) &&
                method.IsGenericMethodDefinition &&
                method.GetGenericArguments().Length == 1 &&
                method.GetParameters().Length == 0);

        return (IQueryable)setMethod
            .MakeGenericMethod(entityType)
            .Invoke(db, null)!;
    }

    private static object? ConvertStringValue(
        string value,
        Type targetType)
    {
        var type = Nullable.GetUnderlyingType(targetType) ?? targetType;
        if (string.IsNullOrWhiteSpace(value))
            return null;

        try
        {
            if (type == typeof(string)) return value;
            if (type == typeof(int)) return int.Parse(value, CultureInfo.InvariantCulture);
            if (type == typeof(long)) return long.Parse(value, CultureInfo.InvariantCulture);
            if (type == typeof(decimal)) return decimal.Parse(value, CultureInfo.InvariantCulture);
            if (type == typeof(double)) return double.Parse(value, CultureInfo.InvariantCulture);
            if (type == typeof(float)) return float.Parse(value, CultureInfo.InvariantCulture);
            if (type == typeof(bool)) return bool.Parse(value);
            if (type == typeof(Guid)) return Guid.Parse(value);
            if (type == typeof(DateTime)) return DateTime.Parse(value, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind);
            if (type == typeof(DateTimeOffset)) return DateTimeOffset.Parse(value, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind);
            if (type == typeof(DateOnly)) return DateOnly.Parse(value, CultureInfo.InvariantCulture);
            if (type == typeof(TimeOnly)) return TimeOnly.Parse(value, CultureInfo.InvariantCulture);
            if (type.IsEnum) return Enum.Parse(type, value, true);
            return value;
        }
        catch
        {
            return null;
        }
    }

    private static object? ConvertValue(
        JsonElement? value,
        Type targetType)
    {
        if (value is null ||
            value.Value.ValueKind == JsonValueKind.Null)
            return null;

        var type =
            Nullable.GetUnderlyingType(targetType)
            ?? targetType;

        try
        {
            if (type == typeof(string))
            {
                if (value.Value.ValueKind == JsonValueKind.String)
                    return value.Value.GetString();
                return value.Value.ToString();
            }

            if (type == typeof(int))
            {
                if (value.Value.ValueKind == JsonValueKind.Number && value.Value.TryGetInt32(out var i))
                    return i;
                var text = value.Value.ToString();
                if (int.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out i)) return i;
                if (double.TryParse(text, NumberStyles.Any, CultureInfo.InvariantCulture, out var d)) return (int)Math.Round(d);
                return 0;
            }

            if (type == typeof(long))
            {
                if (value.Value.ValueKind == JsonValueKind.Number && value.Value.TryGetInt64(out var l))
                    return l;
                var text = value.Value.ToString();
                if (long.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out l)) return l;
                if (double.TryParse(text, NumberStyles.Any, CultureInfo.InvariantCulture, out var d)) return (long)Math.Round(d);
                return 0L;
            }

            if (type == typeof(decimal))
            {
                if (value.Value.ValueKind == JsonValueKind.Number && value.Value.TryGetDecimal(out var dec))
                    return dec;
                var text = value.Value.ToString();
                if (decimal.TryParse(text, NumberStyles.Any, CultureInfo.InvariantCulture, out dec)) return dec;
                return 0m;
            }

            if (type == typeof(double))
            {
                if (value.Value.ValueKind == JsonValueKind.Number && value.Value.TryGetDouble(out var dbl))
                    return dbl;
                var text = value.Value.ToString();
                if (double.TryParse(text, NumberStyles.Any, CultureInfo.InvariantCulture, out dbl)) return dbl;
                return 0.0;
            }

            if (type == typeof(float))
            {
                if (value.Value.ValueKind == JsonValueKind.Number && value.Value.TryGetSingle(out var flt))
                    return flt;
                var text = value.Value.ToString();
                if (float.TryParse(text, NumberStyles.Any, CultureInfo.InvariantCulture, out flt)) return flt;
                return 0.0f;
            }

            if (type == typeof(bool))
            {
                if (value.Value.ValueKind == JsonValueKind.True) return true;
                if (value.Value.ValueKind == JsonValueKind.False) return false;
                var text = value.Value.ToString();
                if (bool.TryParse(text, out var b)) return b;
                if (int.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out var n)) return n != 0;
                return false;
            }

            if (type == typeof(Guid))
            {
                if (value.Value.ValueKind == JsonValueKind.String)
                    return Guid.Parse(value.Value.GetString()!);
                return Guid.Parse(value.Value.ToString());
            }

            if (type == typeof(DateTime))
            {
                if (value.Value.ValueKind == JsonValueKind.Number &&
                    value.Value.TryGetInt64(out var ms))
                    return DateTimeOffset.FromUnixTimeMilliseconds(ms).UtcDateTime;

                var text = value.Value.ValueKind == JsonValueKind.String ? value.Value.GetString() : value.Value.ToString();
                if (long.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out var pMs))
                    return DateTimeOffset.FromUnixTimeMilliseconds(pMs).UtcDateTime;

                return DateTime.Parse(
                    text!,
                    CultureInfo.InvariantCulture,
                    DateTimeStyles.RoundtripKind);
            }

            if (type == typeof(DateTimeOffset))
            {
                if (value.Value.ValueKind == JsonValueKind.Number &&
                    value.Value.TryGetInt64(out var ms))
                    return DateTimeOffset.FromUnixTimeMilliseconds(ms);

                var text = value.Value.ValueKind == JsonValueKind.String ? value.Value.GetString() : value.Value.ToString();
                if (long.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out var pMs))
                    return DateTimeOffset.FromUnixTimeMilliseconds(pMs);

                return DateTimeOffset.Parse(
                    text!,
                    CultureInfo.InvariantCulture,
                    DateTimeStyles.RoundtripKind);
            }

            if (type == typeof(DateOnly))
            {
                if (value.Value.ValueKind == JsonValueKind.Number &&
                    value.Value.TryGetInt64(out var ms))
                    return DateOnly.FromDateTime(
                        DateTimeOffset.FromUnixTimeMilliseconds(ms).DateTime);

                var text = value.Value.ValueKind == JsonValueKind.String ? value.Value.GetString() : value.Value.ToString();
                if (long.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out var pMs))
                    return DateOnly.FromDateTime(DateTimeOffset.FromUnixTimeMilliseconds(pMs).DateTime);

                return DateOnly.Parse(
                    text!,
                    CultureInfo.InvariantCulture);
            }

            if (type == typeof(TimeOnly))
            {
                if (value.Value.ValueKind == JsonValueKind.Number &&
                    value.Value.TryGetDouble(out var ms))
                    return TimeOnly.FromTimeSpan(TimeSpan.FromMilliseconds(ms));

                var text = value.Value.ValueKind == JsonValueKind.String ? value.Value.GetString() : value.Value.ToString();
                return TimeOnly.Parse(
                    text!,
                    CultureInfo.InvariantCulture);
            }

            if (type == typeof(TimeSpan))
            {
                if (value.Value.ValueKind == JsonValueKind.Number &&
                    value.Value.TryGetDouble(out var ms))
                    return TimeSpan.FromMilliseconds(ms);

                var text = value.Value.ValueKind == JsonValueKind.String ? value.Value.GetString() : value.Value.ToString();
                if (double.TryParse(text, NumberStyles.Any, CultureInfo.InvariantCulture, out var pMs))
                    return TimeSpan.FromMilliseconds(pMs);

                return TimeSpan.Parse(text!, CultureInfo.InvariantCulture);
            }

            if (type.IsEnum)
            {
                var text = value.Value.ValueKind == JsonValueKind.String ? value.Value.GetString() : value.Value.ToString();
                return Enum.Parse(type, text!, true);
            }

            return JsonSerializer.Deserialize(
                value.Value.GetRawText(),
                type);
        }
        catch (Exception ex)
        {
            return null;
        }
    }
}
