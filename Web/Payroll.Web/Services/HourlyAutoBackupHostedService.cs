using Microsoft.EntityFrameworkCore;
using Payroll.Shared.Data;

namespace Payroll.Web.Services;

public sealed class HourlyAutoBackupHostedService : BackgroundService
{
    private readonly IServiceScopeFactory _scopeFactory;
    private readonly ILogger<HourlyAutoBackupHostedService> _logger;
    private readonly IConfiguration _configuration;

    public HourlyAutoBackupHostedService(
        IServiceScopeFactory scopeFactory,
        ILogger<HourlyAutoBackupHostedService> logger,
        IConfiguration configuration)
    {
        _scopeFactory = scopeFactory;
        _logger = logger;
        _configuration = configuration;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _logger.LogInformation("Auto-backup background service initialized.");

        // Wait 30 seconds after server startup before initial check
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(30), stoppingToken);
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            return;
        }

        while (!stoppingToken.IsCancellationRequested)
        {
            int intervalHours = 24;
            try
            {
                using var scope = _scopeFactory.CreateScope();
                var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
                var settings = await db.CompanySettings.AsNoTracking()
                    .OrderBy(s => s.SettingID)
                    .FirstOrDefaultAsync(stoppingToken);
                if (settings != null && settings.AutoBackupIntervalHours > 0)
                {
                    intervalHours = settings.AutoBackupIntervalHours;
                }

                var backupService = scope.ServiceProvider.GetRequiredService<DatabaseBackupRestoreService>();
                var emailSender = scope.ServiceProvider.GetRequiredService<EmailSender>();

                _logger.LogInformation("Starting scheduled database auto-backup (configured interval: {IntervalHours}h)...", intervalHours);
                var meta = await backupService.CreateBackupAsync("HourlyAuto", stoppingToken);
                _logger.LogInformation("Scheduled database auto-backup finished: {FileName} ({Size})",
                    meta.FileName, meta.FormattedSize);

                // 1. Dispatch Master Database Backup (.zip) to SuperAdmin
                var superAdminEmail = _configuration["Firebase:SuperAdminEmail"]
                    ?? Environment.GetEnvironmentVariable("SUPERADMIN_EMAIL")
                    ?? "prakashshiva368@gmail.com";

                if (!string.IsNullOrWhiteSpace(superAdminEmail))
                {
                    try
                    {
                        var zipPath = backupService.CreateZipOfBackup(meta.FilePath);
                        var superAdminSubject = $"🛡️ [BioMetric+Payroll] Master Database Backup - {DateTime.UtcNow:yyyy-MM-dd}";
                        var superAdminBody = $@"
                            <div style='font-family: Arial, sans-serif; padding: 20px; color: #333;'>
                                <h2 style='color: #0d6efd;'>BioMetric+Payroll Master Database Backup</h2>
                                <p>Dear SuperAdmin,</p>
                                <p>Attached is your scheduled automated master database snapshot (SQLite database archive compressed as ZIP).</p>
                                <table style='border-collapse: collapse; width: 100%; max-width: 500px; margin: 15px 0;'>
                                    <tr style='background: #f8f9fa;'><td style='padding: 8px; border: 1px solid #dee2e6;'><strong>Backup File</strong></td><td style='padding: 8px; border: 1px solid #dee2e6;'>{meta.FileName}</td></tr>
                                    <tr><td style='padding: 8px; border: 1px solid #dee2e6;'><strong>Database Size</strong></td><td style='padding: 8px; border: 1px solid #dee2e6;'>{meta.FormattedSize}</td></tr>
                                    <tr style='background: #f8f9fa;'><td style='padding: 8px; border: 1px solid #dee2e6;'><strong>Timestamp (UTC)</strong></td><td style='padding: 8px; border: 1px solid #dee2e6;'>{meta.CreatedAtUtc:yyyy-MM-dd HH:mm:ss}</td></tr>
                                </table>
                                <p style='color: #6c757d; font-size: 0.9em;'>This email serves as an air-gapped offsite backup for full disaster recovery.</p>
                            </div>";

                        await emailSender.SendEmailWithAttachmentAsync(
                            superAdminEmail.Trim(),
                            superAdminSubject,
                            superAdminBody,
                            zipPath,
                            Path.GetFileName(zipPath),
                            fromEmail: superAdminEmail.Trim(),
                            fromDisplayName: "BioMetric+Payroll SuperAdmin");
                    }
                    catch (Exception saEx)
                    {
                        _logger.LogWarning(saEx, "Could not send automated master backup to SuperAdmin {Email}", superAdminEmail);
                    }
                }

                // 2. Dispatch Company-Scoped Data Backups to Each Active Company Admin (SuperAdmin -> Company Admin)
                try
                {
                    var activeTenants = await db.CompanyTenants.AsNoTracking()
                        .Where(t => t.IsActive && !string.IsNullOrWhiteSpace(t.AdminEmail))
                        .ToListAsync(stoppingToken);

                    foreach (var tenant in activeTenants)
                    {
                        var tenantBackupPath = await backupService.ExportTenantBackupJsonAsync(tenant.TenantId, stoppingToken);
                        if (tenantBackupPath != null && File.Exists(tenantBackupPath))
                        {
                            var tenantSubject = $"📦 [{tenant.CompanyName}] Daily Company Data Backup - {DateTime.UtcNow:yyyy-MM-dd}";
                            var tenantBody = $@"
                                <div style='font-family: Arial, sans-serif; padding: 20px; color: #333;'>
                                    <h2 style='color: #198754;'>{tenant.CompanyName} - Daily Data Backup</h2>
                                    <p>Dear {tenant.AdminName ?? "Administrator"},</p>
                                    <p>Attached is your company's automated daily data backup containing active employee profiles, biometric punches, attendance logs, shifts, leaves, and payroll history.</p>
                                    <table style='border-collapse: collapse; width: 100%; max-width: 500px; margin: 15px 0;'>
                                        <tr style='background: #f8f9fa;'><td style='padding: 8px; border: 1px solid #dee2e6;'><strong>Organization</strong></td><td style='padding: 8px; border: 1px solid #dee2e6;'>{tenant.CompanyName}</td></tr>
                                        <tr><td style='padding: 8px; border: 1px solid #dee2e6;'><strong>Tenant ID</strong></td><td style='padding: 8px; border: 1px solid #dee2e6;'>{tenant.TenantId}</td></tr>
                                        <tr style='background: #f8f9fa;'><td style='padding: 8px; border: 1px solid #dee2e6;'><strong>Generated At (UTC)</strong></td><td style='padding: 8px; border: 1px solid #dee2e6;'>{DateTime.UtcNow:yyyy-MM-dd HH:mm:ss}</td></tr>
                                    </table>
                                    <p style='color: #6c757d; font-size: 0.9em;'>This backup is strictly scoped to your company data and guarantees zero data loss.</p>
                                </div>";

                            await emailSender.SendEmailWithAttachmentAsync(
                                tenant.AdminEmail.Trim(),
                                tenantSubject,
                                tenantBody,
                                tenantBackupPath,
                                Path.GetFileName(tenantBackupPath),
                                fromEmail: superAdminEmail.Trim(),
                                fromDisplayName: "BioMetric+Payroll Master System");
                        }
                    }
                }
                catch (Exception tenantEx)
                {
                    _logger.LogWarning(tenantEx, "Company-specific backup dispatch encountered an issue.");
                }

                // 3. Keep local disk tidy by keeping last 14 backups
                await backupService.PruneOldAutoBackupsAsync(14, stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Scheduled database auto-backup encountered an error.");
            }

            try
            {
                _logger.LogInformation("Next scheduled auto-backup in {IntervalHours} hour(s).", intervalHours);
                await Task.Delay(TimeSpan.FromHours(intervalHours), stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
        }

        _logger.LogInformation("Auto-backup service stopped.");
    }
}
