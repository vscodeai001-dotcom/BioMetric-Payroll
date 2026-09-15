# Realtime v6 fixes

## Build fixes from v5

- Added the missing `PendingChange.Entries` property required by `ApplicationDataChangeInterceptor`.
- Moved Android `local.properties` / `NEON_API_KEY` loading outside the `android {}` DSL so Kotlin Gradle script resolution is valid.
- Kept the existing UI, database schema, business logic, payroll calculations, Room tracking and SignalR compatibility unchanged.

## Why

The reported C# CS1061 errors were caused by `pending.Entries` being used without a corresponding property.

The Android Gradle errors around `java.util`, `load`, `getProperty`, and `None` were cascading Kotlin DSL parsing/resolution errors caused by placing the secret-loading code inside the Android extension block.

## Configuration

Provide `NEON_API_KEY` in Android `local.properties` or as a Gradle property. Do not commit secrets.
