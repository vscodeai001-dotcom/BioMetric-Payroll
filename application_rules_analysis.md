# BioMetric+Payroll — Complete Application Rules & Conditions Reference

> All rules are grounded in actual code and documentation files. No rules are invented.

---

## 📋 Rule Set Index

| # | Rule Set | Key Files |
|---|---|---|
| 1 | [Geofence + Auto-Punch + Biometric Priority](#-rule-set-1-geofence--auto-punch--biometric-priority) | `GeoLocationService.cs`, `1200-L`, `1200-M`, `989` |
| 2 | [Feature Mode Hierarchy](#-rule-set-2-feature-mode-hierarchy-geofence--dual--auto) | `989_ATTENDANCE_POLICY_REALTIME_PARITY.md` |
| 3 | [Shift, Grace Periods & Lateness](#-rule-set-3-shift-grace-periods--lateness--early-leave) | `AttendanceCalculatorService.cs`, `AttendanceScheduleService.cs` |
| 4 | [Punch Pairing & Open Punch](#-rule-set-4-punch-pairing--open-punch--missing-punch) | `AttendancePunchProcessor.cs` |
| 5 | [Cross-Midnight / Overnight Shifts](#-rule-set-5-cross-midnight--overnight-shift-rules) | `AttendanceBoundsService.cs`, `1200-K` |
| 6 | [Break Time & Penalty](#-rule-set-6-break-time--penalty-calculation) | `AttendanceBreakPenaltyService.cs` |
| 7 | [Worked Hours & Overtime](#-rule-set-7-worked-hours--overtime-calculation) | `AttendanceCalculatorService.cs` |
| 8 | [Conflict Protection & Idempotency](#-rule-set-8-conflict-protection--idempotency) | `AttendanceProcessingCoordinator.cs`, `1200-F` |
| 9 | [Manual Override Integrity](#-rule-set-9-manual-override-integrity) | `1200-G`, `AttendanceCalculatorService.cs` |
| 10 | [Punch Correction & Regularization](#-rule-set-10-punch-correction--regularization) | `1200-H`, `1004_REGULARIZATION_FULL_PARITY.md` |
| 11 | [Leave, Holiday & Closed Day](#-rule-set-11-leave-holiday--closed-day-rules) | `1200-I`, `999_HOLIDAY_CLOSED_DAY_PARITY.md` |
| 12 | [Shift Schedule Integration](#-rule-set-12-shift-schedule-integration) | `1200-J`, `1001_SHIFT_MANAGEMENT_FULL_PARITY.md` |
| 13 | [Background GPS Tracking Lifecycle](#-rule-set-13-background-gps-tracking-lifecycle) | `993`, `1200-M`, `TrackingService.kt` |
| 14 | [Single-Device Session Enforcement](#-rule-set-14-single-device-session-enforcement) | `1100-R`, `982_FIREBASE_AUTH_ROLE_SESSION_SECURITY.md` |
| 15 | [Payroll Lock & Finalization](#-rule-set-15-payroll-lock--finalization) | `1005_PAYROLL_FINALIZATION_PARITY.md` |
| 16 | [Firebase Authority & Anti-Spoofing](#-rule-set-16-firebase-authority--anti-spoofing) | `1200-L`, `GeoLocationService.cs` |

---

## 🏆 Rule Set 1: Geofence + Auto-Punch + Biometric Priority

> **Your example scenario:** Admin enables Geofence + Dual Mode + Auto-Punch. Shift = 10:00 AM. Employee enters geofence radius at 10:25 AM → auto-punch fires. Employee physically punches ZKTeco machine at 10:30 AM → two punches exist. Which wins?

### 1.1 What triggers GEOFENCE_AUTO punch?

The server (`GeoLocationService.UpdateGpsSessionAsync`) fires an automatic punch when ALL of the following are true:

| Condition | Details |
|---|---|
| Geofence is **ON** | `feature_settings` `isGeofenceEnabled = true` |
| Auto-punch mode is **ON** | `isAutoGeofencePunching = true` |
| Employee GPS coordinate enters/exits office radius | Server recalculates distance from Admin-configured office lat/long |
| State direction agrees with attendance state | If entering (IN), employee must currently be OUT; if exiting (OUT), employee must be IN |
| No authoritative punch within ±120 seconds | No `ZKTeco_*`, `MobileWeb`, or `Android` punch in the 2-minute window |

**Code reference:** [`GeoLocationService.cs` L810–866](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Services/GeoLocationService.cs#L810-L866)

```csharp
// L811 — state-flip check
var attendanceCurrentlyOpen = todaysPunches.Count % 2 != 0;

// L824 — direction agreement: skip if geofence direction == attendance state
if (currentLocationState == attendanceCurrentlyOpen)
    return true; // no punch needed

// L830-836 — check 120-second protection window
var recentAuthoritative = todaysPunches
    .Where(IsAuthoritativeAttendancePunch)
    .Where(x => Math.Abs((x.PunchTime - punchTime).TotalSeconds) <= 120)
    .FirstOrDefault();

if (recentAuthoritative != null)
    return true; // skip auto-punch — biometric already covers it
```

### 1.2 What is "Authoritative" vs "Fallback"?

**Code reference:** [`GeoLocationService.cs` L985–1003](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Services/GeoLocationService.cs#L985-L1003)

| Punch Source | `DeviceID` | `BiometricID` | Authority Level |
|---|---|---|---|
| Physical ZKTeco machine | `ZKTeco_*` (any suffix) | Any | ✅ **AUTHORITATIVE** |
| Admin/Employee manual web punch | `MobileWeb` | Any | ✅ **AUTHORITATIVE** |
| Android manual punch | `Android` | Any | ✅ **AUTHORITATIVE** |
| Automatic geofence punch | `GeofenceAuto` | `GEOFENCE_AUTO` | ❌ **FALLBACK only** |

```csharp
// L993-1003 — IsAuthoritativeAttendancePunch()
if (device == "GeofenceAuto" || biometricId == "GEOFENCE_AUTO")
    return false;  // NEVER authoritative

return device.StartsWith("ZKTeco_") ||
       device == "MobileWeb" ||
       device == "Android";
```

### 1.3 Exact resolution of YOUR scenario

```
10:00 AM  → Shift start
10:25 AM  → Employee enters geofence radius
            Server fires GEOFENCE_AUTO IN punch (DeviceID="GeofenceAuto", BiometricID="GEOFENCE_AUTO")
            → Admin sees this in real-time on their screen immediately

10:30 AM  → Employee punches ZKTeco physical machine
            → ZKTeco punch arrives with DeviceID="ZKTeco_001" (authoritative)
            → Both punches now exist in AttendanceLogs

RESULT:
  • Both punches are KEPT in the database — no deletion
  • AttendancePunchProcessor orders all punches chronologically:
    Punch 1: 10:25 GEOFENCE_AUTO (IN)
    Punch 2: 10:30 ZKTeco (IN — treated as 2nd punch = OUT by position rule)

IMPORTANT: The 10:25 GEOFENCE_AUTO is treated as punch #1 (IN).
           The 10:30 ZKTeco is treated as punch #2 (OUT by position).

  To avoid this duplicate, the 120-second protection prevents the
  GEOFENCE_AUTO at 10:25 ONLY IF ZKTeco punched within ±120 seconds.
  Since 10:30 - 10:25 = 300 seconds > 120s, the auto-punch already fired.

BEST PRACTICE (future): If admin wants ZKTeco to be the primary IN,
  the regularization/correction flow allows manual override of the
  punch pairing result.
```

### 1.4 Realtime Admin Display

- After the GEOFENCE_AUTO punch is committed to SQL, `GeoLocationService` immediately calls `_firebaseAttendanceMutations.UpsertPunchAsync()` ([L870](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Services/GeoLocationService.cs#L870))
- Firebase pushes the event → Admin web page's realtime listener receives it → screen updates **without page refresh**

---

## 🔀 Rule Set 2: Feature Mode Hierarchy (Geofence + Dual + Auto)

**Doc ref:** [`989_ATTENDANCE_POLICY_REALTIME_PARITY.md`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Docs/989_ATTENDANCE_POLICY_REALTIME_PARITY.md)

```
Geo-Fencing = OFF
  → Dual Attendance: DISABLED
  → Auto-Punch: DISABLED
  → Manual Punch inside geofence: DISABLED (tracking not running)

Geo-Fencing = ON + Dual Attendance = ON
  → Physical biometric machine is ACTIVE
  → Employee must punch machine (ZKTeco handles check-in)

Geo-Fencing = ON + Auto-Punch = ON
  → GEOFENCE_AUTO punches fire automatically on enter/exit
  → No physical machine required

Geo-Fencing = ON + Dual = OFF + Auto = OFF
  → Employee can MANUALLY punch inside the app when inside radius
```

**Key rule:** Disabling Geofence immediately stops Android tracking service in real-time — no lag, no app restart required. Setting change flows:

```
Admin changes feature_settings → Firebase SSOT
  → Android realtime listener fires
  → EmployeeAttendanceStateMachine updates
  → TrackingService stops/starts immediately
```

---

## ⏰ Rule Set 3: Shift, Grace Periods & Lateness / Early Leave

**Code refs:** [`AttendanceCalculatorService.cs`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Shared/Services/Attendance/AttendanceCalculatorService.cs) · [`AttendanceScheduleService.cs`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Shared/Services/Attendance/AttendanceScheduleService.cs)

### 3.1 Time Precision Rule

> ⚠️ **All punch times are truncated to MINUTE precision. Seconds are ignored.**

```
10:25:45 AM → treated as 10:25:00 AM
10:30:59 AM → treated as 10:30:00 AM
```

**Code:** [`AttendancePunchProcessor.cs` L69–76](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Shared/Services/Attendance/AttendancePunchProcessor.cs#L69-L76)

**Timezone:** All calculations use `Asia/Kolkata`. No UTC conversion applied to punch times.

### 3.2 Grace Periods

**From `AttendanceScheduleService.cs` L70–71:**

```csharp
StartGraceEnd = ShiftStart + LateGraceMinutes   // e.g., 10:00 + 15min = 10:15
EndGraceStart = ShiftEnd   - EndTimeGraceMinutes // e.g., 18:00 - 10min = 17:50
```

| Setting | Config field | Effect |
|---|---|---|
| Late Grace | `settings.LateGraceMinutes` | Minutes after shift start that are still on-time |
| End Grace | `settings.EndTimeGraceMinutes` | Minutes before shift end — leaving early is NOT penalized |

### 3.3 Lateness Calculation

**From `AttendanceCalculatorService.cs` L1252–1264:**

```
IF FirstIn > StartGraceEnd (e.g., punch 10:20 > grace 10:15):
    Lateness = FirstIn - ShiftStart    ← measured from SCHEDULED START, not from grace end
             = 10:20 - 10:00 = 20 minutes late

NOT:
    Lateness = FirstIn - StartGraceEnd = 10:20 - 10:15 = 5 minutes (WRONG — not used)
```

**Example scenarios:**

| Shift Start | Grace | First IN | Lateness | Reason |
|---|---|---|---|---|
| 10:00 AM | 15 min | 10:10 AM | 0 min | 10:10 ≤ 10:15 grace |
| 10:00 AM | 15 min | 10:16 AM | 16 min | Measured from 10:00, not 10:15 |
| 10:00 AM | 0 min | 10:01 AM | 1 min | No grace applied |

### 3.4 Early Leave Calculation

**From `AttendanceCalculatorService.cs` L1279–1293:**

```
IF LastOut < ShiftEnd:
    EarlyLeave = ShiftEnd - LastOut    ← measured from SCHEDULED END (not grace boundary)

Example: Shift ends 18:00, Employee leaves at 17:30
    EarlyLeave = 18:00 - 17:30 = 30 minutes
```

---

## 🔢 Rule Set 4: Punch Pairing & Open Punch / Missing Punch

**Code ref:** [`AttendancePunchProcessor.cs`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Shared/Services/Attendance/AttendancePunchProcessor.cs)

### 4.1 Punch Pairing Rule (Pure positional, no IN/OUT label matters)

```
Punch 1 (chronologically 1st) → IN
Punch 2 → OUT
Punch 3 → IN
Punch 4 → OUT
...and so on
```

> The system does NOT rely on the punch type label. Position is authoritative.

### 4.2 Open Punch Rule (Today)

```
Odd punch count (1, 3, 5...) on TODAY's date:
  → Final punch is an OPEN IN
  → Worked time calculated to current India time (GetIndiaNow())
  → Status = "Present" (live, still working)
  → No fake OUT is inserted into the database
```

### 4.3 Missing Punch Rule (Historical)

```
Odd punch count on YESTERDAY or older date:
  → Status = "Missing Punch"
  → Admin must correct via punch correction / regularization flow
  → No automatic calculation runs on historical missing punches
```

### 4.4 Decision matrix

| Date | Punch Count | Status | Worked Time |
|---|---|---|---|
| Today | 0 | Absent | 0 |
| Today | 1 | Present (open) | FirstIn → Now |
| Today | 2 | Present | FirstIn → LastOut |
| Today | 3 | Present (open) | Pairs covered + open |
| Yesterday | 0 | Absent | 0 |
| Yesterday | 1 | Missing Punch | — |
| Yesterday | 2 | Present | Full worked time |

---

## 🌙 Rule Set 5: Cross-Midnight / Overnight Shift Rules

**Doc ref:** [`1200-K_MIDNIGHT_CROSS_DAY_SHIFT_HANDLING.md`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Docs/1200-K_MIDNIGHT_CROSS_DAY_SHIFT_HANDLING.md)

**Code ref:** `AttendanceBoundsService.cs`

### 5.1 Detection Rule

```
If ShiftEnd ≤ ShiftStart → OVERNIGHT SHIFT
  ShiftEnd += 1 day

Example: 22:00 → 06:00
  Detected as overnight: EndTime (06:00) < StartTime (22:00)
  Effective: 2026-09-17 22:00 → 2026-09-18 06:00
```

### 5.2 Ownership Rule

```
ALL punches from ShiftDate 22:00 through next day 06:00 belong to:
  → ShiftDate = 2026-09-17 (the original shift start date)
```

### 5.3 Concrete examples from docs

| Scenario | Punches | Result |
|---|---|---|
| Normal overnight | `22:00 IN → 02:00 OUT → 02:30 IN → 06:00 OUT` | All 4 assigned to 2026-09-17 |
| Post-shift OT | `22:00 IN → 06:30 OUT` | 06:30 retained; 30 min counted as OT |
| Next-day independent session | `22:00 IN → 06:00 OUT` then `09:00 IN → 18:00 OUT` | 09:00/18:00 = SEPARATE new day |
| Open overnight punch | `22:00 IN`, current time `02:30` next day | Status = Present (open), measured to 02:30 |

### 5.4 WorkDayCutoffHour

The `WorkDayCutoffHour` setting does **NOT** truncate an explicitly scheduled overnight shift. Only unscheduled / no-shift scenarios use the cutoff.

---

## ☕ Rule Set 6: Break Time & Penalty Calculation

**Code ref:** [`AttendanceBreakPenaltyService.cs`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Shared/Services/Attendance/AttendanceBreakPenaltyService.cs)

### 6.1 How Break Time is Calculated

```
Punches: IN(09:00) → OUT(13:00) → IN(14:00) → OUT(18:00)

Segment 1: 09:00–13:00 (working)
Gap:              13:00–14:00 = 60 minutes (break)
Segment 2: 14:00–18:00 (working)

TotalBreak = 60 minutes
```

### 6.2 Break Penalty Formula

```csharp
// AttendanceBreakPenaltyService.cs L46-48
var allowance = TimeSpan.FromMinutes(paidBreakMinutes);  // from emp.StandardBreakMinutes
var penalty = totalGaps - allowance;
if (penalty < TimeSpan.Zero) penalty = TimeSpan.Zero;
```

**Example:**
- Employee `StandardBreakMinutes = 30`
- Actual break taken = 60 min
- `Penalty = max(0, 60 - 30) = 30 minutes` deducted

### 6.3 Single-pair rule

```
If only one IN/OUT pair exists (no break at all):
  TotalBreak = 0
  Penalty = 0
```

---

## 📊 Rule Set 7: Worked Hours & Overtime Calculation

**Code ref:** [`AttendanceCalculatorService.cs` L1316–1350](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Shared/Services/Attendance/AttendanceCalculatorService.cs#L1316-L1350)

### 7.1 Regular Worked (Inside Shift Only)

```
Regular Worked = time overlapping scheduled shift window only

Shift = 10:00–18:00
Punch IN = 09:30, OUT = 19:00

Regular Worked = 10:00–18:00 = 8 hours  ← NOT 09:30–19:00
Pre-shift (09:30–10:00) → NOT regular work
Post-shift (18:00–19:00) → counts as OT
```

> ⚠️ Break/gap time is NOT subtracted from Worked. It is tracked separately and passed independently to `DailySummaryBuilder`.

### 7.2 Overtime Rules

| Type | Trigger | How Counted |
|---|---|---|
| Pre-shift OT | Punch IN before shift start | Time from IN to ShiftStart |
| Post-shift OT | Punch OUT after shift end | Time from ShiftEnd to OUT |
| Weekly Off OT | Worked on weekly off day | All worked hours = OT |
| Holiday OT | Worked on company holiday | All worked hours = OT |

### 7.3 Weekly Off / Holiday Status

```
Weekly Off day + no punches:
  Status = "Weekly Off"
  Worked = 0, OT = 0

Weekly Off day + has punches:
  Status = "Present (Weekly Off)" or similar
  All worked hours → OT
```

---

## 🔒 Rule Set 8: Conflict Protection & Idempotency

**Doc ref:** [`1200-F_ATTENDANCE_CONFLICT_DUPLICATE_PROCESSING.md`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Docs/1200-F_ATTENDANCE_CONFLICT_DUPLICATE_PROCESSING.md)

**Code ref:** [`AttendanceProcessingCoordinator.cs`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Services/AttendanceProcessingCoordinator.cs)

### 8.1 Per Employee/Date Lock

```
Lock key = (EmployeeID, BusinessDate)
Only ONE recalculation for that employee+date runs at a time
Concurrent requests queue and re-check after lock is released
```

### 8.2 Punch Fingerprint

```
Fingerprint = Hash of:
  PunchID + Time + Type + BiometricID + DeviceID + ApprovalState + Latitude + Longitude

If fingerprint unchanged → recalculation SKIPPED
If any field changes  → recalculation RUNS
```

### 8.3 Fingerprint commit rule

```
Fingerprint is ONLY committed after SaveChangesAsync() succeeds
If EF save fails → fingerprint not stored → retry allowed
```

### 8.4 Conflict behavior table

| Situation | Result |
|---|---|
| Same punch fingerprint | Skip — already processed |
| Concurrent callbacks for same day | One runs, other re-checks and skips |
| New punch added | Fingerprint changes → recalculate |
| Punch approval/location/type changes | Fingerprint changes → recalculate |
| App restart | Seeds from existing SQL data |
| Old entries (>24h idle) | Evicted from coordinator memory |

---

## 🛡️ Rule Set 9: Manual Override Integrity

**Doc ref:** [`1200-G_ATTENDANCE_MANUAL_OVERRIDE_REPROCESSING_INTEGRITY.md`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Docs/1200-G_ATTENDANCE_MANUAL_OVERRIDE_REPROCESSING_INTEGRITY.md)

### 9.1 The Golden Rule

```
IF DailySummary.IsManualOverride == true:
  → Automatic recalculation will NEVER overwrite the admin-adjusted values
  → Applies to: Firebase-triggered recalculation, SignalR updates, Mass Re-Process
```

### 9.2 How to clear a manual override

```
Admin explicitly clears the override flag
→ IsManualOverride = false stored in DB
→ Processing fingerprint includes override state, so it now differs
→ Next automatic recalculation runs normally
```

### 9.3 Mass Re-Process behavior

```
Admin triggers Mass Re-Process for date range:
  → For each employee/date in range:
      IF IsManualOverride == true → SKIP (count as "skipped")
      ELSE → recalculate and save
  → Locks are held until save succeeds (no interleave)
```

### 9.4 Leave approval & override interaction

```
Leave is approved for employee on a date:
  IF that date has IsManualOverride == true → SKIP the auto-update
  IF no override → update DailySummary to reflect approved leave
```

---

## 📝 Rule Set 10: Punch Correction & Regularization

**Doc refs:** [`1200-H`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Docs/1200-H_ATTENDANCE_PUNCH_CORRECTION_RECONCILIATION.md) · [`1004_REGULARIZATION_FULL_PARITY.md`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Docs/1004_REGULARIZATION_FULL_PARITY.md)

### 10.1 Regularization Request Lifecycle

```
1. Employee creates request
   → Firebase attendance_punches: status="PENDING", isApproved=false
   → SQL compatibility projection created

2. Admin sees request in real-time (Firebase listener)

3. Admin approves:
   → AttendanceLog corrected in SQL (existing punch correction engine)
   → AttendanceCalculatorService recalculates the day
   → DailySummary republished to Firebase
   → status="APPROVED", isApproved=true

4. Admin rejects:
   → Record deleted from Firebase attendance_punches projection
```

### 10.2 Employee permissions on regularization

```
Employees CAN:    Create a new PENDING request for themselves
Employees CANNOT: Update an existing request
Employees CANNOT: Change approval state
Employees CANNOT: See other employees' requests
```

### 10.3 Punch correction priority

```
Admin Manual Correction > Firebase auto-recalculation
  → After successful manual correction, old fingerprint is INVALIDATED
  → Next auto-pass sees the updated DailySummary + override state
```

### 10.4 Firebase projection failure rule

```
If Firebase projection write fails after SQL commit:
  → SQL mutation is NOT rolled back (data is safe in SQL)
  → Firebase projection failure is LOGGED for manual reconciliation
  → Up to 3 short retries attempted
```

---

## 📅 Rule Set 11: Leave, Holiday & Closed Day Rules

**Doc refs:** [`1200-I`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Docs/1200-I_ATTENDANCE_LEAVE_HOLIDAY_CLOSED_DAY_INTEGRATION.md) · [`999_HOLIDAY_CLOSED_DAY_PARITY.md`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Docs/999_HOLIDAY_CLOSED_DAY_PARITY.md)

### 11.1 Leave approval flow

```
Leave approved → Immediate DailySummary refresh for affected date
Leave revoked  → DailySummary refreshed again
Manual override on leave date → SKIPPED (override preserved)
```

### 11.2 Company Holiday rules

```
Holiday created → ALL affected employees' DailySummary recalculated
  (unless individual employee has IsManualOverride = true)

If empty affectedEmployeeIds → affects ALL staff
If specific IDs listed → only those employees affected
```

### 11.3 Closed Day punch blocking

```
Employee manual punch on a closed day: BLOCKED
Automatic geofence punch on a closed day: BLOCKED
TrackingWindowResolver treats closed day as a non-tracking window
  → TrackingService stops immediately when closed day is detected
```

### 11.4 Payroll formula for Holiday/Closed day

```
Holiday with no work: Holiday pay (per payroll formula — unchanged)
Holiday with work detected: OT pay for worked hours
SalaryEngine receives closedDays list — no formula changed
```

---

## 📆 Rule Set 12: Shift Schedule Integration

**Doc refs:** [`1200-J`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Docs/1200-J_SHIFT_SCHEDULE_INTEGRATION.md) · [`1001_SHIFT_MANAGEMENT_FULL_PARITY.md`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Docs/1001_SHIFT_MANAGEMENT_FULL_PARITY.md)

### 12.1 Shift creation triggers attendance recalculation

```
Admin creates new shift schedule for employee on date X:
  → Firebase shift_schedules updated
  → ShiftScheduleAttendanceImpactService fires immediately
  → AttendanceCalculatorService recalculates affected DailySummary
  → Manual overrides preserved
```

### 12.2 Role-based shift data scope

```
Admin/SuperAdmin: Receives full shift_schedules stream
Employee: Receives ONLY their own shifts (Firebase query: orderBy=employeeId, equalTo=their ID)
```

### 12.3 Pattern resolution priority (tracking window)

```
For same date:
  Concrete dated shift > Recurring pattern shift (pattern is fallback)
  Newest recurring pattern wins if multiple exist for same weekday
```

### 12.4 Shift tracking modes (Android)

| Mode | Behavior |
|---|---|
| `24/7` | Tracking always on (default) |
| `SHIFT` | Tracking ONLY during assigned shift window |
| `CUSTOM` | Tracking only during custom start/end stored in tracking_prefs |

Outside tracking window → GPS session ends with `OUTSIDE_TRACKING_WINDOW`, no attendance generated.

---

## 📡 Rule Set 13: Background GPS Tracking Lifecycle

**Doc refs:** [`993_TRACKING_SHIFT_WORKING_HOURS_PARITY.md`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Docs/993_TRACKING_SHIFT_WORKING_HOURS_PARITY.md) · [`1200-M_BACKGROUND_TRACKING_TO_ATTENDANCE.md`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Docs/1200-M_BACKGROUND_TRACKING_TO_ATTENDANCE.md)

### 13.1 Durable GPS queue

```
Android background GPS fix
  → Room durable queue (persists even if app kills)
  → Foreground uploader (low-latency, first choice)
  → WorkManager fallback (if app backgrounded or killed)
  → Firebase tracking/history + tracking/live
  → Server GeoLocationService.UpdateGpsSessionAsync
  → Attendance auto-punch if geofence configured
```

### 13.2 Offline logout protection

```
User logs out while offline:
  → SESSION_ENDED event queued in Room immediately
  → When connectivity returns: WorkManager syncs SESSION_ENDED FIRST
  → Server closes GPS session
  → Queued GPS points after SESSION_ENDED are rejected (can't resurrect ended session)
```

### 13.3 Session start/end idempotency

```
SESSION_STARTED / SESSION_ENDED replayed after reconnect:
  → Both operations are idempotent (safe to replay)
  → Duplicate start/end handled safely by GeoLocationService
```

### 13.4 Initial OUT suppressed

```
Tracking starts when employee is OUTSIDE geofence:
  → previousLocationState is null, currentLocationState = false
  → NO OUT punch generated for this initial state
  → System waits for first genuine ENTER before any punch
```

---

## 📱 Rule Set 14: Single-Device Session Enforcement

**Doc refs:** [`1100-R_EMPLOYEE_AUTH_SESSION_DEVICE_LIFECYCLE_FINAL_VERIFICATION.md`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Docs/1100-R_EMPLOYEE_AUTH_SESSION_DEVICE_LIFECYCLE_FINAL_VERIFICATION.md) · [`982_FIREBASE_AUTH_ROLE_SESSION_SECURITY.md`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Docs/982_FIREBASE_AUTH_ROLE_SESSION_SECURITY.md)

### 14.1 Single-device rule

```
Session record: employee_sessions/{firebaseUid}
  → Contains: deviceId, timestamp

Employee logs in on Device A: session created with deviceId=A
Employee tries to log in on Device B:
  → Only allowed if "Replace & Login" is explicitly selected
  → After replacement: Device A receives force-logout notification:
    "This account is now active on another device."
```

### 14.2 Cold-start validation

```
On every Android app cold start:
  FirebaseAuthSecurityGate checks:
  1. Firebase ID token validity + revocation
  2. Role claim matches (SUPER_ADMIN / ADMIN / STAFF)
  3. owner_uid tenant claim matches
  4. employee_id claim matches DB record
  5. employee_sessions/{uid}.deviceId == current device

If any check fails → local session cleared → back to Login screen
```

### 14.3 Auth revocation

```
Admin deactivates employee:
  → Firebase refresh tokens revoked
  → employee_sessions record removed
  → Employee's GPS cleanup performed
  → Next app launch fails validation → forced to Login
```

### 14.4 Security hardening

```
HTTP logging (Retrofit):
  Debug builds: BASIC level only
  Release builds: DISABLED entirely (no token leaks)

Firebase rules:
  user_profiles: user can only read/write OWN profile
  Employees CANNOT promote themselves to Admin
  Employees CANNOT change tenant (ownerUid)
```

---

## 💰 Rule Set 15: Payroll Lock & Finalization

**Doc ref:** [`1005_PAYROLL_FINALIZATION_PARITY.md`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Docs/1005_PAYROLL_FINALIZATION_PARITY.md)

### 15.1 Payroll is locked after finalization

```
After payroll is finalized for month YYYY-MM:
  → Attendance recalculation CANNOT overwrite payroll data
  → Manual override guard applies
  → DailySummary payroll-lock flag is respected by all calculation paths
```

### 15.2 SHA-256 verification

```
Before finalization:
  Hash = SHA-256 of exact payroll preview values

After SQL commit:
  Hash again from stored PayrollHistory rows

IF hashes match: verificationStatus = "MATCH"
IF mismatch: logged + recorded in Firebase, payroll values NOT changed silently
```

### 15.3 Firebase payroll paths

```
payroll_previews/{YYYY-MM}     → Admin preview metadata + hash
payroll_history/{payrollId}    → Finalized rows (employee reads only own record)
payroll_finalization/{YYYY-MM} → Lock state, employee count, net salary, verification
```

### 15.4 Firebase publication is best-effort

```
Payroll finalization:
  1. SQL transaction commits (authoritative)
  2. Firebase publication attempted
  IF Firebase write fails → SQL data still safe
  Firebase write CANNOT roll back committed payroll
```

---

## 🌐 Rule Set 16: Firebase Authority & Anti-Spoofing

**Doc ref:** [`1200-L_GEOFENCE_GPS_ATTENDANCE_INTEGRATION.md`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Docs/1200-L_GEOFENCE_GPS_ATTENDANCE_INTEGRATION.md)

### 16.1 Server is the attendance authority — ALWAYS

```
Android captures GPS coordinates → sends to Firebase
Server RECALCULATES distance and radius from Admin-configured office coordinates

Android sends: { lat, lng, distance, radius, within }
Server IGNORES: distance, radius, within  ← client values not trusted
Server USES: its own Haversine distance calculation
```

### 16.2 Coordinate validation

```
Invalid coordinates (null, NaN, 0,0, out-of-range) → REJECTED before any processing
No attendance punch generated for invalid GPS
```

### 16.3 LiveLocationStore deduplication

```
Firebase live GPS event arrives:
  → UpdateGpsSessionAsync runs (single path)
  → LiveLocationStore updated INSIDE that path (not written twice)
  → Prevents double-update/race on the admin map
```

### 16.4 Firebase as transport, not authority

```
Firebase Realtime Database = transport layer + read-model SSOT
SQL/EF = calculation write authority
Web GeoLocationService = attendance decision authority
Android = GPS capture + Firebase transport only (does NOT calculate attendance)
```

---

## 🗺️ Architecture Summary

```
┌─────────────────────────────────────────────────────────┐
│                     ANDROID APP                          │
│  Employee GPS Capture → Room Queue → Firebase Transport  │
│  Geofence detection: display only (NOT attendance calc)  │
│  Single-device enforcement via employee_sessions         │
└─────────────────────┬───────────────────────────────────┘
                      │ Firebase RTDB (SSOT transport)
┌─────────────────────▼───────────────────────────────────┐
│                     WEB SERVER                           │
│  GeoLocationService  → attendance authority              │
│  AttendanceCalculatorService → formula authority          │
│  AttendanceProcessingCoordinator → concurrency guard      │
│  SQL/EF → write model (attendance, payroll, employees)   │
│  Firebase → read model projection after SQL commit       │
└─────────────────────┬───────────────────────────────────┘
                      │ Firebase realtime listeners
┌─────────────────────▼───────────────────────────────────┐
│              ADMIN WEB BROWSER / ANDROID ADMIN           │
│  Sees changes in real-time without page refresh          │
│  Cannot modify attendance formulas or DB schema          │
└─────────────────────────────────────────────────────────┘
```

---

## 📁 Key Files Quick Reference

| File | Purpose |
|---|---|
| [`GeoLocationService.cs`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Services/GeoLocationService.cs) | Geofence auto-punch, authoritative punch detection, 120s window |
| [`AttendanceCalculatorService.cs`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Shared/Services/Attendance/AttendanceCalculatorService.cs) | Lateness, OT, worked hours, grace, India timezone |
| [`AttendancePunchProcessor.cs`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Shared/Services/Attendance/AttendancePunchProcessor.cs) | Punch pairing (1=IN, 2=OUT...), minute precision |
| [`AttendanceBreakPenaltyService.cs`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Shared/Services/Attendance/AttendanceBreakPenaltyService.cs) | Break time gaps, paid break deduction |
| [`AttendanceScheduleService.cs`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Shared/Services/Attendance/AttendanceScheduleService.cs) | Grace windows (StartGraceEnd, EndGraceStart) |
| [`AttendanceProcessingCoordinator.cs`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Services/AttendanceProcessingCoordinator.cs) | Per employee/date lock, fingerprint idempotency |
| [`AttendanceBoundsService.cs`](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Shared/Services/Attendance/AttendanceBoundsService.cs) | Overnight shift detection, cross-day punch assignment |
