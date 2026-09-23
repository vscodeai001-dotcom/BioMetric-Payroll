using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Claims;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Components.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging;
using Payroll.Shared.Data;
using Payroll.Web.Services;

namespace Payroll.Web.Services
{
    public interface ITenantContextService
    {
        Task<string> GetActiveTenantIdAsync();
        Task SetActiveTenantIdAsync(string tenantId);
        Task<CompanyTenant?> GetActiveTenantAsync();
        Task<List<CompanyTenant>> GetAllTenantsAsync();
        Task<FeatureSettings> GetActiveFeatureSettingsAsync();
        Task<CompanySetting> GetActiveCompanySettingAsync();
        Task<bool> IsSuperAdminAsync();
        event Action? OnTenantChanged;
    }

    public class TenantContextService : ITenantContextService
    {
        private readonly IDbContextFactory<AppDbContext> _dbFactory;
        private readonly AuthenticationStateProvider _authStateProvider;
        private readonly IHttpContextAccessor _httpContextAccessor;
        private readonly ILogger<TenantContextService> _logger;

        private string? _superAdminSelectedTenantId;

        public event Action? OnTenantChanged;

        public const string DefaultTenantId = "biometricpayroll";
        private const string TenantCookieName = "BioMetric_SuperAdmin_ActiveTenant";

        public TenantContextService(
            IDbContextFactory<AppDbContext> dbFactory,
            AuthenticationStateProvider authStateProvider,
            IHttpContextAccessor httpContextAccessor,
            ILogger<TenantContextService> logger)
        {
            _dbFactory = dbFactory;
            _authStateProvider = authStateProvider;
            _httpContextAccessor = httpContextAccessor;
            _logger = logger;
        }

        public async Task<bool> IsSuperAdminAsync()
        {
            try
            {
                var authState = await _authStateProvider.GetAuthenticationStateAsync();
                var user = authState.User;
                return user.Identity?.IsAuthenticated == true &&
                       (user.IsInRole("SuperAdmin") ||
                        string.Equals(user.Identity?.Name, FirebaseAuthSecurityConstants.CanonicalSuperAdminEmail, StringComparison.OrdinalIgnoreCase));
            }
            catch
            {
                return false;
            }
        }

        public async Task<string> GetActiveTenantIdAsync()
        {
            var isSuperAdmin = await IsSuperAdminAsync();
            if (isSuperAdmin)
            {
                if (!string.IsNullOrWhiteSpace(_superAdminSelectedTenantId))
                {
                    return _superAdminSelectedTenantId;
                }

                // Check cookie fallback if available
                var http = _httpContextAccessor.HttpContext;
                if (http != null && http.Request.Cookies.TryGetValue(TenantCookieName, out var cookieTenant) && !string.IsNullOrWhiteSpace(cookieTenant))
                {
                    _superAdminSelectedTenantId = cookieTenant;
                    return _superAdminSelectedTenantId;
                }

                return DefaultTenantId;
            }

            // For regular Admin or Employee, resolve based on current user
            var authState = await _authStateProvider.GetAuthenticationStateAsync();
            var user = authState.User;
            var userId = user.FindFirst(ClaimTypes.NameIdentifier)?.Value ?? user.FindFirst("sub")?.Value;
            var email = user.Identity?.Name;

            await using var db = await _dbFactory.CreateDbContextAsync();

            if (!string.IsNullOrWhiteSpace(userId) || !string.IsNullOrWhiteSpace(email))
            {
                var tenant = await db.CompanyTenants
                    .AsNoTracking()
                    .FirstOrDefaultAsync(t => (userId != null && t.AdminUserId == userId) ||
                                              (email != null && t.AdminEmail.ToLower() == email.ToLower()));

                if (tenant != null)
                {
                    return tenant.TenantId;
                }
            }

            return DefaultTenantId;
        }

