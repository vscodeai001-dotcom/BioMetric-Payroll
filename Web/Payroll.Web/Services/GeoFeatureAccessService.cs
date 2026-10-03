using Microsoft.AspNetCore.Components.Authorization;
using Microsoft.EntityFrameworkCore;
using Payroll.Shared.Data;

namespace Payroll.Web.Services;

public sealed class GeoFeatureAccessService
{
    private readonly IDbContextFactory<AppDbContext> _dbFactory;
    private readonly AuthenticationStateProvider _authStateProvider;

    public GeoFeatureAccessService(
        IDbContextFactory<AppDbContext> dbFactory,
        AuthenticationStateProvider authStateProvider)
    {
        _dbFactory = dbFactory;
        _authStateProvider = authStateProvider;
    }

    /// <summary>
    /// SuperAdmin always has access to Geo-Fencing.
    /// In normal mode, Geo-Fencing follows EnableGeoFencing.
    /// Dual Attendance mode also activates Geo-Fencing.
    /// </summary>
    public async Task<bool> IsEnabledAsync()
    {
        var authState =
            await _authStateProvider.GetAuthenticationStateAsync();

        var user = authState.User;

        if (user.Identity?.IsAuthenticated != true)
        {
            return false;
        }

        // ---------------------------------------------------------
        // STANDALONE LOCAL / OFFLINE MODE OVERRIDE
        // In Standalone Local mode, all GPS tracking, geofence, and maps are disabled
        // ---------------------------------------------------------
        await using var db =
            await _dbFactory.CreateDbContextAsync();

        var settings =
            await db.FeatureSettings
                .AsNoTracking()
                .FirstOrDefaultAsync(f => f.Id == 1);

        if (settings?.IsOfflineMode == true || string.Equals(settings?.DeploymentMode, "Offline", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        // ---------------------------------------------------------
        // SUPERADMIN OVERRIDE
        // ---------------------------------------------------------

        if (user.IsInRole("SuperAdmin"))
        {
            return true;
        }

        return settings?.EnableGeoFencing == true;
    }

    /// <summary>
    /// Returns true only when the authenticated user is SuperAdmin.
    /// </summary>
    public async Task<bool> IsSuperAdminAsync()
    {
        var authState =
            await _authStateProvider.GetAuthenticationStateAsync();

        return authState.User.IsInRole("SuperAdmin");
    }
}