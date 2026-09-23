# Complete Data Wipe, Local Backup (Manual + 1-Hour Auto), and Cloud Restore

## Overview
Currently, the **Wipe Data** button in the Danger Zone only runs SQL `DELETE` commands on the local compatibility SQLite database (`FeatureCleanUpService.cs`). It does not wipe **Firebase Realtime Database** where employees, attendance logs, and GPS tracking are authoritative. As a result, the app immediately re-synchronizes data from Firebase or continues displaying cloud data.

Furthermore, there is currently no mechanism to create local backups before wiping, no automated hourly backup service, and no restore mechanism to push restored data back to the Cloud (Firebase).

This plan outlines the architecture for:
1. **Local Database Backup (Manual)**: On-demand snapshot saving both the SQLite database (`.db`) and backup metadata.
2. **Automated 1-Hour Backup**: Background hosted service creating timestamped rolling local backups every 60 minutes.
3. **Restore to Local & Cloud**: Restoring from any selected local backup to SQLite and pushing all restored records back up to Firebase Realtime Database.
4. **Complete Data Wipe (Local + Firebase Cloud)**: Wiping all operational/transactional data from both SQLite and Firebase nodes, while safely preserving system configuration (`feature_settings`, `company_settings`, `professional_tax_slabs`, `holidays`) and SuperAdmin access, with an automatic safety backup taken prior to wiping.

---

## User Review Required

> [!WARNING]
> **Complete Data Wipe Impact:**
> - Wiping will permanently delete all employees, attendance logs, punches, salary advances, GPS tracking history, shifts, leaves, and payroll records from **both** the local SQLite database and Firebase Realtime Database.
> - Preserved tables/nodes: `feature_settings`, `company_settings`, `holidays`/`shop_closed_days`, `professional_tax_slabs`, `AspNetRoles`, and the SuperAdmin account (`prakashshiva368@gmail.com`).
> - An automatic safety backup will be created immediately before any wipe execution so nothing is ever unrecoverable.

> [!IMPORTANT]
> **Cloud Restore Behavior:**
> - Restoring a backup replaces the active local database with the chosen backup point and immediately performs a synchronization push to Firebase Realtime Database (`owners/{ownerUid}/...`), followed by an application-wide SignalR refresh notification (`SYSTEM_DATA_RESTORED`) to all connected Web dashboards and Android mobile devices.

---

## Proposed Architecture & Changes

### 1. Backend Services (`Web/Payroll.Web/Services/`)

#### [NEW] `DatabaseBackupRestoreService.cs`
- **Location:** `Web/Payroll.Web/Services/DatabaseBackupRestoreService.cs`
- **Capabilities:**
  - `CreateBackupAsync(string triggerType = "Manual")`: Uses SQLite's native online backup API (`sourceConnection.BackupDatabase(destinationConnection)`) to safely copy the WAL-mode SQLite database to `data/backups/payroll_backup_{yyyyMMdd_HHmmss}.db` with zero locking conflicts. Creates an accompanying `.json` metadata file.
  - `GetBackupsAsync()`: Scans the backup folder, reads metadata, and returns sorted list (newest first) with file size, creation timestamp, and trigger type (`Manual`, `HourlyAuto`, `PreWipeSafety`).
  - `DeleteBackupAsync(string backupId)`: Allows deleting old backups.
  - `RestoreBackupAsync(string backupFileName)`:
    1. Takes a safety backup of the current state.
    2. Overwrites/restores the SQLite database using SQLite's online backup API in reverse (`backupConnection.BackupDatabase(activeConnection)`).
    3. Pushes all restored tables to Firebase Realtime Database using `FirebaseRealtimeService` so the Cloud matches the restored local state.
    4. Emits `AttendanceRefresh.NotifyGlobalRefreshAsync("SYSTEM_DATA_RESTORED")`.
  - `WipeAllDataAsync()`:
    1. Creates a safety backup first (`PreWipeSafety`).
    2. Calls `FeatureCleanUpService.WipeAllTransactionalDataAsync()` to clean SQLite.
    3. Calls `FirebaseRealtimeService.WipeOwnerOperationalDataAsync()` to remove all operational Firebase nodes.
    4. Emits `AttendanceRefresh.NotifyGlobalRefreshAsync("SYSTEM_DATA_WIPED")`.
  - `PruneOldAutoBackupsAsync(int keepCount = 48)`: Retains the latest 48 hourly backups to prevent unbounded disk growth while preserving all manual backups.

