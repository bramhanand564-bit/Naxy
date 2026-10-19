package com.auradroid.os

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 20 Expressive Pure-Solid Baymax Eye Moods (Zero Pink, Zero Blush!)
 */
enum class BotMood {
    IDLE,               // 1. Classic 2x Solid Black Eyes + Natural Blink
    THINKING,           // 2. Royal Blue Eyes looking up & shifting
    WORKING,            // 3. Emerald Green Determined Heroic Eyes
    HAPPY,              // 4. Emerald Happy Curved Crescents (^ ^)
    ERROR,              // 5. Crimson Red Cross Eyes (X X)
    WINK,               // 6. Playful Wink (● ^)
    STAR_EYES,          // 7. Golden 4-Point Star Eyes (✦ ✦)
    HEART_EYES,         // 8. Bold Crimson Heart Eyes for Likes (♥ ♥)
    SCANNING,           // 9. Cyber Cyan Radar Target Eyes (◎ ◎)
    TYPING,             // 10. Fast Digital Visor Bars (▬ ▬)
    SEARCHING,          // 11. Zoom Magnifier Asymmetric Eyes (● ◉)
    SURPRISED,          // 12. Wide Hollow Ring Eyes (◯ ◯)
    SLEEPY,             // 13. Half-Closed Relaxed Eyes (◡ ◡)
    COOL_SHADES,        // 14. Sleek Cyber Sunglasses Visor (■━■)
    FOCUSED_SQUINT,     // 15. Precision Sniper Squint (━ ━)
    DIZZY,              // 16. Confused/Retry Chevrons (> <)
    ROCKET_LAUNCH,      // 17. Power Boost Double Chevrons (︽ ︽)
    UPLOAD_UP,          // 18. Upward Upload Arrows (↑ ↑)
    CELEBRATE,          // 19. Victory Arcs with Sparkle Bridge (∧ ∧)
    QUESTION            // 20. Curious Raised Single Eye (● •)
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class AiOverlayHud(
    private val context: Context,
    private val onStopClicked: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var podRootLayout: LinearLayout? = null
    private var podParams: WindowManager.LayoutParams? = null
    private var baymaxFaceView: BaymaxFaceView? = null
    private var sideBarContainer: LinearLayout? = null
    private var statusTextView: TextView? = null
    private var stopButtonView: TextView? = null
    private var peekWebButton: TextView? = null

    // Mini / Full Pop-out Live Web Card
    private var webCardContainer: LinearLayout? = null
    private var backgroundWebView: WebView? = null
    private var isWebExpandedFull = false

    // Full-screen transparent overlay for Plump Baymax Hand tap/swipe/thumbs-up gestures
    private var handOverlayView: BaymaxHandOverlayView? = null
    private var handOverlayParams: WindowManager.LayoutParams? = null

    private var isAdded = false
    private var isTaskActivelyRunning = false
    private var manualTapMoodIndex = 0

    private val funIdleMoods = listOf(
        BotMood.IDLE,
        BotMood.WINK,
        BotMood.COOL_SHADES,
        BotMood.HAPPY,
        BotMood.STAR_EYES,
        BotMood.SURPRISED,
        BotMood.QUESTION,
        BotMood.SLEEPY
    )

    private val autoCollapseRunnable = Runnable {
        if (!isTaskActivelyRunning) {
            sideBarContainer?.visibility = View.GONE
            if (webCardContainer?.visibility == View.VISIBLE) {
                setWebCardVisibility(false)
            }
            baymaxFaceView?.setMood(BotMood.IDLE)
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

    fun showHud() {
        mainHandler.post {
            if (isAdded) return@post
            try {
                createHandCursorOverlay()
                createFloatingBaymaxPod()
                isAdded = true
            } catch (_: Exception) {}
        }
    }

    fun hideHud() {
        mainHandler.post {
            mainHandler.removeCallbacks(autoCollapseRunnable)
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
     * Automatically maps the statusText & base mood into one of the 20 expressive Baymax eye states!
     */
    private fun resolveSmartExpression20(statusText: String, baseMood: BotMood): BotMood {
        val s = statusText.lowercase(Locale.ROOT)
        return when {
            baseMood == BotMood.ERROR || s.contains("error") || s.contains("fail") || s.contains("no new") -> BotMood.ERROR
            s.contains("retry") || s.contains("unstick") || s.contains("stuck") || s.contains("limited") -> BotMood.DIZZY
            s.contains("complete") || s.contains("success") -> BotMood.CELEBRATE
            s.contains("capturing") || s.contains("scan") || s.contains("📸") -> BotMood.SCANNING
            s.contains("typing") || s.contains("paste") || s.contains("comment") || s.contains("caption") -> BotMood.TYPING
            s.contains("search") || s.contains("find") || s.contains("folder") -> BotMood.SEARCHING
            s.contains("like") || s.contains("heart") -> BotMood.HEART_EYES
            s.contains("opening") || s.contains("launch") || s.contains("starting") || s.contains("🚀") -> BotMood.ROCKET_LAUNCH
            s.contains("upload") -> BotMood.UPLOAD_UP
            s.contains("viral") || s.contains("flow") || s.contains("title") || s.contains("✨") -> BotMood.STAR_EYES
            s.contains("watch") || s.contains("wait") || s.contains("⏳") -> BotMood.QUESTION
            s.contains("next") || s.contains("send") || s.contains("share") -> BotMood.FOCUSED_SQUINT
            s.contains("stopped") || s.contains("paused") -> BotMood.SLEEPY
            baseMood == BotMood.HAPPY -> BotMood.HAPPY
            baseMood == BotMood.THINKING -> BotMood.THINKING
            baseMood == BotMood.WORKING -> BotMood.WORKING
            else -> baseMood
        }
    }

    fun updateStatus(statusText: String, mood: BotMood, taskRunning: Boolean) {
        mainHandler.post {
            if (!isAdded) showHud()
            isTaskActivelyRunning = taskRunning
            val smartMood = resolveSmartExpression20(statusText, mood)
            baymaxFaceView?.setMood(smartMood)
            statusTextView?.text = statusText

            if (smartMood == BotMood.CELEBRATE || mood == BotMood.HAPPY) {
                val dm = context.resources.displayMetrics
                handOverlayView?.triggerPlumpThumbsUp(dm.widthPixels / 2f, dm.heightPixels * 0.45f)
            }

            mainHandler.removeCallbacks(autoCollapseRunnable)
            if (taskRunning) {
                sideBarContainer?.visibility = View.VISIBLE
                stopButtonView?.visibility = View.VISIBLE
            } else {
                stopButtonView?.visibility = View.GONE
                mainHandler.postDelayed(autoCollapseRunnable, 2600L)
            }
        }
    }

    fun animateHandTap(centerX: Float, centerY: Float) {
        mainHandler.post {
            handOverlayView?.triggerPlumpHandTap(centerX, centerY)
        }
    }

    fun animateHandSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        mainHandler.post {
            handOverlayView?.triggerPlumpHandSwipe(startX, startY, endX, endY)
        }
    }

    fun getOrCreateBackgroundWebView(): WebView? {
        if (backgroundWebView == null && !isAdded) {
            showHud()
        }
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
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(14)
            y = dp(110)
        }

        podRootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 1. Soft-White Circular Baymax Face with 2x Larger Expressive Solid Eyes
        baymaxFaceView = BaymaxFaceView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
        }

        // 2. Collapsible Matte Dark Side Bar
        sideBarContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(10), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#181B20"))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#2E3440"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            }
        }

        statusTextView = TextView(context).apply {
            text = "Ready"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxWidth = dp(190)
            maxLines = 1
        }

        peekWebButton = TextView(context).apply {
            text = " 🌐 "
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
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

        sideBarContainer?.addView(statusTextView)
        sideBarContainer?.addView(peekWebButton)
        sideBarContainer?.addView(stopButtonView)

        topRow.addView(baymaxFaceView)
        topRow.addView(sideBarContainer)

        // 3. Pop-out Live Web Workspace Card
        webCardContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
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
            text = "Background Workspace"
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

        backgroundWebView = WebView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()

            val wSpec = View.MeasureSpec.makeMeasureSpec(dp(340), View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(dp(600), View.MeasureSpec.EXACTLY)
            measure(wSpec, hSpec)
            layout(0, 0, dp(340), dp(600))
        }

        webCardContainer?.addView(webHeader)
        webCardContainer?.addView(backgroundWebView)

        podRootLayout?.addView(topRow)
        podRootLayout?.addView(webCardContainer)

        setupDragAndTapOnBaymaxFace()
        podRootLayout?.let { root ->
            podParams?.let { params ->
                addViewWithFallback(root, params)
            }
        }
    }

    private fun setWebCardVisibility(show: Boolean) {
        webCardContainer?.visibility = if (show) View.VISIBLE else View.GONE
        val params = podParams ?: return
        val root = podRootLayout ?: return
        if (show) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try {
            if (isAdded) windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {}
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
            val maxX = (dm.widthPixels - dp(62)).coerceAtLeast(100)
            val maxY = (dm.heightPixels - dp(95)).coerceAtLeast(200)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
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
                        params.x = (startX + dx).coerceIn(0, maxX)
                        params.y = (startY + dy).coerceIn(0, maxY)
                        try {
                            windowManager.updateViewLayout(podRootLayout, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onBaymaxFaceTapped()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onBaymaxFaceTapped() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        if (!isTaskActivelyRunning) {
            manualTapMoodIndex = (manualTapMoodIndex + 1) % funIdleMoods.size
            baymaxFaceView?.setMood(funIdleMoods[manualTapMoodIndex])
        }
        if (sideBarContainer?.visibility == View.VISIBLE && !isTaskActivelyRunning) {
            sideBarContainer?.visibility = View.GONE
            setWebCardVisibility(false)
        } else {
            sideBarContainer?.visibility = View.VISIBLE
            if (!isTaskActivelyRunning) {
                mainHandler.postDelayed(autoCollapseRunnable, 3200L)
            }
        }
    }

    /**
     * 2x Larger Expressive Baymax Face View with 20 Pure-Solid Expressions (NO Blush, NO Pink!)
     */
    private inner class BaymaxFaceView(context: Context) : View(context) {
        private var currentMood = BotMood.IDLE
        private var blinkScaleY = 1f
        private var gazeOffsetX = 0f
        private var gazeOffsetY = 0f
        private var pulsePhase = 0f
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
            strokeWidth = dp(3.2f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val bridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.STROKE
            strokeWidth = dp(2.4f)
            strokeCap = Paint.Cap.ROUND
        }

        private val reusablePath = Path()

        private val blinkRunnable = object : Runnable {
            override fun run() {
                if (!isRunningBlinkLoop) return
                blinkAnimator?.cancel()
                blinkAnimator = ValueAnimator.ofFloat(1f, 0.06f, 1f).apply {
                    duration = 210L
                    addUpdateListener {
                        blinkScaleY = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }

                when (currentMood) {
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

                val nextBlink = if (currentMood == BotMood.THINKING || currentMood == BotMood.TYPING) 1200L else 3000L
                mainHandler.postDelayed(this, nextBlink)
            }
        }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (!isRunningBlinkLoop) {
                isRunningBlinkLoop = true
                mainHandler.postDelayed(blinkRunnable, 1500L)
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
            // Strictly Bold Solid Colors — NO Pink anywhere!
            val solidColor = when (mood) {
                BotMood.IDLE, BotMood.WINK, BotMood.COOL_SHADES, BotMood.SURPRISED -> Color.parseColor("#0F172A") // Pure Deep Black
                BotMood.THINKING, BotMood.SEARCHING, BotMood.QUESTION -> Color.parseColor("#2563EB")              // Royal Blue
                BotMood.SCANNING, BotMood.TYPING -> Color.parseColor("#0284C7")                                   // Cyber Cyan-Blue
                BotMood.WORKING, BotMood.HAPPY, BotMood.FOCUSED_SQUINT,
                BotMood.UPLOAD_UP, BotMood.CELEBRATE -> Color.parseColor("#10B981")                               // Emerald Green
                BotMood.STAR_EYES, BotMood.ROCKET_LAUNCH -> Color.parseColor("#D97706")                           // Golden Amber
                BotMood.SLEEPY -> Color.parseColor("#475569")                                                     // Matte Slate
                BotMood.DIZZY -> Color.parseColor("#EA580C")                                                      // Deep Orange
                BotMood.ERROR, BotMood.HEART_EYES -> Color.parseColor("#DC2626")                                  // Pure Crimson Red
            }

            eyeFillPaint.color = solidColor
            eyeStrokePaint.color = solidColor
            bridgePaint.color = solidColor

            if (mood == BotMood.THINKING) {
                gazeOffsetY = -dp(2f)
            } else {
                gazeOffsetX = 0f
                gazeOffsetY = 0f
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val headRadius = (width.coerceAtMost(height) / 2f) - dp(3.5f)

            // 1. Draw Soft-White Circular Baymax Head
            canvas.drawCircle(cx, cy, headRadius, headPaint)
            canvas.drawCircle(cx, cy, headRadius, borderPaint)

            // 2. 2x Larger Expressive Eye Geometry
            val eyeSpacing = headRadius * 0.44f
            val r = headRadius * 0.34f // 2x Bolder & Larger Eye Radius!
            val lx = cx - eyeSpacing + gazeOffsetX
            val rx = cx + eyeSpacing + gazeOffsetX
            val ey = cy + gazeOffsetY

            // Draw classic Baymax connector line between the eyes
            if (currentMood != BotMood.COOL_SHADES) {
                canvas.drawLine(lx + r * 0.55f, ey, rx - r * 0.55f, ey, bridgePaint)
            }

            when (currentMood) {
                // 1. IDLE: 2x Big Solid Circular Eyes
                BotMood.IDLE -> {
                    canvas.save()
                    canvas.scale(1f, blinkScaleY, cx, ey)
                    canvas.drawCircle(lx, ey, r, eyeFillPaint)
                    canvas.drawCircle(rx, ey, r, eyeFillPaint)
                    canvas.restore()
                }

                // 2. THINKING: Left eye slightly squinted, Right eye full looking up
                BotMood.THINKING -> {
                    canvas.save()
                    canvas.scale(1f, blinkScaleY, cx, ey)
                    canvas.drawOval(RectF(lx - r, ey - r * 0.72f, lx + r, ey + r * 0.72f), eyeFillPaint)
                    canvas.drawCircle(rx, ey - dp(1f), r, eyeFillPaint)
                    canvas.restore()
                }

                // 3. WORKING: Heroic Angled Determined Eyes
                BotMood.WORKING -> {
                    canvas.save()
                    canvas.scale(1f, blinkScaleY, cx, ey)
                    drawHeroicEye(canvas, lx, ey, r, isLeft = true)
                    drawHeroicEye(canvas, rx, ey, r, isLeft = false)
                    canvas.restore()
                }

                // 4. HAPPY: Bold Upward Curved Arcs (^ ^)
                BotMood.HAPPY -> {
                    val arcL = RectF(lx - r, ey - r * 0.8f, lx + r, ey + r)
                    val arcR = RectF(rx - r, ey - r * 0.8f, rx + r, ey + r)
                    canvas.drawArc(arcL, 195f, 150f, false, eyeStrokePaint)
                    canvas.drawArc(arcR, 195f, 150f, false, eyeStrokePaint)
                }

                // 5. ERROR: Bold X Eyes (✕ ✕)
                BotMood.ERROR -> {
                    val d = r * 0.72f
                    canvas.drawLine(lx - d, ey - d, lx + d, ey + d, eyeStrokePaint)
                    canvas.drawLine(lx + d, ey - d, lx - d, ey + d, eyeStrokePaint)
                    canvas.drawLine(rx - d, ey - d, rx + d, ey + d, eyeStrokePaint)
                    canvas.drawLine(rx + d, ey - d, rx - d, ey + d, eyeStrokePaint)
                }

                // 6. WINK: Left Solid Eye + Right Playful Arc (● ^)
                BotMood.WINK -> {
                    canvas.drawCircle(lx, ey, r, eyeFillPaint)
                    val arcR = RectF(rx - r, ey - r * 0.8f, rx + r, ey + r)
                    canvas.drawArc(arcR, 195f, 150f, false, eyeStrokePaint)
                }

                // 7. STAR_EYES: 4-Point Sparkle Star Eyes (✦ ✦)
                BotMood.STAR_EYES -> {
                    drawFourPointStar(canvas, lx, ey, r * 1.15f)
                    drawFourPointStar(canvas, rx, ey, r * 1.15f)
                }

                // 8. HEART_EYES: Bold Geometric Hearts (♥ ♥ - Crimson, Zero Blush!)
                BotMood.HEART_EYES -> {
                    drawSolidHeart(canvas, lx, ey, r * 1.1f)
                    drawSolidHeart(canvas, rx, ey, r * 1.1f)
                }

                // 9. SCANNING: Outer Target Ring + Inner Center Dot (◎ ◎)
                BotMood.SCANNING -> {
                    eyeStrokePaint.strokeWidth = dp(2.2f)
                    canvas.drawCircle(lx, ey, r, eyeStrokePaint)
                    canvas.drawCircle(rx, ey, r, eyeStrokePaint)
                    val innerR = if (pulsePhase > 0.5f) r * 0.52f else r * 0.36f
                    canvas.drawCircle(lx, ey, innerR, eyeFillPaint)
                    canvas.drawCircle(rx, ey, innerR, eyeFillPaint)
                    eyeStrokePaint.strokeWidth = dp(3.2f)
                }

                // 10. TYPING: Digital Visor Rectangles (▬ ▬)
                BotMood.TYPING -> {
                    val h = r * 0.55f
                    val shift = if (pulsePhase > 0.5f) dp(1.5f) else -dp(1.5f)
                    canvas.drawRoundRect(RectF(lx - r + shift, ey - h, lx + r + shift, ey + h), dp(4f), dp(4f), eyeFillPaint)
                    canvas.drawRoundRect(RectF(rx - r + shift, ey - h, rx + r + shift, ey + h), dp(4f), dp(4f), eyeFillPaint)
                }

                // 11. SEARCHING: Magnifier Asymmetric Eyes (Normal Left, 1.25x Zoom Right)
                BotMood.SEARCHING -> {
                    canvas.drawCircle(lx, ey, r * 0.82f, eyeFillPaint)
                    canvas.drawCircle(rx, ey, r * 1.18f, eyeStrokePaint)
                    canvas.drawCircle(rx, ey, r * 0.55f, eyeFillPaint)
                }

                // 12. SURPRISED: Wide Open Hollow Rings (◯ ◯)
                BotMood.SURPRISED -> {
                    canvas.drawCircle(lx, ey, r * 1.05f, eyeStrokePaint)
                    canvas.drawCircle(rx, ey, r * 1.05f, eyeStrokePaint)
                }

                // 13. SLEEPY: Downward Relaxed Half-Moons (◡ ◡)
                BotMood.SLEEPY -> {
                    val arcL = RectF(lx - r, ey - r, lx + r, ey + r * 0.8f)
                    val arcR = RectF(rx - r, ey - r, rx + r, ey + r * 0.8f)
                    canvas.drawArc(arcL, 15f, 150f, false, eyeStrokePaint)
                    canvas.drawArc(arcR, 15f, 150f, false, eyeStrokePaint)
                }

                // 14. COOL_SHADES: Cyber Sunglasses Visor (■━■)
                BotMood.COOL_SHADES -> {
                    val visorH = r * 0.85f
                    canvas.drawLine(cx - headRadius * 0.88f, ey - visorH * 0.3f, cx + headRadius * 0.88f, ey - visorH * 0.3f, eyeStrokePaint)
                    canvas.drawRoundRect(RectF(lx - r * 1.1f, ey - visorH * 0.5f, lx + r * 1.05f, ey + visorH), dp(3f), dp(3f), eyeFillPaint)
                    canvas.drawRoundRect(RectF(rx - r * 1.05f, ey - visorH * 0.5f, rx + r * 1.1f, ey + visorH), dp(3f), dp(3f), eyeFillPaint)
                }

                // 15. FOCUSED_SQUINT: Precision Horizontal Slits (━ ━)
                BotMood.FOCUSED_SQUINT -> {
                    val h = r * 0.38f
                    canvas.drawRoundRect(RectF(lx - r, ey - h, lx + r, ey + h), dp(3f), dp(3f), eyeFillPaint)
                    canvas.drawRoundRect(RectF(rx - r, ey - h, rx + r, ey + h), dp(3f), dp(3f), eyeFillPaint)
                }

                // 16. DIZZY: Inward Chevrons (> <)
                BotMood.DIZZY -> {
                    val d = r * 0.75f
                    // Left '>'
                    canvas.drawLine(lx - d, ey - d, lx + d * 0.6f, ey, eyeStrokePaint)
                    canvas.drawLine(lx - d, ey + d, lx + d * 0.6f, ey, eyeStrokePaint)
                    // Right '<'
                    canvas.drawLine(rx + d, ey - d, rx - d * 0.6f, ey, eyeStrokePaint)
                    canvas.drawLine(rx + d, ey + d, rx - d * 0.6f, ey, eyeStrokePaint)
                }

                // 17. ROCKET_LAUNCH: Double Upward Boost Chevrons (︽ ︽)
                BotMood.ROCKET_LAUNCH -> {
                    drawUpChevron(canvas, lx, ey - dp(2f), r * 0.8f)
                    drawUpChevron(canvas, lx, ey + dp(3.5f), r * 0.8f)
                    drawUpChevron(canvas, rx, ey - dp(2f), r * 0.8f)
                    drawUpChevron(canvas, rx, ey + dp(3.5f), r * 0.8f)
                }

                // 18. UPLOAD_UP: Upward Arrows Inside Eyes (↑ ↑)
                BotMood.UPLOAD_UP -> {
                    drawUpArrowEye(canvas, lx, ey, r)
                    drawUpArrowEye(canvas, rx, ey, r)
                }

                // 19. CELEBRATE: High Victory Peaks (∧ ∧) + Center Diamond
                BotMood.CELEBRATE -> {
                    drawUpChevron(canvas, lx, ey, r * 0.95f)
                    drawUpChevron(canvas, rx, ey, r * 0.95f)
                    canvas.drawCircle(cx, ey - r * 0.9f, dp(2.2f), eyeFillPaint)
                }

                // 20. QUESTION: Curious Asymmetric Raised Eye (● •)
                BotMood.QUESTION -> {
                    canvas.drawCircle(lx, ey - dp(2f), r * 1.08f, eyeFillPaint)
                    canvas.drawCircle(rx, ey + dp(1.5f), r * 0.72f, eyeFillPaint)
                }
            }
        }

        private fun drawHeroicEye(canvas: Canvas, x: Float, y: Float, r: Float, isLeft: Boolean) {
            reusablePath.reset()
            reusablePath.addCircle(x, y, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(reusablePath)
            canvas.drawCircle(x, y, r, eyeFillPaint)
            // Cut an angled heroic brow along the top
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

        private fun drawUpChevron(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            canvas.drawLine(cx - r, cy + r * 0.45f, cx, cy - r * 0.55f, eyeStrokePaint)
            canvas.drawLine(cx, cy - r * 0.55f, cx + r, cy + r * 0.45f, eyeStrokePaint)
        }

        private fun drawUpArrowEye(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            canvas.drawLine(cx, cy + r * 0.8f, cx, cy - r * 0.75f, eyeStrokePaint)
            canvas.drawLine(cx - r * 0.65f, cy - r * 0.1f, cx, cy - r * 0.8f, eyeStrokePaint)
            canvas.drawLine(cx + r * 0.65f, cy - r * 0.1f, cx, cy - r * 0.8f, eyeStrokePaint)
        }
    }

    /**
     * Upgraded 3D-Styled Plump Baymax Hand Gestures Overlay:
     * 1. TAP Gesture: Full plump 4-finger Baymax glove + thumb + wrist cuff + dual sonar target pulse
     * 2. SWIPE Gesture: Auto-rotates hand in swipe direction + motion chevrons
     * 3. THUMBS-UP Gesture: Celebratory Baymax Thumbs-Up on task completion
     */
    private inner class BaymaxHandOverlayView(context: Context) : View(context) {
        private var gestureMode = 0 // 0=TAP, 1=SWIPE, 2=THUMBS_UP
        private var handX = -100f
        private var handY = -100f
        private var swipeAngleDeg = 0f
        private var animProgress = 0f
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
                    animProgress = f
                    handAlpha = ((1f - f * 0.85f) * 255).toInt().coerceIn(0, 255)
                    rippleRadius = dp(8f) + (dp(34f) * f)
                    // Realistic downward press bounce: 1.15 -> 0.82 -> 1.02
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
            // Calculate rotation so the pointing finger tilts naturally in the direction of motion
            val rawAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
            swipeAngleDeg = (rawAngle * 0.25f).coerceIn(-35f, 35f)
            alpha = 1f

            activeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 540L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    animProgress = f
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
                    animProgress = f
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

            // 1. Target Sonar Rings right at the exact (handX, handY) contact point
            canvas.drawCircle(handX, handY, rippleRadius, primaryRipplePaint)
            canvas.drawCircle(handX, handY, dp(5.5f), secondaryRipplePaint)

            // 2. Draw Plump 3D-Styled Baymax Glove Hand pointing at (0, 0)
            canvas.save()
            canvas.translate(handX, handY)
            canvas.rotate(swipeAngleDeg)
            canvas.scale(pressScale, pressScale)

            // Plump Thumb on the left side
            canvas.save()
            canvas.translate(-dp(13f), dp(16f))
            canvas.rotate(-32f)
            val thumbRect = RectF(-dp(5.5f), -dp(10f), dp(5.5f), dp(10f))
            canvas.drawRoundRect(thumbRect, dp(6f), dp(6f), gloveFillPaint)
            canvas.drawRoundRect(thumbRect, dp(6f), dp(6f), gloveStrokePaint)
            canvas.restore()

            // Two curled plump fingers on the right side
            val midFingerRect = RectF(dp(4f), dp(7f), dp(14f), dp(25f))
            val ringFingerRect = RectF(dp(11f), dp(11f), dp(20f), dp(27f))
            canvas.drawRoundRect(ringFingerRect, dp(5f), dp(5f), gloveFillPaint)
            canvas.drawRoundRect(ringFingerRect, dp(5f), dp(5f), gloveStrokePaint)
            canvas.drawRoundRect(midFingerRect, dp(5.5f), dp(5.5f), gloveFillPaint)
            canvas.drawRoundRect(midFingerRect, dp(5.5f), dp(5.5f), gloveStrokePaint)

            // Main Pointing Index Finger (Tip touches 0,0)
            val indexRect = RectF(-dp(6.5f), -dp(2f), dp(6.5f), dp(24f))
            canvas.drawRoundRect(indexRect, dp(6.5f), dp(6.5f), gloveFillPaint)
            canvas.drawRoundRect(indexRect, dp(6.5f), dp(6.5f), gloveStrokePaint)

            // Soft Rounded Inflatable Palm
            val palmRect = RectF(-dp(14f), dp(13f), dp(18f), dp(39f))
            canvas.drawRoundRect(palmRect, dp(13f), dp(13f), gloveFillPaint)
            canvas.drawRoundRect(palmRect, dp(13f), dp(13f), gloveStrokePaint)

            // Baymax Wrist Cuff Band at bottom of palm
            val cuffRect = RectF(-dp(10f), dp(36f), dp(14f), dp(42f))
            canvas.drawRoundRect(cuffRect, dp(4f), dp(4f), cuffAccentPaint)
            canvas.drawRoundRect(cuffRect, dp(4f), dp(4f), gloveStrokePaint)

            canvas.restore()
        }

        private fun drawThumbsUpCelebration(canvas: Canvas) {
            canvas.save()
            canvas.translate(handX, handY)
            canvas.scale(pressScale, pressScale)

            // Emerald Glow Ring
            primaryRipplePaint.color = Color.parseColor("#10B981")
            canvas.drawCircle(0f, 0f, rippleRadius, primaryRipplePaint)
            primaryRipplePaint.color = Color.parseColor("#3B82F6")

            // Upright Plump Thumb
            val thumbUpRect = RectF(-dp(14f), -dp(26f), -dp(1f), dp(4f))
            canvas.drawRoundRect(thumbUpRect, dp(7f), dp(7f), gloveFillPaint)
            canvas.drawRoundRect(thumbUpRect, dp(7f), dp(7f), gloveStrokePaint)

            // Fist Palm
            val fistRect = RectF(-dp(16f), -dp(6f), dp(14f), dp(22f))
            canvas.drawRoundRect(fistRect, dp(10f), dp(10f), gloveFillPaint)
            canvas.drawRoundRect(fistRect, dp(10f), dp(10f), gloveStrokePaint)

            // 3 Curled Knuckles on right
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
