# 980 - SuperAdmin / Admin Exit & F&F Parity

## Purpose
This pass removes the Android-side mock Full & Final settlement behavior from `ExitManagementActivity`.

## Contract
- Android does not recalculate Web Full & Final settlement values.
- Android requests calculation from the existing authenticated Web `ResignationService` boundary.
- Android displays the server-calculated values read-only.
- Finalization is submitted to the same Web service boundary.
- Web remains responsible for transaction, settlement persistence, employee termination, leave balance closure and notifications.
- Approval/rejection sends the exact approved last-working-day value to the Web service.

## Why
Full & Final settlement contains business rules and side effects. A client-side approximation could silently diverge from payroll records. Keeping this operation behind the Web service preserves the mirror-clone contract while Firebase/Room continue to carry the synchronized realtime/read-model data.

## Security
The Web endpoints remain protected by the existing `MobileBearer` Admin/SuperAdmin authorization boundary.
