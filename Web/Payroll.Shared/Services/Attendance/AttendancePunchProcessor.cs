using System;
using System.Collections.Generic;
using System.Linq;
using Payroll.Shared;
using Payroll.Shared.Data;

namespace Payroll.Shared.Services
{
    public enum PunchSourceTier
    {
        PhysicalMachine = 1,
        ManualAdmin = 2,
        GeofenceAuto = 3
    }

    /// <summary>
    /// Normalizes, deduplicates, and resolves attendance punches using a 3-tier priority hierarchy:
    /// 1. Physical Biometric Machine (ZKTeco, hardware readers) -> Authoritative Hardware
    /// 2. Manual Admin Overrides & Approved Corrections -> Authoritative Override
    /// 3. Geofence Auto Punches (Server & Android) -> Dynamic Fallback Only
    ///
    /// Hybrid Rules:
    /// - Non-shift windows (pre-shift and post-shift OT): all valid sessions (machine, manual, or geofence fallback) are preserved.
    /// - Shift window: geofence auto punches are suppressed to prevent GPS drift from fragmenting the shift.
    ///   However, physical biometric machine punches inside the shift OVERRIDE manual/continuous coverage to record breaks.
    /// </summary>
    public sealed class AttendancePunchProcessor
    {
        public static PunchSourceTier GetPunchTier(AttendanceLog log)
        {
            if (log == null) return PunchSourceTier.GeofenceAuto;

            var device = log.DeviceID?.Trim() ?? string.Empty;
            var bioId = log.BiometricID?.Trim() ?? string.Empty;
            var logType = log.LogType?.Trim() ?? string.Empty;

            // Tier 2: Manual Admin Override or Approved Correction
            if (device.Equals("ManualCorrection", StringComparison.OrdinalIgnoreCase) ||
                device.Equals("Admin", StringComparison.OrdinalIgnoreCase) ||
                bioId.StartsWith("MANUAL_", StringComparison.OrdinalIgnoreCase) ||
                logType.Equals("Manual Correction", StringComparison.OrdinalIgnoreCase))
            {
                return PunchSourceTier.ManualAdmin;
            }

            // Tier 3: Geofence Auto (Server GeofenceAuto, AndroidGeofenceAuto, AUTO_* keys)
            if (device.Equals("GeofenceAuto", StringComparison.OrdinalIgnoreCase) ||
                device.Equals("AndroidGeofenceAuto", StringComparison.OrdinalIgnoreCase) ||
                device.Contains("Geofence", StringComparison.OrdinalIgnoreCase) ||
                bioId.Equals("GEOFENCE_AUTO", StringComparison.OrdinalIgnoreCase) ||
                bioId.StartsWith("AUTO_", StringComparison.OrdinalIgnoreCase) ||
                logType.StartsWith("AUTO_", StringComparison.OrdinalIgnoreCase))
            {
                return PunchSourceTier.GeofenceAuto;
            }

            // Tier 1: Physical Biometric Machine
            if (device.StartsWith("ZKTeco", StringComparison.OrdinalIgnoreCase) ||
                device.StartsWith("Machine", StringComparison.OrdinalIgnoreCase) ||
                int.TryParse(device, out _) ||
                (!string.IsNullOrEmpty(device) && !device.Equals("MobileWeb", StringComparison.OrdinalIgnoreCase) && !device.Equals("Android", StringComparison.OrdinalIgnoreCase)))
            {
                return PunchSourceTier.PhysicalMachine;
            }

            // Explicit human button punch on mobile or web app
            return PunchSourceTier.ManualAdmin;
        }

        public static bool IsExplicitOutPunch(AttendanceLog p)
        {
            if (p == null) return false;
            var t = (p.LogType ?? string.Empty).Trim().ToUpperInvariant();
            return t.Contains("OUT") || t.Equals("CHECKOUT") || t.Equals("CHECK_OUT") || t.StartsWith("AUTO_OUT");
        }

        public static bool IsExplicitInPunch(AttendanceLog p)
        {
            if (p == null) return false;
            var t = (p.LogType ?? string.Empty).Trim().ToUpperInvariant();
            return t.Equals("IN") || t.Equals("CHECKIN") || t.Equals("CHECK_IN") || t.StartsWith("AUTO_IN");
        }

