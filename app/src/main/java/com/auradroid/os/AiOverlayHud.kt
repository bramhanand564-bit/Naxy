package com.auradroid.os

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot

enum class BotMood {
    IDLE,
    THINKING,
    WORKING,
    HAPPY,
    ERROR,
    WINK,
    STAR_EYES,
    HEART_EYES,
    SCANNING,
    TYPING,
    SEARCHING,
    SURPRISED,
    SLEEPY,
    COOL_SHADES,
    FOCUSED_SQUINT,
    DIZZY,
    ROCKET_LAUNCH,
    UPLOAD_UP,
    CELEBRATE,
    QUESTION
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class AiOverlayHud(
    private val context: Context,
    private val onStopClicked: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)

    private var podRootLayout: LinearLayout? = null
    private var podParams: WindowManager.LayoutParams? = null
    private var baymaxFaceView: BaymaxFaceView? = null

    private var notchCapsuleContainer: LinearLayout? = null
    private var statusTextView: TextView? = null
    private var stopButtonView: TextView? = null
    private var peekWebButton: TextView? = null
    private var notchProgressBar: ProgressBar? = null
    private var downloadBadgeView: TextView? = null
    private var dailyLogBadgeView: TextView? = null
    private var notchDetailsSection: LinearLayout? = null
    private var quickSnoozeRow: LinearLayout? = null

    private var webCardContainer: LinearLayout? = null
    private var backgroundWebView: WebView? = null
    private var isWebExpandedFull = false

    private var handOverlayView: BaymaxHandOverlayView? = null
    private var handOverlayParams: WindowManager.LayoutParams? = null

    private var isAdded = false
    private var isTaskActivelyRunning = false
    private var isNotchExpandedByUser = false
    private var isDockedAtEdge = false
    private var manualTapMoodIndex = 0
    private var currentProgressPercent = 0
    private var isVideoDownloadedInSession = false

    // Holds the video URI for 100% silent background WebView file-chooser injection (YouTube/Instagram web upload)
    var pendingUploadFileUri: Uri? = null
    var onQuickActionSelected: ((String) -> Unit)? = null

    private val chanchalPlayfulMoods = listOf(
        BotMood.IDLE,
        BotMood.WINK,
        BotMood.HAPPY,
        BotMood.STAR_EYES,
        BotMood.QUESTION,
        BotMood.COOL_SHADES,
        BotMood.SLEEPY
    )

    private val autoCollapseAndDockRunnable = Runnable {
        if (!isNotchExpandedByUser) {
            notchCapsuleContainer?.visibility = View.GONE
            if (webCardContainer?.visibility == View.VISIBLE && !isTaskActivelyRunning) {
                setWebCardVisibility(false)
            }
            if (!isTaskActivelyRunning) {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                baymaxFaceView?.setMood(if (hour >= 23 || hour < 5) BotMood.SLEEPY else BotMood.IDLE)
                dockBotToNearestCornerEdge()
            } else {
                applyUserTransparencyAndStealthSettings()
            }
        }
    }

    init {
        try {
            WebView.enableSlowWholeDocumentDraw()
        } catch (_: Exception) {}
    }

    private fun resolveBestOverlayType(): Int {
        if (context is AccessibilityService) {
            return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun addViewWithFallback(view: View, params: WindowManager.LayoutParams) {
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
            params.type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            windowManager.addView(view, params)
        }
    }

    /**
     * Strictly enforces FLAG_NOT_FOCUSABLE on the overlay window so the phone's Soft Keyboard (Gboard)
     * ALWAYS opens normally in Instagram, WhatsApp, Chrome, and AuraDroid!
     */
    fun enforceKeyboardFriendlyFocusFlags() {
        mainHandler.post {
            val params = podParams ?: return@post
            val root = podRootLayout ?: return@post
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            try {
                if (isAdded) windowManager.updateViewLayout(root, params)
            } catch (_: Exception) {}
        }
    }

    fun showHud() {
        mainHandler.post {
            if (!isAdded) {
                try {
                    createHandCursorOverlay()
                    createFloatingBaymaxPod()
                    isAdded = true
                } catch (_: Exception) {}
            }
            enforceKeyboardFriendlyFocusFlags()
            applyUserTransparencyAndStealthSettings()
            triggerGreetingPeekIfAllowed()
        }
    }

    fun hideHud() {
        mainHandler.post {
            mainHandler.removeCallbacks(autoCollapseAndDockRunnable)
            baymaxFaceView?.stopAnimations()
            handOverlayView?.stopAnimations()
            if (!isAdded) return@post
            try {
                podRootLayout?.let { windowManager.removeViewImmediate(it) }
            } catch (_: Exception) {}
            try {
                handOverlayView?.let { windowManager.removeViewImmediate(it) }
            } catch (_: Exception) {}
            isAdded = false
        }
    }

    /**
     * Applies user's Stealth Mode (Hide Bot while background tasks still run) and Custom Opacity/Transparency!
     * Supports both MainActivity and internal preference keys.
     */
    fun applyUserTransparencyAndStealthSettings() {
        mainHandler.post {
            val stealthHideBot = prefs.getBoolean("hud_stealth_mode", false) ||
                prefs.getBoolean("stealth_hide_bot", false) ||
                !prefs.getBoolean("show_bot_hud", true)

            val idleAlphaFloat = if (prefs.contains("hud_idle_alpha")) {
                prefs.getFloat("hud_idle_alpha", 0.38f).coerceIn(0.05f, 1.0f)
            } else {
                (prefs.getInt("hud_idle_alpha_percent", 38).coerceIn(5, 100)) / 100f
            }

            val targetAlpha = if (isNotchExpandedByUser) 1.0f else idleAlphaFloat

            if (stealthHideBot) {
                baymaxFaceView?.visibility = View.GONE
                notchCapsuleContainer?.visibility = View.GONE
            } else {
                baymaxFaceView?.visibility = View.VISIBLE
                baymaxFaceView?.alpha = targetAlpha
            }
            enforceKeyboardFriendlyFocusFlags()
        }
    }

    /**
     * Smoothly docks the bot into the left or right screen corner so only a subtle edge peeks out!
     */
    private fun dockBotToNearestCornerEdge() {
        val params = podParams ?: return
        val root = podRootLayout ?: return
        if (!isAdded) return

        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val hideOffset = dp(30)
        val targetX = if (params.x < screenW / 2) -hideOffset else (screenW - dp(26))

        isDockedAtEdge = true
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val startX = params.x
        ValueAnimator.ofInt(startX, targetX).apply {
            duration = 380L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                try {
                    if (isAdded) windowManager.updateViewLayout(root, params)
                } catch (_: Exception) {}
            }
            start()
        }
        applyUserTransparencyAndStealthSettings()
    }

