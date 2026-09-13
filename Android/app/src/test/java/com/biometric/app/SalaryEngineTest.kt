package com.biometric.app

import com.biometric.app.data.SalaryEngine
import com.biometric.app.data.entity.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.*

class SalaryEngineTest {

    private fun createDefaultEmployee(): Employee {
        return Employee(
            employeeId = "test_emp",
            name = "Test Staff",
            salaryType = "MONTHLY_FIXED",
            salaryRate = 30000.0,
            shiftStart = "09:00",
            shiftEnd = "18:00",
            breakHours = 1.0, // 8 net hours
            hireDate = Calendar.getInstance().apply { set(2023, 0, 1) }.timeInMillis,
            isBonusEligibleRule = true,
            isPaidLeaveEligibleRule = true,
        )
    }

    private fun getMonthRange(year: Int, month: Int): Pair<Long, Long> {
        val start = Calendar.getInstance().apply {
            set(year, month, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = Calendar.getInstance().apply {
            set(year, month, start.getActualMaximum(Calendar.DAY_OF_MONTH), 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return start.timeInMillis to end.timeInMillis
    }

    @Test
    fun `test perfect attendance bonus eligibility`() {
        val employee = createDefaultEmployee()
        val (start, end) = getMonthRange(2023, 5) // June 2023 (30 days)
        
        // Mock attendance for all 30 days
        val attendance = mutableListOf<Attendance>()
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        while (cal.timeInMillis <= end) {
            val dayStart = Calendar.getInstance().apply {
                timeInMillis = cal.timeInMillis
                set(Calendar.HOUR_OF_DAY, 9)
            }.timeInMillis
            val dayEnd = Calendar.getInstance().apply {
                timeInMillis = cal.timeInMillis
                set(Calendar.HOUR_OF_DAY, 18)
            }.timeInMillis
            
            attendance.add(Attendance(employeeId = employee.employeeId, checkInTime = dayStart, checkOutTime = dayEnd, type = "WORK"))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val (stats, _) = SalaryEngine.calculateStats(
            employee = employee,
            monthStart = start,
            monthEnd = end,
            allAttendance = attendance,
            closedDays = emptyList(),
            pendingAdvance = 0.0,
            history = emptyList(),
            selDayStart = 0,
            selDayEnd = 0
        )

        assertTrue("Should be bonus eligible for perfect attendance", stats.isBonusEligibleByRule)
        assertEquals(30, stats.daysPresent)
        assertEquals(0, stats.daysAbsent)
        assertEquals(0.0, stats.shortfallHours, 0.01)
    }

    @Test
    fun `test bonus disqualification due to single gap`() {
        val employee = createDefaultEmployee()
        val (start, end) = getMonthRange(2023, 5)
        
        val attendance = mutableListOf<Attendance>()
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        while (cal.timeInMillis <= end) {
            val dayStart = Calendar.getInstance().apply {
                timeInMillis = cal.timeInMillis
                set(Calendar.HOUR_OF_DAY, 9)
            }.timeInMillis
            val dayEnd = Calendar.getInstance().apply {
                timeInMillis = cal.timeInMillis
                set(Calendar.HOUR_OF_DAY, 18)
            }.timeInMillis
            
            attendance.add(Attendance(employeeId = employee.employeeId, checkInTime = dayStart, checkOutTime = dayEnd, type = "WORK"))
            
            // Introduce a 1-hour gap on the 5th day
            if (cal[Calendar.DAY_OF_MONTH] == 5) {
                val gapStart = Calendar.getInstance().apply {
                    timeInMillis = cal.timeInMillis
                    set(Calendar.HOUR_OF_DAY, 12)
                }.timeInMillis
                val gapEnd = Calendar.getInstance().apply {
                    timeInMillis = cal.timeInMillis
                    set(Calendar.HOUR_OF_DAY, 13)
                }.timeInMillis
                attendance.add(Attendance(employeeId = employee.employeeId, checkInTime = gapStart, checkOutTime = gapEnd, type = "GAP"))
            }
            
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val (stats, _) = SalaryEngine.calculateStats(
            employee = employee,
            monthStart = start,
            monthEnd = end,
            allAttendance = attendance,
            closedDays = emptyList(),
            pendingAdvance = 0.0,
            history = emptyList(),
            selDayStart = 0,
            selDayEnd = 0
        )

        assertFalse("Should NOT be bonus eligible due to a gap", stats.isBonusEligibleByRule)
        // Shortfall might be covered by paid leave pool, so let's check bonus eligibility directly
        assertEquals(0.0, stats.bonusAmount.toDouble(), 0.01)
    }

    @Test
    fun `test paid leave eligibility - up to 3 days`() {
        val employee = createDefaultEmployee()
        val (start, end) = getMonthRange(2023, 5)
        
        // Present for 27 days, absent for 3 days
        val attendance = mutableListOf<Attendance>()
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        while (cal.timeInMillis <= end) {
            if (cal[Calendar.DAY_OF_MONTH] > 3) {
                val dayStart = Calendar.getInstance().apply {
                    timeInMillis = cal.timeInMillis
                    set(Calendar.HOUR_OF_DAY, 9)
                }.timeInMillis
                val dayEnd = Calendar.getInstance().apply {
                    timeInMillis = cal.timeInMillis
                    set(Calendar.HOUR_OF_DAY, 18)
                }.timeInMillis
                attendance.add(Attendance(employeeId = employee.employeeId, checkInTime = dayStart, checkOutTime = dayEnd, type = "WORK"))
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val (stats, _) = SalaryEngine.calculateStats(
            employee = employee,
            monthStart = start,
            monthEnd = end,
            allAttendance = attendance,
            closedDays = emptyList(),
            pendingAdvance = 0.0,
            history = emptyList(),
            selDayStart = 0,
            selDayEnd = 0
        )

        assertEquals(3, stats.daysAbsent)
        assertTrue("Should be paid leave eligible for 3 days absence", stats.isPaidLeaveEligibleByRule)
        assertEquals(8.0, stats.paidLeaveAppliedHours, 0.01) // Covers 1 day (8 hours)
    }

    @Test
    fun `test paid leave ineligibility - 4 days absence`() {
        val employee = createDefaultEmployee()
        val (start, end) = getMonthRange(2023, 5)
        
        // Present for 26 days, absent for 4 days
        val attendance = mutableListOf<Attendance>()
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        while (cal.timeInMillis <= end) {
            if (cal[Calendar.DAY_OF_MONTH] > 4) {
                val dayStart = Calendar.getInstance().apply {
                    timeInMillis = cal.timeInMillis
                    set(Calendar.HOUR_OF_DAY, 9)
                }.timeInMillis
                val dayEnd = Calendar.getInstance().apply {
                    timeInMillis = cal.timeInMillis
                    set(Calendar.HOUR_OF_DAY, 18)
                }.timeInMillis
                attendance.add(Attendance(employeeId = employee.employeeId, checkInTime = dayStart, checkOutTime = dayEnd, type = "WORK"))
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val (stats, _) = SalaryEngine.calculateStats(
            employee = employee,
            monthStart = start,
            monthEnd = end,
            allAttendance = attendance,
            closedDays = emptyList(),
            pendingAdvance = 0.0,
            history = emptyList(),
            selDayStart = 0,
            selDayEnd = 0
        )

        assertEquals(4, stats.daysAbsent)
        assertFalse("Should NOT be paid leave eligible for 4 days absence", stats.isPaidLeaveEligibleByRule)
        assertEquals(0.0, stats.paidLeaveAppliedHours, 0.01)
    }

    @Test
    fun `test manual bonus override suppresses automatic paid leave`() {
        val monthKey = "2023_6"
        val (start, end) = getMonthRange(2023, 5) // June 2023
        
        val employee = createDefaultEmployee().apply {
            // Manager manually grants a bonus
            monthlyBonusOverrides = mutableMapOf(monthKey to true)
        }
        
        // Staff has a gap that would normally trigger automatic Paid Leave
        val attendance = mutableListOf<Attendance>()
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        while (cal.timeInMillis <= end) {
            val dayStart = Calendar.getInstance().apply {
                timeInMillis = cal.timeInMillis
                set(Calendar.HOUR_OF_DAY, 9)
            }.timeInMillis
            val dayEnd = Calendar.getInstance().apply {
                timeInMillis = cal.timeInMillis
                set(Calendar.HOUR_OF_DAY, 18)
            }.timeInMillis
            
            attendance.add(Attendance(employeeId = employee.employeeId, checkInTime = dayStart, checkOutTime = dayEnd, type = "WORK"))
            
            if (cal[Calendar.DAY_OF_MONTH] == 5) {
                // 1 hour gap
                val gapStart = Calendar.getInstance().apply {
                    timeInMillis = cal.timeInMillis
                    set(Calendar.HOUR_OF_DAY, 12)
                }.timeInMillis
                val gapEnd = Calendar.getInstance().apply {
                    timeInMillis = cal.timeInMillis
                    set(Calendar.HOUR_OF_DAY, 13)
                }.timeInMillis
                attendance.add(Attendance(employeeId = employee.employeeId, checkInTime = gapStart, checkOutTime = gapEnd, type = "GAP"))
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val (stats, _) = SalaryEngine.calculateStats(
            employee = employee,
            monthStart = start,
            monthEnd = end,
            allAttendance = attendance,
            closedDays = emptyList(),
            pendingAdvance = 0.0,
            history = emptyList(),
            selDayStart = 0,
            selDayEnd = 0
        )

        // VERIFICATION:
        // 1. Manual Bonus should be active.
        // 2. Paid Leave should be SUPPRESSED (to avoid disqualifying the bonus).
        assertTrue("Manual Bonus should be active", stats.isBonusEligible)
        assertTrue("Bonus amount should be one day's pay", stats.bonusAmount.toDouble() > 0)
        assertEquals("Paid Leave should be suppressed to keep bonus valid", 0.0, stats.paidLeaveAppliedHours, 0.001)
    }

    @Test
    fun `test manual paid leave override kills bonus`() {
        val monthKey = "2023_6"
        val (start, end) = getMonthRange(2023, 5) // June 2023
        
        val employee = createDefaultEmployee().apply {
            // Staff is otherwise perfect, but manager MANUALLY grants Paid Leave
            // (Maybe for a future credit or special reason)
            monthlyPaidLeaveOverrides = mutableMapOf(monthKey to true)
        }
        
        // Perfect attendance (No gaps)
        val attendance = mutableListOf<Attendance>()
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        while (cal.timeInMillis <= end) {
            val dayStart = Calendar.getInstance().apply {
                timeInMillis = cal.timeInMillis
                set(Calendar.HOUR_OF_DAY, 9)
            }.timeInMillis
            val dayEnd = Calendar.getInstance().apply {
                timeInMillis = cal.timeInMillis
                set(Calendar.HOUR_OF_DAY, 18)
            }.timeInMillis
            attendance.add(Attendance(employeeId = employee.employeeId, checkInTime = dayStart, checkOutTime = dayEnd, type = "WORK"))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val (stats, _) = SalaryEngine.calculateStats(
            employee = employee,
            monthStart = start,
            monthEnd = end,
            allAttendance = attendance,
            closedDays = emptyList(),
            pendingAdvance = 0.0,
            history = emptyList(),
            selDayStart = 0,
            selDayEnd = 0
        )

        // VERIFICATION:
        // If PL is active (manually or used), Bonus is killed.
        assertTrue("Manual Paid Leave should be active", stats.isPaidLeaveEligible)
        assertFalse("Bonus should be killed by manual Paid Leave", stats.isBonusEligible)
        assertEquals(0.0, stats.bonusAmount.toDouble(), 0.01)
    }

    @Test
    fun `test paid leave amount for August 31-day month`() {
        val employee = Employee(
            employeeId = "aug_emp",
            name = "August Staff",
            salaryType = "MONTHLY_FIXED",
            salaryRate = 12000.0,
            shiftStart = "10:00",
            shiftEnd = "22:00",
            breakHours = 0.0, // 12 net hours
            hireDate = Calendar.getInstance().apply { set(2026, 7, 1) }.timeInMillis, // Aug 1, 2026
            paidLeaveOnWeekends = true,
        )
        val (start, end) = getMonthRange(2026, 7) // August 2026 (31 days)
        
        // Absent on 1st day, present on others
        val attendance = mutableListOf<Attendance>()
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        while (cal.timeInMillis <= end) {
            if (cal[Calendar.DAY_OF_MONTH] > 1) {
                val dayStart = Calendar.getInstance().apply {
                    timeInMillis = cal.timeInMillis
                    set(Calendar.HOUR_OF_DAY, 10)
                }.timeInMillis
                val dayEnd = Calendar.getInstance().apply {
                    timeInMillis = cal.timeInMillis
                    set(Calendar.HOUR_OF_DAY, 22)
                }.timeInMillis
                attendance.add(Attendance(employeeId = employee.employeeId, checkInTime = dayStart, checkOutTime = dayEnd, type = "WORK"))
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val (stats, _) = SalaryEngine.calculateStats(
            employee = employee,
            monthStart = start,
            monthEnd = end,
            allAttendance = attendance,
            closedDays = emptyList(),
            pendingAdvance = 0.0,
            history = emptyList(),
            selDayStart = 0,
            selDayEnd = 0
        )

        assertEquals(1, stats.daysAbsent)
        assertEquals(12.0, stats.paidLeaveAppliedHours, 0.01)
        // 12000 / 31 = 387.096...
        assertEquals(387.10, stats.paidLeaveSalary.toDouble(), 0.01)
    }
}
