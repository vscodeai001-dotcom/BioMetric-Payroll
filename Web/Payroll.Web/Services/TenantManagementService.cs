using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging;
using Payroll.Shared.Data;
using Payroll.Web.Services;

namespace Payroll.Web.Services
{
    public class CreateTenantRequest
    {
        public string CompanyName { get; set; } = string.Empty;
        public string CompanyCode { get; set; } = string.Empty;
        public string IconEmoji { get; set; } = "🏢";
        public string PlanMode { get; set; } = "Spark"; // "Spark" or "Blaze"

        public string AdminEmail { get; set; } = string.Empty;
        public string AdminPassword { get; set; } = string.Empty;
        public string AdminName { get; set; } = string.Empty;
        public string? AdminPhone { get; set; }

        public double OfficeLatitude { get; set; } = 11.9416;
        public double OfficeLongitude { get; set; } = 79.8083;
        public int GeoRadiusMeters { get; set; } = 100;
        public int WorkDayCutoffHour { get; set; } = 22;

        public FeatureSettings InitialFeatures { get; set; } = new FeatureSettings();
    }

    public class TenantOperationResult
    {
        public bool Success { get; set; }
        public string? ErrorMessage { get; set; }
        public CompanyTenant? Tenant { get; set; }
    }

    public class TenantManagementService
    {
        private readonly IDbContextFactory<AppDbContext> _dbFactory;
        private readonly UserManager<IdentityUser> _userManager;
        private readonly RoleManager<IdentityRole> _roleManager;
        private readonly FirebaseRealtimeService _firebase;
        private readonly AttendanceRefreshService _refreshService;
        private readonly ILogger<TenantManagementService> _logger;

        public TenantManagementService(
            IDbContextFactory<AppDbContext> dbFactory,
            UserManager<IdentityUser> userManager,
            RoleManager<IdentityRole> roleManager,
            FirebaseRealtimeService firebase,
            AttendanceRefreshService refreshService,
            ILogger<TenantManagementService> logger)
        {
            _dbFactory = dbFactory;
            _userManager = userManager;
            _roleManager = roleManager;
            _firebase = firebase;
            _refreshService = refreshService;
            _logger = logger;
        }

