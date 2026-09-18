# Module 1100-A: Firebase Single-Source-of-Truth Master Schema

## Objective

Make Firebase Realtime Database the **single authoritative shared data layer** for Web and Android. Existing Web UI, Android UI, business rules, payroll calculations, permissions, authentication, offline behavior, and layouts are preserved.

This module establishes the contract only. It does not destructively delete the existing Web SQL/EF or Android Room implementation. Those stores become compatibility/cache layers until their individual consumers are cut over and verified.

## Canonical topology

```text
Firebase Authentication + Realtime Database
                    |
             single SSOT layer
               /         \\
              /           \\
           Web           Android
        Firebase SDK    Firebase SDK
          |                |
      local cache       Room cache/queue
```

There is no Web-to-Android database bridge and no Android-to-Web database bridge. All shared records converge through Firebase.

## Owner path

```text
owners/{ownerUid}/{table}/{recordId}
```

Default development owner:

```text
biometricpayroll
```

## Canonical business tables

| Entity | Firebase table | Web | Android | Purpose |
|---|---|---|---|---|
| Employee | employees | target Firebase | target Firebase + Room cache | Employee master |
| Shop | shops | target Firebase | target Firebase + Room cache | Shop master |
| AttendanceLog | attendance | target Firebase | target Firebase + Room cache | Attendance |
| AttendancePunch | attendance_punches | target Firebase | target Firebase + Room cache | Punch records |
| SalaryAdvance | advance_payments | target Firebase | target Firebase + Room cache | Advances |
| EmployeeHistory | employee_history | target Firebase | target Firebase + Room cache | Employee history |
| CompanyHoliday | shop_closed_days | target Firebase | target Firebase + Room cache | Closed/holiday days |
| AttendanceRegularization | regularizations | target Firebase | target Firebase + Room cache | Regularization |
| LeaveRequest | leave_requests | target Firebase | target Firebase + Room cache | Leave |
| ResignationRequest | resignation_requests | target Firebase | target Firebase + Room cache | Exit/resignation |
| SalarySnapshot | salary_snapshots | target Firebase | target Firebase + Room cache | Salary snapshots |
| AuditLog | audit_logs | target Firebase | target Firebase + Room cache | Audit |
| DailySummary | daily_summaries | target Firebase | target Firebase + Room cache | Daily summary |
| ShiftSchedule | shift_schedules | target Firebase | target Firebase + Room cache | Shift |
| PayrollHistory | payroll_history | target Firebase | target Firebase + Room cache | Final payroll records |
| PayrollPreview | payroll_previews | target Firebase | target Firebase | Preview results |
| PayrollFinalization | payroll_finalization | target Firebase | target Firebase | Finalization state |
| BonusRecord | bonus_records | target Firebase | target Firebase + Room cache | Bonus |
| TaxDeclaration | tax_declarations | target Firebase | target Firebase + Room cache | Tax declarations |
| FBPComponent | fbp_components | target Firebase | target Firebase + Room cache | FBP master |
| FlexibleBenefitDeclaration | fbp_declarations | target Firebase | target Firebase + Room cache | FBP declarations |
| FeatureSettings | feature_settings | target Firebase | Firebase | Feature switches |
| CompanySetting | company_settings | target Firebase | Firebase | Company/geofence settings |
| ProfessionalTaxSlab | professional_tax_slabs | target Firebase | pending Room parity | Statutory slabs |
| YearEndSummary | year_end_summaries | target Firebase | target Firebase + Room cache | Year-end |
| FnFSettlement | fnf_settlements | target Firebase | pending Room parity | Full & final |
| ReportDefinition | report_definitions | target Firebase | pending Room parity | Report metadata |
| GeoPunchAudit | geo_punch_audits | target Firebase | pending Room parity | Geofence audit |

## Identity and control trees

These are Firebase control/state trees rather than payroll business tables:

```text
user_profiles/
sessions/
tracking/
tracking_scopes/
geofences/
recycle_bin/
owner_events/
mobile_auth_events/
```

They must be included in the final security/realtime audit and must not become hidden alternate databases.

## Write rule

Shared business mutations must converge on Firebase:

```text
Web -> Firebase -> Web + Android
Android -> Firebase -> Android + Web
```

Privileged calculations may continue to execute in the existing trusted server/business layer, but their authoritative persisted result must be written to Firebase. The calculation engine is not a second data store.

## Read rule

Shared application screens should ultimately read Firebase-backed repositories. Local Web cache and Android Room are allowed for performance/offline operation but are projections of Firebase, not competing authorities.

## Migration safety rules

1. Do not delete an existing screen.
2. Do not change existing UI/layout.
3. Do not change payroll formulas or attendance business rules.
4. Do not remove existing permissions.
5. Do not perform a destructive Firebase cleanup in 1100-A.
6. Before a table is cut over, compare its Web and Android schema, key, nullability, timestamps, soft-delete behavior, and authorization.
7. Every table must support create/update/delete propagation and realtime observation where applicable.
8. Offline writes must use an idempotent operation/key and reconcile through Firebase after reconnect.
9. Payroll, attendance, punch, F&F and other sensitive mutations must retain their existing authorization/calculation boundaries.
10. The final cutover is complete only when Web A, Web B and Android show the same canonical Firebase record without manual refresh.

## Current migration note

The source project still contains Web EF/SQLite compatibility code and Android Room. This is intentionally preserved at 1100-A. The next modules must migrate each consumer to the canonical Firebase repository and only then retire the obsolete persistence path.
