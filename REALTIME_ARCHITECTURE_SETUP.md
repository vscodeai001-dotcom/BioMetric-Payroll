# BioMetric Payroll - Render-independent realtime tracking

This change keeps the existing Payroll UI, database schema, business rules, payroll calculations, and SignalR compatibility path intact.

## What changed

### Android GPS

`TrackingService` now publishes every captured GPS fix to Firebase Realtime Database first:

- `tracking/live/{employeeId}` - latest live position
- `tracking/history/{employeeId}/{clientEventId}` - immutable GPS event

Room remains the durable offline ledger. When internet is unavailable, GPS continues to be captured locally. When internet returns, the worker drains the Firebase queue even if Render is still unavailable.

The existing `Payroll.Web -> Neon` GPS upload remains in place as the authoritative Neon persistence/compatibility path.

### Firebase authentication

The existing Payroll login remains the authentication authority. After a successful login, `Payroll.Web` mints a Firebase custom token. Android exchanges it with Firebase Authentication. Firebase then maintains its own session, so later GPS writes do not require Render to be running.

The Firebase custom-token service uses `FirebaseAdmin` and a Google service account. Firebase documents this custom-token flow for external/legacy authentication systems.

### Web realtime bridge

The existing SignalR path is preserved. A second Firebase listener is now attached by `attendance-refresh.js`:

- `tracking/live/*` -> existing `LocationChanged` pipeline
- `application_events/*` -> existing `ApplicationDataChanged` pipeline

Therefore the existing Blazor components continue receiving the same event names and screen structure does not change.

## Required server configuration

Do NOT commit a Firebase service-account JSON file.

Configure one of these environment variables on the server:

```text
FIREBASE_SERVICE_ACCOUNT_JSON=<entire service-account JSON>
```

or:

```text
GOOGLE_APPLICATION_CREDENTIALS=/secure/path/firebase-service-account.json
```

The service account must have permission to sign Firebase custom tokens and access the Realtime Database REST API.

For a shared company account, set `FIREBASE_OWNER_UID` to one stable Firebase UID for the company. The server places this value in the custom-token `owner_uid` claim, so every authorized Android/web client uses the same owner-scoped realtime channel without changing the Neon schema.

The project/database values are already configured as:

```text
FIREBASE_PROJECT_ID=biometricpayroll
FIREBASE_DATABASE_URL=https://biometricpayroll-default-rtdb.asia-southeast1.firebasedatabase.app
FIREBASE_OWNER_UID=<shared company/admin Firebase UID>
```

## Firebase Realtime Database rules

Deploy `Android/database.rules.json` to the Firebase Realtime Database rules section.

The rules intentionally allow:

- employee: write/read only its own tracking path
- admin/super-admin: read live/history tracking
- authenticated admins: read application invalidation events
- owners: preserve the existing owner-scoped application data path

## Important first-login requirement

Existing installations that were logged in before this change may not yet have a Firebase Authentication session. Have the employee/admin complete one normal login while Payroll.Web is available. The login response now contains the Firebase custom token and Android signs into Firebase automatically.

After that, Firebase Authentication keeps the client session independently of Render.

## Validation sequence

1. Configure the service account on Payroll.Web.
2. Deploy the Realtime Database rules.
3. Login once on Android.
4. Confirm Firebase Authentication shows the user.
5. Start employee tracking.
6. Confirm `tracking/live/{employeeId}` changes every GPS interval.
7. Stop Render/Payroll.Web.
8. Confirm Android continues writing `tracking/live` and `tracking/history`.
9. Restore Render.
10. Confirm queued GPS points continue to persist to Neon through the existing server path.
11. Open the admin live map and confirm Firebase `LocationChanged` events update the existing markers without a manual refresh.
12. From Android and Web, create/update/delete a supported record and confirm the other connected platform receives the existing `ApplicationDataChanged` callback without a browser reload.

## What is deliberately NOT changed

- existing Payroll.Web screen/layout structure
- Android screen/layout structure
- Neon/PostgreSQL schema
- payroll calculations
- attendance calculation rules
- existing SignalR events
- existing API routes
- existing Room GPS capture model
- existing offline map/location flow
## Render outage behavior

The Android tracking queue does not require the Payroll.Web bearer token before publishing to Firebase. A queued GPS fix that has already reached Firebase is not written to Firebase again during Neon retry. This keeps the live tracking path independent of Render while preserving the existing Neon persistence path and database schema.



## Credential hardening (v4)

Do not commit Neon passwords/API credentials or Firebase service-account JSON.
Set the web/worker Neon connection string with `DATABASE_URL` or `NEON_CONNECTION_STRING`.
For Android local builds, create `Android/local.properties` from `local.properties.example` and set `NEON_API_KEY`.
If any credential that was previously committed is still active, rotate it before production deployment.
