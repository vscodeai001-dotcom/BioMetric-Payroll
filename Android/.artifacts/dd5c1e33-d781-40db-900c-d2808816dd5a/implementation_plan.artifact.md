# Multi-Tenant Feature Control and White-Label Branding Implementation Plan

This plan outlines the architecture for introducing SuperAdmin-controlled feature access and client-specific branding (logo/name) to the existing TeaShopPOS application, while preserving all current functionality and database flows.

## User Review Required

> [!IMPORTANT]
> This change introduces a new "Feature Configuration" layer that will check user/store permissions at login and during menu/screen loading.

> [!WARNING]
> White-labeling (logo/name change) will require a mechanism to load assets dynamically or via app configuration files. We will need to define where these are stored.

## Open Questions

1.  **Storage:** Where should we store the feature flags (enabled/disabled) and branding info (logo path/name)? Should this be a new table or an extension of the current `User` or `Shop` models?
2.  **Branding:** Are we planning to bundle multiple brand assets, or will this be a dynamic fetch?

## Proposed Changes

### Feature Control Engine

We will introduce a `FeatureManager` service that will:
1.  On login, load the feature set associated with the logged-in user.
2.  Provide a reactive state (e.g., `StateFlow`) for the UI to observe and hide/show menu items or screens.

#### [NEW] [FeatureManager.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/domain/FeatureManager.kt)
Handles the logic for user permissions and feature toggles.

### White-Labeling Service

We will introduce a `BrandingManager` service that will:
1.  Load the branding configuration (app name, logo resource).
2.  Inject these into the UI application-wide.

#### [NEW] [BrandingManager.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/domain/BrandingManager.kt)
Handles app-level branding assets and configuration.

### UI Integration

We will modify the main navigation/menu components to observe the `FeatureManager` state and conditionally render components.

## Verification Plan

### Automated Tests
- Unit tests for `FeatureManager` to ensure correct permissions are returned for specific user IDs.
- Unit tests for `BrandingManager` to ensure correct metadata is loaded.

### Manual Verification
- Log in as the SuperAdmin (9629881598 / 74482).
- Navigate to the "User Management" screen to toggle features for a test user.
- Log in as the test user and verify only enabled menus appear.
- Verify branding updates correctly when changing client configuration.
