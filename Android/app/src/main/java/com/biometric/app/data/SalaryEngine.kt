package com.biometric.app.data

import com.biometric.app.data.entity.*
import com.biometric.app.domain.payroll.PayrollEngine
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

data class SalaryDetails(
    val salaryType: String,
    val salaryRate: BigDecimal,
    val dailyAllowance: BigDecimal,
    val shiftStart: String,
    val shiftEnd: String,
    val shift2Start: String?,
    val shift2End: String?,
    val breakHours: Double,
    val isBonusEligible: Boolean,
    val isPaidLeaveEligible: Boolean,
    val plWeekdays: Boolean,
    val plWeekends: Boolean,
    val rulesOverride: SalaryRules?,
)

object SalaryEngine {
    private val gson = com.google.gson.Gson()

    data class DeductionEvent(
        val type: String, // LATE, EARLY, GAP, LEAVE
        val dayMidnight: Long,
        val totalHours: Double,
        var netHours: Double,
        val totalAmount: BigDecimal,
        var netAmount: BigDecimal,
        val title: String,
        val desc: String,
        val timestamp: Long
    )

    /**
     * CORE REFACTORED ENGINE: Logic remains identical, but structure is cleaner.
     */
    fun calculateStats(
        employee: Employee,
        monthStart: Long,
        monthEnd: Long,
        allAttendance: List<Attendance>,
        closedDays: List<ShopClosedDay>,
        pendingAdvance: Double,
        history: List<EmployeeHistory>,
        selDayStart: Long,
        selDayEnd: Long,
        rules: SalaryRules = SalaryRules(),
        snapshot: SalarySnapshot? = null
    ): Pair<EmployeeStats, Any> {
        val monthCal = Calendar.getInstance().apply { timeInMillis = monthStart }
        val daysInMonth = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)

        val salaryHistory = history.filter { it.type == "SALARY" }.sortedBy { it.effectiveDate }
        val allowanceHistory = history.filter { it.type == "ALLOWANCE" }.sortedBy { it.effectiveDate }
        val shiftHistory = history.filter { it.type == "SHIFT" }.sortedBy { it.effectiveDate }

        val baseDetails = getDetailsForDay(monthEnd, employee, salaryHistory, allowanceHistory, shiftHistory)
        
        var totalScheduledHrsForMonth = 0.0
        val tempCalForTotal = Calendar.getInstance().apply { timeInMillis = monthStart }
        while (tempCalForTotal.timeInMillis <= monthEnd) {
            val dayDetailsForTotal = getDetailsForDay(tempCalForTotal.timeInMillis, employee, salaryHistory, allowanceHistory, shiftHistory)
            totalScheduledHrsForMonth += getShiftHours(
                dayDetailsForTotal.shiftStart, 
                dayDetailsForTotal.shiftEnd, 
                dayDetailsForTotal.breakHours,
                dayDetailsForTotal.shift2Start,
                dayDetailsForTotal.shift2End
            )
            tempCalForTotal.add(Calendar.DAY_OF_YEAR, 1)
        }
        
        val netScheduledHrsAtEnd = getShiftHours(
            baseDetails.shiftStart, 
            baseDetails.shiftEnd, 
            baseDetails.breakHours,
            baseDetails.shift2Start,
            baseDetails.shift2End
        )
        val fullMonthRequiredHrs = totalScheduledHrsForMonth
        
        val attendanceByDay = allAttendance.groupBy { 
            val cal = Calendar.getInstance().apply { timeInMillis = it.checkInTime }
            "${cal[Calendar.YEAR]}_${cal[Calendar.DAY_OF_YEAR]}"
        }
        
        val closedDaysSet = closedDays.asSequence().map {
            val cal = Calendar.getInstance().apply { timeInMillis = it.date }
            "${cal[Calendar.YEAR]}_${cal[Calendar.DAY_OF_YEAR]}"
        }.toSet()

        val context = if (snapshot != null && (snapshot.periodEnd >= monthStart) && (snapshot.periodEnd < monthEnd)) {
            ProcessingContext().apply {
                totalNormalWorkedHours = snapshot.totalNormalWorkedHours
                totalOTHours = snapshot.totalOTHours
                presentDaysCount = snapshot.presentDaysCount
                closedShopDaysCount = snapshot.closedShopDaysCount
                totalAllowanceMoney = BigDecimal.valueOf(snapshot.totalAllowanceMoney)
                paidClosedDaysSalary = BigDecimal.valueOf(snapshot.paidClosedDaysSalary)
                paidClosedDaysHours = snapshot.paidClosedDaysHours
                upToDateRequiredHrs = snapshot.upToDateRequiredHrs
                
                dayWiseEarnings.putAll(gson.fromJson<Map<String, Double>>(snapshot.dayWiseEarningsJson, object : com.google.gson.reflect.TypeToken<Map<String, Double>>() {}.type).entries.associate { it.key.toLong() to BigDecimal.valueOf(it.value) })
                dayWiseOTEarnings.putAll(gson.fromJson<Map<String, Double>>(snapshot.dayWiseOTEarningsJson, object : com.google.gson.reflect.TypeToken<Map<String, Double>>() {}.type).entries.associate { it.key.toLong() to BigDecimal.valueOf(it.value) })
                dayWiseAllowances.putAll(gson.fromJson<Map<String, Double>>(snapshot.dayWiseAllowancesJson, object : com.google.gson.reflect.TypeToken<Map<String, Double>>() {}.type).entries.associate { it.key.toLong() to BigDecimal.valueOf(it.value) })
                dayWiseWorkedHours.putAll(gson.fromJson<Map<String, Double>>(snapshot.dayWiseWorkedHoursJson, object : com.google.gson.reflect.TypeToken<Map<String, Double>>() {}.type).mapKeys { it.key.toLong() })
                dayWiseBonus.putAll(gson.fromJson<Map<String, Double>>(snapshot.dayWiseBonusJson, object : com.google.gson.reflect.TypeToken<Map<String, Double>>() {}.type).entries.associate { it.key.toLong() to BigDecimal.valueOf(it.value) })
                dayWisePaidLeave.putAll(gson.fromJson<Map<String, Double>>(snapshot.dayWisePaidLeaveJson, object : com.google.gson.reflect.TypeToken<Map<String, Double>>() {}.type).entries.associate { it.key.toLong() to BigDecimal.valueOf(it.value) })
                dayShortfalls.putAll(gson.fromJson<Map<String, Double>>(snapshot.dayShortfallsJson, object : com.google.gson.reflect.TypeToken<Map<String, Double>>() {}.type).mapKeys { it.key.toLong() })
                personalAbsenceDays.addAll(gson.fromJson<List<Long>>(snapshot.personalAbsenceDaysJson, object : com.google.gson.reflect.TypeToken<List<Long>>() {}.type))
            }
        } else {
            ProcessingContext()
        }

