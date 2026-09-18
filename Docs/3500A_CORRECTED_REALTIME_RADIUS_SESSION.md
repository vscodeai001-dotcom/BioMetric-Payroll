# 3500A Corrected Realtime + Radius + Session Lifecycle

Compile errors fixed in FirebaseSyncManager.kt and SignalRManager.kt. Web map now recalculates inside/outside from the current office/radius and fresh GPS coordinate instead of trusting stale cached flags. Late packets from another session trigger authoritative session reconciliation instead of replacing the current marker. Ended sessions remove live Firebase markers, and browser Firebase listens for child removal. Android token validation no longer forces a network refresh on every cold start. Web browser sessions use a 12-hour sliding cookie lifetime, and the existing 30-minute GPS inactivity timeout is invoked every 60 seconds.

No screen structure, database schema, payroll calculations, attendance formulas, or core business flow was changed.
