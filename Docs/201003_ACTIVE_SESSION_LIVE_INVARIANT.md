# 201003 Active Session Live Invariant

A dashboard may show an employee as LIVE only when the Firebase owner-scoped live marker has a SessionId and the matching durable GPS session is ACTIVE.

An ENDED or MISSING session is never converted back to ACTIVE. When current Android tracking resumes after an ended/missing session, TrackingService creates a new SessionId and publishes SESSION_STARTED before current GPS is accepted. Historical offline GPS for an ended session remains historical and does not resurrect that session.

Android Admin validates the live marker against the owner-scoped durable session before publishing it to the visible live-location StateFlow. This prevents a stale compatibility/live marker from appearing LIVE without an active session.