        public (
            List<AttendanceLog> Ordered,
            DateTime? FirstIn,
            DateTime? LastOut)
            ProcessPunches(
                List<AttendanceLog> punches,
                DateTime day,
                DateTime? shiftStart = null,
                DateTime? shiftEnd = null,
                FeatureSettings? featureSettings = null)
        {
            if (punches == null || punches.Count == 0)
            {
                return (
                    new List<AttendanceLog>(),
                    null,
                    null);
            }

            // 1. Check feature toggle: If geofencing or auto-punching is disabled, exclude Tier 3 geofence punches
            bool allowGeofence = featureSettings == null || (featureSettings.EnableGeoFencing && featureSettings.EnableAutomaticGeofencePunching);

            // 2. Normalize attendance timestamps to MINUTE precision.
            var normalized = punches
                .Where(p => p != null)
                .Where(p => allowGeofence || GetPunchTier(p) != PunchSourceTier.GeofenceAuto)
                .Select(p =>
                {
                    var value = p.PunchTime;
                    p.PunchTime = new DateTime(
                        value.Year,
                        value.Month,
                        value.Day,
                        value.Hour,
                        value.Minute,
                        0,
                        DateTimeKind.Unspecified);
                    return p;
                })
                .OrderBy(p => p.PunchTime)
                .ThenBy(p => GetPunchTier(p))
                .ThenBy(p => p.LogID)
                .ToList();

            if (normalized.Count == 0)
            {
                return (
                    new List<AttendanceLog>(),
                    null,
                    null);
            }

            // 3. Deduplicate punches by BiometricID and same-minute collisions.
            var deduplicated = new List<AttendanceLog>();
            foreach (var p in normalized)
            {
                bool isDup = false;
                if (!string.IsNullOrWhiteSpace(p.BiometricID))
                {
                    if (deduplicated.Any(x => x.EmployeeID == p.EmployeeID &&
                                              string.Equals(x.BiometricID, p.BiometricID, StringComparison.OrdinalIgnoreCase)))
                    {
                        isDup = true;
                    }
                }

                if (!isDup)
                {
                    var match = deduplicated.FirstOrDefault(x =>
                        x.EmployeeID == p.EmployeeID &&
                        x.PunchTime == p.PunchTime);

                    if (match != null)
                    {
                        // Same minute collision: higher priority tier wins
                        var tierMatch = GetPunchTier(match);
                        var tierIncoming = GetPunchTier(p);
                        if (tierIncoming < tierMatch)
                        {
                            deduplicated[deduplicated.IndexOf(match)] = p;
                        }
                        isDup = true;
                    }
                }

                if (!isDup)
                {
                    deduplicated.Add(p);
                }
            }

            if (deduplicated.Count == 0)
            {
                return (new List<AttendanceLog>(), null, null);
            }

            // 4. Resolve Active Shift Window [windowStart, windowEnd]
            DateTime? windowStart = shiftStart;
            DateTime? windowEnd = shiftEnd;

            // Also check if manual punches define or expand the shift window boundaries
            var manualIn = deduplicated.FirstOrDefault(p => GetPunchTier(p) == PunchSourceTier.ManualAdmin && IsExplicitInPunch(p));
            var manualOut = deduplicated.LastOrDefault(p => GetPunchTier(p) == PunchSourceTier.ManualAdmin && IsExplicitOutPunch(p));

            if (manualIn != null)
            {
                if (!windowStart.HasValue || manualIn.PunchTime < windowStart.Value)
                    windowStart = manualIn.PunchTime;
            }
            if (manualOut != null)
            {
                if (!windowEnd.HasValue || manualOut.PunchTime > windowEnd.Value)
                    windowEnd = manualOut.PunchTime;
            }

            // 5. Shift Zone & Manual Override Filtering:
            // A. Manual Admin Override:
            // When an administrative manual correction pair [manualIn, manualOut] exists,
            // it is an explicit authoritative override of that employee's shift.
            // All intermediate punches (e.g. erratic biometric scans, partial breaks, or GPS drift)
            // strictly inside (manualIn.PunchTime, manualOut.PunchTime) are suppressed and invalidated.
            var suppressedIds = new HashSet<int>();
            var suppressedBioIds = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

            var manualLogs = deduplicated.Where(p => GetPunchTier(p) == PunchSourceTier.ManualAdmin).ToList();
            var manualIns = manualLogs.Where(IsExplicitInPunch).OrderBy(p => p.PunchTime).ToList();
            var manualOuts = manualLogs.Where(IsExplicitOutPunch).OrderBy(p => p.PunchTime).ToList();

            if (manualIns.Count > 0 && manualOuts.Count > 0)
            {
                var mStart = manualIns.First().PunchTime;
                var mEnd = manualOuts.Last().PunchTime;

                if (mEnd > mStart)
                {
                    foreach (var p in deduplicated)
                    {
                        if (GetPunchTier(p) != PunchSourceTier.ManualAdmin && p.PunchTime > mStart && p.PunchTime < mEnd)
                        {
                            if (p.LogID > 0) suppressedIds.Add(p.LogID);
                            if (!string.IsNullOrEmpty(p.BiometricID)) suppressedBioIds.Add(p.BiometricID);
                        }
                    }
                }
            }

            // B. Authoritative Session (Tier 1 Machine or Tier 2 Manual):
            // When an authoritative session covers the shift, intermediate GeofenceAuto punches
            // inside [authIn, authOut] are suppressed to prevent GPS drift from fragmenting working hours.
            // If NO authoritative OUT exists, a geofence OUT punch serves as the dynamic fallback check-out / early departure.
            var authIn = deduplicated.FirstOrDefault(p => GetPunchTier(p) <= PunchSourceTier.ManualAdmin && IsExplicitInPunch(p));
            var authOut = deduplicated.LastOrDefault(p => GetPunchTier(p) <= PunchSourceTier.ManualAdmin && IsExplicitOutPunch(p));

            if (authIn != null && authOut != null && authOut.PunchTime > authIn.PunchTime)
            {
                var wStart = authIn.PunchTime;
                var wEnd = authOut.PunchTime;

                foreach (var p in deduplicated)
                {
                    if (GetPunchTier(p) == PunchSourceTier.GeofenceAuto)
                    {
                        // Strictly inside the covered authoritative session: suppress geofence auto punches
                        if (p.PunchTime > wStart && p.PunchTime < wEnd)
                        {
                            if (p.LogID > 0) suppressedIds.Add(p.LogID);
                            if (!string.IsNullOrEmpty(p.BiometricID)) suppressedBioIds.Add(p.BiometricID);
                        }
                    }
                }
            }

            var survivingPunches = deduplicated
                .Where(p => !suppressedIds.Contains(p.LogID) &&
                            (string.IsNullOrEmpty(p.BiometricID) || !suppressedBioIds.Contains(p.BiometricID)))
                .OrderBy(p => p.PunchTime)
                .ThenBy(p => GetPunchTier(p))
                .ThenBy(p => p.LogID)
                .ToList();

            if (survivingPunches.Count == 0)
            {
                survivingPunches = deduplicated;
            }

            // 6. Dynamic State Machine & Priority Pairing across all zones
            var ordered = new List<AttendanceLog>();
            AttendanceLog? pendingIn = null;

            foreach (var p in survivingPunches)
            {
                bool isOut = IsExplicitOutPunch(p);
                bool isIn = IsExplicitInPunch(p);
                bool isNeutral = !isOut && !isIn;

                if (pendingIn == null)
                {
                    if (isOut)
                    {
                        // Stray / Redundant OUT:
                        // An OUT punch when pendingIn == null can ONLY be valid if no prior punches have occurred
                        // for the day (e.g. an overnight shift finishing in the morning before today's shift starts).
                        // If the employee has already completed an IN->OUT session (ordered.Count > 0), they are ALREADY
                        // clocked out. Any redundant/consecutive OUT punches without an intervening IN are discarded!
                        if (ordered.Count == 0)
                        {
                            ordered.Add(p);
                        }
                        else if (ordered.Count % 2 == 0)
                        {
                            // If incoming OUT is a higher authoritative tier than the existing exit punch, upgrade the exit punch.
                            var lastOutTier = GetPunchTier(ordered[^1]);
                            var incomingTier = GetPunchTier(p);
                            if (incomingTier < lastOutTier && p.PunchTime >= ordered[^2].PunchTime)
                            {
                                ordered[^1] = p;
                            }
                        }
                    }
                    else
                    {
                        // Valid IN or neutral first punch
                        pendingIn = p;
                    }
                }
                else
                {
                    // Currently expecting an OUT
                    if (isIn)
                    {
                        // Consecutive IN:
                        // If pendingIn was an early arrival (pre-shift OT) and p is the shift start (e.g. 10:22 vs 10:30),
                        // preserve the earlier arrival so pre-shift overtime is retained.
                        if (windowStart.HasValue && pendingIn.PunchTime < windowStart.Value && p.PunchTime >= windowStart.Value && p.PunchTime <= windowStart.Value.AddMinutes(30))
                        {
                            // Keep pendingIn (early arrival) — do not overwrite with shift start
                        }
                        else
                        {
                            var tierPending = GetPunchTier(pendingIn);
                            var tierIncoming = GetPunchTier(p);
                            if (tierIncoming < tierPending)
                            {
                                // Higher priority tier wins (e.g. Machine beats Manual/Geofence, Manual beats Geofence)
                                pendingIn = p;
                            }
                        }
                    }
                    else
                    {
                        // Explicit OUT or neutral second punch -> forms an attendance pair!
                        ordered.Add(pendingIn);
                        ordered.Add(p);
                        pendingIn = null;
                    }
                }
            }

            if (pendingIn != null)
            {
                ordered.Add(pendingIn); // Live open punch for today
            }

            if (ordered.Count == 0)
            {
                return (new List<AttendanceLog>(), null, null);
            }

            DateTime? firstIn = ordered[0].PunchTime;
            DateTime? lastOut = ordered.Count >= 2 && ordered.Count % 2 == 0
                ? ordered[^1].PunchTime
                : null;

            return (ordered, firstIn, lastOut);
        }
    }
}