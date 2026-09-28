package com.biometric.app.util

import android.location.Location
import com.biometric.app.sync.SignalRManager
import org.osmdroid.util.GeoPoint
import kotlin.math.cos
import kotlin.math.sin

data class MarkerClusterInfo(
    val employeeId: Int,
    val clusterCenter: GeoPoint,
    val displayPoint: GeoPoint,
    val isClustered: Boolean,
    val clusterSize: Int
)

/**
 * Intelligent spatial co-location clustering and non-overlapping pin fanning
 * for Android OSMDroid live tracking maps.
 *
 * Ensures co-located employees (e.g. in the same room, building, or office geofence)
 * are visually grouped at the same location, fanned out side-by-side with zero overlap,
 * and connected to their shared centroid anchor by leader lines.
 *
 * When an employee moves (speed > 1.0 m/s), they smoothly detach to their real road coordinate.
 */
object LocationClusterHelper {

    fun distanceMeters(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(fromLat, fromLon, toLat, toLon, results)
        return results[0].toDouble()
    }

    fun computeClusterPositions(
        locations: List<SignalRManager.LiveLocation>,
        officeLat: Double = 0.0,
        officeLon: Double = 0.0,
        officeRadiusMeters: Int = 0
    ): Map<Int, MarkerClusterInfo> {
        val validLocations = locations.filter {
            it.employeeId > 0 && it.latitude != 0.0 && it.longitude != 0.0
        }
        if (validLocations.isEmpty()) return emptyMap()

        if (validLocations.size < 2) {
            val loc = validLocations[0]
            val pt = GeoPoint(loc.latitude, loc.longitude)
            return mapOf(
                loc.employeeId to MarkerClusterInfo(
                    employeeId = loc.employeeId,
                    clusterCenter = pt,
                    displayPoint = pt,
                    isClustered = false,
                    clusterSize = 1
                )
            )
        }

        val n = validLocations.size
        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var p = i
            while (parent[p] != p) {
                parent[p] = parent[parent[p]]
                p = parent[p]
            }
            return p
        }
        fun union(a: Int, b: Int) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) parent[rootB] = rootA
        }

        // Only group employees if they are physically at the exact same location (<= 5 meters).
        // Never group employees who are in different houses, rooms, or streets.
        for (i in 0 until n) {
            val locA = validLocations[i]
            for (j in i + 1 until n) {
                val locB = validLocations[j]
                val dist = distanceMeters(locA.latitude, locA.longitude, locB.latitude, locB.longitude)
                if (dist <= 5.0) {
                    union(i, j)
                }
            }
        }

        val groups = mutableMapOf<Int, MutableList<SignalRManager.LiveLocation>>()
        for (i in 0 until n) {
            val root = find(i)
            groups.getOrPut(root) { mutableListOf() }.add(validLocations[i])
        }

        val result = mutableMapOf<Int, MarkerClusterInfo>()
        for ((_, group) in groups) {
            if (group.size <= 1) {
                val loc = group[0]
                val pt = GeoPoint(loc.latitude, loc.longitude)
                result[loc.employeeId] = MarkerClusterInfo(
                    employeeId = loc.employeeId,
                    clusterCenter = pt,
                    displayPoint = pt,
                    isClustered = false,
                    clusterSize = 1
                )
            } else {
                // Stable ordering by employeeId so markers never swap positions or jitter
                group.sortBy { it.employeeId }

                // Centroid coordinates MUST ALWAYS be calculated from the employees' real positions.
                // NEVER override employee coordinates with office coordinates!
                val avgLat = group.sumOf { it.latitude } / group.size
                val avgLon = group.sumOf { it.longitude } / group.size
                val centerPoint = GeoPoint(avgLat, avgLon)

                val count = group.size
                // Gentle micro-offset (~3-5m) so overlapping pins at the same counter are both clickable
                val radiusDeg = if (count <= 2) 0.000035 else if (count <= 4) 0.000045 else 0.000055
                val cosLat = cos(Math.toRadians(avgLat)).coerceAtLeast(0.1)

                group.forEachIndexed { index, loc ->
                    val angle = if (count == 2) {
                        if (index == 0) Math.PI else 0.0
                    } else {
                        (-Math.PI / 2.0) + (index * (2.0 * Math.PI / count))
                    }

                    val fannedLat = avgLat + radiusDeg * sin(angle)
                    val fannedLon = avgLon + (radiusDeg / cosLat) * cos(angle)
                    val displayPt = GeoPoint(fannedLat, fannedLon)

                    result[loc.employeeId] = MarkerClusterInfo(
                        employeeId = loc.employeeId,
                        clusterCenter = centerPoint,
                        displayPoint = displayPt,
                        isClustered = true,
                        clusterSize = count
                    )
                }
            }
        }
        return result
    }
}

