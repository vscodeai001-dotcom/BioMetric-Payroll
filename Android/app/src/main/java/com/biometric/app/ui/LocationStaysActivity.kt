package com.biometric.app.ui
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.biometric.app.data.MainRepository
import com.biometric.app.data.entity.Employee
import com.biometric.app.sync.SignalRManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import kotlin.math.*
@AndroidEntryPoint class LocationStaysActivity:AppCompatActivity(){@Inject lateinit var repo:MainRepository;@Inject lateinit var signal:SignalRManager;private lateinit var list:LinearLayout;private var employees:List<Employee> = emptyList()
 override fun onCreate(b:Bundle?){
  super.onCreate(b)
  build()
  lifecycleScope.launch {
   repo.allEmployeesFlow.collect { current ->
    employees = current
    renderEmployees()
   }
  }
 }
 private fun build(){val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,16,16,16)};root.addView(TextView(this).apply{text="📍 10-Minute Location Stays";textSize=22f;setTextColor(Color.WHITE)});root.addView(TextView(this).apply{text="Existing GPS history • 10 m cluster • minimum 10 minutes";setTextColor(Color.LTGRAY);setPadding(0,6,0,12)});list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};root.addView(ScrollView(this).apply{addView(list)},LinearLayout.LayoutParams(-1,0,1f));setContentView(root)}
 private fun renderEmployees(){list.removeAllViews();if(employees.isEmpty()){list.addView(TextView(this).apply{text="No employees available"});return};employees.filter{it.isActive}.forEach{e->list.addView(Button(this).apply{text="👤 ${e.name}  •  Detect stays";setOnClickListener{load(e)}})}}
 private fun load(e:Employee){lifecycleScope.launch{val points=signal.loadTrackingHistory(e.employeeId.toIntOrNull()?:0,2000);val stays=detect(points);list.removeAllViews();list.addView(TextView(this@LocationStaysActivity).apply{text="${e.name} • ${stays.size} detected stays";textSize=18f;setTextColor(Color.WHITE);setPadding(0,0,0,12)});if(stays.isEmpty())list.addView(TextView(this@LocationStaysActivity).apply{text="No 10-minute stays detected."});stays.forEach{ s->list.addView(TextView(this@LocationStaysActivity).apply{text="📍 ${fmt(s.start)} → ${fmt(s.end)}\n⏱ ${duration(s.end-s.start)} • ${s.points} GPS points\n±${"%.1f".format(s.acc)} m • ${"%.6f".format(s.lat)}, ${"%.6f".format(s.lon)}";setTextColor(Color.LTGRAY);setPadding(14,14,14,14);setBackgroundColor(Color.rgb(29,42,60))},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=8})}}}
 private data class Stay(val start:Long,val end:Long,val points:Int,val acc:Double,val lat:Double,val lon:Double)
 private fun detect(p:List<SignalRManager.LiveLocation>):List<Stay>{val out=mutableListOf<Stay>();val groups=p.filter{it.timestamp!=null}.groupBy{it.sessionId};groups.values.forEach{a->val o=a.sortedBy{parse(it.timestamp!!)};var c=mutableListOf<SignalRManager.LiveLocation>();for(x in o){if(c.isEmpty()){c.add(x);continue};val prev=c.last();val d=hav(prev.latitude,prev.longitude,x.latitude,x.longitude);val gap=parse(x.timestamp!!)-parse(prev.timestamp!!);if(d<=10&&gap>=0&&gap<=600000)c.add(x) else {add(c,out);c=mutableListOf(x)}};add(c,out)};return out.sortedByDescending{it.start}}
 private fun add(c:List<SignalRManager.LiveLocation>,out:MutableList<Stay>){if(c.size<2)return;val s=parse(c.first().timestamp!!);val e=parse(c.last().timestamp!!);if(e-s<600000)return;out.add(Stay(s,e,c.size,c.map{it.accuracyMeters}.average(),c.map{it.latitude}.average(),c.map{it.longitude}.average()))}
 private fun parse(v:String)=runCatching{java.time.Instant.parse(v).toEpochMilli()}.getOrElse{runCatching{SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX",Locale.US).parse(v)?.time?:0}.getOrDefault(0)}
 private fun fmt(v:Long)=SimpleDateFormat("dd-MMM-yyyy HH:mm:ss",Locale.getDefault()).format(Date(v));private fun duration(ms:Long)="${ms/60000}m ${(ms/1000)%60}s";private fun hav(a:Double,b:Double,c:Double,d:Double):Double{val r=6371000.0;val p1=Math.toRadians(a);val p2=Math.toRadians(c);val dp=Math.toRadians(c-a);val dl=Math.toRadians(d-b);val x=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2);return r*2*atan2(sqrt(x),sqrt(1-x))}}
