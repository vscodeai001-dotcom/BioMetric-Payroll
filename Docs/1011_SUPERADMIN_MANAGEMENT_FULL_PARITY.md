# 1011 - SuperAdmin Management Full Parity

## Scope
SuperAdmin-only feature assignment, branding, user-specific permissions and Firebase enforcement while preserving the existing Admin staff-permission workflow.

## Changes
- SuperAdmin Management remains guarded at the Android entry point.
- User-specific feature assignment continues through `user_profiles.enabledFeatures`.
- Branding remains on the user profile (`brandingName`, `brandingLogoUrl`) and is synchronized through Firebase.
- Android management UI now immediately updates its local `BrandingManager` after a successful profile save.
- Firebase `user_profiles` validation prevents non-SuperAdmin users from changing another user's role or branding fields. Existing Admin self-profile updates remain possible, and the existing Admin staff-permission workflow can continue changing `enabledFeatures`.
- Firebase `feature_settings` write access preserves the existing `AdminCanManageEmployeePermissions` delegation while reserving global feature-setting changes for SuperAdmin.
- Existing UI/layout, Room schema, payroll/attendance calculations and authentication flow are unchanged.

## Authority model
Firebase Authentication custom claims (`role`, `owner_uid`, `employee_id`) are the authorization inputs. RTDB rules enforce the final boundary; UI role checks are convenience only.

## Realtime
Existing Firebase listeners continue to propagate profile/feature changes to Web and Android. No polling or reload requirement is introduced.

## Verification
- Firebase rules JSON parses successfully.
- Modified Kotlin/C#/Razor source was not behaviorally compiled in this environment because the required Gradle/.NET toolchains are unavailable.
