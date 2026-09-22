package com.biometric.app.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.biometric.app.api.*
import com.biometric.app.data.MainRepository
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.LocalFbpDeclaration
import com.biometric.app.sync.FirebaseSyncManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FbpDeclarationApprovalActivity : AppCompatActivity() {
 @Inject lateinit var sync: FirebaseSyncManager; @Inject lateinit var api: MobileApiService; @Inject lateinit var session: MobileSessionStore; @Inject lateinit var repo: MainRepository
 private lateinit var list: LinearLayout
 private var employees: List<com.biometric.app.data.entity.Employee> = emptyList()
 private var pendingGroups: Map<Int,List<LocalFbpDeclaration>> = emptyMap()
 override fun onCreate(b:Bundle?){super.onCreate(b); if(!session.userRole().contains("Admin",true)){finish();return}; build(); observe()}
 private fun build(){val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,16,16,16)};root.addView(TextView(this).apply{text="📄 FBP Declaration Approval";textSize=22f;setTextColor(Color.WHITE)});root.addView(TextView(this).apply{text="Pending declarations • same Web approval rules • realtime";setTextColor(Color.LTGRAY);setPadding(0,6,0,12)});list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};root.addView(ScrollView(this).apply{addView(list)},LinearLayout.LayoutParams(-1,0,1f));setContentView(root)}
 private fun observe(){
  lifecycleScope.launch {
   repo.allEmployeesFlow.collectLatest { employees = it; if (pendingGroups.isNotEmpty()) render(pendingGroups) }
  }
  lifecycleScope.launch {
   sync.getDataFlow<LocalFbpDeclaration>("fbp_declarations").collectLatest { rows ->
    pendingGroups = rows.filter { it.status.equals("Submitted", true) }.groupBy { it.employeeId }
    render(pendingGroups)
   }
  }
 }
 private fun render(groups:Map<Int,List<LocalFbpDeclaration>>){
  list.removeAllViews()
  if(groups.isEmpty()){list.addView(TextView(this).apply{text="No pending FBP declarations ✅";setPadding(16,32,16,32)});return}
  groups.values.sortedBy{it.first().employeeId}.forEach { rows ->
   val total = rows.sumOf { it.annualAllocatedAmount }
   val emp = rows.first().employeeId
   val fy = rows.first().financialYear
   val monthlySalary = employees.firstOrNull { it.employeeId.toIntOrNull() == emp }?.salaryRate ?: 0.0
   val available = monthlySalary * 12.0 * 0.10
   val overLimit = total > available + 0.005
   val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,16,16,16);setBackgroundColor(Color.rgb(30,43,61))}
   box.addView(TextView(this).apply{text="👤 Employee #$emp";textSize=18f;setTextColor(Color.WHITE)})
   box.addView(TextView(this).apply{text="FY $fy-${fy+1}  •  Allocation ₹${"%,.2f".format(total)}\nAvailable allowance ₹${"%,.2f".format(available)}";setTextColor(if(overLimit) Color.rgb(255,180,180) else Color.LTGRAY)})
   if(overLimit) box.addView(TextView(this).apply{text="⚠ Cannot approve: allocation exceeds available allowance.";setTextColor(Color.rgb(255,120,120));setPadding(0,8,0,8)})
   val buttons=LinearLayout(this).apply{gravity=Gravity.END}
   buttons.addView(Button(this).apply{text="🔒 Approve";isEnabled=!overLimit;setOnClickListener{act(emp,fy,true,"Approved")}})
   buttons.addView(Button(this).apply{text="❌ Reject";setOnClickListener{reject(emp,fy)}})
   box.addView(buttons)
   list.addView(box,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=12})
  }
 }
 private fun reject(emp:Int,year:Int){val input=EditText(this).apply{hint="Reason for rejection"};androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Reject FBP").setView(input).setPositiveButton("Reject"){_,_->act(emp,year,false,input.text.toString())}.setNegativeButton("Cancel",null).show()}
 private fun act(emp:Int,year:Int,approve:Boolean,remarks:String){
  lifecycleScope.launch {
   runCatching {
    val auth="Bearer ${session.token().orEmpty()}"
    val response = if(approve) api.approveAdminFbp(auth,emp,year) else api.rejectAdminFbp(auth,emp,year,AdminRemarkRequest(remarks))
    if(!response.isSuccessful) throw IllegalStateException(response.errorBody()?.string().orEmpty().ifBlank { "Server rejected the action" })
   }.onSuccess{Toast.makeText(this@FbpDeclarationApprovalActivity,if(approve)"Approved ✅" else "Rejected ❌",Toast.LENGTH_SHORT).show()}.onFailure{Toast.makeText(this@FbpDeclarationApprovalActivity,"Action failed: ${it.message}",Toast.LENGTH_LONG).show()}
  }
 }
}