#### [NEW] `HourlyAutoBackupHostedService.cs`
- **Location:** `Web/Payroll.Web/Services/HourlyAutoBackupHostedService.cs`
- **Role:** `BackgroundService` executing on an hourly timer (`TimeSpan.FromHours(1)`).
- Calls `DatabaseBackupRestoreService.CreateBackupAsync("HourlyAuto")` and runs cleanup of old hourly backups.

#### [MODIFY] `FirebaseRealtimeService.cs`
- **Location:** `Web/Payroll.Web/Services/FirebaseRealtimeService.cs`
- Add `WipeOwnerOperationalDataAsync(string ownerUid, CancellationToken cancellationToken)`:
  - Sends REST DELETE / null patch to Firebase SSOT operational nodes:
    - `employees`
    - `shops`
    - `attendance`
    - `attendance_punches`
    - `advance_payments`
    - `employee_history`
    - `regularizations`
    - `leave_requests`
    - `resignation_requests`
    - `salary_snapshots`
    - `audit_logs`
    - `daily_summaries`
    - `shift_schedules`
    - `payroll_history`
    - `payroll_previews`
    - `payroll_finalization`
    - `bonus_records`
    - `tax_declarations`
    - `fbp_components`
    - `fbp_declarations`
    - `year_end_summaries`
    - `fnf_settlements`
    - `report_definitions`
    - `geo_punch_audits`
    - `presence`
    - `tracking/sessions`
    - `tracking/history`
    - `tracking/live`
    - `notifications`
  - Explicitly leaves `feature_settings`, `company_settings`, `professional_tax_slabs`, and `shop_closed_days` untouched.
- Add `PushAllLocalDataToFirebaseAsync(string ownerUid, CancellationToken cancellationToken)`:
  - Iterates over all restored DbContext entity types and writes them to their corresponding Firebase nodes, restoring the Cloud to the exact backup state.

---

### 2. Dependency Injection (`Program.cs`)
- Register `DatabaseBackupRestoreService` as Scoped.
- Register `HourlyAutoBackupHostedService` via `builder.Services.AddHostedService<HourlyAutoBackupHostedService>()`.

---

### 3. UI Component (`FeatureToggleManager.razor`)
- **Location:** `Web/Payroll.Web/Components/Pages/Settings/FeatureToggleManager.razor`
- In the **Danger Zone** tab:
  - **Backup & Snapshot Card**:
    - "Backup Now" button (`bi bi-database-down`) with loading spinner.
    - Status indicator: `Hourly Auto-Backup Active (Next run: XX:XX)`.
  - **Available Backups & Restore Table**:
    - Lists all local backups with date/time, size, type (Manual / Hourly Auto / Safety Backup).
    - "Restore to Cloud & Local" button with confirmation modal.
    - Delete button for manual deletion.
  - **Wipe All Data Card (Fixed & Enhanced)**:
    - Clear explanation: "Wipes all employees, logs, GPS history, and payroll data from BOTH Local DB and Firebase Cloud. Takes an automatic safety backup first."
    - Two-stage confirmation dialog:
      - 1: "WARNING: This will wipe all transactional data from Local DB AND Firebase Cloud. A safety backup will be created first. Continue?"
      - 2: "FINAL CONFIRMATION: Type WIPE or confirm to proceed."
    - Invokes `DatabaseBackupRestoreService.WipeAllDataAsync()`.

---

## Verification Plan

### Automated Tests & Builds
- Run `dotnet build Web/Payroll.Web/Payroll.Web.csproj` to confirm clean compilation with 0 warnings/errors.

### Manual Verification
1. **Manual Backup Verification**:
   - Trigger "Backup Now" in the UI.
   - Verify that `.db` and `.json` metadata files are created in `data/backups/`.
   - Verify the backup appears in the UI table with correct size and timestamp.
2. **Auto Backup Verification**:
   - Trigger the auto-backup method or check service start log to verify hourly background timer is active and logs backup creation.
3. **Data Wipe Verification**:
   - Create/verify sample records in local DB and Firebase.
   - Click "Wipe Data", confirm.
   - Verify safety backup is created first.
   - Verify local DB tables (employees, attendance, tracking) are empty.
   - Verify Firebase nodes (`employees`, `attendance`, `tracking`) are deleted.
   - Verify SuperAdmin and configuration tables remain intact.
4. **Restore to Cloud Verification**:
   - Select a previous backup and click "Restore".
   - Verify local DB is restored with records.
   - Verify Firebase Cloud nodes are re-populated with all restored records.
   - Verify the UI refreshes and displays the restored data.
