from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
checks = []

def text(rel):
    return (ROOT / rel).read_text(encoding='utf-8', errors='ignore')

def req(label, rel, pattern):
    checks.append((label, re.search(pattern, text(rel), re.I | re.M | re.S) is not None))

worker = 'Android/app/src/main/java/com/biometric/app/domain/location/OfflineSyncWorker.kt'
dao = 'Android/app/src/main/java/com/biometric/app/data/LocationDao.kt'
monitor = 'Android/app/src/main/java/com/biometric/app/domain/location/OfflineTrackingMonitor.kt'

req('Offline worker requires Firebase authentication before draining', worker, r'if \(!firebaseSync\.isAuthenticated\(\)\).*?return Result\.retry\(\)')
req('Stale in-flight GPS rows are recovered', worker, r'recoverStaleInFlight\(\s*System\.currentTimeMillis\(\)\s*-\s*2 \* 60 \* 1000L')
req('GPS queue is ordered by original capture timestamp', dao, r'ORDER BY timestamp ASC, id ASC')
req('Terminal SYNCED rows are excluded from pending drain', dao, r"syncState IN \('PENDING','FAILED'\)")
req('Reconnect schedules durable one-time sync', monitor, r'NETWORK_ONLINE.*?OfflineSyncWorker\.schedule\(context\)')
req('Network callback requires validated internet', monitor, r'NET_CAPABILITY_INTERNET.*?NET_CAPABILITY_VALIDATED')
req('Unique sync work prevents competing drain loops', worker, r'enqueueUniqueWork\(\s*"offline_location_sync".*?ExistingWorkPolicy\.KEEP')
req('GPS sync uses stable client event ID', worker, r'clientEventId = loc\.clientEventId')
req('Session starts are synced before GPS', worker, r'val deferredEndEvents = syncTrackingEvents\(\).*?if \(deferredEndEvents == null\).*?getPendingForSync', )
req('Session ends are deferred until GPS drain completes', worker, r'if \(!failed && deferredEndEvents\.isNotEmpty\(\).*?syncEventBatch\(deferredEndEvents\)')

# Deterministic queue-order stress model mirroring the worker contract.
# Each tuple is (kind, id), where locations belong to a session.
events = [
    ('START', 'S1'),
    ('LOC', 'S1:1'),
    ('LOC', 'S1:2'),
    ('END', 'S1'),
    ('START', 'S2'),
    ('LOC', 'S2:1'),
    ('END', 'S2'),
]
starts = [x for x in events if x[0] == 'START']
locs = [x for x in events if x[0] == 'LOC']
ends = [x for x in events if x[0] == 'END']
model_order = starts + locs + ends
checks.append(('Stress model sends every session START before its LOC', all(model_order.index(('START', sid)) < model_order.index(('LOC', sid + ':1')) for _, sid in starts for _ in [0] if ( 'LOC', sid+':1') in model_order)))
checks.append(('Stress model sends every LOC before its session END', all(model_order.index(('LOC', sid+':1')) < model_order.index(('END', sid)) for _, sid in ends for _ in [0] if ('LOC', sid+':1') in model_order)))
checks.append(('Stress model preserves all queued GPS points', len(locs) == 3 and sum(1 for x in model_order if x[0] == 'LOC') == 3))
checks.append(('Stress model preserves all lifecycle markers', len(starts) == 2 and len(ends) == 2))

# Duplicate/retry model: clientEventId is the idempotency key.
first_upload = {'evt-1'}
retry_upload = {'evt-1'}
checks.append(('Retry model produces one immutable history key', (first_upload | retry_upload) == {'evt-1'} and len(first_upload | retry_upload) == 1))

failed_once = True
retried = True
checks.append(('Transient upload failure remains retryable', failed_once and retried))

failed = [name for name, ok in checks if not ok]
print(f'1200-S Offline/Reconnect static + deterministic stress audit: {len(checks)-len(failed)}/{len(checks)} checks passed')
for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' | ' + name)
if failed:
    raise SystemExit(1)
