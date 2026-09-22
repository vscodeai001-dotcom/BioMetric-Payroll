package com.biometric.app.ui
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.biometric.app.data.entity.YearEndSummaryRecord
import com.biometric.app.sync.FirebaseSyncManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject
@AndroidEntryPoint class YearEndSummaryActivity: AppCompatActivity(){@Inject lateinit var sync:FirebaseSyncManager;private lateinit var list:LinearLayout;private var currentRows:List<YearEndSummaryRecord> = emptyList();private var allRows:List<YearEndSummaryRecord> = emptyList();private var year=Calendar.getInstance().get(Calendar.YEAR)
 override fun onCreate(b:Bundle?){super.onCreate(b);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,16,16,16)};root.addView(TextView(this).apply{text="📅 Year-End Compliance Summary";textSize=22f;setTextColor(Color.WHITE)});root.addView(TextView(this).apply{text="Firebase/SSOT published annual results • calculation authority remains Web payroll";setTextColor(Color.LTGRAY);setPadding(0,6,0,12)});val years=(year downTo year-2).map{it.toString()};val sp=Spinner(this).apply{adapter=ArrayAdapter(this@YearEndSummaryActivity,android.R.layout.simple_spinner_dropdown_item,years)};root.addView(sp);sp.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:android.widget.AdapterView<*>?){ } override fun onItemSelected(p:android.widget.AdapterView<*>?,v:android.view.View?,pos:Int,id:Long){year=years[pos].toInt();render(allRows.filter { it.taxYear == year })}};list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};root.addView(ScrollView(this).apply{addView(list)},LinearLayout.LayoutParams(-1,0,1f));setContentView(root);lifecycleScope.launch{sync.getDataFlow<YearEndSummaryRecord>("year_end_summaries").collectLatest{rows->allRows=rows;render(allRows.filter { it.taxYear == year })}}}
 private fun render(rows:List<YearEndSummaryRecord>){currentRows=rows;list.removeAllViews();if(rows.isEmpty()){list.addView(TextView(this).apply{text="No year-end data for $year";setPadding(16,30,16,30)});return};rows.forEach{r->val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,16,16,16);setBackgroundColor(Color.rgb(29,42,60))};c.addView(TextView(this).apply{text="👤 Employee #${r.employeeId}";textSize=18f;setTextColor(Color.WHITE)});c.addView(TextView(this).apply{text="Gross taxable ₹${"%,.2f".format(r.grossTaxableSalary)}\nTDS ₹${"%,.2f".format(r.totalTdsDeducted)}  • PF ₹${"%,.2f".format(r.totalPfContributionEmployee)}\nAbsent ${r.totalAnnualAbsentDays} days  • OT ₹${"%,.2f".format(r.totalAnnualOtPay)}\n${r.state}";setTextColor(Color.LTGRAY)});list.addView(c,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=12})}}
}
