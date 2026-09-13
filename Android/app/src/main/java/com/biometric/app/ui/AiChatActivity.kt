package com.biometric.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Html
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.biometric.app.R
import com.biometric.app.databinding.ActivityAiChatBinding
import com.biometric.app.databinding.ItemChatBubbleBinding
import com.biometric.app.ui.viewmodel.AiChatViewModel
import com.biometric.app.ui.viewmodel.ChatMessage
import com.biometric.app.util.HapticUtil
import com.biometric.app.util.PremiumLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class AiChatActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityAiChatBinding
    private val viewModel: AiChatViewModel by viewModels()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var speechRecognizer: SpeechRecognizer

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            startVoiceRecognition()
        } else {
            Toast.makeText(this, "Permission denied to record audio", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val shopId = intent.getStringExtra("SHOP_ID")
        viewModel.setShopId(shopId)

        setupToolbar()
        setupRecyclerView()
        setupListeners()
        setupSpeechRecognizer()
        observeViewModel()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 101, 0, "Clear Chat")?.apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 101) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear History?")
                .setMessage("This will permanently delete your chat conversation history.")
                .setPositiveButton("Clear") { _, _ -> viewModel.clearHistory() }
                .setNegativeButton("Cancel", null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun shareInsight(text: String) {
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Workforce AI Insight 🤖:\n\n$text\n\nSent from Biometric Payroll")
        }
        startActivity(Intent.createChooser(shareIntent, "Share via"))
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        binding.rvChat.apply {
            layoutManager = LinearLayoutManager(this@AiChatActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }
    }

    private fun setupListeners() {
        binding.btnSend.setOnClickListener {
            val query = binding.etQuery.text.toString()
            if (query.isNotBlank()) {
                viewModel.sendMessage(query)
                binding.etQuery.text.clear()
            }
        }
        
        binding.btnVoice.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startVoiceRecognition()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    viewModel.sendMessage(matches[0])
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "How can I help you?")
        speechRecognizer.startListening(intent)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messages.collectLatest { messages ->
                        chatAdapter.submitList(messages)
                        binding.rvChat.smoothScrollToPosition(maxOf(0, messages.size - 1))
                        
                        if (messages.isNotEmpty() && !messages.last().isUser) {
                           val text = messages.last().text.lowercase()
                           if (text.contains("perfect") || text.contains("regular") || text.contains("star")) {
                               HapticUtil.vibrateSuccess(binding.root)
                           } else if (text.contains("absent") || text.contains("late") || text.contains("not find")) {
                               HapticUtil.vibrateError(binding.root)
                           } else {
                               HapticUtil.vibrateClick(binding.root)
                           }
                        }
                    }
                }
                launch {
                    viewModel.suggestions.collectLatest { suggestions ->
                        updateSuggestions(suggestions)
                    }
                }
                launch {
                    viewModel.isLoading.collectLatest { isLoading ->
                        if (isLoading) {
                            PremiumLoader.show(binding.brewingLoader, PremiumLoader.ScreenType.GENERIC, lifecycleScope)
                        } else {
                            PremiumLoader.hide(binding.brewingLoader)
                        }
                    }
                }
            }
        }
    }

    private fun updateSuggestions(suggestions: List<String>) {
        binding.cgSuggestions.removeAllViews()
        suggestions.forEach { text ->
            val chip = Chip(this).apply {
                this.text = text
                setOnClickListener {
                    viewModel.sendMessage(text)
                }
            }
            binding.cgSuggestions.addView(chip)
        }
        binding.hsvSuggestions.visibility = if (suggestions.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun handleAction(type: String?) {
        when(type) {
            "VIEW_STAFF" -> {
                startActivity(Intent(this, StaffActivity::class.java))
            }
        }
    }

    private fun setupSparkline(msg: ChatMessage, b: ItemChatBubbleBinding) {
        val chart = b.sparklineChart
        
        val dataSet = LineDataSet(msg.chartData, msg.chartLabel).apply {
            color = ContextCompat.getColor(this@AiChatActivity, R.color.green)
            setCircleColor(ContextCompat.getColor(this@AiChatActivity, R.color.green))
            lineWidth = 2f
            circleRadius = 3f
            setDrawCircleHole(false)
            setDrawValues(false)
            setDrawFilled(true)
            fillAlpha = 30
            fillColor = ContextCompat.getColor(this@AiChatActivity, R.color.green)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        chart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.isEnabled = false
            axisLeft.isEnabled = false
            axisRight.isEnabled = false
            setTouchEnabled(false)
            animateX(500)
            invalidate()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
    }

    inner class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {
        var items = listOf<ChatMessage>()

        fun submitList(newList: List<ChatMessage>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemChatBubbleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val msg = items[position]
            holder.bind(msg)
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(private val b: ItemChatBubbleBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(msg: ChatMessage) {
                val formatted = msg.text
                    .replace("**", "<b>")
                    .replace("**", "</b>")
                
                b.tvMessage.text = Html.fromHtml(formatted, Html.FROM_HTML_MODE_LEGACY)

                if (msg.chartData != null) {
                    b.sparklineChart.visibility = View.VISIBLE
                    setupSparkline(msg, b)
                } else {
                    b.sparklineChart.visibility = View.GONE
                }

                if (msg.actionText != null) {
                    b.btnAction.visibility = View.VISIBLE
                    b.btnAction.text = msg.actionText
                    b.btnAction.setOnClickListener {
                        handleAction(msg.actionType)
                    }
                } else {
                    b.btnAction.visibility = View.GONE
                }

                if (!msg.isUser) {
                    b.btnShare.visibility = View.VISIBLE
                    b.btnShare.setOnClickListener {
                        shareInsight(msg.text)
                    }
                } else {
                    b.btnShare.visibility = View.GONE
                }
                
                val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
                b.tvTimestamp.text = sdf.format(Date(msg.timestamp))

                val params = b.cardBubble.layoutParams as ViewGroup.MarginLayoutParams
                if (msg.isUser) {
                    b.llBubbleContainer.gravity = Gravity.END
                    b.cardBubble.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.colorPrimary))
                    b.tvMessage.setTextColor(ContextCompat.getColor(itemView.context, R.color.white))
                    params.marginStart = 48 
                    params.marginEnd = 0
                } else {
                    b.llBubbleContainer.gravity = Gravity.START
                    b.cardBubble.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.colorSurfaceVariant))
                    b.tvMessage.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
                    params.marginStart = 0
                    params.marginEnd = 48
                }
                b.cardBubble.layoutParams = params
            }
        }
    }
}
