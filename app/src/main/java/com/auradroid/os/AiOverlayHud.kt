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
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

enum class BotMood {
    IDLE,       // Pure Solid Black Eyes + Blinking
    THINKING,   // Solid Royal Blue Eyes + Looking up/around
    WORKING,    // Solid Emerald Green Eyes + Active expression
    HAPPY,      // Solid Emerald Happy Curved Eyes (^ ^)
    ERROR       // Solid Red Eyes + Sad Curve (︵)
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

    // Full-screen transparent overlay solely for Plump Baymax Hand tap/swipe animation
    private var handOverlayView: BaymaxHandOverlayView? = null
    private var handOverlayParams: WindowManager.LayoutParams? = null

    private var isAdded = false
    private var isTaskActivelyRunning = false

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
            // Fallback to standard application overlay if accessibility token is not yet ready
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

    fun updateStatus(statusText: String, mood: BotMood, taskRunning: Boolean) {
        mainHandler.post {
            if (!isAdded) showHud()
            isTaskActivelyRunning = taskRunning
            baymaxFaceView?.setMood(mood)
            statusTextView?.text = statusText

            mainHandler.removeCallbacks(autoCollapseRunnable)
            if (taskRunning) {
                sideBarContainer?.visibility = View.VISIBLE
                stopButtonView?.visibility = View.VISIBLE
            } else {
                stopButtonView?.visibility = View.GONE
                // Auto-hide side bar after 2.5 seconds so ONLY the circular Baymax face remains
                mainHandler.postDelayed(autoCollapseRunnable, 2500L)
            }
        }
    }

    /**
     * Animates the cute plump Baymax Hand directly at the Dead-Center (x, y) without any trail lines
     */
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

    /**
     * Captures a sharp screenshot directly from the Background WebView's internal canvas
     * even when the mini window is hidden (View.GONE)!
     */
    fun captureWebViewCanvasBitmap(): Bitmap? {
        val wv = backgroundWebView ?: return null
        return try {
            val targetW = if (wv.width > 100) wv.width else dp(340)
            val targetH = if (wv.height > 100) wv.height else dp(600)

            // If WebView is hidden (View.GONE), ensure it is measured & laid out so DOM and canvas are valid
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
            x = dp(16)
            y = dp(110)
        }

        podRootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 1. Soft-White Circular Baymax Face (Always visible)
        baymaxFaceView = BaymaxFaceView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
        }

        // 2. Collapsible Matte Dark Side Bar (Hidden when idle)
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

        // 3. Stage 2 & Stage 3 Mini/Full Pop-out Live Web Workspace Card
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

            // Pre-measure & layout so background WebView works immediately even while hidden
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

