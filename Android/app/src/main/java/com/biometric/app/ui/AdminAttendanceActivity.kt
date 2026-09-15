package com.biometric.app.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.api.AdminAttendanceRow
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.sync.AdminRealtimeCoordinator
import com.google.android.material.appbar.MaterialToolbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class AdminAttendanceActivity : AppCompatActivity() {
    @Inject lateinit var api: MobileApiService
    @Inject lateinit var session: MobileSessionStore
    @Inject lateinit var realtimeCoordinator: AdminRealtimeCoordinator
    private lateinit var adapter: AttendanceAdapter
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private var from = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private var to = Calendar.getInstance()
    private lateinit var tvFrom: TextView; private lateinit var tvTo: TextView; private lateinit var progress: View; private lateinit var empty: TextView; private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(R.layout.activity_admin_attendance)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        tvFrom=findViewById(R.id.tvFrom); tvTo=findViewById(R.id.tvTo); progress=findViewById(R.id.progress); empty=findViewById(R.id.empty); summary=findViewById(R.id.tvSummary)
        tvFrom.text=fmt.format(from.time); tvTo.text=fmt.format(to.time)
        tvFrom.setOnClickListener { pick(from) { from=it; tvFrom.text=fmt.format(from.time) } }; tvTo.setOnClickListener { pick(to) { to=it; tvTo.text=fmt.format(to.time) } }
        findViewById<View>(R.id.btnGenerate).setOnClickListener { load() }
        adapter=AttendanceAdapter(); findViewById<RecyclerView>(R.id.rvAttendance).layoutManager=LinearLayoutManager(this); findViewById<RecyclerView>(R.id.rvAttendance).adapter=adapter
        load()
        realtimeCoordinator.start { if (!isFinishing) load() }
    }

    override fun onDestroy() {
        realtimeCoordinator.stop()
        super.onDestroy()
    }
    private fun pick(base:Calendar, done:(Calendar)->Unit) { DatePickerDialog(this,{_,y,m,d->done(Calendar.getInstance().apply{set(y,m,d)})},base.get(Calendar.YEAR),base.get(Calendar.MONTH),base.get(Calendar.DAY_OF_MONTH)).show() }
    private fun load() { lifecycleScope.launch { progress.isVisible=true; empty.isVisible=false; try { val token=session.token() ?: return@launch; val h="Bearer $token"; val f=fmt.format(from.time); val t=fmt.format(to.time); val r=api.adminAttendanceDaily(h,f,t,0); val rows=r.body()?.rows.orEmpty(); adapter.submit(rows); empty.isVisible=rows.isEmpty(); val c=api.adminCompanyAttendanceSummary(h,f,t).body()?.summary; summary.text= if(c==null) "" else "${c.totalEmployeesProcessed} employees  •  Worked ${"%.2f".format(c.totalWorkedHours)}h  •  OT ${minutes(c.totalOvertimeMinutes)}  •  Penalty ${minutes(c.totalPenaltyMinutes)}" } catch(e:Exception){ Toast.makeText(this@AdminAttendanceActivity,e.message ?: "Unable to load attendance",Toast.LENGTH_SHORT).show() } finally { progress.isVisible=false } } }
    private fun minutes(v:Double)=String.format(Locale.US,"%dh %02dm",(v/60).toInt(),(v%60).toInt())
    private class AttendanceAdapter:RecyclerView.Adapter<Holder>() { private val data=mutableListOf<AdminAttendanceRow>(); fun submit(rows:List<AdminAttendanceRow>){data.clear();data.addAll(rows);notifyDataSetChanged()}; override fun onCreateViewHolder(p:ViewGroup,v:Int)=Holder(LayoutInflater.from(p.context).inflate(R.layout.item_admin_attendance,p,false)); override fun onBindViewHolder(h:Holder,i:Int){val x=data[i];h.title.text="${x.employeeName}  •  ${x.date}";h.status.text=x.status;h.details.text="Worked ${"%.2f".format(x.workedHours)}h  •  OT ${"%.0f".format(x.overtimeMinutes)}m  •  Penalty ${"%.0f".format(x.penaltyMinutes)}m\n${x.punches}"};override fun getItemCount()=data.size }
    private class Holder(v:View):RecyclerView.ViewHolder(v){val title=v.findViewById<TextView>(R.id.tvTitle);val status=v.findViewById<TextView>(R.id.tvStatus);val details=v.findViewById<TextView>(R.id.tvDetails)}
}
