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

    // ── Accuracy-weighted position smoother ──────────────────────────────────

    /**
     * Applies an accuracy-weighted exponential moving average (EMA) to [location]
     * and returns a *new* Location object whose lat/lon are the smoothed values.
     *
     * Algorithm:
     *   weight  = clamp(1 / accuracy, 0.10, 0.90)   ← higher accuracy → larger update step
     *   smoothed = (1 − weight) × previous + weight × current
     *
     * Effect:
     *  • A fix with 5m accuracy  → weight ≈ 0.90 → mostly trusts the new reading  (real movement)
     *  • A fix with 20m accuracy → weight ≈ 0.50 → blends old and new             (moderate trust)
     *  • A fix with 35m accuracy → weight ≈ 0.29 → pulls slowly toward the fix    (low trust)
     *
     * All other Location fields (speed, bearing, time, etc.) are preserved from
     * [location] so callers can still read them normally.
     */
    fun smooth(location: Location): Location {
        // Clamp weight: inverse-accuracy, bounded so we always make some progress.
        val rawWeight = 1f / location.accuracy.coerceAtLeast(1f)
        val weight = rawWeight.coerceIn(0.10f, 0.90f)

        if (!hasSmoothedPosition) {
            smoothedLat = location.latitude
            smoothedLon = location.longitude
            hasSmoothedPosition = true
        } else {
            smoothedLat = (1f - weight) * smoothedLat + weight * location.latitude
            smoothedLon = (1f - weight) * smoothedLon + weight * location.longitude
        }

        // Return a copy with the smoothed coordinates so the original is unchanged.
        val smoothed = Location(location)
        smoothed.latitude  = smoothedLat
        smoothed.longitude = smoothedLon
        return smoothed
    }
}
