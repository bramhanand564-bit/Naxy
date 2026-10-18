package com.auradroid.os

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
import android.widget.FrameLayout
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
            webCardContainer?.visibility = View.GONE
            baymaxFaceView?.setMood(BotMood.IDLE)
        }
    }

    init {
        try {
            WebView.enableSlowWholeDocumentDraw()
        } catch (_: Exception) {}
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
            if (!isAdded) return@post
            try {
                podRootLayout?.let { windowManager.removeViewImmediate(it) }
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
        return backgroundWebView
    }

    /**
     * Captures a sharp screenshot directly from the Background WebView's internal canvas
     * even when the mini window is hidden!
     */
    fun captureWebViewCanvasBitmap(): Bitmap? {
        val wv = backgroundWebView ?: return null
        return try {
            val w = wv.width.coerceAtLeast(540)
            val h = wv.height.coerceAtLeast(960)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            wv.draw(canvas)
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun createHandCursorOverlay() {
        handOverlayView = BaymaxHandOverlayView(context)
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        handOverlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(handOverlayView, handOverlayParams)
    }

    private fun createFloatingBaymaxPod() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        podParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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
                webCardContainer?.visibility = View.GONE
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
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }

        webCardContainer?.addView(webHeader)
        webCardContainer?.addView(backgroundWebView)

        podRootLayout?.addView(topRow)
        podRootLayout?.addView(webCardContainer)

        setupDragAndTapOnBaymaxFace()
        windowManager.addView(podRootLayout, podParams)
    }

    private fun toggleWebCardVisibility() {
        val vis = webCardContainer?.visibility == View.VISIBLE
        webCardContainer?.visibility = if (vis) View.GONE else View.VISIBLE
    }

    private fun setupDragAndTapOnBaymaxFace() {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false

        baymaxFaceView?.setOnTouchListener { _, event ->
            val params = podParams ?: return@setOnTouchListener false
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
                        params.x = startX + dx
                        params.y = startY + dy
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
            webCardContainer?.visibility = View.GONE
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
                ValueAnimator.ofFloat(1f, 0.08f, 1f).apply {
                    duration = 220L
                    addUpdateListener {
                        blinkScaleY = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
                val nextBlink = if (currentMood == BotMood.THINKING) 1600L else 3400L
                mainHandler.postDelayed(this, nextBlink)
            }
        }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            mainHandler.postDelayed(blinkRunnable, 2000L)
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
     */
    private inner class BaymaxHandOverlayView(context: Context) : View(context) {
        private var handX = -100f
        private var handY = -100f
        private var handAlpha = 0
        private var rippleRadius = 0f
        private var pressScale = 1f

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

        fun triggerPlumpHandTap(x: Float, y: Float) {
            handX = x
            handY = y
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 480L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    handAlpha = ((1f - f) * 255).toInt().coerceIn(0, 255)
                    rippleRadius = dp(6) + (dp(28) * f)
                    pressScale = if (f < 0.35f) 0.85f else 1f
                    invalidate()
                }
                start()
            }
        }

        fun triggerPlumpHandSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 520L
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