        public async Task SetActiveTenantIdAsync(string tenantId)
        {
            var isSuperAdmin = await IsSuperAdminAsync();
            if (!isSuperAdmin)
            {
                _logger.LogWarning("Non-SuperAdmin attempted to switch tenant context to {TenantId}", tenantId);
                return;
            }

            _superAdminSelectedTenantId = string.IsNullOrWhiteSpace(tenantId) ? DefaultTenantId : tenantId.Trim();

            try
            {
                var http = _httpContextAccessor.HttpContext;
                if (http != null && !http.Response.HasStarted)
                {
                    http.Response.Cookies.Append(TenantCookieName, _superAdminSelectedTenantId, new CookieOptions
                    {
                        HttpOnly = true,
                        Secure = true,
                        SameSite = SameSiteMode.Lax,
                        MaxAge = TimeSpan.FromDays(30),
                        Path = "/"
                    });
                }
            }
            catch (Exception ex)
            {
                _logger.LogDebug(ex, "Could not set tenant cookie; session state active.");
            }

            OnTenantChanged?.Invoke();
        }

        public async Task<CompanyTenant?> GetActiveTenantAsync()
        {
            var activeId = await GetActiveTenantIdAsync();
            await using var db = await _dbFactory.CreateDbContextAsync();

            var tenant = await db.CompanyTenants
                .AsNoTracking()
                .FirstOrDefaultAsync(t => t.TenantId == activeId);

            if (tenant == null && activeId == DefaultTenantId)
            {
                // Fallback default tenant object
                return new CompanyTenant
                {
                    Id = 1,
                    TenantId = DefaultTenantId,
                    CompanyName = "Main Company",
                    CompanyCode = "MAIN",
                    AdminEmail = FirebaseAuthSecurityConstants.CanonicalSuperAdminEmail,
                    AdminName = "Super Administrator",
                    IconEmoji = "🏢",
                    PlanMode = "Spark",
                    IsActive = true,
                    CompanySettingId = 1,
                    FeatureSettingsId = 1
                };
            }

            return tenant;
        }

        public async Task<List<CompanyTenant>> GetAllTenantsAsync()
        {
            await using var db = await _dbFactory.CreateDbContextAsync();
            var list = await db.CompanyTenants
                .AsNoTracking()
                .OrderByDescending(t => t.IsActive)
                .ThenBy(t => t.CompanyName)
                .ToListAsync();

            if (!list.Any(t => t.TenantId == DefaultTenantId))
            {
                list.Insert(0, new CompanyTenant
                {
                    Id = 1,
                    TenantId = DefaultTenantId,
                    CompanyName = "Primary Tenant",
                    CompanyCode = "PRIMARY",
                    AdminEmail = FirebaseAuthSecurityConstants.CanonicalSuperAdminEmail,
                    AdminName = "Super Administrator",
                    IconEmoji = "🏢",
                    PlanMode = "Spark",
                    IsActive = true,
                    CompanySettingId = 1,
                    FeatureSettingsId = 1
                });
            }

            return list;
        }

        public async Task<FeatureSettings> GetActiveFeatureSettingsAsync()
        {
            await using var db = await _dbFactory.CreateDbContextAsync();
            var tenant = await GetActiveTenantAsync();

            if (tenant != null && tenant.FeatureSettingsId > 0)
            {
                var settings = await db.FeatureSettings
                    .AsNoTracking()
                    .FirstOrDefaultAsync(s => s.Id == tenant.FeatureSettingsId);

                if (settings != null)
                    return settings;
            }

            var defaultSettings = await db.FeatureSettings
                .AsNoTracking()
                .FirstOrDefaultAsync(s => s.Id == 1);

            return defaultSettings ?? new FeatureSettings();
        }

        public async Task<CompanySetting> GetActiveCompanySettingAsync()
        {
            await using var db = await _dbFactory.CreateDbContextAsync();
            var tenant = await GetActiveTenantAsync();

            if (tenant != null && tenant.CompanySettingId > 0)
            {
                var company = await db.CompanySettings
                    .AsNoTracking()
                    .FirstOrDefaultAsync(c => c.SettingID == tenant.CompanySettingId);

                if (company != null)
                    return company;
            }

            var defaultCompany = await db.CompanySettings
                .AsNoTracking()
                .FirstOrDefaultAsync(c => c.SettingID == 1);

            return defaultCompany ?? new CompanySetting();
        }
    }
}
