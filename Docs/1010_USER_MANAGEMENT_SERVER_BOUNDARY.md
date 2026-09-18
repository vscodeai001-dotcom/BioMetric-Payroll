# 1010 User Management Server Boundary

## Canonical rule
Firebase Authentication is the credential authority for native Android/Web Firebase sessions. Privileged Firebase Auth lifecycle operations are server-only.

## Protected operations
- Create Firebase Auth user
- Change role/custom claims
- Disable/enable account
- Revoke refresh tokens
- Delete Firebase Auth account
- Synchronize `user_profiles`
- Synchronize Web Identity and Employee link

## API
- `POST /api/mobile/admin/users`
- `PUT /api/mobile/admin/users/{firebaseUid}/role`
- `PUT /api/mobile/admin/users/{firebaseUid}/disabled`
- `DELETE /api/mobile/admin/users/{firebaseUid}`

The endpoints use the existing `MobileBearer` authentication handler, which verifies Firebase ID tokens with Firebase Admin SDK. Only the `SuperAdmin` role is authorized.

Android no longer deletes `user_profiles` directly when removing a user. It invokes the protected server boundary, which revokes/deletes Firebase Auth and then reconciles Web Identity, Employee link, and profile state.

## Compatibility
Existing Web User Management remains functional. No Neon dependency or direct Android Firebase Auth administrative SDK is introduced.
