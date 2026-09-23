package com.biometric.app.ui
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.biometric.app.data.entity.AuditLog
import com.biometric.app.sync.FirebaseSyncManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
@AndroidEntryPoint class AttendanceEventMonitoringActivity:AppCompatActivity(){@Inject lateinit var sync:FirebaseSyncManager;private lateinit var list:LinearLayout
 override fun onCreate(b:Bundle?){super.onCreate(b);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,16,16,16)};root.addView(TextView(this).apply{text="📡 Attendance Event Monitoring";textSize=22f;setTextColor(Color.WHITE)});root.addView(TextView(this).apply{text="Realtime AUTH_SESSION events • same Web filters and event meanings";setTextColor(Color.LTGRAY);setPadding(0,6,0,12)});list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};root.addView(ScrollView(this).apply{addView(list)},LinearLayout.LayoutParams(-1,0,1f));setContentView(root);val q=sync.getOwnerRef()?.child("audit_logs")?.limitToLast(100);if(q!=null){lifecycleScope.launch{sync.getQueryFlow<AuditLog>(q).collectLatest{rows->render(rows.filter{it.action.equals("AUTH_SESSION",true)}.sortedByDescending{it.timestamp})}}}}
 private fun render(rows:List<AuditLog>){list.removeAllViews();if(rows.isEmpty()){list.addView(TextView(this).apply{text="No monitored events";setPadding(16,30,16,30)});return};val fmt=SimpleDateFormat("dd-MMM-yyyy HH:mm:ss",Locale.getDefault());rows.forEach{r->val event=runCatching{JSONObject(r.newValue?:"{}").optString("EventType","UNKNOWN")}.getOrDefault("UNKNOWN");val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(14,14,14,14);setBackgroundColor(Color.rgb(29,42,60))};c.addView(TextView(this).apply{text="$event  •  ${fmt.format(Date(r.timestamp))}";textSize=16f;setTextColor(Color.WHITE)});c.addView(TextView(this).apply{text="${r.userDisplayName.ifBlank{r.userId}}\n${r.targetId.orEmpty()}\n${r.newValue.orEmpty()}";setTextColor(Color.LTGRAY)});list.addView(c,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=8})}}
}
