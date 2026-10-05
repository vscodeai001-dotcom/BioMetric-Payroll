import sqlite3

conn = sqlite3.connect(r'Web\Payroll.Web\data\biometricpayroll-cache.db')
cur = conn.cursor()
tables = [r[0] for r in cur.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()]
for t in ['attendance_punches', 'attendancelogs', 'daily_summaries', 'employees']:
    if t in tables:
        cnt = cur.execute(f'SELECT COUNT(*) FROM "{t}"').fetchone()[0]
        print(f'{t}: {cnt}')
        for r in cur.execute(f'SELECT * FROM "{t}" LIMIT 5').fetchall():
            print('  ', r)
    else:
        print(f'{t}: NOT FOUND')
conn.close()
