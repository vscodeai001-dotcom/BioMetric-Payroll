package com.biometric.app.di

import androidx.room.Room
import com.biometric.app.data.AppDatabase
import com.biometric.app.data.MainRepository
import com.biometric.app.sync.FirebaseSyncManager
import com.biometric.app.sync.FirebaseRoomHydrator
import android.content.Context
import com.biometric.app.data.DataSafetyManager
import com.biometric.app.data.dao.*
import com.biometric.app.data.AppLocalDatabase
import com.biometric.app.data.LocationDao
import com.biometric.app.data.dao.OfflineTrackingEventDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppLocalDatabase(@ApplicationContext context: Context): AppLocalDatabase {
        return AppLocalDatabase.getDatabase(context)
    }

    @Provides
    fun provideLocationDao(database: AppLocalDatabase): LocationDao {
        return database.locationDao()
    }

    @Provides
    fun provideOfflineTrackingEventDao(database: AppLocalDatabase): OfflineTrackingEventDao {
        return database.offlineTrackingEventDao()
    }

    @Provides
    fun provideGeofenceDao(database: AppLocalDatabase): GeofenceDao {
        return database.geofenceDao()
    }

    @Provides
    @Singleton
    @Suppress("DEPRECATION")
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "biometric_payroll.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideLocalShopDao(database: AppDatabase) = database.localShopDao()

    @Provides
    fun provideLocalEmployeeDao(database: AppDatabase) = database.localEmployeeDao()

    @Provides
    fun provideLocalAttendanceDao(database: AppDatabase) = database.localAttendanceDao()

    @Provides
    fun provideLocalAdvancePaymentDao(database: AppDatabase) = database.localAdvancePaymentDao()

    @Provides
    fun provideLocalEmployeeHistoryDao(database: AppDatabase) = database.localEmployeeHistoryDao()

    @Provides
    fun provideLocalShopClosedDayDao(database: AppDatabase) = database.localShopClosedDayDao()

    @Provides
    fun provideLocalRegularizationRequestDao(database: AppDatabase) = database.localRegularizationRequestDao()

    @Provides
    fun provideLocalAttendancePunchDao(database: AppDatabase) = database.localAttendancePunchDao()

    @Provides
    fun provideLocalSalarySnapshotDao(database: AppDatabase) = database.localSalarySnapshotDao()

    @Provides
    fun provideLocalAuditLogDao(database: AppDatabase) = database.localAuditLogDao()

    @Provides
    fun provideLocalLeaveRequestDao(database: AppDatabase) = database.localLeaveRequestDao()

    @Provides
    fun provideLocalResignationRequestDao(database: AppDatabase) = database.localResignationRequestDao()

    @Provides
    fun provideLocalDailySummaryDao(database: AppDatabase) = database.localDailySummaryDao()

    @Provides
    fun provideLocalShiftScheduleDao(database: AppDatabase) = database.localShiftScheduleDao()

    @Provides
    fun provideLocalPayrollHistoryDao(database: AppDatabase) = database.localPayrollHistoryDao()

    @Provides
    fun provideLocalBonusRecordDao(database: AppDatabase) = database.localBonusRecordDao()

    @Provides
    fun provideLocalTaxDeclarationDao(database: AppDatabase) = database.localTaxDeclarationDao()

    @Provides
    fun provideLocalFbpDeclarationDao(database: AppDatabase) = database.localFbpDeclarationDao()

    @Provides
    fun provideLocalFbpComponentDao(database: AppDatabase) = database.localFbpComponentDao()

    @Provides
    fun provideLocalSettingsDao(database: AppDatabase) = database.localSettingsDao()

    @Provides
    @Singleton
    fun provideMainRepository(
        @ApplicationContext context: Context,
        firebaseSync: FirebaseSyncManager,
        firebaseRoomHydrator: FirebaseRoomHydrator,
        dataSafety: DataSafetyManager,
        localShopDao: LocalShopDao,
        localEmployeeDao: LocalEmployeeDao,
        localAttendanceDao: LocalAttendanceDao,
        advanceDao: LocalAdvancePaymentDao,
        historyDao: LocalEmployeeHistoryDao,
        closedDayDao: LocalShopClosedDayDao,
        regularizationDao: LocalRegularizationRequestDao,
        localAttendancePunchDao: LocalAttendancePunchDao,
        localLeaveRequestDao: LocalLeaveRequestDao,
        localResignationRequestDao: LocalResignationRequestDao,
        localDailySummaryDao: LocalDailySummaryDao,
        localShiftScheduleDao: LocalShiftScheduleDao,
        localPayrollHistoryDao: LocalPayrollHistoryDao,
        localSettingsDao: LocalSettingsDao
    ): MainRepository {
        return MainRepository(
            context, firebaseSync, firebaseRoomHydrator, dataSafety,
            localShopDao, localEmployeeDao, localAttendanceDao,
            advanceDao, historyDao, closedDayDao, regularizationDao,
            localAttendancePunchDao, localLeaveRequestDao, localResignationRequestDao,
            localDailySummaryDao, localShiftScheduleDao, localPayrollHistoryDao,
            localSettingsDao
        )
    }
}
