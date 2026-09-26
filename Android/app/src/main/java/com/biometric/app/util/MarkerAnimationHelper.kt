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
        val startRotation = marker.rotation

        // Normalize rotation for shortest path
        var targetRotation = toBearing
        if (abs(targetRotation - startRotation) > 180) {
            if (targetRotation > startRotation) targetRotation -= 360 else targetRotation += 360
        }

        // Animate over 92% of the real GPS-fix interval so the marker appears
        // to travel continuously between fixes (Zomato/Swiggy-style liveness).
        // Clamped: minimum 1.5 s so short-burst updates still look smooth;
        // maximum 65 s for very slow / idle sessions.
        val animDuration = elapsedMs.coerceIn(1_500L, 65_000L)

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
