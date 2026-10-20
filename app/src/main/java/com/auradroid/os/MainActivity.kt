package com.auradroid.os

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import android.view.animation.LinearInterpolator
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.Calendar
import kotlin.math.sin

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class MainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var logAutoRefreshJob: Job? = null

    private val bgMatteDark = Color.parseColor("#121316")
    private val cardSlate = Color.parseColor("#1C1F26")
    private val borderSubtle = Color.parseColor("#2E3440")
    private val textPureWhite = Color.parseColor("#F8FAFC")
    private val textMuted = Color.parseColor("#94A3B8")
    private val accentRoyalBlue = Color.parseColor("#3B82F6")
    private val accentEmerald = Color.parseColor("#10B981")
    private val accentPurple = Color.parseColor("#8B5CF6")
    private val accentAmber = Color.parseColor("#F59E0B")

    private var currentTabIndex = 0
    private lateinit var serviceBadge: TextView
    private lateinit var tabContainers: Array<View>
    private lateinit var bottomNavButtons: Array<TextView>

    private lateinit var homeBotView: FullBodyChanchalBotView
    private lateinit var homeBotStatusTitle: TextView
    private lateinit var homeBotReportSubtitle: TextView
    private lateinit var chatMessagesLayout: LinearLayout
    private lateinit var chatInputBox: EditText
    private lateinit var planSendBtn: TextView
    private val chatHistoryBuilder = StringBuilder()

    private lateinit var folderPathEditText: EditText
    private lateinit var flowDownloadFolderEditText: EditText
    private lateinit var freeWebDownloadFolderEditText: EditText

    private var studioWebView: WebView? = null
    private var isDesktopMode = false
    private val downloadedFilesLog = mutableListOf<String>()

    private lateinit var memoryCategoriesLayout: LinearLayout
    private lateinit var automationRunsListLayout: LinearLayout
    private lateinit var videoSessionsListLayout: LinearLayout

    private var selectedTrainingCategory = "Instagram"
    private var selectedTrainingNote = "इस वीडियो के हर फ्रेम को देखो, जहाँ गलती हुई है उसे सुधारो और सही बटन के कोऑर्डिनेट सेव कर लो"

    private lateinit var liveLogsTextView: TextView
    private lateinit var ladderInfoTextView: TextView
    private lateinit var calibratedSpecsTextView: TextView
    private lateinit var engineScrollView: ScrollView

    private lateinit var localVault: MemoryVault
    private lateinit var localBrain: GeminiBrain

    companion object {
        private const val REQ_VOICE_INPUT = 201
        private const val REQ_PICK_TRAINING_VIDEO = 202
        private const val REQ_PICK_FOLDER_TREE = 203
        private const val REQ_PICK_SINGLE_VIDEO_FOR_YT = 204
        private const val REQ_PICK_FLOW_SAVE_FOLDER = 205
        private const val REQ_PICK_FREEWEB_SAVE_FOLDER = 206
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgMatteDark
        window.navigationBarColor = Color.parseColor("#181B20")

        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        localVault = MemoryVault(this)
        localBrain = GeminiBrain(prefs.getString("gemini_api_key", "") ?: "").apply {
            onBrainLog = { msg -> AutoTouchService.log(msg) }
        }

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
                refreshEngineTabDetails()
                engineScrollView.post { engineScrollView.fullScroll(View.FOCUS_UP) }
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val tab1HomeChat = buildTab1HomeAndChatPlanner()
        val tab2Automations = buildTab2AutomationsAndScheduler()
        val tab3Browser = buildTab3FullControlBrowser()
        val tab4Memory = buildTab4MemoryAndVideoTrainer()
        val tab5Settings = buildTab5EngineAndLogs()

        tabContainers = arrayOf(tab1HomeChat, tab2Automations, tab3Browser, tab4Memory, tab5Settings)
        tabContainers.forEachIndexed { idx, view ->
            view.visibility = if (idx == 0) View.VISIBLE else View.GONE
            contentFrame.addView(view)
        }

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#181B20"))
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }

        val labels = arrayOf("🏠 Home", "🔄 Tasks", "🌐 Browser", "🧠 Memory", "⚙️ Engine")
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
        refreshHomeDailyReportBanner()
        refreshEngineTabDetails()
        if (currentTabIndex == 3) refreshMemoryEverything()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentTabIndex == 2 && studioWebView?.canGoBack() == true) {
            studioWebView?.goBack()
            return
        }
        if (currentTabIndex != 0) {
            switchTab(0)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun getActiveBrain(): GeminiBrain {
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("gemini_api_key", "") ?: ""
        localBrain.updateApiKey(savedKey)
        return AutoTouchService.instance?.geminiBrain?.also { it.updateApiKey(savedKey) } ?: localBrain
    }

    private fun getActiveVault(): MemoryVault {
        return AutoTouchService.instance?.memoryVault ?: localVault
    }

    private fun refreshServiceBadge() {
        if (!::serviceBadge.isInitialized) return
        val active = AutoTouchService.instance != null
        serviceBadge.text = if (active) "● 24/7 Active" else "○ Enable Body"
        serviceBadge.setTextColor(if (active) accentEmerald else accentAmber)
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
        currentTabIndex = index
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

        logAutoRefreshJob?.cancel()
        if (index == 0) {
            refreshHomeDailyReportBanner()
        } else if (index == 3) {
            refreshMemoryEverything()
        } else if (index == 4) {
            refreshEngineTabDetails()
            logAutoRefreshJob = uiScope.launch {
                while (isActive && currentTabIndex == 4) {
                    delay(2500L)
                    refreshEngineTabDetails()
                }
            }
        }
    }

    /**
     * TAB 1: Home Page with Cute Full-Body Chanchal Bot, 10 PM Friendly Daily Report & AI Chat Planner
     */
    private fun buildTab1HomeAndChatPlanner(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        val companionCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(Color.parseColor("#182032"), dp(16), accentRoyalBlue)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        homeBotView = FullBodyChanchalBotView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(92), dp(112)).apply {
                marginEnd = dp(12)
            }
            setOnClickListener {
                triggerPlayfulJumpAndWave()
                val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val wish = when {
                    hr in 5..11 -> "☀️ Good Morning भाई! Have a good luck day! आज कौन सा ऑटोमेशन चलाना है?"
                    hr in 12..16 -> "🚀 All the best भाई! मैं आपके सारे टास्क पर नज़र रख रहा हूँ!"
                    hr in 17..21 -> "✨ Good Evening सर! शाम के टास्क और YouTube/Instagram अपलोड के लिए मैं तैयार हूँ!"
                    else -> "🌙 Late night हो गई भाई! आप आराम से सो जाओ, बैकग्राउंड में मैं सारा काम संभाल लूँगा!"
                }
                Toast.makeText(this@MainActivity, wish, Toast.LENGTH_LONG).show()
            }
        }

        val rightStatusCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        homeBotStatusTitle = TextView(this).apply {
            text = "🤖 Chanchal AI Buddy (Active)"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }

        homeBotReportSubtitle = TextView(this).apply {
            text = "सर, आपके आज के सारे ऑटोमेशन ट्रैक हो रहे हैं। रात 10:00 बजे मैं आपको पूरी डेली रिपोर्ट दूँगा!"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(4), 0, dp(8))
        }

        val actionPillsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val dailyReportBtn = TextView(this).apply {
            text = "📊 Daily Report"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBox(accentEmerald, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { showFriendlyDailyReportDialog() }
        }

        val diagnoseBtn = TextView(this).apply {
            text = "🛠️ गलती सुधारें (AI Fix)"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBox(accentPurple, dp(8))
            setOnClickListener { diagnoseLastFailedTaskWithAi() }
        }

        actionPillsRow.addView(dailyReportBtn)
        actionPillsRow.addView(diagnoseBtn)

        rightStatusCol.addView(homeBotStatusTitle)
        rightStatusCol.addView(homeBotReportSubtitle)
        rightStatusCol.addView(actionPillsRow)

        companionCard.addView(homeBotView)
        companionCard.addView(rightStatusCol)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        chatMessagesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(chatMessagesLayout)

        addChatBubble(
            "AuraDroid Chanchal Bot",
            "नमस्ते भाई! मैं आपका फुल-बॉडी चंचल AI दोस्त हूँ!\n" +
                "• ऊपर मुझ पर टैप करेंगे तो मैं हाथ हिलाकर विश करूँगा!\n" +
                "• रोज़ रात 10 बजे मैं खुद बताऊँगा कि 'सर, आपके सारे टास्क पूरे हो गए हैं' और अगर किसी काम में रुकावट आई तो उसे ठीक करने में मदद करूँगा।",
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
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { startVoiceInput() }
        }

        chatInputBox = EditText(this).apply {
            hint = "यहाँ काम समझाएं या बॉट से बात करें..."
            setHintTextColor(textMuted)
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = null
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val directRunBtn = TextView(this).apply {
            text = "⚡"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(accentEmerald, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener {
                val msg = chatInputBox.text.toString().trim()
                if (msg.isNotEmpty()) {
                    val svc = AutoTouchService.instance
                    if (svc == null) {
                        Toast.makeText(this@MainActivity, "पहले Accessibility Service ऑन करें!", Toast.LENGTH_LONG).show()
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } else {
                        chatInputBox.setText("")
                        addChatBubble("You (Direct Run)", msg, isUser = true)
                        svc.startUniversalTask(msg, emptyList())
                    }
                } else {
                    Toast.makeText(this@MainActivity, "पहले बॉक्स में काम लिखें!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        planSendBtn = TextView(this).apply {
            text = "Plan ➔"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(10), dp(14), dp(10))
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
        inputBar.addView(directRunBtn)
        inputBar.addView(planSendBtn)

        container.addView(companionCard)
        container.addView(scroll)
        container.addView(inputBar)
        refreshHomeDailyReportBanner()
        return container
    }

    private fun refreshHomeDailyReportBanner() {
        if (!::homeBotStatusTitle.isInitialized || !::homeBotReportSubtitle.isInitialized) return
        val vault = getActiveVault()
        val runs = vault.getAllAutomationRuns()
        val successCount = runs.count { it.status == "SUCCESS" }
        val failedRun = runs.firstOrNull { it.status != "SUCCESS" }
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        val greetingPrefix = when {
            hr in 5..11 -> "☀️ Good Morning सर!"
            hr in 12..16 -> "🚀 All the best सर!"
            hr in 17..21 -> "✨ Good Evening सर!"
            else -> "🌙 रात के 10+ बज गए सर!"
        }

        if (failedRun != null) {
            homeBotStatusTitle.text = "$greetingPrefix (1 टास्क में ध्यान दें)"
            homeBotReportSubtitle.text = "आज $successCount टास्क पूरे हुए, लेकिन '${failedRun.title.take(22)}' में रुकावट आई। नीचे '🛠️ गलती सुधारें' दबाएं ताकि AI इसे ठीक कर दे!"
            homeBotView.setBotMood(isHappy = false, isCurious = true)
        } else if (runs.isNotEmpty()) {
            homeBotStatusTitle.text = "$greetingPrefix सारे टास्क फिट हैं ✅"
            homeBotReportSubtitle.text = "सर, आपके $successCount टास्क सफलतापूर्वक पूरे हो गए हैं! कोई भी पेंडिंग एरर नहीं है।"
            homeBotView.setBotMood(isHappy = true, isCurious = false)
        } else {
            homeBotStatusTitle.text = "$greetingPrefix Have a good luck day!"
            homeBotReportSubtitle.text = "मैं 24/7 तैयार हूँ। Tasks टैब से अपना टाइम और YouTube + Instagram लिंक सेट करें!"
            homeBotView.setBotMood(isHappy = true, isCurious = false)
        }
    }

    private fun showFriendlyDailyReportDialog() {
        val vault = getActiveVault()
        val runs = vault.getAllAutomationRuns()
        val ok = runs.count { it.status == "SUCCESS" }
        val fail = runs.count { it.status != "SUCCESS" }

        val sb = StringBuilder()
        sb.append("🌙 नमस्ते सर! आपकी आज की डेली टास्क रिपोर्ट:\n\n")
        sb.append("✅ सफल टास्क (Completed): $ok\n")
        sb.append("⚠️ रुके या अधूरे टास्क (Incomplete): $fail\n\n")

        if (runs.isEmpty()) {
            sb.append("• अभी तक आज कोई टास्क रन नहीं हुआ है। आप Tasks टैब में जाकर 7:00 PM या 8:00 PM का टाइमर ऑन कर सकते हैं।")
        } else {
            sb.append("📋 हाल ही के टास्क:\n")
            runs.take(5).forEachIndexed { idx, r ->
                val icon = if (r.status == "SUCCESS") "✅" else "⚠️"
                sb.append("${idx + 1}. $icon ${r.title} (${r.status}) - ${r.timestamp}\n")
            }
        }

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle("🤖 10:00 PM Friendly Daily Update")
            .setMessage(sb.toString())
            .setPositiveButton("ठीक है, शाबाश!") { d, _ -> d.dismiss() }
            .setNeutralButton("🛠️ AI से गलती ठीक करवाएं") { d, _ ->
                d.dismiss()
                diagnoseLastFailedTaskWithAi()
            }
            .show()
    }

    private fun diagnoseLastFailedTaskWithAi() {
        val vault = getActiveVault()
        val failedRun = vault.getAllAutomationRuns().firstOrNull { it.status != "SUCCESS" }
            ?: vault.getAllAutomationRuns().firstOrNull()

        if (failedRun == null) {
            Toast.makeText(this, "सर, अभी कोई भी फेल टास्क नहीं है! सब 100% सही चल रहा है।", Toast.LENGTH_LONG).show()
            return
        }

        openAutomationRunChatDialog(failedRun)
    }

    private fun handleUserChatMessage(userMsg: String, scroll: ScrollView) {
        addChatBubble("You", userMsg, isUser = true)
        chatHistoryBuilder.append("User: $userMsg\n")
        AutoTouchService.log("💬 User Chat: $userMsg")
        homeBotView.triggerPlayfulJumpAndWave()

        val brain = getActiveBrain()
        val vault = getActiveVault()

        planSendBtn.isEnabled = false
        planSendBtn.text = "⏳..."

        uiScope.launch {
            try {
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
            } finally {
                planSendBtn.isEnabled = true
                planSendBtn.text = "Plan ➔"
            }
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(header)

        steps.forEachIndexed { idx, stepText ->
            val stepTv = TextView(this).apply {
                text = "  ${idx + 1}. $stepText"
                setTextColor(Color.parseColor("#E2E8F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                setPadding(0, dp(5), 0, 0)
            }
            card.addView(stepTv)
        }

        val startBtn = TextView(this).apply {
            text = "▶ हाँ, काम शुरू करो (Start Execution)"
            gravity = Gravity.CENTER
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
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
                    svc.hud.showHud()
                    svc.startUniversalTask(rawGoal, steps, mode)
                }
            }
        }

        card.addView(startBtn)
        chatMessagesLayout.addView(card)
    }

    /**
     * TAB 2: Numbered Tasks Menu (#1 Google Flow, #2 Multi-Website Free Video Hub, #3 Local Folder Auto-Uploader)
     */
    private fun buildTab2AutomationsAndScheduler(): View {
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }

        val defaultDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

        // TASK #1: GOOGLE FLOW AUTOMATION
        val flowCard = createSectionCard("1️⃣ #1 Task: Google Flow AI Video Studio")

        var flowExecMode = prefs.getString("flow_execution_mode", "BACKGROUND") ?: "BACKGROUND"
        val flowModeSwitchBtn = TextView(this).apply {
            text = if (flowExecMode == "BACKGROUND") {
                "🌐 Run Mode: 100% Background (आप फोन चलाते रहें, पीछे वीडियो बनेगी और अपलोड होगी)"
            } else {
                "📱 Run Mode: Live Phone Screen (असली स्क्रीन पर सामने चलेगा)"
            }
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(if (flowExecMode == "BACKGROUND") accentRoyalBlue else accentPurple, dp(10))
            setOnClickListener {
                flowExecMode = if (flowExecMode == "BACKGROUND") "LIVE_PHONE" else "BACKGROUND"
                prefs.edit()
                    .putString("flow_execution_mode", flowExecMode)
                    .putBoolean("bg_youtube_upload", flowExecMode == "BACKGROUND")
                    .apply()
                text = if (flowExecMode == "BACKGROUND") {
                    "🌐 Run Mode: 100% Background (आप फोन चलाते रहें, पीछे वीडियो बनेगी और अपलोड होगी)"
                } else {
                    "📱 Run Mode: Live Phone Screen (असली स्क्रीन पर सामने चलेगा)"
                }
                background = roundedBox(if (flowExecMode == "BACKGROUND") accentRoyalBlue else accentPurple, dp(10))
            }
        }

        var isStoryMode = prefs.getBoolean("flow_story_mode", false)
        val storyModeBtn = TextView(this).apply {
            text = if (isStoryMode) {
                "📖 Video Type: Step-by-Step Story Base (कहानी के भाग 1, 2, 3 लगातार बनेंगे)"
            } else {
                "🎬 Video Type: Single Exact Prompt Video (जो प्रॉम्प्ट देंगे बिल्कुल वही बनेगा)"
            }
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(Color.parseColor("#262B36"), dp(10), borderSubtle)
            setOnClickListener {
                isStoryMode = !isStoryMode
                prefs.edit().putBoolean("flow_story_mode", isStoryMode).apply()
                text = if (isStoryMode) {
                    "📖 Video Type: Step-by-Step Story Base (कहानी के भाग 1, 2, 3 लगातार बनेंगे)"
                } else {
                    "🎬 Video Type: Single Exact Prompt Video (जो प्रॉम्प्ट देंगे बिल्कुल वही बनेगा)"
                }
            }
        }

        val topicInput = createCleanInput(
            prefs.getString("flow_topic", "Young Indian girl dancing in traditional attire") ?: "Young Indian girl dancing in traditional attire",
            "Video Prompt / Story Topic (जो लिखेंगे वही बनेगा)"
        )

        val creditLimitInput = createCleanInput(
            prefs.getInt("flow_daily_videos", 1).toString(),
            "कितनी वीडियो बनानी हैं? (1, 2, या 3 Story Parts)"
        )

        flowDownloadFolderEditText = createCleanInput(
            prefs.getString("custom_download_folder", defaultDownloadDir) ?: defaultDownloadDir,
            "किस फोल्डर में डाउनलोड/सेव होगा?"
        )

        val pickFlowFolderBtn = createPrimaryButton("📂 डाउनलोड फोल्डर चुनें (Choose Save Folder)", Color.parseColor("#334155")) {
            if (requestStoragePermissionsOnDemand()) {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_FLOW_SAVE_FOLDER)
            }
        }

        val multiPlatformBox = buildMultiPlatformLinkerBox(prefs)

        val aiTaskHelperBtn = createPrimaryButton("🤖 AI से बात करके टॉपिक, स्टोरी और टाइम सेट करें", accentPurple) {
            showMiniTaskAiAssistantDialog(topicInput, creditLimitInput)
        }

        val runFlowNowBtn = createPrimaryButton("🚀 अभी #1 Google Flow चलाएं (Create & Auto-Upload)", accentEmerald) {
            val rawTopic = topicInput.text.toString().trim()
            val count = creditLimitInput.text.toString().toIntOrNull()?.coerceIn(1, 5) ?: 1
            val customSaveDir = flowDownloadFolderEditText.text.toString().trim().ifEmpty { defaultDownloadDir }
            val finalTopic = if (isStoryMode) "Step-by-step continuous story: $rawTopic" else rawTopic

            prefs.edit()
                .putString("flow_topic", rawTopic)
                .putInt("flow_daily_videos", count)
                .putString("custom_download_folder", customSaveDir)
                .putBoolean("bg_youtube_upload", flowExecMode == "BACKGROUND")
                .apply()

            val svc = AutoTouchService.instance
            if (svc == null) {
                Toast.makeText(this, "पहले Accessibility Service ऑन करें!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                svc.startGoogleFlowToYouTubePipeline(finalTopic, count)
            }
        }

        flowCard.addView(flowModeSwitchBtn)
        flowCard.addView(spacer(dp(8)))
        flowCard.addView(storyModeBtn)
        flowCard.addView(spacer(dp(8)))
        flowCard.addView(topicInput)
        flowCard.addView(spacer(dp(8)))
        flowCard.addView(creditLimitInput)
        flowCard.addView(spacer(dp(8)))
        flowCard.addView(flowDownloadFolderEditText)
        flowCard.addView(spacer(dp(6)))
        flowCard.addView(pickFlowFolderBtn)
        flowCard.addView(spacer(dp(10)))
        flowCard.addView(multiPlatformBox)
        flowCard.addView(spacer(dp(10)))
        flowCard.addView(aiTaskHelperBtn)
        flowCard.addView(spacer(dp(10)))
        flowCard.addView(runFlowNowBtn)

        // TASK #2: MULTI-WEBSITE FREE VIDEO HUB
        val freeHubCard = createSectionCard("2️⃣ #2 Task: Multi-Website Free AI Video Hub")

        var selectedFreeSite = prefs.getString("free_hub_site", "https://gemini.google.com") ?: "https://gemini.google.com"
        val siteChipsScroll = HorizontalScrollView(this)
        val siteChipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }

        val freeSiteInput = createCleanInput(selectedFreeSite, "Free AI Video Website URL or App Name")

        val presetSites = listOf(
            "✨ Gemini" to "https://gemini.google.com",
            "🤖 ChatGPT" to "https://chatgpt.com",
            "🎬 Hailuo Free" to "https://hailuoai.video",
            "🎥 Kling AI" to "https://klingai.com",
            "🌊 Google Flow" to "https://labs.google/fx/tools/flow"
        )

        presetSites.forEach { (label, url) ->
            val chip = TextView(this).apply {
                text = label
                setTextColor(textPureWhite)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = roundedBox(Color.parseColor("#262B36"), dp(8), borderSubtle)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                setOnClickListener {
                    selectedFreeSite = url
                    freeSiteInput.setText(url)
                    prefs.edit().putString("free_hub_site", url).apply()
                }
            }
            siteChipsRow.addView(chip)
        }
        siteChipsScroll.addView(siteChipsRow)

        var freeHubExecMode = prefs.getString("free_hub_exec_mode", "LIVE_WHEN_IDLE") ?: "LIVE_WHEN_IDLE"
        val freeHubModeBtn = TextView(this).apply {
            text = when (freeHubExecMode) {
                "BACKGROUND" -> "🌐 Mode: Background WebView (पीछे ब्राउज़र में चलेगा)"
                "LIVE_WHEN_IDLE" -> "🌙 Mode: Real App When Phone is Free (जब आप फोन नहीं चला रहे हों तब असली ऐप चलाएगा)"
                else -> "📱 Mode: Fixed Time Real App (तय समय पर असली ऐप में चलाएगा)"
            }
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(Color.parseColor("#1E3A8A"), dp(10), accentRoyalBlue)
            setOnClickListener {
                freeHubExecMode = when (freeHubExecMode) {
                    "BACKGROUND" -> "LIVE_WHEN_IDLE"
                    "LIVE_WHEN_IDLE" -> "LIVE_FIXED_TIME"
                    else -> "BACKGROUND"
                }
                prefs.edit()
                    .putString("free_hub_exec_mode", freeHubExecMode)
                    .putBoolean("run_only_when_idle", freeHubExecMode == "LIVE_WHEN_IDLE")
                    .apply()
                text = when (freeHubExecMode) {
                    "BACKGROUND" -> "🌐 Mode: Background WebView (पीछे ब्राउज़र में चलेगा)"
                    "LIVE_WHEN_IDLE" -> "🌙 Mode: Real App When Phone is Free (जब आप फोन नहीं चला रहे हों तब असली ऐप चलाएगा)"
                    else -> "📱 Mode: Fixed Time Real App (तय समय पर असली ऐप में चलाएगा)"
                }
            }
        }

        val freeHubPromptInput = createCleanInput(
            prefs.getString("free_hub_prompt", "Create a viral 9:16 short video and download it") ?: "",
            "इस वेबसाइट/ऐप से कैसी वीडियो बनवानी है?"
        )

        freeWebDownloadFolderEditText = createCleanInput(
            prefs.getString("custom_download_folder", defaultDownloadDir) ?: defaultDownloadDir,
            "डाउनलोड की गई वीडियो किस फोल्डर में सेव हो?"
        )

        val pickFreeWebFolderBtn = createPrimaryButton("📂 फोल्डर पाथ चुनें (Browse Download Path)", Color.parseColor("#334155")) {
            if (requestStoragePermissionsOnDemand()) {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_FREEWEB_SAVE_FOLDER)
            }
        }

        val runFreeHubBtn = createPrimaryButton("🎬 #2 Free Video Hub चलाएं (Download ➔ YouTube/Insta)", accentRoyalBlue) {
            val site = freeSiteInput.text.toString().trim()
            val prompt = freeHubPromptInput.text.toString().trim()
            val saveDir = freeWebDownloadFolderEditText.text.toString().trim().ifEmpty { defaultDownloadDir }
            prefs.edit()
                .putString("free_hub_site", site)
                .putString("free_hub_prompt", prompt)
                .putString("custom_download_folder", saveDir)
                .apply()

            val svc = AutoTouchService.instance
            if (svc == null) {
                Toast.makeText(this, "पहले Accessibility Service ऑन करें!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                val ytOn = prefs.getBoolean("yt_link_enabled", true)
                val instaOn = prefs.getBoolean("insta_link_enabled", false)
                val fullGoal = "Open $site, generate video for '$prompt', save to $saveDir, and upload to " +
                    listOfNotNull(if (ytOn) "YouTube Shorts" else null, if (instaOn) "Instagram Reels" else null).joinToString(" & ")
                val mode = if (freeHubExecMode == "BACKGROUND") "BACKGROUND_WEB" else "LIVE_PHONE"
                svc.startUniversalTask(fullGoal, emptyList(), mode)
            }
        }

        freeHubCard.addView(siteChipsScroll)
        freeHubCard.addView(freeSiteInput)
        freeHubCard.addView(spacer(dp(8)))
        freeHubCard.addView(freeHubModeBtn)
        freeHubCard.addView(spacer(dp(8)))
        freeHubCard.addView(freeHubPromptInput)
        freeHubCard.addView(spacer(dp(8)))
        freeHubCard.addView(freeWebDownloadFolderEditText)
        freeHubCard.addView(spacer(dp(6)))
        freeHubCard.addView(pickFreeWebFolderBtn)
        freeHubCard.addView(spacer(dp(10)))
        freeHubCard.addView(runFreeHubBtn)

        // TASK #3: LOCAL FOLDER / GALLERY VIDEO -> YOUTUBE & INSTAGRAM AUTO-UPLOADER
        val ytCard = createSectionCard("3️⃣ #3 Task: Folder Video ➔ YouTube & Instagram Uploader")

        var folderUploadBgMode = prefs.getBoolean("bg_youtube_upload", true)
        val folderModeSwitchBtn = TextView(this).apply {
            text = if (folderUploadBgMode) {
                "🌐 Upload Mode: 100% Background YouTube Upload (बिना ऐप खोले चुपचाप अपलोड होगा)"
            } else {
                "📱 Upload Mode: Live YouTube/Instagram App (स्क्रीन पर ऐप खोलकर अपलोड करेगा)"
            }
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(if (folderUploadBgMode) accentRoyalBlue else accentPurple, dp(10))
            setOnClickListener {
                folderUploadBgMode = !folderUploadBgMode
                prefs.edit().putBoolean("bg_youtube_upload", folderUploadBgMode).apply()
                text = if (folderUploadBgMode) {
                    "🌐 Upload Mode: 100% Background YouTube Upload (बिना ऐप खोले चुपचाप अपलोड होगा)"
                } else {
                    "📱 Upload Mode: Live YouTube/Instagram App (स्क्रीन पर ऐप खोलकर अपलोड करेगा)"
                }
                background = roundedBox(if (folderUploadBgMode) accentRoyalBlue else accentPurple, dp(10))
            }
        }

        folderPathEditText = createCleanInput(
            prefs.getString("yt_folder_path", defaultDownloadDir) ?: defaultDownloadDir,
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

        val camPath = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera").absolutePath
        val movPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath

        chipsRow.addView(chipBtn("📥 Download", defaultDownloadDir))
        chipsRow.addView(chipBtn("🎥 Camera", camPath))
        chipsRow.addView(chipBtn("🎬 Movies", movPath))

        val browseFolderBtn = createPrimaryButton("📂 फोन से फोल्डर चुनें (Browse Folder)", accentRoyalBlue) {
            if (requestStoragePermissionsOnDemand()) {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_FOLDER_TREE)
            }
        }

        var pickOldest = prefs.getBoolean("yt_pick_oldest", true)
        val orderToggleBtn = TextView(this).apply {
            text = if (pickOldest) "🔃 Order: Oldest Video First (पुरानी पहले)" else "🔃 Order: Newest Video First (नई पहले)"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBox(Color.parseColor("#262B36"), dp(10), borderSubtle)
            setOnClickListener {
                pickOldest = !pickOldest
                prefs.edit().putBoolean("yt_pick_oldest", pickOldest).apply()
                text = if (pickOldest) "🔃 Order: Oldest Video First (पुरानी पहले)" else "🔃 Order: Newest Video First (नई पहले)"
            }
        }

        val runYtNowBtn = createPrimaryButton("🚀 फोल्डर से 1 वीडियो YouTube (+ Linked Insta) पर अपलोड करें", accentEmerald) {
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

        ytCard.addView(folderModeSwitchBtn)
        ytCard.addView(spacer(dp(8)))
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

        // 24/7 SMART SCHEDULER + IDLE PHONE MODE + SKIP FOR 10 MIN
        val schedCard = createSectionCard("⏰ 24/7 Smart Scheduler + Idle Phone & Skip Control")
        val timeInput = createCleanInput(
            prefs.getString("schedule_time_hm", "19:00") ?: "19:00",
            "24-Hour Time (19:00 = 7:00 PM, 20:00 = 8:00 PM)"
        )

        var schedType = prefs.getString("scheduled_pipeline_type", "FLOW_YT") ?: "FLOW_YT"
        val typeSwitchBtn = TextView(this).apply {
            text = if (schedType == "FLOW_YT") "🔄 Daily Task: #1 Google Flow ➔ YouTube/Insta" else "🔄 Daily Task: #3 Local Folder ➔ YouTube/Insta"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBox(Color.parseColor("#262B36"), dp(10), borderSubtle)
            setOnClickListener {
                schedType = if (schedType == "FLOW_YT") "FOLDER_YT" else "FLOW_YT"
                prefs.edit().putString("scheduled_pipeline_type", schedType).apply()
                text = if (schedType == "FLOW_YT") "🔄 Daily Task: #1 Google Flow ➔ YouTube/Insta" else "🔄 Daily Task: #3 Local Folder ➔ YouTube/Insta"
            }
        }

        var onlyWhenIdle = prefs.getBoolean("run_only_when_idle", false)
        val idleModeToggleBtn = TextView(this).apply {
            text = if (onlyWhenIdle) {
                "🌙 Smart Trigger: ON (जब मैं फोन न चला रहा हूँ / फोन 3 मिनट से फ्री हो तभी चलाएं)"
            } else {
                "⌚ Smart Trigger: Fixed Time (ठीक 7:00 बजे चलेगा, और फोन चलाते समय पहले पूछेगा)"
            }
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(if (onlyWhenIdle) accentPurple else Color.parseColor("#262B36"), dp(10), borderSubtle)
            setOnClickListener {
                onlyWhenIdle = !onlyWhenIdle
                prefs.edit().putBoolean("run_only_when_idle", onlyWhenIdle).apply()
                text = if (onlyWhenIdle) {
                    "🌙 Smart Trigger: ON (जब मैं फोन न चला रहा हूँ / फोन 3 मिनट से फ्री हो तभी चलाएं)"
                } else {
                    "⌚ Smart Trigger: Fixed Time (ठीक 7:00 बजे चलेगा, और फोन चलाते समय पहले पूछेगा)"
                }
                background = roundedBox(if (onlyWhenIdle) accentPurple else Color.parseColor("#262B36"), dp(10), borderSubtle)
            }
        }

        val snoozeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }

        val skip10MinBtn = TextView(this).apply {
            text = "⏳ Skip for 10 Min"
            gravity = Gravity.CENTER
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBox(accentAmber, dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                val until = System.currentTimeMillis() + 10 * 60_000L
                prefs.edit().putLong("snooze_until_ms", until).apply()
                Toast.makeText(this@MainActivity, "⏳ टास्क 10 मिनट के लिए Skip कर दिया गया!", Toast.LENGTH_SHORT).show()
            }
        }

        val previewPromptBtn = TextView(this).apply {
            text = "🔔 Test 7 PM Skip Popup"
            gravity = Gravity.CENTER
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBox(Color.parseColor("#334155"), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val svc = AutoTouchService.instance
                if (svc != null) {
                    svc.hud.showPreRunChoicePrompt("7:00 PM Daily Video Task")
                } else {
                    Toast.makeText(this@MainActivity, "पहले Accessibility Service ऑन करें!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        snoozeRow.addView(skip10MinBtn)
        snoozeRow.addView(previewPromptBtn)

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
        schedCard.addView(spacer(dp(8)))
        schedCard.addView(idleModeToggleBtn)
        schedCard.addView(snoozeRow)
        schedCard.addView(spacer(dp(10)))
        schedCard.addView(enableSchedBtn)

        container.addView(flowCard)
        container.addView(freeHubCard)
        container.addView(ytCard)
        container.addView(schedCard)
        scroll.addView(container)
        return scroll
    }

    private fun buildMultiPlatformLinkerBox(prefs: android.content.SharedPreferences): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(Color.parseColor("#161922"), dp(12), borderSubtle)
        }

        val title = TextView(this).apply {
            text = "🔗 Multi-Platform Auto-Upload Linker (वीडियो बनते ही कहाँ-कहाँ अपलोड हो?):"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        box.addView(title)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        var ytLinked = prefs.getBoolean("yt_link_enabled", true)
        val ytChip = TextView(this).apply {
            text = if (ytLinked) "✅ YouTube Shorts" else "⚪ YouTube Shorts"
            gravity = Gravity.CENTER
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBox(if (ytLinked) Color.parseColor("#DC2626") else Color.parseColor("#262B36"), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                ytLinked = !ytLinked
                prefs.edit().putBoolean("yt_link_enabled", ytLinked).apply()
                text = if (ytLinked) "✅ YouTube Shorts" else "⚪ YouTube Shorts"
                background = roundedBox(if (ytLinked) Color.parseColor("#DC2626") else Color.parseColor("#262B36"), dp(8))
            }
        }

        var instaLinked = prefs.getBoolean("insta_link_enabled", false)
        val instaChip = TextView(this).apply {
            text = if (instaLinked) "✅ Instagram Reels" else "⚪ Instagram Reels"
            gravity = Gravity.CENTER
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBox(if (instaLinked) Color.parseColor("#DB2777") else Color.parseColor("#262B36"), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                instaLinked = !instaLinked
                prefs.edit().putBoolean("insta_link_enabled", instaLinked).apply()
                text = if (instaLinked) "✅ Instagram Reels" else "⚪ Instagram Reels"
                background = roundedBox(if (instaLinked) Color.parseColor("#DB2777") else Color.parseColor("#262B36"), dp(8))
            }
        }

        val customStatusTv = TextView(this).apply {
            val savedCustom = prefs.getString("custom_linked_platforms", "") ?: ""
            text = if (savedCustom.isNotEmpty()) "🔗 Extra Linked: $savedCustom" else "कोई अतिरिक्त प्लेटफॉर्म लिंक नहीं है।"
            setTextColor(textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, 0)
        }

        val addCustomBtn = TextView(this).apply {
            text = "➕ Add"
            gravity = Gravity.CENTER
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBox(accentRoyalBlue, dp(8))
            setOnClickListener {
                val input = createCleanInput(
                    prefs.getString("custom_linked_platforms", "Facebook Reels, Moj") ?: "",
                    "नए ऐप या वेबसाइट का नाम लिखें (जैसे Facebook Reels, X, Moj)"
                )
                AlertDialog.Builder(this@MainActivity, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
                    .setTitle("➕ नया अपलोड प्लेटफॉर्म लिंक करें")
                    .setView(input as View)
                    .setPositiveButton("Link Save करें") { _, _ ->
                        val v = input.text.toString().trim()
                        prefs.edit().putString("custom_linked_platforms", v).apply()
                        customStatusTv.text = if (v.isNotEmpty()) "🔗 Extra Linked: $v" else "कोई अतिरिक्त प्लेटफॉर्म लिंक नहीं है।"
                    }
                    .setNegativeButton("रद्द करें", null)
                    .show()
            }
        }

        row.addView(ytChip)
        row.addView(instaChip)
        row.addView(addCustomBtn)
        box.addView(row)
        box.addView(customStatusTv)
        return box
    }

    private fun showMiniTaskAiAssistantDialog(topicField: EditText, countField: EditText) {
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val input = createCleanInput(
            "",
            "जैसे: 'रोज़ शाम 7:30 बजे 2 पार्ट में हॉरर स्टोरी वीडियो बनाकर YouTube और Insta पर डालो'"
        ).apply { minLines = 3 }

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle("🤖 Mini Task AI Configurator")
            .setMessage("भाई, अपनी भाषा में बताइए कि आपको किस टाइप की वीडियो, कितने भाग (Story) और किस टाइम पर अपलोड करवानी है:")
            .setView(input as View)
            .setPositiveButton("⚡ AI से ऑटो-सेट करें") { _, _ ->
                val userWish = input.text.toString().trim()
                if (userWish.isEmpty()) return@setPositiveButton
                Toast.makeText(this, "🤖 AI आपकी सेटिंग्स तैयार कर रहा है...", Toast.LENGTH_SHORT).show()

                uiScope.launch {
                    val plan = getActiveBrain().chatAndBuildDetailedPlan(
                        userMessage = "Extract video topic, story part count, and schedule time from: $userWish",
                        chatHistory = "",
                        userProfileRules = ""
                    )
                    val extractedTitle = plan.optString("taskTitle", userWish).ifEmpty { userWish }
                    topicField.setText(extractedTitle)

                    val timeRegex = Regex("(\\d{1,2})[:.](\\d{2})")
                    val match = timeRegex.find(userWish)
                    if (match != null) {
                        prefs.edit().putString("schedule_time_hm", match.value.replace(".", ":")).putBoolean("scheduler_enabled", true).apply()
                    }
                    if (userWish.contains("story", ignoreCase = true) || userWish.contains("कहानी") || userWish.contains("पार्ट")) {
                        prefs.edit().putBoolean("flow_story_mode", true).apply()
                        countField.setText("2")
                    }
                    if (userWish.contains("insta", ignoreCase = true) || userWish.contains("इंस्टा")) {
                        prefs.edit().putBoolean("insta_link_enabled", true).apply()
                    }
                    Toast.makeText(this@MainActivity, "✅ AI ने आपका टॉपिक, स्टोरी मोड और शेड्यूल सेट कर दिया!", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("बंद करें", null)
            .show()
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

        toolStrip.addView(navBtn("🔑 Gmail") {
            studioWebView?.loadUrl("https://accounts.google.com")
        })
        toolStrip.addView(navBtn("🎬 Flow") {
            studioWebView?.loadUrl("https://labs.google/fx/tools/flow")
        })
        toolStrip.addView(navBtn("📺 YT Studio") {
            studioWebView?.loadUrl("https://studio.youtube.com")
        })
        toolStrip.addView(navBtn("🖥️ Desktop") {
            isDesktopMode = !isDesktopMode
            studioWebView?.settings?.userAgentString = if (isDesktopMode) {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            } else {
                "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            }
            studioWebView?.reload()
            Toast.makeText(this, if (isDesktopMode) "Desktop View ON" else "Mobile View ON", Toast.LENGTH_SHORT).show()
        })
        toolStrip.addView(navBtn("📌 Save") {
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
            settings.javaScriptCanOpenWindowsAutomatically = true
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
     * TAB 4: Memory Folders, Automation Runs History & Video Training Sessions
     */
    private fun buildTab4MemoryAndVideoTrainer(): View {
        val vault = getActiveVault()
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
            getActiveVault().saveUserProfileRules(profileInput.text.toString())
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
        val vault = getActiveVault()
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

            val fixes = vault.recallFixesForApp(cat).take(6)
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
        val vault = getActiveVault()
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
                else -> accentAmber
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
        val vault = getActiveVault()
        val brain = getActiveBrain()
        val liveChatHistory = runRecord.chatHistory.toMutableList()

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
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132))
            }
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val thumbFrames = mutableListOf<FrameLayout>()

            runRecord.screenshotPaths.forEachIndexed { idx, path ->
                val bmp = vault.loadBitmapFromPath(path) ?: return@forEachIndexed
                val frameWrap = FrameLayout(this).apply {
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    background = roundedBox(Color.TRANSPARENT, dp(6), borderSubtle)
                    layoutParams = LinearLayout.LayoutParams(dp(74), dp(126)).apply {
                        marginEnd = dp(8)
                    }
                }
                val imgView = ImageView(this).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.FIT_XY
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setOnTouchListener { v, ev ->
                        if (ev.action == MotionEvent.ACTION_UP) {
                            thumbFrames.forEach { it.background = roundedBox(Color.TRANSPARENT, dp(6), borderSubtle) }
                            frameWrap.background = roundedBox(Color.TRANSPARENT, dp(6), accentRoyalBlue)
                            val normX = ((ev.x / v.width.coerceAtLeast(1)) * 1000).toInt().coerceIn(0, 1000)
                            val normY = ((ev.y / v.height.coerceAtLeast(1)) * 1000).toInt().coerceIn(0, 1000)
                            val curText = input.text.toString()
                            input.setText("$curText [Screenshot #${idx + 1} Pin XY: ($normX, $normY)] ")
                            input.setSelection(input.text.length)
                            Toast.makeText(this@MainActivity, "📍 Pinned Screenshot #${idx + 1}: ($normX, $normY)", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                }
                frameWrap.addView(imgView)
                thumbFrames.add(frameWrap)
                strip.addView(frameWrap)
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
                text = "$sender: $msgText"
                setTextColor(if (sender == "User") Color.parseColor("#60A5FA") else accentEmerald)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(4), 0, dp(4))
            }
            msgListLayout.addView(tv)
        }

        appendMsg("Gemini 3.8", "नमस्ते भाई! मैंने '${runRecord.title}' के ${runRecord.screenshotPaths.size} स्क्रीनशॉट्स और स्टेप्स लोड कर लिए हैं। बताइए कहाँ सुधार करना है?")
        for (c in liveChatHistory) {
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
            .setView(dialogView as View)
            .setPositiveButton("बंद करें") { d, _ -> d.dismiss() }
            .create()

        send.setOnClickListener {
            val userText = input.text.toString().trim()
            if (userText.isEmpty()) return@setOnClickListener
            input.setText("")
            send.isEnabled = false
            send.text = "⏳..."

            appendMsg("User", userText)
            liveChatHistory.add("User: $userText")
            vault.appendChatToAutomationRun(runRecord.runId, "User", userText)

            val updatedRunRecord = runRecord.copy(chatHistory = liveChatHistory.toList())

            uiScope.launch(Dispatchers.IO) {
                val b64Imgs = vault.loadSavedImagesAsBase64(runRecord.screenshotPaths, maxImages = 6)
                val replyObj = brain.chatAboutAutomationRunWith38Flash(
                    runRecord = updatedRunRecord,
                    userCorrectionMessage = userText,
                    screenshotsBase64 = b64Imgs
                )

                liveChatHistory.add("Gemini 3.8: ${replyObj.replyText}")
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
                    send.isEnabled = true
                    send.text = "Send ➔"
                    appendMsg("Gemini 3.8 (${replyObj.modelUsed})", replyObj.replyText)
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
        val vault = getActiveVault()
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
        val vault = getActiveVault()
        val brain = getActiveBrain()
        val liveChatHistory = session.chatHistory.toMutableList()
        val liveRulesSummary = session.extractedRulesSummary.toMutableList()

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
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132))
            }
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val thumbFrames = mutableListOf<FrameLayout>()

            session.framePaths.forEachIndexed { idx, path ->
                val bmp = vault.loadBitmapFromPath(path) ?: return@forEachIndexed
                val frameWrap = FrameLayout(this).apply {
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    background = roundedBox(Color.TRANSPARENT, dp(6), borderSubtle)
                    layoutParams = LinearLayout.LayoutParams(dp(74), dp(126)).apply {
                        marginEnd = dp(8)
                    }
                }
                val imgView = ImageView(this).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.FIT_XY
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setOnTouchListener { v, ev ->
                        if (ev.action == MotionEvent.ACTION_UP) {
                            thumbFrames.forEach { it.background = roundedBox(Color.TRANSPARENT, dp(6), borderSubtle) }
                            frameWrap.background = roundedBox(Color.TRANSPARENT, dp(6), accentEmerald)
                            val normX = ((ev.x / v.width.coerceAtLeast(1)) * 1000).toInt().coerceIn(0, 1000)
                            val normY = ((ev.y / v.height.coerceAtLeast(1)) * 1000).toInt().coerceIn(0, 1000)
                            val curText = input.text.toString()
                            input.setText("$curText [Frame #${idx + 1} Pin XY: ($normX, $normY)] ")
                            input.setSelection(input.text.length)
                            Toast.makeText(this@MainActivity, "📍 Pinned Frame #${idx + 1}: ($normX, $normY)", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                }
                frameWrap.addView(imgView)
                thumbFrames.add(frameWrap)
                strip.addView(frameWrap)
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
                text = "$sender: $msgText"
                setTextColor(if (sender == "User") Color.parseColor("#60A5FA") else accentEmerald)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(4), 0, dp(4))
            }
            msgListLayout.addView(tv)
        }

        appendMsg("Gemini 3.8", "नमस्ते भाई! मैंने इस वीडियो के ${session.framePaths.size} फ्रेम्स देख लिए हैं। बताइए क्या सुधार करना है?")
        for (c in liveChatHistory) {
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
            .setView(dialogView as View)
            .setPositiveButton("बंद करें") { d, _ -> d.dismiss() }
            .create()

        send.setOnClickListener {
            val userText = input.text.toString().trim()
            if (userText.isEmpty()) return@setOnClickListener
            input.setText("")
            send.isEnabled = false
            send.text = "⏳..."

            appendMsg("User", userText)
            liveChatHistory.add("User: $userText")
            vault.appendChatToVideoSession(session.sessionId, "User", userText)

            val updatedSession = session.copy(
                chatHistory = liveChatHistory.toList(),
                extractedRulesSummary = liveRulesSummary.toList()
            )

            uiScope.launch(Dispatchers.IO) {
                val b64Frames = vault.loadSavedImagesAsBase64(session.framePaths, maxImages = 10)
                val replyObj = brain.chatAboutTrainingVideoWith38Flash(
                    videoSession = updatedSession,
                    userMessage = userText,
                    videoFramesBase64 = b64Frames
                )

                liveChatHistory.add("Gemini 3.8: ${replyObj.replyText}")
                liveRulesSummary.add(replyObj.extractedSolutionRule)
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
                    send.isEnabled = true
                    send.text = "Send ➔"
                    appendMsg("Gemini 3.8 (${replyObj.modelUsed})", replyObj.replyText)
                    chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
                    refreshMemoryEverything()
                }
            }
        }

        dialog.show()
    }

    /**
     * TAB 5: Engine Settings, Floating Chanchal Bot Stealth/Transparency Controls & Dedicated LIVE LOGS
     */
    private fun buildTab5EngineAndLogs(): View {
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        engineScrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        val stealthCard = createSectionCard("👻 Floating Chanchal Bot: Stealth Mode & Corner Transparency")

        var isStealth = prefs.getBoolean("hud_stealth_mode", false)
        val stealthToggleBtn = createPrimaryButton(
            if (isStealth) "👻 Stealth Mode (गायब मोड): ON (बॉट पूरी तरह अदृश्य रहेगा, पर बैकग्राउंड में सारे काम चलते रहेंगे)"
            else "👀 Stealth Mode (गायब मोड): OFF (चंचल बॉट कोने में हल्का दिखेगा)",
            if (isStealth) accentPurple else Color.parseColor("#334155")
        ) { btn ->
            isStealth = !isStealth
            prefs.edit().putBoolean("hud_stealth_mode", isStealth).apply()
            btn.text = if (isStealth) "👻 Stealth Mode (गायब मोड): ON (बॉट पूरी तरह अदृश्य रहेगा, पर बैकग्राउंड में सारे काम चलते रहेंगे)"
            else "👀 Stealth Mode (गायब मोड): OFF (चंचल बॉट कोने में हल्का दिखेगा)"
            btn.background = roundedBox(if (isStealth) accentPurple else Color.parseColor("#334155"), dp(12))
            AutoTouchService.instance?.hud?.applyUserTransparencyAndStealthSettings()
        }

        var greetingsOn = prefs.getBoolean("hud_greetings_enabled", true)
        val greetingsToggleBtn = createPrimaryButton(
            if (greetingsOn) "☀️ Good Morning / Good Night / All the Best अपडेट्स: ON"
            else "🔕 Good Morning / Good Night अपडेट्स: OFF",
            if (greetingsOn) accentEmerald else Color.parseColor("#475569")
        ) { btn ->
            greetingsOn = !greetingsOn
            prefs.edit().putBoolean("hud_greetings_enabled", greetingsOn).apply()
            btn.text = if (greetingsOn) "☀️ Good Morning / Good Night / All the Best अपडेट्स: ON"
            else "🔕 Good Morning / Good Night अपडेट्स: OFF"
            btn.background = roundedBox(if (greetingsOn) accentEmerald else Color.parseColor("#475569"), dp(12))
        }

        val currentAlphaPct = ((prefs.getFloat("hud_idle_alpha", 0.35f)) * 100).toInt().coerceIn(5, 100)
        val alphaLabel = TextView(this).apply {
            text = "🎚️ कोने में छुपने पर बॉट की पारदर्शिता (Edge Transparency): $currentAlphaPct% (जितना कम रखेंगे उतना हल्का दिखेगा, खींचने पर 100% बाहर आएगा)"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(0, dp(10), 0, dp(6))
        }

        val alphaSeekBar = SeekBar(this).apply {
            max = 95
            progress = (currentAlphaPct - 5).coerceAtLeast(0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val pct = (progress + 5).coerceIn(5, 100)
                    alphaLabel.text = "🎚️ कोने में छुपने पर बॉट की पारदर्शिता (Edge Transparency): $pct%"
                    prefs.edit().putFloat("hud_idle_alpha", pct / 100f).apply()
                    AutoTouchService.instance?.hud?.applyUserTransparencyAndStealthSettings()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        val testEdgePeekBtn = createPrimaryButton("✨ अभी कोने से 'Good Morning / All the Best' झलक टेस्ट करें", accentRoyalBlue) {
            val svc = AutoTouchService.instance
            if (svc != null) {
                svc.hud.showHud()
                svc.hud.triggerFriendlyGreeting("☀️ Good Morning भाई! Have a good luck day! मैं कोने में तैयार हूँ!")
            } else {
                Toast.makeText(this, "पहले Accessibility Service ऑन करें!", Toast.LENGTH_SHORT).show()
            }
        }

        stealthCard.addView(stealthToggleBtn)
        stealthCard.addView(spacer(dp(8)))
        stealthCard.addView(greetingsToggleBtn)
        stealthCard.addView(alphaLabel)
        stealthCard.addView(alphaSeekBar)
        stealthCard.addView(spacer(dp(10)))
        stealthCard.addView(testEdgePeekBtn)

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

        logControlsRow.addView(logActionBtn("🔄 Refresh", accentRoyalBlue) { refreshEngineTabDetails() })
        logControlsRow.addView(logActionBtn("📋 Copy Logs", Color.parseColor("#334155")) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("auradroid_logs", liveLogsTextView.text))
            Toast.makeText(this, "All Logs Copied to Clipboard!", Toast.LENGTH_SHORT).show()
        })
        logControlsRow.addView(logActionBtn("🗑️ Clear", Color.parseColor("#7F1D1D")) {
            synchronized(AutoTouchService.liveLogs) { AutoTouchService.liveLogs.clear() }
            getActiveVault().clearPersistentLogs()
            refreshEngineTabDetails()
        })

        liveLogsTextView = TextView(this).apply {
            text = "Loading logs..."
            setTextColor(accentEmerald)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(bgMatteDark, dp(10), borderSubtle)
            minLines = 8
        }

        logsCard.addView(logControlsRow)
        logsCard.addView(liveLogsTextView)

        val apiCard = createSectionCard("🔑 Gemini API & 3.5–3.8 Free-Tier Model Ladder")
        val keyInput = createCleanInput(
            prefs.getString("gemini_api_key", "") ?: "",
            "Paste your Google AI Studio Gemini API Key"
        )
        val saveKeyBtn = createPrimaryButton("💾 Save API Key & Activate 3.5–3.8 Ladder", accentRoyalBlue) {
            val k = keyInput.text.toString().trim()
            prefs.edit().putString("gemini_api_key", k).apply()
            localBrain.updateApiKey(k)
            AutoTouchService.instance?.geminiBrain?.updateApiKey(k)
            Toast.makeText(this, "API Key Updated!", Toast.LENGTH_SHORT).show()
            refreshEngineTabDetails()
        }

        ladderInfoTextView = TextView(this).apply {
            text = getActiveBrain().getActiveModelListStatus()
            setTextColor(textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(10), 0, 0)
        }

        apiCard.addView(keyInput)
        apiCard.addView(spacer(dp(10)))
        apiCard.addView(saveKeyBtn)
        apiCard.addView(ladderInfoTextView)

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
            refreshEngineTabDetails()
        }

        var teachMode = prefs.getBoolean("passive_teach_mode", true)
        val teachSwitchBtn = createPrimaryButton(
            if (teachMode) "🎓 Before+After Auto Touch Learning: ON" else "⚪ Before+After Auto Touch Learning: OFF",
            if (teachMode) accentRoyalBlue else Color.parseColor("#475569")
        ) { btn ->
            teachMode = !teachMode
            prefs.edit().putBoolean("passive_teach_mode", teachMode).apply()
            btn.text = if (teachMode) "🎓 Before+After Auto Touch Learning: ON" else "⚪ Before+After Auto Touch Learning: OFF"
            refreshEngineTabDetails()
        }

        modeCard.addView(modeSwitchBtn)
        modeCard.addView(spacer(dp(10)))
        modeCard.addView(teachSwitchBtn)

        val bodyCard = createSectionCard("🦾 Calibrated Phone Body & On-Demand Permissions")
        calibratedSpecsTextView = TextView(this).apply {
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        val overlayBtn = createPrimaryButton("🪟 1. Grant Floating Chanchal Bot Overlay Permission", Color.parseColor("#334155")) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                AutoTouchService.instance?.hud?.showHud()
                Toast.makeText(this, "Chanchal Floating Face Active!", Toast.LENGTH_SHORT).show()
            }
        }

        val accessBtn = createPrimaryButton("♿ 2. Enable AuraDroid Accessibility Service", accentRoyalBlue) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val storageBtn = createPrimaryButton("📁 3. Turn ON File Manager Access (On-Demand Only)", Color.parseColor("#0D9488")) {
            if (requestStoragePermissionsOnDemand()) {
                Toast.makeText(this, "File Manager Access Active!", Toast.LENGTH_SHORT).show()
                refreshEngineTabDetails()
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

        bodyCard.addView(calibratedSpecsTextView)
        bodyCard.addView(spacer(dp(12)))
        bodyCard.addView(overlayBtn)
        bodyCard.addView(spacer(dp(8)))
        bodyCard.addView(accessBtn)
        bodyCard.addView(spacer(dp(8)))
        bodyCard.addView(storageBtn)
        bodyCard.addView(spacer(dp(8)))
        bodyCard.addView(batteryBtn)

        container.addView(stealthCard)
        container.addView(logsCard)
        container.addView(apiCard)
        container.addView(modeCard)
        container.addView(bodyCard)
        engineScrollView.addView(container)
        refreshEngineTabDetails()
        return engineScrollView
    }

    private fun refreshEngineTabDetails() {
        refreshLiveLogsBox()
        if (::ladderInfoTextView.isInitialized) {
            ladderInfoTextView.text = getActiveBrain().getActiveModelListStatus()
        }
        if (::calibratedSpecsTextView.isInitialized) {
            val specs = AutoTouchService.instance?.getCalibratedDeviceBodySpecs()
            calibratedSpecsTextView.text = if (specs != null) {
                "• Phone Model: ${specs.modelName}\n" +
                    "• Real Full-Screen Pixels: ${specs.screenWidth} × ${specs.screenHeight} px\n" +
                    "• Top Punch-Hole / Cutout Safe Zone: 0 to ${specs.cutoutSafeTop} px\n" +
                    "• Bottom Nav Bar Height: ${specs.navBarHeight} px\n" +
                    "• Low-Task Engine: Gemini 3.5 Flash-Lite / 3.5 Flash\n" +
                    "• High-Task & Reflection Engine: Gemini 3.8 Flash Active"
            } else {
                "Enable Accessibility Service below to calibrate your screen, notch & punch-hole cutout."
            }
        }
    }

    private fun refreshLiveLogsBox() {
        if (!::liveLogsTextView.isInitialized) return
        val inMemory = synchronized(AutoTouchService.liveLogs) { AutoTouchService.liveLogs.toList() }
        val lines = if (inMemory.isNotEmpty()) {
            inMemory.take(45)
        } else {
            getActiveVault().getPersistentLogs().take(45)
        }
        liveLogsTextView.text = if (lines.isEmpty()) {
            "No logs yet. Start any task or change a setting to see live history here."
        } else {
            lines.joinToString("\n")
        }
    }

    private fun resolveTreeUriToRealPath(treeUri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            when {
                docId.startsWith("raw:") -> docId.removePrefix("raw:")
                docId.equals("downloads", ignoreCase = true) ->
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                docId.contains(":") -> {
                    val split = docId.split(":")
                    val type = split[0]
                    val rel = if (split.size > 1) split[1] else ""
                    if (type.equals("primary", ignoreCase = true)) {
                        if (rel.isEmpty()) Environment.getExternalStorageDirectory().absolutePath
                        else "${Environment.getExternalStorageDirectory().absolutePath}/$rel"
                    } else {
                        "/storage/$type/$rel"
                    }
                }
                else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
            }
        } catch (_: Exception) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
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
                chatInputBox.setSelection(spoken.length)
            }

            REQ_PICK_FOLDER_TREE -> {
                val treeUri = data.data ?: return
                try {
                    contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                val resolvedPath = resolveTreeUriToRealPath(treeUri)
                folderPathEditText.setText(resolvedPath)
                getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
                    .edit().putString("yt_folder_path", resolvedPath).apply()
                Toast.makeText(this, "📂 Selected Folder: $resolvedPath", Toast.LENGTH_SHORT).show()
            }

            REQ_PICK_FLOW_SAVE_FOLDER -> {
                val treeUri = data.data ?: return
                val resolvedPath = resolveTreeUriToRealPath(treeUri)
                if (::flowDownloadFolderEditText.isInitialized) {
                    flowDownloadFolderEditText.setText(resolvedPath)
                }
                getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
                    .edit().putString("custom_download_folder", resolvedPath).apply()
                Toast.makeText(this, "📂 Flow Save Folder: $resolvedPath", Toast.LENGTH_SHORT).show()
            }

            REQ_PICK_FREEWEB_SAVE_FOLDER -> {
                val treeUri = data.data ?: return
                val resolvedPath = resolveTreeUriToRealPath(treeUri)
                if (::freeWebDownloadFolderEditText.isInitialized) {
                    freeWebDownloadFolderEditText.setText(resolvedPath)
                }
                getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
                    .edit().putString("custom_download_folder", resolvedPath).apply()
                Toast.makeText(this, "📂 Free Hub Save Folder: $resolvedPath", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "फ्रेम्स निकालकर Gemini 3.8 Flash से एनालाइज किया जा रहा है...", Toast.LENGTH_LONG).show()
                val brain = getActiveBrain()
                val vault = getActiveVault()

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
                        vault.appendChatToVideoSession(
                            session.sessionId,
                            "Gemini 3.8",
                            "Learned Rule: ${rule.solutionRule}",
                            rule.solutionRule
                        )
                    }

                    withContext(Dispatchers.Main) {
                        refreshMemoryEverything()
                        Toast.makeText(
                            this@MainActivity,
                            "✅ Video Trained (${learnedRulesList.size} Rules) & Saved in Memory/${session.appCategory}!",
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBox(bgMatteDark, dp(10), borderSubtle)
        }
    }

    private fun createPrimaryButton(label: String, color: Int, onClick: (TextView) -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(12), dp(14), dp(12))
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
        logAutoRefreshJob?.cancel()
        uiScope.cancel()
        try {
            studioWebView?.stopLoading()
            studioWebView?.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    /**
     * Cute Full-Body Chanchal Bot View for Home Page (Head, Eyes, Tiny Cute Mouth, Waving Arms & Feet)
     */
    inner class FullBodyChanchalBotView(context: Context) : View(context) {
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            style = Paint.Style.FILL
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8")
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        private val featurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.FILL
        }
        private val mouthStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F472B6")
            alpha = 130
            style = Paint.Style.FILL
        }

        private var animPhase = 0f
        private var waveBoost = 0f
        private var isHappyMood = true
        private var isCuriousMood = false
        private val mouthPath = Path()
        private val rect = RectF()

        private val idleAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 2600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                animPhase = it.animatedValue as Float
                if (waveBoost > 0.02f) waveBoost *= 0.92f else waveBoost = 0f
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (!idleAnimator.isStarted) idleAnimator.start()
        }

        override fun onDetachedFromWindow() {
            idleAnimator.cancel()
            super.onDetachedFromWindow()
        }

        fun triggerPlayfulJumpAndWave() {
            waveBoost = 1f
            invalidate()
        }

        fun setBotMood(isHappy: Boolean, isCurious: Boolean) {
            isHappyMood = isHappy
            isCuriousMood = isCurious
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val bobY = sin(animPhase.toDouble()).toFloat() * dp(3) - (waveBoost * dp(6))
            val glanceX = sin((animPhase * 0.7f).toDouble()).toFloat() * dp(2)

            // Cute Feet
            canvas.drawOval(cx - dp(18), h - dp(14), cx - dp(4), h - dp(4), bodyPaint)
            canvas.drawOval(cx + dp(4), h - dp(14), cx + dp(18), h - dp(4), bodyPaint)

            // Chubby Torso
            rect.set(cx - dp(24), h * 0.42f + bobY, cx + dp(24), h - dp(10) + bobY)
            canvas.drawRoundRect(rect, dp(22).toFloat(), dp(22).toFloat(), bodyPaint)
            canvas.drawRoundRect(rect, dp(22).toFloat(), dp(22).toFloat(), shadowPaint)

            // Waving Arms
            val waveOffset = sin((animPhase * 2f).toDouble()).toFloat() * dp(6) + (waveBoost * dp(12))
            canvas.drawOval(cx - dp(34), h * 0.46f + bobY - waveOffset, cx - dp(20), h * 0.68f + bobY, bodyPaint)
            canvas.drawOval(cx + dp(20), h * 0.46f + bobY + waveOffset, cx + dp(34), h * 0.68f + bobY, bodyPaint)

            // Cute Head
            rect.set(cx - dp(26), dp(8).toFloat() + bobY, cx + dp(26), h * 0.46f + bobY)
            canvas.drawRoundRect(rect, dp(24).toFloat(), dp(24).toFloat(), bodyPaint)
            canvas.drawRoundRect(rect, dp(24).toFloat(), dp(24).toFloat(), shadowPaint)

            // Rosy Cheeks
            val eyeY = h * 0.26f + bobY
            canvas.drawCircle(cx - dp(15) + glanceX, eyeY + dp(5), dp(4).toFloat(), blushPaint)
            canvas.drawCircle(cx + dp(15) + glanceX, eyeY + dp(5), dp(4).toFloat(), blushPaint)

            // Expressive Chanchal Eyes
            val leftEyeX = cx - dp(10) + glanceX
            val rightEyeX = cx + dp(10) + glanceX
            val eyeR = if (isCuriousMood) dp(5).toFloat() else dp(4).toFloat()
            canvas.drawLine(leftEyeX, eyeY, rightEyeX, eyeY, mouthStrokePaint)
            canvas.drawCircle(leftEyeX, eyeY, eyeR, featurePaint)
            canvas.drawCircle(rightEyeX, eyeY, eyeR, featurePaint)

            // Tiny Cute Expressive Mouth
            val mouthY = eyeY + dp(8)
            if (isCuriousMood) {
                canvas.drawCircle(cx + glanceX, mouthY, dp(3).toFloat(), mouthStrokePaint)
            } else {
                mouthPath.reset()
                mouthPath.moveTo(cx - dp(4) + glanceX, mouthY)
                mouthPath.quadTo(
                    cx + glanceX,
                    mouthY + if (isHappyMood) dp(4).toFloat() else -dp(2).toFloat(),
                    cx + dp(4) + glanceX,
                    mouthY
                )
                canvas.drawPath(mouthPath, mouthStrokePaint)
            }
        }
    }
}
