package com.biometric.app.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.abs

/**
 * Handles smooth movement and rotation animations for map markers,
 * inspired by high-end delivery apps like Zomato/Swiggy.
 */
object MarkerAnimationHelper {

    private val activeAnimators = mutableMapOf<Int, ValueAnimator>()

    fun animateMarker(
        marker: Marker,
        toPosition: GeoPoint,
        toBearing: Float,
        empId: Int,
        elapsedMs: Long = 2000L,
        onUpdate: (GeoPoint) -> Unit
    ) {
        activeAnimators[empId]?.cancel()

        val startPos = marker.position

        // 1. Initial Placement Check: If the marker was at (0,0) or uninitialized,
        // snap immediately to the real position. Never animate from (0,0) across the globe!
        if (startPos == null || (startPos.latitude == 0.0 && startPos.longitude == 0.0)) {
            marker.position = toPosition
            marker.rotation = toBearing
            onUpdate(toPosition)
            return
        }

        // 2. Large Jump / Transit Check: If the distance between current and new position
        // is > 300 meters (e.g. app freshly opened, or employee traveled in vehicle), snap
        // directly to the real position. Google Maps / Uber never slowly drag a marker across 5 km.
        val distMeters = startPos.distanceToAsDouble(toPosition)
        if (distMeters > 300.0) {
            marker.position = toPosition
            marker.rotation = toBearing
            onUpdate(toPosition)
            return
        }

        val startRotation = marker.rotation

        // Normalize rotation for shortest path
        var targetRotation = toBearing
        if (abs(targetRotation - startRotation) > 180) {
            if (targetRotation > startRotation) targetRotation -= 360 else targetRotation += 360
        }

        // Animate over 92% of the real GPS-fix interval so the marker appears
        // to travel continuously between fixes (Zomato/Swiggy-style liveness).
        // Clamped: minimum 1.2 s so short-burst updates still look smooth;
        // maximum 35 s for slower sessions.
        val animDuration = elapsedMs.coerceIn(1_200L, 35_000L)

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = animDuration
            interpolator = LinearInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (activeAnimators[empId] === animation) {
                        activeAnimators.remove(empId)
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (activeAnimators[empId] === animation) {
                        activeAnimators.remove(empId)
                    }
                }
            })
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                
                // Smooth Position
                val lat = startPos.latitude + (toPosition.latitude - startPos.latitude) * t
                val lon = startPos.longitude + (toPosition.longitude - startPos.longitude) * t
                val currentPoint = GeoPoint(lat, lon)
                marker.position = currentPoint
                
                // Smooth Rotation (Bearing)
                marker.rotation = startRotation + (targetRotation - startRotation) * t
                
                onUpdate(currentPoint)
            }
        }
        
        activeAnimators[empId] = animator
        animator.start()
    }

    fun cancel(empId: Int) {
        activeAnimators.remove(empId)?.cancel()
    }

    fun cancelAll() {
        val snapshot = activeAnimators.values.toList()
        activeAnimators.clear()
        snapshot.forEach { animator -> runCatching { animator.cancel() } }
    }
}
