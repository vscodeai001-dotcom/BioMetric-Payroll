from pathlib import Path
root=Path('/mnt/data/work1100g')
# Controllers
p=root/'Web/Payroll.Web/Controllers/MobileAdminAttendanceController.cs'; s=p.read_text();
s=s.replace('    private readonly IDbContextFactory<AppDbContext> _dbFactory;\n    public MobileAdminAttendanceController(IDbContextFactory<AppDbContext> dbFactory) => _dbFactory = dbFactory;', '    private readonly IDbContextFactory<AppDbContext> _dbFactory;\n    private readonly FirebaseEmployeeManagementService _firebaseEmployees;\n    public MobileAdminAttendanceController(IDbContextFactory<AppDbContext> dbFactory, FirebaseEmployeeManagementService firebaseEmployees)\n    { _dbFactory = dbFactory; _firebaseEmployees = firebaseEmployees; }')
s=s.replace('var employees = await db.Employees.AsNoTracking().Where(e => !e.IsDeleted && (employeeId <= 0 || e.EmployeeID == employeeId)).OrderBy(e => e.Name).ToListAsync();', 'var employees = (await _firebaseEmployees.GetEmployeesAsync(HttpContext.RequestAborted))\n            .Where(e => !e.IsDeleted && (employeeId <= 0 || e.EmployeeID == employeeId))\n            .OrderBy(e => e.Name).ToList();')
p.write_text(s)

p=root/'Web/Payroll.Web/Controllers/MobileAdminPunchController.cs'; s=p.read_text();
s=s.replace('    private readonly AttendanceRefreshService _refresh;\n', '    private readonly AttendanceRefreshService _refresh;\n    private readonly FirebaseEmployeeManagementService _firebaseEmployees;\n')
s=s.replace('        PayrollLockService lockService, AuditService audit, AttendanceRefreshService refresh)\n    { _dbFactory = dbFactory; _calculator = calculator; _lockService = lockService; _audit = audit; _refresh = refresh; }', '        PayrollLockService lockService, AuditService audit, AttendanceRefreshService refresh, FirebaseEmployeeManagementService firebaseEmployees)\n    { _dbFactory = dbFactory; _calculator = calculator; _lockService = lockService; _audit = audit; _refresh = refresh; _firebaseEmployees = firebaseEmployees; }')
s=s.replace('var employees = await db.Employees.AsNoTracking().Where(e => !e.IsDeleted && (employeeId <= 0 || e.EmployeeID == employeeId)).ToListAsync();', 'var employees = (await _firebaseEmployees.GetEmployeesAsync(HttpContext.RequestAborted))\n            .Where(e => !e.IsDeleted && (employeeId <= 0 || e.EmployeeID == employeeId)).ToList();')
s=s.replace('var names = await db.Employees.AsNoTracking().ToDictionaryAsync(x => x.EmployeeID, x => x.Name);', 'var names = (await _firebaseEmployees.GetEmployeesAsync(HttpContext.RequestAborted))\n            .Where(x => !x.IsDeleted).ToDictionary(x => x.EmployeeID, x => x.Name);')
p.write_text(s)

p=root/'Web/Payroll.Web/Controllers/MobileAdminLeaveController.cs'; s=p.read_text();
s=s.replace('    private readonly LeaveManagementService _leave;\n    public MobileAdminLeaveController(IDbContextFactory<AppDbContext> db, LeaveManagementService leave)\n    { _db = db; _leave = leave; }', '    private readonly LeaveManagementService _leave;\n    private readonly FirebaseEmployeeManagementService _firebaseEmployees;\n    public MobileAdminLeaveController(IDbContextFactory<AppDbContext> db, LeaveManagementService leave, FirebaseEmployeeManagementService firebaseEmployees)\n    { _db = db; _leave = leave; _firebaseEmployees = firebaseEmployees; }')
s=s.replace('var employees = await db.Employees.AsNoTracking().Where(e => !e.IsDeleted).ToDictionaryAsync(e => e.EmployeeID, e => e.Name);', 'var employees = (await _firebaseEmployees.GetEmployeesAsync(HttpContext.RequestAborted))\n            .Where(e => !e.IsDeleted).ToDictionary(e => e.EmployeeID, e => e.Name);')
s=s.replace('        await using var db = await _db.CreateDbContextAsync();\n        var name = await db.Employees.AsNoTracking().Where(e => e.EmployeeID == request.EmployeeId).Select(e => e.Name).FirstOrDefaultAsync() ?? "Employee";', '        var name = (await _firebaseEmployees.GetEmployeeAsync(request.EmployeeId, HttpContext.RequestAborted))?.Name ?? "Employee";')
p.write_text(s)

