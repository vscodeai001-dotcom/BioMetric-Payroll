using System;
using System.Collections.Generic;
using System.Linq;
using Payroll.Shared;
using Payroll.Shared.Data;
using Payroll.Shared.Services;

namespace Payroll.Web.Models;

public class EvaluatedPunchRecord
{
    public int LogId { get; set; }
    public int EmployeeId { get; set; }
    public string EmployeeName { get; set; } = string.Empty;
    public string? Role { get; set; }
    public DateTime PunchTime { get; set; }
    public DateOnly PunchDate => DateOnly.FromDateTime(PunchTime.Date);
    public string RawDirection { get; set; } = string.Empty;
    public bool IsIn => AttendancePunchProcessor.IsExplicitInPunch(new AttendanceLog { LogType = RawDirection });
    public bool IsOut => AttendancePunchProcessor.IsExplicitOutPunch(new AttendanceLog { LogType = RawDirection });
    public PunchSourceTier SourceTier { get; set; }
    public string DeviceId { get; set; } = string.Empty;
    public string BiometricId { get; set; } = string.Empty;
    public bool IsAccepted { get; set; }
    public string EvaluationStatus { get; set; } = string.Empty;
    public string Reason { get; set; } = string.Empty;
    public string RuleCategory { get; set; } = string.Empty;
    public int SequenceOrder { get; set; }
}

public class DailyEmployeePunchSummary
{
    public int EmployeeId { get; set; }
    public string EmployeeName { get; set; } = string.Empty;
    public DateOnly Date { get; set; }
    public TimeOnly? ShiftStart { get; set; }
    public TimeOnly? ShiftEnd { get; set; }
    public List<EvaluatedPunchRecord> RawPunches { get; set; } = new();
    public List<EvaluatedPunchRecord> AcceptedPunches => RawPunches.Where(p => p.IsAccepted).OrderBy(p => p.PunchTime).ToList();
    public List<EvaluatedPunchRecord> SuppressedPunches => RawPunches.Where(p => !p.IsAccepted).OrderBy(p => p.PunchTime).ToList();
    public double TotalValidHoursWorked { get; set; }
    public double OvertimeHours { get; set; }
    public string ShiftName => ShiftStart.HasValue && ShiftEnd.HasValue ? $"{ShiftStart:HH:mm} - {ShiftEnd:HH:mm}" : "Standard Shift";
}
