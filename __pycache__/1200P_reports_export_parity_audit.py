#!/usr/bin/env python3
"""Static audit for 1200-P Reports / Export Parity."""
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

csv = "Web/Payroll.Web/Services/CsvExportService.cs"
admin = "Web/Payroll.Web/Components/Pages/Admin/ReportCenter.razor"
self_report = "Web/Payroll.Web/Components/Pages/EmpScreens/MyReports.razor"
android = "Android/app/src/main/java/com/biometric/app/ui/ReportCenterActivity.kt"
layout = "Android/app/src/main/res/layout/activity_report_center.xml"

require("DailySummary CSV exporter", csv, r"GenerateDailySummaryCsv")
require("Consolidated attendance CSV exporter", csv, r"GenerateConsolidatedAttendanceCsv")
require("Payroll variance CSV exporter", csv, r"GeneratePayrollVarianceCsv")
require("Financial register CSV exporter", csv, r"GenerateFinancialRegisterCsv")
require("Admin Report Center has export action", admin, r"ExportReportCsv")
require("Admin Report Center invokes file download", admin, r"downloadFileFromStream")
require("Employee MyReports has real export action", self_report, r"async Task ExportCsv")
forbid("Employee MyReports no longer has placeholder export", self_report, r"Export started\.\.\." )
require("Employee MyReports invokes file download", self_report, r"downloadFileFromStream")
require("Android report center has generated report results", android, r"viewModel\.generateReport")
require("Android report center exposes export control", layout, r"btnExportCsv")
require("Android report center implements CSV export", android, r"exportReportCsv|share.*csv|text/csv")

failed = [name for name, ok in checks if not ok]
print(f"1200-P static reports/export parity audit: {len(checks)-len(failed)}/{len(checks)} checks passed")
for name, ok in checks:
    print(("PASS" if ok else "FAIL") + " | " + name)
if failed:
    raise SystemExit(1)