        val now = System.currentTimeMillis()
        val todayMidnight = getMidnight(now)
        val hireMidnight = getMidnight(employee.hireDate)
        val terminateMidnight = employee.terminateDate?.let { getMidnight(it) } ?: Long.MAX_VALUE

        val tempCal = Calendar.getInstance().apply { 
            timeInMillis = if (snapshot != null && snapshot.periodEnd >= monthStart && snapshot.periodEnd < monthEnd) {
                snapshot.periodEnd + 1000 // Start from the next day
            } else {
                monthStart
            }
        }

        while (tempCal.timeInMillis <= monthEnd) {
            val dayMidnight = tempCal.timeInMillis
            val dateKey = "${tempCal[Calendar.YEAR]}_${tempCal[Calendar.DAY_OF_YEAR]}"
            val isSelectedDay = dayMidnight in (selDayStart..selDayEnd)
            
            val dayDetails = getDetailsForDay(dayMidnight, employee, salaryHistory, allowanceHistory, shiftHistory)
            val dayNetScheduledHrs = getShiftHours(
                dayDetails.shiftStart, 
                dayDetails.shiftEnd, 
                dayDetails.breakHours,
                dayDetails.shift2Start,
                dayDetails.shift2End
            )
            
            val dayHourlyRate = if (dayDetails.salaryType == "MONTHLY_FIXED") {
                if (dayNetScheduledHrs > 0) {
                    dayDetails.salaryRate.divide(BigDecimal.valueOf(daysInMonth.toLong()), 10, RoundingMode.HALF_UP)
                        .divide(BigDecimal.valueOf(dayNetScheduledHrs), 10, RoundingMode.HALF_UP)
                } else BigDecimal.ZERO
            } else {
                dayDetails.salaryRate
            }
            val dayDailyRate = dayHourlyRate.multiply(BigDecimal.valueOf(dayNetScheduledHrs))

            val isEmployed = dayMidnight in hireMidnight..terminateMidnight
            val isClosed = closedDaysSet.contains(dateKey)

            var dayReqHrsSoFar = 0.0
            if (isEmployed && !isClosed) {
                if (dayMidnight < todayMidnight) {
                    dayReqHrsSoFar = dayNetScheduledHrs
                } else if (dayMidnight == todayMidnight) {
                    val currentDecimal = Calendar.getInstance()[Calendar.HOUR_OF_DAY] + (Calendar.getInstance()[Calendar.MINUTE] / 60.0)
                    if (currentDecimal >= getShiftStartDecimal(dayDetails.shiftStart)) {
                        dayReqHrsSoFar = dayNetScheduledHrs
                    }
                }
            }
            context.upToDateRequiredHrs += dayReqHrsSoFar

            processDayAttendance(
                dayMidnight = dayMidnight,
                isSelectedDay = isSelectedDay,
                isEmployed = isEmployed,
                isClosed = isClosed,
                dayReqHrsSoFar = dayReqHrsSoFar,
                dayHourlyRate = dayHourlyRate,
                dayDailyRate = dayDailyRate,
                dayDetails = dayDetails,
                employee = employee,
                dayAttendance = attendanceByDay[dateKey],
                closedDays = closedDays,
                context = context,
                now = now
            )
            
            tempCal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val stats = applySystematicLogic(
            employee, monthStart, monthEnd, monthCal, daysInMonth, 
            baseDetails, baseDetails.rulesOverride ?: rules, netScheduledHrsAtEnd, fullMonthRequiredHrs,
            pendingAdvance, history.size, context, todayMidnight, selDayStart,
            getDetailsForDay(selDayStart, employee, salaryHistory, allowanceHistory, shiftHistory),
            salaryHistory, allowanceHistory, shiftHistory
        )
        return Pair(stats, context)
    }