    /**
     * Toggles Web Card and dynamically updates Window focusability so soft keyboard works when open
     */
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
            val maxX = (dm.widthPixels - dp(58)).coerceAtLeast(100)
            val maxY = (dm.heightPixels - dp(90)).coerceAtLeast(200)

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
        if (sideBarContainer?.visibility == View.VISIBLE && !isTaskActivelyRunning) {
            sideBarContainer?.visibility = View.GONE
            setWebCardVisibility(false)
        } else {
            sideBarContainer?.visibility = View.VISIBLE
            if (!isTaskActivelyRunning) {
                // Auto-hide again after 3 seconds if idle
                mainHandler.postDelayed(autoCollapseRunnable, 3000L)
            }
        }
    }

    /**
     * Pure Solid-Eye Baymax Face View:
     * - Soft-White head (#F8FAFC)
     * - 4x Larger Solid Eyes (NO 3D white dots/reflections, NO cheek blush)
     * - Changes eye color & expression based on state (Idle=Black, Thinking=Blue, Working/Happy=Green, Error=Red)
     */
    private inner class BaymaxFaceView(context: Context) : View(context) {
        private var currentMood = BotMood.IDLE
        private var blinkScaleY = 1f
        private var gazeOffsetX = 0f
        private var gazeOffsetY = 0f
        private var blinkAnimator: ValueAnimator? = null
        private var isRunningBlinkLoop = false

        private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F8FAFC")
            style = Paint.Style.FILL
            setShadowLayer(8f, 0f, 3f, Color.parseColor("#55000000"))
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }

        private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A") // Default pure solid black
            style = Paint.Style.FILL
        }

        private val connectorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
        }

        private val blinkRunnable = object : Runnable {
            override fun run() {
                if (!isRunningBlinkLoop) return
                blinkAnimator?.cancel()
                blinkAnimator = ValueAnimator.ofFloat(1f, 0.08f, 1f).apply {
                    duration = 220L
                    addUpdateListener {
                        blinkScaleY = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
                if (currentMood == BotMood.THINKING) {
                    gazeOffsetX = if (gazeOffsetX <= 0f) dp(1).toFloat() else -dp(1).toFloat()
                }
                val nextBlink = if (currentMood == BotMood.THINKING) 1400L else 3200L
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
                mainHandler.postDelayed(blinkRunnable, 1800L)
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
                BotMood.IDLE -> Color.parseColor("#0F172A")     // Pure Black
                BotMood.THINKING -> Color.parseColor("#2563EB") // Solid Royal Blue
                BotMood.WORKING -> Color.parseColor("#10B981")  // Solid Emerald Green
                BotMood.HAPPY -> Color.parseColor("#10B981")    // Solid Emerald Green
                BotMood.ERROR -> Color.parseColor("#EF4444")    // Solid Red
            }
            eyePaint.color = solidColor
            connectorLinePaint.color = solidColor

            if (mood == BotMood.THINKING) {
                gazeOffsetY = -dp(2).toFloat()
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
            val radius = (width.coerceAtMost(height) / 2f) - dp(4)

            // 1. Draw Soft-White Circular Baymax Head
            canvas.drawCircle(cx, cy, radius, headPaint)
            canvas.drawCircle(cx, cy, radius, borderPaint)

            // 2. 4x Large Pure Solid Eyes Configuration (No reflection dots, no blush)
            val eyeSpacing = radius * 0.44f
            val eyeRadius = radius * 0.25f // 4x larger expressive solid eyes
            val leftEyeX = cx - eyeSpacing + gazeOffsetX
            val rightEyeX = cx + eyeSpacing + gazeOffsetX
            val eyeY = cy + gazeOffsetY

            // Classic subtle Baymax bridge line between the two eyes
            canvas.drawLine(leftEyeX + eyeRadius * 0.6f, eyeY, rightEyeX - eyeRadius * 0.6f, eyeY, connectorLinePaint)

            when (currentMood) {
                BotMood.HAPPY -> {
                    // Happy curved eyes (^  ^)
                    connectorLinePaint.strokeWidth = dp(3).toFloat()
                    val arcRectL = RectF(leftEyeX - eyeRadius, eyeY - eyeRadius, leftEyeX + eyeRadius, eyeY + eyeRadius)
                    val arcRectR = RectF(rightEyeX - eyeRadius, eyeY - eyeRadius, rightEyeX + eyeRadius, eyeY + eyeRadius)
                    canvas.drawArc(arcRectL, 190f, 160f, false, connectorLinePaint)
                    canvas.drawArc(arcRectR, 190f, 160f, false, connectorLinePaint)
                    connectorLinePaint.strokeWidth = dp(2).toFloat()
                }
                BotMood.ERROR -> {
                    // Solid Red Eyes + subtle sad mouth curve
                    canvas.save()
                    canvas.scale(1f, blinkScaleY, cx, eyeY)
                    canvas.drawCircle(leftEyeX, eyeY, eyeRadius, eyePaint)
                    canvas.drawCircle(rightEyeX, eyeY, eyeRadius, eyePaint)
                    canvas.restore()

                    val sadRect = RectF(cx - dp(6), cy + dp(9), cx + dp(6), cy + dp(15))
                    canvas.drawArc(sadRect, 200f, 140f, false, connectorLinePaint)
                }
                else -> {
                    // Pure Solid Large Circular Eyes with smooth vertical blink
                    canvas.save()
                    canvas.scale(1f, blinkScaleY, cx, eyeY)
                    canvas.drawCircle(leftEyeX, eyeY, eyeRadius, eyePaint)
                    canvas.drawCircle(rightEyeX, eyeY, eyeRadius, eyePaint)
                    canvas.restore()
                }
            }
        }
    }

    /**
     * Cute Plump Baymax Hand Cursor Overlay (No flight trail lines!)
     * Appears directly at the dead-center of the button being tapped or swiped.
     * Automatically sets view alpha = 0f when idle so Android 12+ never blocks touches underneath!
     */
    private inner class BaymaxHandOverlayView(context: Context) : View(context) {
        private var handX = -100f
        private var handY = -100f
        private var handAlpha = 0
        private var rippleRadius = 0f
        private var pressScale = 1f
        private var activeAnimator: ValueAnimator? = null

        private val handFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F8FAFC")
            style = Paint.Style.FILL
        }

        private val handStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#334155")
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }

        private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3B82F6")
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }

        fun stopAnimations() {
            activeAnimator?.cancel()
            handAlpha = 0
            alpha = 0f
        }

        fun triggerPlumpHandTap(x: Float, y: Float) {
            activeAnimator?.cancel()
            handX = x
            handY = y
            alpha = 1f
            activeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 460L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    handAlpha = ((1f - f) * 255).toInt().coerceIn(0, 255)
                    rippleRadius = dp(6) + (dp(28) * f)
                    pressScale = if (f < 0.35f) 0.85f else 1f
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
            alpha = 1f
            activeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 500L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    handX = startX + (endX - startX) * f
                    handY = startY + (endY - startY) * f
                    handAlpha = if (f < 0.85f) 240 else ((1f - f) * 1200).toInt().coerceIn(0, 255)
                    rippleRadius = dp(12).toFloat()
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

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (handAlpha <= 5 || handX < 0 || handY < 0) return

            handFillPaint.alpha = handAlpha
            handStrokePaint.alpha = handAlpha
            ripplePaint.alpha = (handAlpha * 0.75f).toInt()

            // Soft circular ripple right at the dead-center target point
            canvas.drawCircle(handX, handY, rippleRadius, ripplePaint)

            // Cute Plump Baymax Glove/Hand shape pointing at (handX, handY)
            canvas.save()
            canvas.translate(handX, handY)
            canvas.scale(pressScale, pressScale)

            // Pointing plump finger tip at (0,0) and soft round palm slightly below
            val fingerRect = RectF(-dp(6).toFloat(), 0f, dp(6).toFloat(), dp(22).toFloat())
            val palmRect = RectF(-dp(15).toFloat(), dp(12).toFloat(), dp(15).toFloat(), dp(38).toFloat())

            canvas.drawRoundRect(fingerRect, dp(6).toFloat(), dp(6).toFloat(), handFillPaint)
            canvas.drawRoundRect(fingerRect, dp(6).toFloat(), dp(6).toFloat(), handStrokePaint)

            canvas.drawOval(palmRect, handFillPaint)
            canvas.drawOval(palmRect, handStrokePaint)

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
}
