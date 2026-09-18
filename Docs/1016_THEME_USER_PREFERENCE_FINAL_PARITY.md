# 1016 Theme / User Preference Final Parity

## Final contract

Firebase Realtime Database `user_profiles/{firebaseUid}/theme` is the cross-device source of truth for the authenticated user's presentation theme. Android local SharedPreferences and browser localStorage are only last-known caches/fallbacks.

## Android

- `UserProfile` now carries `theme` with a `light` default.
- `ThemePreferenceSync` no longer calls `MobileApiService` for theme reads/writes.
- Theme reads use the authenticated Firebase UID.
- Theme writes update only the `theme` child, avoiding replacement of the complete profile.
- Firebase Android offline persistence queues theme writes while offline.
- Local theme is applied immediately, then synchronized to Firebase.
- Existing UI/theme toggle flow is unchanged.

## Web

- `ThemeService` now reads/writes Firebase `user_profiles/{uid}/theme`.
- Existing SQL `user_theme_preferences` is retained only as a one-time migration fallback when Firebase has no theme yet.
- Once a legacy SQL value is found, it is copied into Firebase and subsequent saves use Firebase.
- Existing browser user-namespaced cache remains a presentation fallback.
- Existing layout and theme toggle UI are unchanged.

## Cross-platform result

```text
User selects Dark
      ↓
Firebase user_profiles/{uid}/theme = dark
      ↓
Web and Android authenticated as the same Firebase UID
      ↓
Next login on any device reads dark
      ↓
Existing local/browser cache applies immediately
```

## Security

The existing RTDB `user_profiles/{uid}` boundary remains in force. A user may update their own profile without changing their role. Admin/SuperAdmin retain their existing privileged profile-management permissions. The theme update is a child update and does not permit role escalation.

## Compatibility

- No screen/layout changes.
- No payroll/attendance calculation changes.
- No Room schema migration.
- No Neon/Render dependency introduced.
- `MobileApiService` theme endpoints are retained only as compatibility declarations; Android no longer consumes them.
- Existing SQL theme table/migration is not deleted.

## Validation

- Static Kotlin/C# source checks: performed.
- Firebase rules JSON: validated.
- ZIP integrity: validated.
- Full Gradle/.NET compilation: not verified in this environment because the configured Gradle distribution/dependencies are unavailable offline.
