using Microsoft.EntityFrameworkCore;
using Payroll.Shared.Data;

namespace Payroll.Web.Services;

public sealed class HourlyAutoBackupHostedService : BackgroundService
{
    private readonly IServiceScopeFactory _scopeFactory;
    private readonly ILogger<HourlyAutoBackupHostedService> _logger;

    public HourlyAutoBackupHostedService(
        IServiceScopeFactory scopeFactory,
        ILogger<HourlyAutoBackupHostedService> logger)
    {
        _scopeFactory = scopeFactory;
        _logger = logger;
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

                _logger.LogInformation("Starting scheduled database auto-backup (configured interval: {IntervalHours}h)...", intervalHours);
                var meta = await backupService.CreateBackupAsync("HourlyAuto", stoppingToken);
                _logger.LogInformation("Scheduled database auto-backup finished: {FileName} ({Size})",
                    meta.FileName, meta.FormattedSize);
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