p=root/'Web/Payroll.Web/Controllers/MobileAdminPayrollController.cs'; s=p.read_text();
s=s.replace('    private readonly ILogger<MobileAdminPayrollController> _logger;\n', '    private readonly ILogger<MobileAdminPayrollController> _logger;\n    private readonly FirebaseEmployeeManagementService _firebaseEmployees;\n')
s=s.replace('        PayrollProcessorService processor,\n        ILogger<MobileAdminPayrollController> logger)', '        PayrollProcessorService processor,\n        ILogger<MobileAdminPayrollController> logger,\n        FirebaseEmployeeManagementService firebaseEmployees)')
s=s.replace('        _logger = logger;\n', '        _logger = logger;\n        _firebaseEmployees = firebaseEmployees;\n')
s=s.replace('var names = await db.Employees.AsNoTracking()\n            .Where(e => rows.Select(r => r.EmployeeID).Contains(e.EmployeeID))\n            .ToDictionaryAsync(e => e.EmployeeID, e => e.Name);', 'var names = (await _firebaseEmployees.GetEmployeesAsync(HttpContext.RequestAborted))\n            .Where(e => !e.IsDeleted && rows.Select(r => r.EmployeeID).Contains(e.EmployeeID))\n            .ToDictionary(e => e.EmployeeID, e => e.Name);')
# Finalize employee load is unused, remove it only
s=s.replace('            var employees = await db.Employees.AsNoTracking().Where(x => !x.IsDeleted).OrderBy(x => x.Name).ToListAsync();\n            var rows = request.Rows.Select', '            var rows = request.Rows.Select')
p.write_text(s)

p=root/'Web/Payroll.Web/Controllers/MobileAdminRegularizationController.cs'; s=p.read_text();
s=s.replace('    private readonly RegularizationService _service;\n\n    public MobileAdminRegularizationController(IDbContextFactory<AppDbContext> db, RegularizationService service)\n    { _db = db; _service = service; }', '    private readonly RegularizationService _service;\n    private readonly FirebaseEmployeeManagementService _firebaseEmployees;\n\n    public MobileAdminRegularizationController(IDbContextFactory<AppDbContext> db, RegularizationService service, FirebaseEmployeeManagementService firebaseEmployees)\n    { _db = db; _service = service; _firebaseEmployees = firebaseEmployees; }')
s=s.replace('var names = await db.Employees.AsNoTracking().Where(e => !e.IsDeleted)\n            .ToDictionaryAsync(e => e.EmployeeID, e => e.Name);', 'var names = (await _firebaseEmployees.GetEmployeesAsync(HttpContext.RequestAborted))\n            .Where(e => !e.IsDeleted).ToDictionary(e => e.EmployeeID, e => e.Name);')
p.write_text(s)

p=root/'Web/Payroll.Web/Controllers/MobileAdminShiftController.cs'; s=p.read_text();
s=s.replace('    private readonly RosteringService _rostering;\n    public MobileAdminShiftController(IDbContextFactory<AppDbContext> db, RosteringService rostering) { _db = db; _rostering = rostering; }', '    private readonly RosteringService _rostering;\n    private readonly FirebaseEmployeeManagementService _firebaseEmployees;\n    public MobileAdminShiftController(IDbContextFactory<AppDbContext> db, RosteringService rostering, FirebaseEmployeeManagementService firebaseEmployees) { _db = db; _rostering = rostering; _firebaseEmployees = firebaseEmployees; }')
s=s.replace('var employees = await db.Employees.AsNoTracking().Where(e => !e.IsDeleted).ToDictionaryAsync(e => e.EmployeeID, e => e.Name);', 'var employees = (await _firebaseEmployees.GetEmployeesAsync(HttpContext.RequestAborted))\n            .Where(e => !e.IsDeleted).ToDictionary(e => e.EmployeeID, e => e.Name);')
p.write_text(s)
