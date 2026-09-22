package com.biometric.app.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.biometric.app.sync.FirebaseSyncManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Native realtime viewer for Web modules that are informational/report-like.
 *
 * CRUD workflows remain in their dedicated native activities so existing
 * business rules are not duplicated or bypassed.
 */
@AndroidEntryPoint
@OptIn(ExperimentalCoroutinesApi::class)
class WebParityModuleActivity : MotionBaseActivity() {

    @Inject
    lateinit var firebaseSync: FirebaseSyncManager

    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    private var ref: DatabaseReference? = null
    private var listener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = intent.getStringExtra("TITLE") ?: "Web Module"
        val icon = intent.getStringExtra("ICON") ?: "📱"
        val table = intent.getStringExtra("TABLE").orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val toolbar = MaterialToolbar(this).apply {
            this.title = "$icon $title"

            setNavigationIcon(
                androidx.appcompat.R.drawable.abc_ic_ab_back_material
            )

            setNavigationOnClickListener {
                finish()
            }
        }

        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                -1,
                dp(64)
            )
        )

        status = TextView(this).apply {
            text = "Connecting to Firebase / SSOT…"
            textSize = 12f
            setTextColor(Color.parseColor("#1B7F5A"))

            setPadding(
                dp(16),
                dp(10),
                dp(16),
                dp(10)
            )
        }

        root.addView(status)

        val progress = ProgressBar(this).apply {
            isIndeterminate = true
        }

        root.addView(
            progress,
            LinearLayout.LayoutParams(
                -1,
                dp(3)
            )
        )

        val scroll = androidx.core.widget.NestedScrollView(this)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(16),
                dp(16),
                dp(16),
                dp(16)
            )
        }

        scroll.addView(list)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        setContentView(root)

        attachRealtime(table)
    }

    private fun attachRealtime(table: String) {

        val owner = firebaseSync.getOwnerRef() ?: run {
            status.text = "SSOT unavailable"
            return
        }

        ref = table
            .split('/')
            .fold(owner) { current, child ->
                current.child(child)
            }

        listener = object : ValueEventListener {

            override fun onDataChange(snapshot: DataSnapshot) {
                lifecycleScope.launch {
                    render(snapshot)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                status.text =
                    "Realtime listener paused: ${error.message} • retrying automatically"
            }
        }

        ref?.addValueEventListener(listener!!)
    }

    private fun render(snapshot: DataSnapshot) {

        list.removeAllViews()

        val children = snapshot.children.toList()

        status.text =
            "🟢 Realtime • ${children.size} records • Firebase / SSOT"

        if (children.isEmpty()) {

            list.addView(
                TextView(this).apply {

                    text =
                        "No records currently available.\n\n" +
                                "This screen follows Firebase/SSOT in realtime."

                    textSize = 16f
                    gravity = Gravity.CENTER

                    setPadding(
                        dp(24),
                        dp(60),
                        dp(24),
                        dp(60)
                    )
                }
            )

            return
        }

        children
            .take(200)
            .forEach { child ->

                val card = MaterialCardView(this).apply {
                    radius = dp(16).toFloat()
                    strokeWidth = dp(1)
                    strokeColor = Color.parseColor("#D6DEEA")
                }

                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL

                    setPadding(
                        dp(14),
                        dp(14),
                        dp(14),
                        dp(14)
                    )
                }

                box.addView(
                    TextView(this).apply {

                        text = "# ${child.key.orEmpty()}"

                        textSize = 15f

                        setTypeface(
                            typeface,
                            android.graphics.Typeface.BOLD
                        )
                    }
                )

                val fields = child.children
                    .toList()
                    .take(10)

                fields.forEach { field ->

                    val value =
                        field.value?.toString().orEmpty()

                    box.addView(
                        TextView(this).apply {

                            text =
                                "${pretty(field.key.orEmpty())}: $value"

                            textSize = 12f

                            setTextColor(
                                Color.parseColor("#526173")
                            )

                            setPadding(
                                0,
                                dp(4),
                                0,
                                0
                            )
                        }
                    )
                }

                card.addView(box)

                list.addView(
                    card,
                    LinearLayout.LayoutParams(
                        -1,
                        -2
                    ).apply {
                        bottomMargin = dp(10)
                    }
                )
            }

        if (children.size > 200) {

            list.addView(
                TextView(this).apply {

                    text =
                        "Showing latest 200 visible records. " +
                                "The underlying Firebase/SSOT data is unchanged."

                    setTextColor(Color.GRAY)

                    setPadding(
                        0,
                        dp(8),
                        0,
                        dp(20)
                    )
                }
            )
        }
    }

    override fun onDestroy() {

        listener?.let { currentListener ->
            ref?.removeEventListener(currentListener)
        }

        listener = null
        ref = null

        super.onDestroy()
    }

    private fun pretty(value: String): String =
        value
            .replace('_', ' ')
            .replace(
                Regex("([a-z])([A-Z])"),
                "$1 $2"
            )
            .replaceFirstChar {
                it.uppercase()
            }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}