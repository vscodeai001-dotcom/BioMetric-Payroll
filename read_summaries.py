import sqlite3

conn = sqlite3.connect(r'Web\Payroll.Web\data\biometricpayroll-cache.db')
cur = conn.cursor()
for row in cur.execute('SELECT * FROM daily_summaries').fetchall():
    print(row)
conn.close()
