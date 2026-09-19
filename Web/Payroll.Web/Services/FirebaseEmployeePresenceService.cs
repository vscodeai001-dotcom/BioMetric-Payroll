using System.Globalization;

namespace Payroll.Web.Services;

/// <summary>
/// Cross-platform employee login presence.
/// Presence is deliberately separate from GPS: an employee can be logged in
/// without a current GPS fix. Each platform/device gets its own session node.
/// </summary>
public sealed class FirebaseEmployeePresenceService
{
    private readonly FirebaseRealtimeService _firebase;
    private readonly ILogger<FirebaseEmployeePresenceService> _logger;

    public FirebaseEmployeePresenceService(
        FirebaseRealtimeService firebase,
        ILogger<FirebaseEmployeePresenceService> logger)
    {
        _firebase = firebase;
        _logger = logger;
    }

    public async Task<bool> SetWebPresenceAsync(
        int employeeId,
        string sessionId,
        string authUid,
        string email,
        CancellationToken ct = default)
    {
        if (employeeId <= 0 || string.IsNullOrWhiteSpace(sessionId) || string.IsNullOrWhiteSpace(authUid))
            return false;

        var ownerUid = _firebase.ResolveOwnerUid(authUid, "Employee");
        if (string.IsNullOrWhiteSpace(ownerUid)) return false;

        var key = SanitizeKey(sessionId);
        var nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var row = new Dictionary<string, object?>
        {
            ["employeeId"] = employeeId,
            ["authUid"] = authUid,
            ["email"] = email ?? string.Empty,
            ["platform"] = "WEB",
            ["deviceId"] = sessionId,
            ["active"] = true,
            ["lastSeenAt"] = nowMs,
            ["updatedUtc"] = DateTime.UtcNow.ToString("O", CultureInfo.InvariantCulture)
        };

        try
        {
            // REQUIREMENT: Enforce single-device rule across Web and Android.
            // Publish the authoritative session lock to 'employee_sessions' node
            // used by Android's lifecycle monitor. DeviceId 'WEB_BROWSER' indicates
            // that the employee is currently active on the Web Dashboard.
            var sessionRow = new Dictionary<string, object?>
            {
                ["deviceId"] = "WEB_BROWSER_" + key.Take(8),
                ["employeeId"] = employeeId,
                ["ownerUid"] = ownerUid,
                ["uid"] = authUid,
                ["lastSeenAt"] = nowMs,
                ["createdAt"] = nowMs
            };

            await _firebase.SetGlobalRecordAsync($"employee_sessions/{authUid}", sessionRow, ct);

            return await _firebase.SetOwnerRecordAsync(
                ownerUid, "presence", $"{employeeId}_{key}", row, ct);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Unable to publish Web employee presence. EmployeeId={EmployeeId}", employeeId);
            return false;
        }
    }

    public async Task<bool> RemoveAsync(
        int employeeId,
        string sessionId,
        string authUid,
        CancellationToken ct = default)
    {
        if (employeeId <= 0 || string.IsNullOrWhiteSpace(sessionId) || string.IsNullOrWhiteSpace(authUid))
            return false;

        var ownerUid = _firebase.ResolveOwnerUid(authUid, "Employee");
        if (string.IsNullOrWhiteSpace(ownerUid)) return false;

        try
        {
            return await _firebase.DeleteOwnerRecordAsync(
                ownerUid, "presence", $"{employeeId}_{SanitizeKey(sessionId)}", ct);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Unable to remove Web employee presence. EmployeeId={EmployeeId}", employeeId);
            return false;
        }
    }

    private static string SanitizeKey(string value) =>
        value.Trim()
            .Replace(".", "_dot_", StringComparison.Ordinal)
            .Replace("#", "_hash_", StringComparison.Ordinal)
            .Replace("$", "_dollar_", StringComparison.Ordinal)
            .Replace("[", "_open_", StringComparison.Ordinal)
            .Replace("]", "_close_", StringComparison.Ordinal)
            .Replace("/", "_slash_", StringComparison.Ordinal);
}
