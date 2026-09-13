package com.biometric.app.domain

import javax.inject.Inject

class DataMigrationUseCase @Inject constructor() {
    fun migrateToSummaries() {
        // No migration needed for workforce-only portal clone
    }
}
