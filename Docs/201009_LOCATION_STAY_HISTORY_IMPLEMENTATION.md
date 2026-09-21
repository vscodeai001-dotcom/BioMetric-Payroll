# 201009 - Location Tracking History / >10 Minute Stay Analysis

## Base
This package is based directly on `2010008AA_SELECTED_EMPLOYEE_MAP_DETAILS_TOOLS_FINAL.zip`.

## Implementation
A new Admin-only Web screen was added at:

`/admin/location-stays`

The screen derives stayed-location records from the existing `EmployeeLocationHistory` records. No new database table or migration is introduced.

### Detection
A stay is derived when, within the same employee + GPS session:

- GPS points remain within approximately 10 metres of the run anchor.
- At least two GPS points exist.
- The observed time between the first and last point is **more than 10 minutes**.

The original GPS records are read-only and are not modified.

### Report
The screen includes:

- Employee filter
- Date From / Date To
- From Time / To Time
- Detected stay count
- Employees represented
- Total observed stay time
- GPS points analyzed
- Stay timeline/list
- Start / End / Duration
- GPS accuracy
- Arrival/departure speed where calculable
- Exact latitude/longitude
- Shop/location matching where an existing Firebase shop has valid coordinates and an explicit configured radius
- Distance from matched shop
- Historical movement path
- Highlighted stay markers
- Report-to-map synchronization
- Marker-to-report synchronization
- Reverse-geocoded address presentation with `Address unavailable` fallback

## Shop matching safety
A GPS stay is not assigned to a shop merely because the shop is nearby. Matching is only accepted when the existing Firebase shop projection supplies latitude, longitude, and a positive configured radius, and the stay coordinate falls inside that configured radius. Otherwise the result remains `Unknown / Other Location`.

## Existing application protection
The change does not add or modify database schema, attendance logic, payroll logic, employee session logic, login/logout behavior, GPS collection frequency, or the existing Admin Dashboard map implementation.

The existing Admin Dashboard and Android Admin map changes from the base package remain intact.

## Validation
- `themeInterop.js` passed `node --check`.
- New route and navigation link are present.
- No migration file was added or modified by this feature.
- Full .NET compilation was not performed because the execution environment does not provide the .NET SDK.
