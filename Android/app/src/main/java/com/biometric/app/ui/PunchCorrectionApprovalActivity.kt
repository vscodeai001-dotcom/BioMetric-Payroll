package com.biometric.app.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.biometric.app.api.AdminPendingPunchDto
import com.biometric.app.api.MobileApiService
import com.biometric.app.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PunchCorrectionApprovalActivity : MotionBaseActivity() {
    @Inject lateinit var api: MobileApiService
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private fun auth() = "Bearer ${sessionStore.token() ?: ""}"

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(R.layout.activity_punch_correction_approval); list=findViewById(R.id.llPunchApprovalList); empty=findViewById(R.id.tvPunchApprovalEmpty); findViewById<View>(R.id.btnRefreshPunchApproval).setOnClickListener{load()}; findViewById<View>(R.id.toolbar).setOnClickListener{finish()}; load() }
    private fun load(){ lifecycleScope.launch { try { val r=api.adminPendingPunches(auth()); if(!r.isSuccessful){Toast.makeText(this@PunchCorrectionApprovalActivity,"Unable to load requests",Toast.LENGTH_SHORT).show();return@launch}; render(r.body().orEmpty()) } catch(e:Exception){Toast.makeText(this@PunchCorrectionApprovalActivity,e.message?:"Load failed",Toast.LENGTH_SHORT).show()} } }
    private fun render(rows:List<AdminPendingPunchDto>){ list.removeAllViews(); empty.visibility=if(rows.isEmpty())View.VISIBLE else View.GONE; rows.forEach{row-> val card=MaterialCardView(this).apply{radius=20f;cardElevation=2f;useCompatPadding=true}; val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,20,24,20)}; val title=TextView(this).apply{text="${row.employeeName}  •  ${row.logType}";textSize=17f;setTextColor(ContextCompat.getColor(this@PunchCorrectionApprovalActivity, R.color.text_primary))}; val info=TextView(this).apply{text="${row.punchTime}\nSource: ${row.deviceId}";textSize=13f;setPadding(0,8,0,12)}; val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}; val approve=MaterialButton(this).apply{text="✓ Approve";setOnClickListener{act(row,true)}}; val reject=MaterialButton(this).apply{text="✕ Reject";setOnClickListener{act(row,false)}}; actions.addView(approve,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)); actions.addView(reject,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)); box.addView(title);box.addView(info);box.addView(actions);card.addView(box);list.addView(card) } }
    private fun act(row:AdminPendingPunchDto,approve:Boolean){ lifecycleScope.launch { try { val r=if(approve)api.adminApprovePunch(auth(),row.id) else api.adminRejectPunch(auth(),row.id); if(r.isSuccessful){Toast.makeText(this@PunchCorrectionApprovalActivity,if(approve)"Punch approved ✓" else "Punch rejected",Toast.LENGTH_SHORT).show();load()}else Toast.makeText(this@PunchCorrectionApprovalActivity,"Action failed",Toast.LENGTH_SHORT).show()}catch(e:Exception){Toast.makeText(this@PunchCorrectionApprovalActivity,e.message?:"Action failed",Toast.LENGTH_SHORT).show()} } }
}
