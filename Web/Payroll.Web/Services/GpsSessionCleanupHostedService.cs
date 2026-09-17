using Microsoft.Extensions.Hosting;

namespace Payroll.Web.Services;

/// <summary>
/// Periodically invokes the existing GPS session timeout lifecycle.
/// The domain rules remain in GeoLocationService.
/// </summary>
public sealed class GpsSessionCleanupHostedService : BackgroundService
{
    private readonly IServiceScopeFactory _scopeFactory;
    private readonly ILogger<GpsSessionCleanupHostedService> _logger;
    private readonly IConfiguration _configuration;

    public GpsSessionCleanupHostedService(
        IServiceScopeFactory scopeFactory,
        ILogger<GpsSessionCleanupHostedService> logger,
        IConfiguration configuration)
    {
        _scopeFactory = scopeFactory;
        _logger = logger;
        _configuration = configuration;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var intervalSeconds = Math.Max(15,
            _configuration.GetValue<int>("GpsSessionCleanup:CheckIntervalSeconds", 60));

        _logger.LogInformation(
            "GPS session lifecycle cleanup started. CheckInterval={Interval}s",
            intervalSeconds);

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                using var scope = _scopeFactory.CreateScope();
                var geo = scope.ServiceProvider.GetRequiredService<GeoLocationService>();
                await geo.MarkTimedOutSessionsAsync();
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "GPS session lifecycle cleanup failed.");
            }

            try
            {
                await Task.Delay(TimeSpan.FromSeconds(intervalSeconds), stoppingToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }
}
