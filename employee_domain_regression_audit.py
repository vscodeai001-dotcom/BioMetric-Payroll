#!/usr/bin/env python3
"""Static regression audit for the 1100 Employee Web/Firebase/Android boundary."""
from pathlib import Path
import json, re, sys

ROOT = Path(__file__).resolve().parents[2]
checks = []

def require(label, path, pattern):
    text = (ROOT / path).read_text(encoding='utf-8', errors='ignore')
    ok = re.search(pattern, text, re.I | re.M) is not None
    checks.append((label, ok))
    return ok

def forbid(label, path, pattern):
    text = (ROOT / path).read_text(encoding='utf-8', errors='ignore')
    ok = re.search(pattern, text, re.I | re.M) is None
    checks.append((label, ok))
    return ok

# Web SSOT/read path
require('Web Employee List uses Firebase service', 'Web/Payroll.Web/Components/Pages/Employees/EmployeeList.razor', r'FirebaseEmployeeManagementService')
require('Web Employee List has realtime Employee filter', 'Web/Payroll.Web/Components/Pages/Employees/EmployeeList.razor', r'EntityFilter="Employee"')
require('Web Employee List has offline cache fallback', 'Web/Payroll.Web/Components/Pages/Employees/EmployeeList.razor', r'TryLoadEmployeeCacheAsync')
require('Web Employee service has duplicate fingerprinting', 'Web/Payroll.Web/Services/FirebaseEmployeeManagementService.cs', r'SHA256|fingerprint')
require('Web Employee service emits revision metadata', 'Web/Payroll.Web/Services/FirebaseEmployeeManagementService.cs', r'_revision')

# Android employee scoping
android_path = ROOT / 'Android/app/src/main/java/com/biometric/app/data/repository/FirebaseEmployeeSelfServiceRepository.kt'
android_text = android_path.read_text(encoding='utf-8', errors='ignore')
checks.append(('Android direct employee lookup', 'employeesRef.child(id)' in android_text))
checks.append(('Android employee realtime is record-scoped', 'child("employees")?.child(employeeKey)' in android_text or 'child("employees").child(employeeKey)' in android_text))
checks.append(('Android Employee runtime does not enumerate employees as fallback', '.child("employees").get()' not in android_text))

# Firebase rules
rules = json.loads((ROOT / 'Android/database.rules.json').read_text(encoding='utf-8'))
employees = rules['rules']['owners']['$uid']['employees']
checks.append(('Employee rules contain admin write boundary', '.write' in employees and 'Admin' in employees['.write']))
checks.append(('Employee rules contain employee self-read boundary', '$employeeId' in employees and 'auth.token.employee_id' in employees['$employeeId']['.read']))
checks.append(('Employee rules validate employeeId/key', '$employeeId' in employees and 'employeeId' in employees['$employeeId']['.validate']))

# No broad Employee entity migration in calculation/mutation paths is asserted by audit docs.
doc = (ROOT / 'docs/1100/1100-I_EMPLOYEE_SQL_DEPENDENCY_AUDIT.md').read_text(encoding='utf-8', errors='ignore')
checks.append(('SQL calculation/mutation boundaries explicitly preserved', 'calculation' in doc.lower() and 'mutation' in doc.lower()))

failed = [label for label, ok in checks if not ok]
print(f'1100-T Employee regression audit: {len(checks)-len(failed)}/{len(checks)} checks passed')
for label, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' | ' + label)
if failed:
    print('\nFAILED:')
    for x in failed: print(' - ' + x)
    sys.exit(1)
