# Implementation Plan - Rename and Unlink App from TeaShop

This plan outlines the steps to rename the application from "TeaShopPOS" to "Biometric Payroll" and update the package name to `com.biometric.app`, unlinking it from the previous TeaShop database.

## User Review Required

> [!IMPORTANT]
> The package name change will require a new Firebase project setup if not already done. The current `google-services.json` already points to `com.biometric.app` and project `biometricpayroll`, which matches your request.

## Proposed Changes

### Build Configuration

#### [MODIFY] [build.gradle.kts](file:///E:/Project/Android App Projects/BioMetric+Payroll/app/build.gradle.kts)
- Update `namespace` to `com.biometric.app`.
- Update `applicationId` to `com.biometric.app`.

### Resources

#### [MODIFY] [strings.xml](file:///E:/Project/Android App Projects/BioMetric+Payroll/app/src/main/res/values/strings.xml)
- Change `app_name` from `TeaShopPOS` to `Biometric Payroll`.

### Source Code Refactoring

#### [MODIFY] All Kotlin files in `com.teashop.pos`
- Update package declarations to `com.biometric.app`.
- Update all internal imports.
- Rename `TeaShopApplication` to `BiometricApplication`.
- Update references to "TeaShop" or "TeaShopPOS" in UI strings or logs if appropriate (e.g., Gemini system prompt).

#### [MODIFY] [TeaShopApplication.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/app/src/main/java/com/teashop/pos/TeaShopApplication.kt)
- Update/Remove the hardcoded Firebase Database URL to unlink from the TeaShop database.

### Manifest and Proguard

#### [MODIFY] [AndroidManifest.xml](file:///E:/Project/Android App Projects/BioMetric+Payroll/app/src/main/AndroidManifest.xml)
- Update `android:name` for the application to `.BiometricApplication`.
- Update theme references if they were renamed (e.g., `Theme.TeaShopPOS` to `Theme.Biometric`).

#### [MODIFY] [proguard-rules.pro](file:///E:/Project/Android App Projects/BioMetric+Payroll/app/proguard-rules.pro)
- Update keep rules for the new package name.

## Verification Plan

### Automated Tests
- Run `gradle build` to ensure all references are updated correctly and the project compiles.

### Manual Verification
- Deploy the app and verify the app name on the launcher and login screen.
- Verify that the app connects to the new Firebase database (Biometric Payroll) and not the TeaShop one.
