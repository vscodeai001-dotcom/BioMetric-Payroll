# Implementation Plan - Fix Dashboard Connectivity and Data Sync

The Android app is currently failing to display data and the map correctly because it cannot connect to the backend API hosted on a local IP address (`192.168.31.38`). This causes a `SocketTimeoutException`, leaving the dashboard in an empty state (0 values) and preventing the map from receiving office coordinates.

## User Review Required

> [!IMPORTANT]
> **API Connectivity**: The app is configured to connect to `http://192.168.31.38:5000/`. You must ensure that:
> 1. The computer running the Web project (`E:\Project\Android App Projects\BioMetric+Payroll\BioMetric+Payroll\Web`) is on the same Wi-Fi/network as your Android device.
> 2. The IP address of that computer is still `192.168.31.38`. If it has changed, you must update `local.properties` in the Android project.
> 3. The backend Web application is actually running and listening on port 5000.

> [!NOTE]
> The app uses Firebase for real-time tracking, but the Dashboard (Salary, Leaves, Office Location) is authoritative from the Web API. If the API is down, this data will be missing.

## Proposed Changes

### Android App Configuration

#### [MODIFY] [local.properties](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/local.properties)
Check and update the `BIOMETRIC_API_BASE_URL` to match the current IP address of your server.

#### [MODIFY] [BiometricApplication.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/BiometricApplication.kt)
Add Firebase App Check debug provider to resolve the `No AppCheckProvider installed` warnings, which can sometimes interfere with Firebase requests.

### UI Improvements for Connectivity

#### [MODIFY] [EmployeeHomeActivity.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/ui/EmployeeHomeActivity.kt)
- Improve error handling in `loadDashboard` to show a "Connection Failed" message instead of empty values.
- Add a manual "Retry" button or swipe-to-refresh if the connection fails.
- Ensure the map shows a clear error state if office coordinates (0,0) are received due to API failure.

## Verification Plan

### Manual Verification
1. **Verify Server IP**: Run `ipconfig` (Windows) on the machine running the Web project to confirm its IP address.
2. **Test API Access**: Open `http://192.168.31.38:5000/api/mobile/employee/dashboard` in a mobile browser on the device to see if it's reachable.
3. **App Build**: Rebuild and deploy the Android app after updating the IP.
4. **Logcat Check**: Monitor Logcat for `Dashboard load failed` errors to see if they persist.
