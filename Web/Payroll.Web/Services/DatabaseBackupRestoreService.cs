using System.Text.Json;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Payroll.Shared.Data;
using Payroll.Shared.Services;

namespace Payroll.Web.Services;

public sealed class BackupMetadata
{
    public string FileName { get; set; } = string.Empty;
    public string FilePath { get; set; } = string.Empty;
    public DateTime CreatedAtUtc { get; set; }
    public long FileSizeBytes { get; set; }
    public string TriggerType { get; set; } = "Manual"; // "Manual", "HourlyAuto", "PreWipeSafety", "PreRestoreSafety"
    public string FormattedSize => FileSizeBytes switch
    {
        < 1024 => $"{FileSizeBytes} B",
        < 1024 * 1024 => $"{(FileSizeBytes / 1024.0):F1} KB",
        _ => $"{(FileSizeBytes / (1024.0 * 1024.0)):F2} MB"
    };
}

public sealed class RestoreResult
{
    public bool Success { get; set; }
    public string? ErrorMessage { get; set; }
    public int CloudRecordsRestored { get; set; }
    public string? SafetyBackupFileName { get; set; }
}

public sealed class WipeResult
{
    public bool Success { get; set; }
    public string? ErrorMessage { get; set; }
    public string? SafetyBackupFileName { get; set; }
}

public sealed class DatabaseBackupRestoreService
{
    private readonly IWebHostEnvironment _environment;
    private readonly IConfiguration _configuration;
    private readonly IDbContextFactory<AppDbContext> _dbFactory;
    private readonly FirebaseRealtimeService _firebase;
    private readonly FeatureCleanUpService _cleanerService;
    private readonly AttendanceRefreshService _refreshService;
    private readonly ILogger<DatabaseBackupRestoreService> _logger;

    public DatabaseBackupRestoreService(
        IWebHostEnvironment environment,
        IConfiguration configuration,
        IDbContextFactory<AppDbContext> dbFactory,
        FirebaseRealtimeService firebase,
        FeatureCleanUpService cleanerService,
        AttendanceRefreshService refreshService,
        ILogger<DatabaseBackupRestoreService> logger)
    {
        _environment = environment;
        _configuration = configuration;
        _dbFactory = dbFactory;
        _firebase = firebase;
        _cleanerService = cleanerService;
        _refreshService = refreshService;
        _logger = logger;
    }

    public string GetSqlitePath()
    {
        var configured = Environment.GetEnvironmentVariable("BIOMETRIC_SQLITE_PATH");
        if (!string.IsNullOrWhiteSpace(configured))
            return configured.Trim();

        return Path.Combine(_environment.ContentRootPath, "data", "biometricpayroll-cache.db");
    }

    public string GetBackupDirectory()
    {
        var dir = Path.Combine(_environment.ContentRootPath, "data", "backups");
        if (!Directory.Exists(dir))
        {
            Directory.CreateDirectory(dir);
        }
        return dir;
    }

    /// <summary>
    /// Creates a safe online backup of the SQLite database without lock contention.
    /// Also saves an accompanying JSON metadata file.
    /// </summary>
    public async Task<BackupMetadata> CreateBackupAsync(string triggerType = "Manual", CancellationToken cancellationToken = default)
    {
        var sourcePath = GetSqlitePath();
        if (!File.Exists(sourcePath))
        {
            throw new FileNotFoundException("Active database file not found at " + sourcePath);
        }

        var backupDir = GetBackupDirectory();
        var timestamp = DateTime.UtcNow;
        var fileName = $"payroll_backup_{timestamp:yyyyMMdd_HHmmss}_{triggerType.ToLowerInvariant()}.db";
        var destPath = Path.Combine(backupDir, fileName);

        // Perform safe SQLite online backup
        await Task.Run(() =>
        {
            using var sourceConn = new SqliteConnection($"Data Source={sourcePath};Cache=Shared;Mode=ReadOnly;");
            sourceConn.Open();

            using var destConn = new SqliteConnection($"Data Source={destPath};");
            destConn.Open();

            sourceConn.BackupDatabase(destConn);
        }, cancellationToken);

        var fileInfo = new FileInfo(destPath);
        var meta = new BackupMetadata
        {
            FileName = fileName,
            FilePath = destPath,
            CreatedAtUtc = timestamp,
            FileSizeBytes = fileInfo.Length,
            TriggerType = triggerType
        };

        // Write companion metadata file
        var metaPath = Path.ChangeExtension(destPath, ".json");
        var metaJson = JsonSerializer.Serialize(meta, new JsonSerializerOptions { WriteIndented = true });
        await File.WriteAllTextAsync(metaPath, metaJson, cancellationToken);

        _logger.LogInformation("Database backup created: {FileName} ({Size}, Trigger: {Trigger})",
            fileName, meta.FormattedSize, triggerType);

        if (triggerType.Equals("HourlyAuto", StringComparison.OrdinalIgnoreCase))
        {
            await PruneOldAutoBackupsAsync(48, cancellationToken);
        }

        return meta;
    }

