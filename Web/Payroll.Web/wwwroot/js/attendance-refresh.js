window.attendanceRefresh = (function () {

    let connection = null;
    let started = false;
    let starting = false;
    let retryTimer = null;

    let viewerRef = null;
    let viewerRefreshTimer = null;
    let viewerRefreshInFlight = false;
    let viewerRefreshPending = false;
    let listeners = [];
    // Each application listener may optionally subscribe to one entity.
    // Keeping the filter in the browser avoids unnecessary Blazor reloads
    // when unrelated domains publish application events.
    let applicationListeners = [];

    let applicationRefreshTimer = null;
    let applicationRefreshPending = new Map();

    // Firebase is an independent realtime transport. SignalR remains the
    // existing compatibility path, but Firebase keeps live GPS and CRUD
    // invalidation flowing when the Payroll.Web process is temporarily absent.
    let firebaseStarted = false;
    let firebaseStarting = false;
    let firebaseDatabase = null;
    let firebaseGeoPunchAuditRef = null;
    let firebaseGeoPunchAuditTimer = null;
    let firebaseGeoPunchAuditPending = new Map();
    let firebaseStartTime = Date.now();

    async function startFirebaseRealtime() {
        if (firebaseStarted || firebaseStarting || !window.firebase)
            return;

        firebaseStarting = true;
        firebaseStartTime = Date.now();

        try {
            const response = await fetch('/api/firebase/auth-token', {
                method: 'GET',
                credentials: 'same-origin',
                cache: 'no-store'
            });

            if (!response.ok) {
                console.warn(
                    'Firebase realtime auth endpoint returned HTTP ' +
                    response.status +
                    '. Retrying without requiring a page refresh.'
                );
                scheduleRetry();
                return;
            }

            const authResult = await response.json();
            if (!authResult || !authResult.token) {
                console.warn(
                    'Firebase realtime auth token was empty. Retrying without requiring a page refresh.'
                );
                scheduleRetry();
                return;
            }

            if (!firebase.apps.length) {
                firebase.initializeApp({
                    apiKey: 'AIzaSyDxIsBW8bq31gG7LqOm8-lwhmRFMsRu5CE',
                    authDomain: 'biometricpayroll.firebaseapp.com',
                    databaseURL: 'https://biometricpayroll-default-rtdb.asia-southeast1.firebasedatabase.app',
                    projectId: 'biometricpayroll',
                    storageBucket: 'biometricpayroll.firebasestorage.app',
                    messagingSenderId: '63802944560'
                });
            }

            await firebase.auth().signInWithCustomToken(authResult.token);
            firebaseDatabase = firebase.database();
            firebaseStarted = true;

            console.log(
                'Firebase realtime transport authenticated. Live GPS listener is active.'
            );

            const liveRef = firebaseDatabase.ref('tracking/live');
            liveRef.on('child_added', onFirebaseLiveLocation);
            liveRef.on('child_changed', onFirebaseLiveLocation);

            const ownerUid = authResult.ownerUid || authResult.ownerUID || null;
            const realtimeOwnerUid = ownerUid || 'biometricpayroll';
            if (ownerUid) {
                const ownerEventsRef = firebaseDatabase.ref('owner_events/' + ownerUid);
                ownerEventsRef.on('child_added', onFirebaseApplicationEvent);

                // Mobile-originated changes use a per-employee channel so an
                // employee cannot write to the shared admin event stream.
                // Admin/SuperAdmin Firebase rules allow the web dashboard to
                // receive these events without changing the existing UI.
                const clientEventsRef = firebaseDatabase.ref('client_events');
                clientEventsRef.on('child_added', onFirebaseClientEventEmployee);

            } else {
                // Backward compatibility for installations that have not yet
                // configured a shared Firebase owner UID.
                const eventsRef = firebaseDatabase.ref('application_events');
                eventsRef.on('child_added', onFirebaseApplicationEvent);
            }

            // Geo punch audits are always scoped to the same owner as the
            // Firebase auth contract. This remains active even when the auth
            // response omits ownerUid and the single-owner fallback is used.
            firebaseGeoPunchAuditRef = firebaseDatabase.ref(
                'owners/' + realtimeOwnerUid + '/geo_punch_audits'
            );
            firebaseGeoPunchAuditRef.on('child_added', onFirebaseGeoPunchAudit);
            firebaseGeoPunchAuditRef.on('child_changed', onFirebaseGeoPunchAudit);

            console.log('Firebase realtime transport connected.');
        } catch (error) {
            console.warn(
                'Firebase realtime transport unavailable. Retrying automatically.',
                error
            );
            scheduleRetry();
        } finally {
            firebaseStarting = false;
        }
    }

    async function onFirebaseLiveLocation(snapshot) {
        try {
            const data = snapshot.val();
            if (!data || !data.EmployeeId) return;

            // Dispatch the browser event first. The Leaflet map can therefore
            // react immediately even if a Blazor circuit is busy reconnecting.
            window.dispatchEvent(new CustomEvent('location-data-changed', { detail: data }));
            window.dispatchEvent(new CustomEvent('firebase-location-changed', { detail: data }));

            // Keep the existing Blazor state synchronization path as well.
            await notifyListeners('LocationChanged', data);
        } catch (error) {
            console.warn('Firebase live location callback failed.', error);
        }
    }

    async function onFirebaseGeoPunchAudit(snapshot) {
        try {
            const data = snapshot.val();
            if (!data || typeof data !== 'object') return;

            // Collapse a burst of Firebase child callbacks into one UI
            // invalidation per employee. The existing database query remains
            // authoritative, so no employee update is lost.
            const employeeId = Number(data.employeeId ?? data.EmployeeId ?? 0);
            const pendingKey = Number.isFinite(employeeId) && employeeId > 0
                ? String(employeeId)
                : snapshot.key || String(Date.now());
            firebaseGeoPunchAuditPending.set(pendingKey, data);

            if (firebaseGeoPunchAuditTimer !== null)
                clearTimeout(firebaseGeoPunchAuditTimer);

            firebaseGeoPunchAuditTimer = setTimeout(async function () {
                firebaseGeoPunchAuditTimer = null;
                const pendingEvents = Array.from(firebaseGeoPunchAuditPending.values());
                firebaseGeoPunchAuditPending.clear();

                for (const pending of pendingEvents) {
                    await notifyListeners('GeoPunchAuditChanged', pending);
                    window.dispatchEvent(new CustomEvent('geo-punch-audit-changed', { detail: pending }));
                }
            }, 80);
        } catch (error) {
            console.warn('Firebase geo punch audit callback failed.', error);
        }
    }

    async function onFirebaseClientEventEmployee(employeeSnapshot) {
        try {
            employeeSnapshot.ref.on('child_added', onFirebaseApplicationEvent);
        } catch (error) {
            console.warn('Firebase client-event callback failed.', error);
        }
    }

    async function onFirebaseApplicationEvent(snapshot) {
        try {
            const data = snapshot.val();
            if (!data || !data.timestamp) return;

            const eventTime = Date.parse(data.timestamp);
            // Ignore the initial backlog when the page first attaches. Only
            // changes that happened after this browser session started are
            // realtime invalidations.
            if (Number.isFinite(eventTime) && eventTime + 5000 < firebaseStartTime)
                return;

            // Coalesce bursts from one Firebase write into a single UI
            // invalidation. The event itself remains in Firebase as the audit
            // source; this timer only controls how often Blazor reloads.
            const changes = Array.isArray(data.changes) ? data.changes : [];
            const key = changes.map(function (c) {
                return String(c && (c.Entity || c.entity) || '') + ':' +
                    String(c && (c.RecordId || c.recordId) || '') + ':' +
                    String(c && (c.Action || c.action) || '');
            }).sort().join('|') || snapshot.key || String(Date.now());

            applicationRefreshPending.set(key, data);
            if (applicationRefreshTimer !== null)
                clearTimeout(applicationRefreshTimer);

            applicationRefreshTimer = setTimeout(async function () {
                applicationRefreshTimer = null;
                const pending = Array.from(applicationRefreshPending.values());
                applicationRefreshPending.clear();

                for (const eventData of pending) {
                    await notifyApplicationListeners('ApplicationDataChanged', eventData);
                    await notifyListeners('ApplicationDataChanged', eventData);
                    window.dispatchEvent(new CustomEvent('application-data-changed', { detail: eventData }));

                    // The Attendance Log Viewer is a route-level consumer.
                    // Firebase application events are the realtime source for
                    // Web-side SSOT invalidation, so bridge only attendance-
                    // relevant entities into the viewer's existing debounced
                    // RefreshFromNotification path. This preserves the current
                    // load/recalculation boundary while removing dependence on
                    // a separate SignalR attendance event for Firebase writes.
                    if (viewerRef && applicationEventAffectsAttendanceViewer(eventData)) {
                        await notifyViewer();
                    }
                }
            }, 80);
        } catch (error) {
            console.warn('Firebase application event callback failed.', error);
        }
    }

    async function start() {
        // Firebase is the only realtime transport for the SSOT path.
        // Once this page has authenticated with Firebase, it does not need
        // SignalR/Render for subsequent realtime events.
        if (!firebaseStarted)
            await startFirebaseRealtime();
    }

    function scheduleRetry() {

        if (retryTimer || (!listeners.length && !applicationListeners.length))
            return;

        retryTimer = setTimeout(
            function () {
                retryTimer = null;
                start();
            },
            2000
        );
    }


    /*
     * ==============================================================
     * APPLICATION-WIDE LISTENER NOTIFICATION
     * ==============================================================
     */

    async function notifyApplicationListeners(methodName, data) {

        const currentListeners =
            [...applicationListeners];

        for (const registration of currentListeners) {
            const listener = registration.ref;

            // An entity-filtered listener only receives events that actually
            // contain the requested entity. This prevents an Employee page
            // from reloading because of an unrelated payroll/attendance event.
            if (registration.entity && !applicationEventContainsEntity(data, registration.entity))
                continue;

            try {
                const targetMethod = typeof methodName === "string"
                    ? methodName
                    : "ApplicationDataChanged";

                await listener.invokeMethodAsync(targetMethod);
            }
            catch (error) {
                applicationListeners = applicationListeners.filter(function (item) {
                    return item.ref !== listener;
                });
            }
        }
    }

    function applicationEventContainsEntity(data, entity) {
        if (!data || !entity) return false;
        const changes = Array.isArray(data.changes) ? data.changes : [];
        const expected = String(entity).trim().toLowerCase();
        return changes.some(function (change) {
            const actual = String(change && (change.Entity || change.entity) || '')
                .trim().toLowerCase();
            return actual === expected;
        });
    }

    // Attendance Log Viewer depends on the finalized attendance projection
    // plus the inputs that can change the visible result (employee roster,
    // punches, leave, schedules, holidays, and regularization). Do not wake
    // the viewer for unrelated payroll/finance/admin events.
    const attendanceViewerEntities = new Set([
        'Employee',
        'AttendanceLog',
        'DailySummary',
        'LeaveRequest',
        'ShiftSchedule',
        'CompanyHoliday',
        'AttendanceRegularization'
    ]);

    function applicationEventAffectsAttendanceViewer(data) {
        if (!data || !Array.isArray(data.changes)) return false;

        return data.changes.some(function (change) {
            const entity = String(
                change && (change.Entity || change.entity) || ''
            ).trim();

            return attendanceViewerEntities.has(entity);
        });
    }


    /*
     * ==============================================================
     * VIEWER NOTIFICATION
     * ==============================================================
     */

    async function notifyViewer() {

        if (!viewerRef)
            return;

        // Attendance can generate several SignalR notifications in a very
        // short period. Debounce them so the Blazor component receives one
        // controlled refresh instead of a burst of concurrent JS -> .NET
        // invocations.
        viewerRefreshPending = true;

        if (viewerRefreshTimer !== null) {
            clearTimeout(viewerRefreshTimer);
        }

        viewerRefreshTimer = setTimeout(
            flushViewerRefresh,
            350
        );
    }


    async function flushViewerRefresh() {

        viewerRefreshTimer = null;

        if (viewerRefreshInFlight || !viewerRefreshPending)
            return;

        if (!viewerRef) {
            viewerRefreshPending = false;
            return;
        }

        viewerRefreshPending = false;
        viewerRefreshInFlight = true;

        const currentViewerRef = viewerRef;

        try {

            await currentViewerRef.invokeMethodAsync(
                "RefreshFromNotification"
            );

        }
        catch (error) {

            // A disposed Blazor component can remain referenced briefly while
            // SignalR is delivering an event. Clear only that stale reference
            // so future notifications do not repeatedly invoke a dead circuit.
            if (viewerRef === currentViewerRef) {
                viewerRef = null;
            }

            console.debug(
                "Attendance viewer refresh skipped because the viewer is no longer available.",
                error
            );
        }
        finally {

            viewerRefreshInFlight = false;

            // If another event arrived while the previous refresh was running,
            // schedule exactly one follow-up refresh.
            if (viewerRefreshPending && viewerRef) {
                viewerRefreshTimer = setTimeout(
                    flushViewerRefresh,
                    100
                );
            }
        }
    }


    /*
     * ==============================================================
     * LISTENER NOTIFICATION
     * ==============================================================
     *
     * data is optional.
     *
     * Existing components that define:
     *
     * LocationChanged()
     *
     * continue to work.
     *
     * Components that define:
     *
     * LocationChanged(data)
     *
     * can now receive the actual event payload.
     */

    async function notifyListeners(
        methodName,
        data
    ) {

        const currentListeners =
            [...listeners];

        for (const listener of currentListeners) {

            try {

                if (typeof data === "undefined") {

                    await listener.invokeMethodAsync(
                        methodName
                    );

                }
                else {

                    await listener.invokeMethodAsync(
                        methodName,
                        data
                    );

                }

            }
            catch (error) {

                // A Blazor component can disappear while SignalR is still
                // delivering an event. In that case the DotNetObjectReference
                // is stale and every future realtime event would fail again.
                //
                // Keep the existing callback/fallback behavior, but remove
                // the reference only when BOTH calls fail. This is lifecycle
                // cleanup only and does not change any business logic.

                let callbackFailed = true;

                if (typeof data !== "undefined") {
                    try {
                        await listener.invokeMethodAsync(
                            methodName
                        );

                        callbackFailed = false;
                    }
                    catch (fallbackError) {
                        console.debug(
                            "Attendance refresh listener became unavailable; removing stale listener.",
                            methodName
                        );
                    }
                }

                if (callbackFailed) {
                    const index = listeners.indexOf(listener);

                    if (index >= 0) {
                        listeners.splice(index, 1);
                    }
                }

            }
        }
    }


    /*
     * ==============================================================
     * VIEWER REGISTRATION
     * ==============================================================
     */

    function registerViewer(dotNetReference) {

        viewerRef = dotNetReference;

        start();
    }


    async function unregisterViewer(
        dotNetReference
    ) {

        if (viewerRef === dotNetReference) {
            viewerRef = null;
        }

        viewerRefreshPending = false;

        if (viewerRefreshTimer !== null) {
            clearTimeout(viewerRefreshTimer);
            viewerRefreshTimer = null;
        }
    }


    /*
     * ==============================================================
     * GENERAL LISTENER REGISTRATION
     * ==============================================================
     */

    function register(dotNetReference) {

        if (!listeners.includes(dotNetReference)) {

            listeners.push(
                dotNetReference
            );
        }

        start();
    }


    async function unregister(
        dotNetReference
    ) {

        listeners =
            listeners.filter(
                function (item) {

                    return item !== dotNetReference;
                }
            );
    }

    /*
     * ==============================================================
     * APPLICATION-WIDE LISTENER REGISTRATION
     * ==============================================================
     */

    function registerApplication(dotNetReference, entityFilter) {

        applicationListeners = applicationListeners.filter(function (item) {
            return item.ref !== dotNetReference;
        });

        applicationListeners.push({
            ref: dotNetReference,
            entity: typeof entityFilter === "string" && entityFilter.trim()
                ? entityFilter.trim()
                : null
        });

        start();
    }

    async function unregisterApplication(dotNetReference) {

        applicationListeners =
            applicationListeners.filter(
                function (item) {
                    return item.ref !== dotNetReference;
                }
            );

        if (applicationListeners.length === 0 && applicationRefreshTimer !== null) {
            clearTimeout(applicationRefreshTimer);
            applicationRefreshTimer = null;
            applicationRefreshPending.clear();
        }
    }


    // Allow Blazor components to register for periodic LocationHealth bridge
    function registerLocationHealth(dotNetReference) {
        try {
            const handler = function (ev) {
                try {
                    const detail = ev.detail;
                    // A navigation/disposal can invalidate the reference while
                    // the browser event listener is still queued. Ignore that
                    // transient lifecycle condition.
                    dotNetReference.invokeMethodAsync('LocationChanged', null)
                        .catch(function () {
                            try {
                                unregisterLocationHealth(dotNetReference);
                            }
                            catch (cleanupError) { }
                        });
                }
                catch (e) { }
            };

            window.addEventListener('location-health-updated', handler);

            // store handler on the dotNetReference so unregister can remove
            dotNetReference._locationHealthHandler = handler;
        }
        catch (e) { }
    }

    function unregisterLocationHealth(dotNetReference) {
        try {
            if (dotNetReference && dotNetReference._locationHealthHandler) {
                window.removeEventListener('location-health-updated', dotNetReference._locationHealthHandler);
                dotNetReference._locationHealthHandler = null;
            }
        }
        catch (e) { }
    }


    return {

        start: start,

        registerViewer:
            registerViewer,

        unregisterViewer:
            unregisterViewer,

        register:
            register,

        unregister:
            unregister,

        registerApplication:
            registerApplication,

        unregisterApplication:
            unregisterApplication,

        registerLocationHealth:
            registerLocationHealth,

        unregisterLocationHealth:
            unregisterLocationHealth

    };



})();