# 201009 Location Stays + Admin Map Presentation Audit

## Baseline
Latest user-supplied `2010008aa.zip`.

## Scope
Presentation/reporting additions only. Existing database schema, attendance rules, payroll calculations, GPS capture flow, Firebase ownership model, and screen flow are preserved.

## Changes
- Added `/admin/location-stays` for Admin/SuperAdmin.
- Reads the existing `EmployeeLocationHistory` table only. No migration or new table is introduced.
- Detects stays within 10 metres for 10 minutes or longer, scoped to the same EmployeeId + GPS SessionId.
- Provides date range, employee filter, stay duration, point count, average accuracy, exact coordinates, session ID, map markers, and clickable stay details.
- Added a presentation-only map ticker to the existing Web Admin live map. The ticker pauses on hover; this does not control map tools.
- Web Admin map tools are now persistent and only expand/minimize through the explicit Tools click/tap. Hover/focus no longer reveals or hides the control rail.
- Existing Android Admin map control behavior remains tap-only and persistent.

## Explicitly not changed
- No database design/schema changes.
- No payroll calculation changes.
- No attendance/geofence calculation changes.
- No GPS capture interval changes.
- No Firebase path/schema changes.
- No employee login/session rules changes.
- No Admin/Employee screen flow changes.

## Validation performed
- JavaScript syntax check: `node --check Web/Payroll.Web/wwwroot/js/themeInterop.js`.
- Source-level structural checks for modified C#/Razor/Kotlin files.
- Full .NET compilation could not be executed because the environment does not have the `dotnet` CLI installed.
- Android Gradle wrapper was present, but full Gradle compilation/package verification was not completed in this environment.