    /// <summary>
    /// Lists all available backups ordered by creation date descending.
    /// </summary>
    public async Task<List<BackupMetadata>> GetBackupsAsync(CancellationToken cancellationToken = default)
    {
        var backupDir = GetBackupDirectory();
        var files = Directory.GetFiles(backupDir, "payroll_backup_*.db");
        var list = new List<BackupMetadata>();

        foreach (var file in files)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var metaPath = Path.ChangeExtension(file, ".json");
            BackupMetadata? meta = null;

            if (File.Exists(metaPath))
            {
                try
                {
                    var json = await File.ReadAllTextAsync(metaPath, cancellationToken);
                    meta = JsonSerializer.Deserialize<BackupMetadata>(json);
                }
                catch
                {
                    // Fall back to file info below
                }
            }

            if (meta == null)
            {
                var fi = new FileInfo(file);
                meta = new BackupMetadata
                {
                    FileName = Path.GetFileName(file),
                    FilePath = file,
                    CreatedAtUtc = fi.CreationTimeUtc,
                    FileSizeBytes = fi.Length,
                    TriggerType = file.Contains("hourlyauto", StringComparison.OrdinalIgnoreCase) ? "HourlyAuto" :
                                  file.Contains("prewipe", StringComparison.OrdinalIgnoreCase) ? "PreWipeSafety" :
                                  file.Contains("prerestore", StringComparison.OrdinalIgnoreCase) ? "PreRestoreSafety" : "Manual"
                };
            }

            list.Add(meta);
        }

