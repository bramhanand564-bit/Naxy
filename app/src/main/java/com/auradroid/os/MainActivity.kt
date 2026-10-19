package com.auradroid.os

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class MainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val bgMatteDark = Color.parseColor("#121316")
    private val cardSlate = Color.parseColor("#1C1F26")
    private val borderSubtle = Color.parseColor("#2E3440")
    private val textPureWhite = Color.parseColor("#F8FAFC")
    private val textMuted = Color.parseColor("#94A3B8")
    private val accentRoyalBlue = Color.parseColor("#3B82F6")
    private val accentEmerald = Color.parseColor("#10B981")

    private lateinit var serviceBadge: TextView
    private lateinit var tabContainers: Array<View>
    private lateinit var bottomNavButtons: Array<TextView>

    private lateinit var chatMessagesLayout: LinearLayout
    private lateinit var chatInputBox: EditText
    private val chatHistoryBuilder = StringBuilder()

    private lateinit var folderPathEditText: EditText

    private var studioWebView: WebView? = null
    private var isDesktopMode = false
    private val downloadedFilesLog = mutableListOf<String>()

    private lateinit var memoryCategoriesLayout: LinearLayout
    private lateinit var automationRunsListLayout: LinearLayout
    private lateinit var videoSessionsListLayout: LinearLayout

    private var selectedTrainingCategory = "Instagram"
    private var selectedTrainingNote = "इस वीडियो के हर फ्रेम को देखो, जहाँ गलती हुई है उसे सुधारो और सही बटन के कोऑर्डिनेट सेव कर लो"

    private lateinit var liveLogsTextView: TextView
    private lateinit var engineScrollView: ScrollView

    companion object {
        private const val REQ_VOICE_INPUT = 201
        private const val REQ_PICK_TRAINING_VIDEO = 202
        private const val REQ_PICK_FOLDER_TREE = 203
        private const val REQ_PICK_SINGLE_VIDEO_FOR_YT = 204
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgMatteDark
        window.navigationBarColor = Color.parseColor("#181B20")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgMatteDark)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#181B20"))
        }

        val titleView = TextView(this).apply {
            text = "AuraDroid OS"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val quickLogsBtn = TextView(this).apply {
            text = "📜 Logs"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBox(Color.parseColor("#262B36"), dp(14), borderSubtle)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener {
                switchTab(4)
                refreshLiveLogsBox()
                engineScrollView.post { engineScrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }

        serviceBadge = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedBox(Color.parseColor("#222630"), dp(16), borderSubtle)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        refreshServiceBadge()

        header.addView(titleView)
        header.addView(quickLogsBtn)
        header.addView(serviceBadge)

        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val tab1Chat = buildTab1ChatAndPlanner()
        val tab2Automations = buildTab2AutomationsAndScheduler()
        val tab3Browser = buildTab3FullControlBrowser()
        val tab4Memory = buildTab4MemoryAndVideoTrainer()
        val tab5Settings = buildTab5EngineAndLogs()

        tabContainers = arrayOf(tab1Chat, tab2Automations, tab3Browser, tab4Memory, tab5Settings)
        tabContainers.forEachIndexed { idx, view ->
            view.visibility = if (idx == 0) View.VISIBLE else View.GONE
            contentFrame.addView(view)
        }

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#181B20"))
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }

        val labels = arrayOf("💬 Chat", "🔄 Tasks", "🌐 Browser", "🧠 Memory", "⚙️ Engine")
        bottomNavButtons = Array(5) { idx ->
            TextView(this).apply {
                text = labels[idx]
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), dp(10), dp(4), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { switchTab(idx) }
                bottomBar.addView(this)
            }
        }
        switchTab(0)

        root.addView(header)
        root.addView(contentFrame)
        root.addView(bottomBar)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshServiceBadge()
        refreshLiveLogsBox()
    }

    private fun refreshServiceBadge() {
        if (!::serviceBadge.isInitialized) return
        val active = AutoTouchService.instance != null
        serviceBadge.text = if (active) "● 24/7 Active" else "○ Enable Body"
        serviceBadge.setTextColor(if (active) accentEmerald else Color.parseColor("#F59E0B"))
    }

    private fun requestStoragePermissionsOnDemand(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AutoTouchService.log("📁 Requesting All Files Access Permission...")
                Toast.makeText(this, "कृपया 'All Files Access' ऑन करें!", Toast.LENGTH_LONG).show()
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
                return false
            }
            return true
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                101
            )
            return true
        }
    }

    private fun switchTab(index: Int) {
        tabContainers.forEachIndexed { i, v ->
            v.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        bottomNavButtons.forEachIndexed { i, btn ->
            if (i == index) {
                btn.setTextColor(textPureWhite)
                btn.background = roundedBox(accentRoyalBlue, dp(12))
            } else {
                btn.setTextColor(textMuted)
                btn.background = null
            }
        }
        if (index == 3) refreshMemoryEverything()
        if (index == 4) refreshLiveLogsBox()
    }

    /**
     * TAB 1: Interactive AI Chat & Detailed Sub-Task Planner
     */
    private fun buildTab1ChatAndPlanner(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        chatMessagesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(chatMessagesLayout)

        addChatBubble(
            "AuraDroid OS",
            "नमस्ते भाई! मुझे कोई भी काम बताओ (जैसे: 'Instagram खोलो, भोजपुरी गाना सर्च करो और पहला वीडियो चलाओ')। हर रन खत्म होने पर Gemini 3.8 खुद सारे स्क्रीनशॉट्स देखकर अगले रन के लिए अपनी मेमोरी और बेहतर कर लेगा!",
            isUser = false
        )

        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedBox(cardSlate, dp(16), borderSubtle)
        }

        val micBtn = TextView(this).apply {
            text = "🎙️"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { startVoiceInput() }
        }

        chatInputBox = EditText(this).apply {
            hint = "यहाँ काम समझाएं या बात करें..."
            setHintTextColor(textMuted)
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = null
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val sendBtn = TextView(this).apply {
            text = "Plan ➔"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = roundedBox(accentRoyalBlue, dp(12))
            setOnClickListener {
                val msg = chatInputBox.text.toString().trim()
                if (msg.isNotEmpty()) {
                    chatInputBox.setText("")
                    handleUserChatMessage(msg, scroll)
                }
            }
        }

        inputBar.addView(micBtn)
        inputBar.addView(chatInputBox)
        inputBar.addView(sendBtn)

        container.addView(scroll)
        container.addView(inputBar)
        return container
    }

    private fun handleUserChatMessage(userMsg: String, scroll: ScrollView) {
        addChatBubble("You", userMsg, isUser = true)
        chatHistoryBuilder.append("User: $userMsg\n")
        AutoTouchService.log("💬 User Chat: $userMsg")

        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""
        val brain = AutoTouchService.instance?.geminiBrain ?: GeminiBrain(apiKey)
        val vault = AutoTouchService.instance?.memoryVault ?: MemoryVault(this)

        uiScope.launch {
            val planJson = brain.chatAndBuildDetailedPlan(
                userMessage = userMsg,
                chatHistory = chatHistoryBuilder.toString().takeLast(1500),
                userProfileRules = vault.getUserProfileRules()
            )

            val reply = planJson.optString("reply", "समझ गया भाई!")
            val ready = planJson.optBoolean("readyToExecute", false)
            val title = planJson.optString("taskTitle", userMsg.take(30))
            val mode = planJson.optString("executionMode", "LIVE_PHONE")
            val modelUsed = planJson.optString("modelUsed", "Gemini")
            val stepsArr = planJson.optJSONArray("steps") ?: JSONArray()

            val stepsList = mutableListOf<String>()
            for (i in 0 until stepsArr.length()) {
                stepsList.add(stepsArr.optString(i))
            }

            chatHistoryBuilder.append("AI: $reply\n")
            AutoTouchService.log("⚡ Planner [$modelUsed] Created ${stepsList.size} Steps for '$title'")
            addChatBubble("AuraDroid ($modelUsed)", reply, isUser = false)

            if (ready && stepsList.isNotEmpty()) {
                addDetailedPlanCardToChat(title, mode, stepsList, userMsg)
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun addChatBubble(sender: String, message: String, isUser: Boolean) {
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBox(
                if (isUser) Color.parseColor("#1E3A8A") else cardSlate,
                dp(14),
                borderSubtle
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
                if (isUser) marginStart = dp(40) else marginEnd = dp(24)
            }
        }

        val senderTv = TextView(this).apply {
            text = sender
            setTextColor(textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val msgTv = TextView(this).apply {
            text = message
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(4), 0, 0)
        }

        bubble.addView(senderTv)
        bubble.addView(msgTv)
        chatMessagesLayout.addView(bubble)
    }

    private fun addDetailedPlanCardToChat(
        title: String,
        mode: String,
        steps: List<String>,
        rawGoal: String
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBox(Color.parseColor("#172554"), dp(16), accentRoyalBlue)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        val header = TextView(this).apply {
            text = "📋 फाइनल एक्शन प्लान: $title ($mode)"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(header)

        steps.forEachIndexed { idx, stepText ->
            val stepTv = TextView(this).apply {
                text = "  ${idx + 1}. $stepText"
                setTextColor(Color.parseColor("#E2E8F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, dp(6), 0, 0)
            }
            card.addView(stepTv)
        }

        val startBtn = TextView(this).apply {
            text = "▶ हाँ, काम शुरू करो (Start Execution)"
            gravity = Gravity.CENTER
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBox(accentEmerald, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            setOnClickListener {
                val svc = AutoTouchService.instance
                if (svc == null) {
                    Toast.makeText(this@MainActivity, "पहले Accessibility Service ऑन करें!", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    svc.startUniversalTask(rawGoal, steps, mode)
                }
            }
        }

        card.addView(startBtn)
        chatMessagesLayout.addView(card)
    }

    /**
     * TAB 2: Universal Automations
     */
    private fun buildTab2AutomationsAndScheduler(): View {
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        val ytCard = createSectionCard("🎬 Folder / Video ➔ YouTube Shorts Auto-Uploader")
        val defaultFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        folderPathEditText = createCleanInput(
            prefs.getString("yt_folder_path", defaultFolder) ?: defaultFolder,
            "Video Folder Path"
        )

        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }

        fun chipBtn(title: String, path: String): TextView {
            return TextView(this).apply {
                text = title
                setTextColor(textPureWhite)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = roundedBox(Color.parseColor("#262B36"), dp(8), borderSubtle)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
                gravity = Gravity.CENTER
                setOnClickListener {
                    folderPathEditText.setText(path)
                    prefs.edit().putString("yt_folder_path", path).apply()
                }
            }
        }

        val dlPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        val camPath = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera").absolutePath
        val movPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath

        chipsRow.addView(chipBtn("📥 Download", dlPath))
        chipsRow.addView(chipBtn("🎥 Camera", camPath))
        chipsRow.addView(chipBtn("🎬 Movies", movPath))

        val browseFolderBtn = createPrimaryButton("📂 फोन से फोल्डर चुनें (Browse Folder)", accentRoyalBlue) {
            if (requestStoragePermissionsOnDemand()) {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, REQ_PICK_FOLDER_TREE)
            }
        }

        var pickOldest = prefs.getBoolean("yt_pick_oldest", true)
        val orderToggleBtn = TextView(this).apply {
            text = if (pickOldest) "🔃 Order: Oldest Video First (पुरानी पहले)" else "🔃 Order: Newest Video First (नई पहले)"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBox(Color.parseColor("#262B36"), dp(10), borderSubtle)
            setOnClickListener {
                pickOldest = !pickOldest
                prefs.edit().putBoolean("yt_pick_oldest", pickOldest).apply()
                text = if (pickOldest) "🔃 Order: Oldest Video First (पुरानी पहले)" else "🔃 Order: Newest Video First (नई पहले)"
            }
        }

        val runYtNowBtn = createPrimaryButton("🚀 Instant Test: फोल्डर से 1 वीडियो YouTube पर अपलोड करें", accentEmerald) {
            if (!requestStoragePermissionsOnDemand()) return@createPrimaryButton
            val path = folderPathEditText.text.toString().trim()
            prefs.edit().putString("yt_folder_path", path).apply()
            val svc = AutoTouchService.instance
            if (svc == null) {
                Toast.makeText(this, "पहले Accessibility Service ऑन करें!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                svc.startYouTubeFolderAutoUpload(path, pickOldest)
            }
        }

        val pickSingleVideoBtn = createPrimaryButton("🎬 सीधे गैलरी से 1 वीडियो चुनें और अभी अपलोड करें", Color.parseColor("#0D9488")) {
            val svc = AutoTouchService.instance
            if (svc == null) {
                Toast.makeText(this, "पहले Accessibility Service ऑन करें!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "video/*"
                }
                startActivityForResult(intent, REQ_PICK_SINGLE_VIDEO_FOR_YT)
            }
        }

        ytCard.addView(folderPathEditText)
        ytCard.addView(chipsRow)
        ytCard.addView(spacer(dp(8)))
        ytCard.addView(browseFolderBtn)
        ytCard.addView(spacer(dp(8)))
        ytCard.addView(orderToggleBtn)
        ytCard.addView(spacer(dp(12)))
        ytCard.addView(runYtNowBtn)
        ytCard.addView(spacer(dp(8)))
        ytCard.addView(pickSingleVideoBtn)

        val flowCard = createSectionCard("✨ Google Flow (8s Video) ➔ YouTube Pipeline")
        val topicInput = createCleanInput(
            prefs.getString("flow_topic", "Futuristic Sci-Fi Exploration") ?: "Futuristic Sci-Fi Exploration",
            "Daily Video Topic (Safe Cinematic Theme)"
        )
        val creditLimitInput = createCleanInput(
            prefs.getInt("flow_daily_videos", 1).toString(),
            "Daily Video Count (1, 2, or 3 credits)"
        )

        val runFlowNowBtn = createPrimaryButton("🎬 अभी Google Flow से 8s वीडियो बनाकर अपलोड करें", accentRoyalBlue) {
            val topic = topicInput.text.toString().trim()
            val count = creditLimitInput.text.toString().toIntOrNull()?.coerceIn(1, 3) ?: 1
            prefs.edit().putString("flow_topic", topic).putInt("flow_daily_videos", count).apply()
            AutoTouchService.instance?.startGoogleFlowToYouTubePipeline(topic, count)
                ?: Toast.makeText(this, "पहले Accessibility Service ऑन करें!", Toast.LENGTH_SHORT).show()
        }

        flowCard.addView(topicInput)
        flowCard.addView(spacer(dp(8)))
        flowCard.addView(creditLimitInput)
        flowCard.addView(spacer(dp(12)))
        flowCard.addView(runFlowNowBtn)

        val schedCard = createSectionCard("⏰ 24/7 Daily Auto-Scheduler (7:00 PM / 8:00 PM)")
        val timeInput = createCleanInput(
            prefs.getString("schedule_time_hm", "19:00") ?: "19:00",
            "24-Hour Time (19:00 for 7 PM, 20:00 for 8 PM)"
        )

        var schedType = prefs.getString("scheduled_pipeline_type", "FLOW_YT") ?: "FLOW_YT"
        val typeSwitchBtn = TextView(this).apply {
            text = if (schedType == "FLOW_YT") "🔄 Mode: Google Flow ➔ YouTube" else "🔄 Mode: Local Folder ➔ YouTube"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBox(Color.parseColor("#262B36"), dp(10), borderSubtle)
            setOnClickListener {
                schedType = if (schedType == "FLOW_YT") "FOLDER_YT" else "FLOW_YT"
                prefs.edit().putString("scheduled_pipeline_type", schedType).apply()
                text = if (schedType == "FLOW_YT") "🔄 Mode: Google Flow ➔ YouTube" else "🔄 Mode: Local Folder ➔ YouTube"
            }
        }

        var schedEnabled = prefs.getBoolean("scheduler_enabled", false)
        val enableSchedBtn = createPrimaryButton(
            if (schedEnabled) "✅ 24/7 Daily Scheduler: ON" else "⚪ 24/7 Daily Scheduler: OFF",
            if (schedEnabled) accentEmerald else Color.parseColor("#475569")
        ) { btn ->
            schedEnabled = !schedEnabled
            val t = timeInput.text.toString().trim()
            prefs.edit()
                .putBoolean("scheduler_enabled", schedEnabled)
                .putString("schedule_time_hm", t)
                .apply()
            btn.text = if (schedEnabled) "✅ 24/7 Daily Scheduler: ON" else "⚪ 24/7 Daily Scheduler: OFF"
            btn.background = roundedBox(if (schedEnabled) accentEmerald else Color.parseColor("#475569"), dp(12))
        }

        schedCard.addView(timeInput)
        schedCard.addView(spacer(dp(8)))
        schedCard.addView(typeSwitchBtn)
        schedCard.addView(spacer(dp(12)))
        schedCard.addView(enableSchedBtn)

        container.addView(ytCard)
        container.addView(flowCard)
        container.addView(schedCard)
        scroll.addView(container)
        return scroll
    }

    /**
     * TAB 3: Full-Control In-App Browser
     */
    private fun buildTab3FullControlBrowser(): View {
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(cardSlate)
        }

        val urlInput = EditText(this).apply {
            setText(prefs.getString("saved_flow_url", "https://labs.google/fx/tools/flow"))
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine(true)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBox(bgMatteDark, dp(8), borderSubtle)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        fun navBtn(label: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                setTextColor(textPureWhite)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener { onClick() }
            }
        }

        navBar.addView(navBtn("⬅") { if (studioWebView?.canGoBack() == true) studioWebView?.goBack() })
        navBar.addView(navBtn("➡") { if (studioWebView?.canGoForward() == true) studioWebView?.goForward() })
        navBar.addView(navBtn("🔄") { studioWebView?.reload() })
        navBar.addView(urlInput)
        navBar.addView(navBtn("Go") {
            var u = urlInput.text.toString().trim()
            if (!u.startsWith("http")) u = "https://$u"
            studioWebView?.loadUrl(u)
        })

        val toolStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.parseColor("#181B20"))
        }

        toolStrip.addView(navBtn("🔑 Gmail Login") {
            studioWebView?.loadUrl("https://accounts.google.com")
        })
        toolStrip.addView(navBtn("🎬 Google Flow") {
            studioWebView?.loadUrl("https://labs.google/fx/tools/flow")
        })
        toolStrip.addView(navBtn("🖥️ Desktop") {
            isDesktopMode = !isDesktopMode
            studioWebView?.settings?.userAgentString = if (isDesktopMode) {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            } else {
                "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }
            studioWebView?.reload()
            Toast.makeText(this, if (isDesktopMode) "Desktop View ON" else "Mobile View ON", Toast.LENGTH_SHORT).show()
        })
        toolStrip.addView(navBtn("📌 Save Page") {
            val cur = studioWebView?.url ?: "https://labs.google/fx/tools/flow"
            prefs.edit().putString("saved_flow_url", cur).apply()
            CookieManager.getInstance().flush()
            Toast.makeText(this, "Target Page & Login Session Saved!", Toast.LENGTH_SHORT).show()
        })

        studioWebView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    urlInput.setText(url ?: "")
                    CookieManager.getInstance().flush()
                }
            }
            webChromeClient = WebChromeClient()
            setDownloadListener(DownloadListener { url, _, _, _, _ ->
                downloadedFilesLog.add(0, url)
                Toast.makeText(this@MainActivity, "Downloading media from Browser...", Toast.LENGTH_SHORT).show()
            })

            loadUrl(prefs.getString("saved_flow_url", "https://labs.google/fx/tools/flow") ?: "https://labs.google/fx/tools/flow")
        }

        container.addView(navBar)
        container.addView(toolStrip)
        container.addView(studioWebView)
        return container
    }

    /**
     * TAB 4: Memory Folders, Automation Runs History & Video Training Sessions with Gemini 3.8 Flash Chat
     */
    private fun buildTab4MemoryAndVideoTrainer(): View {
        val vault = AutoTouchService.instance?.memoryVault ?: MemoryVault(this)
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        val trainerCard = createSectionCard("📹 Frame-by-Frame Video Trainer (Gemini 3.8 Flash)")
        val catInput = createCleanInput("Instagram", "App Folder Name (e.g. Instagram, YouTube)")
        val noteInput = createCleanInput(
            "इस वीडियो के हर फ्रेम को देखो, जहाँ गलती हुई है उसे सुधारो और सही बटन के कोऑर्डिनेट सेव कर लो",
            "इस वीडियो के बारे में निर्देश लिखें..."
        )

        val pickVideoBtn = createPrimaryButton("📤 Upload Screen Recording Video & Train (12 Frames)", accentRoyalBlue) {
            selectedTrainingCategory = catInput.text.toString().trim().ifEmpty { "General" }
            selectedTrainingNote = noteInput.text.toString().trim().ifEmpty { "Learn exact step coordinates" }
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "video/*" }
            startActivityForResult(intent, REQ_PICK_TRAINING_VIDEO)
        }

        trainerCard.addView(catInput)
        trainerCard.addView(spacer(dp(8)))
        trainerCard.addView(noteInput)
        trainerCard.addView(spacer(dp(12)))
        trainerCard.addView(pickVideoBtn)

        val videoSessionsCard = createSectionCard("🎬 Uploaded Video Training Sessions (Interactive Chat)")
        videoSessionsListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        videoSessionsCard.addView(videoSessionsListLayout)

        val runsCard = createSectionCard("🔄 Automation Runs History (Auto-Reflected by 3.8)")
        automationRunsListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        runsCard.addView(automationRunsListLayout)

        val foldersCard = createSectionCard("📁 App-Wise Learned Fixes & Playbooks")
        memoryCategoriesLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        foldersCard.addView(memoryCategoriesLayout)

        val profileCard = createSectionCard("🔐 Personal Profile & Form Auto-Fill Memory")
        val profileInput = createCleanInput(
            vault.getUserProfileRules(),
            "अपना नाम, ईमेल, फोल्डर नियम या प्रेफरेंस यहाँ लिखें..."
        ).apply { minLines = 3 }

        val saveProfileBtn = createPrimaryButton("💾 Save Personal Memory", accentEmerald) {
            vault.saveUserProfileRules(profileInput.text.toString())
            Toast.makeText(this, "Personal Memory Saved!", Toast.LENGTH_SHORT).show()
        }

        profileCard.addView(profileInput)
        profileCard.addView(spacer(dp(10)))
        profileCard.addView(saveProfileBtn)

        container.addView(trainerCard)
        container.addView(videoSessionsCard)
        container.addView(runsCard)
        container.addView(foldersCard)
        container.addView(profileCard)
        scroll.addView(container)
        return scroll
    }

    private fun refreshMemoryEverything() {
        refreshMemoryFoldersList()
        refreshAutomationRunsList()
        refreshVideoTrainingSessionsList()
    }

    private fun refreshMemoryFoldersList() {
        if (!::memoryCategoriesLayout.isInitialized) return
        memoryCategoriesLayout.removeAllViews()
        val vault = AutoTouchService.instance?.memoryVault ?: MemoryVault(this)
        val summary = vault.getAllCategoriesSummary()

        if (summary.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "अभी कोई Learned Fix नहीं है। जैसे ही कोई टास्क चलेगा, Gemini 3.8 खुद स्क्रीनशॉट्स देखकर यहाँ नियम सेव कर लेगा!"
                setTextColor(textMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            memoryCategoriesLayout.addView(emptyTv)
            return
        }

        for ((cat, count) in summary) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBox(Color.parseColor("#262B36"), dp(10), borderSubtle)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val topHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val title = TextView(this).apply {
                text = "📂 Memory/$cat/ ($count Rules)"
                setTextColor(textPureWhite)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val clearCatBtn = TextView(this).apply {
                text = "🗑️ Clear"
                setTextColor(Color.parseColor("#EF4444"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    vault.clearCategoryMemory(cat)
                    refreshMemoryEverything()
                }
            }

            topHeader.addView(title)
            topHeader.addView(clearCatBtn)
            row.addView(topHeader)

            val fixes = vault.recallFixesForApp(cat).takeLast(6)
            for (f in fixes) {
                val xNorm = (f.tapXRatio * 1000).toInt()
                val yNorm = (f.tapYRatio * 1000).toInt()
                val item = TextView(this).apply {
                    text = "  • [${f.sourceType}] ${f.problemDescription} ➔ ${f.solutionRule} (XY: $xNorm, $yNorm)"
                    setTextColor(textMuted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(4), 0, 0)
                }
                row.addView(item)
            }
            memoryCategoriesLayout.addView(row)
        }
    }

    private fun refreshAutomationRunsList() {
        if (!::automationRunsListLayout.isInitialized) return
        automationRunsListLayout.removeAllViews()
        val vault = AutoTouchService.instance?.memoryVault ?: MemoryVault(this)
        val runs = vault.getAllAutomationRuns()

        if (runs.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "अभी कोई Automation Run सेव नहीं हुआ है। टास्क रन होने पर यहाँ दिखेगा।"
                setTextColor(textMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            automationRunsListLayout.addView(emptyTv)
            return
        }

        for (run in runs) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBox(Color.parseColor("#1C1F26"), dp(10), borderSubtle)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val statusColor = when (run.status) {
                "SUCCESS" -> accentEmerald
                "FAILED" -> Color.parseColor("#EF4444")
                else -> Color.parseColor("#F59E0B")
            }

            val titleTv = TextView(this).apply {
                text = "📌 ${run.title} [${run.status}] - ${run.timestamp}"
                setTextColor(statusColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
            }

            val descTv = TextView(this).apply {
                text = "App: ${run.appCategory} | Steps: ${run.stepsSummary.size} | Screenshots: ${run.screenshotPaths.size}"
                setTextColor(textMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(2), 0, dp(6))
            }

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val chatWithRunBtn = TextView(this).apply {
                text = "💬 Gemini 3.8 चैट & स्क्रीनशॉट्स"
                gravity = Gravity.CENTER
                setTextColor(textPureWhite)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = roundedBox(accentRoyalBlue, dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    openAutomationRunChatDialog(run)
                }
            }

            val reRunBtn = TextView(this).apply {
                text = "🔄 Re-Run"
                gravity = Gravity.CENTER
                setTextColor(textPureWhite)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = roundedBox(accentEmerald, dp(8))
                setOnClickListener {
                    AutoTouchService.instance?.startUniversalTask(run.title, emptyList())
                        ?: Toast.makeText(this@MainActivity, "Accessibility ऑन करें!", Toast.LENGTH_SHORT).show()
                }
            }

            btnRow.addView(chatWithRunBtn)
            btnRow.addView(reRunBtn)

            card.addView(titleTv)
            card.addView(descTv)
            card.addView(btnRow)
            automationRunsListLayout.addView(card)
        }
    }

    private fun openAutomationRunChatDialog(runRecord: AutomationRunRecord) {
        val vault = AutoTouchService.instance?.memoryVault ?: MemoryVault(this)
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val brain = AutoTouchService.instance?.geminiBrain ?: GeminiBrain(prefs.getString("gemini_api_key", "") ?: "")

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(bgMatteDark)
        }

        val input = createCleanInput("", "भाई, यहाँ समझाओ क्या गलती हुई...").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }

        if (runRecord.screenshotPaths.isNotEmpty()) {
            val tipTv = TextView(this).apply {
                text = "👆 किसी भी स्क्रीनशॉट पर उंगली से टैप करके सटीक बटन पिन करें:"
                setTextColor(textMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, 0, 0, dp(4))
            }
            dialogView.addView(tipTv)

            val hScroll = HorizontalScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130))
            }
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

            runRecord.screenshotPaths.forEachIndexed { idx, path ->
                val bmp = vault.loadBitmapFromPath(path) ?: return@forEachIndexed
                val imgView = ImageView(this).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.FIT_XY
                    layoutParams = LinearLayout.LayoutParams(dp(72), dp(124)).apply {
                        marginEnd = dp(8)
                    }
                    setOnTouchListener { v, ev ->
                        if (ev.action == MotionEvent.ACTION_UP) {
                            val normX = ((ev.x / v.width.coerceAtLeast(1)) * 1000).toInt().coerceIn(0, 1000)
                            val normY = ((ev.y / v.height.coerceAtLeast(1)) * 1000).toInt().coerceIn(0, 1000)
                            val curText = input.text.toString()
                            input.setText("$curText [Screenshot #${idx + 1} Pin XY: ($normX, $normY)] ")
                            input.setSelection(input.text.length)
                        }
                        true
                    }
                }
                strip.addView(imgView)
            }
            hScroll.addView(strip)
            dialogView.addView(hScroll)
        }

        val chatScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220), 1f)
        }
        val msgListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatScroll.addView(msgListLayout)

        fun appendMsg(sender: String, msgText: String) {
            val tv = TextView(this).apply {
                setText("$sender: $msgText")
                setTextColor(if (sender == "User") Color.parseColor("#60A5FA") else accentEmerald)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(4), 0, dp(4))
            }
            msgListLayout.addView(tv)
        }

        appendMsg("Gemini 3.8", "नमस्ते भाई! मैंने '${runRecord.title}' के ${runRecord.screenshotPaths.size} स्क्रीनशॉट्स और स्टेप्स लोड कर लिए हैं।")
        for (c in runRecord.chatHistory) {
            val parts = c.split(":", limit = 2)
            if (parts.size == 2) appendMsg(parts[0].trim(), parts[1].trim())
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        val send = TextView(this).apply {
            text = "Send ➔"
            setTextColor(textPureWhite)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(accentRoyalBlue, dp(8))
        }

        inputRow.addView(input)
        inputRow.addView(send)

        dialogView.addView(chatScroll)
        dialogView.addView(inputRow)

        val dialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle("💬 Fix Run: ${runRecord.title}")
            .setView(dialogView)
            .setPositiveButton("बंद करें") { d, _ -> d.dismiss() }
            .create()

        send.setOnClickListener {
            val userText = input.text.toString().trim()
            if (userText.isEmpty()) return@setOnClickListener
            input.setText("")
            appendMsg("User", userText)
            vault.appendChatToAutomationRun(runRecord.runId, "User", userText)

            uiScope.launch(Dispatchers.IO) {
                val b64Imgs = vault.loadSavedImagesAsBase64(runRecord.screenshotPaths, maxImages = 6)
                val replyObj = brain.chatAboutAutomationRunWith38Flash(
                    runRecord = runRecord,
                    userCorrectionMessage = userText,
                    screenshotsBase64 = b64Imgs
                )

                vault.appendChatToAutomationRun(runRecord.runId, "Gemini 3.8", replyObj.replyText)
                vault.saveStuckOrHumanFix(
                    category = runRecord.appCategory,
                    screenSignature = "run_chat_${runRecord.runId}",
                    problemDescription = replyObj.extractedProblem,
                    solutionRule = replyObj.extractedSolutionRule,
                    tapXRatio = replyObj.tapXRatio,
                    tapYRatio = replyObj.tapYRatio,
                    sourceType = "RUN_CHAT_CORRECTION (${replyObj.modelUsed})"
                )

                withContext(Dispatchers.Main) {
                    appendMsg("Gemini 3.8", replyObj.replyText)
                    chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
                    refreshMemoryEverything()
                }
            }
        }

        dialog.show()
    }

    private fun refreshVideoTrainingSessionsList() {
        if (!::videoSessionsListLayout.isInitialized) return
        videoSessionsListLayout.removeAllViews()
        val vault = AutoTouchService.instance?.memoryVault ?: MemoryVault(this)
        val sessions = vault.getAllVideoTrainingSessions()

        if (sessions.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "अभी कोई वीडियो ट्रेनिंग सेशन नहीं है। ऊपर से वीडियो अपलोड करने पर यहाँ दिखेगा।"
                setTextColor(textMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            videoSessionsListLayout.addView(emptyTv)
            return
        }

        for (session in sessions) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBox(Color.parseColor("#1C1F26"), dp(10), borderSubtle)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val titleTv = TextView(this).apply {
                text = "📹 Video Session: ${session.appCategory} (${session.timestamp})"
                setTextColor(accentRoyalBlue)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
            }

            val noteTv = TextView(this).apply {
                text = "Note: ${session.userNote} | Frames: ${session.framePaths.size} | Rules: ${session.extractedRulesSummary.size}"
                setTextColor(textMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(2), 0, dp(6))
            }

            val chatWithVidBtn = TextView(this).apply {
                text = "💬 इस वीडियो पर Gemini 3.8 से चैट करें"
                gravity = Gravity.CENTER
                setTextColor(textPureWhite)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = roundedBox(accentEmerald, dp(8))
                setOnClickListener {
                    openVideoSessionChatDialog(session)
                }
            }

            card.addView(titleTv)
            card.addView(noteTv)
            card.addView(chatWithVidBtn)
            videoSessionsListLayout.addView(card)
        }
    }

    private fun openVideoSessionChatDialog(session: VideoTrainingSession) {
        val vault = AutoTouchService.instance?.memoryVault ?: MemoryVault(this)
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val brain = AutoTouchService.instance?.geminiBrain ?: GeminiBrain(prefs.getString("gemini_api_key", "") ?: "")

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(bgMatteDark)
        }

        val input = createCleanInput("", "वीडियो के बारे में कुछ पूछें या सुधार बताएं...").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }

        if (session.framePaths.isNotEmpty()) {
            val tipTv = TextView(this).apply {
                text = "👆 किसी भी वीडियो फ्रेम पर टैप करके सही बटन पिन करें:"
                setTextColor(textMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, 0, 0, dp(4))
            }
            dialogView.addView(tipTv)

            val hScroll = HorizontalScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130))
            }
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

            session.framePaths.forEachIndexed { idx, path ->
                val bmp = vault.loadBitmapFromPath(path) ?: return@forEachIndexed
                val imgView = ImageView(this).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.FIT_XY
                    layoutParams = LinearLayout.LayoutParams(dp(72), dp(124)).apply {
                        marginEnd = dp(8)
                    }
                    setOnTouchListener { v, ev ->
                        if (ev.action == MotionEvent.ACTION_UP) {
                            val normX = ((ev.x / v.width.coerceAtLeast(1)) * 1000).toInt().coerceIn(0, 1000)
                            val normY = ((ev.y / v.height.coerceAtLeast(1)) * 1000).toInt().coerceIn(0, 1000)
                            val curText = input.text.toString()
                            input.setText("$curText [Frame #${idx + 1} Pin XY: ($normX, $normY)] ")
                            input.setSelection(input.text.length)
                        }
                        true
                    }
                }
                strip.addView(imgView)
            }
            hScroll.addView(strip)
            dialogView.addView(hScroll)
        }

        val chatScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220), 1f)
        }
        val msgListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatScroll.addView(msgListLayout)

        fun appendMsg(sender: String, msgText: String) {
            val tv = TextView(this).apply {
                setText("$sender: $msgText")
                setTextColor(if (sender == "User") Color.parseColor("#60A5FA") else accentEmerald)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(4), 0, dp(4))
            }
            msgListLayout.addView(tv)
        }

        appendMsg("Gemini 3.8", "नमस्ते भाई! मैंने इस वीडियो के ${session.framePaths.size} फ्रेम्स देख लिए हैं। बताइए क्या सुधार करना है?")
        for (c in session.chatHistory) {
            val parts = c.split(":", limit = 2)
            if (parts.size == 2) appendMsg(parts[0].trim(), parts[1].trim())
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        val send = TextView(this).apply {
            text = "Send ➔"
            setTextColor(textPureWhite)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(accentRoyalBlue, dp(8))
        }

        inputRow.addView(input)
        inputRow.addView(send)

        dialogView.addView(chatScroll)
        dialogView.addView(inputRow)

        val dialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle("💬 Video Chat: ${session.appCategory}")
            .setView(dialogView)
            .setPositiveButton("बंद करें") { d, _ -> d.dismiss() }
            .create()

        send.setOnClickListener {
            val userText = input.text.toString().trim()
            if (userText.isEmpty()) return@setOnClickListener
            input.setText("")
            appendMsg("User", userText)
            vault.appendChatToVideoSession(session.sessionId, "User", userText)

            uiScope.launch(Dispatchers.IO) {
                val b64Frames = vault.loadSavedImagesAsBase64(session.framePaths, maxImages = 10)
                val replyObj = brain.chatAboutTrainingVideoWith38Flash(
                    videoSession = session,
                    userMessage = userText,
                    videoFramesBase64 = b64Frames
                )

                vault.appendChatToVideoSession(session.sessionId, "Gemini 3.8", replyObj.replyText, replyObj.extractedSolutionRule)
                vault.saveStuckOrHumanFix(
                    category = session.appCategory,
                    screenSignature = "video_chat_${session.sessionId}",
                    problemDescription = replyObj.extractedProblem,
                    solutionRule = replyObj.extractedSolutionRule,
                    tapXRatio = replyObj.tapXRatio,
                    tapYRatio = replyObj.tapYRatio,
                    sourceType = "VIDEO_CHAT_CORRECTION (${replyObj.modelUsed})"
                )

                withContext(Dispatchers.Main) {
                    appendMsg("Gemini 3.8", replyObj.replyText)
                    chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
                    refreshMemoryEverything()
                }
            }
        }

        dialog.show()
    }

    /**
     * TAB 5: Engine Settings & Dedicated LIVE LOGS Section
     */
    private fun buildTab5EngineAndLogs(): View {
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        engineScrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        val logsCard = createSectionCard("📜 Live System & Action History Logs")
        val logControlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }

        fun logActionBtn(label: String, bgColor: Int, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                setTextColor(textPureWhite)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = roundedBox(bgColor, dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener { onClick() }
            }
        }

        logControlsRow.addView(logActionBtn("🔄 Refresh", accentRoyalBlue) { refreshLiveLogsBox() })
        logControlsRow.addView(logActionBtn("📋 Copy Logs", Color.parseColor("#334155")) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("auradroid_logs", liveLogsTextView.text))
            Toast.makeText(this, "All Logs Copied to Clipboard!", Toast.LENGTH_SHORT).show()
        })
        logControlsRow.addView(logActionBtn("🗑️ Clear", Color.parseColor("#7F1D1D")) {
            synchronized(AutoTouchService.liveLogs) { AutoTouchService.liveLogs.clear() }
            (AutoTouchService.instance?.memoryVault ?: MemoryVault(this)).clearPersistentLogs()
            refreshLiveLogsBox()
        })

        liveLogsTextView = TextView(this).apply {
            text = "Loading logs..."
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(bgMatteDark, dp(10), borderSubtle)
            minLines = 8
        }

        logsCard.addView(logControlsRow)
        logsCard.addView(liveLogsTextView)

        val apiCard = createSectionCard("🔑 Gemini API & Free-Tier Model Ladder")
        val keyInput = createCleanInput(
            prefs.getString("gemini_api_key", "") ?: "",
            "Paste your Google AI Studio Gemini API Key"
        )
        val saveKeyBtn = createPrimaryButton("💾 Save API Key & Activate Free Ladder", accentRoyalBlue) {
            val k = keyInput.text.toString().trim()
            prefs.edit().putString("gemini_api_key", k).apply()
            AutoTouchService.instance?.geminiBrain?.updateApiKey(k)
            Toast.makeText(this, "API Key Updated!", Toast.LENGTH_SHORT).show()
            refreshLiveLogsBox()
        }

        val ladderInfo = TextView(this).apply {
            text = AutoTouchService.instance?.geminiBrain?.getActiveModelListStatus()
                ?: "✅ gemini-3.1-flash-lite (Fastest Free First)\n✅ gemini-3.5-flash-lite\n✅ gemini-3.8-flash (Post-Run Auto-Reflection & Video Brain)\n✅ gemini-robotics-er-2-preview"
            setTextColor(textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(10), 0, 0)
        }

        apiCard.addView(keyInput)
        apiCard.addView(spacer(dp(10)))
        apiCard.addView(saveKeyBtn)
        apiCard.addView(ladderInfo)

        val modeCard = createSectionCard("🎛️ Execution Mode & Before/After Touch Learning")

        var currentMode = prefs.getString("execution_mode", "LIVE_PHONE") ?: "LIVE_PHONE"
        val modeSwitchBtn = createPrimaryButton(
            if (currentMode == "LIVE_PHONE") "📱 Mode: LIVE REAL TOUCH (Direct Phone Control)"
            else "🌐 Mode: BACKGROUND WEB STUDIO (Silent)",
            accentEmerald
        ) { btn ->
            currentMode = if (currentMode == "LIVE_PHONE") "BACKGROUND_WEB" else "LIVE_PHONE"
            prefs.edit().putString("execution_mode", currentMode).apply()
            btn.text = if (currentMode == "LIVE_PHONE") "📱 Mode: LIVE REAL TOUCH (Direct Phone Control)"
            else "🌐 Mode: BACKGROUND WEB STUDIO (Silent)"
            refreshLiveLogsBox()
        }

        var teachMode = prefs.getBoolean("passive_teach_mode", true)
        val teachSwitchBtn = createPrimaryButton(
            if (teachMode) "🎓 Before+After Auto Touch Learning: ON" else "⚪ Before+After Auto Touch Learning: OFF",
            if (teachMode) accentRoyalBlue else Color.parseColor("#475569")
        ) { btn ->
            teachMode = !teachMode
            prefs.edit().putBoolean("passive_teach_mode", teachMode).apply()
            btn.text = if (teachMode) "🎓 Before+After Auto Touch Learning: ON" else "⚪ Before+After Auto Touch Learning: OFF"
            refreshLiveLogsBox()
        }

        modeCard.addView(modeSwitchBtn)
        modeCard.addView(spacer(dp(10)))
        modeCard.addView(teachSwitchBtn)

        val bodyCard = createSectionCard("🦾 Calibrated Phone Body & On-Demand Permissions")
        val specs = AutoTouchService.instance?.getCalibratedDeviceBodySpecs()
        val specsTv = TextView(this).apply {
            text = if (specs != null) {
                "• Phone Model: ${specs.modelName}\n" +
                    "• Real Full-Screen Pixels: ${specs.screenWidth} × ${specs.screenHeight} px\n" +
                    "• Top Punch-Hole / Cutout Safe Zone: 0 to ${specs.cutoutSafeTop} px\n" +
                    "• Bottom Nav Bar Height: ${specs.navBarHeight} px\n" +
                    "• Post-Run Auto-Reflection: Gemini 3.8 Flash Active"
            } else {
                "Enable Accessibility Service below to calibrate your screen, notch & punch-hole cutout."
            }
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        val overlayBtn = createPrimaryButton("🪟 1. Grant Floating Baymax Overlay Permission", Color.parseColor("#334155")) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                AutoTouchService.instance?.hud?.showHud()
                Toast.makeText(this, "Baymax Floating Face Active!", Toast.LENGTH_SHORT).show()
            }
        }

        val accessBtn = createPrimaryButton("♿ 2. Enable AuraDroid Accessibility Service", accentRoyalBlue) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val storageBtn = createPrimaryButton("📁 3. Turn ON File Manager Access (On-Demand Only)", Color.parseColor("#0D9488")) {
            if (requestStoragePermissionsOnDemand()) {
                Toast.makeText(this, "File Manager Access Active!", Toast.LENGTH_SHORT).show()
                refreshLiveLogsBox()
            }
        }

        val batteryBtn = createPrimaryButton("🔋 4. Enable 24/7 Unstoppable Battery Mode", accentEmerald) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "24/7 Unstoppable Mode Already Active!", Toast.LENGTH_SHORT).show()
            }
        }

        bodyCard.addView(specsTv)
        bodyCard.addView(spacer(dp(12)))
        bodyCard.addView(overlayBtn)
        bodyCard.addView(spacer(dp(8)))
        bodyCard.addView(accessBtn)
        bodyCard.addView(spacer(dp(8)))
        bodyCard.addView(storageBtn)
        bodyCard.addView(spacer(dp(8)))
        bodyCard.addView(batteryBtn)

        container.addView(logsCard)
        container.addView(apiCard)
        container.addView(modeCard)
        container.addView(bodyCard)
        engineScrollView.addView(container)
        refreshLiveLogsBox()
        return engineScrollView
    }

    private fun refreshLiveLogsBox() {
        if (!::liveLogsTextView.isInitialized) return
        val inMemory = synchronized(AutoTouchService.liveLogs) { AutoTouchService.liveLogs.toList() }
        val lines = if (inMemory.isNotEmpty()) {
            inMemory.take(45)
        } else {
            (AutoTouchService.instance?.memoryVault ?: MemoryVault(this)).getPersistentLogs().take(45)
        }
        liveLogsTextView.text = if (lines.isEmpty()) {
            "No logs yet. Start any task or change a setting to see live history here."
        } else {
            lines.joinToString("\n")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return

        when (requestCode) {
            REQ_VOICE_INPUT -> {
                val spoken = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
                chatInputBox.setText(spoken)
            }

            REQ_PICK_FOLDER_TREE -> {
                val treeUri = data.data ?: return
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val split = docId.split(":")
                val resolvedPath = if (split.size >= 2) {
                    "${Environment.getExternalStorageDirectory().absolutePath}/${split[1]}"
                } else {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                }
                folderPathEditText.setText(resolvedPath)
                getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
                    .edit().putString("yt_folder_path", resolvedPath).apply()
                Toast.makeText(this, "📂 Selected Folder: $resolvedPath", Toast.LENGTH_SHORT).show()
            }

            REQ_PICK_SINGLE_VIDEO_FOR_YT -> {
                val videoUri = data.data ?: return
                try {
                    contentResolver.takePersistableUriPermission(videoUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                val displayName = getFileNameFromUri(videoUri)
                AutoTouchService.instance?.startSinglePickedVideoYouTubeUpload(videoUri, displayName)
            }

            REQ_PICK_TRAINING_VIDEO -> {
                val videoUri = data.data ?: return
                Toast.makeText(this, "12 फ्रेम्स निकालकर Gemini 3.8 Flash से एनालाइज किया जा रहा है...", Toast.LENGTH_LONG).show()
                val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
                val brain = AutoTouchService.instance?.geminiBrain ?: GeminiBrain(prefs.getString("gemini_api_key", "") ?: "")
                val vault = AutoTouchService.instance?.memoryVault ?: MemoryVault(this)

                uiScope.launch(Dispatchers.IO) {
                    val (session, framesB64) = vault.createVideoTrainingSession(
                        appCategory = selectedTrainingCategory,
                        userNote = selectedTrainingNote,
                        videoUri = videoUri,
                        maxFrames = 12
                    )

                    val learnedRulesList = brain.analyzeTrainingVideoFrames(
                        appCategory = selectedTrainingCategory,
                        userInstruction = selectedTrainingNote,
                        framesBase64 = framesB64
                    )

                    for (rule in learnedRulesList) {
                        vault.saveStuckOrHumanFix(
                            category = selectedTrainingCategory,
                            screenSignature = rule.screenSignature,
                            problemDescription = rule.problemDescription,
                            solutionRule = rule.solutionRule,
                            tapXRatio = rule.tapXRatio,
                            tapYRatio = rule.tapYRatio,
                            sourceType = rule.sourceType
                        )
                        vault.appendChatToVideoSession(session.sessionId, "Gemini 3.8", "Learned Rule: ${rule.solutionRule}", rule.solutionRule)
                    }

                    withContext(Dispatchers.Main) {
                        refreshMemoryEverything()
                        Toast.makeText(
                            this@MainActivity,
                            "✅ Video Trained & Saved in Memory/$selectedTrainingCategory!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "viral_short_video.mp4"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(idx) ?: name
                }
            }
        } catch (_: Exception) {}
        return name
    }

    private fun startVoiceInput() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            }
            startActivityForResult(intent, REQ_VOICE_INPUT)
        } catch (_: Exception) {
            Toast.makeText(this, "Voice input not supported", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createSectionCard(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBox(cardSlate, dp(16), borderSubtle)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }

            val header = TextView(this@MainActivity).apply {
                text = title
                setTextColor(textPureWhite)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(12))
            }
            addView(header)
        }
    }

    private fun createCleanInput(initialText: String, hintText: String): EditText {
        return EditText(this).apply {
            setText(initialText)
            hint = hintText
            setHintTextColor(textMuted)
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBox(bgMatteDark, dp(10), borderSubtle)
        }
    }

    private fun createPrimaryButton(label: String, color: Int, onClick: (TextView) -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = roundedBox(color, dp(12))
            setOnClickListener { onClick(this) }
        }
    }

    private fun roundedBox(fillColor: Int, radiusPx: Int, strokeColor: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radiusPx.toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }
    }

    private fun spacer(heightPx: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}
