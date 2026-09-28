package com.biometric.app.domain.location

import android.location.Location
import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enterprise-grade GPS quality gating and position smoothing.
 *
 * Responsibilities:
 *  1. [isMockLocation]       — Reject spoofed/mock providers.
 *  2. [isSuspiciousMovement] — Reject physically impossible speed jumps (>300 km/h).
 *  3. [smooth]               — Accuracy-weighted exponential moving average that blends
 *                              consecutive GPS fixes to reduce indoor multipath jitter
 *                              without adding perceptible lag for real movement.
 */
@Singleton
class LocationQualityManager @Inject constructor() {

    // ── Smoother state ───────────────────────────────────────────────────────
    // The smoothed position is stored as flat doubles so we never hold a
    // Location object longer than needed (avoids stale Binder refs).
    private var smoothedLat: Double = 0.0
    private var smoothedLon: Double = 0.0
    private var hasSmoothedPosition: Boolean = false

    /** Reset the smoother when a new tracking session starts. */
    fun resetSmoother() {
        hasSmoothedPosition = false
    }

    // ── Mock-location detection ──────────────────────────────────────────────

    /**
     * Returns true if [location] was produced by a mock / spoofing provider.
     */
    fun isMockLocation(location: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
    }

    // ── Impossible-speed detection ───────────────────────────────────────────

    /**
     * Returns true if the device appears to have teleported (speed > 300 km/h).
     */
    fun isSuspiciousMovement(lastLoc: Location?, currentLoc: Location): Boolean {
        if (lastLoc == null) return false
        val distance = lastLoc.distanceTo(currentLoc)
        val timeDeltaSeconds = (currentLoc.time - lastLoc.time) / 1000.0
        if (timeDeltaSeconds <= 0) return false
        val speedKmh = (distance / timeDeltaSeconds) * 3.6
        return speedKmh > 300.0
    }

    // ── High-Precision Position Filter & Motion Tracker ─────────────────────

    /**
     * Filters and smooths GPS fixes to deliver a Google-grade real-time experience:
     *
     *  1. Stationary Deadband: If the employee is stopped (speed < 0.6 m/s) and GPS
     *     wanders within an 8m radius, anchor the coordinate. Eliminates indoor desk
     *     jitter, multipath reflections, and phantom movement while sitting still.
     *
     *  2. Large Movement Reset: If the employee moves > 100m (e.g. driving, riding,
     *     or resuming tracking at a new site), snap directly to the new fix. No lag,
     *     no false trailing positions, no slow cross-town dragging.
     *
     *  3. Responsive Movement Smoothing: When moving between 8m and 100m, trust the
     *     new GPS fix with 85% weight (15% previous). This tracks true physical walking
     *     and driving paths faithfully without artificial 50m teleport-jumps.
     */
    fun smooth(location: Location): Location {
        if (!hasSmoothedPosition) {
            smoothedLat = location.latitude
            smoothedLon = location.longitude
            hasSmoothedPosition = true
            return Location(location)
        }

        val tempLoc = Location("smoother_ref")
        tempLoc.latitude = smoothedLat
        tempLoc.longitude = smoothedLon
        val distFromSmoothed = tempLoc.distanceTo(location)
        val speed = if (location.hasSpeed()) location.speed else 0f

        // A. Stationary Deadband: Ignore micro-jitter (< 8m) when device is stationary.
        // Prevents map marker from jittering around the employee's desk.
        if (speed < 0.6f && distFromSmoothed < 8.0f) {
            val smoothed = Location(location)
            smoothed.latitude = smoothedLat
            smoothed.longitude = smoothedLon
            return smoothed
        }

        // B. Large displacement (> 100m): Real rapid transit or location jump.
        // Instantly adopt the real GPS coordinate — zero lag, zero ghosting.
        if (distFromSmoothed > 100.0f) {
            smoothedLat = location.latitude
            smoothedLon = location.longitude
        } else {
            // C. Active Movement (8m - 100m): Responsive complementary filter.
            // 85% new reading, 15% previous reading. Tracks turns and movement faithfully.
            val weight = if (speed > 1.5f) 0.90f else 0.85f
            smoothedLat = (1f - weight) * smoothedLat + weight * location.latitude
            smoothedLon = (1f - weight) * smoothedLon + weight * location.longitude
        }

        val smoothed = Location(location)
        smoothed.latitude = smoothedLat
        smoothed.longitude = smoothedLon
        return smoothed
    }
}