        return list.OrderByDescending(x => x.CreatedAtUtc).ToList();
    }

    /// <summary>
    /// Deletes a specific backup and its metadata file.
    /// </summary>
    public Task<bool> DeleteBackupAsync(string fileName)
    {
        try
        {
            var backupDir = GetBackupDirectory();
            var sanitized = Path.GetFileName(fileName);
            var dbPath = Path.Combine(backupDir, sanitized);
            var metaPath = Path.ChangeExtension(dbPath, ".json");

            if (File.Exists(dbPath)) File.Delete(dbPath);
            if (File.Exists(metaPath)) File.Delete(metaPath);

            _logger.LogInformation("Deleted backup {FileName}", sanitized);
            return Task.FromResult(true);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to delete backup {FileName}", fileName);
            return Task.FromResult(false);
        }
    }

    /// <summary>
    /// Restores the local database from a backup file, then pushes all restored records to Firebase Cloud.
    /// </summary>
    public async Task<RestoreResult> RestoreBackupAsync(string fileName, CancellationToken cancellationToken = default)
    {
        var backupDir = GetBackupDirectory();
        var sanitized = Path.GetFileName(fileName);
        var backupPath = Path.Combine(backupDir, sanitized);

        if (!File.Exists(backupPath))
        {
            return new RestoreResult { Success = false, ErrorMessage = $"Backup file '{sanitized}' not found." };
        }

        string? safetyFileName = null;
        try
        {
            // 1. Take a safety snapshot before overwriting
            var safetyBackup = await CreateBackupAsync("PreRestoreSafety", cancellationToken);
            safetyFileName = safetyBackup.FileName;

            // 2. Restore SQLite database using online backup API in reverse
            var targetPath = GetSqlitePath();
            await Task.Run(() =>
            {
                using var sourceConn = new SqliteConnection($"Data Source={backupPath};Cache=Shared;Mode=ReadOnly;");
                sourceConn.Open();

                using var targetConn = new SqliteConnection($"Data Source={targetPath};Cache=Shared;");
                targetConn.Open();

                sourceConn.BackupDatabase(targetConn);
            }, cancellationToken);

            _logger.LogInformation("Local SQLite database restored from {FileName}", sanitized);

            // 3. Push restored records to Firebase Realtime Database Cloud
            var ownerUid = _firebase.ResolveOwnerUid("SuperAdmin");
            var cloudPushed = await _firebase.PushAllLocalDataToFirebaseAsync(ownerUid, _dbFactory, cancellationToken);

            // 4. Notify all clients
            await _refreshService.NotifyGlobalRefreshAsync("SYSTEM_DATA_RESTORED");

            return new RestoreResult
            {
                Success = true,
                CloudRecordsRestored = cloudPushed,
                SafetyBackupFileName = safetyFileName
            };
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Restore failed from {FileName}", sanitized);
            return new RestoreResult
            {
                Success = false,
                ErrorMessage = ex.Message,
                SafetyBackupFileName = safetyFileName
            };
        }
    }

    /// <summary>
    /// Partial Wipe: Wipes all operational/transactional records (attendance, GPS tracking, payroll,
    /// advances, leaves, and audit logs) from BOTH Local SQLite and Firebase Cloud.
    /// Strictly preserves all Admin Settings screens: Employees, Shops, Company Settings, Holiday Management,
    /// User & Role Management, Feature Toggles, and Tax Slabs.
    /// Takes an automated safety backup before wiping.
    /// </summary>
    public async Task<WipeResult> WipeOperationalDataOnlyAsync(CancellationToken cancellationToken = default)
    {
        string? safetyFileName = null;
        try
        {
            // 1. Create automatic safety backup before partial wiping
            var safetyBackup = await CreateBackupAsync("PrePartialWipeSafety", cancellationToken);
            safetyFileName = safetyBackup.FileName;

            // 2. Wipe Firebase Realtime Database operational nodes FIRST across all discovered owners
            var owners = await _firebase.GetAvailableOwnerUidsAsync(cancellationToken);
            var firebaseOk = true;
            foreach (var owner in owners)
            {
                var ok = await _firebase.WipeOwnerOperationalDataOnlyAsync(owner, cancellationToken);
                if (!ok) firebaseOk = false;
            }

            _logger.LogInformation(
                "Firebase Cloud operational data wiped across {Count} owner(s). Success={Success}",
                owners.Count, firebaseOk);

            // 3. Wipe SQLite database operational tables (preserves employees, settings, users, holidays)
            await _cleanerService.WipeOperationalDataOnlyAsync();
            _logger.LogInformation("Local SQLite operational data wiped successfully (settings & employees preserved).");

            // 4. Invalidate global caches
            await _refreshService.NotifyGlobalRefreshAsync("SYSTEM_DATA_WIPED");

            return new WipeResult
            {
                Success = firebaseOk,
                ErrorMessage = firebaseOk ? null : "Firebase reported one or more deletion warnings. Check server logs.",
                SafetyBackupFileName = safetyFileName
            };
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "WipeOperationalDataOnlyAsync failed.");
            return new WipeResult
            {
                Success = false,
                ErrorMessage = ex.Message,
                SafetyBackupFileName = safetyFileName
            };
        }
    }

    /// <summary>
    /// Full Wipe: Wipes all operational data PLUS employees and shops from BOTH local SQLite and Firebase Realtime Database.
    /// Preserves only configuration and SuperAdmin access, and creates a pre-wipe safety backup.
    /// </summary>
    public async Task<WipeResult> WipeAllDataAsync(CancellationToken cancellationToken = default)
    {
        string? safetyFileName = null;
        try
        {
            // 1. Create automatic safety backup before wiping
            var safetyBackup = await CreateBackupAsync("PreWipeSafety", cancellationToken);
            safetyFileName = safetyBackup.FileName;

            // 2. Wipe Firebase Realtime Database operational + employee nodes across all discovered owners
            var owners = await _firebase.GetAvailableOwnerUidsAsync(cancellationToken);
            var firebaseOk = true;
            foreach (var owner in owners)
            {
                var ok = await _firebase.WipeOwnerAllDataAsync(owner, cancellationToken);
                if (!ok) firebaseOk = false;
            }

            _logger.LogInformation(
                "Firebase Cloud all data wiped across {Count} owner(s). Success={Success}",
                owners.Count, firebaseOk);

            // 3. Wipe SQLite database tables (preserves settings & SuperAdmin)
            await _cleanerService.WipeAllTransactionalDataAsync();
            _logger.LogInformation("Local SQLite transactional data wiped successfully.");

            // 4. Invalidate global caches
            await _refreshService.NotifyGlobalRefreshAsync("SYSTEM_DATA_WIPED");

            return new WipeResult
            {
                Success = firebaseOk,
                ErrorMessage = firebaseOk ? null : "Firebase reported one or more deletion warnings. Check server logs.",
                SafetyBackupFileName = safetyFileName
            };
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "WipeAllDataAsync failed.");
            return new WipeResult
            {
                Success = false,
                ErrorMessage = ex.Message,
                SafetyBackupFileName = safetyFileName
            };
        }
    }

    /// <summary>
    /// Keeps the last keepCount hourly auto-backups, deleting older ones.
    /// </summary>
    public async Task PruneOldAutoBackupsAsync(int keepCount = 48, CancellationToken cancellationToken = default)
    {
        try
        {
            var backups = await GetBackupsAsync(cancellationToken);
            var autoBackups = backups
                .Where(x => x.TriggerType.Equals("HourlyAuto", StringComparison.OrdinalIgnoreCase))
                .OrderByDescending(x => x.CreatedAtUtc)
                .Skip(keepCount)
                .ToList();

            foreach (var old in autoBackups)
            {
                await DeleteBackupAsync(old.FileName);
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "PruneOldAutoBackupsAsync encountered a warning.");
        }
    }
}
