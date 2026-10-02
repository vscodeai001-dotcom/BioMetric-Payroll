import os
import json
import sqlite3
import urllib.request
import urllib.parse
from datetime import datetime, timezone, timedelta
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

# ==============================================================================
# CONFIGURATION
# ==============================================================================
SQLITE_DB_PATH = os.path.abspath("Web/Payroll.Web/data/biometricpayroll-cache.db")
FIREBASE_URL = "https://biometricpayroll-default-rtdb.asia-southeast1.firebasedatabase.app"
FIREBASE_AUTH_URL = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=AIzaSyDE6qAFRWzKkZiH2G2Hr6a6GC98wjEzucg"
SUPER_ADMIN_EMAIL = "prakashshiva368@gmail.com"
SUPER_ADMIN_PASS = "Shiva@74482"
OWNER_UID = "biometricpayroll"
REFRESH_URL = "http://localhost:5050/api/internal/attendance-refresh"
REFRESH_SECRET = "_SKmPG4ifIU8bL8JErEop_YVRGhxq-j-1xzat8oPn6TDdjLj"

IST = timezone(timedelta(hours=5, minutes=30))

EMPLOYEES = {
    4: {"name": "Dinesh", "biometric_id": "1524", "shift_start": "10:30", "shift_end": "16:30", "sched_hours": 6.0},
    3: {"name": "Nevetha", "biometric_id": "1003", "shift_start": "06:00", "shift_end": "16:00", "sched_hours": 10.0}
}

# ==============================================================================
# FIREBASE AUTH & REST HELPERS
# ==============================================================================
import subprocess

def get_firebase_token():
    print("🔑 Obtaining Google OAuth2 access token via service account...")
    ps_script = '$ErrorActionPreference="Stop"; Add-Type -Path "Web/Payroll.Web/bin/Debug/net8.0/Google.Apis.Auth.dll"; $cred = [Google.Apis.Auth.OAuth2.GoogleCredential]::FromFile("C:\\FirebaseSecrets\\firebase-service-account.json").CreateScoped(@("https://www.googleapis.com/auth/firebase.database", "https://www.googleapis.com/auth/cloud-platform", "https://www.googleapis.com/auth/userinfo.email")); $cred.UnderlyingCredential.GetAccessTokenForRequestAsync().GetAwaiter().GetResult()'
    token = subprocess.check_output(["pwsh", "-NoProfile", "-Command", ps_script]).decode("utf-8").strip()
    return token

def firebase_delete(token, path):
    url = f"{FIREBASE_URL}/owners/{OWNER_UID}/{path}.json?access_token={token}"
    req = urllib.request.Request(url, method="DELETE")
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status
    except Exception as e:
        print(f"  Warning: Delete failed for {path}: {e}")
        return None

def firebase_patch(token, path, data):
    url = f"{FIREBASE_URL}/owners/{OWNER_UID}/{path}.json?access_token={token}"
    payload = json.dumps(data).encode("utf-8")
    req = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/json"}, method="PATCH")
    with urllib.request.urlopen(req) as resp:
        return resp.status

def to_timestamp_ms(dt_str):
    # dt_str format: "YYYY-MM-DD HH:MM:SS"
    dt = datetime.strptime(dt_str, "%Y-%m-%d %H:%M:%S").replace(tzinfo=IST)
    return int(dt.timestamp() * 1000)

