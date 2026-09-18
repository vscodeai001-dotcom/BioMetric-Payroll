#!/usr/bin/env python3
"""Static audit for 1200-Q Audit & Traceability."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
checks = []

def require(label, path, pattern):
    text = (ROOT / path).read_text(encoding="utf-8", errors="ignore")
    ok = re.search(pattern, text, re.I | re.M | re.S) is not None
    checks.append((label, ok))

def forbid(label, path, pattern):
    text = (ROOT / path).read_text(encoding="utf-8", errors="ignore")
    ok = re.search(pattern, text, re.I | re.M | re.S) is None
    checks.append((label, ok))

require("AuditService keeps SQL audit record authoritative", "Web/Payroll.Web/Services/AuditService.cs",
        r"_dbContext\.AuditLogs\.Add\(log\).*?SaveChangesAsync")
require("AuditService publishes owner-scoped Firebase audit", "Web/Payroll.Web/Services/AuditService.cs",
        r"ResolveOwnerUid\(userId,\s*role\).*?SetOwnerRecordAsync")
require("Firebase audit uses stable SQL LogID key", "Web/Payroll.Web/Services/AuditService.cs",
        r"log\.LogID\.ToString\(System\.Globalization\.CultureInfo\.InvariantCulture\)")
require("Audit event has correlation id", "Web/Payroll.Web/Services/AuditService.cs",
        r"correlationId")
require("Firebase audit failure cannot break committed operation", "Web/Payroll.Web/Services/AuditService.cs",
        r"catch \(Exception firebaseEx\).*?Audit event committed to SQL but Firebase publication failed")
require("Audit viewer reads SQL history", "Web/Payroll.Web/Components/Pages/Admin/AuditLogViewer.razor",
        r"db\.AuditLogs.*?ToListAsync")
require("Audit viewer reads Firebase audit history", "Web/Payroll.Web/Components/Pages/Admin/AuditLogViewer.razor",
        r"GetOwnerTableAsync\(ownerUid,\s*\"audit_logs\"\)")
require("Audit viewer merges both stores", "Web/Payroll.Web/Components/Pages/Admin/AuditLogViewer.razor",
        r"sqlLogs\s*\.Concat\(firebaseLogs\)")
require("Audit viewer deduplicates stable IDs", "Web/Payroll.Web/Components/Pages/Admin/AuditLogViewer.razor",
        r"GroupBy\(l => l\.LogID > 0")
require("Audit viewer tolerates Firebase read failure", "Web/Payroll.Web/Components/Pages/Admin/AuditLogViewer.razor",
        r"Firebase audit read failed; using SQL history")
require("Audit feature gate remains enforced", "Web/Payroll.Web/Services/AuditService.cs",
        r"!settings\.EnableAuditLog")
require("Audit entity remains mapped to Firebase SSOT", "Web/Payroll.Web/Services/FirebaseRealtimeService.cs",
        r'"AuditLog"\s*=>\s*"audit_logs"')

failed = [label for label, ok in checks if not ok]
print(f"1200-Q static audit: {len(checks)-len(failed)}/{len(checks)} checks passed")
for label, ok in checks:
    print(("PASS" if ok else "FAIL") + " | " + label)
if failed:
    raise SystemExit(1)
