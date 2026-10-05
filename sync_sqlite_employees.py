import sqlite3

conn = sqlite3.connect(r'Web\Payroll.Web\data\biometricpayroll-cache.db')
cur = conn.cursor()

# Clear residual daily summaries and attendance logs
cur.execute('DELETE FROM daily_summaries;')
cur.execute('DELETE FROM attendancelogs;')

# Insert employees 1 and 2 if not exists
cur.execute('DELETE FROM Employees;')
cur.execute('''
INSERT INTO Employees (
    employeeid, name, dob, role, monthlysalary, basic_salary_component, hra_component, da_component,
    standardhours, ot_rule, ot_flatrate, biometricid, standardbreakminutes, shiftstarttime, shiftendtime,
    shift_mode, tracking_mode, HireDate, TerminationDate, comp_off_day, Email, payroll_type_override,
    SalaryCalculationMethod, DirectHourlyWage, AspNetUserId, tenant_id, enable_pf, enable_esi,
    uan_number, esi_number, PaidLeaveBalance, SickLeaveBalance, NightShiftAllowance, tds_rate_percent,
    is_deleted, enable_shift_rotation, rotation_group, shift_rotation_pattern, current_shift_index
) VALUES (
    1, 'Nevetha S', '1994-06-16', 'Manager', 56244.0, 0.0, 0.0, 0.0,
    8.0, 'No Overtime', 0.0, '101', 60, '09:00:00', '18:00:00',
    'SINGLE_DAY', 'ALWAYS_ON', '2026-10-01', NULL, 0, 'nevetha16061994@gmail.com', 'MONTHLY_FIXED',
    'Days in Month', 0.0, NULL, 'tenant_2001', 0, 0,
    NULL, NULL, 0.0, 0.0, 0.0, 0.0,
    0, 0, NULL, NULL, 0
);
''')

cur.execute('''
INSERT INTO Employees (
    employeeid, name, dob, role, monthlysalary, basic_salary_component, hra_component, da_component,
    standardhours, ot_rule, ot_flatrate, biometricid, standardbreakminutes, shiftstarttime, shiftendtime,
    shift_mode, tracking_mode, HireDate, TerminationDate, comp_off_day, Email, payroll_type_override,
    SalaryCalculationMethod, DirectHourlyWage, AspNetUserId, tenant_id, enable_pf, enable_esi,
    uan_number, esi_number, PaidLeaveBalance, SickLeaveBalance, NightShiftAllowance, tds_rate_percent,
    is_deleted, enable_shift_rotation, rotation_group, shift_rotation_pattern, current_shift_index
) VALUES (
    2, 'Prakash J', '2001-01-01', 'Supervisor', 12454.0, 0.0, 0.0, 0.0,
    8.0, 'No Overtime', 0.0, '102', 60, '05:00:00', '18:00:00',
    'SINGLE_DAY', 'ALWAYS_ON', '2026-10-01', NULL, 3, 'prakashshiva368@hotmail.com', 'PER_HOUR',
    'Days in Month', 0.0, NULL, 'tenant_2001', 0, 0,
    NULL, NULL, 0.0, 0.0, 0.0, 0.0,
    0, 0, NULL, NULL, 0
);
''')

conn.commit()
print("SQLite updated:")
print("  Employees count:", cur.execute('SELECT COUNT(*) FROM Employees').fetchone()[0])
print("  daily_summaries count:", cur.execute('SELECT COUNT(*) FROM daily_summaries').fetchone()[0])
conn.close()
