package com.auradroid.os

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import android.webkit.WebView
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale
import kotlin.math.sin

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class MainActivity : AppCompatActivity() {

    internal val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var logAutoRefreshJob: Job? = null

    internal val bgMatteDark = Color.parseColor("#121316")
    internal val cardSlate = Color.parseColor("#1C1F26")
    internal val borderSubtle = Color.parseColor("#2E3440")
    internal val textPureWhite = Color.parseColor("#F8FAFC")
    internal val textMuted = Color.parseColor("#94A3B8")
    internal val accentRoyalBlue = Color.parseColor("#3B82F6")
    internal val accentEmerald = Color.parseColor("#10B981")
    internal val accentPurple = Color.parseColor("#8B5CF6")
    internal val accentAmber = Color.parseColor("#F59E0B")

    private var currentTabIndex = 0
    private lateinit var serviceBadge: TextView
    private lateinit var tabContainers: Array<View>
    private lateinit var bottomNavButtons: Array<TextView>

    private lateinit var homeBotView: FullBodyChanchalBotView
    private lateinit var homeBotStatusTitle: TextView
    private lateinit var homeBotReportSubtitle: TextView
    private lateinit var chatMessagesLayout: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var chatInputBox: EditText
    private lateinit var planSendBtn: TextView
    private val chatHistoryBuilder = StringBuilder()

    internal lateinit var folderPathEditText: EditText
    internal lateinit var flowDownloadFolderEditText: EditText
    internal lateinit var freeWebDownloadFolderEditText: EditText

    internal var studioWebView: WebView? = null
    internal var isDesktopMode = false
    internal val downloadedFilesLog = mutableListOf<String>()

    internal lateinit var memoryCategoriesLayout: LinearLayout
    internal lateinit var automationRunsListLayout: LinearLayout
    internal lateinit var videoSessionsListLayout: LinearLayout

    internal var selectedTrainingCategory = "Instagram"
    internal var selectedTrainingNote = "इस वीडियो के हर फ्रेम को देखो, जहाँ गलती हुई है उसे सुधारो और सही बटन के कोऑर्डिनेट सेव कर लो"

    internal lateinit var liveLogsTextView: TextView
    internal lateinit var apiTelemetryTextView: TextView
    internal lateinit var ladderInfoTextView: TextView
    internal lateinit var calibratedSpecsTextView: TextView
    internal lateinit var engineScrollView: ScrollView

    internal lateinit var localVault: MemoryVault
    internal lateinit var localBrain: GeminiBrain

    companion object {
        internal const val REQ_VOICE_INPUT = 201
        internal const val REQ_PICK_TRAINING_VIDEO = 202
        internal const val REQ_PICK_FOLDER_TREE = 203
        internal const val REQ_PICK_SINGLE_VIDEO_FOR_YT = 204
        internal const val REQ_PICK_FLOW_SAVE_FOLDER = 205
        internal const val REQ_PICK_FREEWEB_SAVE_FOLDER = 206
        internal const val REQ_CHAT_PICK_IMAGE = 207
        internal const val REQ_CHAT_PICK_VIDEO = 208
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgMatteDark
        window.navigationBarColor = Color.parseColor("#181B20")

        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        localVault = MemoryVault(this)
        localBrain = GeminiBrain(prefs.getString("gemini_api_key", "") ?: "").apply {
            detailedApiLoggingEnabled = prefs.getBoolean("detailed_api_logging", true)
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
            setPadding(dp(14), dp(12), dp(14), dp(10))
            setBackgroundColor(Color.parseColor("#181B20"))
        }

        val titleView = TextView(this).apply {
            text = "AuraDroid OS"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val liveBgHeaderBtn = TextView(this).apply {
            text = "🌐 Live BG"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBox(accentRoyalBlue, dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener {
                val svc = AutoTouchService.instance
                if (svc != null) {
                    val savedFlowUrl = prefs.getString("saved_flow_url", "https://labs.google/fx/tools/flow")
                        ?: "https://labs.google/fx/tools/flow"
                    val curUrl = svc.hud.getOrCreateBackgroundWebView()?.url
                    val urlToOpen = if (!curUrl.isNullOrBlank() && curUrl != "about:blank") curUrl else savedFlowUrl
                    svc.hud.openBackgroundDotUrl(urlToOpen, desktopMode = true, showCard = true)
                    Toast.makeText(this@MainActivity, "🤖 Bot Emoji पर टैप करने से यह छुप (Hide) या खुल जाएगा!", Toast.LENGTH_SHORT).show()
                } else {
                    switchTab(2)
                }
            }
        }

        val quickLogsBtn = TextView(this).apply {
            text = "📊 API & Logs"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBox(Color.parseColor("#262B36"), dp(14), borderSubtle)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener {
                switchTab(4)
                refreshEngineTabDetails()
                engineScrollView.post { engineScrollView.fullScroll(View.FOCUS_UP) }
            }
        }

        serviceBadge = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBox(Color.parseColor("#222630"), dp(16), borderSubtle)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        refreshServiceBadge()

        header.addView(titleView)
        header.addView(liveBgHeaderBtn)
        header.addView(quickLogsBtn)
        header.addView(serviceBadge)

        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        tabContainers = arrayOf(
            buildTab1HomeAndChatPlanner(),
            buildTab2AutomationsAndScheduler(),
            buildTab3FullControlBrowser(),
            buildTab4MemoryAndVideoTrainer(),
            buildTab5EngineAndLogs()
        )
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

    internal fun darkDialogBuilder(): AlertDialog.Builder {
        return AlertDialog.Builder(this)
    }

    internal fun getActiveBrain(): GeminiBrain {
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("gemini_api_key", "") ?: ""
        val detailedLogs = prefs.getBoolean("detailed_api_logging", true)
        localBrain.updateApiKey(savedKey)
        localBrain.detailedApiLoggingEnabled = detailedLogs
        return AutoTouchService.instance?.geminiBrain?.also {
            it.updateApiKey(savedKey)
            it.detailedApiLoggingEnabled = detailedLogs
        } ?: localBrain
    }

    internal fun getActiveVault(): MemoryVault = AutoTouchService.instance?.memoryVault ?: localVault

    internal fun isValidBrainReply(replyObj: MemoryChatReply): Boolean {
        if (replyObj.modelUsed.contains("All models busy", ignoreCase = true) ||
            replyObj.modelUsed.contains("No API Key", ignoreCase = true)
        ) return false
        val sol = replyObj.extractedSolutionRule
        if (sol.isBlank() || sol.contains("@213") || sol.contains("Chat not selected", ignoreCase = true)) return false
        return true
    }

    internal fun isPoisonedFixRecord(fix: LearnedFixRecord): Boolean {
        val r = fix.solutionRule
        val s = fix.sourceType
        return r.contains("@213") ||
            r.contains("Chat not selected", ignoreCase = true) ||
            s.contains("All models busy", ignoreCase = true) ||
            r.contains("All models busy", ignoreCase = true)
    }

    internal fun purgePoisonedRulesFromCategory(category: String): Int {
        val vault = getActiveVault()
        val allFixes = vault.recallFixesForApp(category)
        val goodFixes = allFixes.filter { !isPoisonedFixRecord(it) }
        val removedCount = allFixes.size - goodFixes.size
        if (removedCount > 0) {
            vault.clearCategoryMemory(category)
            for (g in goodFixes.reversed()) {
                vault.saveStuckOrHumanFix(
                    category = category,
                    screenSignature = g.screenSignature,
                    problemDescription = g.problemDescription,
                    solutionRule = g.solutionRule,
                    tapXRatio = g.tapXRatio,
                    tapYRatio = g.tapYRatio,
                    sourceType = g.sourceType
                )
            }
        }
        return removedCount
    }

    private fun refreshServiceBadge() {
        if (!::serviceBadge.isInitialized) return
        val active = AutoTouchService.instance != null
        serviceBadge.text = if (active) "● 24/7 Active" else "○ Enable Body"
        serviceBadge.setTextColor(if (active) accentEmerald else accentAmber)
    }

    internal fun requestStoragePermissionsOnDemand(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
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

    internal fun switchTab(index: Int) {
        currentTabIndex = index
        tabContainers.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
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
        when (index) {
            0 -> refreshHomeDailyReportBanner()
            3 -> refreshMemoryEverything()
            4 -> {
                refreshEngineTabDetails()
                logAutoRefreshJob = uiScope.launch {
                    while (isActive && currentTabIndex == 4) {
                        delay(2000L)
                        refreshEngineTabDetails()
                    }
                }
            }
        }
    }

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
            layoutParams = LinearLayout.LayoutParams(dp(86), dp(106)).apply { marginEnd = dp(10) }
            setOnClickListener {
                triggerPlayfulJumpAndWave()
                val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val wish = when {
                    hr in 5..11 -> "☀️ Good Morning भाई! Have a good luck day!"
                    hr in 12..16 -> "🚀 All the best भाई! मैं आपके सारे टास्क देख रहा हूँ!"
                    hr in 17..21 -> "✨ Good Evening सर! शाम के ऑटोमेशन के लिए मैं तैयार हूँ!"
                    else -> "🌙 Late night हो गई भाई! आप सो जाओ, बैकग्राउंड में मैं काम संभाल लूँगा!"
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
        }

        homeBotReportSubtitle = TextView(this).apply {
            text = "सर, स्क्रीन के Floating Emoji पर टैप करने से बैकग्राउंड ब्राउज़र खुलेगा और दोबारा टैप पर छुप जाएगा!"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(3), 0, dp(6))
        }

        val pillsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val actionPillsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val dailyReportBtn = TextView(this).apply {
            text = "📊 Report"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = roundedBox(accentEmerald, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { showFriendlyDailyReportDialog() }
        }

        val diagnoseBtn = TextView(this).apply {
            text = "🛠️ 1-2-3 Fix"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = roundedBox(accentPurple, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { openLastRunScreenshotsPickerForChat() }
        }

        val tasksQuickBtn = TextView(this).apply {
            text = "⏰ Flow & Upload Time"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = roundedBox(accentRoyalBlue, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { switchTab(1) }
        }

        val apiTestPillBtn = TextView(this).apply {
            text = "⏱️ API Test"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = roundedBox(Color.parseColor("#0D9488"), dp(8))
            setOnClickListener { switchTab(4) }
        }

        actionPillsRow.addView(dailyReportBtn)
        actionPillsRow.addView(diagnoseBtn)
        actionPillsRow.addView(tasksQuickBtn)
        actionPillsRow.addView(apiTestPillBtn)
        pillsScroll.addView(actionPillsRow)

        rightStatusCol.addView(homeBotStatusTitle)
        rightStatusCol.addView(homeBotReportSubtitle)
        rightStatusCol.addView(pillsScroll)
        companionCard.addView(homeBotView)
        companionCard.addView(rightStatusCol)

        chatScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        chatMessagesLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatScrollView.addView(chatMessagesLayout)

        addChatBubble(
            "AuraDroid Chanchal Bot",
            "नमस्ते भाई! आपके सभी नए कंट्रोल्स तैयार हैं:\n" +
                "• **Floating Emoji Tap:** स्क्रीन पर दिख रहे इमोजी पर टैप करेंगे तो बैकग्राउंड में चल रहा प्रोजेक्ट (Flow/YouTube) खुल जाएगा, और दोबारा टैप करेंगे तो Hide हो जाएगा!\n" +
                "• **🔄 Tasks टैब:** वहाँ से Flow वीडियो बनने का टाइम (Background या Real Chrome) और फोल्डर से अपलोड होने का टाइम (Background या Real YouTube/Insta + Description) सेट करें।\n" +
                "• **⚙️ Engine टैब:** हर एक Gemini API कॉल किस काम में हुआ और कितने सेकंड लगे — उसका पूरा लाइव टेस्ट मीटर देखें!",
            isUser = false
        )

        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = roundedBox(cardSlate, dp(16), borderSubtle)
        }

        val attachMediaBtn = TextView(this).apply {
            text = "📎"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(dp(8), dp(6), dp(6), dp(6))
            setOnClickListener { showChatMediaAttachMenu() }
        }

        val micBtn = TextView(this).apply {
            text = "🎙️"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setPadding(dp(6), dp(6), dp(8), dp(6))
            setOnClickListener { startVoiceInput() }
        }

        chatInputBox = EditText(this).apply {
            hint = "काम बोलें या 📎 से फोटो/वीडियो भेजें..."
            setHintTextColor(textMuted)
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            background = null
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val directRunBtn = TextView(this).apply {
            text = "⚡"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(11), dp(10), dp(11), dp(10))
            background = roundedBox(accentEmerald, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(5) }
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
                }
            }
        }

        planSendBtn = TextView(this).apply {
            text = "Plan ➔"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(accentRoyalBlue, dp(12))
            setOnClickListener {
                val msg = chatInputBox.text.toString().trim()
                if (msg.isNotEmpty()) {
                    chatInputBox.setText("")
                    handleUserChatMessage(msg, chatScrollView)
                }
            }
        }

        inputBar.addView(attachMediaBtn)
        inputBar.addView(micBtn)
        inputBar.addView(chatInputBox)
        inputBar.addView(directRunBtn)
        inputBar.addView(planSendBtn)

        container.addView(companionCard)
        container.addView(chatScrollView)
        container.addView(inputBar)
        refreshHomeDailyReportBanner()
        return container
    }

    private fun showChatMediaAttachMenu() {
        val options: Array<CharSequence> = arrayOf(
            "🖼️ गैलरी से स्क्रीनशॉट/फोटो भेजें (1-2-3 पॉइंट लगाएं)",
            "📸 पिछले टास्क के स्क्रीनशॉट पर 1-2-3 पॉइंट लगाएं",
            "🎥 चैट में वीडियो (Screen Recording) भेजें & सुधारें",
            "🧹 किसी ऐप के खराब नियम (@213 / Chat not selected) साफ करें",
            "🗑️ किसी ऐप का पूरा पुराना नियम हटाकर नया बनाएं"
        )
        darkDialogBuilder()
            .setTitle("📎 चैट टूल्स & 1-2-3 नियम एडिटर")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, REQ_CHAT_PICK_IMAGE)
                    1 -> openLastRunScreenshotsPickerForChat()
                    2 -> startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "video/*" }, REQ_CHAT_PICK_VIDEO)
                    3 -> {
                        val vault = getActiveVault()
                        val catNames: List<String> = vault.getAllCategoriesSummary().map { it.first }
                        if (catNames.isEmpty()) {
                            Toast.makeText(this, "अभी कोई पुराना नियम नहीं है!", Toast.LENGTH_SHORT).show()
                        } else {
                            var totalPurged = 0
                            for (c in catNames) {
                                totalPurged += purgePoisonedRulesFromCategory(c)
                            }
                            addChatBubble("🧹 Memory Cleaner", "✅ सभी ऐप्स से **$totalPurged खराब/अधूरे नियम** साफ कर दिए गए हैं!", false)
                        }
                    }
                    4 -> {
                        val vault = getActiveVault()
                        val catNames: List<String> = vault.getAllCategoriesSummary().map { it.first }
                        if (catNames.isEmpty()) {
                            Toast.makeText(this, "अभी कोई पुराना नियम नहीं है!", Toast.LENGTH_SHORT).show()
                        } else {
                            val catItems: Array<CharSequence> = catNames.map { it as CharSequence }.toTypedArray()
                            darkDialogBuilder()
                                .setTitle("किस ऐप के पुराने नियम हटाकर नए बनाने हैं?")
                                .setItems(catItems) { _, idx ->
                                    val chosenCat = catNames[idx]
                                    vault.clearCategoryMemory(chosenCat)
                                    addChatBubble("⚙️ Live Rule Engine", "✅ `$chosenCat` के पुराने नियम हटा दिए गए हैं! अब नए 1-2-3 पॉइंट दें।", false)
                                }
                                .show()
                        }
                    }
                }
            }
            .show()
    }

    internal fun openLastRunScreenshotsPickerForChat() {
        val vault = getActiveVault()
        val runs = vault.getAllAutomationRuns().filter { it.screenshotPaths.isNotEmpty() }
        if (runs.isEmpty()) {
            Toast.makeText(this, "अभी पिछले रन का कोई स्क्रीनशॉट नहीं है! गैलरी से फोटो चुनें।", Toast.LENGTH_LONG).show()
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, REQ_CHAT_PICK_IMAGE)
            return
        }

        val run = runs.first()
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(bgMatteDark)
        }
        dialogLayout.addView(TextView(this).apply {
            text = "📌 टास्क: ${run.title}\n👆 जिस स्क्रीनशॉट में समस्या थी उस पर टैप करें और **1, 2, 3 पॉइंट** लगाएं:"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(0, 0, 0, dp(10))
        })

        val hScroll = HorizontalScrollView(this)
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val pickerDialog = darkDialogBuilder()
            .setTitle("📸 स्क्रीनशॉट चुनें (${run.screenshotPaths.size})")
            .setView(dialogLayout)
            .setNegativeButton("बंद करें", null)
            .create()

        run.screenshotPaths.forEachIndexed { idx, path ->
            val bmp = vault.loadBitmapFromPath(path) ?: return@forEachIndexed
            val stepLabel = run.stepsSummary.getOrNull(idx) ?: "Step #${idx + 1}"
            val img = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedBox(cardSlate, dp(8), accentRoyalBlue)
                setPadding(dp(3), dp(3), dp(3), dp(3))
                layoutParams = LinearLayout.LayoutParams(dp(95), dp(165)).apply { marginEnd = dp(10) }
                setOnClickListener {
                    pickerDialog.dismiss()
                    openMultiPointEditableScreenshotDialog(bmp, run.appCategory, "Step #${idx + 1}: $stepLabel")
                }
            }
            strip.addView(img)
        }
        hScroll.addView(strip)
        dialogLayout.addView(hScroll)
        pickerDialog.show()
    }

    internal fun openMultiPointEditableScreenshotDialog(
        bmp: Bitmap,
        defaultAppCategory: String = "Instagram",
        initialTitle: String = "1-2-3 Multi-Point Rule Editor"
    ) {
        val pointsNormList = mutableListOf<Pair<Int, Int>>()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgMatteDark)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val pointsStatusTv = TextView(this).apply {
            text = "👆 फोटो पर उंगली से टैप करके **1, 2, 3... पॉइंट्स** बनाएं:"
            setTextColor(accentAmber)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        }

        val multiPointCanvasView = object : View(this) {
            private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            private val dstRect = RectF()
            private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#38BDF8")
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
                pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
            }
            private val circleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            private val circleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
            }
            private val numTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = dp(13).toFloat()
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                dstRect.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawBitmap(bmp, null, dstRect, bmpPaint)

                for (i in 0 until pointsNormList.size - 1) {
                    val p1 = pointsNormList[i]
                    val p2 = pointsNormList[i + 1]
                    canvas.drawLine(
                        (p1.first / 1000f) * width,
                        (p1.second / 1000f) * height,
                        (p2.first / 1000f) * width,
                        (p2.second / 1000f) * height,
                        linePaint
                    )
                }

                pointsNormList.forEachIndexed { idx, pt ->
                    val px = (pt.first / 1000f) * width
                    val py = (pt.second / 1000f) * height
                    circleFillPaint.color = when (idx % 3) {
                        0 -> Color.parseColor("#EF4444")
                        1 -> Color.parseColor("#10B981")
                        else -> Color.parseColor("#3B82F6")
                    }
                    canvas.drawCircle(px, py, dp(14).toFloat(), circleFillPaint)
                    canvas.drawCircle(px, py, dp(14).toFloat(), circleStrokePaint)
                    val textY = py - ((numTextPaint.descent() + numTextPaint.ascent()) / 2f)
                    canvas.drawText("${idx + 1}", px, textY, numTextPaint)
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(330))
            setOnTouchListener { v, ev ->
                if (ev.action == MotionEvent.ACTION_UP) {
                    val nx = ((ev.x / v.width.coerceAtLeast(1)) * 1000).toInt().coerceIn(0, 1000)
                    val ny = ((ev.y / v.height.coerceAtLeast(1)) * 1000).toInt().coerceIn(0, 1000)
                    pointsNormList.add(Pair(nx, ny))
                    val summary = pointsNormList.mapIndexed { i, p -> "[${i + 1}: (${p.first},${p.second})]" }.joinToString(" ➔ ")
                    pointsStatusTv.text = "🎯 पॉइंट्स: $summary"
                    v.invalidate()
                }
                true
            }
        }

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val appNameInput = createCleanInput(defaultAppCategory, "App (जैसे Instagram)").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        }

        val undoBtn = createPrimaryButton("↩️ Undo", Color.parseColor("#475569")) {
            if (pointsNormList.isNotEmpty()) {
                pointsNormList.removeAt(pointsNormList.lastIndex)
                val summary = pointsNormList.mapIndexed { i, p -> "[${i + 1}: (${p.first},${p.second})]" }.joinToString(" ➔ ")
                pointsStatusTv.text = if (summary.isEmpty()) "👆 फोटो पर टैप करके 1, 2, 3 पॉइंट बनाएं:" else "🎯 पॉइंट्स: $summary"
                multiPointCanvasView.invalidate()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }

        val clearPtsBtn = createPrimaryButton("🗑️ Clear", Color.parseColor("#7F1D1D")) {
            pointsNormList.clear()
            pointsStatusTv.text = "👆 फोटो पर टैप करके 1, 2, 3 पॉइंट बनाएं:"
            multiPointCanvasView.invalidate()
        }

        controlsRow.addView(appNameInput)
        controlsRow.addView(undoBtn)
        controlsRow.addView(clearPtsBtn)

        val instructionInput = createCleanInput(
            chatInputBox.text.toString(),
            "यहाँ लिखें: जैसे '1 पर टाइप करना है और 2 पर टैप करके सेंड करना है'..."
        ).apply { minLines = 2 }

        var replaceOldRules = false
        val replaceToggleBtn = createPrimaryButton("🔄 मोड: पुराने नियमों के साथ जोड़ें (Add to Rules)", Color.parseColor("#262B36")) { btn ->
            replaceOldRules = !replaceOldRules
            btn.text = if (replaceOldRules) "⚡ मोड: पुराना गलत नियम बदलें/हटाएं (Replace Old Rules)"
            else "🔄 मोड: पुराने नियमों के साथ जोड़ें (Add to Rules)"
            btn.background = roundedBox(if (replaceOldRules) accentPurple else Color.parseColor("#262B36"), dp(10))
        }

        root.addView(pointsStatusTv)
        root.addView(multiPointCanvasView)
        root.addView(controlsRow)
        root.addView(replaceToggleBtn)
        root.addView(spacer(dp(6)))
        root.addView(instructionInput)

        darkDialogBuilder()
            .setTitle("🖼️ $initialTitle")
            .setView(root)
            .setPositiveButton("⚡ पक्का कानून (Live Rule) बनाएं") { d, _ ->
                val appCat = appNameInput.text.toString().trim().ifEmpty { "Instagram" }
                val userInstruction = instructionInput.text.toString().trim().ifEmpty { "Follow numbered points in order" }
                chatInputBox.setText("")
                d.dismiss()
                compileMultiPointScreenshotIntoLiveRules(bmp, appCat, pointsNormList.toList(), userInstruction, replaceOldRules)
            }
            .setNegativeButton("रद्द करें", null)
            .show()
    }

    private fun compileMultiPointScreenshotIntoLiveRules(
        bmp: Bitmap,
        appCategory: String,
        points: List<Pair<Int, Int>>,
        userInstruction: String,
        replaceOld: Boolean
    ) {
        val vault = getActiveVault()
        val brain = getActiveBrain()

        val ptsText = if (points.isEmpty()) "Auto-detect from screenshot"
        else points.mapIndexed { idx, p -> "Point ${idx + 1} Pin XY: (${p.first}, ${p.second})" }.joinToString(", ")

        addChatBubble(
            "You (📸 Screenshot + ${points.size} Points)",
            "App: $appCategory\n🎯 Points: $ptsText\n💬 निर्देश: $userInstruction",
            isUser = true
        )

        uiScope.launch(Dispatchers.IO) {
            if (replaceOld) {
                vault.clearCategoryMemory(appCategory)
            } else {
                purgePoisonedRulesFromCategory(appCategory)
            }

            val b64 = brain.bitmapToHdWebpBase64(bmp, maxHeight = 1024)
            val syntheticRun = AutomationRunRecord(
                runId = "chat_img_${System.currentTimeMillis()}",
                title = "Chat Screenshot Rule ($appCategory)",
                appCategory = appCategory,
                status = "USER_EDITED",
                timestamp = "Just Now",
                stepsSummary = points.mapIndexed { i, p -> "Point ${i + 1} at XY(${p.first}, ${p.second})" },
                screenshotPaths = emptyList(),
                chatHistory = emptyList()
            )

            val combinedPrompt = "User marked ${points.size} numbered points on this screenshot: [$ptsText]. User instruction: '$userInstruction'. Create a mandatory reusable skill rule following these exact numbered points!"
            val replyObj = brain.chatAboutAutomationRunWith38Flash(syntheticRun, combinedPrompt, listOf(b64))

            if (points.isNotEmpty()) {
                points.forEachIndexed { idx, pt ->
                    val ptNum = idx + 1
                    vault.saveStuckOrHumanFix(
                        category = appCategory,
                        screenSignature = "live_pt_${System.currentTimeMillis()}_$ptNum",
                        problemDescription = "[UNIVERSAL_SKILL] Point #$ptNum on ${replyObj.extractedProblem}",
                        solutionRule = "MANDATORY LIVE RULE (Step Point #$ptNum): $userInstruction -> Execute at Point #$ptNum CLICK_XY(${pt.first}, ${pt.second}) [All Points: $ptsText]",
                        tapXRatio = (pt.first / 1000f).coerceIn(0.01f, 0.99f),
                        tapYRatio = (pt.second / 1000f).coerceIn(0.01f, 0.99f),
                        sourceType = "LIVE_CODE_RULE_PT#$ptNum"
                    )
                }
                withContext(Dispatchers.Main) {
                    addChatBubble(
                        "⚖️ Live Code Rule Compiler (${replyObj.modelUsed})",
                        "${replyObj.replyText}\n\n✅ **${points.size} पक्के कोडेड कानून (`LIVE_CODE_RULE`) `$appCategory` में लागू हो गए हैं!**\n• Points: `$ptsText`",
                        isUser = false
                    )
                    chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
                }
            } else if (isValidBrainReply(replyObj)) {
                vault.saveStuckOrHumanFix(
                    category = appCategory,
                    screenSignature = "live_rule_${System.currentTimeMillis()}",
                    problemDescription = replyObj.extractedProblem,
                    solutionRule = "MANDATORY LIVE RULE: ${replyObj.extractedSolutionRule}",
                    tapXRatio = replyObj.tapXRatio,
                    tapYRatio = replyObj.tapYRatio,
                    sourceType = "LIVE_CODE_RULE (${replyObj.modelUsed})"
                )
                withContext(Dispatchers.Main) {
                    addChatBubble(
                        "⚖️ Live Code Rule Compiler (${replyObj.modelUsed})",
                        "${replyObj.replyText}\n\n✅ **पक्का कोडेड कानून (`LIVE_CODE_RULE`) `$appCategory` में लागू हो गया है!**",
                        isUser = false
                    )
                    chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
                }
            } else {
                withContext(Dispatchers.Main) {
                    addChatBubble(
                        "⏳ Quota Pacer Alert",
                        "भाई, अभी सभी AI मॉडल्स 60s कूलडाउन में हैं। कृपया फोटो पर उंगली से **1, 2, 3 पॉइंट** टैप करके भेजें ताकि बिना AI वेट के आपके सटीक कोऑर्डिनेट तुरंत सेव हो जाएं!",
                        isUser = false
                    )
                    chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun handleChatDirectVideoUpload(videoUri: Uri) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(bgMatteDark)
        }
        val appInput = createCleanInput("Instagram", "किस ऐप का वीडियो है? (जैसे Instagram, YouTube)")
        val noteInput = createCleanInput(
            chatInputBox.text.toString().ifEmpty { "भाई इस वीडियो में देखो कहाँ समस्या थी और इसे सुधार कर नया पक्का रूल बना दो" },
            "वीडियो में कहाँ समस्या थी या क्या सिखाना है?"
        ).apply { minLines = 3 }

        var replaceOld = false
        val replaceBtn = createPrimaryButton("🔄 मोड: पुराने नियमों के साथ जोड़ें", Color.parseColor("#262B36")) { btn ->
            replaceOld = !replaceOld
            btn.text = if (replaceOld) "⚡ मोड: पुराना नियम हटाकर इस वीडियो का नया नियम लगाएं"
            else "🔄 मोड: पुराने नियमों के साथ जोड़ें"
            btn.background = roundedBox(if (replaceOld) accentPurple else Color.parseColor("#262B36"), dp(10))
        }

        root.addView(appInput)
        root.addView(spacer(dp(8)))
        root.addView(replaceBtn)
        root.addView(spacer(dp(8)))
        root.addView(noteInput)

        darkDialogBuilder()
            .setTitle("🎥 चैट में वीडियो भेजकर नियम बनाएं")
            .setView(root)
            .setPositiveButton("🚀 वीडियो एनालाइज करें & रूल बनाएं") { _, _ ->
                val appCat = appInput.text.toString().trim().ifEmpty { "Instagram" }
                val note = noteInput.text.toString().trim()
                chatInputBox.setText("")
                addChatBubble("You (🎥 Video Uploaded in Chat)", "App: $appCat\n💬 निर्देश: $note", isUser = true)
                addChatBubble("AuraDroid Video Coach", "⏳ भाई, वीडियो के फ्रेम्स निकालकर `3.1 Pro + Robotics` से सटीक नियम बनाए जा रहे हैं...", isUser = false)

                val vault = getActiveVault()
                val brain = getActiveBrain()
                uiScope.launch(Dispatchers.IO) {
                    if (replaceOld) {
                        vault.clearCategoryMemory(appCat)
                    } else {
                        purgePoisonedRulesFromCategory(appCat)
                    }
                    val (session, framesB64) = vault.createVideoTrainingSession(appCat, note, videoUri, 12)
                    val rules = brain.analyzeTrainingVideoFrames(appCat, note, framesB64).filter { !isPoisonedFixRecord(it) }
                    for (r in rules) {
                        vault.saveStuckOrHumanFix(
                            category = appCat,
                            screenSignature = r.screenSignature,
                            problemDescription = r.problemDescription,
                            solutionRule = "MANDATORY LIVE RULE: ${r.solutionRule}",
                            tapXRatio = r.tapXRatio,
                            tapYRatio = r.tapYRatio,
                            sourceType = "CHAT_VIDEO_LAW"
                        )
                        vault.appendChatToVideoSession(session.sessionId, "AI", "Learned: ${r.solutionRule}", r.solutionRule)
                    }
                    withContext(Dispatchers.Main) {
                        if (rules.isEmpty()) {
                            addChatBubble(
                                "⏳ Models Cooling Down",
                                "भाई, अभी AI मॉडल्स कूलडाउन में होने के कारण कोई गलत रूल सेव नहीं किया गया। 30 सेकंड बाद दोबारा ट्राई करें!",
                                isUser = false
                            )
                        } else {
                            val rulesText = rules.take(5).mapIndexed { i, r ->
                                "${i + 1}. ${r.problemDescription} ➔ ${r.solutionRule} (XY: ${(r.tapXRatio * 1000).toInt()}, ${(r.tapYRatio * 1000).toInt()})"
                            }.joinToString("\n")
                            addChatBubble(
                                "✅ Video Live Rules Compiled ($appCat)",
                                "भाई, आपके वीडियो से **${rules.size} पक्के कोडेड कानून (`CHAT_VIDEO_LAW`)** बनाकर `$appCat` में लागू कर दिए गए हैं:\n\n$rulesText",
                                isUser = false
                            )
                        }
                        chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
            .setNegativeButton("रद्द करें", null)
            .show()
    }

    private fun isDirectRuleOrCorrectionMessage(msg: String): Boolean {
        val lower = msg.lowercase(Locale.ROOT)
        val ruleTriggers = listOf(
            "नियम", "niyam", "rule", "गलती", "galti", "सुधार", "sudhar", "याद रख", "yaad rakh",
            "मत दबा", "mat daba", "मत टैप", "mat tap", "दोबारा मत", "dobara mat", "कन्फर्म", "confirm",
            "नीला बटन", "neela button", "जब भी", "jab bhi", "ऐसा मत", "aisa mat", "fix karo", "ठीक करो", "बदल दो"
        )
        return ruleTriggers.any { lower.contains(it) }
    }

    internal fun refreshHomeDailyReportBanner() {
        if (!::homeBotStatusTitle.isInitialized || !::homeBotReportSubtitle.isInitialized) return
        val runs = getActiveVault().getAllAutomationRuns()
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
            homeBotReportSubtitle.text = "आज $successCount टास्क पूरे हुए, लेकिन '${failedRun.title.take(20)}' के ${failedRun.screenshotPaths.size} स्क्रीनशॉट सेव हैं। नीचे '🛠️ 1-2-3 Fix' दबाएं!"
            homeBotView.setBotMood(isHappy = false, isCurious = true)
        } else if (runs.isNotEmpty()) {
            homeBotStatusTitle.text = "$greetingPrefix सारे टास्क फिट हैं ✅"
            homeBotReportSubtitle.text = "सर, आपके $successCount टास्क सफलतापूर्वक पूरे हो गए हैं!"
            homeBotView.setBotMood(isHappy = true, isCurious = false)
        } else {
            homeBotStatusTitle.text = "$greetingPrefix Have a good luck day!"
            homeBotReportSubtitle.text = "स्क्रीन के Bot Emoji पर टैप करने से Live Background Browser खुलेगा और दोबारा टैप पर छुप जाएगा!"
            homeBotView.setBotMood(isHappy = true, isCurious = false)
        }
    }

    private fun showFriendlyDailyReportDialog() {
        val runs = getActiveVault().getAllAutomationRuns()
        val ok = runs.count { it.status == "SUCCESS" }
        val fail = runs.count { it.status != "SUCCESS" }
        val sb = StringBuilder("🌙 नमस्ते सर! आपकी आज की डेली टास्क रिपोर्ट:\n\n✅ सफल टास्क: $ok\n⚠️ अधूरे टास्क: $fail\n\n")
        if (runs.isEmpty()) {
            sb.append("• अभी तक आज कोई टास्क रन नहीं हुआ है।")
        } else {
            runs.take(5).forEachIndexed { idx, r ->
                sb.append("${idx + 1}. ${if (r.status == "SUCCESS") "✅" else "⚠️"} ${r.title} (${r.screenshotPaths.size} Screenshots) - ${r.timestamp}\n")
            }
        }
        darkDialogBuilder()
            .setTitle("🤖 10:00 PM Friendly Daily Update")
            .setMessage(sb.toString())
            .setPositiveButton("ठीक है!") { d, _ -> d.dismiss() }
            .setNeutralButton("🛠️ 1-2-3 पॉइंट से सुधारें") { d, _ ->
                d.dismiss()
                openLastRunScreenshotsPickerForChat()
            }
            .show()
    }

    internal fun handleUserChatMessage(userMsg: String, scroll: ScrollView) {
        addChatBubble("You", userMsg, isUser = true)
        chatHistoryBuilder.append("User: $userMsg\n")
        homeBotView.triggerPlayfulJumpAndWave()
        planSendBtn.isEnabled = false
        planSendBtn.text = "⏳..."

        val brain = getActiveBrain()
        val vault = getActiveVault()

        if (isDirectRuleOrCorrectionMessage(userMsg)) {
            uiScope.launch(Dispatchers.IO) {
                val lastRun = vault.getAllAutomationRuns().firstOrNull()
                val targetCategory = lastRun?.appCategory ?: when {
                    userMsg.contains("insta", true) || userMsg.contains("राहुल") || userMsg.contains("reel", true) -> "Instagram"
                    userMsg.contains("youtube", true) || userMsg.contains("short", true) -> "YouTube"
                    else -> "Instagram"
                }
                if (userMsg.contains("बदल") || userMsg.contains("हटा") || userMsg.contains("change", true)) {
                    vault.clearCategoryMemory(targetCategory)
                } else {
                    purgePoisonedRulesFromCategory(targetCategory)
                }

                val imgs = if (lastRun != null) vault.loadSavedImagesAsBase64(lastRun.screenshotPaths, 6) else emptyList()
                val syntheticRun = lastRun ?: AutomationRunRecord(
                    runId = "live_${System.currentTimeMillis()}",
                    title = "Live User Rule for $targetCategory",
                    appCategory = targetCategory,
                    status = "USER_TAUGHT",
                    timestamp = "Just Now",
                    stepsSummary = emptyList(),
                    screenshotPaths = emptyList(),
                    chatHistory = emptyList()
                )
                val replyObj = brain.chatAboutAutomationRunWith38Flash(syntheticRun, userMsg, imgs)

                val existingProfile = vault.getUserProfileRules()
                vault.saveUserProfileRules((existingProfile + "\n• [$targetCategory Law]: $userMsg").trim().takeLast(2000))

                if (isValidBrainReply(replyObj)) {
                    vault.saveStuckOrHumanFix(
                        category = targetCategory,
                        screenSignature = "live_chat_rule_${System.currentTimeMillis()}",
                        problemDescription = replyObj.extractedProblem,
                        solutionRule = "MANDATORY LIVE RULE: ${replyObj.extractedSolutionRule}",
                        tapXRatio = replyObj.tapXRatio,
                        tapYRatio = replyObj.tapYRatio,
                        sourceType = "LIVE_CODE_RULE (${replyObj.modelUsed})"
                    )
                }

                withContext(Dispatchers.Main) {
                    planSendBtn.isEnabled = true
                    planSendBtn.text = "Plan ➔"
                    val normX = (replyObj.tapXRatio * 1000).toInt()
                    val normY = (replyObj.tapYRatio * 1000).toInt()
                    addChatBubble(
                        "⚖️ Live Rule Compiler (${replyObj.modelUsed})",
                        "${replyObj.replyText}\n\n✅ **पक्का कानून (`LIVE_CODE_RULE`) लागू हो गया:**\n• App: `$targetCategory`\n• Action: `${replyObj.extractedSolutionRule}`\n• XY: `($normX, $normY)`",
                        isUser = false
                    )
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
            return
        }

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
                val stepsList = (0 until stepsArr.length()).map { stepsArr.optString(it) }

                chatHistoryBuilder.append("AI: $reply\n")
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

    internal fun addChatBubble(sender: String, message: String, isUser: Boolean) {
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBox(if (isUser) Color.parseColor("#1E3A8A") else cardSlate, dp(14), borderSubtle)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
                if (isUser) marginStart = dp(40) else marginEnd = dp(24)
            }
        }
        bubble.addView(TextView(this).apply {
            text = sender
            setTextColor(textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        })
        bubble.addView(TextView(this).apply {
            text = message
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(4), 0, 0)
        })
        chatMessagesLayout.addView(bubble)
    }

    private fun addDetailedPlanCardToChat(title: String, mode: String, steps: List<String>, rawGoal: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBox(Color.parseColor("#172554"), dp(16), accentRoyalBlue)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        card.addView(TextView(this).apply {
            text = "📋 फाइनल एक्शन प्लान: $title ($mode)"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        })
        steps.forEachIndexed { idx, stepText ->
            card.addView(TextView(this).apply {
                text = "  ${idx + 1}. $stepText"
                setTextColor(Color.parseColor("#E2E8F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(4), 0, 0)
            })
        }
        card.addView(createPrimaryButton("▶ हाँ, काम शुरू करो (Start Execution)", accentEmerald) {
            val svc = AutoTouchService.instance
            if (svc == null) {
                Toast.makeText(this@MainActivity, "पहले Accessibility Service ऑन करें!", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                svc.hud.showHud()
                svc.startUniversalTask(rawGoal, steps, mode)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        })
        chatMessagesLayout.addView(card)
    }

    private fun resolveTreeUriToRealPath(treeUri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            when {
                docId.startsWith("raw:") -> docId.removePrefix("raw:")
                docId.equals("downloads", ignoreCase = true) -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                docId.contains(":") -> {
                    val split = docId.split(":")
                    val rel = if (split.size > 1) split[1] else ""
                    if (split[0].equals("primary", ignoreCase = true)) {
                        if (rel.isEmpty()) Environment.getExternalStorageDirectory().absolutePath
                        else "${Environment.getExternalStorageDirectory().absolutePath}/$rel"
                    } else "/storage/${split[0]}/$rel"
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
            REQ_CHAT_PICK_IMAGE -> {
                val imgUri = data.data ?: return
                try {
                    contentResolver.openInputStream(imgUri)?.use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream)
                        if (bmp != null) {
                            openMultiPointEditableScreenshotDialog(bmp, "Instagram", "1-2-3 Multi-Point Rule Editor")
                        }
                    }
                } catch (_: Exception) {
                    Toast.makeText(this, "फोटो लोड नहीं हो सका!", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_CHAT_PICK_VIDEO -> {
                val vidUri = data.data ?: return
                handleChatDirectVideoUpload(vidUri)
            }
            REQ_PICK_FOLDER_TREE -> {
                val resolvedPath = resolveTreeUriToRealPath(data.data ?: return)
                folderPathEditText.setText(resolvedPath)
                getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE).edit().putString("yt_folder_path", resolvedPath).apply()
            }
            REQ_PICK_FLOW_SAVE_FOLDER -> {
                val resolvedPath = resolveTreeUriToRealPath(data.data ?: return)
                if (::flowDownloadFolderEditText.isInitialized) flowDownloadFolderEditText.setText(resolvedPath)
                if (::folderPathEditText.isInitialized) folderPathEditText.setText(resolvedPath)
                getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("custom_download_folder", resolvedPath)
                    .putString("yt_folder_path", resolvedPath)
                    .apply()
            }
            REQ_PICK_FREEWEB_SAVE_FOLDER -> {
                val resolvedPath = resolveTreeUriToRealPath(data.data ?: return)
                if (::freeWebDownloadFolderEditText.isInitialized) freeWebDownloadFolderEditText.setText(resolvedPath)
                getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE).edit().putString("custom_download_folder", resolvedPath).apply()
            }
            REQ_PICK_SINGLE_VIDEO_FOR_YT -> {
                val videoUri = data.data ?: return
                AutoTouchService.instance?.startSinglePickedVideoYouTubeUpload(videoUri, getFileNameFromUri(videoUri))
            }
            REQ_PICK_TRAINING_VIDEO -> {
                val videoUri = data.data ?: return
                val brain = getActiveBrain()
                val vault = getActiveVault()
                uiScope.launch(Dispatchers.IO) {
                    val (session, framesB64) = vault.createVideoTrainingSession(selectedTrainingCategory, selectedTrainingNote, videoUri, 12)
                    val rules = brain.analyzeTrainingVideoFrames(selectedTrainingCategory, selectedTrainingNote, framesB64)
                        .filter { !isPoisonedFixRecord(it) }
                    for (r in rules) {
                        vault.saveStuckOrHumanFix(
                            category = selectedTrainingCategory,
                            screenSignature = r.screenSignature,
                            problemDescription = r.problemDescription,
                            solutionRule = r.solutionRule,
                            tapXRatio = r.tapXRatio,
                            tapYRatio = r.tapYRatio,
                            sourceType = r.sourceType
                        )
                        vault.appendChatToVideoSession(session.sessionId, "Gemini 3.8", "Learned: ${r.solutionRule}", r.solutionRule)
                    }
                    withContext(Dispatchers.Main) {
                        refreshMemoryEverything()
                        Toast.makeText(this@MainActivity, "✅ Video Trained (${rules.size} Verified Rules)!", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "viral_short_video.mp4"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
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

    internal fun createSectionCard(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBox(cardSlate, dp(16), borderSubtle)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(textPureWhite)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(12))
            })
        }
    }

    internal fun createCleanInput(initialText: String, hintText: String): EditText {
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

    internal fun createPrimaryButton(label: String, color: Int, onClick: (TextView) -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = roundedBox(color, dp(12))
            setOnClickListener { onClick(this) }
        }
    }

    internal fun roundedBox(fillColor: Int, radiusPx: Int, strokeColor: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radiusPx.toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }
    }

    internal fun spacer(heightPx: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
        }
    }

    internal fun dp(value: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
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
            val bobY = sin(animPhase.toDouble()).toFloat() * dp(3).toFloat() - (waveBoost * dp(6).toFloat())
            val glanceX = sin((animPhase * 0.7f).toDouble()).toFloat() * dp(2).toFloat()

            canvas.drawOval(
                cx - dp(18).toFloat(),
                h - dp(14).toFloat(),
                cx - dp(4).toFloat(),
                h - dp(4).toFloat(),
                bodyPaint
            )
            canvas.drawOval(
                cx + dp(4).toFloat(),
                h - dp(14).toFloat(),
                cx + dp(18).toFloat(),
                h - dp(4).toFloat(),
                bodyPaint
            )

            rect.set(
                cx - dp(24).toFloat(),
                h * 0.42f + bobY,
                cx + dp(24).toFloat(),
                h - dp(10).toFloat() + bobY
            )
            canvas.drawRoundRect(rect, dp(22).toFloat(), dp(22).toFloat(), bodyPaint)
            canvas.drawRoundRect(rect, dp(22).toFloat(), dp(22).toFloat(), shadowPaint)

            val waveOffset = sin((animPhase * 2f).toDouble()).toFloat() * dp(6).toFloat() + (waveBoost * dp(12).toFloat())
            canvas.drawOval(
                cx - dp(34).toFloat(),
                h * 0.46f + bobY - waveOffset,
                cx - dp(20).toFloat(),
                h * 0.68f + bobY,
                bodyPaint
            )
            canvas.drawOval(
                cx + dp(20).toFloat(),
                h * 0.46f + bobY + waveOffset,
                cx + dp(34).toFloat(),
                h * 0.68f + bobY,
                bodyPaint
            )

            rect.set(
                cx - dp(26).toFloat(),
                dp(8).toFloat() + bobY,
                cx + dp(26).toFloat(),
                h * 0.46f + bobY
            )
            canvas.drawRoundRect(rect, dp(24).toFloat(), dp(24).toFloat(), bodyPaint)
            canvas.drawRoundRect(rect, dp(24).toFloat(), dp(24).toFloat(), shadowPaint)

            val eyeY = h * 0.26f + bobY
            canvas.drawCircle(cx - dp(15).toFloat() + glanceX, eyeY + dp(5).toFloat(), dp(4).toFloat(), blushPaint)
            canvas.drawCircle(cx + dp(15).toFloat() + glanceX, eyeY + dp(5).toFloat(), dp(4).toFloat(), blushPaint)

            val leftEyeX = cx - dp(10).toFloat() + glanceX
            val rightEyeX = cx + +dp(10).toFloat() + glanceX
            val eyeR = if (isCuriousMood) dp(5).toFloat() else dp(4).toFloat()
            canvas.drawLine(leftEyeX, eyeY, rightEyeX, eyeY, mouthStrokePaint)
            canvas.drawCircle(leftEyeX, eyeY, eyeR, featurePaint)
            canvas.drawCircle(rightEyeX, eyeY, eyeR, featurePaint)

            val mouthY = eyeY + dp(8).toFloat()
            if (isCuriousMood) {
                canvas.drawCircle(cx + glanceX, mouthY, dp(3).toFloat(), mouthStrokePaint)
            } else {
                mouthPath.reset()
                mouthPath.moveTo(cx - dp(4).toFloat() + glanceX, mouthY)
                mouthPath.quadTo(
                    cx + glanceX,
                    mouthY + if (isHappyMood) dp(4).toFloat() else -dp(2).toFloat(),
                    cx + dp(4).toFloat() + glanceX,
                    mouthY
                )
                canvas.drawPath(mouthPath, mouthStrokePaint)
            }
        }
    }
}