# ==============================================================================
# SCENARIO DEFINITIONS (30 DAYS)
# ==============================================================================
def build_scenarios():
    scenarios = {}
    
    for day in range(1, 31):
        date_str = f"2026-09-{day:02d}"
        weekday = datetime.strptime(date_str, "%Y-%m-%d").weekday() # 6 is Sunday
        
        dinesh_punches = []
        dinesh_summary = {}
        nevetha_punches = []
        nevetha_summary = {}
        
        if day == 1:
            # Cat 1: Pure Physical Biometric Machine (Tier 1)
            desc = "Pure Physical Biometric Machine (Tier 1 Authority)"
            dinesh_punches = [
                ("10:30:00", "ZKTeco_001", "IN", "1524"),
                ("16:30:00", "ZKTeco_001", "OUT", "1524")
            ]
            dinesh_summary = {"status": "Present", "worked": 6.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("06:00:00", "ZKTeco_001", "IN", "1003"),
                ("16:00:00", "ZKTeco_001", "OUT", "1003")
            ]
            nevetha_summary = {"status": "Present", "worked": 10.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 2:
            # Cat 2: Pure Admin Manual Correction (Tier 2)
            desc = "Pure Admin Manual Correction (Tier 2 Authority)"
            dinesh_punches = [
                ("10:30:00", "ManualCorrection", "IN", "MANUAL_4_IN"),
                ("16:30:00", "ManualCorrection", "OUT", "MANUAL_4_OUT")
            ]
            dinesh_summary = {"status": "Present", "worked": 6.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("06:00:00", "ManualCorrection", "IN", "MANUAL_3_IN"),
                ("16:00:00", "ManualCorrection", "OUT", "MANUAL_3_OUT")
            ]
            nevetha_summary = {"status": "Present", "worked": 10.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 3:
            # Cat 3: Pure Geofence Auto (Tier 3 Dynamic Fallback)
            desc = "Pure Geofence Auto (Tier 3 Fallback)"
            dinesh_punches = [
                ("10:30:00", "GeofenceAuto", "AUTO_IN", "GEOFENCE_AUTO"),
                ("16:30:00", "GeofenceAuto", "AUTO_OUT", "GEOFENCE_AUTO")
            ]
            dinesh_summary = {"status": "Present", "worked": 6.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("06:00:00", "GeofenceAuto", "AUTO_IN", "GEOFENCE_AUTO"),
                ("16:00:00", "GeofenceAuto", "AUTO_OUT", "GEOFENCE_AUTO")
            ]
            nevetha_summary = {"status": "Present", "worked": 10.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 4:
            # Cat 4: Hybrid Machine IN + Geofence OUT
            desc = "Hybrid: Machine IN + Geofence Fallback OUT"
            dinesh_punches = [
                ("10:30:00", "ZKTeco_001", "IN", "1524"),
                ("16:30:00", "GeofenceAuto", "OUT", "GEOFENCE_AUTO")
            ]
            dinesh_summary = {"status": "Present", "worked": 6.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("06:00:00", "ZKTeco_001", "IN", "1003"),
                ("16:00:00", "GeofenceAuto", "OUT", "GEOFENCE_AUTO")
            ]
            nevetha_summary = {"status": "Present", "worked": 10.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 5:
            # Cat 5: Hybrid Manual IN + Geofence OUT
            desc = "Hybrid: Manual Admin IN + Geofence Fallback OUT"
            dinesh_punches = [
                ("10:30:00", "ManualCorrection", "IN", "MANUAL_4_IN"),
                ("16:30:00", "GeofenceAuto", "OUT", "GEOFENCE_AUTO")
            ]
            dinesh_summary = {"status": "Present", "worked": 6.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("06:00:00", "ManualCorrection", "IN", "MANUAL_3_IN"),
                ("16:00:00", "GeofenceAuto", "OUT", "GEOFENCE_AUTO")
            ]
            nevetha_summary = {"status": "Present", "worked": 10.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 6 or day == 13 or day == 20 or day == 27:
            # Cat 6, 13, 20, 27: Sunday Weekly Off
            desc = "Weekly Off (Sunday)"
            dinesh_punches = []
            dinesh_summary = {"status": "Weekly Off", "worked": 0.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            nevetha_punches = []
            nevetha_summary = {"status": "Weekly Off", "worked": 0.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 7:
            # Cat 7: Pre-Shift Early Arrival Overtime (Geofence arrival + Machine shift)
            desc = "Pre-Shift Early Arrival Overtime (20m Pre-Shift OT)"
            dinesh_punches = [
                ("10:10:00", "GeofenceAuto", "IN", "GEOFENCE_AUTO"),
                ("10:30:00", "ZKTeco_001", "IN", "1524"),
                ("16:30:00", "ZKTeco_001", "OUT", "1524")
            ]
            dinesh_summary = {"status": "Present", "worked": 6.0, "ot": "00:20:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("05:40:00", "GeofenceAuto", "IN", "GEOFENCE_AUTO"),
                ("06:00:00", "ZKTeco_001", "IN", "1003"),
                ("16:00:00", "ZKTeco_001", "OUT", "1003")
            ]
            nevetha_summary = {"status": "Present", "worked": 10.0, "ot": "00:20:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 8:
            # Cat 8: Post-Shift Overtime with Geofence Fallback Exit
            desc = "Post-Shift Overtime with Geofence Fallback (1h Post-Shift OT)"
            dinesh_punches = [
                ("10:30:00", "ZKTeco_001", "IN", "1524"),
                ("16:30:00", "ZKTeco_001", "OUT", "1524"),
                ("17:00:00", "ZKTeco_001", "IN", "1524"),
                ("18:00:00", "GeofenceAuto", "OUT", "GEOFENCE_AUTO")
            ]
            dinesh_summary = {"status": "Present", "worked": 6.0, "ot": "01:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("06:00:00", "ZKTeco_001", "IN", "1003"),
                ("16:00:00", "ZKTeco_001", "OUT", "1003"),
                ("16:30:00", "ZKTeco_001", "IN", "1003"),
                ("17:30:00", "GeofenceAuto", "OUT", "GEOFENCE_AUTO")
            ]
            nevetha_summary = {"status": "Present", "worked": 10.0, "ot": "01:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 9:
            # Cat 9: Mid-Shift Biometric Break (Physical scan overrides continuous shift)
            desc = "Mid-Shift Biometric Break (1h Lunch Scan Override)"
            dinesh_punches = [
                ("10:30:00", "ZKTeco_001", "IN", "1524"),
                ("13:00:00", "ZKTeco_001", "OUT", "1524"),
                ("14:00:00", "ZKTeco_001", "IN", "1524"),
                ("16:30:00", "ZKTeco_001", "OUT", "1524")
            ]
            dinesh_summary = {"status": "Present", "worked": 5.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("06:00:00", "ZKTeco_001", "IN", "1003"),
                ("12:00:00", "ZKTeco_001", "OUT", "1003"),
                ("13:00:00", "ZKTeco_001", "IN", "1003"),
                ("16:00:00", "ZKTeco_001", "OUT", "1003")
            ]
            nevetha_summary = {"status": "Present", "worked": 9.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 10:
            # Cat 10: Mid-Shift Geofence Exit & Return (Out of radius test)
            desc = "Mid-Shift Geofence Exit & Return (30m Radius Exit)"
            dinesh_punches = [
                ("10:30:00", "ManualCorrection", "IN", "MANUAL_4_IN"),
                ("13:00:00", "GeofenceAuto", "OUT", "GEOFENCE_AUTO"),
                ("13:30:00", "GeofenceAuto", "IN", "GEOFENCE_AUTO"),
                ("16:30:00", "ManualCorrection", "OUT", "MANUAL_4_OUT")
            ]
            dinesh_summary = {"status": "Present", "worked": 5.5, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("06:00:00", "ManualCorrection", "IN", "MANUAL_3_IN"),
                ("11:00:00", "GeofenceAuto", "OUT", "GEOFENCE_AUTO"),
                ("11:30:00", "GeofenceAuto", "IN", "GEOFENCE_AUTO"),
                ("16:00:00", "ManualCorrection", "OUT", "MANUAL_3_OUT")
            ]
            nevetha_summary = {"status": "Present", "worked": 9.5, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 11:
            # Cat 11: Noisy GPS Indoor Jumps Suppressed
            desc = "Noisy GPS Indoor Jumps Suppressed (Shift Preserved)"
            dinesh_punches = [
                ("10:30:00", "ManualCorrection", "IN", "MANUAL_4_IN"),
                ("10:45:00", "GeofenceAuto", "IN", "AUTO_NOISE_1"),
                ("11:15:00", "GeofenceAuto", "OUT", "AUTO_NOISE_2"),
                ("11:45:00", "GeofenceAuto", "IN", "AUTO_NOISE_3"),
                ("16:30:00", "ManualCorrection", "OUT", "MANUAL_4_OUT")
            ]
            dinesh_summary = {"status": "Present", "worked": 6.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("06:00:00", "ManualCorrection", "IN", "MANUAL_3_IN"),
                ("07:15:00", "GeofenceAuto", "IN", "AUTO_NOISE_1"),
                ("08:30:00", "GeofenceAuto", "OUT", "AUTO_NOISE_2"),
                ("09:00:00", "GeofenceAuto", "IN", "AUTO_NOISE_3"),
                ("16:00:00", "ManualCorrection", "OUT", "MANUAL_3_OUT")
            ]
            nevetha_summary = {"status": "Present", "worked": 10.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 12:
            # Cat 12: Late Arrival with Grace Period (10 min late, grace = 15m)
            desc = "Late Arrival within Grace Period (10m Late, No Penalty)"
            dinesh_punches = [
                ("10:40:00", "ZKTeco_001", "IN", "1524"),
                ("16:30:00", "ZKTeco_001", "OUT", "1524")
            ]
            dinesh_summary = {"status": "Present", "worked": 5.83, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("06:10:00", "ZKTeco_001", "IN", "1003"),
                ("16:00:00", "ZKTeco_001", "OUT", "1003")
            ]
            nevetha_summary = {"status": "Present", "worked": 9.83, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 14:
            # Cat 14: Late Arrival beyond Grace (45 min late)
            desc = "Late Arrival Beyond Grace (45m Penalty)"
            dinesh_punches = [
                ("11:15:00", "ZKTeco_001", "IN", "1524"),
                ("16:30:00", "ZKTeco_001", "OUT", "1524")
            ]
            dinesh_summary = {"status": "Present", "worked": 5.25, "ot": "00:00:00", "lateness": "00:45:00", "penalty": "00:45:00"}
            
            nevetha_punches = [
                ("06:45:00", "ZKTeco_001", "IN", "1003"),
                ("16:00:00", "ZKTeco_001", "OUT", "1003")
            ]
            nevetha_summary = {"status": "Present", "worked": 9.25, "ot": "00:00:00", "lateness": "00:45:00", "penalty": "00:45:00"}

        elif day == 15:
            # Cat 15: Early Departure (left 1 hour early)
            desc = "Early Departure (1h Early Exit)"
            dinesh_punches = [
                ("10:30:00", "ZKTeco_001", "IN", "1524"),
                ("15:30:00", "ZKTeco_001", "OUT", "1524")
            ]
            dinesh_summary = {"status": "Present", "worked": 5.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("06:00:00", "ZKTeco_001", "IN", "1003"),
                ("15:00:00", "ZKTeco_001", "OUT", "1003")
            ]
            nevetha_summary = {"status": "Present", "worked": 9.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 16:
            # Cat 16: Triple Source Hybrid (Machine + Manual + Geofence)
            desc = "Triple Source Hybrid (Machine IN + Manual OUT + GPS noise)"
            dinesh_punches = [
                ("10:30:00", "ZKTeco_001", "IN", "1524"),
                ("13:00:00", "GeofenceAuto", "IN", "GEOFENCE_AUTO"),
                ("16:30:00", "ManualCorrection", "OUT", "MANUAL_4_OUT")
            ]
            dinesh_summary = {"status": "Present", "worked": 6.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("06:00:00", "ZKTeco_001", "IN", "1003"),
                ("12:40:00", "GeofenceAuto", "IN", "GEOFENCE_AUTO"),
                ("16:00:00", "ManualCorrection", "OUT", "MANUAL_3_OUT")
            ]
            nevetha_summary = {"status": "Present", "worked": 10.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 17:
            # Cat 17: Pre-shift Bounce (01:42-01:44) + Shift + Post-Shift OT
            desc = "Early Midnight Ping (2m OT) + Shift + Post-Shift OT (1h)"
            dinesh_punches = [
                ("01:42:00", "GeofenceAuto", "IN", "AUTO_339"),
                ("01:44:00", "GeofenceAuto", "OUT", "AUTO_340"),
                ("10:30:00", "ZKTeco_001", "IN", "1524"),
                ("16:30:00", "ZKTeco_001", "OUT", "1524"),
                ("17:00:00", "ZKTeco_001", "IN", "1524"),
                ("18:00:00", "ZKTeco_001", "OUT", "1524")
            ]
            dinesh_summary = {"status": "Present", "worked": 6.0, "ot": "01:02:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("01:42:00", "GeofenceAuto", "IN", "AUTO_333"),
                ("01:44:00", "GeofenceAuto", "OUT", "AUTO_334"),
                ("06:00:00", "ZKTeco_001", "IN", "1003"),
                ("16:00:00", "ZKTeco_001", "OUT", "1003"),
                ("16:30:00", "ZKTeco_001", "IN", "1003"),
                ("17:30:00", "ZKTeco_001", "OUT", "1003")
            ]
            nevetha_summary = {"status": "Present", "worked": 10.0, "ot": "01:02:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 18:
            # Cat 18: Unclosed Session / Missing OUT Punch (Single Punch)
            desc = "Unclosed Session (Missing OUT Punch)"
            dinesh_punches = [
                ("10:30:00", "ZKTeco_001", "IN", "1524")
            ]
            dinesh_summary = {"status": "Missing Punch", "worked": 0.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("06:00:00", "ZKTeco_001", "IN", "1003")
            ]
            nevetha_summary = {"status": "Missing Punch", "worked": 0.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 19:
            # Cat 19: Unclosed Session with Multiple Duplicate INs
            desc = "Unclosed Session with Multiple Consecutive INs"
            dinesh_punches = [
                ("10:30:00", "ZKTeco_001", "IN", "1524"),
                ("11:00:00", "GeofenceAuto", "IN", "GEOFENCE_AUTO")
            ]
            dinesh_summary = {"status": "Missing Punch", "worked": 0.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("06:00:00", "ZKTeco_001", "IN", "1003"),
                ("06:30:00", "GeofenceAuto", "IN", "GEOFENCE_AUTO")
            ]
            nevetha_summary = {"status": "Missing Punch", "worked": 0.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 21:
            # Cat 21: Full Day Absent
            desc = "Full Day Absent (Zero Punches)"
            dinesh_punches = []
            dinesh_summary = {"status": "Absent", "worked": 0.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            nevetha_punches = []
            nevetha_summary = {"status": "Absent", "worked": 0.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 22:
            # Cat 22: Half Day Work
            desc = "Half Day Work (3h for Dinesh, 5h for Nevetha)"
            dinesh_punches = [
                ("10:30:00", "ZKTeco_001", "IN", "1524"),
                ("13:30:00", "ZKTeco_001", "OUT", "1524")
            ]
            dinesh_summary = {"status": "Half Day", "worked": 3.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("06:00:00", "ZKTeco_001", "IN", "1003"),
                ("11:00:00", "ZKTeco_001", "OUT", "1003")
            ]
            nevetha_summary = {"status": "Half Day", "worked": 5.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 23:
            # Cat 23: Company Holiday
            desc = "Company Holiday / Festival"
            dinesh_punches = []
            dinesh_summary = {"status": "Holiday", "worked": 0.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            nevetha_punches = []
            nevetha_summary = {"status": "Holiday", "worked": 0.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 24:
            # Cat 24: Multiple Work Segments (Two Mid-Shift Breaks)
            desc = "Two Biometric Breaks (Morning Tea & Lunch)"
            dinesh_punches = [
                ("10:30:00", "ZKTeco_001", "IN", "1524"),
                ("12:00:00", "ZKTeco_001", "OUT", "1524"),
                ("12:30:00", "ZKTeco_001", "IN", "1524"),
                ("14:30:00", "ZKTeco_001", "OUT", "1524"),
                ("15:00:00", "ZKTeco_001", "IN", "1524"),
                ("16:30:00", "ZKTeco_001", "OUT", "1524")
            ]
            dinesh_summary = {"status": "Present", "worked": 5.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("06:00:00", "ZKTeco_001", "IN", "1003"),
                ("09:00:00", "ZKTeco_001", "OUT", "1003"),
                ("09:30:00", "ZKTeco_001", "IN", "1003"),
                ("12:30:00", "ZKTeco_001", "OUT", "1003"),
                ("13:00:00", "ZKTeco_001", "IN", "1003"),
                ("16:00:00", "ZKTeco_001", "OUT", "1003")
            ]
            nevetha_summary = {"status": "Present", "worked": 9.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 25:
            # Cat 25: Rapid Consecutive Duplicate Punches (15s double-tap debounced)
            desc = "Rapid Consecutive Duplicate Punches (Debounced)"
            dinesh_punches = [
                ("10:30:00", "ZKTeco_001", "IN", "1524"),
                ("10:30:15", "ZKTeco_001", "IN", "1524"),
                ("16:30:00", "ZKTeco_001", "OUT", "1524")
            ]
            dinesh_summary = {"status": "Present", "worked": 6.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("06:00:00", "ZKTeco_001", "IN", "1003"),
                ("06:00:15", "ZKTeco_001", "IN", "1003"),
                ("16:00:00", "ZKTeco_001", "OUT", "1003")
            ]
            nevetha_summary = {"status": "Present", "worked": 10.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 26:
            # Cat 26: Stray Midnight OUT Punch + Normal Shift
            desc = "Stray Midnight OUT Punch + Normal Shift"
            dinesh_punches = [
                ("00:05:00", "GeofenceAuto", "OUT", "GEOFENCE_AUTO"),
                ("10:30:00", "ZKTeco_001", "IN", "1524"),
                ("16:30:00", "ZKTeco_001", "OUT", "1524")
            ]
            dinesh_summary = {"status": "Present", "worked": 6.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("00:05:00", "GeofenceAuto", "OUT", "GEOFENCE_AUTO"),
                ("06:00:00", "ZKTeco_001", "IN", "1003"),
                ("16:00:00", "ZKTeco_001", "OUT", "1003")
            ]
            nevetha_summary = {"status": "Present", "worked": 10.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 28:
            # Cat 28: Off-Day Overtime Only
            desc = "Special Overtime Day (Pure Overtime)"
            dinesh_punches = [
                ("11:00:00", "ZKTeco_001", "IN", "1524"),
                ("15:00:00", "ZKTeco_001", "OUT", "1524")
            ]
            dinesh_summary = {"status": "Present", "worked": 0.0, "ot": "04:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("08:00:00", "ZKTeco_001", "IN", "1003"),
                ("14:00:00", "ZKTeco_001", "OUT", "1003")
            ]
            nevetha_summary = {"status": "Present", "worked": 0.0, "ot": "06:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 29:
            # Cat 29: Complex Multi-Zone Hybrid (Pre-Shift Geofence OT + Biometric Shift + Manual OUT + Post-Shift Geofence OT)
            desc = "Complex Multi-Zone Hybrid (Pre-OT + Shift + Post-OT)"
            dinesh_punches = [
                ("10:15:00", "GeofenceAuto", "IN", "GEOFENCE_AUTO"),
                ("10:30:00", "ZKTeco_001", "IN", "1524"),
                ("16:30:00", "ManualCorrection", "OUT", "MANUAL_4_OUT"),
                ("17:00:00", "ZKTeco_001", "IN", "1524"),
                ("18:30:00", "GeofenceAuto", "OUT", "GEOFENCE_AUTO")
            ]
            dinesh_summary = {"status": "Present", "worked": 6.0, "ot": "01:45:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("05:45:00", "GeofenceAuto", "IN", "GEOFENCE_AUTO"),
                ("06:00:00", "ZKTeco_001", "IN", "1003"),
                ("16:00:00", "ManualCorrection", "OUT", "MANUAL_3_OUT"),
                ("16:30:00", "ZKTeco_001", "IN", "1003"),
                ("17:30:00", "GeofenceAuto", "OUT", "GEOFENCE_AUTO")
            ]
            nevetha_summary = {"status": "Present", "worked": 10.0, "ot": "01:15:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        elif day == 30:
            # Cat 30: Perfect Closing Standard Shift
            desc = "Month-End Flawless Standard Shift"
            dinesh_punches = [
                ("10:30:00", "ZKTeco_001", "IN", "1524"),
                ("16:30:00", "ZKTeco_001", "OUT", "1524")
            ]
            dinesh_summary = {"status": "Present", "worked": 6.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}
            
            nevetha_punches = [
                ("06:00:00", "ZKTeco_001", "IN", "1003"),
                ("16:00:00", "ZKTeco_001", "OUT", "1003")
            ]
            nevetha_summary = {"status": "Present", "worked": 10.0, "ot": "00:00:00", "lateness": "00:00:00", "penalty": "00:00:00"}

        scenarios[date_str] = {
            "day": day,
            "desc": desc,
            "dinesh": {"punches": dinesh_punches, "summary": dinesh_summary},
            "nevetha": {"punches": nevetha_punches, "summary": nevetha_summary}
        }
        
    return scenarios

# ==============================================================================
# MAIN EXECUTION
# ==============================================================================
def main():
    print("=" * 70)
    print("🚀 SEEDING 30-DAY HYBRID PROBABILITY SCENARIOS (SEPTEMBER 2026)")
    print("=" * 70)
    
    # 1. Connect to SQLite
    conn = sqlite3.connect(SQLITE_DB_PATH)
    cur = conn.cursor()
    
    # 2. Get Firebase Token
    token = get_firebase_token()
    
    # 3. Clean up existing September records from SQLite
    print("\n🧹 Step 1: Cleaning September records from SQLite...")
    cur.execute("DELETE FROM attendancelogs WHERE punchtime LIKE '2026-09%'")
    deleted_punches = cur.rowcount
    cur.execute("DELETE FROM daily_summaries WHERE shiftdate LIKE '2026-09%'")
    deleted_summaries = cur.rowcount
    conn.commit()
    print(f"  Deleted {deleted_punches} punches and {deleted_summaries} daily summaries from SQLite.")
    
    # 4. Clean up September records from Firebase
    print("\n🧹 Step 2: Cleaning September records from Firebase Realtime Database...")
    # Fetch existing punch keys
    rtdb_punches_url = f"{FIREBASE_URL}/owners/{OWNER_UID}/attendance_punches.json?access_token={token}"
    req = urllib.request.Request(rtdb_punches_url)
    try:
        raw_p = json.loads(urllib.request.urlopen(req).read().decode("utf-8")) or {}
        p_dict = raw_p if isinstance(raw_p, dict) else {str(i): v for i, v in enumerate(raw_p) if v}
        for k, v in p_dict.items():
            if v and str(v.get("date", "")).startswith("2026-09"):
                firebase_delete(token, f"attendance_punches/{k}")
                firebase_delete(token, f"attendance/{k}")
        print("  Cleaned matching September punches from Firebase.")
    except Exception as e:
        print(f"  Warning fetching Firebase punches: {e}")
        
    rtdb_sums_url = f"{FIREBASE_URL}/owners/{OWNER_UID}/daily_summaries.json?access_token={token}"
    req = urllib.request.Request(rtdb_sums_url)
    try:
        raw_s = json.loads(urllib.request.urlopen(req).read().decode("utf-8")) or {}
        s_dict = raw_s if isinstance(raw_s, dict) else {str(i): v for i, v in enumerate(raw_s) if v}
        for k, v in s_dict.items():
            if v and str(v.get("shiftDate", "")).startswith("2026-09"):
                firebase_delete(token, f"daily_summaries/{k}")
        print("  Cleaned matching September summaries from Firebase.")
    except Exception as e:
        print(f"  Warning fetching Firebase summaries: {e}")
        
    # 5. Build 30-Day Scenarios
    scenarios = build_scenarios()
    
    # Find max existing IDs in SQLite
    cur.execute("SELECT MAX(logid) FROM attendancelogs")
    next_log_id = (cur.fetchone()[0] or 0) + 1
    
    cur.execute("SELECT MAX(summaryid) FROM daily_summaries")
    next_summary_id = (cur.fetchone()[0] or 0) + 1
    
    sqlite_punches_to_insert = []
    sqlite_summaries_to_insert = []
    
    firebase_punches_patch = {}
    firebase_summaries_patch = {}
    
    print("\n📝 Step 3: Generating 30 days of category probability records...")
    
    now_utc_str = datetime.now(timezone.utc).isoformat()
    
    for date_str, sc in sorted(scenarios.items()):
        day_num = sc["day"]
        desc = sc["desc"]
        
        # Process Dinesh (EmpID 4)
        for emp_id, emp_key in [(4, "dinesh"), (3, "nevetha")]:
            emp_info = EMPLOYEES[emp_id]
            data = sc[emp_key]
            punches = data["punches"]
            summary = data["summary"]
            
            # Create punches
            for time_str, dev_id, log_type, bio_id in punches:
                punch_dt_str = f"{date_str} {time_str}"
                ts_ms = to_timestamp_ms(punch_dt_str)
                log_id = next_log_id
                next_log_id += 1
                
                # Biometric punch ID
                biometric_id = bio_id
                if bio_id.startswith("MANUAL_"):
                    biometric_id = f"MANUAL_{emp_id}_{date_str.replace('-','')}_{time_str.replace(':','')}_{log_type}"
                elif bio_id == "GEOFENCE_AUTO":
                    biometric_id = f"AUTO_{emp_id}_{ts_ms}"
                elif bio_id.isdigit():
                    biometric_id = f"{bio_id}_{ts_ms}"
                    
                lat = 11.9308101 if "Geofence" in dev_id or "ZKTeco" in dev_id else None
                lng = 79.7848124 if "Geofence" in dev_id or "ZKTeco" in dev_id else None
                
                sqlite_punches_to_insert.append((
                    log_id, emp_id, biometric_id, punch_dt_str, dev_id, log_type, 1, lat, lng
                ))
                
                punch_fb = {
                    "punchId": str(log_id),
                    "attendanceId": str(log_id),
                    "employeeId": emp_id,
                    "staffId": str(emp_id),
                    "biometricId": biometric_id,
                    "timestamp": ts_ms,
                    "checkInTime": ts_ms,
                    "createdAt": ts_ms,
                    "date": date_str,
                    "deviceId": dev_id,
                    "type": "IN" if "IN" in log_type.upper() else "OUT",
                    "source": log_type,
                    "note": log_type,
                    "logType": log_type,
                    "status": "APPROVED",
                    "isApproved": True,
                    "latitude": lat,
                    "longitude": lng,
                    "_entity": "AttendancePunch",
                    "_key": str(log_id),
                    "_updatedUtc": now_utc_str
                }
                firebase_punches_patch[str(log_id)] = punch_fb

            # Create summary
            sum_id = next_summary_id
            next_summary_id += 1
            
            sched_hours = emp_info["sched_hours"]
            sched_dur_str = f"{int(sched_hours):02d}:00:00"
            sched_dur_ms = int(sched_hours * 3600000)
            
            status = summary.get("status", "Present")
            worked_hours = summary.get("worked", 0.0)
            ot_str = summary.get("ot", "00:00:00")
            ot_ms = 0
            if ot_str != "00:00:00":
                parts = ot_str.split(":")
                ot_ms = (int(parts[0]) * 3600 + int(parts[1]) * 60 + int(parts[2])) * 1000
                
            penalty_str = summary.get("penalty", "00:00:00")
            penalty_ms = 0
            if penalty_str != "00:00:00":
                parts = penalty_str.split(":")
                penalty_ms = (int(parts[0]) * 3600 + int(parts[1]) * 60 + int(parts[2])) * 1000
                
            lateness_str = summary.get("lateness", "00:00:00")
            lateness_ms = 0
            if lateness_str != "00:00:00":
                parts = lateness_str.split(":")
                lateness_ms = (int(parts[0]) * 3600 + int(parts[1]) * 60 + int(parts[2])) * 1000

            sqlite_summaries_to_insert.append((
                sum_id, emp_id, date_str, status, str(worked_hours), ot_str, penalty_str, lateness_str,
                "00:00:00", sched_dur_str, "0.0", 0
            ))
            
            sum_key = str(sum_id)
            summary_fb = {
                "summaryId": sum_id,
                "employeeId": emp_id,
                "staffId": emp_id,
                "shiftDate": date_str,
                "date": date_str,
                "status": status,
                "earnedStandardHours": worked_hours,
                "totalOvertimeMs": ot_ms,
                "totalPenaltyMs": penalty_ms,
                "totalLatenessMs": lateness_ms,
                "totalBreakPenaltyMs": 0,
                "scheduledShiftDurationMs": sched_dur_ms,
                "shiftAllowanceEarned": 0.0,
                "isManualOverride": False,
                "_entity": "DailySummary",
                "_key": sum_key,
                "_updatedUtc": now_utc_str
            }
            firebase_summaries_patch[sum_key] = summary_fb
            
        print(f"  Day {day_num:02d} ({date_str}): {desc}")

    # 6. Insert into SQLite
    print("\n💾 Step 4: Writing records to local SQLite...")
    cur.executemany("""
        INSERT INTO attendancelogs (logid, employeeid, biometricid, punchtime, DeviceID, LogType, is_approved, latitude, longitude)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, sqlite_punches_to_insert)
    
    cur.executemany("""
        INSERT INTO daily_summaries (summaryid, employeeid, shiftdate, status, earned_standard_hours, total_overtime_duration, total_penalty_duration, total_lateness, total_break_penalty, scheduled_shift_duration, shift_allowance_earned, is_manual_override)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, sqlite_summaries_to_insert)
    
    conn.commit()
    conn.close()
    print(f"  Successfully inserted {len(sqlite_punches_to_insert)} punches and {len(sqlite_summaries_to_insert)} daily summaries into SQLite.")

    # 7. Write to Firebase
    print("\n☁️ Step 5: Publishing records to Firebase Realtime Database...")
    print(f"  Syncing {len(firebase_punches_patch)} punches to 'attendance_punches'...")
    firebase_patch(token, "attendance_punches", firebase_punches_patch)
    print(f"  Syncing {len(firebase_punches_patch)} punches to 'attendance'...")
    firebase_patch(token, "attendance", firebase_punches_patch)
    print(f"  Syncing {len(firebase_summaries_patch)} summaries to 'daily_summaries'...")
    firebase_patch(token, "daily_summaries", firebase_summaries_patch)
    print("  Firebase sync complete!")

    # 8. Notify Live Refresh
    print("\n📡 Step 6: Triggering real-time broadcast refresh...")
    try:
        req = urllib.request.Request(
            REFRESH_URL,
            headers={"X-Attendance-Refresh-Secret": REFRESH_SECRET},
            data=b"",
            method="POST"
        )
        with urllib.request.urlopen(req) as resp:
            print(f"  Realtime refresh triggered successfully (HTTP {resp.status})!")
    except Exception as e:
        print(f"  Note: Refresh broadcast response: {e}")

    print("\n" + "=" * 70)
    print("🎉 ALL 30 DAYS OF SEPTEMBER SCENARIOS SEEDED SUCCESSFULLY!")
    print("=" * 70)

if __name__ == "__main__":
    main()
