# 1200-V: Offline Tracking & Reconnection Sync Engine Specification

> **Document ID:** `1200-V`  
> **Status:** Production / Implemented  
> **Target Platforms:** Android (Kotlin / Room / WorkManager / OSMdroid) & Web (Blazor / .NET 8 / EF Core / Firebase RTDB)  
> **Core Concept:** 100% Autonomous Offline GPS Ledger, Silent Hardware Satellite Tracking, Local Queue Buffering, Automatic Reconnection Drain, and Retroactive Hybrid Attendance Reconciliation.

---

## 1. Executive Summary & Objective

In field service, construction, security, and enterprise attendance management, mobile devices frequently enter dead zones, experience battery-saver network throttles, or are intentionally disconnected by users (e.g., turning off mobile data/Wi-Fi or toggling **Airplane Mode**) while keeping the phone powered on.

The **Offline Tracking & Sync Engine** guarantees:
1. **Silent Hardware GPS Capture**: While mobile power is on, hardware GPS coordinates are continuously received and logged by the foreground tracking service (`TrackingService`) holding an Android CPU partial wake-lock.
2. **Local Queue Buffering (Room SQLite)**: Each offline GPS fix is stored locally in `location_points` with `isOfflineCapture = true` and `syncState = SYNC_PENDING`.
3. **Real-Time Geofence Auto-Punches (Offline)**: If the employee enters or exits the company's designated office radius while offline, `GeofenceAutoPunchCoordinator` calculates distance against locally cached coordinates and immediately records an `AUTO_IN` or `AUTO_OUT` punch into SQLite Room (`syncState = 0`).
4. **Autonomous Reconnection Sync (WorkManager)**: When internet connectivity is restored, `OfflineSyncWorker` activates automatically, flushes pending punches to Firebase RTDB (`attendance_punches`), drains offline GPS breadcrumbs to the cloud tracking ledger (`/tracking/history`), and marks local records as `SYNCED`.
5. **Full Transparency in Offline Tracking Screen**: Both Android and Web dashboards show:
   - When the disconnection started and ended.
   - Total offline duration (e.g., `30m 00s`).
   - Disconnect reason (`Airplane Mode`, `Mobile Data / Wi-Fi Off`, or `Signal Loss`).
   - Polyline route traveled during the offline gap plotted on the map.
   - Breakdown of waypoints captured **Inside** vs. **Outside** the geofence radius.
   - Attendance punches taken while offline and their retroactive impact on the daily attendance log.

---

## 2. System Architecture & Lifecycle

```
[ Mobile Enters Offline Mode ]
        │
        ▼
[ TrackingService (Foreground + Partial WakeLock) ]
   ├── LocationCallback: receives (lat, lon, speed, accuracy, time) from FusedLocationClient
   ├── Evaluates Geofence: calculateDistance(lat, lon, officeLat, officeLon)
   │     └── Crosses Radius? ──► GeofenceAutoPunchCoordinator
   │                               ├── Creates AUTO IN / OUT LocalAttendancePunch (syncState = 0)
   │                               └── Saved to SQLite Room DB
   ├── Buffers GPS Fix ──► Room DB: LocationPointEntity (syncState = PENDING, isOffline = true)
   └── Logs Disconnect Event ──► Room DB: OfflineTrackingEvent ("Airplane Mode" / "Data Off")
        │
[ Device Reconnects to Internet ]
        │
        ▼
[ WorkManager: OfflineSyncWorker (NetworkType.CONNECTED) ]
   ├── Step 1: syncOfflinePunches()
   │     └── Reads unsynced punches (syncState = 0) ──► Pushes to Firebase RTDB
   │     └── Marks punch as syncState = 1 in local SQLite
   ├── Step 2: Drains GPS points (getPendingForSync)
   │     └── Pushes to Firebase /tracking/history/{empId}/{timestamp} (isOffline = true)
   │     └── Marks location as SYNCED in local SQLite
   └── Step 3: Drains tracking lifecycle events (SESSION_STARTED, OFFLINE_PERIOD, etc.)
        │
        ▼
[ Offline Tracking Dashboard (Android & Web) ]
   ├── Visualizes exact offline window & duration
   ├── Renders orange polyline route of points captured during disconnection
   ├── Displays Inside vs Outside geofence waypoint count
   └── Links offline punches into Daily Attendance Summary & Journey Timeline
```

