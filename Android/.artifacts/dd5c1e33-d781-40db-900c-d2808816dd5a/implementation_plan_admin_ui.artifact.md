# SuperAdmin Management Interface Implementation Plan

This plan outlines the creation of the SuperAdmin management interface for managing user-level feature permissions (16 screens) and white-label branding, supporting both light/dark modes and emojis.

## User Review Required

> [!IMPORTANT]
> The interface will manage a list of 16 distinct screens/features.

> [!IMPORTANT]
> The admin screen must strictly respect light/dark modes. I will use standard Material3 components.

## Proposed Changes

### SuperAdmin Management UI

We will create a new Activity: `SuperAdminManagementActivity`.

1.  **User Selector:** Search/list view to select the user (by phone number 9629881598 etc.).
2.  **Feature Toggler:** A list of 16 toggles with emojis for the following:
    - 📊 Dashboard
    - 🛒 POS
    - 👥 Staff Management
    - 📦 Inventory
    - 📈 Reports
    - 💳 Finance Entry
    - 🧮 Daily Analytics
    - 🎯 Sales Pattern
    - 🏥 Risk Alerts
    - 📋 Audit Trail
    - 🗑️ Recycle Bin
    - 📦 Smart Stock
    - 🔍 Stock Audit
    - 🧠 AI Predictions
    - 🧾 Fixed Expenses
    - 💰 Investments
3.  **Branding Configuration:** Input fields for App Name and Logo URL, accessible to SuperAdmin (9629881598 / 74482) to update app branding.

#### [NEW] [SuperAdminManagementActivity.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/SuperAdminManagementActivity.kt)
The primary UI for the SuperAdmin.

### Data Layer Updates

1.  Update `UserProfile` entity to store the list of 16 enabled screen flags.
2.  Extend `FeatureManager` to handle global app branding settings and user-level screen permissions.

## Verification Plan

### Automated Tests
- Verify that SuperAdmin toggles correctly update the `UserProfile` in Firebase.

### Manual Verification
- Log in as SuperAdmin (9629881598 / 74482).
- Change app branding and disable specific features for a staff user.
- Verify changes are reflected upon staff user login.
