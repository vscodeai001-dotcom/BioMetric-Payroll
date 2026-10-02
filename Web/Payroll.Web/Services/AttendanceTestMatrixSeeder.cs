using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging;
using Payroll.Shared;
using Payroll.Shared.Data;

namespace Payroll.Web.Services
{
    public sealed class AttendanceTestMatrixSeeder
    {
        private readonly AppDbContext _dbContext;
        private readonly FirebaseAttendanceMutationService _firebaseMutation;
        private readonly AttendanceRefreshService _refreshService;
        private readonly ILogger<AttendanceTestMatrixSeeder> _logger;

        public AttendanceTestMatrixSeeder(
            AppDbContext dbContext,
            FirebaseAttendanceMutationService firebaseMutation,
            AttendanceRefreshService refreshService,
            ILogger<AttendanceTestMatrixSeeder> logger)
        {
            _dbContext = dbContext;
            _firebaseMutation = firebaseMutation;
            _refreshService = refreshService;
            _logger = logger;
        }

        public async Task<int> SeedOctoberMatrixAsync()
        {
            _logger.LogInformation("Starting injection of 31-day October test matrix for Dinesh (#4) and Nevetha (#3)...");

            var dinesh = await _dbContext.Employees.FirstOrDefaultAsync(e => e.EmployeeID == 4 || e.Name == "Dinesh");
            var nevetha = await _dbContext.Employees.FirstOrDefaultAsync(e => e.EmployeeID == 3 || e.Name == "Nevetha");

            int dineshId = dinesh?.EmployeeID ?? 4;
            int nevethaId = nevetha?.EmployeeID ?? 3;
            string dineshBio = dinesh?.BiometricID ?? "1524";
            string nevethaBio = nevetha?.BiometricID ?? "1003";

            var startDate = new DateTime(2026, 10, 1, 0, 0, 0);
            var endDate = new DateTime(2026, 11, 1, 0, 0, 0);

            // Clean old October logs for Dinesh & Nevetha
            var existingLogs = await _dbContext.AttendanceLogs
                .Where(l => (l.EmployeeID == dineshId || l.EmployeeID == nevethaId) &&
                            l.PunchTime >= startDate && l.PunchTime < endDate)
                .ToListAsync();

            if (existingLogs.Count > 0)
            {
                _dbContext.AttendanceLogs.RemoveRange(existingLogs);
            }

            var startD = DateOnly.FromDateTime(startDate);
            var endD = DateOnly.FromDateTime(endDate);
            var existingSummaries = await _dbContext.DailySummaries
                .Where(s => (s.EmployeeID == dineshId || s.EmployeeID == nevethaId) &&
                            s.ShiftDate >= startD && s.ShiftDate < endD)
                .ToListAsync();

            if (existingSummaries.Count > 0)
            {
                _dbContext.DailySummaries.RemoveRange(existingSummaries);
            }

            await _dbContext.SaveChangesAsync();

            // Prepare entries from user's test matrix
            var rawData = GetMatrixEntries();
            var newPunches = new List<AttendanceLog>();
            int nextLogId = 10000;

            foreach (var (dateStr, empName, rawLog) in rawData)
            {
                bool isDinesh = empName.Equals("Dinesh", StringComparison.OrdinalIgnoreCase);
                int empId = isDinesh ? dineshId : nevethaId;
                string empBio = isDinesh ? dineshBio : nevethaBio;
                var date = DateTime.ParseExact(dateStr, "dd-MMM-yyyy", CultureInfo.InvariantCulture);

                var lines = rawLog.Split('\n', StringSplitOptions.RemoveEmptyEntries);
                foreach (var line in lines)
                {
                    var trimmed = line.Trim();
                    if (string.IsNullOrWhiteSpace(trimmed)) continue;

                    // Parse e.g. "10:30:00 Machine #0 IN" or "10:20:00 ManualCorrection #1 IN"
                    var parts = trimmed.Split(' ', StringSplitOptions.RemoveEmptyEntries);
                    if (parts.Length < 3) continue;

                    var timeParts = parts[0].Split(':');
                    int h = int.Parse(timeParts[0]);
                    int m = int.Parse(timeParts[1]);
                    int s = timeParts.Length > 2 ? int.Parse(timeParts[2]) : 0;
                    var punchTime = new DateTime(date.Year, date.Month, date.Day, h, m, s);

                    string device = parts[1];
                    string type = parts[^1].ToUpperInvariant(); // IN or OUT

                    string bioId = empBio;
                    if (device.Contains("Geofence", StringComparison.OrdinalIgnoreCase))
                    {
                        bioId = "AUTO_" + Guid.NewGuid().ToString("N")[..8];
                    }

                    var log = new AttendanceLog
                    {
                        LogID = nextLogId++,
                        EmployeeID = empId,
                        BiometricID = bioId,
                        PunchTime = punchTime,
                        LogType = type,
                        DeviceID = device,
                        IsApproved = true
                    };

                    newPunches.Add(log);
                }
            }

            _dbContext.AttendanceLogs.AddRange(newPunches);
            await _dbContext.SaveChangesAsync();

            _logger.LogInformation("Saved {Count} raw matrix punches to SQLite. Now publishing to Firebase SSOT...", newPunches.Count);

            foreach (var punch in newPunches)
            {
                await _firebaseMutation.UpsertPunchAsync(punch);
            }

            await _refreshService.NotifyAllDataChangedAsync();
            _logger.LogInformation("Successfully injected October 30-day matrix and notified all clients.");

            return newPunches.Count;
        }

