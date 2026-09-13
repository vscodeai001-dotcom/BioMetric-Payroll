# 🛡️ Biometric Payroll

A high-performance Android Point of Sale (POS) and Enterprise Resource Planning (ERP) solution for tea shop chains.

## 📖 Project Documentation Index

This project maintains detailed documentation for different aspects of the system:

- **[Project Overview](Project_Overview_Documentation.md)**: High-level overview of the business logic, technical stack, and core modules.
- **[Detailed Technical Documentation](DOCUMENTATION.md)**: In-depth technical details including Salary Engine rules, AI Inventory forecasting, and sync architecture.
- **[Salary Engine Logic](Salary_Engine_Documentation.md)**: Detailed breakdown of the complex payroll and attendance rules.
- **[Database Schema](DATABASE_SCHEMA.md)**: Documentation of the Room database entities and relationships. (Coming Soon)

## 🚀 Quick Start for Developers

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer.
- JDK 17.
- Firebase project for synchronization (optional for local development).

### Architecture
The project follows **Clean Architecture** combined with **MVVM**:
- **UI Layer**: Jetpack Compose (Modern parts) & XML with ViewBinding.
- **Domain Layer**: UseCases for business logic encapsulation.
- **Data Layer**: Repository pattern with Room for local storage and Firebase for cloud sync.

### Key Components
- `SalaryEngine`: The core logic for calculating staff salaries, bonuses, and paid leave.
- `FirebaseSyncManager`: Handles the offline-first synchronization logic.
- `StockAI`: Predictive analytics for inventory management.

## 🛠️ Build and Test

- **Build**: `./gradlew assembleDebug`
- **Run Tests**: `./gradlew test` (Pay special attention to `SalaryEngineTest`)

---
© 2024 Biometric Payroll Team.
