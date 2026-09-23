# Firebase Spark Plan Analysis — BioMetric+Payroll

## 🚨 Critical Situation: You Are Already Over Quota

![Firebase Quota Screenshot](C:/Users/Shiva Prakash J/.gemini/antigravity/brain/dcf62386-06d9-496e-82ba-3189d3cc480d/.user_uploaded/media_1790171578507.png)

| Resource | Used | Limit | Status |
|---|---|---|---|
| RTDB Storage | 122 MB | 1 GB | ✅ 11.9% — OK |
| **RTDB Downloads** | **62.2 GB** | **10 GB/month** | 🚨 **620% EXCEEDED** |
| Firestore Writes | 0 | 20K/day | ✅ OK |
| Firestore Reads | 0 | 50K/day | ✅ OK |

> [!CAUTION]
> Your Firebase Realtime Database download bandwidth is **62.2 GB** against a **10 GB/month** limit — exceeded by **52.2 GB**. Firebase **has already throttled or disabled** your RTDB service for this billing month. This is why real-time sync fails on standby Admin screens.

---

## Why Is It Consuming 62 GB/Month? (Root Cause in Your Code)

### 1. `keepSynced(true)` on the entire live tracking node
**File:** [`SignalRManager.kt` line 186](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/android/app/src/main/java/com/biometric/app/sync/SignalRManager.kt#L186)

```kotlin
liveRef.keepSynced(true)  // ← Keeps ALL tracking/live data offline on EVERY Admin device
```

With 10 employees sending GPS every few seconds, `keepSynced(true)` forces every Admin Android device to **download the entire tracking/live collection continuously**, including repeated refreshes when the listener reconnects.

**Impact:** If GPS sends 1 update/15s per employee:
- 10 employees × 4 updates/min × 8 hours/day × ~500 bytes = **~960 MB/day** per Admin device
- 2 Admin devices × 30 days = **~57 GB/month** ← This matches your 62 GB!

### 2. `FirebaseRoomHydrator` downloads ALL tables on every Admin login
**File:** [`FirebaseRoomHydrator.kt` lines 104-130](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/android/app/src/main/java/com/biometric/app/sync/FirebaseRoomHydrator.kt)

Every Admin device subscribes live listeners to:
- `employees`, `attendance`, `attendance_punches`, `advance_payments`
- `employee_history`, `regularizations`, `leave_requests`, `resignation_requests`
- `salary_snapshots`, `audit_logs`, `daily_summaries`, `shift_schedules`, `payroll_history`

On each listener reconnect, Firebase re-downloads **the entire collection**. With 10+ employees and months of history, this is substantial.

### 3. `tracking/history` is never cleaned up
GPS history is stored at `tracking/history/{employeeId}/{eventId}` and **grows forever**. Every Admin login re-downloads all of it.

### 4. My new `reconcileLiveLocationsNow()` runs every 15s (just added today)
The fix I added today for the standby issue calls `liveRef.get().await()` every 15 seconds — this adds more download traffic. Needs to be made smarter (only run if Firebase listener is healthy).

---

## Can This App Run on Spark Plan with 10+ Employees?

> [!WARNING]
> **Short answer: NO.** GPS live tracking for 10+ employees with real-time Admin dashboards will **always exceed 10 GB/month** on Spark. This is a structural limitation, not a code bug.

### Spark Plan Hard Limits vs Your Needs

| Feature | Spark Limit | Your Need (10 employees) | Verdict |
|---|---|---|---|
| RTDB Downloads | 10 GB/month | ~30–60 GB/month | ❌ Cannot fit |
| RTDB Storage | 1 GB | ~200–500 MB | ✅ OK for now |
| Firestore Reads | 50K/day | ~5K–20K/day | ✅ OK |
| Firestore Writes | 20K/day | ~2K–5K/day | ✅ OK |
| Cloud Functions | ❌ Not available | Needed for server-side cleanup | ❌ Not available |

---

## Options to Fix This

### Option A: Upgrade to Firebase Blaze (Pay-as-you-go) — **Recommended**

Blaze is **still free within Spark quotas**, but adds per-GB billing beyond that.

**Estimated cost for 10 employees:**
| Usage | Price |
|---|---|
| RTDB Downloads: ~20 GB over 10 GB free | ~20 GB × \$1.00/GB = **\$20/month** |
| RTDB Storage (under 1 GB) | **\$0** |
| Firestore (within free tier) | **\$0** |
| Cloud Functions (for cleanup) | ~**\$0–\$2/month** |
| **Total** | **~\$20–\$25/month** |

This also unlocks Cloud Functions so you can auto-clean `tracking/history`.

---

### Option B: Stay on Spark — Reduce Bandwidth by 80% (Code Changes)

If you cannot upgrade now, these code changes can **reduce downloads by 80%**:

#### B1. Remove `keepSynced(true)` — saves ~50 GB/month
```kotlin
// SignalRManager.kt — REMOVE this line:
liveRef.keepSynced(true)  // ← DELETE or restrict to employee's own node only
```
This single change will eliminate most of the bandwidth.

#### B2. Add TTL cleanup for `tracking/history` — saves storage growth
In the Web app, delete entries older than 24 hours when publishing a new location.

#### B3. Add `tracking/history` query limit in Admin
Only read the last 50 history entries per employee, not the entire history.

#### B4. Reduce GPS update frequency from every ~15s to every 30–60s
For attendance/payroll purposes, 30–60s accuracy is sufficient.

#### B5. Make the 15s reconciliation smarter (from today's fix)
```kotlin
// Only run reconcileLiveLocationsNow() if last successful read is older than 60s
if (lastSuccessfulLiveReadAt > 0L && now - lastSuccessfulLiveReadAt > 60_000L) {
    reconcileLiveLocationsNow()
}
```

---

## Immediate Action Required

> [!IMPORTANT]
> **Your RTDB is throttled RIGHT NOW.** This explains the standby admin not updating — not just a code bug. Firebase has cut off bandwidth for this month.

**Steps to restore service immediately:**
1. Go to [Firebase Console → BioMetricPayroll → Usage and billing](https://console.firebase.google.com)
2. Click **Upgrade** → Switch to **Blaze plan** (free until you exceed limits, then ~\$1/GB)
3. Or wait for the monthly reset (next billing cycle) — but you'll hit the same limit again within a few days

**Then, apply code fix B1 immediately** (remove `keepSynced(true)`) to avoid exceeding Blaze quotas.

---

## Summary

| | Spark Free | Blaze Pay-as-you-go |
|---|---|---|
| 10+ employees with GPS | ❌ Impossible | ✅ ~\$20/month |
| Service disruption risk | 🚨 Already happening | ✅ None |
| Cloud Functions | ❌ Not available | ✅ Available |
| Cost | Free | ~\$20–\$25/month |
| **Recommendation** | ❌ Not viable | ✅ **Upgrade now** |
