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

        val hasOffice = officeLat != 0.0 && officeLon != 0.0 && officeRadiusMeters > 0
        val officeEffectiveRadius = officeRadiusMeters + 15.0

        for (i in 0 until n) {
            val locA = validLocations[i]
            for (j in i + 1 until n) {
                val locB = validLocations[j]
                val dist = distanceMeters(locA.latitude, locA.longitude, locB.latitude, locB.longitude)

                val bothInOffice = hasOffice &&
                    (distanceMeters(officeLat, officeLon, locA.latitude, locA.longitude) <= officeEffectiveRadius) &&
                    (distanceMeters(officeLat, officeLon, locB.latitude, locB.longitude) <= officeEffectiveRadius)

                val stationaryA = locA.speedMps <= 1.0 || locA.movementState.equals("Stopped", ignoreCase = true)
                val stationaryB = locB.speedMps <= 1.0 || locB.movementState.equals("Stopped", ignoreCase = true)
                val bothStationary = stationaryA && stationaryB

                val threshold = when {
                    bothInOffice -> 120.0 // Both verified inside company office
                    bothStationary -> 95.0 // Both stationary at same building/shop/room (handles indoor GPS multipath jitter)
                    else -> 18.0 // Moving (e.g. together in same vehicle)
                }

                if (dist <= threshold) {
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

                // Centroid coordinates
                val allInOffice = hasOffice &&
                    group.all { distanceMeters(officeLat, officeLon, it.latitude, it.longitude) <= officeEffectiveRadius }

                val avgLat = if (allInOffice) officeLat else (group.sumOf { it.latitude } / group.size)
                val avgLon = if (allInOffice) officeLon else (group.sumOf { it.longitude } / group.size)
                val centerPoint = GeoPoint(avgLat, avgLon)

                val count = group.size
                // Radial offset in degrees (~20m - 28m on map)
                val radiusDeg = if (count <= 2) 0.00018 else if (count <= 4) 0.00022 else 0.00026
                val cosLat = cos(Math.toRadians(avgLat)).coerceAtLeast(0.1)

                group.forEachIndexed { index, loc ->
                    val angle = if (count == 2) {
                        // For 2 employees, place side-by-side horizontally (West and East)
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
