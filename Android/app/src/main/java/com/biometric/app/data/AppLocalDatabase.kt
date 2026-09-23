package com.biometric.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.biometric.app.data.dao.GeofenceDao
import com.biometric.app.data.dao.OfflineTrackingEventDao
import com.biometric.app.data.entity.GeofenceLocation
import com.biometric.app.data.entity.OfflineTrackingEvent

@Database(
    entities = [LocalLocation::class, OfflineTrackingEvent::class, GeofenceLocation::class],
    version = 4,
    exportSchema = false
)
abstract class AppLocalDatabase : RoomDatabase() {
    abstract fun locationDao(): LocationDao
    abstract fun offlineTrackingEventDao(): OfflineTrackingEventDao
    abstract fun geofenceDao(): GeofenceDao

    companion object {
        @Volatile
        private var INSTANCE: AppLocalDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE offline_locations ADD COLUMN clientEventId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE offline_locations ADD COLUMN sequence INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE offline_locations ADD COLUMN capturedElapsedRealtime INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE offline_locations ADD COLUMN syncState TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE offline_locations ADD COLUMN attemptCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE offline_locations ADD COLUMN lastAttemptAt INTEGER")
                db.execSQL("ALTER TABLE offline_locations ADD COLUMN lastError TEXT")
                db.execSQL("ALTER TABLE offline_locations ADD COLUMN isOfflineCapture INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE offline_locations ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE offline_locations ADD COLUMN syncedAt INTEGER")
                db.execSQL("UPDATE offline_locations SET clientEventId = 'legacy-' || id, createdAt = timestamp WHERE clientEventId = ''")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_offline_locations_clientEventId ON offline_locations(clientEventId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_locations_sessionId_timestamp ON offline_locations(sessionId, timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_locations_syncState_timestamp ON offline_locations(syncState, timestamp)")
                db.execSQL("CREATE TABLE IF NOT EXISTS offline_tracking_events (eventId TEXT NOT NULL PRIMARY KEY, eventTime INTEGER NOT NULL, eventType TEXT NOT NULL, severity TEXT NOT NULL, message TEXT NOT NULL, sessionId TEXT, networkAvailable INTEGER NOT NULL, queueDepth INTEGER NOT NULL, latitude REAL, longitude REAL, accuracy REAL, correlationId TEXT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_tracking_events_eventTime ON offline_tracking_events(eventTime)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_tracking_events_sessionId_eventTime ON offline_tracking_events(sessionId, eventTime)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add sync fields to offline_tracking_events
                db.execSQL("ALTER TABLE offline_tracking_events ADD COLUMN syncState TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE offline_tracking_events ADD COLUMN syncedAt INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_tracking_events_syncState_eventTime ON offline_tracking_events(syncState, eventTime)")
                
                // Create geofences table
                db.execSQL("CREATE TABLE IF NOT EXISTS geofences (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, radius REAL NOT NULL, type TEXT NOT NULL, isActive INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                var columnExists = false
                val cursor = db.query("PRAGMA table_info(offline_locations)")
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex != -1 && cursor.getString(nameIndex) == "bearing") {
                        columnExists = true
                        break
                    }
                }
                cursor.close()

                if (!columnExists) {
                    db.execSQL("ALTER TABLE offline_locations ADD COLUMN bearing REAL NOT NULL DEFAULT 0.0")
                }
            }
        }

        fun getDatabase(context: Context): AppLocalDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppLocalDatabase::class.java,
                    "biometric_local_db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
