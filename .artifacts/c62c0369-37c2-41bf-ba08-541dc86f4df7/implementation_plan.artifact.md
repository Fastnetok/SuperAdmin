# Implementation Plan - City/Franchise Management & Dynamic Feature/Company Permissions

This plan outlines a comprehensive, centralized management system for Super Admin to control **Company Services** and **Super Admin Menu Features** dynamically per city/franchise (e.g., Okara, Renala, Depalpur, Pakpattan, etc.) across **both registration time and central management**.

## Confirmed Dual Workflow

1. **Registration / License Creation Time:**
   - When registering a new city or franchise license (e.g., Pakpattan), Super Admin will have an interactive setup dialog/screen to select which company services (EBONE, WATEEN, ZONG, and future companies) and which Super Admin menu features are enabled or disabled for this specific city right from the start.
   - Future companies and features will automatically populate in this registration setup via our dynamic registry.

2. **Central Super Admin Management Screen (`CityFranchiseManagementActivity`):**
   - Accessible directly from the Super Admin menu.
   - Displays a clear, comprehensive list/table of all cities and franchises.
   - For every city, it clearly shows:
     - Assigned company services (EBONE, WATEEN, ZONG, etc.) and their Enabled/Disabled status.
     - Assigned Super Admin menu features (all 16+ features) and their Enabled/Disabled status.
   - Super Admin can change any permission at any time with live toggles, taking effect immediately.

## Dynamic Architecture (Future-Proof Design)

1. **Centralized Feature & Company Registry (`AppFeatures.kt` & Company Config):**
   - Centralized keys ensure future menu features and future companies automatically appear in both registration and management screens without code rewrites.

2. **Strict Safety & Exclusions:**
   - **`WebViewLoginActivity.kt` will NOT be touched or modified.**
   - All payment settings (EasyPaisa, JazzCash, Sadapay, Raast, etc.) remain 100% untouched and safe.

## Proposed Changes

### [Admin Panel & Management]

#### [NEW] [AppFeatures.kt](file:///C:/Users/Dell/AndroidStudioProjects/SuperAdminSuite/SuperAdmin/app/src/main/java/com/example/eboneadminpanel/core/AppFeatures.kt)
- Centralized registry of Super Admin menu feature keys and company services for dynamic management.

#### [NEW] [CityFranchiseManagementActivity.kt](file:///C:/Users/Dell/AndroidStudioProjects/SuperAdminSuite/SuperAdmin/app/src/main/java/com/example/eboneadminpanel/CityFranchiseManagementActivity.kt)
- Centralized Super Admin screen showing cities, companies, and menu features with live toggle controls, plus registration flow integration.

#### [MODIFY] [MainActivity.kt](file:///C:/Users/Dell/AndroidStudioProjects/SuperAdminSuite/SuperAdmin/app/src/main/java/com/example/eboneadminpanel/MainActivity.kt)
- Add navigation button to open `CityFranchiseManagementActivity` and dynamically filter menu items based on city permissions.

#### [NEW] [CityPermissionManager.kt](file:///C:/Users/Dell/AndroidStudioProjects/SuperAdminSuite/SuperAdmin/app/src/main/java/com/example/eboneadminpanel/core/CityPermissionManager.kt)
- Helper utility to check feature and company permissions per city/zone.

## Verification Plan

### Automated Tests
- Gradle build verification (`app:assembleDebug`).

### Manual Verification
- Test creating/registering a city with specific company and menu feature permissions.
- Test changing permissions via the central `CityFranchiseManagementActivity` screen.
- Confirm payment methods and `WebViewLoginActivity.kt` remain 100% untouched.
