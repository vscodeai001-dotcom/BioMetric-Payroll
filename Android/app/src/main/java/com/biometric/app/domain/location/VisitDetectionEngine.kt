package com.biometric.app.domain.location

import android.location.Location
import com.biometric.app.data.entity.LocationTrack
import com.biometric.app.data.entity.LocationVisit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VisitDetectionEngine @Inject constructor() {

    companion object {
        private const val VISIT_RADIUS_METERS = 50.0
        private const val MIN_VISIT_DURATION_MILLIS = 10 * 60 * 1000L // 10 minutes minimum stay
    }

    /**
     * Groups sequential GPS points into Visits.
     */
    fun detectVisits(tracks: List<LocationTrack>): List<LocationVisit> {
        if (tracks.isEmpty()) return emptyList()

        val visits = mutableListOf<LocationVisit>()
        var currentCluster = mutableListOf<LocationTrack>()

        for (track in tracks) {
            if (currentCluster.isEmpty()) {
                currentCluster.add(track)
                continue
            }

            val firstPoint = currentCluster.first()
            val distance = calculateDistance(
                firstPoint.latitude, firstPoint.longitude,
                track.latitude, track.longitude
            )

            if (distance <= VISIT_RADIUS_METERS) {
                currentCluster.add(track)
            } else {
                // Check if the cluster qualifies as a visit
                val visit = createVisitIfQualified(currentCluster)
                if (visit != null) {
                    visits.add(visit)
                }
                currentCluster = mutableListOf(track)
            }
        }

        // Check the last cluster
        val finalVisit = createVisitIfQualified(currentCluster)
        if (finalVisit != null) {
            visits.add(finalVisit)
        }

        return visits
    }

    private fun createVisitIfQualified(cluster: List<LocationTrack>): LocationVisit? {
        if (cluster.size < 2) return null

        val arrival = cluster.first().timestamp
        val departure = cluster.last().timestamp
        val duration = departure - arrival

        if (duration >= MIN_VISIT_DURATION_MILLIS) {
            // Calculate average location
            val avgLat = cluster.map { it.latitude }.average()
            val avgLon = cluster.map { it.longitude }.average()

            return LocationVisit(
                staffId = cluster.first().staffId,
                locationId = null,
                arrivalTime = arrival,
                departureTime = departure,
                duration = duration / (60 * 1000L), // in minutes
                latitude = avgLat,
                longitude = avgLon
            )
        }
        return null
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }
}