    private fun getDetailsForDay(
        timestamp: Long, 
        employee: Employee, 
        salaryHistory: List<EmployeeHistory>, 
        allowanceHistory: List<EmployeeHistory>,
        shiftHistory: List<EmployeeHistory>
    ): SalaryDetails {
        val appSalary = salaryHistory.asSequence()
            .lastOrNull { timestamp >= it.effectiveDate && (it.endDate == null || timestamp <= it.endDate!!) }
            ?: salaryHistory.lastOrNull { it.effectiveDate <= timestamp }
            ?: salaryHistory.firstOrNull()

        val appAllowance = allowanceHistory.asSequence()
            .lastOrNull { timestamp >= it.effectiveDate && (it.endDate == null || timestamp <= it.endDate!!) }
            ?: allowanceHistory.lastOrNull { it.effectiveDate <= timestamp }
            ?: allowanceHistory.firstOrNull()

        val appShift = shiftHistory.asSequence()
            .lastOrNull { timestamp >= it.effectiveDate && (it.endDate == null || timestamp <= it.endDate!!) }
            ?: shiftHistory.find { it.effectiveDate >= timestamp } // Try to find the closest future record if viewing historical data
            ?: shiftHistory.firstOrNull()

        val rulesOverride = appSalary?.rulesOverrideJson?.let {
            try { gson.fromJson(it, SalaryRules::class.java) } catch (_: Exception) { null }
        }

        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val isWeekend = cal[Calendar.DAY_OF_WEEK] == Calendar.SATURDAY || cal[Calendar.DAY_OF_WEEK] == Calendar.SUNDAY

        val finalShiftStart = if (isWeekend && employee.weekendShiftStart != null) employee.weekendShiftStart!! else (appShift?.shiftStart ?: appSalary?.shiftStart ?: employee.shiftStart)
        val finalShiftEnd = if (isWeekend && employee.weekendShiftEnd != null) employee.weekendShiftEnd!! else (appShift?.shiftEnd ?: appSalary?.shiftEnd ?: employee.shiftEnd)
        val finalShift2Start = if (isWeekend && employee.weekendShift2Start != null) employee.weekendShift2Start else (appShift?.shift2Start ?: appSalary?.shift2Start ?: employee.shift2Start)
        val finalShift2End = if (isWeekend && employee.weekendShift2End != null) employee.weekendShift2End else (appShift?.shift2End ?: appSalary?.shift2End ?: employee.shift2End)
        val finalBreakHours = if (isWeekend && employee.weekendBreakHours != null) employee.weekendBreakHours!! else (appShift?.breakHours ?: appSalary?.breakHours ?: employee.breakHours)

        return SalaryDetails(
            salaryType = appSalary?.salaryType ?: employee.salaryType,
            salaryRate = BigDecimal.valueOf(appSalary?.newValue ?: employee.salaryRate),
            dailyAllowance = BigDecimal.valueOf(appAllowance?.newValue ?: employee.dailyAllowance),
            shiftStart = finalShiftStart,
            shiftEnd = finalShiftEnd,
            shift2Start = finalShift2Start,
            shift2End = finalShift2End,
            breakHours = finalBreakHours,
            isBonusEligible = appSalary?.isBonusEligible ?: employee.isBonusEligibleRule,
            isPaidLeaveEligible = appSalary?.isPaidLeaveEligible ?: employee.isPaidLeaveEligibleRule,
            plWeekdays = appSalary?.paidLeaveOnWeekdays ?: employee.paidLeaveOnWeekdays,
            plWeekends = appSalary?.paidLeaveOnWeekends ?: employee.paidLeaveOnWeekends,
            rulesOverride = rulesOverride ?: employee.salaryRulesOverride
        )
    }