    private fun undockBotFromEdgeToVisible() {
        val params = podParams ?: return
        val root = podRootLayout ?: return
        if (!isAdded) return

        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        isDockedAtEdge = false
        baymaxFaceView?.alpha = 1.0f
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val targetX = if (params.x < screenW / 2) dp(10) else (screenW - dp(68)).coerceAtLeast(dp(10))
        if (params.x < 0 || params.x > screenW - dp(56)) {
            params.x = targetX
            try {
                windowManager.updateViewLayout(root, params)
            } catch (_: Exception) {}
        }
    }

    /**
     * Peeks out from the corner to say Good Morning / Good Luck / Late Night Sleep greeting if enabled in Settings!
     */
    fun triggerGreetingPeekIfAllowed(customGreeting: String? = null) {
        mainHandler.post {
            val stealth = prefs.getBoolean("hud_stealth_mode", false) ||
                prefs.getBoolean("stealth_hide_bot", false) ||
                !prefs.getBoolean("show_bot_hud", true)
            val greetingsOn = prefs.getBoolean("hud_greetings_enabled", true) &&
                prefs.getBoolean("bot_greetings_enabled", true)
            if (stealth || !greetingsOn) return@post

            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val todayKey = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date()) + "_$hour"
            if (customGreeting == null) {
                val lastGreetKey = prefs.getString("last_greet_hour_key", "") ?: ""
                if (lastGreetKey == todayKey) return@post
                prefs.edit().putString("last_greet_hour_key", todayKey).apply()
            }

            val (msg, mood) = when {
                customGreeting != null -> Pair(customGreeting, BotMood.HAPPY)
                hour in 5..11 -> Pair("☀️ Good Morning भाई! Have a good luck day!", BotMood.HAPPY)
                hour in 12..16 -> Pair("🚀 All the best! आज के टास्क के लिए रेडी हूँ!", BotMood.WINK)
                hour in 17..21 -> Pair("✨ Good Evening भाई! बैकग्राउंड इंजन एक्टिव है।", BotMood.STAR_EYES)
                hour >= 22 || hour < 5 -> Pair("🌙 Late night हो गई भाई, आराम से सो जाओ! 😴", BotMood.SLEEPY)
                else -> Pair("👋 हाय भाई! सब बढ़िया चल रहा है!", BotMood.WINK)
            }

            undockBotFromEdgeToVisible()
            baymaxFaceView?.setMood(mood)
            statusTextView?.text = msg
            notchCapsuleContainer?.visibility = View.VISIBLE
            notchDetailsSection?.visibility = View.GONE

            mainHandler.removeCallbacks(autoCollapseAndDockRunnable)
            mainHandler.postDelayed(autoCollapseAndDockRunnable, 4200L)
        }
    }

    fun showScheduledTaskConfirmBanner(
        taskTitle: String,
        onRunNow: () -> Unit,
        onSkip10Min: () -> Unit,
        onRunWhenIdle: () -> Unit,
        onRunBackground: () -> Unit
    ) {
        mainHandler.post {
            if (!isAdded) showHud()
            undockBotFromEdgeToVisible()
            isNotchExpandedByUser = true
            baymaxFaceView?.setMood(BotMood.QUESTION)
            statusTextView?.text = "⏰ शेड्यूल टाइम: $taskTitle"

            quickSnoozeRow?.removeAllViews()
            fun pillBtn(label: String, bgHex: String, action: () -> Unit): TextView {
                return TextView(context).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(8), dp(5), dp(8), dp(5))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(bgHex))
                        cornerRadius = dp(8).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(5) }
                    setOnClickListener {
                        isNotchExpandedByUser = false
                        notchCapsuleContainer?.visibility = View.GONE
                        dockBotToNearestCornerEdge()
                        action()
                    }
                }
            }

            quickSnoozeRow?.addView(pillBtn("▶ अभी करें", "#10B981", onRunNow))
            quickSnoozeRow?.addView(pillBtn("⏳ Skip 10m", "#F59E0B", onSkip10Min))
            quickSnoozeRow?.addView(pillBtn("🌙 जब फोन खाली हो", "#3B82F6", onRunWhenIdle))
            quickSnoozeRow?.addView(pillBtn("🌐 Background", "#6366F1", onRunBackground))
            quickSnoozeRow?.visibility = View.VISIBLE

            notchCapsuleContainer?.visibility = View.VISIBLE
            notchDetailsSection?.visibility = View.VISIBLE
            enforceKeyboardFriendlyFocusFlags()
        }
    }

    private fun resolveSmartExpression20(statusText: String, baseMood: BotMood): BotMood {
        val s = statusText.lowercase(Locale.ROOT)
        return when {
            baseMood == BotMood.ERROR || s.contains("error") || s.contains("fail") -> BotMood.ERROR
            s.contains("retry") || s.contains("stuck") -> BotMood.DIZZY
            s.contains("complete") || s.contains("success") -> BotMood.CELEBRATE
            s.contains("capturing") || s.contains("scan") || s.contains("📸") -> BotMood.SCANNING
            s.contains("typing") || s.contains("paste") || s.contains("comment") || s.contains("caption") -> BotMood.TYPING
            s.contains("search") || s.contains("find") -> BotMood.SEARCHING
            s.contains("like") || s.contains("heart") -> BotMood.HEART_EYES
            s.contains("opening") || s.contains("launch") || s.contains("🚀") -> BotMood.ROCKET_LAUNCH
            s.contains("upload") || s.contains("download") || s.contains("📥") -> BotMood.UPLOAD_UP
            s.contains("viral") || s.contains("flow") || s.contains("✨") -> BotMood.STAR_EYES
            s.contains("watch") || s.contains("wait") || s.contains("generating") || s.contains("⏳") -> BotMood.QUESTION
            s.contains("next") || s.contains("send") || s.contains("share") -> BotMood.FOCUSED_SQUINT
            s.contains("stopped") || s.contains("paused") || s.contains("sleep") -> BotMood.SLEEPY
            else -> baseMood
        }
    }

    private fun updateNotchTelemetryFromStatus(statusText: String, taskRunning: Boolean) {
        val s = statusText.lowercase(Locale.ROOT)
        val todayStr = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date())
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        when {
            s.contains("starting") || s.contains("creating prompt") -> {
                currentProgressPercent = 15
                if (s.contains("flow")) isVideoDownloadedInSession = false
            }
            s.contains("submitting") -> currentProgressPercent = 32
            s.contains("generating") -> currentProgressPercent = (currentProgressPercent + 4).coerceIn(38, 78)
            s.contains("downloading") || s.contains("📥") -> {
                currentProgressPercent = 85
                isVideoDownloadedInSession = true
                prefs.edit()
                    .putString("last_download_status", "📥 Video Downloaded ✅ ($todayStr $timeStr)")
                    .apply()
            }
            s.contains("opening youtube") || s.contains("next") -> currentProgressPercent = 90
            s.contains("caption") || s.contains("upload short") -> currentProgressPercent = 95
            s.contains("success") || s.contains("complete") -> {
                currentProgressPercent = 100
                val summary = if (s.contains("upload")) {
                    "✅ $todayStr ($timeStr): Video Uploaded Successfully!"
                } else {
                    "✅ $todayStr ($timeStr): Task Completed!"
                }
                prefs.edit().putString("last_daily_action_log", summary).apply()
            }
            !taskRunning && s.contains("error") -> {
                prefs.edit().putString("last_daily_action_log", "⚠️ $todayStr ($timeStr): $statusText").apply()
            }
        }

        notchProgressBar?.progress = if (taskRunning) currentProgressPercent.coerceAtLeast(15) else 100
        val savedDl = prefs.getString("last_download_status", "📥 Video Download: अभी कोई नया डाउनलोड नहीं")
        downloadBadgeView?.text = if (isVideoDownloadedInSession) {
            "📥 Video Status: Downloaded & Ready ✅"
        } else {
            savedDl
        }

        val schedOn = prefs.getBoolean("scheduler_enabled", false)
        val schedTime = prefs.getString("schedule_time_hm", "19:00") ?: "19:00"
        val lastDaily = prefs.getString("last_daily_action_log", "📅 आज अभी कोई टास्क पूरा नहीं हुआ")
        dailyLogBadgeView?.text = if (schedOn) {
            "$lastDaily\n⏰ Daily Auto-Schedule: ON ($schedTime)"
        } else {
            "$lastDaily\n⏰ Daily Auto-Schedule: OFF"
        }
    }

    fun updateStatus(statusText: String, mood: BotMood, taskRunning: Boolean) {
        mainHandler.post {
            if (!isAdded) showHud()
            isTaskActivelyRunning = taskRunning
            val smartMood = resolveSmartExpression20(statusText, mood)
            baymaxFaceView?.setMood(smartMood)
            statusTextView?.text = statusText
            updateNotchTelemetryFromStatus(statusText, taskRunning)
            enforceKeyboardFriendlyFocusFlags()

            val stealth = prefs.getBoolean("hud_stealth_mode", false) ||
                prefs.getBoolean("stealth_hide_bot", false) ||
                !prefs.getBoolean("show_bot_hud", true)
            if (stealth) {
                baymaxFaceView?.visibility = View.GONE
                notchCapsuleContainer?.visibility = View.GONE
                return@post
            }

            if (smartMood == BotMood.CELEBRATE || mood == BotMood.HAPPY) {
                val dm = context.resources.displayMetrics
                handOverlayView?.triggerPlumpThumbsUp(dm.widthPixels / 2f, dm.heightPixels * 0.45f)
            }

            mainHandler.removeCallbacks(autoCollapseAndDockRunnable)
            if (!isDockedAtEdge) {
                notchCapsuleContainer?.visibility = View.VISIBLE
            }
            stopButtonView?.visibility = if (taskRunning) View.VISIBLE else View.GONE

            val hideDelay = if (taskRunning) 3000L else 2400L
            mainHandler.postDelayed(autoCollapseAndDockRunnable, hideDelay)
        }
    }

    fun animateHandTap(centerX: Float, centerY: Float) {
        mainHandler.post {
            val stealth = prefs.getBoolean("hud_stealth_mode", false) || prefs.getBoolean("stealth_hide_bot", false)
            if (!stealth) handOverlayView?.triggerPlumpHandTap(centerX, centerY)
        }
    }

    fun animateHandSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        mainHandler.post {
            val stealth = prefs.getBoolean("hud_stealth_mode", false) || prefs.getBoolean("stealth_hide_bot", false)
            if (!stealth) handOverlayView?.triggerPlumpHandSwipe(startX, startY, endX, endY)
        }
    }

    fun getOrCreateBackgroundWebView(): WebView? {
        if (backgroundWebView == null && !isAdded) {
            showHud()
        }
        enforceKeyboardFriendlyFocusFlags()
        return backgroundWebView
    }

    fun captureWebViewCanvasBitmap(): Bitmap? {
        val wv = backgroundWebView ?: return null
        return try {
            val targetW = if (wv.width > 100) wv.width else dp(340)
            val targetH = if (wv.height > 100) wv.height else dp(600)

            if (wv.width <= 100 || wv.height <= 100) {
                val wSpec = View.MeasureSpec.makeMeasureSpec(targetW, View.MeasureSpec.EXACTLY)
                val hSpec = View.MeasureSpec.makeMeasureSpec(targetH, View.MeasureSpec.EXACTLY)
                wv.measure(wSpec, hSpec)
                wv.layout(0, 0, targetW, targetH)
            }

            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            wv.draw(canvas)
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun createHandCursorOverlay() {
        handOverlayView = BaymaxHandOverlayView(context).apply {
            alpha = 0f
            isFocusable = false
        }
        val overlayType = resolveBestOverlayType()

        handOverlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        handOverlayView?.let { view ->
            handOverlayParams?.let { params ->
                addViewWithFallback(view, params)
            }
        }
    }

    private fun createFloatingBaymaxPod() {
        val overlayType = resolveBestOverlayType()

        podParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(10)
            y = dp(95)
        }

        // CRITICAL KEYBOARD FIX: Block descendant focus so no child view in the overlay ever steals Gboard!
        podRootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
        }

        baymaxFaceView = BaymaxFaceView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            isFocusable = false
        }

        notchCapsuleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            isFocusable = false
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EB111318"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            }
        }

        val capsuleHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusTextView = TextView(context).apply {
            text = "AuraDroid Ready"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            maxWidth = dp(190)
            maxLines = 2
        }

        peekWebButton = TextView(context).apply {
            text = " 👁️ Web "
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener {
                toggleWebCardVisibility()
            }
        }

        stopButtonView = TextView(context).apply {
            text = " ■ "
            setTextColor(Color.parseColor("#EF4444"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            visibility = View.GONE
            setPadding(dp(6), dp(2), dp(4), dp(2))
            setOnClickListener {
                onStopClicked()
            }
        }

        capsuleHeaderRow.addView(statusTextView)
        capsuleHeaderRow.addView(peekWebButton)
        capsuleHeaderRow.addView(stopButtonView)

        notchDetailsSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, dp(2))
        }

        notchProgressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 100
            layoutParams = LinearLayout.LayoutParams(dp(235), dp(5)).apply {
                bottomMargin = dp(6)
            }
        }

        downloadBadgeView = TextView(context).apply {
            text = "📥 Video Download: Ready"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, 0, 0, dp(3))
        }

        dailyLogBadgeView = TextView(context).apply {
            text = "📅 आज की कार्रवाई: Ready"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 0, dp(4))
        }

        quickSnoozeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(2))
        }

        fun quickModeChip(title: String, colorHex: String, modeKey: String): TextView {
            return TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(7), dp(4), dp(7), dp(4))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(colorHex))
                    cornerRadius = dp(7).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(5) }
                setOnClickListener {
                    when (modeKey) {
                        "SKIP_10M" -> {
                            val resumeAt = System.currentTimeMillis() + 600_000L
                            prefs.edit().putLong("snooze_until_ms", resumeAt).apply()
                            statusTextView?.text = "⏳ 10 मिनट के लिए स्किप किया गया!"
                            onStopClicked()
                        }
                        "WHEN_IDLE" -> {
                            prefs.edit().putBoolean("run_only_when_idle", true).apply()
                            statusTextView?.text = "🌙 फोन खाली (Idle) होने पर चलेगा!"
                        }
                        "BG_MODE" -> {
                            prefs.edit().putString("execution_mode", "BACKGROUND_WEB").apply()
                            statusTextView?.text = "🌐 Background Mode ON!"
                        }
                    }
                    onQuickActionSelected?.invoke(modeKey)
                }
            }
        }

        quickSnoozeRow?.addView(quickModeChip("⏳ Skip 10m", "#D97706", "SKIP_10M"))
        quickSnoozeRow?.addView(quickModeChip("🌙 जब फोन खाली हो", "#2563EB", "WHEN_IDLE"))
        quickSnoozeRow?.addView(quickModeChip("🌐 Background", "#059669", "BG_MODE"))

        notchDetailsSection?.addView(notchProgressBar)
        notchDetailsSection?.addView(downloadBadgeView)
        notchDetailsSection?.addView(dailyLogBadgeView)
        notchDetailsSection?.addView(quickSnoozeRow)

        notchCapsuleContainer?.addView(capsuleHeaderRow)
        notchCapsuleContainer?.addView(notchDetailsSection)

        topRow.addView(baymaxFaceView)
        topRow.addView(notchCapsuleContainer)

        webCardContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            isFocusable = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#121316"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(300), dp(420)).apply {
                topMargin = dp(8)
            }
        }

        val webHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }

        val webTitle = TextView(context).apply {
            text = "🎬 Live Background Workspace"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val expandBtn = TextView(context).apply {
            text = "[ ⛶ Full ]"
            setTextColor(Color.parseColor("#3B82F6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener {
                isWebExpandedFull = !isWebExpandedFull
                val lp = webCardContainer?.layoutParams as? LinearLayout.LayoutParams
                if (isWebExpandedFull) {
                    lp?.width = dp(350)
                    lp?.height = dp(620)
                    text = "[ ↙ Mini ]"
                } else {
                    lp?.width = dp(300)
                    lp?.height = dp(420)
                    text = "[ ⛶ Full ]"
                }
                webCardContainer?.layoutParams = lp
                enforceKeyboardFriendlyFocusFlags()
            }
        }

        val hideBtn = TextView(context).apply {
            text = "[ ✕ ]"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(6), dp(2), dp(4), dp(2))
            setOnClickListener {
                setWebCardVisibility(false)
            }
        }

        webHeader.addView(webTitle)
        webHeader.addView(expandBtn)
        webHeader.addView(hideBtn)

        // CRITICAL KEYBOARD FIX: Override onCheckIsTextEditor & requestFocus so backgroundWebView
        // never steals Android's InputMethodManager (Soft Keyboard) from Instagram, Chrome, or AuraDroid!
        backgroundWebView = object : WebView(context) {
            override fun onCheckIsTextEditor(): Boolean = false
            override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?): Boolean = false
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFocusable = false
            isFocusableInTouchMode = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    val uri = pendingUploadFileUri
                    return if (uri != null && filePathCallback != null) {
                        filePathCallback.onReceiveValue(arrayOf(uri))
                        true
                    } else {
                        super.onShowFileChooser(webView, filePathCallback, fileChooserParams)
                    }
                }
            }

            val wSpec = View.MeasureSpec.makeMeasureSpec(dp(340), View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(dp(600), View.MeasureSpec.EXACTLY)
            measure(wSpec, hSpec)
            layout(0, 0, dp(340), dp(600))
        }

        webCardContainer?.addView(webHeader)
        webCardContainer?.addView(backgroundWebView)

        podRootLayout?.addView(topRow)
        podRootLayout?.addView(webCardContainer)

        updateNotchTelemetryFromStatus("Ready", false)
        setupDragAndTapOnBaymaxFace()
        podRootLayout?.let { root ->
            podParams?.let { params ->
                addViewWithFallback(root, params)
            }
        }
    }

    private fun setWebCardVisibility(show: Boolean) {
        webCardContainer?.visibility = if (show) View.VISIBLE else View.GONE
        // CRITICAL KEYBOARD FIX: Always keep FLAG_NOT_FOCUSABLE even when Web Card is visible!
        enforceKeyboardFriendlyFocusFlags()
    }

    private fun toggleWebCardVisibility() {
        val currentlyVisible = webCardContainer?.visibility == View.VISIBLE
        setWebCardVisibility(!currentlyVisible)
    }

    private fun setupDragAndTapOnBaymaxFace() {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false

        baymaxFaceView?.setOnTouchListener { _, event ->
            val params = podParams ?: return@setOnTouchListener false
            val dm = context.resources.displayMetrics
            val maxX = (dm.widthPixels - dp(24)).coerceAtLeast(100)
            val maxY = (dm.heightPixels - dp(95)).coerceAtLeast(200)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mainHandler.removeCallbacks(autoCollapseAndDockRunnable)
                    baymaxFaceView?.alpha = 1.0f
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (hypot(dx.toDouble(), dy.toDouble()) > dp(8)) {
                        isDragging = true
                        isDockedAtEdge = false
                        params.x = (startX + dx).coerceIn(-dp(32), maxX)
                        params.y = (startY + dy).coerceIn(0, maxY)
                        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        try {
                            windowManager.updateViewLayout(podRootLayout, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onBaymaxFaceTapped()
                    } else {
                        mainHandler.postDelayed(autoCollapseAndDockRunnable, 4000L)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onBaymaxFaceTapped() {
        mainHandler.removeCallbacks(autoCollapseAndDockRunnable)
        undockBotFromEdgeToVisible()

        if (!isTaskActivelyRunning) {
            manualTapMoodIndex = (manualTapMoodIndex + 1) % chanchalPlayfulMoods.size
            baymaxFaceView?.setMood(chanchalPlayfulMoods[manualTapMoodIndex])
        }

        val isCapsuleVisible = notchCapsuleContainer?.visibility == View.VISIBLE
        val isDetailsVisible = notchDetailsSection?.visibility == View.VISIBLE

        if (isCapsuleVisible && isDetailsVisible) {
            isNotchExpandedByUser = false
            notchDetailsSection?.visibility = View.GONE
            notchCapsuleContainer?.visibility = View.GONE
            setWebCardVisibility(false)
            dockBotToNearestCornerEdge()
        } else {
            isNotchExpandedByUser = true
            updateNotchTelemetryFromStatus(statusTextView?.text?.toString() ?: "Ready", isTaskActivelyRunning)
            notchCapsuleContainer?.visibility = View.VISIBLE
            notchDetailsSection?.visibility = View.VISIBLE
            enforceKeyboardFriendlyFocusFlags()
            mainHandler.postDelayed({
                isNotchExpandedByUser = false
                notchDetailsSection?.visibility = View.GONE
                notchCapsuleContainer?.visibility = View.GONE
                if (!isTaskActivelyRunning) dockBotToNearestCornerEdge()
            }, 7000L)
        }
    }

    /**
     * Chanchal (Playful) 2x-Eye Baymax Face with a Tiny Cute Mouth!
     */
    private inner class BaymaxFaceView(context: Context) : View(context) {
        private var currentMood = BotMood.IDLE
        private var blinkScaleY = 1f
        private var gazeOffsetX = 0f
        private var gazeOffsetY = 0f
        private var pulsePhase = 0f
        private var chanchalTick = 0
        private var blinkAnimator: ValueAnimator? = null
        private var isRunningBlinkLoop = false

        private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F8FAFC")
            style = Paint.Style.FILL
            setShadowLayer(9f, 0f, 3f, Color.parseColor("#66000000"))
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
        }

        private val eyeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.FILL
        }

        private val eyeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.STROKE
            strokeWidth = dp(3.0f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val bridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.STROKE
            strokeWidth = dp(2.0f)
            strokeCap = Paint.Cap.ROUND
        }

        private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.STROKE
            strokeWidth = dp(2.0f)
            strokeCap = Paint.Cap.ROUND
        }

        private val reusablePath = Path()

        private val blinkRunnable = object : Runnable {
            override fun run() {
                if (!isRunningBlinkLoop) return
                chanchalTick++
                blinkAnimator?.cancel()
                blinkAnimator = ValueAnimator.ofFloat(1f, 0.06f, 1f).apply {
                    duration = 200L
                    addUpdateListener {
                        blinkScaleY = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }

                when (currentMood) {
                    BotMood.IDLE -> {
                        gazeOffsetX = when (chanchalTick % 4) {
                            1 -> dp(2.0f)
                            2 -> -dp(2.0f)
                            else -> 0f
                        }
                        gazeOffsetY = if (chanchalTick % 5 == 0) -dp(1.5f) else 0f
                    }
                    BotMood.THINKING, BotMood.SEARCHING -> {
                        gazeOffsetX = if (gazeOffsetX <= 0f) dp(2.2f) else -dp(2.2f)
                        gazeOffsetY = -dp(1.8f)
                    }
                    BotMood.TYPING, BotMood.SCANNING -> {
                        pulsePhase = if (pulsePhase == 0f) 1f else 0f
                    }
                    else -> {
                        gazeOffsetX = 0f
                        gazeOffsetY = 0f
                    }
                }

                val nextBlink = if (currentMood == BotMood.THINKING || currentMood == BotMood.TYPING) 1300L else 2600L
                mainHandler.postDelayed(this, nextBlink)
            }
        }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            isFocusable = false
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (!isRunningBlinkLoop) {
                isRunningBlinkLoop = true
                mainHandler.postDelayed(blinkRunnable, 1400L)
            }
        }

        override fun onDetachedFromWindow() {
            stopAnimations()
            super.onDetachedFromWindow()
        }

        fun stopAnimations() {
            isRunningBlinkLoop = false
            mainHandler.removeCallbacks(blinkRunnable)
            blinkAnimator?.cancel()
        }

        fun setMood(mood: BotMood) {
            currentMood = mood
            val solidColor = when (mood) {
                BotMood.IDLE, BotMood.WINK, BotMood.COOL_SHADES, BotMood.SURPRISED -> Color.parseColor("#0F172A")
                BotMood.THINKING, BotMood.SEARCHING, BotMood.QUESTION -> Color.parseColor("#2563EB")
                BotMood.SCANNING, BotMood.TYPING -> Color.parseColor("#0284C7")
                BotMood.WORKING, BotMood.HAPPY, BotMood.FOCUSED_SQUINT,
                BotMood.UPLOAD_UP, BotMood.CELEBRATE -> Color.parseColor("#10B981")
                BotMood.STAR_EYES, BotMood.ROCKET_LAUNCH -> Color.parseColor("#D97706")
                BotMood.SLEEPY -> Color.parseColor("#475569")
                BotMood.DIZZY -> Color.parseColor("#EA580C")
                BotMood.ERROR, BotMood.HEART_EYES -> Color.parseColor("#DC2626")
            }

            eyeFillPaint.color = solidColor
            eyeStrokePaint.color = solidColor
            bridgePaint.color = solidColor
            mouthPaint.color = solidColor
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val headRadius = (width.coerceAtMost(height) / 2f) - dp(3.2f)

            canvas.drawCircle(cx, cy, headRadius, headPaint)
            canvas.drawCircle(cx, cy, headRadius, borderPaint)

            val eyeSpacing = headRadius * 0.43f
            val r = headRadius * 0.32f
            val lx = cx - eyeSpacing + gazeOffsetX
            val rx = cx + eyeSpacing + gazeOffsetX
            val ey = cy - dp(2.5f) + gazeOffsetY

            if (currentMood != BotMood.COOL_SHADES) {
                canvas.drawLine(lx + r * 0.55f, ey, rx - r * 0.55f, ey, bridgePaint)
            }

            when (currentMood) {
                BotMood.IDLE -> {
                    canvas.save()
                    canvas.scale(1f, blinkScaleY, cx, ey)
                    canvas.drawCircle(lx, ey, r, eyeFillPaint)
                    canvas.drawCircle(rx, ey, r, eyeFillPaint)
                    canvas.restore()
                }
                BotMood.THINKING, BotMood.QUESTION -> {
                    canvas.save()
                    canvas.scale(1f, blinkScaleY, cx, ey)
                    canvas.drawOval(RectF(lx - r, ey - r * 0.72f, lx + r, ey + r * 0.72f), eyeFillPaint)
                    canvas.drawCircle(rx, ey - dp(1.2f), r * 1.05f, eyeFillPaint)
                    canvas.restore()
                }
                BotMood.WORKING, BotMood.FOCUSED_SQUINT -> {
                    canvas.save()
                    canvas.scale(1f, blinkScaleY, cx, ey)
                    drawHeroicEye(canvas, lx, ey, r, isLeft = true)
                    drawHeroicEye(canvas, rx, ey, r, isLeft = false)
                    canvas.restore()
                }
                BotMood.HAPPY, BotMood.CELEBRATE -> {
                    val arcL = RectF(lx - r, ey - r * 0.8f, lx + r, ey + r)
                    val arcR = RectF(rx - r, ey - r * 0.8f, rx + r, ey + r)
                    canvas.drawArc(arcL, 195f, 150f, false, eyeStrokePaint)
                    canvas.drawArc(arcR, 195f, 150f, false, eyeStrokePaint)
                }
                BotMood.ERROR, BotMood.DIZZY -> {
                    val d = r * 0.70f
                    canvas.drawLine(lx - d, ey - d, lx + d, ey + d, eyeStrokePaint)
                    canvas.drawLine(lx + d, ey - d, lx - d, ey + d, eyeStrokePaint)
                    canvas.drawLine(rx - d, ey - d, rx + d, ey + d, eyeStrokePaint)
                    canvas.drawLine(rx + d, ey - d, rx - d, ey + d, eyeStrokePaint)
                }
                BotMood.WINK -> {
                    canvas.drawCircle(lx, ey, r, eyeFillPaint)
                    val arcR = RectF(rx - r, ey - r * 0.8f, rx + r, ey + r)
                    canvas.drawArc(arcR, 195f, 150f, false, eyeStrokePaint)
                }
                BotMood.STAR_EYES, BotMood.ROCKET_LAUNCH -> {
                    drawFourPointStar(canvas, lx, ey, r * 1.12f)
                    drawFourPointStar(canvas, rx, ey, r * 1.12f)
                }
                BotMood.HEART_EYES -> {
                    drawSolidHeart(canvas, lx, ey, r * 1.08f)
                    drawSolidHeart(canvas, rx, ey, r * 1.08f)
                }
                BotMood.SCANNING, BotMood.SEARCHING, BotMood.SURPRISED -> {
                    eyeStrokePaint.strokeWidth = dp(2.2f)
                    canvas.drawCircle(lx, ey, r, eyeStrokePaint)
                    canvas.drawCircle(rx, ey, r, eyeStrokePaint)
                    val innerR = if (pulsePhase > 0.5f) r * 0.50f else r * 0.35f
                    canvas.drawCircle(lx, ey, innerR, eyeFillPaint)
                    canvas.drawCircle(rx, ey, innerR, eyeFillPaint)
                    eyeStrokePaint.strokeWidth = dp(3.0f)
                }
                BotMood.TYPING -> {
                    val h = r * 0.52f
                    val shift = if (pulsePhase > 0.5f) dp(1.5f) else -dp(1.5f)
                    canvas.drawRoundRect(RectF(lx - r + shift, ey - h, lx + r + shift, ey + h), dp(4f), dp(4f), eyeFillPaint)
                    canvas.drawRoundRect(RectF(rx - r + shift, ey - h, rx + r + shift, ey + h), dp(4f), dp(4f), eyeFillPaint)
                }
                BotMood.SLEEPY -> {
                    val arcL = RectF(lx - r, ey - r, lx + r, ey + r * 0.8f)
                    val arcR = RectF(rx - r, ey - r, rx + r, ey + r * 0.8f)
                    canvas.drawArc(arcL, 15f, 150f, false, eyeStrokePaint)
                    canvas.drawArc(arcR, 15f, 150f, false, eyeStrokePaint)
                }
                BotMood.COOL_SHADES -> {
                    val visorH = r * 0.82f
                    canvas.drawLine(cx - headRadius * 0.85f, ey - visorH * 0.3f, cx + headRadius * 0.85f, ey - visorH * 0.3f, eyeStrokePaint)
                    canvas.drawRoundRect(RectF(lx - r * 1.08f, ey - visorH * 0.5f, lx + r * 1.02f, ey + visorH), dp(3f), dp(3f), eyeFillPaint)
                    canvas.drawRoundRect(RectF(rx - r * 1.02f, ey - visorH * 0.5f, rx + r * 1.08f, ey + visorH), dp(3f), dp(3f), eyeFillPaint)
                }
                BotMood.UPLOAD_UP -> {
                    drawUpArrowEye(canvas, lx, ey, r)
                    drawUpArrowEye(canvas, rx, ey, r)
                }
            }

            drawTinyCuteMouth(canvas, cx + gazeOffsetX * 0.5f, cy + headRadius * 0.42f, headRadius * 0.22f)
        }

        private fun drawTinyCuteMouth(canvas: Canvas, mx: Float, my: Float, mw: Float) {
            when (currentMood) {
                BotMood.HAPPY, BotMood.CELEBRATE, BotMood.WINK, BotMood.STAR_EYES, BotMood.HEART_EYES -> {
                    val mRect = RectF(mx - mw, my - mw * 0.7f, mx + mw, my + mw * 0.7f)
                    canvas.drawArc(mRect, 15f, 150f, false, mouthPaint)
                }
                BotMood.THINKING, BotMood.QUESTION, BotMood.SCANNING, BotMood.SURPRISED -> {
                    canvas.drawCircle(mx, my, mw * 0.45f, mouthPaint)
                }
                BotMood.ERROR, BotMood.DIZZY -> {
                    val mRect = RectF(mx - mw * 0.85f, my - mw * 0.3f, mx + mw * 0.85f, my + mw * 0.9f)
                    canvas.drawArc(mRect, 200f, 140f, false, mouthPaint)
                }
                BotMood.SLEEPY -> {
                    canvas.drawCircle(mx, my, mw * 0.32f, eyeFillPaint)
                }
                else -> {
                    val mRect = RectF(mx - mw * 0.75f, my - mw * 0.5f, mx + mw * 0.75f, my + mw * 0.5f)
                    canvas.drawArc(mRect, 20f, 140f, false, mouthPaint)
                }
            }
        }

        private fun drawHeroicEye(canvas: Canvas, x: Float, y: Float, r: Float, isLeft: Boolean) {
            reusablePath.reset()
            reusablePath.addCircle(x, y, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(reusablePath)
            canvas.drawCircle(x, y, r, eyeFillPaint)
            reusablePath.reset()
            if (isLeft) {
                reusablePath.moveTo(x - r * 1.2f, y - r * 1.2f)
                reusablePath.lineTo(x + r * 1.2f, y - r * 0.35f)
                reusablePath.lineTo(x + r * 1.2f, y - r * 1.2f)
            } else {
                reusablePath.moveTo(x - r * 1.2f, y - r * 0.35f)
                reusablePath.lineTo(x + r * 1.2f, y - r * 1.2f)
                reusablePath.lineTo(x - r * 1.2f, y - r * 1.2f)
            }
            reusablePath.close()
            canvas.drawPath(reusablePath, headPaint)
            canvas.restore()
        }

        private fun drawFourPointStar(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val inner = r * 0.35f
            reusablePath.reset()
            reusablePath.moveTo(cx, cy - r)
            reusablePath.lineTo(cx + inner, cy - inner)
            reusablePath.lineTo(cx + r, cy)
            reusablePath.lineTo(cx + inner, cy + inner)
            reusablePath.lineTo(cx, cy + r)
            reusablePath.lineTo(cx - inner, cy + inner)
            reusablePath.lineTo(cx - r, cy)
            reusablePath.lineTo(cx - inner, cy - inner)
            reusablePath.close()
            canvas.drawPath(reusablePath, eyeFillPaint)
        }

        private fun drawSolidHeart(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            reusablePath.reset()
            val topY = cy - r * 0.35f
            reusablePath.moveTo(cx, cy + r * 0.85f)
            reusablePath.cubicTo(cx - r * 1.3f, cy + r * 0.1f, cx - r * 0.9f, cy - r * 0.95f, cx, topY)
            reusablePath.cubicTo(cx + r * 0.9f, cy - r * 0.95f, cx + r * 1.3f, cy + r * 0.1f, cx, cy + r * 0.85f)
            reusablePath.close()
            canvas.drawPath(reusablePath, eyeFillPaint)
        }

        private fun drawUpArrowEye(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            canvas.drawLine(cx, cy + r * 0.8f, cx, cy - r * 0.75f, eyeStrokePaint)
            canvas.drawLine(cx - r * 0.65f, cy - r * 0.1f, cx, cy - r * 0.8f, eyeStrokePaint)
            canvas.drawLine(cx + r * 0.65f, cy - r * 0.1f, cx, cy - r * 0.8f, eyeStrokePaint)
        }
    }

    private inner class BaymaxHandOverlayView(context: Context) : View(context) {
        private var gestureMode = 0
        private var handX = -100f
        private var handY = -100f
        private var swipeAngleDeg = 0f
        private var handAlpha = 0
        private var rippleRadius = 0f
        private var pressScale = 1f
        private var activeAnimator: ValueAnimator? = null

        private val gloveFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F8FAFC")
            style = Paint.Style.FILL
            setShadowLayer(10f, 0f, 4f, Color.parseColor("#66000000"))
        }

        private val gloveStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            style = Paint.Style.STROKE
            strokeWidth = dp(2.2f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val cuffAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3B82F6")
            style = Paint.Style.FILL
        }

        private val primaryRipplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3B82F6")
            style = Paint.Style.STROKE
            strokeWidth = dp(2.5f)
        }

        private val secondaryRipplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#10B981")
            style = Paint.Style.FILL
        }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            isFocusable = false
        }

        fun stopAnimations() {
            activeAnimator?.cancel()
            handAlpha = 0
            alpha = 0f
        }

        fun triggerPlumpHandTap(x: Float, y: Float) {
            activeAnimator?.cancel()
            gestureMode = 0
            handX = x
            handY = y
            swipeAngleDeg = 0f
            alpha = 1f

            activeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 500L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    handAlpha = ((1f - f * 0.85f) * 255).toInt().coerceIn(0, 255)
                    rippleRadius = dp(8f) + (dp(34f) * f)
                    pressScale = when {
                        f < 0.28f -> 1.15f - (f / 0.28f) * 0.33f
                        f < 0.65f -> 0.82f + ((f - 0.28f) / 0.37f) * 0.20f
                        else -> 1.02f
                    }
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        handAlpha = 0
                        alpha = 0f
                        invalidate()
                    }
                })
                start()
            }
        }

        fun triggerPlumpHandSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
            activeAnimator?.cancel()
            gestureMode = 1
            val dx = endX - startX
            val dy = endY - startY
            val rawAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
            swipeAngleDeg = (rawAngle * 0.25f).coerceIn(-35f, 35f)
            alpha = 1f

            activeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 540L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    handX = startX + dx * f
                    handY = startY + dy * f
                    handAlpha = if (f < 0.82f) 245 else ((1f - f) * 1300).toInt().coerceIn(0, 255)
                    rippleRadius = dp(14f)
                    pressScale = 0.92f
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        handAlpha = 0
                        alpha = 0f
                        invalidate()
                    }
                })
                start()
            }
        }

        fun triggerPlumpThumbsUp(centerX: Float, centerY: Float) {
            activeAnimator?.cancel()
            gestureMode = 2
            handX = centerX
            handY = centerY
            alpha = 1f

            activeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 750L
                interpolator = OvershootInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    handAlpha = if (f < 0.75f) 250 else ((1f - f) * 950).toInt().coerceIn(0, 255)
                    pressScale = f.coerceIn(0.4f, 1.12f)
                    rippleRadius = dp(20f) + dp(25f) * f
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        handAlpha = 0
                        alpha = 0f
                        invalidate()
                    }
                })
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (handAlpha <= 5 || handX < 0 || handY < 0) return

            gloveFillPaint.alpha = handAlpha
            gloveStrokePaint.alpha = handAlpha
            cuffAccentPaint.alpha = handAlpha
            primaryRipplePaint.alpha = (handAlpha * 0.8f).toInt()
            secondaryRipplePaint.alpha = (handAlpha * 0.45f).toInt()

            if (gestureMode == 2) {
                drawThumbsUpCelebration(canvas)
                return
            }

            canvas.drawCircle(handX, handY, rippleRadius, primaryRipplePaint)
            canvas.drawCircle(handX, handY, dp(5.5f), secondaryRipplePaint)

            canvas.save()
            canvas.translate(handX, handY)
            canvas.rotate(swipeAngleDeg)
            canvas.scale(pressScale, pressScale)

            canvas.save()
            canvas.translate(-dp(13f), dp(16f))
            canvas.rotate(-32f)
            val thumbRect = RectF(-dp(5.5f), -dp(10f), dp(5.5f), dp(10f))
            canvas.drawRoundRect(thumbRect, dp(6f), dp(6f), gloveFillPaint)
            canvas.drawRoundRect(thumbRect, dp(6f), dp(6f), gloveStrokePaint)
            canvas.restore()

            val midFingerRect = RectF(dp(4f), dp(7f), dp(14f), dp(25f))
            val ringFingerRect = RectF(dp(11f), dp(11f), dp(20f), dp(27f))
            canvas.drawRoundRect(ringFingerRect, dp(5f), dp(5f), gloveFillPaint)
            canvas.drawRoundRect(ringFingerRect, dp(5f), dp(5f), gloveStrokePaint)
            canvas.drawRoundRect(midFingerRect, dp(5.5f), dp(5.5f), gloveFillPaint)
            canvas.drawRoundRect(midFingerRect, dp(5.5f), dp(5.5f), gloveStrokePaint)

            val indexRect = RectF(-dp(6.5f), -dp(2f), dp(6.5f), dp(24f))
            canvas.drawRoundRect(indexRect, dp(6.5f), dp(6.5f), gloveFillPaint)
            canvas.drawRoundRect(indexRect, dp(6.5f), dp(6.5f), gloveStrokePaint)

            val palmRect = RectF(-dp(14f), dp(13f), dp(18f), dp(39f))
            canvas.drawRoundRect(palmRect, dp(13f), dp(13f), gloveFillPaint)
            canvas.drawRoundRect(palmRect, dp(13f), dp(13f), gloveStrokePaint)

            val cuffRect = RectF(-dp(10f), dp(36f), dp(14f), dp(42f))
            canvas.drawRoundRect(cuffRect, dp(4f), dp(4f), cuffAccentPaint)
            canvas.drawRoundRect(cuffRect, dp(4f), dp(4f), gloveStrokePaint)

            canvas.restore()
        }

        private fun drawThumbsUpCelebration(canvas: Canvas) {
            canvas.save()
            canvas.translate(handX, handY)
            canvas.scale(pressScale, pressScale)

            primaryRipplePaint.color = Color.parseColor("#10B981")
            canvas.drawCircle(0f, 0f, rippleRadius, primaryRipplePaint)
            primaryRipplePaint.color = Color.parseColor("#3B82F6")

            val thumbUpRect = RectF(-dp(14f), -dp(26f), -dp(1f), dp(4f))
            canvas.drawRoundRect(thumbUpRect, dp(7f), dp(7f), gloveFillPaint)
            canvas.drawRoundRect(thumbUpRect, dp(7f), dp(7f), gloveStrokePaint)

            val fistRect = RectF(-dp(16f), -dp(6f), dp(14f), dp(22f))
            canvas.drawRoundRect(fistRect, dp(10f), dp(10f), gloveFillPaint)
            canvas.drawRoundRect(fistRect, dp(10f), dp(10f), gloveStrokePaint)

            for (i in 0..2) {
                val top = -dp(4f) + i * dp(8f)
                val knuckle = RectF(dp(6f), top, dp(18f), top + dp(7.5f))
                canvas.drawRoundRect(knuckle, dp(4f), dp(4f), gloveFillPaint)
                canvas.drawRoundRect(knuckle, dp(4f), dp(4f), gloveStrokePaint)
            }

            canvas.restore()
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        )
    }
}
