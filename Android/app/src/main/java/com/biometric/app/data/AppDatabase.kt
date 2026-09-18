package com.biometric.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.biometric.app.data.dao.*
import com.biometric.app.data.entity.*

@Database(
    entities = [
        LocalShop::class,
        LocalEmployee::class,
        LocalAttendance::class,
        LocalAdvancePayment::class,
        LocalEmployeeHistory::class,
        LocalShopClosedDay::class,
        LocalRegularizationRequest::class,
        LocalAttendancePunch::class,
        LocalSalarySnapshot::class,
        LocalAuditLog::class,
        LocalLeaveRequest::class,
        LocalResignationRequest::class,
        LocalDailySummary::class,
        LocalShiftSchedule::class,
        LocalPayrollHistory::class,
        LocalBonusRecord::class,
        LocalTaxDeclaration::class,
        LocalFbpDeclaration::class,
        LocalFbpComponent::class
    ],
    version = 12,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun localShopDao(): LocalShopDao
    abstract fun localEmployeeDao(): LocalEmployeeDao
    abstract fun localAttendanceDao(): LocalAttendanceDao
    abstract fun localAdvancePaymentDao(): LocalAdvancePaymentDao
    abstract fun localEmployeeHistoryDao(): LocalEmployeeHistoryDao
    abstract fun localShopClosedDayDao(): LocalShopClosedDayDao
    abstract fun localRegularizationRequestDao(): LocalRegularizationRequestDao
    abstract fun localAttendancePunchDao(): LocalAttendancePunchDao
    abstract fun localSalarySnapshotDao(): LocalSalarySnapshotDao
    abstract fun localAuditLogDao(): LocalAuditLogDao
    abstract fun localLeaveRequestDao(): LocalLeaveRequestDao
    abstract fun localResignationRequestDao(): LocalResignationRequestDao
    abstract fun localDailySummaryDao(): LocalDailySummaryDao
    abstract fun localShiftScheduleDao(): LocalShiftScheduleDao
    abstract fun localPayrollHistoryDao(): LocalPayrollHistoryDao
    abstract fun localBonusRecordDao(): LocalBonusRecordDao
    abstract fun localTaxDeclarationDao(): LocalTaxDeclarationDao
    abstract fun localFbpDeclarationDao(): LocalFbpDeclarationDao
    abstract fun localFbpComponentDao(): LocalFbpComponentDao
}