        public async Task EnsureDefaultTenantSeededAsync()
        {
            try
            {
                await using var db = await _dbFactory.CreateDbContextAsync();

                // Ensure the table exists in SQLite
                await db.Database.ExecuteSqlRawAsync(@"
                    CREATE TABLE IF NOT EXISTS ""CompanyTenants"" (
                        ""id"" INTEGER PRIMARY KEY AUTOINCREMENT,
                        ""tenant_id"" TEXT NOT NULL,
                        ""company_name"" TEXT NOT NULL,
                        ""company_code"" TEXT NOT NULL,
                        ""admin_user_id"" TEXT NULL,
                        ""admin_email"" TEXT NOT NULL,
                        ""admin_name"" TEXT NOT NULL,
                        ""admin_phone"" TEXT NULL,
                        ""icon_emoji"" TEXT NOT NULL DEFAULT '🏢',
                        ""plan_mode"" TEXT NOT NULL DEFAULT 'Spark',
                        ""is_active"" INTEGER NOT NULL DEFAULT 1,
                        ""created_at_utc"" TEXT NOT NULL,
                        ""company_setting_id"" INTEGER NOT NULL DEFAULT 1,
                        ""feature_settings_id"" INTEGER NOT NULL DEFAULT 1
                    );
                    CREATE UNIQUE INDEX IF NOT EXISTS ""IX_CompanyTenants_tenant_id"" ON ""CompanyTenants"" (""tenant_id"");
                    CREATE UNIQUE INDEX IF NOT EXISTS ""IX_CompanyTenants_company_code"" ON ""CompanyTenants"" (""company_code"");
                ");

                var exists = await db.CompanyTenants.AnyAsync(t => t.TenantId == TenantContextService.DefaultTenantId);
                if (!exists)
                {
                    var defaultCompany = await db.CompanySettings.FirstOrDefaultAsync(c => c.SettingID == 1);
                    var companyName = defaultCompany?.CompanyName ?? "Primary Workspace";

                    var tenant = new CompanyTenant
                    {
                        TenantId = TenantContextService.DefaultTenantId,
                        CompanyName = companyName,
                        CompanyCode = "PRIMARY",
                        AdminEmail = FirebaseAuthSecurityConstants.CanonicalSuperAdminEmail,
                        AdminName = "Super Administrator",
                        AdminPhone = "",
                        IconEmoji = "🏢",
                        PlanMode = "Spark",
                        IsActive = true,
                        CreatedAtUtc = DateTime.UtcNow,
                        CompanySettingId = 1,
                        FeatureSettingsId = 1
                    };

                    db.CompanyTenants.Add(tenant);
                    await db.SaveChangesAsync();
                    _logger.LogInformation("Seeded default primary tenant {TenantId} ({CompanyName})", tenant.TenantId, tenant.CompanyName);
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error while seeding default primary tenant.");
            }
        }

        public async Task<TenantOperationResult> CreateTenantAsync(CreateTenantRequest request)
        {
            if (string.IsNullOrWhiteSpace(request.CompanyName))
                return new TenantOperationResult { Success = false, ErrorMessage = "Company Name is required." };

            if (string.IsNullOrWhiteSpace(request.AdminEmail))
                return new TenantOperationResult { Success = false, ErrorMessage = "Admin Email is required." };

            if (string.IsNullOrWhiteSpace(request.AdminPassword) || request.AdminPassword.Length < 6)
                return new TenantOperationResult { Success = false, ErrorMessage = "Password must be at least 6 characters." };

            var code = string.IsNullOrWhiteSpace(request.CompanyCode)
                ? Regex.Replace(request.CompanyName.ToUpperInvariant(), "[^A-Z0-9]", "")
                : Regex.Replace(request.CompanyCode.ToUpperInvariant(), "[^A-Z0-9]", "");

            if (code.Length > 10) code = code[..10];
            if (string.IsNullOrWhiteSpace(code)) code = "COMP" + new Random().Next(100, 999);

            var tenantId = "tenant_" + code.ToLowerInvariant();

            await using var db = await _dbFactory.CreateDbContextAsync();

            // Check if tenant_id or code already exists
            if (await db.CompanyTenants.AnyAsync(t => t.TenantId == tenantId || t.CompanyCode == code))
            {
                tenantId = $"{tenantId}_{new Random().Next(10, 99)}";
                code = $"{code}{new Random().Next(1, 9)}";
            }

            // Check admin email
            var existingUser = await _userManager.FindByEmailAsync(request.AdminEmail.Trim());
            IdentityUser adminUser;

            if (existingUser != null)
            {
                adminUser = existingUser;
                if (!await _userManager.IsInRoleAsync(adminUser, "Admin") && !await _userManager.IsInRoleAsync(adminUser, "SuperAdmin"))
                {
                    await _userManager.AddToRoleAsync(adminUser, "Admin");
                }
            }
            else
            {
                // Ensure Admin role exists
                if (!await _roleManager.RoleExistsAsync("Admin"))
                {
                    await _roleManager.CreateAsync(new IdentityRole("Admin"));
                }

                adminUser = new IdentityUser
                {
                    UserName = request.AdminEmail.Trim(),
                    Email = request.AdminEmail.Trim(),
                    EmailConfirmed = true
                };

                var createRes = await _userManager.CreateAsync(adminUser, request.AdminPassword);
                if (!createRes.Succeeded)
                {
                    var err = string.Join("; ", createRes.Errors.Select(e => e.Description));
                    return new TenantOperationResult { Success = false, ErrorMessage = $"Admin account creation failed: {err}" };
                }

                await _userManager.AddToRoleAsync(adminUser, "Admin");
            }

            // Provision Firebase Auth account for tenant admin
            try
            {
                await _firebase.EnsureFirebaseUserAsync(
                    request.AdminEmail.Trim(),
                    request.AdminPassword,
                    "Admin",
                    displayName: string.IsNullOrWhiteSpace(request.AdminName) ? request.CompanyName.Trim() + " Admin" : request.AdminName.Trim(),
                    updatePasswordIfExisting: true);
            }
            catch (Exception authEx)
            {
                _logger.LogWarning(authEx, "Firebase Auth provisioning deferred for admin {Email}", request.AdminEmail);
            }

            // Create CompanySetting
            var maxSettingId = await db.CompanySettings.MaxAsync(c => (int?)c.SettingID) ?? 0;
            var companySetting = new CompanySetting
            {
                SettingID = maxSettingId + 1,
                CompanyName = request.CompanyName.Trim(),
                AddressLine1 = "Office Location",
                CityStatePincode = "",
                WorkDayCutoffHour = request.WorkDayCutoffHour,
                LateGraceMinutes = 15,
                EndTimeGraceMinutes = 15
            };

            db.CompanySettings.Add(companySetting);
            await db.SaveChangesAsync();

            // Create FeatureSettings
            var maxFeatureId = await db.FeatureSettings.MaxAsync(f => (int?)f.Id) ?? 0;
            var features = request.InitialFeatures ?? new FeatureSettings();
            features.Id = maxFeatureId + 1;
            features.FirebasePlanMode = request.PlanMode;
            db.FeatureSettings.Add(features);
            await db.SaveChangesAsync();

            // Create CompanyTenant
            var tenant = new CompanyTenant
            {
                TenantId = tenantId,
                CompanyName = request.CompanyName.Trim(),
                CompanyCode = code,
                AdminUserId = adminUser.Id,
                AdminEmail = request.AdminEmail.Trim(),
                AdminName = string.IsNullOrWhiteSpace(request.AdminName) ? request.CompanyName.Trim() + " Admin" : request.AdminName.Trim(),
                AdminPhone = request.AdminPhone,
                IconEmoji = string.IsNullOrWhiteSpace(request.IconEmoji) ? "🏢" : request.IconEmoji.Trim(),
                PlanMode = request.PlanMode,
                IsActive = true,
                CreatedAtUtc = DateTime.UtcNow,
                CompanySettingId = companySetting.SettingID,
                FeatureSettingsId = features.Id
            };

            db.CompanyTenants.Add(tenant);
            await db.SaveChangesAsync();

            // Initialize Firebase tenant nodes
            try
            {
                var tenantPayload = new Dictionary<string, object?>
                {
                    ["tenantId"] = tenant.TenantId,
                    ["companyName"] = tenant.CompanyName,
                    ["companyCode"] = tenant.CompanyCode,
                    ["adminEmail"] = tenant.AdminEmail,
                    ["adminName"] = tenant.AdminName,
                    ["iconEmoji"] = tenant.IconEmoji,
                    ["planMode"] = tenant.PlanMode,
                    ["isActive"] = tenant.IsActive,
                    ["createdAtUtc"] = tenant.CreatedAtUtc.ToString("O")
                };

                await _firebase.SetAsync($"tenants/{tenant.TenantId}", tenantPayload, default);

                // Publish initial company setting to Firebase owner
                var companyPayload = new Dictionary<string, object?>
                {
                    ["companyName"] = tenant.CompanyName,
                    ["officeLatitude"] = request.OfficeLatitude,
                    ["officeLongitude"] = request.OfficeLongitude,
                    ["geoRadiusMeters"] = request.GeoRadiusMeters,
                    ["workDayCutoffHour"] = request.WorkDayCutoffHour
                };
                await _firebase.SetAsync($"owners/{tenant.TenantId}/company_settings/1", companyPayload, default);
            }
            catch (Exception fbEx)
            {
                _logger.LogWarning(fbEx, "Firebase tenant metadata write deferred for {TenantId}", tenant.TenantId);
            }

            await _refreshService.NotifyGlobalRefreshAsync("TENANTS_UPDATED");
            _logger.LogInformation("Successfully created tenant {TenantId} for {CompanyName}", tenant.TenantId, tenant.CompanyName);

            return new TenantOperationResult
            {
                Success = true,
                Tenant = tenant
            };
        }

        public async Task<TenantOperationResult> UpdateTenantProfileAndCredentialsAsync(
            CompanyTenant updated,
            string? newAdminEmail,
            string? newAdminPassword)
        {
            if (string.IsNullOrWhiteSpace(updated.CompanyName))
                return new TenantOperationResult { Success = false, ErrorMessage = "Company Name is required." };

            await using var db = await _dbFactory.CreateDbContextAsync();
            var existing = await db.CompanyTenants.FirstOrDefaultAsync(t => t.Id == updated.Id);
            if (existing == null)
                return new TenantOperationResult { Success = false, ErrorMessage = "Company Tenant record not found." };

            string targetEmail = string.IsNullOrWhiteSpace(newAdminEmail) ? existing.AdminEmail.Trim() : newAdminEmail.Trim();

            // Validate email format
            if (!Regex.IsMatch(targetEmail, @"^[^@\s]+@[^@\s]+\.[^@\s]+$"))
                return new TenantOperationResult { Success = false, ErrorMessage = "Invalid Administrator Email format." };

            bool emailChanged = !string.Equals(existing.AdminEmail, targetEmail, StringComparison.OrdinalIgnoreCase);

            if (emailChanged)
            {
                // Check if target email belongs to another company
                var emailInUseByOtherTenant = await db.CompanyTenants.AnyAsync(t => t.Id != existing.Id && t.AdminEmail.ToLower() == targetEmail.ToLower());
                if (emailInUseByOtherTenant)
                    return new TenantOperationResult { Success = false, ErrorMessage = $"Email '{targetEmail}' is already assigned as administrator of another company." };
            }

            if (!string.IsNullOrWhiteSpace(newAdminPassword) && newAdminPassword.Length < 6)
                return new TenantOperationResult { Success = false, ErrorMessage = "Password must be at least 6 characters long." };

            // Find or create Identity admin user
            IdentityUser? adminUser = null;
            if (!string.IsNullOrWhiteSpace(existing.AdminUserId))
            {
                adminUser = await _userManager.FindByIdAsync(existing.AdminUserId);
            }
            if (adminUser == null)
            {
                adminUser = await _userManager.FindByEmailAsync(existing.AdminEmail) ?? await _userManager.FindByEmailAsync(targetEmail);
            }

            if (adminUser != null)
            {
                if (emailChanged)
                {
                    var setEmailRes = await _userManager.SetEmailAsync(adminUser, targetEmail);
                    var setUsernameRes = await _userManager.SetUserNameAsync(adminUser, targetEmail);
                    if (!setEmailRes.Succeeded || !setUsernameRes.Succeeded)
                    {
                        var err = string.Join("; ", setEmailRes.Errors.Concat(setUsernameRes.Errors).Select(e => e.Description));
                        _logger.LogWarning("Failed to update Identity email: {Err}", err);
                    }
                }

                if (!string.IsNullOrWhiteSpace(newAdminPassword))
                {
                    var token = await _userManager.GeneratePasswordResetTokenAsync(adminUser);
                    var resetRes = await _userManager.ResetPasswordAsync(adminUser, token, newAdminPassword);
                    if (!resetRes.Succeeded)
                    {
                        await _userManager.RemovePasswordAsync(adminUser);
                        var addRes = await _userManager.AddPasswordAsync(adminUser, newAdminPassword);
                        if (!addRes.Succeeded)
                        {
                            var err = string.Join("; ", addRes.Errors.Select(e => e.Description));
                            return new TenantOperationResult { Success = false, ErrorMessage = $"Could not update admin password: {err}" };
                        }
                    }
                }

                if (!await _roleManager.RoleExistsAsync("Admin"))
                {
                    await _roleManager.CreateAsync(new IdentityRole("Admin"));
                }

                if (!await _userManager.IsInRoleAsync(adminUser, "Admin") && !await _userManager.IsInRoleAsync(adminUser, "SuperAdmin"))
                {
                    await _userManager.AddToRoleAsync(adminUser, "Admin");
                }
            }
            else
            {
                if (!await _roleManager.RoleExistsAsync("Admin"))
                {
                    await _roleManager.CreateAsync(new IdentityRole("Admin"));
                }

                adminUser = new IdentityUser
                {
                    UserName = targetEmail,
                    Email = targetEmail,
                    EmailConfirmed = true
                };

                var initialPassword = string.IsNullOrWhiteSpace(newAdminPassword) ? "Admin@123" : newAdminPassword;
                var createRes = await _userManager.CreateAsync(adminUser, initialPassword);
                if (!createRes.Succeeded)
                {
                    var err = string.Join("; ", createRes.Errors.Select(e => e.Description));
                    return new TenantOperationResult { Success = false, ErrorMessage = $"Failed to create admin user: {err}" };
                }
                await _userManager.AddToRoleAsync(adminUser, "Admin");
            }

            // Sync to Firebase Auth
            try
            {
                await _firebase.EnsureFirebaseUserAsync(
                    targetEmail,
                    string.IsNullOrWhiteSpace(newAdminPassword) ? "" : newAdminPassword,
                    "Admin",
                    displayName: string.IsNullOrWhiteSpace(updated.AdminName) ? updated.CompanyName.Trim() + " Admin" : updated.AdminName.Trim(),
                    updatePasswordIfExisting: !string.IsNullOrWhiteSpace(newAdminPassword));
            }
            catch (Exception fbAuthEx)
            {
                _logger.LogWarning(fbAuthEx, "Firebase Auth credential sync deferred for {Email}", targetEmail);
            }

            // Update Tenant entity
            existing.CompanyName = updated.CompanyName.Trim();
            existing.AdminUserId = adminUser.Id;
            existing.AdminEmail = targetEmail;
            existing.AdminName = string.IsNullOrWhiteSpace(updated.AdminName) ? existing.CompanyName + " Admin" : updated.AdminName.Trim();
            existing.AdminPhone = updated.AdminPhone;
            existing.IconEmoji = string.IsNullOrWhiteSpace(updated.IconEmoji) ? "🏢" : updated.IconEmoji.Trim();
            existing.PlanMode = updated.PlanMode;
            existing.IsActive = updated.IsActive;

            await db.SaveChangesAsync();

            // Sync tenant metadata to Firebase Realtime Database
            try
            {
                var tenantPayload = new Dictionary<string, object?>
                {
                    ["companyName"] = existing.CompanyName,
                    ["adminEmail"] = existing.AdminEmail,
                    ["adminName"] = existing.AdminName,
                    ["adminPhone"] = existing.AdminPhone,
                    ["iconEmoji"] = existing.IconEmoji,
                    ["planMode"] = existing.PlanMode,
                    ["isActive"] = existing.IsActive
                };
                await _firebase.UpdateAsync(new Dictionary<string, object?>
                {
                    [$"tenants/{existing.TenantId}"] = tenantPayload
                }, default);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Firebase tenant metadata update deferred.");
            }

            await _refreshService.NotifyGlobalRefreshAsync("TENANTS_UPDATED");
            return new TenantOperationResult { Success = true, Tenant = existing };
        }

        public async Task<bool> UpdateTenantAsync(CompanyTenant updated)
        {
            var res = await UpdateTenantProfileAndCredentialsAsync(updated, updated.AdminEmail, null);
            return res.Success;
        }

        public async Task<bool> SaveTenantFeaturesAsync(int tenantId, FeatureSettings newSettings)
        {
            await using var db = await _dbFactory.CreateDbContextAsync();
            var tenant = await db.CompanyTenants.FirstOrDefaultAsync(t => t.Id == tenantId);
            if (tenant == null) return false;

            var existingFeatures = await db.FeatureSettings.FirstOrDefaultAsync(f => f.Id == tenant.FeatureSettingsId);
            if (existingFeatures != null)
            {
                db.Entry(existingFeatures).CurrentValues.SetValues(newSettings);
            }
            else
            {
                db.FeatureSettings.Add(newSettings);
                await db.SaveChangesAsync();
                tenant.FeatureSettingsId = newSettings.Id;
            }

            await db.SaveChangesAsync();

            // Mirror feature settings to Firebase under tenant's owner node
            try
            {
                await _firebase.SetAsync($"owners/{tenant.TenantId}/feature_settings/1", newSettings, default);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Failed to mirror tenant feature settings to Firebase.");
            }

            await _refreshService.NotifyGlobalRefreshAsync("FEATURE_TOGGLES_UPDATED");
            return true;
        }

        public async Task<List<CompanyTenant>> GetAllTenantsAsync()
        {
            await using var db = await _dbFactory.CreateDbContextAsync();
            return await db.CompanyTenants
                .AsNoTracking()
                .OrderByDescending(t => t.IsActive)
                .ThenBy(t => t.CompanyName)
                .ToListAsync();
        }

        public async Task<FeatureSettings?> GetTenantFeaturesAsync(int tenantId)
        {
            await using var db = await _dbFactory.CreateDbContextAsync();
            var tenant = await db.CompanyTenants.AsNoTracking().FirstOrDefaultAsync(t => t.Id == tenantId);
            if (tenant == null) return null;

            return await db.FeatureSettings.AsNoTracking().FirstOrDefaultAsync(f => f.Id == tenant.FeatureSettingsId);
        }

        public async Task<CompanySetting?> GetTenantCompanySettingAsync(int tenantId)
        {
            await using var db = await _dbFactory.CreateDbContextAsync();
            var tenant = await db.CompanyTenants.AsNoTracking().FirstOrDefaultAsync(t => t.Id == tenantId);
            if (tenant == null) return null;

            return await db.CompanySettings.AsNoTracking().FirstOrDefaultAsync(c => c.SettingID == tenant.CompanySettingId);
        }

        public async Task<bool> UpdateTenantCompanySettingAsync(int tenantId, CompanySetting setting)
        {
            await using var db = await _dbFactory.CreateDbContextAsync();
            var tenant = await db.CompanyTenants.FirstOrDefaultAsync(t => t.Id == tenantId);
            if (tenant == null) return false;

            var existing = await db.CompanySettings.FirstOrDefaultAsync(c => c.SettingID == tenant.CompanySettingId);
            if (existing != null)
            {
                existing.CompanyName = setting.CompanyName;
                existing.AddressLine1 = setting.AddressLine1;
                existing.CityStatePincode = setting.CityStatePincode;
                existing.WorkDayCutoffHour = setting.WorkDayCutoffHour;
                existing.LateGraceMinutes = setting.LateGraceMinutes;
                existing.EndTimeGraceMinutes = setting.EndTimeGraceMinutes;
                existing.OfficeLatitude = setting.OfficeLatitude;
                existing.OfficeLongitude = setting.OfficeLongitude;
                existing.GeoRadiusMeters = setting.GeoRadiusMeters;
                existing.ZktecoIP = setting.ZktecoIP;
                existing.ZktecoPort = setting.ZktecoPort;
                await db.SaveChangesAsync();

                // Mirror to Firebase
                try
                {
                    var companyPayload = new Dictionary<string, object?>
                    {
                        ["companyName"] = existing.CompanyName,
                        ["officeLatitude"] = existing.OfficeLatitude,
                        ["officeLongitude"] = existing.OfficeLongitude,
                        ["geoRadiusMeters"] = existing.GeoRadiusMeters,
                        ["workDayCutoffHour"] = existing.WorkDayCutoffHour
                    };
                    await _firebase.SetAsync($"owners/{tenant.TenantId}/company_settings/1", companyPayload, default);
                }
                catch (Exception ex)
                {
                    _logger.LogWarning(ex, "Failed to mirror tenant company setting to Firebase.");
                }
                return true;
            }
            return false;
        }

        public async Task<bool> ToggleTenantActiveAsync(int tenantId)
        {
            await using var db = await _dbFactory.CreateDbContextAsync();
            var tenant = await db.CompanyTenants.FirstOrDefaultAsync(t => t.Id == tenantId);
            if (tenant == null) return false;

            tenant.IsActive = !tenant.IsActive;
            await db.SaveChangesAsync();

            try
            {
                await _firebase.UpdateAsync(new Dictionary<string, object?>
                {
                    [$"tenants/{tenant.TenantId}/isActive"] = tenant.IsActive
                }, default);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Failed to update tenant status in Firebase.");
            }

            await _refreshService.NotifyGlobalRefreshAsync("TENANTS_UPDATED");
            return true;
        }
    }
}