---

## 3. Component Breakdown

### 3.1 Android Components

| Class / File | Purpose & Responsibilities |
| :--- | :--- |
| [`TrackingService.kt`](file:///E:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/domain/location/TrackingService.kt) | Runs as a foreground service with `PARTIAL_WAKE_LOCK`. Captures GPS satellite fixes without network dependency. Dispatches locations to `GeofenceAutoPunchCoordinator` and queues unacknowledged points in Room. |
| [`GeofenceAutoPunchCoordinator.kt`](file:///E:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/domain/location/GeofenceAutoPunchCoordinator.kt) | Evaluates geofence boundary crossings in real time on the device. Creates `AUTO_IN` / `AUTO_OUT` punches in local SQLite Room with `syncState = 0`. |
| [`OfflineSyncWorker.kt`](file:///E:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/domain/location/OfflineSyncWorker.kt) | WorkManager background worker triggered on `NetworkType.CONNECTED`. Synchronizes pending punches first, followed by pending GPS points and lifecycle events to Firebase RTDB. |
| [`OfflineTrackingMonitor.kt`](file:///E:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/domain/location/OfflineTrackingMonitor.kt) | Monitors network state transitions, detects airplane mode and data toggle reasons, records timestamps, and manages local retention policies. |
| [`OfflineTrackingActivity.kt`](file:///E:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/ui/OfflineTrackingActivity.kt) | Android UI displaying: connection health cards, offline disconnection intervals, detailed employee inspection cards, interactive OSMdroid route map, and chronological journey timeline. |

### 3.2 Web Components

| Class / File | Purpose & Responsibilities |
| :--- | :--- |
| [`OfflineTracking.razor`](file:///E:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Components/Pages/Admin/OfflineTracking.razor) | Web Admin dashboard showing live/stale/offline connection status, offline intervals grouped by employee, offline punches taken, and breadcrumb route tables. |
| [`OfflineTrackingDetails.razor`](file:///E:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Components/Pages/Admin/OfflineTrackingDetails.razor) | In-depth audit viewer for historical offline sessions, raw GPS telemetry, speed, accuracy, and geofence boundary analytics. |
| [`AttendancePunchProcessor.cs`](file:///E:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Shared/Services/Attendance/AttendancePunchProcessor.cs) | Hybrid 3-tier attendance calculation engine running on Web to synthesize synchronized offline punches into official daily attendance summaries. |

---

## 4. Detailed Data Schemas

### 4.1 Local SQLite Room: `location_points`
```sql
CREATE TABLE location_points (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    clientEventId TEXT NOT NULL,
    sessionId TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    accuracy REAL NOT NULL,
    speed REAL NOT NULL,
    bearing REAL NOT NULL,
    batteryLevel INTEGER NOT NULL,
    timestamp INTEGER NOT NULL,
    syncState TEXT NOT NULL DEFAULT 'PENDING',  -- PENDING, IN_FLIGHT, SYNCED, FAILED
    isOfflineCapture INTEGER NOT NULL DEFAULT 0,
    syncedAt INTEGER,
    lastError TEXT
);
```

### 4.2 Local SQLite Room: `attendance_punches`
```sql
CREATE TABLE attendance_punches (
    punchId TEXT PRIMARY KEY NOT NULL,
    staffId TEXT NOT NULL,
    date TEXT NOT NULL,
    type TEXT NOT NULL,                         -- IN, OUT
    timestamp INTEGER NOT NULL,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    accuracy REAL NOT NULL,
    source TEXT NOT NULL,                       -- GEOFENCE_AUTO, MANUAL_ADMIN, PHYSICAL_MACHINE
    status TEXT NOT NULL DEFAULT 'APPROVED',
    syncState INTEGER NOT NULL DEFAULT 0,       -- 0 = Pending, 1 = Synced
    lastModified INTEGER NOT NULL
);
```

### 4.3 Firebase RTDB: Cloud SSOT Nodes
- **Offline Punches Node:**  
  `owners/{tenantId}/attendance_punches/{punchId}`  
  Contains punch metadata, coordinates, source (`GEOFENCE_AUTO`), and original capture timestamp.
- **Offline Route History Node:**  
  `owners/{tenantId}/tracking/history/{employeeId}/{timestamp}`  
  Contains waypoints with `isOffline: true`, `movementState`, `speedMps`, and `accuracyMeters`.

---

## 5. UI Features & User Interaction

### 5.1 Android Offline Tracking Screen
1. **Connectivity Header**: Live status banner showing whether current device is online or offline, disconnect reason, and pending queue depth.
2. **Employee Status List**: Searchable list of all employees showing Live/Stale/Offline state, last seen time, movement state, current geofence status, and punch status.
3. **Selected Employee Inspector Card**:
   - Punch In / Out details with exact timestamps and geofence distance.
   - Live / last recorded GPS coordinates and movement.
   - Synchronized cloud points count vs local pending queue count.
4. **Offline Intervals Table**:
   - Disconnection window: `14:15:00 → 14:45:00 IST`.
   - Elapsed offline time: `30m 00s`.
   - Reason tag: `✈️ Airplane Mode` or `📶 Mobile Data / Wi-Fi Off`.
   - Reconciled Punches: Displays any punch taken while offline (e.g. `🔴 OUT Punch at 02:25 PM`).
   - Breadcrumbs summary: Total points, distance traveled, and Inside vs. Outside count.
   - **"View Route"** button: Fits the map viewport to the orange route traveled during that specific offline interval.
5. **Interactive Map (OSMdroid)**:
   - Blue polyline: Continuous live/synced route.
   - **Orange polyline**: Route traveled while disconnected offline.
   - Green marker: Punch In location.
   - Red marker: Punch Out location.
   - Target icon: Current live employee position.
6. **Chronological Journey Timeline**: Step-by-step visual feed connecting punch-in, offline gaps, reconnection events, route streams, and punch-out.

### 5.2 Web Admin Offline Tracking Screen
1. **Summary Badges**: Real-time counter of Live, Stale, and Offline employees.
2. **Offline Disconnection Intervals Table**: Lists all detected offline intervals across all employees for the day.
3. **Reconciled Punches Column**: Shows whether punches were created offline and retroactively merged into the attendance ledger.
4. **Route Waypoint Drawer**: Collapsible sub-table displaying every waypoint captured during the offline window with timestamp, speed, movement state, and geofence inside/outside badge.

---

## 6. Retroactive Attendance Reconciliation Rules

When offline punches arrive at Firebase and sync to Web and Android:
1. **Timestamp Integrity**: The punch keeps its original hardware timestamp (e.g., `14:25:00`), never the synchronization time (`14:45:00`).
2. **Hybrid Hierarchy Integration**:
   - The 3-Tier engine in `AttendancePunchProcessor` evaluates the newly received offline punch alongside any existing physical machine or manual entries.
   - If no higher-priority manual override exists for that timestamp window, the geofence auto-punch becomes the authoritative `CheckIn` or `CheckOut`.
3. **Work Duration Adjustment**: Total work hours for the shift are recalculated retroactively using the original offline punch time.
4. **Mirror Parity**: Because both Android (`AttendancePunchProcessor.kt`) and Web (`AttendancePunchProcessor.cs`) use identical business logic, both platforms display the exact same attendance status and hours.
