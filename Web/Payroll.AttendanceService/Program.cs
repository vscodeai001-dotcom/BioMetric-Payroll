using Payroll.AttendanceService;
using Payroll.AttendanceService.Services;
using Payroll.Shared.Data;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Configuration;

// 1. Create the Host Builder
IHost host = Host.CreateDefaultBuilder(args)
    // 2. Enable running as a Windows Service
    .UseWindowsService() 
    .ConfigureServices((hostContext, services) =>
    {
        // 3. Register the local EF compatibility projection.
        // Firebase is the shared durable SSOT; this SQLite database is local-only
        // and keeps the existing worker/entity contracts intact during migration.
        var sqlitePath = Environment.GetEnvironmentVariable("BIOMETRIC_SQLITE_PATH");
        if (string.IsNullOrWhiteSpace(sqlitePath))
            sqlitePath = Path.Combine(AppContext.BaseDirectory, "data", "biometricpayroll-cache.db");

        var sqliteDirectory = Path.GetDirectoryName(sqlitePath);
        if (!string.IsNullOrWhiteSpace(sqliteDirectory))
            Directory.CreateDirectory(sqliteDirectory);

        services.AddDbContext<AppDbContext>(options =>
        {
            options.UseSqlite($"Data Source={sqlitePath}");

            // The shared model targets PostgreSQL/SQL Server and therefore
            // contains the `public` schema metadata. SQLite has no schemas,
            // so EF Core emits model-validation warnings for those mappings.
            // This worker intentionally uses SQLite only as a local cache.
            // Suppress only EF model-validation warnings for this provider;
            // database command/error logging remains unchanged.
            options.ConfigureWarnings(warnings =>
                warnings.Ignore(
                    Microsoft.EntityFrameworkCore.Diagnostics.SqliteEventId.SchemaConfiguredWarning));
        }, ServiceLifetime.Transient);

        // 4. Register Firebase worker bridge and background services
        services.AddSingleton<FirebaseWorkerSyncService>();
        services.AddHostedService<Worker>();
        services.AddHostedService<GpsSessionCleanupService>();
    })
    .Build();

// 6. Run the Service
await host.RunAsync();