        private static List<(string Date, string Employee, string RawLog)> GetMatrixEntries()
        {
            return new List<(string Date, string Employee, string RawLog)>
            {
                // 01-Oct-2026
                ("01-Oct-2026", "Dinesh", "10:30:00 Machine #0 IN\n16:30:00 Machine #0 OUT"),
                ("01-Oct-2026", "Nevetha", "06:00:00 Machine #0 IN\n16:00:00 Machine #0 OUT"),

                // 02-Oct-2026
                ("02-Oct-2026", "Dinesh", "10:30:00 Machine #0 IN\n16:30:00 Machine #0 OUT"),
                ("02-Oct-2026", "Nevetha", "10:20:00 ManualCorrection #1 IN\n12:00:00 AndroidGeofenceAuto #2 OUT\n13:00:00 ManualCorrection #3 IN\n13:05:00 Machine #0 IN\n16:35:00 Machine #0 OUT"),

                // 03-Oct-2026
                ("03-Oct-2026", "Dinesh", "10:25:00 ManualCorrection #1 IN\n16:35:00 Machine #0 OUT"),
                ("03-Oct-2026", "Nevetha", "10:15:00 Machine #0 IN\n12:30:00 AndroidGeofenceAuto #2 OUT\n13:00:00 Machine #0 IN\n16:30:00 Machine #0 OUT\n16:31:00 AndroidGeofenceAuto #5 OUT"),

                // 04-Oct-2026
                ("04-Oct-2026", "Dinesh", "10:28:00 AndroidGeofenceAuto #1 IN\n16:32:00 AndroidGeofenceAuto #2 OUT"),
                ("04-Oct-2026", "Nevetha", "10:00:00 AndroidGeofenceAuto #1 IN\n10:01:00 AndroidGeofenceAuto #2 IN\n10:02:00 AndroidGeofenceAuto #3 IN\n16:30:00 AndroidGeofenceAuto #4 OUT\n16:31:00 AndroidGeofenceAuto #5 OUT"),

                // 05-Oct-2026
                ("05-Oct-2026", "Dinesh", "10:20:00 AndroidGeofenceAuto #1 IN\n10:25:00 ManualCorrection #2 IN\n10:31:00 Machine #0 IN\n16:35:00 Machine #0 OUT"),
                ("05-Oct-2026", "Nevetha", "09:50:00 AndroidGeofenceAuto #1 IN\n10:05:00 Machine #0 IN\n12:00:00 Machine #0 OUT\n12:30:00 ManualCorrection #4 IN\n12:35:00 Machine #0 IN\n16:40:00 Machine #0 OUT"),

                // 06-Oct-2026
                ("06-Oct-2026", "Dinesh", "10:25:00 Machine #0 IN\n12:40:00 Machine #0 OUT\n13:05:00 Machine #0 IN\n16:40:00 Machine #0 OUT"),
                ("06-Oct-2026", "Nevetha", "09:45:00 Machine #0 IN\n12:00:00 Machine #0 OUT\n12:30:00 AndroidGeofenceAuto #3 IN\n12:35:00 Machine #0 IN\n15:00:00 AndroidGeofenceAuto #5 OUT\n15:05:00 Machine #0 OUT\n16:45:00 Machine #0 IN\n18:30:00 Machine #0 OUT"),

                // 07-Oct-2026
                ("07-Oct-2026", "Dinesh", "10:25:00 Machine #0 IN\n12:40:00 AndroidGeofenceAuto #2 OUT\n13:05:00 AndroidGeofenceAuto #3 IN\n16:40:00 Machine #0 OUT"),
                ("07-Oct-2026", "Nevetha", "10:10:00 ManualCorrection #1 IN\n10:15:00 Machine #0 IN\n12:00:00 ManualCorrection #3 OUT\n12:05:00 Machine #0 OUT\n13:00:00 AndroidGeofenceAuto #5 IN\n13:05:00 Machine #0 IN\n16:35:00 Machine #0 OUT"),

                // 08-Oct-2026
                ("08-Oct-2026", "Dinesh", "10:20:00 AndroidGeofenceAuto #1 IN\n10:22:00 ManualCorrection #2 IN\n10:31:00 Machine #0 IN\n16:35:00 Machine #0 OUT"),
                ("08-Oct-2026", "Nevetha", "10:00:00 Machine #0 IN\n12:00:00 Machine #0 OUT\n12:30:00 Machine #0 IN\n14:00:00 ManualCorrection #4 OUT\n14:05:00 Machine #0 OUT\n14:20:00 AndroidGeofenceAuto #6 IN\n14:25:00 Machine #0 IN\n16:45:00 Machine #0 OUT"),

                // 09-Oct-2026
                ("09-Oct-2026", "Dinesh", "10:20:00 ManualCorrection #1 IN\n12:00:00 AndroidGeofenceAuto #2 OUT\n13:00:00 ManualCorrection #3 IN\n13:05:00 Machine #0 IN\n16:35:00 Machine #0 OUT"),
                ("09-Oct-2026", "Nevetha", "10:15:00 AndroidGeofenceAuto #1 IN\n12:00:00 AndroidGeofenceAuto #2 OUT\n12:30:00 ManualCorrection #3 IN\n12:35:00 Machine #0 IN\n14:00:00 AndroidGeofenceAuto #5 OUT\n14:05:00 Machine #0 OUT\n14:20:00 Machine #0 IN\n16:40:00 Machine #0 OUT"),

                // 10-Oct-2026
                ("10-Oct-2026", "Dinesh", "10:15:00 Machine #0 IN\n12:30:00 AndroidGeofenceAuto #2 OUT\n13:00:00 Machine #0 IN\n16:30:00 Machine #0 OUT\n16:31:00 AndroidGeofenceAuto #5 OUT"),
                ("10-Oct-2026", "Nevetha", "09:40:00 Machine #0 IN\n09:41:00 Machine #0 IN\n12:30:00 AndroidGeofenceAuto #3 OUT\n12:31:00 ManualCorrection #4 OUT\n13:00:00 Machine #0 IN\n16:30:00 Machine #0 OUT"),

                // 11-Oct-2026
                ("11-Oct-2026", "Dinesh", "10:00:00 AndroidGeofenceAuto #1 IN\n10:01:00 AndroidGeofenceAuto #2 IN\n10:02:00 AndroidGeofenceAuto #3 IN\n16:30:00 AndroidGeofenceAuto #4 OUT\n16:31:00 AndroidGeofenceAuto #5 OUT"),
                ("11-Oct-2026", "Nevetha", "10:20:00 ManualCorrection #1 IN\n12:00:00 AndroidGeofenceAuto #2 OUT\n12:10:00 ManualCorrection #3 IN\n12:12:00 Machine #0 IN\n15:00:00 AndroidGeofenceAuto #5 OUT\n15:02:00 Machine #0 OUT"),

                // 12-Oct-2026
                ("12-Oct-2026", "Dinesh", "09:50:00 AndroidGeofenceAuto #1 IN\n10:05:00 Machine #0 IN\n12:00:00 Machine #0 OUT\n12:30:00 ManualCorrection #4 IN\n12:35:00 Machine #0 IN\n16:40:00 Machine #0 OUT"),
                ("12-Oct-2026", "Nevetha", "10:00:00 AndroidGeofenceAuto #1 IN\n12:00:00 AndroidGeofenceAuto #2 OUT\n12:30:00 AndroidGeofenceAuto #3 IN\n15:00:00 AndroidGeofenceAuto #4 OUT\n15:30:00 AndroidGeofenceAuto #5 IN\n16:30:00 AndroidGeofenceAuto #6 OUT"),

                // 13-Oct-2026
                ("13-Oct-2026", "Dinesh", "09:45:00 Machine #0 IN\n12:00:00 Machine #0 OUT\n12:30:00 AndroidGeofenceAuto #3 IN\n12:35:00 Machine #0 IN\n15:00:00 AndroidGeofenceAuto #5 OUT\n15:05:00 Machine #0 OUT\n16:45:00 Machine #0 IN\n18:30:00 Machine #0 OUT"),
                ("13-Oct-2026", "Nevetha", "09:55:00 AndroidGeofenceAuto #1 IN\n10:00:00 ManualCorrection #2 IN\n10:10:00 Machine #0 IN\n12:00:00 AndroidGeofenceAuto #4 OUT\n12:05:00 Machine #0 OUT\n13:00:00 AndroidGeofenceAuto #6 IN\n13:05:00 Machine #0 IN\n16:30:00 Machine #0 OUT"),

                // 14-Oct-2026
                ("14-Oct-2026", "Dinesh", "10:10:00 ManualCorrection #1 IN\n10:15:00 Machine #0 IN\n12:00:00 ManualCorrection #3 OUT\n12:05:00 Machine #0 OUT\n13:00:00 AndroidGeofenceAuto #5 IN\n13:05:00 Machine #0 IN\n16:35:00 Machine #0 OUT"),
                ("14-Oct-2026", "Nevetha", "10:15:00 Machine #0 IN\n12:00:00 Machine #0 OUT\n12:20:00 AndroidGeofenceAuto #3 IN\n12:25:00 ManualCorrection #4 IN\n12:30:00 Machine #0 IN\n16:30:00 Machine #0 OUT\n16:31:00 AndroidGeofenceAuto #7 OUT"),

                // 15-Oct-2026
                ("15-Oct-2026", "Dinesh", "10:00:00 Machine #0 IN\n12:00:00 Machine #0 OUT\n12:30:00 Machine #0 IN\n14:00:00 ManualCorrection #4 OUT\n14:05:00 Machine #0 OUT\n14:20:00 AndroidGeofenceAuto #6 IN\n14:25:00 Machine #0 IN\n16:45:00 Machine #0 OUT"),
                ("15-Oct-2026", "Nevetha", "10:10:00 AndroidGeofenceAuto #1 IN\n11:30:00 AndroidGeofenceAuto #2 OUT\n11:31:00 AndroidGeofenceAuto #3 OUT\n12:00:00 ManualCorrection #4 IN\n12:05:00 Machine #0 IN\n14:00:00 Machine #0 OUT\n14:30:00 AndroidGeofenceAuto #7 IN\n14:35:00 Machine #0 IN\n16:45:00 Machine #0 OUT"),

                // 16-Oct-2026
                ("16-Oct-2026", "Dinesh", "10:15:00 AndroidGeofenceAuto #1 IN\n12:00:00 AndroidGeofenceAuto #2 OUT\n12:30:00 ManualCorrection #3 IN\n12:35:00 Machine #0 IN\n14:00:00 AndroidGeofenceAuto #5 OUT\n14:05:00 Machine #0 OUT\n14:20:00 Machine #0 IN\n16:40:00 Machine #0 OUT"),
                ("16-Oct-2026", "Nevetha", "09:50:00 AndroidGeofenceAuto #1 IN\n10:00:00 ManualCorrection #2 IN\n10:20:00 Machine #0 IN\n12:00:00 AndroidGeofenceAuto #4 OUT\n12:05:00 Machine #0 OUT\n12:30:00 AndroidGeofenceAuto #6 IN\n12:40:00 Machine #0 IN\n16:30:00 Machine #0 OUT"),

                // 17-Oct-2026
                ("17-Oct-2026", "Dinesh", "09:40:00 Machine #0 IN\n09:41:00 Machine #0 IN\n12:30:00 AndroidGeofenceAuto #3 OUT\n12:31:00 ManualCorrection #4 OUT\n13:00:00 Machine #0 IN\n16:30:00 Machine #0 OUT"),
                ("17-Oct-2026", "Nevetha", "10:00:00 Machine #0 IN\n12:00:00 Machine #0 OUT\n12:30:00 AndroidGeofenceAuto #3 IN\n12:35:00 ManualCorrection #4 IN\n12:40:00 Machine #0 IN\n16:30:00 Machine #0 OUT\n18:00:00 AndroidGeofenceAuto #7 OUT"),

                // 18-Oct-2026
                ("18-Oct-2026", "Dinesh", "10:20:00 ManualCorrection #1 IN\n12:00:00 AndroidGeofenceAuto #2 OUT\n12:10:00 ManualCorrection #3 IN\n12:12:00 Machine #0 IN\n15:00:00 AndroidGeofenceAuto #5 OUT\n15:02:00 Machine #0 OUT"),
                ("18-Oct-2026", "Nevetha", "09:30:00 Machine #0 IN\n12:00:00 Machine #0 OUT\n12:01:00 AndroidGeofenceAuto #3 OUT\n12:30:00 ManualCorrection #4 IN\n12:35:00 Machine #0 IN\n15:00:00 AndroidGeofenceAuto #6 OUT\n15:05:00 Machine #0 OUT\n15:30:00 AndroidGeofenceAuto #8 IN\n15:35:00 Machine #0 IN\n18:30:00 Machine #0 OUT"),

                // 19-Oct-2026
                ("19-Oct-2026", "Dinesh", "10:00:00 AndroidGeofenceAuto #1 IN\n12:00:00 AndroidGeofenceAuto #2 OUT\n12:30:00 AndroidGeofenceAuto #3 IN\n15:00:00 AndroidGeofenceAuto #4 OUT\n15:30:00 AndroidGeofenceAuto #5 IN\n16:30:00 AndroidGeofenceAuto #6 OUT"),
                ("19-Oct-2026", "Nevetha", "09:55:00 AndroidGeofenceAuto #1 IN\n10:00:00 ManualCorrection #2 IN\n10:10:00 Machine #0 IN\n12:00:00 AndroidGeofenceAuto #4 OUT\n12:05:00 Machine #0 OUT\n13:00:00 AndroidGeofenceAuto #6 IN\n13:05:00 Machine #0 IN\n16:30:00 Machine #0 OUT\n16:35:00 ManualCorrection #9 OUT"),

                // 20-Oct-2026
                ("20-Oct-2026", "Dinesh", "09:55:00 AndroidGeofenceAuto #1 IN\n10:00:00 ManualCorrection #2 IN\n10:10:00 Machine #0 IN\n12:00:00 AndroidGeofenceAuto #4 OUT\n12:05:00 Machine #0 OUT\n13:00:00 AndroidGeofenceAuto #6 IN\n13:05:00 Machine #0 IN\n16:30:00 Machine #0 OUT"),
                ("20-Oct-2026", "Nevetha", "10:20:00 Machine #0 IN\n12:00:00 Machine #0 OUT\n12:20:00 AndroidGeofenceAuto #3 IN\n12:25:00 Machine #0 IN\n13:30:00 AndroidGeofenceAuto #5 OUT\n13:35:00 ManualCorrection #6 OUT\n14:00:00 Machine #0 IN\n16:30:00 Machine #0 OUT\n17:30:00 AndroidGeofenceAuto #9 OUT"),

                // 21-Oct-2026
                ("21-Oct-2026", "Dinesh", "10:15:00 Machine #0 IN\n12:00:00 Machine #0 OUT\n12:20:00 AndroidGeofenceAuto #3 IN\n12:25:00 ManualCorrection #4 IN\n12:30:00 Machine #0 IN\n16:30:00 Machine #0 OUT\n16:31:00 AndroidGeofenceAuto #7 OUT"),
                ("21-Oct-2026", "Nevetha", "09:20:00 AndroidGeofenceAuto #1 IN\n09:25:00 ManualCorrection #2 IN\n09:30:00 Machine #0 IN\n11:00:00 Machine #0 OUT\n11:30:00 AndroidGeofenceAuto #5 IN\n11:35:00 Machine #0 IN\n13:00:00 Machine #0 OUT\n13:30:00 ManualCorrection #8 IN\n13:35:00 Machine #0 IN\n16:30:00 Machine #0 OUT\n18:00:00 Machine #0 OUT"),

                // 22-Oct-2026
                ("22-Oct-2026", "Dinesh", "10:10:00 AndroidGeofenceAuto #1 IN\n11:30:00 AndroidGeofenceAuto #2 OUT\n11:31:00 AndroidGeofenceAuto #3 OUT\n12:00:00 ManualCorrection #4 IN\n12:05:00 Machine #0 IN\n14:00:00 Machine #0 OUT\n14:30:00 AndroidGeofenceAuto #7 IN\n14:35:00 Machine #0 IN\n16:45:00 Machine #0 OUT"),
                ("22-Oct-2026", "Nevetha", "09:00:00 AndroidGeofenceAuto #1 IN\n09:05:00 ManualCorrection #2 IN\n09:10:00 Machine #0 IN\n11:00:00 AndroidGeofenceAuto #4 OUT\n11:05:00 ManualCorrection #5 OUT\n11:10:00 Machine #0 OUT\n11:30:00 AndroidGeofenceAuto #7 IN\n11:35:00 Machine #0 IN\n14:00:00 AndroidGeofenceAuto #9 OUT\n14:05:00 Machine #0 OUT\n14:30:00 Machine #0 IN\n16:30:00 Machine #0 OUT\n20:00:00 AndroidGeofenceAuto #13 OUT"),

                // 23-Oct-2026
                ("23-Oct-2026", "Dinesh", "09:50:00 AndroidGeofenceAuto #1 IN\n10:00:00 ManualCorrection #2 IN\n10:20:00 Machine #0 IN\n12:00:00 AndroidGeofenceAuto #4 OUT\n12:05:00 Machine #0 OUT\n12:30:00 AndroidGeofenceAuto #6 IN\n12:40:00 Machine #0 IN\n16:30:00 Machine #0 OUT"),
                ("23-Oct-2026", "Nevetha", "10:30:00 Machine #0 IN\n12:00:00 AndroidGeofenceAuto #2 OUT\n12:05:00 ManualCorrection #3 OUT\n12:30:00 Machine #0 IN\n14:00:00 AndroidGeofenceAuto #5 OUT\n14:05:00 Machine #0 OUT\n14:30:00 ManualCorrection #7 IN\n14:35:00 Machine #0 IN\n16:30:00 Machine #0 OUT\n19:00:00 AndroidGeofenceAuto #10 OUT"),

                // 24-Oct-2026
                ("24-Oct-2026", "Dinesh", "10:00:00 Machine #0 IN\n12:00:00 Machine #0 OUT\n12:30:00 AndroidGeofenceAuto #3 IN\n12:35:00 ManualCorrection #4 IN\n12:40:00 Machine #0 IN\n16:30:00 Machine #0 OUT\n18:00:00 AndroidGeofenceAuto #7 OUT"),
                ("24-Oct-2026", "Nevetha", "09:00:00 AndroidGeofenceAuto #1 IN\n09:05:00 ManualCorrection #2 IN\n09:10:00 Machine #0 IN\n11:00:00 AndroidGeofenceAuto #4 OUT\n11:05:00 ManualCorrection #5 OUT\n11:10:00 Machine #0 OUT\n11:30:00 AndroidGeofenceAuto #7 IN\n11:35:00 Machine #0 IN\n14:00:00 AndroidGeofenceAuto #9 OUT\n14:05:00 Machine #0 OUT\n14:30:00 Machine #0 IN\n16:30:00 Machine #0 OUT\n16:31:00 Machine #0 OUT\n18:30:00 AndroidGeofenceAuto #14 OUT"),

                // 25-Oct-2026
                ("25-Oct-2026", "Dinesh", "09:30:00 Machine #0 IN\n12:00:00 Machine #0 OUT\n12:01:00 AndroidGeofenceAuto #3 OUT\n12:30:00 ManualCorrection #4 IN\n12:35:00 Machine #0 IN\n15:00:00 AndroidGeofenceAuto #6 OUT\n15:05:00 Machine #0 OUT\n15:30:00 AndroidGeofenceAuto #8 IN\n15:35:00 Machine #0 IN\n18:30:00 Machine #0 OUT"),
                ("25-Oct-2026", "Nevetha", "10:30:00 Machine #0 IN\n16:30:00 Machine #0 OUT"),

                // 26-Oct-2026
                ("26-Oct-2026", "Dinesh", "09:55:00 AndroidGeofenceAuto #1 IN\n10:00:00 ManualCorrection #2 IN\n10:10:00 Machine #0 IN\n12:00:00 AndroidGeofenceAuto #4 OUT\n12:05:00 Machine #0 OUT\n13:00:00 AndroidGeofenceAuto #6 IN\n13:05:00 Machine #0 IN\n16:30:00 Machine #0 OUT\n16:35:00 ManualCorrection #9 OUT"),
                ("26-Oct-2026", "Nevetha", "10:25:00 ManualCorrection #1 IN\n16:35:00 Machine #0 OUT"),

                // 27-Oct-2026
                ("27-Oct-2026", "Dinesh", "10:20:00 Machine #0 IN\n12:00:00 Machine #0 OUT\n12:20:00 AndroidGeofenceAuto #3 IN\n12:25:00 Machine #0 IN\n13:30:00 AndroidGeofenceAuto #5 OUT\n13:35:00 ManualCorrection #6 OUT\n14:00:00 Machine #0 IN\n16:30:00 Machine #0 OUT\n17:30:00 AndroidGeofenceAuto #9 OUT"),
                ("27-Oct-2026", "Nevetha", "10:28:00 AndroidGeofenceAuto #1 IN\n16:32:00 AndroidGeofenceAuto #2 OUT"),

                // 28-Oct-2026
                ("28-Oct-2026", "Dinesh", "09:20:00 AndroidGeofenceAuto #1 IN\n09:25:00 ManualCorrection #2 IN\n09:30:00 Machine #0 IN\n11:00:00 Machine #0 OUT\n11:30:00 AndroidGeofenceAuto #5 IN\n11:35:00 Machine #0 IN\n13:00:00 Machine #0 OUT\n13:30:00 ManualCorrection #8 IN\n13:35:00 Machine #0 IN\n16:30:00 Machine #0 OUT\n18:00:00 Machine #0 OUT"),
                ("28-Oct-2026", "Nevetha", "10:20:00 AndroidGeofenceAuto #1 IN\n10:25:00 ManualCorrection #2 IN\n10:31:00 Machine #0 IN\n16:35:00 Machine #0 OUT"),

                // 29-Oct-2026
                ("29-Oct-2026", "Dinesh", "09:00:00 AndroidGeofenceAuto #1 IN\n09:05:00 ManualCorrection #2 IN\n09:10:00 Machine #0 IN\n11:00:00 AndroidGeofenceAuto #4 OUT\n11:05:00 ManualCorrection #5 OUT\n11:10:00 Machine #0 OUT\n11:30:00 AndroidGeofenceAuto #7 IN\n11:35:00 Machine #0 IN\n14:00:00 AndroidGeofenceAuto #9 OUT\n14:05:00 Machine #0 OUT\n14:30:00 Machine #0 IN\n16:30:00 Machine #0 OUT\n20:00:00 AndroidGeofenceAuto #13 OUT"),
                ("29-Oct-2026", "Nevetha", "10:25:00 Machine #0 IN\n12:40:00 Machine #0 OUT\n13:05:00 Machine #0 IN\n16:40:00 Machine #0 OUT"),

                // 30-Oct-2026
                ("30-Oct-2026", "Dinesh", "10:30:00 Machine #0 IN\n12:00:00 AndroidGeofenceAuto #2 OUT\n12:05:00 ManualCorrection #3 OUT\n12:30:00 Machine #0 IN\n14:00:00 AndroidGeofenceAuto #5 OUT\n14:05:00 Machine #0 OUT\n14:30:00 ManualCorrection #7 IN\n14:35:00 Machine #0 IN\n16:30:00 Machine #0 OUT\n19:00:00 AndroidGeofenceAuto #10 OUT"),
                ("30-Oct-2026", "Nevetha", "10:25:00 Machine #0 IN\n12:40:00 AndroidGeofenceAuto #2 OUT\n13:05:00 AndroidGeofenceAuto #3 IN\n16:40:00 Machine #0 OUT"),

                // 31-Oct-2026
                ("31-Oct-2026", "Dinesh", "09:00:00 AndroidGeofenceAuto #1 IN\n09:05:00 ManualCorrection #2 IN\n09:10:00 Machine #0 IN\n11:00:00 AndroidGeofenceAuto #4 OUT\n11:05:00 ManualCorrection #5 OUT\n11:10:00 Machine #0 OUT\n11:30:00 AndroidGeofenceAuto #7 IN\n11:35:00 Machine #0 IN\n14:00:00 AndroidGeofenceAuto #9 OUT\n14:05:00 Machine #0 OUT\n14:30:00 Machine #0 IN\n16:30:00 Machine #0 OUT\n16:31:00 Machine #0 OUT\n18:30:00 AndroidGeofenceAuto #14 OUT"),
                ("31-Oct-2026", "Nevetha", "10:20:00 AndroidGeofenceAuto #1 IN\n10:22:00 ManualCorrection #2 IN\n10:31:00 Machine #0 IN\n16:35:00 Machine #0 OUT")
            };
        }
    }
}
