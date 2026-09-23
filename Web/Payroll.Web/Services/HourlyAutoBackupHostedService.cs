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
        _logger.LogInformation("Hourly auto-backup service starting. Interval: 1 hour.");

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
            try
            {
                using var scope = _scopeFactory.CreateScope();
                var backupService = scope.ServiceProvider.GetRequiredService<DatabaseBackupRestoreService>();

                _logger.LogInformation("Starting scheduled hourly database backup...");
                var meta = await backupService.CreateBackupAsync("HourlyAuto", stoppingToken);
                _logger.LogInformation("Scheduled hourly database backup finished: {FileName} ({Size})",
                    meta.FileName, meta.FormattedSize);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Hourly database auto-backup encountered an error.");
            }

            try
            {
                await Task.Delay(TimeSpan.FromHours(1), stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
        }

        _logger.LogInformation("Hourly auto-backup service stopped.");
    }
}