    private fun processDayAttendance(
        dayMidnight: Long, isSelectedDay: Boolean, isEmployed: Boolean, 
        isClosed: Boolean, dayReqHrsSoFar: Double, dayHourlyRate: BigDecimal, dayDailyRate: BigDecimal,
        dayDetails: SalaryDetails, employee: Employee, dayAttendance: List<Attendance>?,
        closedDays: List<ShopClosedDay>, context: ProcessingContext, now: Long
    ) {
        if (dayAttendance != null) {
            var dayWorkedHrsInShift = 0.0
            var dayOTHrsOutsideShift = 0.0
            var hasWorkRecord = false
            
            val shiftStartTs = getTimestampForTime(dayMidnight, dayDetails.shiftStart)
            var shiftEndTs = getTimestampForTime(dayMidnight, dayDetails.shiftEnd)
            if (shiftEndTs <= shiftStartTs) shiftEndTs += 86400000L

            val shift2StartTs = dayDetails.shift2Start?.let { getTimestampForTime(dayMidnight, it) }
            var shift2EndTs = dayDetails.shift2End?.let { getTimestampForTime(dayMidnight, it) }
            if (shift2StartTs != null && shift2EndTs != null) {
                if (shift2EndTs <= shift2StartTs) shift2EndTs += 86400000L
            }

            val sortedDayAttendance = dayAttendance.filter { it.shopId == employee.shopId }.sortedBy { it.checkInTime }

            sortedDayAttendance.forEach { att ->
                val checkIn = att.checkInTime
                val checkOut = att.checkOutTime ?: now

                // Primary Shift Overlap
                val s1OverlapStart = max(checkIn, shiftStartTs)
                val s1OverlapEnd = min(checkOut, shiftEndTs)
                val s1OverlapHrs = if (s1OverlapEnd > s1OverlapStart) (s1OverlapEnd - s1OverlapStart) / 3600000.0 else 0.0

                // Secondary Shift Overlap
                var s2OverlapHrs = 0.0
                if (shift2StartTs != null && shift2EndTs != null) {
                    val s2OverlapStart = max(checkIn, shift2StartTs)
                    val s2OverlapEnd = min(checkOut, shift2EndTs)
                    s2OverlapHrs = if (s2OverlapEnd > s2OverlapStart) (s2OverlapEnd - s2OverlapStart) / 3600000.0 else 0.0
                }

                val totalOverlapHrs = s1OverlapHrs + s2OverlapHrs
                val totalDuration = (checkOut - checkIn) / 3600000.0
                val totalOTHrs = (totalDuration - totalOverlapHrs).coerceAtLeast(0.0)

                if (att.type == "WORK") {
                    hasWorkRecord = true
                    dayWorkedHrsInShift += totalOverlapHrs
                    dayOTHrsOutsideShift += totalOTHrs

                    // Late Arrival Detection
                    if (att == sortedDayAttendance.firstOrNull { it.type == "WORK" }) {
                        if (att.checkInTime > (shiftStartTs + 59000)) { // 1 min buffer
                            val lateMins = (att.checkInTime - shiftStartTs) / 60000
                            val hrs = lateMins / 60.0
                            val amt = dayHourlyRate.multiply(BigDecimal.valueOf(hrs))
                            val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(att.checkInTime))
                            context.deductionEvents.add(DeductionEvent("LATE", dayMidnight, hrs, hrs, amt, amt, "Late Arrival", "Delayed by $lateMins mins (In: $timeStr | Shift: ${dayDetails.shiftStart})", att.checkInTime))
                        }
                    }
                    
                    // Early Leave Detection
                    if (att == sortedDayAttendance.lastOrNull { it.type == "WORK" }) {
                        val finalShiftEnd = shift2EndTs ?: shiftEndTs
                        if (checkOut < (finalShiftEnd - 59000)) {
                            val earlyMins = (finalShiftEnd - checkOut) / 60000
                            val hrs = earlyMins / 60.0
                            val amt = dayHourlyRate.multiply(BigDecimal.valueOf(hrs))
                            val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(checkOut))
                            context.deductionEvents.add(DeductionEvent("EARLY", dayMidnight, hrs, hrs, amt, amt, "Early Leave", "Left $earlyMins mins early (Out: $timeStr | Shift: ${dayDetails.shift2End ?: dayDetails.shiftEnd})", checkOut))
                        }
                    }

                } else if (att.type == "GAP") {
                    dayWorkedHrsInShift -= totalOverlapHrs
                    dayOTHrsOutsideShift -= totalOTHrs
                    
                    val hrs = abs(att.hoursWorked)
                    val amt = dayHourlyRate.multiply(BigDecimal.valueOf(hrs))
                    val inTimeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(att.checkInTime))
                    val outTimeStr = att.checkOutTime?.let { SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it)) } ?: "..."
                    context.deductionEvents.add(DeductionEvent("GAP", dayMidnight, hrs, hrs, amt, amt, "Shift Gap", "Missed %.1f h ($inTimeStr - $outTimeStr)".format(hrs), att.checkInTime))
                }
                if (isSelectedDay && att.checkOutTime == null) context.isCheckedIn = true
            }
            
            val normalHrs = (dayWorkedHrsInShift - dayDetails.breakHours).coerceAtLeast(0.0)
            val otHrs = dayOTHrsOutsideShift.coerceAtLeast(0.0)
            
            if (hasWorkRecord) {
                if (isEmployed) context.presentDaysCount++
            } else if (isEmployed && !isClosed) {
                context.personalAbsenceDays.add(dayMidnight)
            }
            
            context.totalNormalWorkedHours += normalHrs
            context.totalOTHours += otHrs

            if (isEmployed && !isClosed) {
                val shortfall = (dayReqHrsSoFar - normalHrs).coerceAtLeast(0.0)
                if (shortfall > 0.001) context.dayShortfalls[dayMidnight] = shortfall
            }
            
            val dayNormalSal = dayHourlyRate.multiply(BigDecimal.valueOf(normalHrs))
            val dayOTSal = dayHourlyRate.multiply(BigDecimal.valueOf(otHrs * employee.otRateMultiplier))
            context.dayWiseEarnings[dayMidnight] = dayNormalSal
            context.dayWiseOTEarnings[dayMidnight] = dayOTSal
            context.dayWiseOTHours[dayMidnight] = otHrs
            context.dayWiseWorkedHours[dayMidnight] = normalHrs + otHrs
            context.dayWiseAllowances[dayMidnight] = dayDetails.dailyAllowance
            context.totalAllowanceMoney = context.totalAllowanceMoney.add(dayDetails.dailyAllowance)

            if (otHrs > 0.01) {
                context.otEvents.add(SalaryActivityItem("OT", "Overtime Worked", "Worked %.1f h (Earned ₹%.2f)".format(otHrs, dayOTSal.toDouble()), dayMidnight, otHrs, otHrs, dayOTSal, dayOTSal))
            }
            if (dayDetails.dailyAllowance > BigDecimal.ZERO) {
                context.allowanceEvents.add(SalaryActivityItem("ALLOWANCE", "Daily Allowance", "₹%.2f given".format(dayDetails.dailyAllowance.toDouble()), dayMidnight, 0.0, 0.0, dayDetails.dailyAllowance, dayDetails.dailyAllowance))
            }

            if (isSelectedDay) {
                context.isPresentToday = hasWorkRecord
                context.selectedDayAllowance = dayDetails.dailyAllowance
                context.selectedDayOTHours = otHrs 
                context.selectedDayNormalWorkedHours = normalHrs
                context.selectedDayNormalWorkedSalary = dayNormalSal
                context.selectedDayOTSalary = dayOTSal
            }
        } else if (isClosed) {
            if (isEmployed) {
                context.closedShopDaysCount++
                val closedDayObj = closedDays.find { getMidnight(it.date) == dayMidnight }
                if (closedDayObj?.paySalary == true) {
                    context.paidClosedDaysSalary = context.paidClosedDaysSalary.add(dayDailyRate)
                    context.paidClosedDaysHours += getShiftHours(
                        dayDetails.shiftStart, 
                        dayDetails.shiftEnd, 
                        dayDetails.breakHours,
                        dayDetails.shift2Start,
                        dayDetails.shift2End
                    )
                    context.dayWiseEarnings[dayMidnight] = (context.dayWiseEarnings[dayMidnight] ?: BigDecimal.ZERO).add(dayDailyRate)
                }
            }
        } else if (isEmployed) {
            context.personalAbsenceDays.add(dayMidnight)
            context.dayShortfalls[dayMidnight] = dayReqHrsSoFar
        }
    }

    private fun applySystematicLogic(
        employee: Employee, monthStart: Long, monthEnd: Long, monthCal: Calendar, daysInMonth: Int,
        baseDetails: SalaryDetails, rules: SalaryRules, netScheduledHrsAtEnd: Double, 
        fullMonthRequiredHrs: Double, pendingAdvance: Double, versionCount: Int, 
        context: ProcessingContext, todayMidnight: Long, selDayStart: Long,
        selDayDetails: SalaryDetails,
        salaryHistory: List<EmployeeHistory>,
        allowanceHistory: List<EmployeeHistory>,
        shiftHistory: List<EmployeeHistory>
    ): EmployeeStats {
        val hireCal = Calendar.getInstance().apply { timeInMillis = employee.hireDate }
        val isNewJoineePostCutoff = (hireCal[Calendar.YEAR] == monthCal[Calendar.YEAR]) &&
                                     (hireCal[Calendar.MONTH] == monthCal[Calendar.MONTH]) &&
                                     (hireCal[Calendar.DAY_OF_MONTH] > rules.newJoineeCutoffDay)

        val totalFullDayAbsences = context.personalAbsenceDays.size
        
        // Populate Full Day Leave Events
        context.personalAbsenceDays.sorted().forEach { dayTime ->
            val dayDetails = getDetailsForDay(dayTime, employee, salaryHistory, allowanceHistory, shiftHistory)
            val dayNetScheduledHrs = getShiftHours(dayDetails.shiftStart, dayDetails.shiftEnd, dayDetails.breakHours, dayDetails.shift2Start, dayDetails.shift2End)
            val dayHourlyRate = if (dayDetails.salaryType == "MONTHLY_FIXED") {
                if (dayNetScheduledHrs > 0) dayDetails.salaryRate.divide(BigDecimal.valueOf(daysInMonth.toLong()), 10, RoundingMode.HALF_UP).divide(BigDecimal.valueOf(dayNetScheduledHrs), 10, RoundingMode.HALF_UP) else BigDecimal.ZERO
            } else dayDetails.salaryRate
            val amt = dayHourlyRate.multiply(BigDecimal.valueOf(dayNetScheduledHrs))
            context.deductionEvents.add(DeductionEvent("LEAVE", dayTime, dayNetScheduledHrs, dayNetScheduledHrs, amt, amt, "Full Day Leave", "Absent from work", dayTime))
        }

        val isAutoPaidLeaveEligible = !isNewJoineePostCutoff && baseDetails.isPaidLeaveEligible && totalFullDayAbsences <= rules.maxAbsencesForPaidLeave
        val monthKey = "${monthCal[Calendar.YEAR]}_${monthCal[Calendar.MONTH] + 1}"
        val manualPLEligible = employee.monthlyPaidLeaveOverrides[monthKey]
        val isEligiblePL = manualPLEligible ?: isAutoPaidLeaveEligible

        var totalPaidLeaveHours = 0.0
        var plHoursRemaining = if (isEligiblePL) netScheduledHrsAtEnd * rules.paidLeaveDaysPool else 0.0
        
        val hourlyRateAtEnd = if (fullMonthRequiredHrs > 0) {
            BigDecimal.valueOf(baseDetails.salaryRate.toDouble()).divide(BigDecimal.valueOf(fullMonthRequiredHrs), 10, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        // Distribute Paid Leave (Priority: LEAVE > GAP > LATE/EARLY)
        val sortedDeductions = context.deductionEvents.sortedWith(compareBy({ when(it.type) { "LEAVE" -> 0; "GAP" -> 1; else -> 2 } }, { it.dayMidnight }))
        sortedDeductions.forEach { event ->
            val c = Calendar.getInstance().apply { timeInMillis = event.dayMidnight }
            val dayDetails = getDetailsForDay(event.dayMidnight, employee, salaryHistory, allowanceHistory, shiftHistory)
            val isWeekend = c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
            val allowedPL = if (isWeekend) dayDetails.plWeekends else dayDetails.plWeekdays
            
            // Skip PL for partial shortfalls if 0 absences
            var skipPL = false
            if (totalFullDayAbsences == 0 && event.type != "LEAVE") {
                val dayNetScheduledHrs = getShiftHours(dayDetails.shiftStart, dayDetails.shiftEnd, dayDetails.breakHours, dayDetails.shift2Start, dayDetails.shift2End)
                if (event.totalHours < (dayNetScheduledHrs - 0.001)) skipPL = true
            }

            if (plHoursRemaining > 0 && allowedPL && !skipPL) {
                val cover = min(event.netHours, plHoursRemaining)
                val dayHourlyRate = if (dayDetails.salaryType == "MONTHLY_FIXED") {
                    val hrs = getShiftHours(dayDetails.shiftStart, dayDetails.shiftEnd, dayDetails.breakHours, dayDetails.shift2Start, dayDetails.shift2End)
                    if (hrs > 0) dayDetails.salaryRate.divide(BigDecimal.valueOf(daysInMonth.toLong()), 10, RoundingMode.HALF_UP).divide(BigDecimal.valueOf(hrs), 10, RoundingMode.HALF_UP) else BigDecimal.ZERO
                } else dayDetails.salaryRate
                val coverAmt = dayHourlyRate.multiply(BigDecimal.valueOf(cover))
                
                totalPaidLeaveHours += cover
                plHoursRemaining -= cover
                event.netHours -= cover
                event.netAmount = event.netAmount.subtract(coverAmt).coerceAtLeast(BigDecimal.ZERO)
                context.dayWisePaidLeave[event.dayMidnight] = (context.dayWisePaidLeave[event.dayMidnight] ?: BigDecimal.ZERO).add(coverAmt)
                context.dayWisePaidLeaveHours[event.dayMidnight] = (context.dayWisePaidLeaveHours[event.dayMidnight] ?: 0.0) + cover
            }
        }

        val shortfallThreshold = rules.maxShortfallMinutesForBonus / 60.0
        val isAutoBonus = !isNewJoineePostCutoff && baseDetails.isBonusEligible &&
                          totalFullDayAbsences <= rules.maxAbsencesForBonus &&
                          context.dayShortfalls.values.sum() < shortfallThreshold &&
                          context.presentDaysCount > 0

        val manualBonus = employee.monthlyBonusOverrides[monthKey]
        val finalBonusEligible: Boolean
        val finalPLEligible: Boolean

        val isForcedBonusByZeroAbsence = (totalFullDayAbsences == 0 && context.presentDaysCount > 0)

        when {
            totalPaidLeaveHours > 0.001 -> { finalBonusEligible = false; finalPLEligible = true }
            isAutoBonus || isForcedBonusByZeroAbsence -> { finalBonusEligible = true; finalPLEligible = manualPLEligible ?: false }
            else -> { finalBonusEligible = manualBonus ?: false; finalPLEligible = manualPLEligible ?: isAutoPaidLeaveEligible }
        }

        val bonusAmt = if (finalBonusEligible) {
            baseDetails.salaryRate.divide(BigDecimal.valueOf(daysInMonth.toLong()), 10, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO
        
        if (bonusAmt > BigDecimal.ZERO) {
            val bonusDay = if (context.isPresentToday && todayMidnight <= monthEnd) todayMidnight 
                           else (context.dayWiseWorkedHours.keys.filter { it <= monthEnd }.maxOrNull() ?: monthEnd)
            context.dayWiseBonus[bonusDay] = bonusAmt
            context.bonusEvents.add(SalaryActivityItem("BONUS", "Bonus Awarded", "₹%,.2f eligible".format(bonusAmt.toDouble()), bonusDay, 0.0, 0.0, bonusAmt, bonusAmt))
        }

        val totalWorkSal = context.dayWiseEarnings.values.fold(BigDecimal.ZERO) { acc, b -> acc.add(b) }
        val totalOTAmt = context.dayWiseOTEarnings.values.fold(BigDecimal.ZERO) { acc, b -> acc.add(b) }
        val plSalary = context.dayWisePaidLeave.values.fold(BigDecimal.ZERO) { acc, b -> acc.add(b) }
        
        var lateCount = 0; var earlyCount = 0; var gapCount = 0; var leaveCount = 0
        var totalLateHrs = 0.0; var totalEarlyHrs = 0.0; var totalGapHrs = 0.0; var totalLeaveHrs = 0.0
        var totalLateAmt = BigDecimal.ZERO; var totalEarlyAmt = BigDecimal.ZERO; var totalGapAmt = BigDecimal.ZERO; var totalLeaveAmt = BigDecimal.ZERO
        var netLateAmt = BigDecimal.ZERO; var netEarlyAmt = BigDecimal.ZERO; var netGapAmt = BigDecimal.ZERO; var netLeaveAmt = BigDecimal.ZERO
        
        context.deductionEvents.forEach { e ->
            when(e.type) {
                "LATE" -> { lateCount++; totalLateHrs += e.totalHours; totalLateAmt = totalLateAmt.add(e.totalAmount); netLateAmt = netLateAmt.add(e.netAmount) }
                "EARLY" -> { earlyCount++; totalEarlyHrs += e.totalHours; totalEarlyAmt = totalEarlyAmt.add(e.totalAmount); netEarlyAmt = netEarlyAmt.add(e.netAmount) }
                "GAP" -> { gapCount++; totalGapHrs += e.totalHours; totalGapAmt = totalGapAmt.add(e.totalAmount); netGapAmt = netGapAmt.add(e.netAmount) }
                "LEAVE" -> { leaveCount++; totalLeaveHrs += e.totalHours; totalLeaveAmt = totalLeaveAmt.add(e.totalAmount); netLeaveAmt = netLeaveAmt.add(e.netAmount) }
            }
        }
        
        val activityLog = (context.otEvents + context.bonusEvents + context.allowanceEvents + context.deductionEvents.map { e ->
            val netInfo = if (e.netHours < e.totalHours) " (Net: %.1f h)".format(e.netHours) else ""
            SalaryActivityItem(e.type, e.title, e.desc + netInfo + " | ₹%,.2f".format(e.totalAmount.toDouble()), e.timestamp, e.totalHours, e.netHours, e.totalAmount, e.netAmount)
        } + context.dayWisePaidLeave.map { (ts, amt) ->
            SalaryActivityItem("PAID_LEAVE", "Paid Leave used", "₹%,.2f covered".format(amt.toDouble()), ts, 0.0, 0.0, amt, amt)
        }).sortedByDescending { it.timestamp }

        var weekdayDeductAmt = BigDecimal.ZERO; var weekendDeductAmt = BigDecimal.ZERO
        var weekdayDeductHrs = 0.0; var weekendDeductHrs = 0.0
        context.deductionEvents.forEach { e ->
            val dayOfWeek = Calendar.getInstance().apply { timeInMillis = e.dayMidnight }.get(Calendar.DAY_OF_WEEK)
            if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) { weekendDeductHrs += e.netHours; weekendDeductAmt = weekendDeductAmt.add(e.netAmount) } else { weekdayDeductHrs += e.netHours; weekdayDeductAmt = weekdayDeductAmt.add(e.netAmount) }
        }

        val grossEarned = totalWorkSal.add(totalOTAmt).add(plSalary).add(bonusAmt).add(context.totalAllowanceMoney)
        val breakdown = PayrollEngine.calculateBreakdown(grossEarned.toDouble())

        return EmployeeStats(
            employee = employee, monthStart = monthStart, monthEnd = monthEnd, pendingAdvance = pendingAdvance,
            versionCount = versionCount, daysPresent = context.presentDaysCount, daysAbsent = totalFullDayAbsences,
            closedShopDays = context.closedShopDaysCount, totalNormalWorkedSalaryMonth = totalWorkSal, 
            monthlySalary = totalWorkSal.toDouble(), totalOTSalaryMonth = totalOTAmt, 
            paidLeaveSalary = plSalary, monthlyAllowance = context.totalAllowanceMoney, 
            bonusAmount = bonusAmt, deductibleAmount = weekdayDeductAmt.add(weekendDeductAmt), 
            weekdayDeductibleHours = weekdayDeductHrs, weekdayDeductibleAmount = weekdayDeductAmt,
            weekendDeductibleHours = weekendDeductHrs, weekendDeductibleAmount = weekendDeductAmt,
            payrollBreakdown = breakdown,
            requiredWorkHours = context.upToDateRequiredHrs + context.paidClosedDaysHours, 
            totalNormalWorkedHoursMonth = context.totalNormalWorkedHours + context.paidClosedDaysHours,
            totalOTHoursMonth = context.totalOTHours, shortfallHours = (context.upToDateRequiredHrs - (context.totalNormalWorkedHours + totalPaidLeaveHours)).coerceAtLeast(0.0), 
            shortfallDays = if (netScheduledHrsAtEnd > 0) (context.upToDateRequiredHrs - (context.totalNormalWorkedHours + totalPaidLeaveHours)).coerceAtLeast(0.0) / netScheduledHrsAtEnd else 0.0,
            paidLeaveAppliedHours = totalPaidLeaveHours, isPresentToday = context.isPresentToday, isCheckedIn = context.isCheckedIn,
            selectedDayWorkedHours = context.selectedDayNormalWorkedHours + context.selectedDayOTHours,
            selectedDayNormalWorkedHours = context.selectedDayNormalWorkedHours,
            selectedDayOTHours = context.selectedDayOTHours,
            selectedDayNormalWorkedSalary = context.selectedDayNormalWorkedSalary,
            selectedDayOTSalary = context.selectedDayOTSalary,
            selectedDayAllowance = context.selectedDayAllowance,
            selectedDayWorkedSalary = context.selectedDayNormalWorkedSalary.add(context.selectedDayOTSalary),
            fullMonthSalary = baseDetails.salaryRate.toDouble(), 
            selectedDaySalaryRate = selDayDetails.salaryRate.toDouble(),
            isBonusEligible = finalBonusEligible,
            isPaidLeaveEligible = finalPLEligible, isBonusEligibleByRule = isAutoBonus,
            isPaidLeaveEligibleByRule = isAutoPaidLeaveEligible, salaryType = selDayDetails.salaryType,
            shiftStart = selDayDetails.shiftStart, shiftEnd = selDayDetails.shiftEnd,
            shift2Start = selDayDetails.shift2Start, shift2End = selDayDetails.shift2End,
            hourlyRate = hourlyRateAtEnd.toDouble(), netShiftHours = getShiftHours(selDayDetails.shiftStart, selDayDetails.shiftEnd, selDayDetails.breakHours, selDayDetails.shift2Start, selDayDetails.shift2End),
            dayWiseEarnings = context.dayWiseEarnings, dayWiseOTEarnings = context.dayWiseOTEarnings,
            dayWiseAllowances = context.dayWiseAllowances, dayWiseWorkedHours = context.dayWiseWorkedHours,
            dayWiseBonus = context.dayWiseBonus, dayWisePaidLeave = context.dayWisePaidLeave,
            dayWisePaidLeaveHours = context.dayWisePaidLeaveHours,
            dayWiseOTHours = context.dayWiseOTHours,
            totalHoursMonth = context.totalNormalWorkedHours + context.totalOTHours,
            selectedDayHours = context.selectedDayNormalWorkedHours + context.selectedDayOTHours,
            selectedDaySalary = context.selectedDayNormalWorkedSalary.add(context.selectedDayOTSalary).add(context.selectedDayAllowance).toDouble(),
            selectedDayMidnight = if (selDayStart > 0) selDayStart else -1L,
            lateCount = lateCount, earlyCount = earlyCount, gapCount = gapCount, leaveCount = leaveCount,
            totalLateHours = totalLateHrs, totalEarlyHours = totalEarlyHrs, totalGapHours = totalGapHrs, totalLeaveHours = totalLeaveHrs,
            totalLateAmount = totalLateAmt, totalEarlyAmount = totalEarlyAmt, totalGapAmount = totalGapAmt, totalLeaveAmount = totalLeaveAmt,
            netLateAmount = netLateAmt, netEarlyAmount = netEarlyAmt, netGapAmount = netGapAmt, netLeaveAmount = netLeaveAmt,
            otCount = context.otEvents.size,
            paidLeaveCount = context.dayWisePaidLeaveHours.values.count { it > 0.01 },
            allowanceCount = context.allowanceEvents.size,
            bonusCount = context.bonusEvents.size,
            activityLog = activityLog
        )
    }

    fun createSnapshot(employeeId: String, shopId: String, periodStart: Long, periodEnd: Long, context: Any): SalarySnapshot {
        val ctx = context as ProcessingContext
        return SalarySnapshot(
            snapshotId = "${employeeId}_${shopId}_$periodEnd",
            employeeId = employeeId,
            shopId = shopId,
            periodStart = periodStart,
            periodEnd = periodEnd,
            totalNormalWorkedHours = ctx.totalNormalWorkedHours,
            totalOTHours = ctx.totalOTHours,
            presentDaysCount = ctx.presentDaysCount,
            closedShopDaysCount = ctx.closedShopDaysCount,
            totalAllowanceMoney = ctx.totalAllowanceMoney.toDouble(),
            paidClosedDaysSalary = ctx.paidClosedDaysSalary.toDouble(),
            paidClosedDaysHours = ctx.paidClosedDaysHours,
            upToDateRequiredHrs = ctx.upToDateRequiredHrs,
            dayWiseEarningsJson = gson.toJson(ctx.dayWiseEarnings),
            dayWiseOTEarningsJson = gson.toJson(ctx.dayWiseOTEarnings),
            dayWiseAllowancesJson = gson.toJson(ctx.dayWiseAllowances),
            dayWiseWorkedHoursJson = gson.toJson(ctx.dayWiseWorkedHours),
            dayWiseBonusJson = gson.toJson(ctx.dayWiseBonus),
            dayWisePaidLeaveJson = gson.toJson(ctx.dayWisePaidLeave),
            dayShortfallsJson = gson.toJson(ctx.dayShortfalls),
            personalAbsenceDaysJson = gson.toJson(ctx.personalAbsenceDays)
        )
    }

    internal class ProcessingContext {
        val dayWiseEarnings = mutableMapOf<Long, BigDecimal>()
        val dayWiseOTEarnings = mutableMapOf<Long, BigDecimal>()
        val dayWiseOTHours = mutableMapOf<Long, Double>()
        val dayWiseAllowances = mutableMapOf<Long, BigDecimal>()
        val dayWiseWorkedHours = mutableMapOf<Long, Double>()
        val dayWiseBonus = mutableMapOf<Long, BigDecimal>()
        val dayWisePaidLeave = mutableMapOf<Long, BigDecimal>()
        val dayWisePaidLeaveHours = mutableMapOf<Long, Double>()
        val dayShortfalls = mutableMapOf<Long, Double>()
        val personalAbsenceDays = mutableListOf<Long>()
        var totalNormalWorkedHours = 0.0
        var totalOTHours = 0.0
        var presentDaysCount = 0
        var closedShopDaysCount = 0
        var totalAllowanceMoney = BigDecimal.ZERO
        var paidClosedDaysSalary = BigDecimal.ZERO
        var paidClosedDaysHours = 0.0
        var upToDateRequiredHrs = 0.0
        var isPresentToday = false
        var isCheckedIn = false
        var selectedDayNormalWorkedHours = 0.0
        var selectedDayOTHours = 0.0
        var selectedDayNormalWorkedSalary = BigDecimal.ZERO
        var selectedDayOTSalary = BigDecimal.ZERO
        var selectedDayAllowance = BigDecimal.ZERO

        val deductionEvents = mutableListOf<DeductionEvent>()
        val otEvents = mutableListOf<SalaryActivityItem>()
        val allowanceEvents = mutableListOf<SalaryActivityItem>()
        val bonusEvents = mutableListOf<SalaryActivityItem>()
    }

    private fun getMidnight(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun getTimestampForTime(dayMidnight: Long, timeStr: String): Long {
        val parts = timeStr.split(":")
        return Calendar.getInstance().apply {
            timeInMillis = dayMidnight
            set(Calendar.HOUR_OF_DAY, parts.getOrNull(0)?.toInt() ?: 0)
            set(Calendar.MINUTE, parts.getOrNull(1)?.toInt() ?: 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun getShiftHours(start: String, end: String, breakHrs: Double, start2: String? = null, end2: String? = null): Double {
        val sParts = start.split(":")
        val eParts = end.split(":")
        val startDecimal = (sParts.getOrNull(0)?.toInt() ?: 0) + (sParts.getOrNull(1)?.toInt() ?: 0) / 60.0
        var endDecimal = (eParts.getOrNull(0)?.toInt() ?: 0) + (eParts.getOrNull(1)?.toInt() ?: 0) / 60.0
        if (endDecimal < startDecimal) endDecimal += 24.0
        
        val shift1Hrs = endDecimal - startDecimal
        
        var shift2Hrs = 0.0
        if (start2 != null && end2 != null) {
            val s2Parts = start2.split(":")
            val e2Parts = end2.split(":")
            val s2Decimal = (s2Parts.getOrNull(0)?.toInt() ?: 0) + (s2Parts.getOrNull(1)?.toInt() ?: 0) / 60.0
            var e2Decimal = (e2Parts.getOrNull(0)?.toInt() ?: 0) + (e2Parts.getOrNull(1)?.toInt() ?: 0) / 60.0
            if (e2Decimal < s2Decimal) e2Decimal += 24.0
            shift2Hrs = e2Decimal - s2Decimal
        }
        
        return (shift1Hrs + shift2Hrs - breakHrs).coerceAtLeast(0.0)
    }

    private fun getShiftStartDecimal(shiftStart: String): Double {
        val parts = shiftStart.split(":")
        return (parts.getOrNull(0)?.toInt() ?: 0) + (parts.getOrNull(1)?.toInt() ?: 0) / 60.0
    }
}
