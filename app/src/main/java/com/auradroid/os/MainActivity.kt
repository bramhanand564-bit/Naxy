package com.auradroid.os

import android.annotation.SuppressLint
import android.app.Activity
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
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.JSONArray

@SuppressLint("SetJavaScriptEnabled")
class MainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Executive Matte Dark Palette (No Neon)
    private val bgMatteDark = Color.parseColor("#121316")
    private val cardSlate = Color.parseColor("#1C1F26")
    private val borderSubtle = Color.parseColor("#2E3440")
    private val textPureWhite = Color.parseColor("#F8FAFC")
    private val textMuted = Color.parseColor("#94A3B8")
    private val accentRoyalBlue = Color.parseColor("#3B82F6")
    private val accentEmerald = Color.parseColor("#10B981")

    private lateinit var tabContainers: Array<View>
    private lateinit var bottomNavButtons: Array<TextView>

    // Chat & Planner state
    private lateinit var chatMessagesLayout: LinearLayout
    private lateinit var chatInputBox: EditText
    private val chatHistoryBuilder = StringBuilder()
    private var latestPlanTitle = ""
    private var latestPlannedSteps = listOf<String>()
    private var latestPlanMode = "LIVE_PHONE"

    // In-App Full Control Browser
    private var studioWebView: WebView? = null
    private var isDesktopMode = false
    private val downloadedFilesLog = mutableListOf<String>()

    // Memory & Video Trainer
    private lateinit var memoryCategoriesLayout: LinearLayout
    private var selectedTrainingCategory = "Instagram"

    companion object {
        private const val REQ_VOICE_INPUT = 201
        private const val REQ_PICK_TRAINING_VIDEO = 202
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

        // Top Executive Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            setBackgroundColor(Color.parseColor("#181B20"))
        }

        val titleView = TextView(this).apply {
            text = "AuraDroid OS"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val serviceBadge = TextView(this).apply {
            val active = AutoTouchService.instance != null
            text = if (active) "● 24/7 Active" else "○ Enable Body"
            setTextColor(if (active) accentEmerald else Color.parseColor("#F59E0B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedBox(Color.parseColor("#222630"), dp(16), borderSubtle)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        header.addView(titleView)
        header.addView(serviceBadge)

        // Content Frame for the 5 Tabs
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
        val tab5Settings = buildTab5EngineAndBodySettings()

        tabContainers = arrayOf(tab1Chat, tab2Automations, tab3Browser, tab4Memory, tab5Settings)
        tabContainers.forEachIndexed { idx, view ->
            view.visibility = if (idx == 0) View.VISIBLE else View.GONE
            contentFrame.addView(view)
        }

        // Bottom 5-Tab Navigation Bar
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
        if (index == 3) refreshMemoryFoldersList()
    }

    /**
     * TAB 1: Interactive AI Chat & Detailed Sub-Task Planner (Discuss -> Breakdown -> Start)
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
            "नमस्ते भाई! मुझे कोई भी काम बताओ (जैसे: 'Instagram खोलो, भोजपुरी गाना सर्च करो और पहला वीडियो चलाओ' या 'YouTube पर फोल्डर से वीडियो अपलोड करो')। मैं पहले पूरा स्टेप-बाय-स्टेप प्लान दिखाऊँगा, फिर आपकी मंजूरी से काम शुरू करूँगा!",
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
            addChatBubble("AuraDroid ($modelUsed)", reply, isUser = false)

            if (ready && stepsList.isNotEmpty()) {
                latestPlanTitle = title
                latestPlannedSteps = stepsList
                latestPlanMode = mode
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
     * TAB 2: Universal Automations, Folder Video -> YouTube (Oldest/Newest) & Google Flow 8s Daily Scheduler
     */
    private fun buildTab2AutomationsAndScheduler(): View {
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        // Card 1: Local Folder Video -> YouTube Shorts Direct App Uploader (Oldest vs Newest)
        val ytCard = createSectionCard("🎬 Folder Video ➔ YouTube Shorts Auto-Uploader")
        val defaultFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        val folderInput = createCleanInput(
            prefs.getString("yt_folder_path", defaultFolder) ?: defaultFolder,
            "Video Folder Path (e.g. /storage/emulated/0/Download)"
        )

        var pickOldest = prefs.getBoolean("yt_pick_oldest", true)
        val orderToggleBtn = TextView(this).apply {
            text = if (pickOldest) "📂 Order: Oldest Video First (पुरानी पहले)" else "📂 Order: Newest Video First (नई पहले)"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBox(Color.parseColor("#262B36"), dp(10), borderSubtle)
            setOnClickListener {
                pickOldest = !pickOldest
                prefs.edit().putBoolean("yt_pick_oldest", pickOldest).apply()
                text = if (pickOldest) "📂 Order: Oldest Video First (पुरानी पहले)" else "📂 Order: Newest Video First (नई पहले)"
            }
        }

        val runYtNowBtn = createPrimaryButton("🚀 Instant Test: अभी 1 वीडियो YouTube पर अपलोड करें", accentEmerald) {
            val path = folderInput.text.toString().trim()
            prefs.edit().putString("yt_folder_path", path).apply()
            val svc = AutoTouchService.instance
            if (svc == null) {
                Toast.makeText(this, "Accessibility ऑन करें!", Toast.LENGTH_SHORT).show()
            } else {
                svc.startYouTubeFolderAutoUpload(path, pickOldest)
            }
        }

        ytCard.addView(folderInput)
        ytCard.addView(spacer(dp(8)))
        ytCard.addView(orderToggleBtn)
        ytCard.addView(spacer(dp(12)))
        ytCard.addView(runYtNowBtn)

        // Card 2: Google Flow (1000 Credits) -> 8s Video -> YouTube Shorts Pipeline
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
                ?: Toast.makeText(this, "Accessibility ऑन करें!", Toast.LENGTH_SHORT).show()
        }

        flowCard.addView(topicInput)
        flowCard.addView(spacer(dp(8)))
        flowCard.addView(creditLimitInput)
        flowCard.addView(spacer(dp(12)))
        flowCard.addView(runFlowNowBtn)

        // Card 3: 24/7 Daily Time Scheduler (e.g. 19:00 / 7:00 PM or 20:00 / 8:00 PM)
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
            prefs.edit()
                .putBoolean("scheduler_enabled", schedEnabled)
                .putString("schedule_time_hm", timeInput.text.toString().trim())
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
     * TAB 3: Full-Control In-App Browser (Manual Gmail Login + Back/Next/Refresh/Desktop/Downloads/Save Page)
     */
    private fun buildTab3FullControlBrowser(): View {
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Top Navigation & Control Bar
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

        // Second Action Strip: Google Login, Desktop Toggle, Downloads, Pin Target Page
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
     * TAB 4: On-Demand Stuck/Human-Fix App Folders (Memory/Instagram, Memory/YouTube) + Screen Recording Video Trainer
     */
    private fun buildTab4MemoryAndVideoTrainer(): View {
        val vault = AutoTouchService.instance?.memoryVault ?: MemoryVault(this)
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        // Card 1: Video Trainer (Upload Screen Recording Video -> Gemini 3.8/Pro learns every frame)
        val trainerCard = createSectionCard("📹 Video Trainer (वीडियो दिखाकर नया काम सिखाएं)")
        val catInput = createCleanInput("Instagram", "App Folder Name (e.g. Instagram, YouTube, WhatsApp)")
        val noteInput = createCleanInput(
            "इस वीडियो के हर स्टेप को समझो और याद रखो",
            "इस वीडियो में क्या सिखाया गया है?"
        )

        val pickVideoBtn = createPrimaryButton("📤 Select Screen Recording Video & Train AI", accentRoyalBlue) {
            selectedTrainingCategory = catInput.text.toString().trim().ifEmpty { "General" }
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
            }
            startActivityForResult(intent, REQ_PICK_TRAINING_VIDEO)
        }

        trainerCard.addView(catInput)
        trainerCard.addView(spacer(dp(8)))
        trainerCard.addView(noteInput)
        trainerCard.addView(spacer(dp(12)))
        trainerCard.addView(pickVideoBtn)

        // Card 2: App-Wise Learned Fixes Folders (Only Stuck & Human Fixes)
        val foldersCard = createSectionCard("📁 App-Wise Stuck & Human-Fix Memory Folders")
        memoryCategoriesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        foldersCard.addView(memoryCategoriesLayout)

        // Card 3: Personal Info & Web Form Auto-Fill Vault
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
        container.addView(foldersCard)
        container.addView(profileCard)
        scroll.addView(container)
        return scroll
    }

    private fun refreshMemoryFoldersList() {
        if (!::memoryCategoriesLayout.isInitialized) return
        memoryCategoriesLayout.removeAllViews()
        val vault = AutoTouchService.instance?.memoryVault ?: MemoryVault(this)
        val summary = vault.getAllCategoriesSummary()

        if (summary.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "अभी कोई Stuck या Human-Fix रिकॉर्ड नहीं है। जब भी AI कहीं अटकेगा और आप टैप करेंगे, यहाँ अपने-आप App Folder बन जाएगा!"
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

            val title = TextView(this).apply {
                text = "📂 Memory/$cat/ ($count Learned Fixes)"
                setTextColor(textPureWhite)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
            }
            row.addView(title)

            val fixes = vault.recallFixesForApp(cat).takeLast(3)
            for (f in fixes) {
                val item = TextView(this).apply {
                    text = "  • ${f.problemDescription} ➔ ${f.solutionRule}"
                    setTextColor(textMuted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(4), 0, 0)
                }
                row.addView(item)
            }
            memoryCategoriesLayout.addView(row)
        }
    }

    /**
     * TAB 5: Engine Settings, Live vs Background Switch, Passive Teach Toggle, Free-Tier Model Ladder & Body Calibration
     */
    private fun buildTab5EngineAndBodySettings(): View {
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        // Card 1: Gemini API Key & Free-Tier Model Ladder (2.5 Flash -> 3.1 -> 3.5 -> 3.8 Pro)
        val apiCard = createSectionCard("🔑 Gemini API & Free-Tier Model Ladder")
        val keyInput = createCleanInput(
            prefs.getString("gemini_api_key", "") ?: "",
            "Paste your Google AI Studio Gemini API Key"
        )
        val saveKeyBtn = createPrimaryButton("💾 Save API Key & Activate Ladder", accentRoyalBlue) {
            val k = keyInput.text.toString().trim()
            prefs.edit().putString("gemini_api_key", k).apply()
            AutoTouchService.instance?.geminiBrain?.updateApiKey(k)
            Toast.makeText(this, "API Key Updated!", Toast.LENGTH_SHORT).show()
        }

        val ladderInfo = TextView(this).apply {
            text = AutoTouchService.instance?.geminiBrain?.getActiveModelListStatus()
                ?: "✅ gemini-2.5-flash (Fastest)\n✅ gemini-3.1-flash-lite\n✅ gemini-3.5-flash-lite\n✅ gemini-3.8-flash (Smart Agent)\n✅ gemini-3.1-pro-preview (Deep Memory)\n✅ gemini-robotics-er-2-preview"
            setTextColor(textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(10), 0, 0)
        }

        apiCard.addView(keyInput)
        apiCard.addView(spacer(dp(10)))
        apiCard.addView(saveKeyBtn)
        apiCard.addView(ladderInfo)

        // Card 2: Execution Mode (Live Real Touch vs Background Web) & Passive Teach Mode
        val modeCard = createSectionCard("🎛️ Execution Mode & Human-Fix Learning")

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
        }

        var teachMode = prefs.getBoolean("passive_teach_mode", true)
        val teachSwitchBtn = createPrimaryButton(
            if (teachMode) "🎓 Stuck & Human-Fix Teach Mode: ON" else "⚪ Stuck & Human-Fix Teach Mode: OFF",
            if (teachMode) accentRoyalBlue else Color.parseColor("#475569")
        ) { btn ->
            teachMode = !teachMode
            prefs.edit().putBoolean("passive_teach_mode", teachMode).apply()
            btn.text = if (teachMode) "🎓 Stuck & Human-Fix Teach Mode: ON" else "⚪ Stuck & Human-Fix Teach Mode: OFF"
        }

        modeCard.addView(modeSwitchBtn)
        modeCard.addView(spacer(dp(10)))
        modeCard.addView(teachSwitchBtn)

        // Card 3: Calibrated Device Body Specs & Permissions
        val bodyCard = createSectionCard("🦾 Calibrated Phone Body & 24/7 Permissions")
        val specs = AutoTouchService.instance?.getCalibratedDeviceBodySpecs()
        val specsTv = TextView(this).apply {
            text = if (specs != null) {
                "• Phone Model: ${specs.modelName}\n" +
                    "• Real Full-Screen Pixels: ${specs.screenWidth} × ${specs.screenHeight} px\n" +
                    "• Top Punch-Hole / Cutout Safe Zone: 0 to ${specs.cutoutSafeTop} px\n" +
                    "• Bottom Nav Bar Height: ${specs.navBarHeight} px\n" +
                    "• Screenshot Format: HD WebP (85% Crystal Clear)"
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

        val batteryBtn = createPrimaryButton("🔋 3. Enable 24/7 Unstoppable Battery Mode", accentEmerald) {
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
        bodyCard.addView(batteryBtn)

        container.addView(apiCard)
        container.addView(modeCard)
        container.addView(bodyCard)
        scroll.addView(container)
        return scroll
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return

        if (requestCode == REQ_VOICE_INPUT) {
            val spoken = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            chatInputBox.setText(spoken)
        } else if (requestCode == REQ_PICK_TRAINING_VIDEO) {
            val videoUri = data.data ?: return
            Toast.makeText(this, "Extracting video frames for Gemini 3.8/Pro...", Toast.LENGTH_LONG).show()
            val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
            val brain = AutoTouchService.instance?.geminiBrain ?: GeminiBrain(prefs.getString("gemini_api_key", "") ?: "")
            val vault = AutoTouchService.instance?.memoryVault ?: MemoryVault(this)

            uiScope.launch(Dispatchers.IO) {
                val frames = vault.extractKeyFramesBase64FromVideo(videoUri, maxFrames = 6)
                val learnedWorkflow = brain.analyzeTrainingVideoFrames(
                    appCategory = selectedTrainingCategory,
                    userInstruction = "Learn step-by-step workflow from this user screen recording",
                    framesBase64 = frames
                )
                vault.saveStuckOrHumanFix(
                    category = selectedTrainingCategory,
                    screenSignature = "video_training",
                    problemDescription = "Video Trained Workflow",
                    solutionRule = learnedWorkflow,
                    tapXRatio = 0.5f,
                    tapYRatio = 0.5f
                )
                withContext(Dispatchers.Main) {
                    refreshMemoryFoldersList()
                    Toast.makeText(this@MainActivity, "✅ Video Trained & Saved in Memory/$selectedTrainingCategory!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startVoiceInput() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            }
            startActivityForResult(intent, REQ_VOICE_INPUT)
        } catch (_: Exception) {
            Toast.makeText(this, "Voice input not supported on this device", Toast.LENGTH_SHORT).show()
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
