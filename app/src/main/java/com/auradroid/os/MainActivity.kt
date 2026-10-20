package com.auradroid.os

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
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
import java.util.Locale
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
    private lateinit var chatScrollView: ScrollView
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
        private const val REQ_CHAT_PICK_IMAGE = 207
        private const val REQ_CHAT_PICK_VIDEO = 208
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
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
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
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        refreshServiceBadge()

        header.addView(titleView)
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

    private fun getActiveBrain(): GeminiBrain {
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("gemini_api_key", "") ?: ""
        localBrain.updateApiKey(savedKey)
        return AutoTouchService.instance?.geminiBrain?.also { it.updateApiKey(savedKey) } ?: localBrain
    }

    private fun getActiveVault(): MemoryVault = AutoTouchService.instance?.memoryVault ?: localVault

    private fun refreshServiceBadge() {
        if (!::serviceBadge.isInitialized) return
        val active = AutoTouchService.instance != null
        serviceBadge.text = if (active) "● 24/7 Active" else "○ Enable Body"
        serviceBadge.setTextColor(if (active) accentEmerald else accentAmber)
    }

    private fun requestStoragePermissionsOnDemand(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "कृपया 'All Files Access' ऑन करें!", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
                return false
            }
            return true
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
            return true
        }
    }

    private fun switchTab(index: Int) {
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
        if (index == 0) refreshHomeDailyReportBanner()
        else if (index == 3) refreshMemoryEverything()
        else if (index == 4) {
            refreshEngineTabDetails()
            logAutoRefreshJob = uiScope.launch {
                while (isActive && currentTabIndex == 4) {
                    delay(2500L)
                    refreshEngineTabDetails()
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
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
            text = "सर, नीचे चैट में '📎' दबाकर स्क्रीनशॉट (1-2-3 पॉइंट लगाकर) या वीडियो सीधे यहीं भेजें!"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(3), 0, dp(6))
        }

        val actionPillsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val dailyReportBtn = TextView(this).apply {
            text = "📊 Report"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = roundedBox(accentEmerald, dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
            setOnClickListener { showFriendlyDailyReportDialog() }
        }

        val diagnoseBtn = TextView(this).apply {
            text = "🛠️ 1-2-3 स्क्रीनशॉट Fix"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = roundedBox(accentPurple, dp(8))
            setOnClickListener { openLastRunScreenshotsPickerForChat() }
        }

        actionPillsRow.addView(dailyReportBtn)
        actionPillsRow.addView(diagnoseBtn)
        rightStatusCol.addView(homeBotStatusTitle)
        rightStatusCol.addView(homeBotReportSubtitle)
        rightStatusCol.addView(actionPillsRow)
        companionCard.addView(homeBotView)
        companionCard.addView(rightStatusCol)

        chatScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        chatMessagesLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatScrollView.addView(chatMessagesLayout)

        addChatBubble(
            "AuraDroid Chanchal Bot",
            "नमस्ते भाई! अब आप सीधे इसी चैट में सब कुछ कर सकते हैं:\n" +
                "• नीचे **`📎` बटन** दबाकर स्क्रीनशॉट चुनें ➔ उस पर उंगली से **`1`**, **`2`**, **`3`** पॉइंट लगाएं और लिख दें कि कहाँ टाइप/टैप करना है!\n" +
                "• या **`📎` बटन** से सीधे यहीं **वीडियो (Screen Recording)** भेजें — मैं पुराना नियम बदलकर पक्का कोडेड कानून (`LIVE_CODE_RULE`) बना दूँगा!",
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(5) }
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
        val options = arrayOf(
            "🖼️ गैलरी से स्क्रीनशॉट/फोटो भेजें (1-2-3 पॉइंट लगाएं)",
            "📸 पिछले टास्क के स्क्रीनशॉट पर 1-2-3 पॉइंट लगाएं",
            "🎥 चैट में वीडियो (Screen Recording) भेजें & सुधारें",
            "🗑️ किसी ऐप का पुराना लाइव कोड रूल बदलें / साफ करें"
        )
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle("📎 चैट में स्क्रीनशॉट या वीडियो भेजें")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, REQ_CHAT_PICK_IMAGE)
                    1 -> openLastRunScreenshotsPickerForChat()
                    2 -> startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "video/*" }, REQ_CHAT_PICK_VIDEO)
                    3 -> {
                        val vault = getActiveVault()
                        val cats = vault.getAllCategoriesSummary().keys.toTypedArray()
                        if (cats.isEmpty()) {
                            Toast.makeText(this, "अभी कोई पुराना नियम नहीं है!", Toast.LENGTH_SHORT).show()
                        } else {
                            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
                                .setTitle("किस ऐप के पुराने नियम हटाकर नए बनाने हैं?")
                                .setItems(cats) { _, idx ->
                                    vault.clearCategoryMemory(cats[idx])
                                    addChatBubble("⚙️ Live Rule Engine", "✅ `${cats[idx]}` के पुराने नियम हटा दिए गए हैं! अब नए 1-2-3 पॉइंट दें।", false)
                                }
                                .show()
                        }
                    }
                }
            }
            .show()
    }

    private fun openLastRunScreenshotsPickerForChat() {
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

        val pickerDialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle("📸 स्क्रीनशॉट चुनें (${run.screenshotPaths.size})")
            .setView(dialogLayout as View)
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

    private fun openMultiPointEditableScreenshotDialog(
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
        }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) } }

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

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle("🖼️ $initialTitle")
            .setView(root as View)
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
            if (replaceOld) vault.clearCategoryMemory(appCategory)

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

            if (points.isEmpty()) {
                vault.saveStuckOrHumanFix(
                    category = appCategory,
                    screenSignature = "live_rule_${System.currentTimeMillis()}",
                    problemDescription = replyObj.extractedProblem,
                    solutionRule = "MANDATORY LIVE RULE: ${replyObj.extractedSolutionRule}",
                    tapXRatio = replyObj.tapXRatio,
                    tapYRatio = replyObj.tapYRatio,
                    sourceType = "LIVE_CODE_RULE (${replyObj.modelUsed})"
                )
            } else {
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
            }

            withContext(Dispatchers.Main) {
                addChatBubble(
                    "⚖️ Live Code Rule Compiler (${replyObj.modelUsed})",
                    "${replyObj.replyText}\n\n✅ **${points.size.coerceAtLeast(1)} पक्के कोडेड कानून (`LIVE_CODE_RULE`) `$appCategory` में लागू हो गए हैं!**\n• Points: `$ptsText`",
                    isUser = false
                )
                chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
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

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle("🎥 चैट में वीडियो भेजकर नियम बनाएं")
            .setView(root as View)
            .setPositiveButton("🚀 वीडियो एनालाइज करें & रूल बनाएं") { _, _ ->
                val appCat = appInput.text.toString().trim().ifEmpty { "Instagram" }
                val note = noteInput.text.toString().trim()
                chatInputBox.setText("")
                addChatBubble("You (🎥 Video Uploaded in Chat)", "App: $appCat\n💬 निर्देश: $note", isUser = true)
                addChatBubble("AuraDroid Video Coach", "⏳ भाई, वीडियो के फ्रेम्स निकालकर `3.1 Pro + Robotics` से सटीक नियम बनाए जा रहे हैं...", isUser = false)

                val vault = getActiveVault()
                val brain = getActiveBrain()
                uiScope.launch(Dispatchers.IO) {
                    if (replaceOld) vault.clearCategoryMemory(appCat)
                    val (session, framesB64) = vault.createVideoTrainingSession(appCat, note, videoUri, 12)
                    val rules = brain.analyzeTrainingVideoFrames(appCat, note, framesB64)
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
                        val rulesText = rules.take(5).mapIndexed { i, r ->
                            "${i + 1}. ${r.problemDescription} ➔ ${r.solutionRule} (XY: ${(r.tapXRatio * 1000).toInt()}, ${(r.tapYRatio * 1000).toInt()})"
                        }.joinToString("\n")
                        addChatBubble(
                            "✅ Video Live Rules Compiled ($appCat)",
                            "भाई, आपके वीडियो से **${rules.size} पक्के कोडेड कानून (`CHAT_VIDEO_LAW`)** बनाकर `$appCat` में लागू कर दिए गए हैं:\n\n$rulesText",
                            isUser = false
                        )
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

    private fun refreshHomeDailyReportBanner() {
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
            homeBotReportSubtitle.text = "आज $successCount टास्क पूरे हुए, लेकिन '${failedRun.title.take(20)}' के ${failedRun.screenshotPaths.size} स्क्रीनशॉट सेव हैं। नीचे '🛠️ 1-2-3 स्क्रीनशॉट Fix' या '📎' दबाएं!"
            homeBotView.setBotMood(isHappy = false, isCurious = true)
        } else if (runs.isNotEmpty()) {
            homeBotStatusTitle.text = "$greetingPrefix सारे टास्क फिट हैं ✅"
            homeBotReportSubtitle.text = "सर, आपके $successCount टास्क सफलतापूर्वक पूरे हो गए हैं!"
            homeBotView.setBotMood(isHappy = true, isCurious = false)
        } else {
            homeBotStatusTitle.text = "$greetingPrefix Have a good luck day!"
            homeBotReportSubtitle.text = "नीचे चैट में '📎' दबाकर स्क्रीनशॉट (1-2-3 पॉइंट) या वीडियो से सीधा नियम सिखाएं!"
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
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle("🤖 10:00 PM Friendly Daily Update")
            .setMessage(sb.toString())
            .setPositiveButton("ठीक है!") { d, _ -> d.dismiss() }
            .setNeutralButton("🛠️ 1-2-3 पॉइंट से सुधारें") { d, _ ->
                d.dismiss()
                openLastRunScreenshotsPickerForChat()
            }
            .show()
    }

    private fun handleUserChatMessage(userMsg: String, scroll: ScrollView) {
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
                vault.saveStuckOrHumanFix(
                    category = targetCategory,
                    screenSignature = "live_chat_rule_${System.currentTimeMillis()}",
                    problemDescription = replyObj.extractedProblem,
                    solutionRule = "MANDATORY LIVE RULE: ${replyObj.extractedSolutionRule}",
                    tapXRatio = replyObj.tapXRatio,
                    tapYRatio = replyObj.tapYRatio,
                    sourceType = "LIVE_CODE_RULE (${replyObj.modelUsed})"
                )
                val existingProfile = vault.getUserProfileRules()
                vault.saveUserProfileRules((existingProfile + "\n• [$targetCategory Law]: ${replyObj.extractedSolutionRule}").trim().takeLast(2000))

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

    private fun addChatBubble(sender: String, message: String, isUser: Boolean) {
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBox(if (isUser) Color.parseColor("#1E3A8A") else cardSlate, dp(14), borderSubtle)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        })
        chatMessagesLayout.addView(card)
    }

    private fun buildTab2AutomationsAndScheduler(): View {
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }
        val defaultDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

        val flowCard = createSectionCard("1️⃣ #1 Task: Google Flow AI Video Studio")
        var flowExecMode = prefs.getString("flow_execution_mode", "BACKGROUND") ?: "BACKGROUND"
        val flowModeSwitchBtn = createPrimaryButton(
            if (flowExecMode == "BACKGROUND") "🌐 Run Mode: 100% Background (पीछे चुपचाप बनेगी और अपलोड होगी)"
            else "📱 Run Mode: Live Phone Screen (असली स्क्रीन पर सामने चलेगा)",
            if (flowExecMode == "BACKGROUND") accentRoyalBlue else accentPurple
        ) { btn ->
            flowExecMode = if (flowExecMode == "BACKGROUND") "LIVE_PHONE" else "BACKGROUND"
            prefs.edit().putString("flow_execution_mode", flowExecMode).putBoolean("bg_youtube_upload", flowExecMode == "BACKGROUND").apply()
            btn.text = if (flowExecMode == "BACKGROUND") "🌐 Run Mode: 100% Background (पीछे चुपचाप बनेगी और अपलोड होगी)"
            else "📱 Run Mode: Live Phone Screen (असली स्क्रीन पर सामने चलेगा)"
            btn.background = roundedBox(if (flowExecMode == "BACKGROUND") accentRoyalBlue else accentPurple, dp(12))
        }

        var isStoryMode = prefs.getBoolean("flow_story_mode", false)
        val storyModeBtn = createPrimaryButton(
            if (isStoryMode) "📖 Video Type: Step-by-Step Story Base (कहानी के भाग 1, 2, 3 बनेंगे)"
            else "🎬 Video Type: Single Exact Prompt Video (जो प्रॉम्प्ट देंगे वही बनेगा)",
            Color.parseColor("#262B36")
        ) { btn ->
            isStoryMode = !isStoryMode
            prefs.edit().putBoolean("flow_story_mode", isStoryMode).apply()
            btn.text = if (isStoryMode) "📖 Video Type: Step-by-Step Story Base (कहानी के भाग 1, 2, 3 बनेंगे)"
            else "🎬 Video Type: Single Exact Prompt Video (जो प्रॉम्प्ट देंगे वही बनेगा)"
        }

        val topicInput = createCleanInput(
            prefs.getString("flow_topic", "Young Indian girl dancing in traditional attire") ?: "Young Indian girl dancing in traditional attire",
            "Video Prompt / Story Topic"
        )
        val creditLimitInput = createCleanInput(prefs.getInt("flow_daily_videos", 1).toString(), "कितनी वीडियो बनानी हैं? (1, 2, या 3)")
        flowDownloadFolderEditText = createCleanInput(prefs.getString("custom_download_folder", defaultDownloadDir) ?: defaultDownloadDir, "किस फोल्डर में सेव होगा?")

        val pickFlowFolderBtn = createPrimaryButton("📂 डाउनलोड फोल्डर चुनें (Choose Save Folder)", Color.parseColor("#334155")) {
            if (requestStoragePermissionsOnDemand()) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_FLOW_SAVE_FOLDER)
        }

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
            AutoTouchService.instance?.startGoogleFlowToYouTubePipeline(finalTopic, count)
                ?: Toast.makeText(this, "पहले Accessibility Service ऑन करें!", Toast.LENGTH_SHORT).show()
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
        flowCard.addView(buildMultiPlatformLinkerBox(prefs))
        flowCard.addView(spacer(dp(10)))
        flowCard.addView(aiTaskHelperBtn)
        flowCard.addView(spacer(dp(10)))
        flowCard.addView(runFlowNowBtn)

        val freeHubCard = createSectionCard("2️⃣ #2 Task: Multi-Website Free AI Video Hub")
        val freeSiteInput = createCleanInput(prefs.getString("free_hub_site", "https://gemini.google.com") ?: "https://gemini.google.com", "Free AI Website URL or App")

        val siteChipsScroll = HorizontalScrollView(this)
        val siteChipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }
        listOf(
            "✨ Gemini" to "https://gemini.google.com",
            "🤖 ChatGPT" to "https://chatgpt.com",
            "🎬 Hailuo Free" to "https://hailuoai.video",
            "🎥 Kling AI" to "https://klingai.com",
            "🌊 Google Flow" to "https://labs.google/fx/tools/flow"
        ).forEach { (label, url) ->
            siteChipsRow.addView(TextView(this).apply {
                text = label
                setTextColor(textPureWhite)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = roundedBox(Color.parseColor("#262B36"), dp(8), borderSubtle)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
                setOnClickListener {
                    freeSiteInput.setText(url)
                    prefs.edit().putString("free_hub_site", url).apply()
                }
            })
        }
        siteChipsScroll.addView(siteChipsRow)

        var freeHubExecMode = prefs.getString("free_hub_exec_mode", "LIVE_WHEN_IDLE") ?: "LIVE_WHEN_IDLE"
        val freeHubModeBtn = createPrimaryButton(
            when (freeHubExecMode) {
                "BACKGROUND" -> "🌐 Mode: Background WebView (पीछे ब्राउज़र में चलेगा)"
                "LIVE_WHEN_IDLE" -> "🌙 Mode: Real App When Phone is Free (जब फोन फ्री हो तब असली ऐप चलाएगा)"
                else -> "📱 Mode: Fixed Time Real App (तय समय पर असली ऐप में चलाएगा)"
            },
            Color.parseColor("#1E3A8A")
        ) { btn ->
            freeHubExecMode = when (freeHubExecMode) {
                "BACKGROUND" -> "LIVE_WHEN_IDLE"
                "LIVE_WHEN_IDLE" -> "LIVE_FIXED_TIME"
                else -> "BACKGROUND"
            }
            prefs.edit().putString("free_hub_exec_mode", freeHubExecMode).putBoolean("run_only_when_idle", freeHubExecMode == "LIVE_WHEN_IDLE").apply()
            btn.text = when (freeHubExecMode) {
                "BACKGROUND" -> "🌐 Mode: Background WebView (पीछे ब्राउज़र में चलेगा)"
                "LIVE_WHEN_IDLE" -> "🌙 Mode: Real App When Phone is Free (जब फोन फ्री हो तब असली ऐप चलाएगा)"
                else -> "📱 Mode: Fixed Time Real App (तय समय पर असली ऐप में चलाएगा)"
            }
        }

        val freeHubPromptInput = createCleanInput(prefs.getString("free_hub_prompt", "Create a viral 9:16 short video and download it") ?: "", "कैसी वीडियो बनवानी है?")
        freeWebDownloadFolderEditText = createCleanInput(prefs.getString("custom_download_folder", defaultDownloadDir) ?: defaultDownloadDir, "डाउनलोड फोल्डर पाथ")

        val pickFreeWebFolderBtn = createPrimaryButton("📂 फोल्डर पाथ चुनें (Browse Download Path)", Color.parseColor("#334155")) {
            if (requestStoragePermissionsOnDemand()) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_FREEWEB_SAVE_FOLDER)
        }

        val runFreeHubBtn = createPrimaryButton("🎬 #2 Free Video Hub चलाएं (Download ➔ YouTube/Insta)", accentRoyalBlue) {
            val site = freeSiteInput.text.toString().trim()
            val prompt = freeHubPromptInput.text.toString().trim()
            val saveDir = freeWebDownloadFolderEditText.text.toString().trim().ifEmpty { defaultDownloadDir }
            prefs.edit().putString("free_hub_site", site).putString("free_hub_prompt", prompt).putString("custom_download_folder", saveDir).apply()
            val svc = AutoTouchService.instance
            if (svc == null) {
                Toast.makeText(this, "पहले Accessibility Service ऑन करें!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                val ytOn = prefs.getBoolean("yt_link_enabled", true)
                val instaOn = prefs.getBoolean("insta_link_enabled", false)
                val targets = listOfNotNull(if (ytOn) "YouTube Shorts" else null, if (instaOn) "Instagram Reels" else null).joinToString(" & ")
                val mode = if (freeHubExecMode == "BACKGROUND") "BACKGROUND_WEB" else "LIVE_PHONE"
                svc.startUniversalTask("Open $site, generate video for '$prompt', save to $saveDir, and upload to $targets", emptyList(), mode)
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

        val ytCard = createSectionCard("3️⃣ #3 Task: Folder Video ➔ YouTube & Instagram Uploader")
        var folderUploadBgMode = prefs.getBoolean("bg_youtube_upload", true)
        val folderModeSwitchBtn = createPrimaryButton(
            if (folderUploadBgMode) "🌐 Upload Mode: 100% Background YouTube Upload (बिना ऐप खोले)"
            else "📱 Upload Mode: Live YouTube/Instagram App (स्क्रीन पर ऐप खोलकर)",
            if (folderUploadBgMode) accentRoyalBlue else accentPurple
        ) { btn ->
            folderUploadBgMode = !folderUploadBgMode
            prefs.edit().putBoolean("bg_youtube_upload", folderUploadBgMode).apply()
            btn.text = if (folderUploadBgMode) "🌐 Upload Mode: 100% Background YouTube Upload (बिना ऐप खोले)"
            else "📱 Upload Mode: Live YouTube/Instagram App (स्क्रीन पर ऐप खोलकर)"
            btn.background = roundedBox(if (folderUploadBgMode) accentRoyalBlue else accentPurple, dp(12))
        }

        folderPathEditText = createCleanInput(prefs.getString("yt_folder_path", defaultDownloadDir) ?: defaultDownloadDir, "Video Folder Path")
        val browseFolderBtn = createPrimaryButton("📂 फोन से फोल्डर चुनें (Browse Folder)", accentRoyalBlue) {
            if (requestStoragePermissionsOnDemand()) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_FOLDER_TREE)
        }

        var pickOldest = prefs.getBoolean("yt_pick_oldest", true)
        val orderToggleBtn = createPrimaryButton(
            if (pickOldest) "🔃 Order: Oldest Video First (पुरानी पहले)" else "🔃 Order: Newest Video First (नई पहले)",
            Color.parseColor("#262B36")
        ) { btn ->
            pickOldest = !pickOldest
            prefs.edit().putBoolean("yt_pick_oldest", pickOldest).apply()
            btn.text = if (pickOldest) "🔃 Order: Oldest Video First (पुरानी पहले)" else "🔃 Order: Newest Video First (नई पहले)"
        }

        val runYtNowBtn = createPrimaryButton("🚀 फोल्डर से 1 वीडियो YouTube (+ Linked Insta) पर अपलोड करें", accentEmerald) {
            if (!requestStoragePermissionsOnDemand()) return@createPrimaryButton
            val path = folderPathEditText.text.toString().trim()
            prefs.edit().putString("yt_folder_path", path).apply()
            AutoTouchService.instance?.startYouTubeFolderAutoUpload(path, pickOldest)
                ?: Toast.makeText(this, "पहले Accessibility Service ऑन करें!", Toast.LENGTH_SHORT).show()
        }

        val pickSingleVideoBtn = createPrimaryButton("🎬 सीधे गैलरी से 1 वीडियो चुनें और अभी अपलोड करें", Color.parseColor("#0D9488")) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }
            startActivityForResult(intent, REQ_PICK_SINGLE_VIDEO_FOR_YT)
        }

        ytCard.addView(folderModeSwitchBtn)
        ytCard.addView(spacer(dp(8)))
        ytCard.addView(folderPathEditText)
        ytCard.addView(spacer(dp(8)))
        ytCard.addView(browseFolderBtn)
        ytCard.addView(spacer(dp(8)))
        ytCard.addView(orderToggleBtn)
        ytCard.addView(spacer(dp(10)))
        ytCard.addView(runYtNowBtn)
        ytCard.addView(spacer(dp(8)))
        ytCard.addView(pickSingleVideoBtn)

        val schedCard = createSectionCard("⏰ 24/7 Smart Scheduler + Idle Phone & Skip Control")
        val timeInput = createCleanInput(prefs.getString("schedule_time_hm", "19:00") ?: "19:00", "24-Hour Time (19:00 = 7:00 PM)")

        var schedType = prefs.getString("scheduled_pipeline_type", "FLOW_YT") ?: "FLOW_YT"
        val typeSwitchBtn = createPrimaryButton(
            if (schedType == "FLOW_YT") "🔄 Daily Task: #1 Google Flow ➔ YouTube/Insta" else "🔄 Daily Task: #3 Local Folder ➔ YouTube/Insta",
            Color.parseColor("#262B36")
        ) { btn ->
            schedType = if (schedType == "FLOW_YT") "FOLDER_YT" else "FLOW_YT"
            prefs.edit().putString("scheduled_pipeline_type", schedType).apply()
            btn.text = if (schedType == "FLOW_YT") "🔄 Daily Task: #1 Google Flow ➔ YouTube/Insta" else "🔄 Daily Task: #3 Local Folder ➔ YouTube/Insta"
        }

        var onlyWhenIdle = prefs.getBoolean("run_only_when_idle", false)
        val idleModeToggleBtn = createPrimaryButton(
            if (onlyWhenIdle) "🌙 Smart Trigger: ON (जब मैं फोन न चला रहा हूँ तभी चलाएं)"
            else "⌚ Smart Trigger: Fixed Time (ठीक समय पर चलेगा, फोन चलाते समय पूछेगा)",
            if (onlyWhenIdle) accentPurple else Color.parseColor("#262B36")
        ) { btn ->
            onlyWhenIdle = !onlyWhenIdle
            prefs.edit().putBoolean("run_only_when_idle", onlyWhenIdle).apply()
            btn.text = if (onlyWhenIdle) "🌙 Smart Trigger: ON (जब मैं फोन न चला रहा हूँ तभी चलाएं)"
            else "⌚ Smart Trigger: Fixed Time (ठीक समय पर चलेगा, फोन चलाते समय पूछेगा)"
            btn.background = roundedBox(if (onlyWhenIdle) accentPurple else Color.parseColor("#262B36"), dp(12))
        }

        val snoozeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        snoozeRow.addView(createPrimaryButton("⏳ Skip 10 Min", accentAmber) {
            prefs.edit().putLong("snooze_until_ms", System.currentTimeMillis() + 10 * 60_000L).apply()
            Toast.makeText(this, "⏳ 10 मिनट के लिए Skip कर दिया गया!", Toast.LENGTH_SHORT).show()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) } })

        snoozeRow.addView(createPrimaryButton("🔔 Test Popup", Color.parseColor("#334155")) {
            AutoTouchService.instance?.hud?.showHud()
            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
                .setTitle("🤖 7:00 PM Smart Task Prompt")
                .setMessage("भाई, अभी आपका डेली ऑटोमेशन का समय हो गया है। आप क्या करना चाहते हैं?")
                .setPositiveButton("🌐 Run in Background") { _, _ ->
                    prefs.edit().putBoolean("bg_youtube_upload", true).apply()
                    Toast.makeText(this, "🌐 बैकग्राउंड मोड चुन लिया गया!", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("🌙 When Phone Free") { _, _ ->
                    prefs.edit().putBoolean("run_only_when_idle", true).apply()
                    Toast.makeText(this, "🌙 जब फोन फ्री होगा तब चलेगा!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("⏳ Skip 10 Min") { _, _ ->
                    prefs.edit().putLong("snooze_until_ms", System.currentTimeMillis() + 10 * 60_000L).apply()
                    Toast.makeText(this, "⏳ 10 मिनट के लिए Skip कर दिया गया!", Toast.LENGTH_SHORT).show()
                }
                .show()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })

        var schedEnabled = prefs.getBoolean("scheduler_enabled", false)
        val enableSchedBtn = createPrimaryButton(
            if (schedEnabled) "✅ 24/7 Daily Scheduler: ON" else "⚪ 24/7 Daily Scheduler: OFF",
            if (schedEnabled) accentEmerald else Color.parseColor("#475569")
        ) { btn ->
            schedEnabled = !schedEnabled
            prefs.edit().putBoolean("scheduler_enabled", schedEnabled).putString("schedule_time_hm", timeInput.text.toString().trim()).apply()
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
        box.addView(TextView(this).apply {
            text = "🔗 Multi-Platform Auto-Upload Linker:"
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var ytLinked = prefs.getBoolean("yt_link_enabled", true)
        val ytChip = createPrimaryButton(
            if (ytLinked) "✅ YouTube" else "⚪ YouTube",
            if (ytLinked) Color.parseColor("#DC2626") else Color.parseColor("#262B36")
        ) { btn ->
            ytLinked = !ytLinked
            prefs.edit().putBoolean("yt_link_enabled", ytLinked).apply()
            btn.text = if (ytLinked) "✅ YouTube" else "⚪ YouTube"
            btn.background = roundedBox(if (ytLinked) Color.parseColor("#DC2626") else Color.parseColor("#262B36"), dp(10))
        }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) } }

        var instaLinked = prefs.getBoolean("insta_link_enabled", false)
        val instaChip = createPrimaryButton(
            if (instaLinked) "✅ Insta Reels" else "⚪ Insta Reels",
            if (instaLinked) Color.parseColor("#DB2777") else Color.parseColor("#262B36")
        ) { btn ->
            instaLinked = !instaLinked
            prefs.edit().putBoolean("insta_link_enabled", instaLinked).apply()
            btn.text = if (instaLinked) "✅ Insta Reels" else "⚪ Insta Reels"
            btn.background = roundedBox(if (instaLinked) Color.parseColor("#DB2777") else Color.parseColor("#262B36"), dp(10))
        }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) } }

        val customStatusTv = TextView(this).apply {
            val s = prefs.getString("custom_linked_platforms", "") ?: ""
            text = if (s.isNotEmpty()) "🔗 Extra Linked: $s" else "कोई अतिरिक्त प्लेटफॉर्म लिंक नहीं है।"
            setTextColor(textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, 0)
        }

        val addCustomBtn = createPrimaryButton("➕ Add", accentRoyalBlue) {
            val input = createCleanInput(prefs.getString("custom_linked_platforms", "Facebook Reels") ?: "", "नए ऐप/वेबसाइट का नाम")
            AlertDialog.Builder(this@MainActivity, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
                .setTitle("➕ नया अपलोड प्लेटफॉर्म लिंक करें")
                .setView(input as View)
                .setPositiveButton("Save") { _, _ ->
                    val v = input.text.toString().trim()
                    prefs.edit().putString("custom_linked_platforms", v).apply()
                    customStatusTv.text = if (v.isNotEmpty()) "🔗 Extra Linked: $v" else "कोई अतिरिक्त प्लेटफॉर्म लिंक नहीं है।"
                }
                .setNegativeButton("रद्द करें", null)
                .show()
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
        val input = createCleanInput("", "जैसे: 'रोज़ शाम 7:30 बजे 2 पार्ट में स्टोरी वीडियो बनाकर YouTube और Insta पर डालो'").apply { minLines = 3 }
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle("🤖 Mini Task AI Configurator")
            .setView(input as View)
            .setPositiveButton("⚡ ऑटो-सेट करें") { _, _ ->
                val userWish = input.text.toString().trim()
                if (userWish.isEmpty()) return@setPositiveButton
                uiScope.launch {
                    val plan = getActiveBrain().chatAndBuildDetailedPlan("Extract video topic from: $userWish", "", "")
                    topicField.setText(plan.optString("taskTitle", userWish).ifEmpty { userWish })
                    Regex("(\\d{1,2})[:.](\\d{2})").find(userWish)?.let {
                        prefs.edit().putString("schedule_time_hm", it.value.replace(".", ":")).putBoolean("scheduler_enabled", true).apply()
                    }
                    if (userWish.contains("story", true) || userWish.contains("कहानी") || userWish.contains("पार्ट")) {
                        prefs.edit().putBoolean("flow_story_mode", true).apply()
                        countField.setText("2")
                    }
                    if (userWish.contains("insta", true) || userWish.contains("इंस्टा")) {
                        prefs.edit().putBoolean("insta_link_enabled", true).apply()
                    }
                    Toast.makeText(this@MainActivity, "✅ AI ने सेटिंग्स अपडेट कर दीं!", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("बंद करें", null)
            .show()
    }

    private fun buildTab3FullControlBrowser(): View {
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
        fun navBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { onClick() }
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
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.parseColor("#181B20"))
        }
        toolStrip.addView(navBtn("🔑 Gmail") { studioWebView?.loadUrl("https://accounts.google.com") })
        toolStrip.addView(navBtn("🎬 Flow") { studioWebView?.loadUrl("https://labs.google/fx/tools/flow") })
        toolStrip.addView(navBtn("📺 YT Studio") { studioWebView?.loadUrl("https://studio.youtube.com") })
        toolStrip.addView(navBtn("🖥️ Desktop") {
            isDesktopMode = !isDesktopMode
            studioWebView?.settings?.userAgentString = if (isDesktopMode)
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            else
                "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            studioWebView?.reload()
        })
        toolStrip.addView(navBtn("📌 Save") {
            prefs.edit().putString("saved_flow_url", studioWebView?.url ?: "https://labs.google/fx/tools/flow").apply()
            CookieManager.getInstance().flush()
            Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
        })

        studioWebView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    urlInput.setText(url ?: "")
                    CookieManager.getInstance().flush()
                }
            }
            webChromeClient = WebChromeClient()
            setDownloadListener(DownloadListener { url, _, _, _, _ -> downloadedFilesLog.add(0, url) })
            loadUrl(prefs.getString("saved_flow_url", "https://labs.google/fx/tools/flow") ?: "https://labs.google/fx/tools/flow")
        }
        container.addView(navBar)
        container.addView(toolStrip)
        container.addView(studioWebView)
        return container
    }

    private fun buildTab4MemoryAndVideoTrainer(): View {
        val vault = getActiveVault()
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        val trainerCard = createSectionCard("📹 Frame-by-Frame Video Trainer (3.1 Pro + Robotics)")
        val catInput = createCleanInput("Instagram", "App Folder Name")
        val noteInput = createCleanInput(selectedTrainingNote, "निर्देश लिखें...")
        val pickVideoBtn = createPrimaryButton("📤 Upload Screen Recording Video & Train", accentRoyalBlue) {
            selectedTrainingCategory = catInput.text.toString().trim().ifEmpty { "General" }
            selectedTrainingNote = noteInput.text.toString().trim().ifEmpty { "Learn exact step coordinates" }
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "video/*" }, REQ_PICK_TRAINING_VIDEO)
        }
        trainerCard.addView(catInput)
        trainerCard.addView(spacer(dp(8)))
        trainerCard.addView(noteInput)
        trainerCard.addView(spacer(dp(12)))
        trainerCard.addView(pickVideoBtn)

        val videoSessionsCard = createSectionCard("🎬 Uploaded Video Training Sessions")
        videoSessionsListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        videoSessionsCard.addView(videoSessionsListLayout)

        val runsCard = createSectionCard("🖼️ Automation Runs History (1-2-3 Screenshot Editor)")
        automationRunsListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        runsCard.addView(automationRunsListLayout)

        val foldersCard = createSectionCard("📁 App-Wise Confirmed Laws (LIVE_CODE_RULEs)")
        memoryCategoriesLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        foldersCard.addView(memoryCategoriesLayout)

        val profileCard = createSectionCard("🔐 Personal Profile Memory")
        val profileInput = createCleanInput(vault.getUserProfileRules(), "अपना नाम, ईमेल या नियम लिखें...").apply { minLines = 3 }
        profileCard.addView(profileInput)
        profileCard.addView(spacer(dp(10)))
        profileCard.addView(createPrimaryButton("💾 Save Personal Memory", accentEmerald) {
            getActiveVault().saveUserProfileRules(profileInput.text.toString())
            Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
        })

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
            memoryCategoriesLayout.addView(TextView(this).apply {
                text = "अभी कोई Learned Fix नहीं है।"
                setTextColor(textMuted)
            })
            return
        }
        for ((cat, count) in summary) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBox(Color.parseColor("#262B36"), dp(10), borderSubtle)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            }
            val topHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            topHeader.addView(TextView(this).apply {
                text = "📂 Memory/$cat/ ($count Laws)"
                setTextColor(textPureWhite)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            topHeader.addView(TextView(this).apply {
                text = "🗑️ Clear"
                setTextColor(Color.parseColor("#EF4444"))
                setOnClickListener {
                    vault.clearCategoryMemory(cat)
                    refreshMemoryEverything()
                }
            })
            row.addView(topHeader)
            vault.recallFixesForApp(cat).take(8).forEach { f ->
                val nx = (f.tapXRatio * 1000).toInt()
                val ny = (f.tapYRatio * 1000).toInt()
                row.addView(TextView(this).apply {
                    text = " • [${f.sourceType}] ${f.problemDescription} ➔ ${f.solutionRule} (XY: $nx, $ny)"
                    setTextColor(textMuted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                })
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
            automationRunsListLayout.addView(TextView(this).apply {
                text = "अभी कोई Automation Run सेव नहीं हुआ है।"
                setTextColor(textMuted)
            })
            return
        }
        for (run in runs) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBox(cardSlate, dp(10), borderSubtle)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            }
            card.addView(TextView(this).apply {
                text = "📌 ${run.title} [${run.status}] (${run.screenshotPaths.size} Screenshots)"
                setTextColor(if (run.status == "SUCCESS") accentEmerald else accentAmber)
                typeface = Typeface.DEFAULT_BOLD
            })

            if (run.screenshotPaths.isNotEmpty()) {
                val hScroll = HorizontalScrollView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(116)).apply { topMargin = dp(6) }
                }
                val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                run.screenshotPaths.forEachIndexed { idx, path ->
                    val bmp = vault.loadBitmapFromPath(path) ?: return@forEachIndexed
                    val stepDesc = run.stepsSummary.getOrNull(idx) ?: "Step #${idx + 1}"
                    val img = ImageView(this).apply {
                        setImageBitmap(bmp)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = roundedBox(bgMatteDark, dp(6), accentRoyalBlue)
                        setPadding(dp(2), dp(2), dp(2), dp(2))
                        layoutParams = LinearLayout.LayoutParams(dp(68), dp(110)).apply { marginEnd = dp(8) }
                        setOnClickListener {
                            openMultiPointEditableScreenshotDialog(bmp, run.appCategory, "Step #${idx + 1}: $stepDesc")
                        }
                    }
                    strip.addView(img)
                }
                hScroll.addView(strip)
                card.addView(hScroll)
            }

            card.addView(createPrimaryButton("💬 1-2-3 पॉइंट & चैट सुधार", accentRoyalBlue) {
                openAutomationRunChatDialog(run)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            })
            automationRunsListLayout.addView(card)
        }
    }

    private fun openAutomationRunChatDialog(runRecord: AutomationRunRecord) {
        val vault = getActiveVault()
        val brain = getActiveBrain()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(bgMatteDark)
        }
        val input = createCleanInput("", "भाई, यहाँ समझाओ क्या गलती हुई...")

        if (runRecord.screenshotPaths.isNotEmpty()) {
            dialogView.addView(TextView(this).apply {
                text = "👆 किसी भी स्क्रीनशॉट पर टैप करके **1-2-3 Multi-Point Editor** खोलें:"
                setTextColor(accentAmber)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, 0, 0, dp(4))
            })
            val hScroll = HorizontalScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(122))
            }
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            runRecord.screenshotPaths.forEachIndexed { idx, path ->
                val bmp = vault.loadBitmapFromPath(path) ?: return@forEachIndexed
                val stepInfo = runRecord.stepsSummary.getOrNull(idx) ?: "Step #${idx + 1}"
                val img = ImageView(this).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = roundedBox(cardSlate, dp(6), accentRoyalBlue)
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    layoutParams = LinearLayout.LayoutParams(dp(70), dp(116)).apply { marginEnd = dp(8) }
                    setOnClickListener {
                        openMultiPointEditableScreenshotDialog(bmp, runRecord.appCategory, "Step #${idx + 1}: $stepInfo")
                    }
                }
                strip.addView(img)
            }
            hScroll.addView(strip)
            dialogView.addView(hScroll)
        }

        val msgListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chatScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190), 1f)
            addView(msgListLayout)
        }
        fun appendMsg(s: String, m: String) {
            msgListLayout.addView(TextView(this).apply {
                text = "$s: $m"
                setTextColor(if (s == "User") Color.parseColor("#60A5FA") else accentEmerald)
                setPadding(0, dp(4), 0, dp(4))
            })
        }
        appendMsg("3.1 Pro + Robotics", "मैंने '${runRecord.title}' के स्क्रीनशॉट्स लोड कर लिए हैं। फोटो पर टैप करके 1-2-3 पॉइंट लगाएं या नीचे नियम लिखें!")
        runRecord.chatHistory.forEach { appendMsg("History", it) }

        val sendBtn = createPrimaryButton("Send ➔", accentRoyalBlue) { btn ->
            val userText = input.text.toString().trim()
            if (userText.isEmpty()) return@createPrimaryButton
            input.setText("")
            btn.isEnabled = false
            appendMsg("User", userText)
            vault.appendChatToAutomationRun(runRecord.runId, "User", userText)
            uiScope.launch(Dispatchers.IO) {
                val imgs = vault.loadSavedImagesAsBase64(runRecord.screenshotPaths, 6)
                val rep = brain.chatAboutAutomationRunWith38Flash(runRecord, userText, imgs)
                vault.appendChatToAutomationRun(runRecord.runId, "Gemini 3.8", rep.replyText)
                vault.saveStuckOrHumanFix(
                    category = runRecord.appCategory,
                    screenSignature = "run_${runRecord.runId}",
                    problemDescription = rep.extractedProblem,
                    solutionRule = "MANDATORY LIVE RULE: ${rep.extractedSolutionRule}",
                    tapXRatio = rep.tapXRatio,
                    tapYRatio = rep.tapYRatio,
                    sourceType = "LIVE_CODE_RULE"
                )
                withContext(Dispatchers.Main) {
                    btn.isEnabled = true
                    appendMsg("Gemini 3.8", rep.replyText)
                    refreshMemoryEverything()
                }
            }
        }
        dialogView.addView(chatScroll)
        dialogView.addView(input)
        dialogView.addView(spacer(dp(6)))
        dialogView.addView(sendBtn)

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle("💬 Fix Run: ${runRecord.title}")
            .setView(dialogView as View)
            .setPositiveButton("बंद करें", null)
            .show()
    }

    private fun refreshVideoTrainingSessionsList() {
        if (!::videoSessionsListLayout.isInitialized) return
        videoSessionsListLayout.removeAllViews()
        val sessions = getActiveVault().getAllVideoTrainingSessions()
        if (sessions.isEmpty()) {
            videoSessionsListLayout.addView(TextView(this).apply {
                text = "अभी कोई वीडियो ट्रेनिंग सेशन नहीं है।"
                setTextColor(textMuted)
            })
            return
        }
        for (session in sessions) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBox(cardSlate, dp(10), borderSubtle)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            }
            card.addView(TextView(this).apply {
                text = "📹 ${session.appCategory} (${session.timestamp})"
                setTextColor(accentRoyalBlue)
                typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(createPrimaryButton("💬 इस वीडियो पर Gemini 3.8 से चैट करें", accentEmerald) {
                openVideoSessionChatDialog(session)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            })
            videoSessionsListLayout.addView(card)
        }
    }

    private fun openVideoSessionChatDialog(session: VideoTrainingSession) {
        val vault = getActiveVault()
        val brain = getActiveBrain()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(bgMatteDark)
        }
        val input = createCleanInput("", "वीडियो के बारे में सुधार बताएं...")
        val msgListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chatScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220), 1f)
            addView(msgListLayout)
        }
        fun appendMsg(s: String, m: String) {
            msgListLayout.addView(TextView(this).apply {
                text = "$s: $m"
                setTextColor(if (s == "User") Color.parseColor("#60A5FA") else accentEmerald)
                setPadding(0, dp(4), 0, dp(4))
            })
        }
        appendMsg("Gemini 3.8", "मैंने इस वीडियो के ${session.framePaths.size} फ्रेम्स देख लिए हैं।")

        val sendBtn = createPrimaryButton("Send ➔", accentRoyalBlue) { btn ->
            val userText = input.text.toString().trim()
            if (userText.isEmpty()) return@createPrimaryButton
            input.setText("")
            btn.isEnabled = false
            appendMsg("User", userText)
            uiScope.launch(Dispatchers.IO) {
                val frames = vault.loadSavedImagesAsBase64(session.framePaths, 10)
                val rep = brain.chatAboutTrainingVideoWith38Flash(session, userText, frames)
                vault.appendChatToVideoSession(session.sessionId, "Gemini 3.8", rep.replyText, rep.extractedSolutionRule)
                vault.saveStuckOrHumanFix(
                    category = session.appCategory,
                    screenSignature = "vid_${session.sessionId}",
                    problemDescription = rep.extractedProblem,
                    solutionRule = "MANDATORY LIVE RULE: ${rep.extractedSolutionRule}",
                    tapXRatio = rep.tapXRatio,
                    tapYRatio = rep.tapYRatio,
                    sourceType = "VIDEO_CHAT"
                )
                withContext(Dispatchers.Main) {
                    btn.isEnabled = true
                    appendMsg("Gemini 3.8", rep.replyText)
                    refreshMemoryEverything()
                }
            }
        }
        dialogView.addView(chatScroll)
        dialogView.addView(input)
        dialogView.addView(spacer(dp(6)))
        dialogView.addView(sendBtn)

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle("💬 Video Chat: ${session.appCategory}")
            .setView(dialogView as View)
            .setPositiveButton("बंद करें", null)
            .show()
    }

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
            if (isStealth) "👻 Stealth Mode (गायब मोड): ON (बॉट अदृश्य रहेगा, पर बैकग्राउंड काम चलते रहेंगे)"
            else "👀 Stealth Mode (गायब मोड): OFF (चंचल बॉट कोने में हल्का दिखेगा)",
            if (isStealth) accentPurple else Color.parseColor("#334155")
        ) { btn ->
            isStealth = !isStealth
            prefs.edit().putBoolean("hud_stealth_mode", isStealth).putBoolean("stealth_hide_bot", isStealth).apply()
            btn.text = if (isStealth) "👻 Stealth Mode (गायब मोड): ON (बॉट अदृश्य रहेगा, पर बैकग्राउंड काम चलते रहेंगे)"
            else "👀 Stealth Mode (गायब मोड): OFF (चंचल बॉट कोने में हल्का दिखेगा)"
            btn.background = roundedBox(if (isStealth) accentPurple else Color.parseColor("#334155"), dp(12))
            AutoTouchService.instance?.hud?.applyUserTransparencyAndStealthSettings()
        }

        var greetingsOn = prefs.getBoolean("hud_greetings_enabled", true)
        val greetingsToggleBtn = createPrimaryButton(
            if (greetingsOn) "☀️ Good Morning / Good Night अपडेट्स: ON" else "🔕 Good Morning / Good Night अपडेट्स: OFF",
            if (greetingsOn) accentEmerald else Color.parseColor("#475569")
        ) { btn ->
            greetingsOn = !greetingsOn
            prefs.edit().putBoolean("hud_greetings_enabled", greetingsOn).putBoolean("bot_greetings_enabled", greetingsOn).apply()
            btn.text = if (greetingsOn) "☀️ Good Morning / Good Night अपडेट्स: ON" else "🔕 Good Morning / Good Night अपडेट्स: OFF"
            btn.background = roundedBox(if (greetingsOn) accentEmerald else Color.parseColor("#475569"), dp(12))
        }

        val currentAlphaPct = ((prefs.getFloat("hud_idle_alpha", 0.35f)) * 100).toInt().coerceIn(5, 100)
        val alphaLabel = TextView(this).apply {
            text = "🎚️ कोने में छुपने पर बॉट की पारदर्शिता (Edge Transparency): $currentAlphaPct%"
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
                    prefs.edit().putFloat("hud_idle_alpha", pct / 100f).putInt("hud_idle_alpha_percent", pct).apply()
                    AutoTouchService.instance?.hud?.applyUserTransparencyAndStealthSettings()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        val testEdgePeekBtn = createPrimaryButton("✨ अभी कोने से 'Good Morning' झलक टेस्ट करें", accentRoyalBlue) {
            val svc = AutoTouchService.instance
            if (svc != null) {
                svc.hud.showHud()
                Toast.makeText(this, "☀️ Good Morning भाई! Have a good luck day!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "पहले Accessibility ऑन करें!", Toast.LENGTH_SHORT).show()
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
        logControlsRow.addView(createPrimaryButton("🔄 Refresh", accentRoyalBlue) { refreshEngineTabDetails() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        })
        logControlsRow.addView(createPrimaryButton("📋 Copy", Color.parseColor("#334155")) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("logs", liveLogsTextView.text))
            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        })
        logControlsRow.addView(createPrimaryButton("🗑️ Clear", Color.parseColor("#7F1D1D")) {
            synchronized(AutoTouchService.liveLogs) { AutoTouchService.liveLogs.clear() }
            getActiveVault().clearPersistentLogs()
            refreshEngineTabDetails()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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

        val apiCard = createSectionCard("🔑 Gemini API & 3.5–3.8 Ladder")
        val keyInput = createCleanInput(prefs.getString("gemini_api_key", "") ?: "", "Paste Gemini API Key")
        val saveKeyBtn = createPrimaryButton("💾 Save API Key", accentRoyalBlue) {
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

        val bodyCard = createSectionCard("🦾 Calibrated Phone Body & Permissions")
        calibratedSpecsTextView = TextView(this).apply {
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        bodyCard.addView(calibratedSpecsTextView)
        bodyCard.addView(spacer(dp(10)))
        bodyCard.addView(createPrimaryButton("🪟 1. Grant Floating Overlay Permission", Color.parseColor("#334155")) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                AutoTouchService.instance?.hud?.showHud()
            }
        })
        bodyCard.addView(spacer(dp(8)))
        bodyCard.addView(createPrimaryButton("♿ 2. Enable Accessibility Service", accentRoyalBlue) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        bodyCard.addView(spacer(dp(8)))
        bodyCard.addView(createPrimaryButton("📁 3. Turn ON File Manager Access", Color.parseColor("#0D9488")) {
            requestStoragePermissionsOnDemand()
        })
        bodyCard.addView(spacer(dp(8)))
        bodyCard.addView(createPrimaryButton("🔋 4. Enable 24/7 Unstoppable Battery Mode", accentEmerald) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        })

        container.addView(stealthCard)
        container.addView(logsCard)
        container.addView(apiCard)
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
                "• Phone: ${specs.modelName} (${specs.screenWidth}×${specs.screenHeight} px)\n• Cutout Safe Zone: ${specs.cutoutSafeTop} px | Nav: ${specs.navBarHeight} px"
            } else {
                "Enable Accessibility Service below to calibrate your screen."
            }
        }
    }

    private fun refreshLiveLogsBox() {
        if (!::liveLogsTextView.isInitialized) return
        val inMemory = synchronized(AutoTouchService.liveLogs) { AutoTouchService.liveLogs.toList() }
        val lines = if (inMemory.isNotEmpty()) inMemory.take(45) else getActiveVault().getPersistentLogs().take(45)
        liveLogsTextView.text = if (lines.isEmpty()) "No logs yet." else lines.joinToString("\n")
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
                getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE).edit().putString("custom_download_folder", resolvedPath).apply()
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
                        Toast.makeText(this@MainActivity, "✅ Video Trained (${rules.size} Rules)!", Toast.LENGTH_LONG).show()
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

    private fun createSectionCard(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBox(cardSlate, dp(16), borderSubtle)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(textPureWhite)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(12))
            })
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(11), dp(12), dp(11))
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
            val bobY = sin(animPhase.toDouble()).toFloat() * dp(3) - (waveBoost * dp(6))
            val glanceX = sin((animPhase * 0.7f).toDouble()).toFloat() * dp(2)

            canvas.drawOval(cx - dp(18), h - dp(14), cx - dp(4), h - dp(4), bodyPaint)
            canvas.drawOval(cx + dp(4), h - dp(14), cx + dp(18), h - dp(4), bodyPaint)

            rect.set(cx - dp(24), h * 0.42f + bobY, cx + dp(24), h - dp(10) + bobY)
            canvas.drawRoundRect(rect, dp(22).toFloat(), dp(22).toFloat(), bodyPaint)
            canvas.drawRoundRect(rect, dp(22).toFloat(), dp(22).toFloat(), shadowPaint)

            val waveOffset = sin((animPhase * 2f).toDouble()).toFloat() * dp(6) + (waveBoost * dp(12))
            canvas.drawOval(cx - dp(34), h * 0.46f + bobY - waveOffset, cx - dp(20), h * 0.68f + bobY, bodyPaint)
            canvas.drawOval(cx + dp(20), h * 0.46f + bobY + waveOffset, cx + dp(34), h * 0.68f + bobY, bodyPaint)

            rect.set(cx - dp(26), dp(8).toFloat() + bobY, cx + dp(26), h * 0.46f + bobY)
            canvas.drawRoundRect(rect, dp(24).toFloat(), dp(24).toFloat(), bodyPaint)
            canvas.drawRoundRect(rect, dp(24).toFloat(), dp(24).toFloat(), shadowPaint)

            val eyeY = h * 0.26f + bobY
            canvas.drawCircle(cx - dp(15) + glanceX, eyeY + dp(5), dp(4).toFloat(), blushPaint)
            canvas.drawCircle(cx + dp(15) + glanceX, eyeY + dp(5), dp(4).toFloat(), blushPaint)

            val leftEyeX = cx - dp(10) + glanceX
            val rightEyeX = cx + dp(10) + glanceX
            val eyeR = if (isCuriousMood) dp(5).toFloat() else dp(4).toFloat()
            canvas.drawLine(leftEyeX, eyeY, rightEyeX, eyeY, mouthStrokePaint)
            canvas.drawCircle(leftEyeX, eyeY, eyeR, featurePaint)
            canvas.drawCircle(rightEyeX, eyeY, eyeR, featurePaint)

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
