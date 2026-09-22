package com.biometric.app.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.biometric.app.api.FbpComponentDto
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.LocalFbpComponent
import com.biometric.app.sync.FirebaseSyncManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FbpComponentsActivity : AppCompatActivity() {
    @Inject lateinit var sync: FirebaseSyncManager
    @Inject lateinit var api: MobileApiService
    @Inject lateinit var session: MobileSessionStore
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!session.userRole().contains("SuperAdmin", ignoreCase = true)) {
            Toast.makeText(this, "SuperAdmin access required", Toast.LENGTH_LONG).show()
            finish(); return
        }
        build(); observe()
    }

    private fun build() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,16,16,16) }
        root.addView(TextView(this).apply { text="🏦 FBP Component Configuration"; textSize=22f; setTextColor(Color.WHITE) })
        root.addView(TextView(this).apply { text="Same Web fields • annual limit • tax exempt • active"; setTextColor(Color.LTGRAY); setPadding(0,6,0,12) })
        root.addView(Button(this).apply { text="＋ Add Component"; setOnClickListener { edit(null) } })
        list = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
    }

    private fun observe() {
        lifecycleScope.launch {
            sync.getDataFlow<LocalFbpComponent>("fbp_components").collectLatest { rows ->
                list.removeAllViews()
                rows.sortedBy { it.name }.forEach { card(it) }
            }
        }
    }

    private fun card(c: LocalFbpComponent) {
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,16,16,16);setBackgroundColor(Color.rgb(29,42,60))}
        box.addView(TextView(this).apply{text="🎁 ${c.name}";textSize=18f;setTextColor(Color.WHITE)})
        box.addView(TextView(this).apply{text="Max annual ₹${"%,.0f".format(c.maxAnnualLimit)}  •  Tax exempt ${if(c.isTaxExempt)"Yes" else "No"}  •  ${if(c.isActive)"Active 🟢" else "Inactive ⚪"}";setTextColor(Color.LTGRAY);setPadding(0,6,0,8)})
        box.addView(Button(this).apply{text="✏️ Edit";setOnClickListener{edit(c)}})
        list.addView(box,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=12})
    }

    private fun edit(c: LocalFbpComponent?) {
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,8,24,0)}
        val name=EditText(this).apply{hint="Name";setText(c?.name.orEmpty())}
        val limit=EditText(this).apply{hint="Max Annual Limit";inputType=2;setText(if(c==null)"" else c.maxAnnualLimit.toString())}
        val exempt=CheckBox(this).apply{text="Tax Exempt";isChecked=c?.isTaxExempt?:true}
        val active=CheckBox(this).apply{text="Active";isChecked=c?.isActive?:true}
        box.addView(name);box.addView(limit);box.addView(exempt);box.addView(active)
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(if(c==null)"Add FBP Component" else "Edit FBP Component").setView(box)
            .setPositiveButton("Save"){_,_->save(c?.componentId?:0,name.text.toString(),limit.text.toString().toDoubleOrNull()?:0.0,active.isChecked,exempt.isChecked)}
            .setNegativeButton("Cancel",null).show()
    }

    private fun save(id:Int,name:String,limit:Double,active:Boolean,exempt:Boolean){
        if(name.isBlank()||limit<=0){Toast.makeText(this,"Name and positive Max Limit are required",Toast.LENGTH_LONG).show();return}
        lifecycleScope.launch{runCatching{api.saveAdminFbpComponent("Bearer ${session.token().orEmpty()}",FbpComponentDto(id,name,limit,active,exempt))}.onSuccess{if(it.isSuccessful)Toast.makeText(this@FbpComponentsActivity,"Saved ✅",Toast.LENGTH_SHORT).show() else error("HTTP ${it.code()}")}.onFailure{Toast.makeText(this@FbpComponentsActivity,"Save failed: ${it.message}",Toast.LENGTH_LONG).show()}}
    }
}
