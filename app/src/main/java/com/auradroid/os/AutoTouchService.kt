package com.auradroid.os

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.random.Random

data class TaggedUiElement(
    val tagId: Int,
    val node: AccessibilityNodeInfo?,
    val label: String,
    val className: String,
    val bounds: Rect,
    val centerX: Int,
    val centerY: Int
)

class AutoTouchService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutoTouchService? = null
            private set

        val liveLogs = mutableListOf<String>()

        fun log(msg: String) {
            val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
            val line = "[$time] $msg"
            synchronized(liveLogs) {
                liveLogs.add(0, line)
                if (liveLogs.size > 250) {
                    liveLogs.removeAt(liveLogs.lastIndex)
                }
            }
            instance?.memoryVault?.appendPersistentLog(line)
        }
    }

    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal var currentTaskJob: Job? = null
    private var schedulerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    lateinit var hud: AiOverlayHud
    lateinit var memoryVault: MemoryVault
    lateinit var geminiBrain: GeminiBrain

    @Volatile
    internal var isAgentRunning = false
    internal var currentTaskGoal = ""
    internal var currentAppCategory = "General"
    internal var lastStepScreenshot: Bitmap? = null
    internal var lastAgentTapTimestamp = 0L
    private var lastUserScreenInteractionTimeMs = System.currentTimeMillis()
    private var lastScreenshotTimestamp = 0L
    private var lastAiCallTimestamp = 0L
    private var consecutiveSameScreenCount = 0
    internal var lastScreenSignature = ""

    internal val currentRunBitmaps = mutableListOf<Bitmap>()
    internal val currentRunSteps = mutableListOf<String>()
    internal val currentPlannedSteps = mutableListOf<String>()
    internal var activeRunId = ""
    internal var activeRunTitle = ""
    internal var activeRunFinalized = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("gemini_api_key", "") ?: ""

        memoryVault = MemoryVault(this)
        geminiBrain = GeminiBrain(savedKey).apply {
            detailedApiLoggingEnabled = prefs.getBoolean("detailed_api_logging", true)
            onBrainLog = { brainMsg -> log(brainMsg) }
        }
        hud = AiOverlayHud(this) { stopCurrentTask("Stopped by User") }

        start24x7ForegroundProtection()
        if (prefs.getBoolean("show_bot_hud", true)) {
            hud.showHud()
        }
        startDailySchedulerLoop()
        log("✅ AuraDroid OS 24/7 Body Service Connected.")
    }

    override fun onDestroy() {
        releaseWakeLock()
        hud.hideHud()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    internal fun recordRunBitmapSafely(bmp: Bitmap?) {
        if (bmp == null) return
        lastStepScreenshot = bmp
        synchronized(currentRunBitmaps) {
            if (currentRunBitmaps.size >= 10) {
                currentRunBitmaps.removeAt(0)
            }
            currentRunBitmaps.add(bmp)
        }
    }

    private fun start24x7ForegroundProtection() {
        try {
            val channelId = "auradroid_24_7_channel"
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val chan = NotificationChannel(
                    channelId,
                    "AuraDroid 24/7 Autonomous Engine",
                    NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(chan)
            }
            val notif: Notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("AuraDroid OS Active (24/7)")
                .setContentText("Autonomous Mind & Body Ready")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1001, notif)
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("WakelockTimeout")
    internal fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuraDroidOS::AutonomousWakeLock")
            }
            if (wakeLock?.isHeld == false) wakeLock?.acquire()
        } catch (_: Exception) {}
    }

    internal fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
    }

    fun getCalibratedDeviceBodySpecs(): DeviceBodySpecs {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val realMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(realMetrics)

        val width = realMetrics.widthPixels.coerceAtLeast(720)
        val height = realMetrics.heightPixels.coerceAtLeast(1280)

        var statusBarH = (28 * realMetrics.density).toInt()
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) statusBarH = resources.getDimensionPixelSize(resId)

        var cutoutTop = statusBarH
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = wm.currentWindowMetrics
            val cutout = windowMetrics.windowInsets.displayCutout
            if (cutout != null && cutout.safeInsetTop > cutoutTop) {
                cutoutTop = cutout.safeInsetTop
            }
        }

        var navBarH = (48 * realMetrics.density).toInt()
        val navResId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (navResId > 0) navBarH = resources.getDimensionPixelSize(navResId)

        return DeviceBodySpecs(
            modelName = "${Build.MANUFACTURER} ${Build.MODEL}",
            screenWidth = width,
            screenHeight = height,
            statusBarHeight = statusBarH,
            cutoutSafeTop = cutoutTop,
            navBarHeight = navBarH,
            densityDpi = realMetrics.densityDpi
        )
    }

    fun isPhoneCurrentlyIdleOrFree(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) return true
            val idleMs = System.currentTimeMillis() - lastUserScreenInteractionTimeMs
            idleMs >= 90_000L
        } catch (_: Exception) {
            false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val teachEnabled = prefs.getBoolean("passive_teach_mode", true)

        val timeSinceAgentTap = System.currentTimeMillis() - lastAgentTapTimestamp
        if (timeSinceAgentTap > 1400L &&
            (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)
        ) {
            lastUserScreenInteractionTimeMs = System.currentTimeMillis()
        }

        if (teachEnabled && isAgentRunning && timeSinceAgentTap > 1400L &&
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg == packageName) return

            val src = event.source ?: return
            val rect = Rect()
            src.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) return

            val specs = getCalibratedDeviceBodySpecs()
            val tapXRatio = rect.exactCenterX() / specs.screenWidth.toFloat()
            val tapYRatio = rect.exactCenterY() / specs.screenHeight.toFloat()
            val nodeDesc = (src.text?.toString() ?: src.contentDescription?.toString() ?: src.viewIdResourceName ?: "Element").take(60)

            val beforeBitmap = lastStepScreenshot
            val cat = currentAppCategory
            val goal = currentTaskGoal
            val sig = lastScreenSignature

            log("👆 Human Touch Detected on [$nodeDesc]! Capturing AFTER screenshot...")
            currentRunSteps.add("Human Intervened: Tapped [$nodeDesc] at (${rect.centerX()}, ${rect.centerY()})")

            serviceScope.launch {
                delay(1200L)
                val afterBitmap = takeHdDisplayScreenshotWithTimeout(2200L)
                recordRunBitmapSafely(afterBitmap)
                withContext(Dispatchers.IO) {
                    val (prob, rule) = geminiBrain.analyzeBeforeAfterHumanTouchWith38Flash(
                        appCategory = cat,
                        goal = goal,
                        beforeScreenshot = beforeBitmap,
                        afterScreenshot = afterBitmap,
                        humanTapXRatio = tapXRatio,
                        humanTapYRatio = tapYRatio,
                        clickedNodeDesc = nodeDesc
                    )
                    if (prob.isNotBlank() && rule.isNotBlank()) {
                        memoryVault.saveStuckOrHumanFix(
                            category = cat,
                            screenSignature = sig,
                            problemDescription = prob,
                            solutionRule = rule,
                            tapXRatio = tapXRatio,
                            tapYRatio = tapYRatio,
                            beforeScreenshot = beforeBitmap,
                            afterScreenshot = afterBitmap,
                            sourceType = "HUMAN_TOUCH_FIX"
                        )
                        log("🧠 Learned & Saved in Memory/$cat: $rule")
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    internal fun finalizeRunAndTriggerAutoReflection(finalStatus: String) {
        if (activeRunFinalized || activeRunId.isEmpty()) return
        activeRunFinalized = true

        val rId = activeRunId
        val rTitle = activeRunTitle
        val rCat = currentAppCategory
        val rGoal = currentTaskGoal
        val rPlanned = currentPlannedSteps.toList()
        val rSteps = currentRunSteps.toList()
        val rBitmaps = synchronized(currentRunBitmaps) { currentRunBitmaps.toList() }

        memoryVault.saveAutomationRunSession(
            runId = rId,
            title = rTitle,
            appCategory = rCat,
            status = finalStatus,
            stepsSummary = rSteps,
            capturedBitmaps = rBitmaps
        )

        if (finalStatus.equals("SUCCESS", ignoreCase = true) || finalStatus.equals("STOPPED", ignoreCase = true)) {
            log("🛡️ Quota Saver: '$rTitle' [$finalStatus] saved locally (0 extra Audit API tokens used!).")
            return
        }

        if (finalStatus.equals("FAILED", ignoreCase = true) && (rBitmaps.isNotEmpty() || rSteps.isNotEmpty())) {
            serviceScope.launch(Dispatchers.IO) {
                log("🧠 Failed-Run Diagnosis started for '$rTitle'...")
                val reflection = geminiBrain.autoReflectAndImproveAfterRun(
                    appCategory = rCat,
                    goal = rGoal,
                    plannedSteps = rPlanned,
                    actualStepsTaken = rSteps,
                    finalStatus = finalStatus,
                    runBitmaps = rBitmaps
                )

                memoryVault.appendChatToAutomationRun(
                    runId = rId,
                    sender = "Auto-Diagnosis (${reflection.modelUsed})",
                    message = reflection.reflectionSummary
                )

                for (rule in reflection.learnedRules) {
                    memoryVault.saveStuckOrHumanFix(
                        category = rCat,
                        screenSignature = rule.screenSignature,
                        problemDescription = rule.problemDescription,
                        solutionRule = rule.solutionRule,
                        tapXRatio = rule.tapXRatio,
                        tapYRatio = rule.tapYRatio,
                        sourceType = rule.sourceType
                    )
                }
            }
        }
    }

    /**
     * Single-Stop Guard: Prevents printing "Task Stopped: Stopped by User" multiple times!
     */
    fun stopCurrentTask(reason: String = "Stopped") {
        val wasRunning = isAgentRunning
        val jobWasActive = currentTaskJob?.isActive == true
        if (!wasRunning && !jobWasActive) return

        isAgentRunning = false
        if (wasRunning) {
            val status = if (reason.contains("Complete", true)) "SUCCESS" else "STOPPED"
            finalizeRunAndTriggerAutoReflection(status)
        }
        currentTaskJob?.cancel()
        releaseWakeLock()
        hud.updateStatus(reason, BotMood.IDLE, taskRunning = false)
        log("■ Task Stopped: $reason")
    }

    internal suspend fun hideWebPreviewCardIfOpen() {
        withContext(Dispatchers.Main) {
            try {
                (hud.getOrCreateBackgroundWebView()?.parent as? View)?.visibility = View.GONE
            } catch (_: Exception) {}
        }
    }

    /**
     * Universal Auto-Popup Killer (0 API calls!):
     * Automatically dismisses unwanted popups like "Share this on Threads?", "Discard changes?", "Turn on notifications?"
     */
    private suspend fun dismissUnwantedPopupsIfPresent(
        elements: List<TaggedUiElement>,
        specs: DeviceBodySpecs
    ): Boolean {
        val hasThreadsPopup = elements.any {
            it.label.contains("Share this on Threads", true) || it.label.equals("Share now", true)
        }
        val hasDiscardPopup = elements.any {
            it.label.contains("Discard changes", true) || it.label.contains("not be saved", true)
        }
        val hasNotifPopup = elements.any {
            it.label.contains("Turn on notifications", true)
        }

        if (hasThreadsPopup || hasDiscardPopup || hasNotifPopup) {
            val cancelOrNotNow = elements.find {
                it.label.equals("Cancel", true) ||
                    it.label.equals("Not now", true) ||
                    it.label.equals("Dismiss", true) ||
                    it.label.equals("रद्द करें", true)
            }
            if (cancelOrNotNow != null) {
                log("🛡️ Auto-Popup Killer: Dismissed unwanted popup by tapping [${cancelOrNotNow.label}]!")
                tapElementSmartly(cancelOrNotNow, forceAccessibilityClick = true)
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            delay(1300L)
            return true
        }
        return false
    }

    /**
     * Universal Loading Spinner Wait (0 API calls!):
     * If screen shows "Searching for..." or "Loading...", waits 1.6s so results finish loading before taking a screenshot.
     */
    private suspend fun waitIfScreenIsActivelyLoading(elements: List<TaggedUiElement>): Boolean {
        val isLoading = elements.any {
            it.label.startsWith("Searching for", true) ||
                it.label.equals("Loading...", true) ||
                it.label.equals("लोड हो रहा है...", true)
        }
        if (isLoading) {
            log("⏳ Screen is actively loading results -> Waiting 1.6s for content to appear...")
            delay(1600L)
            return true
        }
        return false
    }

    /**
     * 3-Step Post-Click Road-Crossing Verifier:
     * When the final action (e.g. tapping the bottom blue "Send" / "Submit" button) is executed,
     * waits 2.4s, re-scans the screen, and verifies that the Share/Action sheet actually closed!
     * If the sheet is still open (meaning the tap missed!), it taps the exact bottom button again and re-verifies!
     */
    private suspend fun verifyFinalActionRoadCrossedOrRetry(
        specs: DeviceBodySpecs,
        preTapElements: List<TaggedUiElement>,
        tapX: Int,
        tapY: Int
    ): Boolean {
        val hadWriteMessageBefore = preTapElements.any { it.label.contains("Write a message", true) }
        val hadSendButtonBefore = preTapElements.any {
            it.label.equals("Send", true) || it.label.contains("Send separately", true)
        }

        delay(2400L)
        val afterElements = scanScreenInteractiveElements(specs)

        val stillHasWriteMessage = afterElements.any { it.label.contains("Write a message", true) }
        val stillHasSendButton = afterElements.any {
            (it.label.equals("Send", true) || it.label.contains("Send separately", true)) &&
                it.centerY > (specs.screenHeight * 0.82f)
        }

        if ((hadWriteMessageBefore || hadSendButtonBefore) && (stillHasWriteMessage || stillHasSendButton)) {
            log("⚠️ Post-Click Proof: Share/Send sheet is STILL open (tap missed)! Retrying exact bottom Send button...")
            if (isSoftKeyboardOpen()) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(1000L)
            }
            val exactSendNode = scanScreenInteractiveElements(specs).find {
                (it.label.equals("Send", true) || it.label.contains("Send separately", true)) &&
                    it.centerY > (specs.screenHeight * 0.84f)
            }
            val retryX = exactSendNode?.centerX ?: (specs.screenWidth / 2)
            val retryY = exactSendNode?.centerY ?: (specs.screenHeight * 0.938f).toInt()
            performPhysicalTap(retryX, retryY)
            delay(2400L)

            val checkAgain = scanScreenInteractiveElements(specs)
            val stillOpenAfterRetry = checkAgain.any { it.label.contains("Write a message", true) }
            if (!stillOpenAfterRetry) {
                log("🎉 3-Step Road-Crossing Confirmed on Retry: Sheet closed & action completed!")
                return true
            }
            return false
        }

        log("🎉 3-Step Road-Crossing Confirmed: Screen transitioned to next road after final action!")
        return true
    }

    /**
     * Universal Autonomous Goal-Based Executor (Works for ANY App or Website in the Universe!)
     */
    fun startUniversalTask(
        goal: String,
        plannedSteps: List<String> = emptyList(),
        forceMode: String? = null
    ) {
        currentTaskJob?.cancel()
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        geminiBrain.updateApiKey(prefs.getString("gemini_api_key", "") ?: "")
        geminiBrain.detailedApiLoggingEnabled = prefs.getBoolean("detailed_api_logging", true)

        val execMode = forceMode ?: prefs.getString("execution_mode", "LIVE_PHONE") ?: "LIVE_PHONE"

        if (execMode.startsWith("SCHEDULE_YT_")) {
            val timeHm = normalizeTimeHm24(execMode.removePrefix("SCHEDULE_YT_").trim().ifEmpty { "19:00" })
            val pipeType = if (goal.contains("flow", true)) "FLOW_YT" else "FOLDER_YT"
            prefs.edit()
                .putBoolean("scheduler_enabled", true)
                .putString("flow_create_time_hm", timeHm)
                .putString("schedule_time_hm", timeHm)
                .putString("folder_upload_time_hm", timeHm)
                .putString("scheduled_pipeline_type", pipeType)
                .apply()
            hud.updateStatus("⏰ Daily Schedule Set: $timeHm", BotMood.HAPPY, taskRunning = false)
            log("⏰ Chat Activated 24/7 Daily Scheduler at $timeHm [$pipeType]!")
            return
        }

        if (execMode == "IDLE_PHONE_MODE") {
            prefs.edit()
                .putBoolean("run_only_when_idle", true)
                .putString("pending_idle_goal", goal)
                .apply()
            hud.updateStatus("🌙 फोन खाली होने पर चलेगा!", BotMood.HAPPY, taskRunning = false)
            log("🌙 Queued Task for Idle Phone Mode: $goal")
            return
        }

        if (execMode == "YOUTUBE_FOLDER_UPLOAD") {
            val folder = prefs.getString(
                "yt_folder_path",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
            ) ?: ""
            val pickOldest = prefs.getBoolean("yt_pick_oldest", true)
            startYouTubeFolderAutoUpload(folder, pickOldest)
            return
        }

        if (execMode == "GOOGLE_FLOW_UPLOAD") {
            val topic = prefs.getString("flow_topic", goal.take(40)) ?: goal.take(40)
            val count = prefs.getInt("flow_daily_videos", 1)
            startGoogleFlowToYouTubePipeline(topic, count, uploadImmediatelyAfterCreate = true)
            return
        }

        val isContinuousMonitor = goal.contains("बात करते रहो", true) ||
            goal.contains("monitor", true) ||
            goal.contains("wait for reply", true) ||
            goal.contains("लगातार", true)

        activeRunId = "run_${System.currentTimeMillis()}"
        activeRunTitle = goal.take(35)
        activeRunFinalized = false
        synchronized(currentRunBitmaps) { currentRunBitmaps.clear() }
        currentRunSteps.clear()
        currentPlannedSteps.clear()
        currentPlannedSteps.addAll(plannedSteps)
        currentAppCategory = memoryVault.resolveCategoryFolder("", goal)

        currentTaskJob = serviceScope.launch {
            try {
                isAgentRunning = true
                acquireWakeLock()
                currentTaskGoal = goal
                consecutiveSameScreenCount = 0
                lastScreenSignature = ""
                val stepHistory = mutableListOf<String>()
                var stepNumber = 0
                var hasAlreadyCommentedInThisRun = false

                hud.updateStatus("Starting: ${goal.take(22)}...", BotMood.THINKING, taskRunning = true)
                log("🚀 Starting Task [$execMode]: $goal")

                if (execMode == "BACKGROUND_WEB") {
                    runBackgroundWebAgentLoop(goal, plannedSteps)
                    finalizeRunAndTriggerAutoReflection("SUCCESS")
                    return@launch
                }

                val lowerGoal = goal.lowercase(Locale.ROOT)
                val autoTargetApp = when {
                    lowerGoal.contains("instagram") || lowerGoal.contains("इंस्टाग्राम") || lowerGoal.contains("insta") -> "instagram"
                    lowerGoal.contains("whatsapp") || lowerGoal.contains("व्हाट्सएप") -> "whatsapp"
                    lowerGoal.contains("youtube") || lowerGoal.contains("यूट्यूब") -> "youtube"
                    lowerGoal.contains("gemini") || lowerGoal.contains("जेमिनी") -> "com.google.android.apps.bard"
                    lowerGoal.contains("chrome") || lowerGoal.contains("क्रोम") -> "chrome"
                    lowerGoal.contains("setting") || lowerGoal.contains("सेटिंग") -> "settings"
                    else -> null
                }

                if (autoTargetApp != null) {
                    hud.updateStatus("🚀 Opening $autoTargetApp...", BotMood.WORKING, taskRunning = true)
                    launchAppByNameOrPackage(autoTargetApp)
                    stepHistory.add("MILESTONE_APP_OPENED: Opened $autoTargetApp app")
                    currentRunSteps.add("Step 0: Directly opened $autoTargetApp app")
                    delay(3200L)
                }

                while (isActive && isAgentRunning) {
                    stepNumber++
                    val specs = getCalibratedDeviceBodySpecs()
                    val activePkg = rootInActiveWindow?.packageName?.toString() ?: "android"
                    currentAppCategory = memoryVault.resolveCategoryFolder(activePkg, goal)

                    if (stepNumber == 1 && activePkg == packageName && autoTargetApp == null) {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        delay(1500L)
                    }

                    var taggedElements = scanScreenInteractiveElements(specs)

                    // 1. Universal Auto-Popup Killer (0 API calls)
                    if (dismissUnwantedPopupsIfPresent(taggedElements, specs)) {
                        taggedElements = scanScreenInteractiveElements(specs)
                    }

                    // 2. Universal Loading Spinner Wait (0 API calls)
                    if (waitIfScreenIsActivelyLoading(taggedElements)) {
                        taggedElements = scanScreenInteractiveElements(specs)
                    }

                    val currentSig = buildScreenSignature(activePkg, taggedElements)

                    if (currentSig == lastScreenSignature && stepNumber > 1) {
                        consecutiveSameScreenCount++
                    } else {
                        consecutiveSameScreenCount = 0
                        lastScreenSignature = currentSig
                    }

                    if (hasAlreadyCommentedInThisRun && activePkg.contains("instagram")) {
                        val commentSheetStillOpen = taggedElements.any {
                            it.label.contains("Join the conversation", true) ||
                                it.label.contains("Add a comment", true)
                        }
                        if (commentSheetStillOpen) {
                            log("⌨️ Comment already posted! Auto-closing Comment Sheet to expose Share button...")
                            hud.updateStatus("Closing Comment Sheet...", BotMood.WORKING, taskRunning = true)
                            closeKeyboardCompletely()
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            delay(1400L)
                            stepHistory.add("MILESTONE_COMMENT_SHEET_CLOSED: Returned to main Reel screen")
                            continue
                        }
                    }

                    if (consecutiveSameScreenCount >= 3) {
                        log("⚠️ Screen unchanged for 3 steps! Performing smart unstick...")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        stepHistory.add("Pressed BACK to unstick keyboard/sheet")
                        consecutiveSameScreenCount = 1
                        delay(1500L)
                    }

                    val hasLimitedComments = taggedElements.any {
                        it.label.contains("Comments on this post have been limited", true) ||
                            it.label.contains("comments are limited", true)
                    }
                    if (hasLimitedComments) {
                        log("⚠️ Detected limited comments! Swiping to next video...")
                        hud.updateStatus("Comments limited -> Next Video", BotMood.WORKING, taskRunning = true)
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        delay(1200L)
                        performPhysicalSwipe(
                            specs.screenWidth / 2,
                            (specs.screenHeight * 0.78f).toInt(),
                            specs.screenWidth / 2,
                            (specs.screenHeight * 0.22f).toInt()
                        )
                        stepHistory.add("Closed limited comment sheet and swiped to next video")
                        currentRunSteps.add("Step $stepNumber: Skipped limited comments video")
                        delay(2200L)
                        continue
                    }

                    val isStuck = consecutiveSameScreenCount >= 2

                    hud.updateStatus("📸 Step $stepNumber Capturing...", BotMood.WORKING, taskRunning = true)
                    val screenshot = takeHdDisplayScreenshotWithTimeout(2500L)
                    recordRunBitmapSafely(screenshot)

                    val recalledFixes = memoryVault.recallFixesForApp(currentAppCategory)
                    val nodesPromptList = taggedElements.take(55).joinToString("\n") {
                        "[${it.tagId}] ${it.label} (${it.className}) -> Center(${it.centerX}, ${it.centerY})"
                    }

                    val elapsedSinceLastAi = System.currentTimeMillis() - lastAiCallTimestamp
                    if (elapsedSinceLastAi in 1..2200L) {
                        delay(2200L - elapsedSinceLastAi)
                    }

                    hud.updateStatus("🧠 Step $stepNumber Thinking...", BotMood.THINKING, taskRunning = true)
                    lastAiCallTimestamp = System.currentTimeMillis()

                    val effectiveHistory = if (isStuck) {
                        stepHistory + "⚠️ WARNING: Screen did NOT change after last action! Do NOT repeat the last tap!"
                    } else {
                        stepHistory
                    }

                    val decision = withTimeoutOrNull(25000L) {
                        geminiBrain.decideNextStep(
                            goal = goal,
                            planSteps = plannedSteps,
                            currentStepHistory = effectiveHistory,
                            bodySpecs = specs,
                            numberedNodesList = nodesPromptList,
                            screenshotBitmap = screenshot,
                            isStuck = isStuck,
                            recalledFixes = recalledFixes,
                            isContinuousMonitor = isContinuousMonitor
                        )
                    } ?: AgentStepDecision(action = "WAIT", reason = "Retrying AI...")

                    log("⚡ Step $stepNumber [${decision.modelUsed}] -> ${decision.action}: ${decision.reason}")
                    hud.updateStatus("Step $stepNumber • ${decision.reason}", BotMood.WORKING, taskRunning = true)
                    currentRunSteps.add("Step $stepNumber: ${decision.action} (${decision.reason})")

                    // Check if AI is trying to click the final Share/Send button on a Share sheet!
                    val reasonLower = decision.reason.lowercase(Locale.ROOT)
                    val isShareSheetOpenNow = taggedElements.any {
                        it.label.contains("Write a message", true) ||
                            it.label.contains("Send separately", true)
                    }
                    val wantsToTapFinalSend = isShareSheetOpenNow ||
                        (reasonLower.contains("tapping send") ||
                            reasonLower.contains("tap send") ||
                            reasonLower.contains("send button") ||
                            reasonLower.contains("already selected"))

                    if (wantsToTapFinalSend && (decision.action == "CLICK_TAG" || decision.action == "CLICK_XY")) {
                        if (isSoftKeyboardOpen()) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            delay(900L)
                        }
                        val bottomSendNode = taggedElements.find {
                            (it.label.equals("Send", true) || it.label.contains("Send separately", true)) &&
                                it.centerY > (specs.screenHeight * 0.84f)
                        }
                        val sendX = bottomSendNode?.centerX ?: (specs.screenWidth / 2)
                        val sendY = bottomSendNode?.centerY ?: (specs.screenHeight * 0.938f).toInt()

                        log("🎯 Area Guard Lock: Tapping exact bottom blue Send button at ($sendX, $sendY)!")
                        performPhysicalTap(sendX, sendY)
                        stepHistory.add("MILESTONE_FINAL_SEND_TAPPED: Tapped bottom blue Send button at ($sendX, $sendY)")

                        // 3-Step Post-Click Road-Crossing Proof!
                        val roadCrossed = verifyFinalActionRoadCrossedOrRetry(specs, taggedElements, sendX, sendY)
                        if (roadCrossed && !isContinuousMonitor) {
                            hud.updateStatus("🎉 Task Complete!", BotMood.HAPPY, taskRunning = false)
                            log("✅ Task Completed & Verified via 3-Step Road-Crossing Proof!")
                            finalizeRunAndTriggerAutoReflection("SUCCESS")
                            isAgentRunning = false
                            break
                        }
                        continue
                    }

                    when (decision.action) {
                        "DONE" -> {
                            if (isContinuousMonitor) {
                                hud.updateStatus("⏳ Watching for new reply...", BotMood.THINKING, taskRunning = true)
                                delay(4500L)
                            } else {
                                hud.updateStatus("🎉 Task Complete!", BotMood.HAPPY, taskRunning = false)
                                log("✅ Task Completed Successfully: ${decision.reason}")
                                finalizeRunAndTriggerAutoReflection("SUCCESS")
                                isAgentRunning = false
                                break
                            }
                        }

                        "BACK" -> {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            stepHistory.add("Pressed BACK")
                            delay(1800L)
                        }

                        "LAUNCH_APP" -> {
                            launchAppByNameOrPackage(decision.packageNameOrSetting.ifBlank { decision.text })
                            stepHistory.add("MILESTONE_APP_OPENED: Launched ${decision.packageNameOrSetting}")
                            delay(3200L)
                        }

                        "OPEN_SETTINGS" -> {
                            openSystemSettingsSection(decision.packageNameOrSetting.ifBlank { decision.text })
                            stepHistory.add("Opened Settings ${decision.packageNameOrSetting}")
                            delay(2500L)
                        }

                        "CLICK_TAG" -> {
                            val target = taggedElements.find { it.tagId == decision.tagId }
                            val tapX: Int
                            val tapY: Int
                            if (target != null) {
                                tapX = target.centerX
                                tapY = target.centerY
                                tapElementSmartly(target, forceAccessibilityClick = isStuck)
                                stepHistory.add("Tapped [${target.tagId}] ${target.label}")
                            } else {
                                tapX = convertNormalizedX(decision.x, specs)
                                tapY = convertNormalizedY(decision.y, specs)
                                performPhysicalTap(tapX, tapY)
                                stepHistory.add("Tapped XY($tapX,$tapY)")
                            }

                            if (decision.isFinalAction && !isContinuousMonitor) {
                                val crossed = verifyFinalActionRoadCrossedOrRetry(specs, taggedElements, tapX, tapY)
                                if (crossed) {
                                    hud.updateStatus("🎉 Task Complete!", BotMood.HAPPY, taskRunning = false)
                                    log("✅ Final Step Verified via Road-Crossing Proof!")
                                    finalizeRunAndTriggerAutoReflection("SUCCESS")
                                    isAgentRunning = false
                                    break
                                }
                            } else {
                                delay(2200L + Random.nextLong(150L, 350L))
                            }
                        }

                        "CLICK_XY" -> {
                            val px = convertNormalizedX(decision.x, specs)
                            val py = convertNormalizedY(decision.y, specs)
                            val snapped = findNearestElementToSnap(px, py, taggedElements)
                            if (snapped != null && !isStuck && !decision.isFinalAction) {
                                tapElementSmartly(snapped, forceAccessibilityClick = false)
                                stepHistory.add("Tapped ${snapped.label} at ($px,$py)")
                            } else {
                                performPhysicalTap(px, py)
                                stepHistory.add("Tapped XY($px,$py)")
                            }

                            if (decision.isFinalAction && !isContinuousMonitor) {
                                val crossed = verifyFinalActionRoadCrossedOrRetry(specs, taggedElements, px, py)
                                if (crossed) {
                                    hud.updateStatus("🎉 Task Complete!", BotMood.HAPPY, taskRunning = false)
                                    log("✅ Final Step Verified via Road-Crossing Proof!")
                                    finalizeRunAndTriggerAutoReflection("SUCCESS")
                                    isAgentRunning = false
                                    break
                                }
                            } else {
                                delay(2200L + Random.nextLong(150L, 350L))
                            }
                        }

                        "LONG_PRESS_XY" -> {
                            val px = convertNormalizedX(decision.x, specs)
                            val py = convertNormalizedY(decision.y, specs)
                            performPhysicalLongPress(px, py)
                            stepHistory.add("Long-Pressed ($px,$py)")
                            delay(2000L)
                        }

                        "TYPE" -> {
                            // Smart Comment/Input Box Finder: If on a comment sheet, always target the bottom input box!
                            val bottomCommentBox = taggedElements.find {
                                (it.label.contains("Join the conversation", true) ||
                                    it.label.contains("Add a comment", true) ||
                                    it.className.contains("EditText", true)) &&
                                    it.centerY > (specs.screenHeight * 0.75f)
                            }
                            val target = bottomCommentBox ?: taggedElements.find { it.tagId == decision.tagId }
                            val targetLabel = target?.label ?: ""

                            if (target != null) {
                                tapElementSmartly(target, forceAccessibilityClick = false)
                                delay(950L)
                            } else if (decision.x > 0 && decision.y > 0) {
                                performPhysicalTap(
                                    convertNormalizedX(decision.x, specs),
                                    convertNormalizedY(decision.y, specs)
                                )
                                delay(950L)
                            }

                            val typedOk = typeAndPasteTextReliably(decision.text, target?.node)
                            delay(1000L)

                            if (activePkg.contains("whatsapp") || isContinuousMonitor) {
                                clickWhatsAppSendIfPresent()
                                stepHistory.add("MILESTONE_SENT_MESSAGE: Typed and sent '${decision.text}'")
                            } else {
                                val wasCommentSubmitted = handleSmartPostOrSearchAfterType(
                                    typedText = decision.text,
                                    targetLabel = targetLabel,
                                    decisionReason = decision.reason,
                                    typedOk = typedOk
                                )
                                if (wasCommentSubmitted) {
                                    hasAlreadyCommentedInThisRun = true
                                    stepHistory.add("MILESTONE_COMMENT_POSTED: Typed and posted comment '${decision.text}' and closed comment sheet!")
                                } else {
                                    stepHistory.add("MILESTONE_TYPED_AND_SUBMITTED: Typed '${decision.text}' and submitted")
                                }
                            }
                            delay(1800L)
                        }

                        "SWIPE" -> {
                            val sx = convertNormalizedX(decision.x, specs)
                            val sy = convertNormalizedY(decision.y, specs)
                            val ex = convertNormalizedX(decision.endX, specs)
                            val ey = convertNormalizedY(decision.endY, specs)
                            performPhysicalSwipe(sx, sy, ex, ey)
                            stepHistory.add("Swiped ($sx,$sy)->($ex,$ey)")
                            delay(2200L)
                        }

                        "WAIT" -> {
                            hud.updateStatus("⏳ ${decision.reason}", BotMood.THINKING, taskRunning = true)
                            delay(2500L)
                        }

                        else -> delay(2000L)
                    }
                }
            } catch (_: CancellationException) {
                finalizeRunAndTriggerAutoReflection("STOPPED")
                hud.updateStatus("Stopped", BotMood.IDLE, taskRunning = false)
            } catch (e: Exception) {
                log("⚠️ Task Exception: ${e.message}")
                finalizeRunAndTriggerAutoReflection("FAILED")
                hud.updateStatus("Paused", BotMood.ERROR, taskRunning = false)
            } finally {
                isAgentRunning = false
                releaseWakeLock()
            }
        }
    }

    internal suspend fun runBackgroundWebAgentLoop(goal: String, plannedSteps: List<String>) {
        val webView = hud.getOrCreateBackgroundWebView() ?: return
        val specs = getCalibratedDeviceBodySpecs()
        val history = mutableListOf<String>()

        val lowerGoal = goal.lowercase(Locale.ROOT)
        val initialTargetUrl = when {
            Regex("https?://[^\\s]+").find(goal) != null -> Regex("https?://[^\\s]+").find(goal)!!.value
            lowerGoal.contains("youtube studio") || lowerGoal.contains("yt studio") -> "https://studio.youtube.com"
            lowerGoal.contains("qwen") -> "https://chat.qwen.ai"
            lowerGoal.contains("synthesia") -> "https://www.synthesia.io/features/text-to-video"
            lowerGoal.contains("flow") -> "https://labs.google/fx/tools/flow"
            lowerGoal.contains("freellm") || lowerGoal.contains("3001") -> "http://localhost:3001/models/chat"
            else -> ""
        }
        if (initialTargetUrl.isNotBlank()) {
            withContext(Dispatchers.Main) {
                webView.loadUrl(initialTargetUrl)
            }
            delay(4000L)
        }

        for (step in 1..60) {
            if (!isAgentRunning) break
            hud.updateStatus("🌐 BG Web Step $step...", BotMood.WORKING, taskRunning = true)

            val domTagList = injectVisualNumberStickersInWebView(webView)
            delay(400L)
            val webBitmap = hud.captureWebViewCanvasBitmap()
            recordRunBitmapSafely(webBitmap)

            val decision = geminiBrain.decideNextStep(
                goal = goal,
                planSteps = plannedSteps,
                currentStepHistory = history,
                bodySpecs = specs,
                numberedNodesList = domTagList,
                screenshotBitmap = webBitmap,
                isStuck = false,
                recalledFixes = memoryVault.recallFixesForApp("WebStudio"),
                isContinuousMonitor = false
            )

            log("🌐 BG Web Step $step [${decision.modelUsed}]: ${decision.action} -> ${decision.reason}")
            currentRunSteps.add("Web Step $step: ${decision.action} (${decision.reason})")
            if (decision.action == "DONE") {
                hud.updateStatus("🎉 Background Web Task Done!", BotMood.HAPPY, taskRunning = false)
                break
            }

            executeWebStepInDom(webView, decision)
            history.add("${decision.action}: ${decision.reason}")
            delay(3200L)
        }
    }

    internal suspend fun injectVisualNumberStickersInWebView(webView: WebView): String =
        suspendCancellableCoroutine { cont ->
            val js = """
                (function() {
                    document.querySelectorAll('.auradroid-tag').forEach(e => e.remove());
                    const items = Array.from(document.querySelectorAll('button, a, input, textarea, [role="button"], [contenteditable="true"]'));
                    const summary = [];
                    items.slice(0, 35).forEach((el, idx) => {
                        const r = el.getBoundingClientRect();
                        if (r.width > 10 && r.height > 10) {
                            el.setAttribute('data-aura-id', idx);
                            const badge = document.createElement('div');
                            badge.className = 'auradroid-tag';
                            badge.innerText = '[' + idx + ']';
                            badge.style.cssText = 'position:fixed;left:' + r.left + 'px;top:' + r.top + 'px;background:#FACC15;color:#000;font-size:10px;font-weight:bold;padding:1px 3px;z-index:999999;pointer-events:none;border-radius:3px;';
                            document.body.appendChild(badge);
                            const lbl = (el.innerText || el.getAttribute('placeholder') || el.getAttribute('aria-label') || el.tagName).substring(0, 35).replace(/\n/g, ' ');
                            summary.push('[' + idx + '] ' + lbl);
                        }
                    });
                    return summary.join('\\n');
                })();
            """.trimIndent()
            webView.evaluateJavascript(js) { res: String? ->
                cont.resume(res?.trim('"')?.replace("\\n", "\n") ?: "")
            }
        }

    internal suspend fun executeWebStepInDom(webView: WebView, decision: AgentStepDecision) {
        withContext(Dispatchers.Main) {
            val js = when (decision.action) {
                "TYPE" -> """
                    (function() {
                        const el = document.querySelector('[data-aura-id="${decision.tagId}"]') || document.activeElement;
                        if (el) {
                            el.focus();
                            document.execCommand('selectAll', false, null);
                            document.execCommand('insertText', false, ${JSONObject.quote(decision.text)});
                            el.dispatchEvent(new Event('input', { bubbles: true }));
                        }
                    })();
                """.trimIndent()
                else -> """
                    (function() {
                        const el = document.querySelector('[data-aura-id="${decision.tagId}"]');
                        if (el) el.click();
                    })();
                """.trimIndent()
            }
            webView.evaluateJavascript(js, null)
        }
    }

    internal fun withContextOrMain(webView: WebView, js: String, onResult: (String) -> Unit) {
        mainExecutor.execute {
            try {
                webView.evaluateJavascript(js) { raw: String? ->
                    onResult(raw?.trim('"') ?: "")
                }
            } catch (_: Exception) {
                onResult("")
            }
        }
    }

    /**
     * 24/7 Multi-Time n8n Workflow & Dual-Stage Scheduler Loop:
     * Checks every 10 seconds, normalizes all times to strict 24-Hr "HH:mm" (so "3:36" == "03:36"),
     * and executes every enabled n8n Time Workflow Card + Main Schedule on the exact minute!
     */
    private fun startDailySchedulerLoop() {
        schedulerJob?.cancel()
        schedulerJob = serviceScope.launch {
            var lastTriggeredFlowMinute = ""
            var lastTriggeredUploadMinute = ""
            var lastNightReportDay = ""
            while (isActive) {
                delay(10_000L)
                val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
                if (isAgentRunning) continue

                val nowMs = System.currentTimeMillis()
                val snoozeUntil = prefs.getLong("snooze_until_ms", 0L)
                if (nowMs < snoozeUntil) continue

                val pendingIdleGoal = prefs.getString("pending_idle_goal", "") ?: ""
                if (pendingIdleGoal.isNotBlank() && isPhoneCurrentlyIdleOrFree()) {
                    prefs.edit().remove("pending_idle_goal").apply()
                    log("🌙 Phone is now Free/Idle -> Auto-starting queued task: $pendingIdleGoal")
                    if (pendingIdleGoal == "FLOW_CREATE_ONLY") {
                        val topic = prefs.getString("flow_topic", "Futuristic Sci-Fi World") ?: "Futuristic Sci-Fi World"
                        val credits = prefs.getInt("flow_daily_videos", 1)
                        startGoogleFlowToYouTubePipeline(topic, credits, uploadImmediatelyAfterCreate = false)
                    } else if (pendingIdleGoal == "FOLDER_UPLOAD_ONLY") {
                        val folder = prefs.getString(
                            "yt_folder_path",
                            prefs.getString("custom_download_folder", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
                        ) ?: ""
                        val oldest = prefs.getBoolean("yt_pick_oldest", true)
                        startYouTubeFolderAutoUpload(folder, oldest)
                    } else {
                        startUniversalTask(pendingIdleGoal, emptyList(), "LIVE_PHONE")
                    }
                    continue
                }

                val nowHm = java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date())
                val todayStr = java.text.SimpleDateFormat("dd_MMM", Locale.US).format(java.util.Date())
                val todayMinuteKey = "${todayStr}_$nowHm"

                if (nowHm == "22:00" && lastNightReportDay != todayStr) {
                    lastNightReportDay = todayStr
                    val dailyStatus = prefs.getString("last_daily_action_log", "आज के सभी सिस्टम रेडी हैं!")
                    hud.triggerGreetingPeekIfAllowed("🌙 सर, 10 बज गए! आज की रिपोर्ट: $dailyStatus")
                }

                if (!prefs.getBoolean("scheduler_enabled", true)) continue

                val defaultDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                val folder = prefs.getString("yt_folder_path", prefs.getString("custom_download_folder", defaultDir)) ?: defaultDir
                val oldest = prefs.getBoolean("yt_pick_oldest", true)
                val credits = prefs.getInt("flow_daily_videos", 1).coerceIn(1, 5)
                val mainStoryTopic = prefs.getString("flow_topic", "Young Indian fashion designer story")
                    ?: "Young Indian fashion designer story"

                // 1. Check all enabled n8n Multi-Time Workflow Cards!
                val workflows = loadN8nWorkflowsFromPrefs(prefs)
                var triggeredFromN8nCard = false

                for (wf in workflows) {
                    if (!wf.enabled) continue
                    val wfTriggerHm = normalizeTimeHm24(wf.triggerTimeHm)
                    val wfDelayedHm = if (wf.delayedUploadTimeHm.isNotBlank()) {
                        normalizeTimeHm24(wf.delayedUploadTimeHm)
                    } else {
                        wfTriggerHm
                    }

                    if (nowHm == wfTriggerHm && wf.lastRunMinuteKey != todayMinuteKey) {
                        wf.lastRunMinuteKey = todayMinuteKey
                        lastTriggeredFlowMinute = nowHm
                        saveN8nWorkflowsToPrefs(prefs, workflows)

                        val wfTopic = wf.customStoryPrompt.trim().ifEmpty { mainStoryTopic }
                        prefs.edit().putBoolean("flow_story_mode", wf.storyContinuationMode).apply()
                        if (wf.sourceWebUrl.isNotBlank() && (wf.sourceWebUrl.contains("flow.google") || wf.sourceWebUrl.contains("labs.google"))) {
                            prefs.edit().putString("saved_flow_url", wf.sourceWebUrl).apply()
                        }

                        val uploadImmediately = when (wf.targetActionNode) {
                            "YOUTUBE_DIRECT" -> {
                                prefs.edit().putBoolean("yt_link_enabled", true).apply()
                                true
                            }
                            "INSTA_DIRECT" -> {
                                prefs.edit().putBoolean("yt_link_enabled", false).putBoolean("insta_link_enabled", true).apply()
                                true
                            }
                            "BOTH_DIRECT" -> {
                                prefs.edit().putBoolean("yt_link_enabled", true).putBoolean("insta_link_enabled", true).apply()
                                true
                            }
                            "SAVE_FOLDER_ONLY" -> false
                            else -> (wfTriggerHm == wfDelayedHm)
                        }

                        if (uploadImmediately) {
                            lastTriggeredUploadMinute = nowHm
                        }

                        log("🔗 n8n Workflow Triggered at $nowHm [Source=${wf.sourceNodeType}, Action=${wf.targetActionNode}, StoryEp=${wf.storyContinuationMode}]!")

                        when (wf.sourceNodeType) {
                            "FOLDER_ONLY" -> {
                                startYouTubeFolderAutoUpload(folder, oldest)
                            }
                            "CUSTOM_WEB_URL" -> {
                                val targets = if (wf.targetActionNode == "INSTA_DIRECT") "Instagram Reels" else "YouTube Shorts"
                                startUniversalTask(
                                    "Open ${wf.sourceWebUrl}, generate 9:16 video for '$wfTopic', save to $folder, and upload to $targets",
                                    emptyList(),
                                    "BACKGROUND_WEB"
                                )
                            }
                            else -> {
                                startGoogleFlowToYouTubePipeline(
                                    topic = wfTopic,
                                    maxVideosToCreate = credits,
                                    uploadImmediatelyAfterCreate = uploadImmediately
                                )
                            }
                        }
                        triggeredFromN8nCard = true
                        break
                    }

                    // Check if a workflow has a separate Delayed Upload time
                    if (wf.targetActionNode == "DELAYED_UPLOAD" && wfDelayedHm != wfTriggerHm &&
                        nowHm == wfDelayedHm && lastTriggeredUploadMinute != nowHm
                    ) {
                        lastTriggeredUploadMinute = nowHm
                        log("🔗 n8n Delayed Upload Triggered at $nowHm from Folder [$folder]...")
                        startYouTubeFolderAutoUpload(folder, oldest)
                        triggeredFromN8nCard = true
                        break
                    }
                }

                if (triggeredFromN8nCard) continue

                // 2. Fallback check on primary Flow Create & Folder Upload time boxes (normalized to "HH:mm")
                val flowCreateTimeHm = normalizeTimeHm24(
                    prefs.getString("flow_create_time_hm", prefs.getString("schedule_time_hm", "18:00")) ?: "18:00"
                )
                val folderUploadTimeHm = normalizeTimeHm24(
                    prefs.getString("folder_upload_time_hm", prefs.getString("schedule_time_hm", "19:00")) ?: "19:00"
                )
                val runWhenIdleOnly = prefs.getBoolean("run_only_when_idle", false)
                val isSameTimeForBoth = flowCreateTimeHm == folderUploadTimeHm

                if (nowHm == flowCreateTimeHm && lastTriggeredFlowMinute != nowHm) {
                    lastTriggeredFlowMinute = nowHm
                    if (isSameTimeForBoth) lastTriggeredUploadMinute = nowHm

                    val flowExecMode = prefs.getString("flow_execution_mode", "BACKGROUND") ?: "BACKGROUND"
                    if (runWhenIdleOnly && flowExecMode != "BACKGROUND" && !isPhoneCurrentlyIdleOrFree()) {
                        hud.showScheduledTaskConfirmBanner(
                            taskTitle = "🎬 Flow Video Generation ($flowCreateTimeHm)",
                            onRunNow = { startGoogleFlowToYouTubePipeline(mainStoryTopic, credits, uploadImmediatelyAfterCreate = isSameTimeForBoth) },
                            onSkip10Min = {
                                prefs.edit().putLong("snooze_until_ms", System.currentTimeMillis() + 600_000L).apply()
                                lastTriggeredFlowMinute = ""
                            },
                            onRunWhenIdle = {
                                prefs.edit().putString("pending_idle_goal", "FLOW_CREATE_ONLY").apply()
                            },
                            onRunBackground = {
                                prefs.edit().putString("flow_execution_mode", "BACKGROUND").apply()
                                startGoogleFlowToYouTubePipeline(mainStoryTopic, credits, uploadImmediatelyAfterCreate = isSameTimeForBoth)
                            }
                        )
                    } else {
                        log("⏰ Stage 1 Scheduler ($nowHm): Auto-Creating Flow Video & Saving to [$folder] (UploadNow=$isSameTimeForBoth)...")
                        startGoogleFlowToYouTubePipeline(mainStoryTopic, credits, uploadImmediatelyAfterCreate = isSameTimeForBoth)
                    }
                    continue
                }

                if (!isSameTimeForBoth && nowHm == folderUploadTimeHm && lastTriggeredUploadMinute != nowHm) {
                    lastTriggeredUploadMinute = nowHm
                    val bgUpload = prefs.getBoolean("bg_youtube_upload", false)
                    if (runWhenIdleOnly && !bgUpload && !isPhoneCurrentlyIdleOrFree()) {
                        hud.showScheduledTaskConfirmBanner(
                            taskTitle = "📤 Folder Video Upload ($folderUploadTimeHm)",
                            onRunNow = { startYouTubeFolderAutoUpload(folder, oldest) },
                            onSkip10Min = {
                                prefs.edit().putLong("snooze_until_ms", System.currentTimeMillis() + 600_000L).apply()
                                lastTriggeredUploadMinute = ""
                            },
                            onRunWhenIdle = {
                                prefs.edit().putString("pending_idle_goal", "FOLDER_UPLOAD_ONLY").apply()
                            },
                            onRunBackground = {
                                prefs.edit().putBoolean("bg_youtube_upload", true).apply()
                                startYouTubeFolderAutoUpload(folder, oldest)
                            }
                        )
                    } else {
                        log("⏰ Stage 2 Scheduler ($nowHm): Auto-Uploading Video from Folder [$folder] with Title & Description...")
                        startYouTubeFolderAutoUpload(folder, oldest)
                    }
                }
            }
        }
    }

    /**
     * Universal Interactive Element Scanner:
     * Filters out giant full-sheet container boxes (like `@2131955775`) and "Share on Threads" links
     * so `CLICK_TAG` never taps the middle of a container box instead of a real button!
     */
    internal fun scanScreenInteractiveElements(specs: DeviceBodySpecs): List<TaggedUiElement> {
        val result = mutableListOf<TaggedUiElement>()
        val seenBoundsLabels = mutableSetOf<String>()
        var idCounter = 0

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val nodePkg = node.packageName?.toString() ?: ""
            if (nodePkg == packageName) return

            val rect = Rect()
            node.getBoundsInScreen(rect)

            val isValidSize = rect.width() > 14 && rect.height() > 14 &&
                rect.bottom > specs.cutoutSafeTop && rect.top < specs.screenHeight

            // Universal Big-Container Trap Filter: Ignore giant layout wrappers that cover >65% width AND >35% height!
            val isGiantContainerBox = rect.width() > (specs.screenWidth * 0.65f) &&
                rect.height() > (specs.screenHeight * 0.35f)

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim() ?: ""
            } else ""
            val viewId = node.viewIdResourceName?.substringAfterLast('/') ?: ""

            val label = when {
                text.isNotEmpty() -> text
                desc.isNotEmpty() -> desc
                hint.isNotEmpty() -> hint
                viewId.isNotEmpty() && !viewId.startsWith("@213") -> viewId
                else -> ""
            }

            // Never include "Share on Threads" links inside comment lists
            val isThreadsLink = label.equals("Share on Threads", ignoreCase = true)

            if (isValidSize && !isGiantContainerBox && !isThreadsLink &&
                (node.isClickable || node.isEditable || node.isCheckable || node.isFocused || label.isNotEmpty())
            ) {
                val cleanLabel = label.ifEmpty { "Button" }.replace("\n", " ").take(55)
                val dedupKey = "${cleanLabel}_${rect.centerX() / 12}_${rect.centerY() / 12}"
                if (seenBoundsLabels.add(dedupKey)) {
                    result.add(
                        TaggedUiElement(
                            tagId = idCounter++,
                            node = node,
                            label = cleanLabel,
                            className = node.className?.toString()?.substringAfterLast('.') ?: "View",
                            bounds = rect,
                            centerX = rect.centerX(),
                            centerY = rect.centerY()
                        )
                    )
                }
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            traverse(activeRoot)
        }

        try {
            for (win in windows) {
                if (win.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    win.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER ||
                    win.type == AccessibilityWindowInfo.TYPE_SYSTEM
                ) {
                    val r = win.root
                    if (r != null && r != activeRoot) {
                        traverse(r)
                    }
                }
            }
        } catch (_: Exception) {}

        return result
    }

    private fun buildScreenSignature(pkg: String, elements: List<TaggedUiElement>): String {
        if (elements.isEmpty()) return "$pkg:empty:${isSoftKeyboardOpen()}"
        val topPart = elements.take(10).joinToString("|") { "${it.label}@${it.centerY / 45}" }
        val bottomPart = elements.takeLast(10).joinToString("|") { "${it.label}@${it.centerY / 45}" }
        return "$pkg:${elements.size}:kb=${isSoftKeyboardOpen()}:$topPart#$bottomPart"
    }

    private fun convertNormalizedX(valX: Int, specs: DeviceBodySpecs): Int {
        return if (valX <= 1000) {
            ((valX / 1000f) * specs.screenWidth).toInt().coerceIn(10, specs.screenWidth - 10)
        } else {
            valX.coerceIn(10, specs.screenWidth - 10)
        }
    }

    private fun convertNormalizedY(valY: Int, specs: DeviceBodySpecs): Int {
        val rawPy = if (valY <= 1000) {
            ((valY / 1000f) * specs.screenHeight).toInt()
        } else valY
        return rawPy.coerceIn(specs.cutoutSafeTop + 8, specs.screenHeight - 12)
    }

    private fun findNearestElementToSnap(px: Int, py: Int, elements: List<TaggedUiElement>): TaggedUiElement? {
        return elements.filter { it.node?.isClickable == true || it.node?.isEditable == true }
            .minByOrNull { hypot((it.centerX - px).toDouble(), (it.centerY - py).toDouble()) }
            ?.takeIf { hypot((it.centerX - px).toDouble(), (it.centerY - py).toDouble()) <= 78.0 }
    }

    internal suspend fun tapElementSmartly(element: TaggedUiElement, forceAccessibilityClick: Boolean = false) {
        if (forceAccessibilityClick && element.node != null) {
            var curr: AccessibilityNodeInfo? = element.node
            var clicked = false
            for (depth in 0..3) {
                if (curr == null) break
                if ((curr.isClickable || curr.isEditable) && curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    clicked = true
                    break
                }
                curr = curr.parent
            }
            if (clicked) {
                lastAgentTapTimestamp = System.currentTimeMillis()
                hud.animateHandTap(element.centerX.toFloat(), element.centerY.toFloat())
                delay(150L)
                return
            }
        }
        performPhysicalTap(element.centerX, element.centerY)
    }

    internal suspend fun performPhysicalTap(x: Int, y: Int) {
        lastAgentTapTimestamp = System.currentTimeMillis()
        val jitterX = (x + Random.nextInt(-3, 4)).coerceAtLeast(8)
        val jitterY = (y + Random.nextInt(-3, 4)).coerceAtLeast(8)
        hud.animateHandTap(jitterX.toFloat(), jitterY.toFloat())
        val path = Path().apply { moveTo(jitterX.toFloat(), jitterY.toFloat()) }
        val duration = Random.nextLong(85L, 125L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()
        dispatchGesture(gesture, null, null)
        delay(duration + 35L)
    }

    private suspend fun performPhysicalLongPress(x: Int, y: Int) {
        lastAgentTapTimestamp = System.currentTimeMillis()
        hud.animateHandTap(x.toFloat(), y.toFloat())
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 650L))
            .build()
        dispatchGesture(gesture, null, null)
        delay(700L)
    }

    internal suspend fun performPhysicalSwipe(sx: Int, sy: Int, ex: Int, ey: Int) {
        lastAgentTapTimestamp = System.currentTimeMillis()
        hud.animateHandSwipe(sx.toFloat(), sy.toFloat(), ex.toFloat(), ey.toFloat())
        val path = Path().apply {
            moveTo(sx.toFloat(), sy.toFloat())
            lineTo(ex.toFloat(), ey.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 380L))
            .build()
        dispatchGesture(gesture, null, null)
        delay(420L)
    }

    internal suspend fun typeAndPasteTextReliably(
        textToType: String,
        preferredNode: AccessibilityNodeInfo? = null
    ): Boolean {
        if (textToType.isBlank()) return false
        withContext(Dispatchers.Main) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("aura_text", textToType))
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
        }

        fun tryApplyTextToNode(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
                return true
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
                return true
            }
            return false
        }

        if (preferredNode != null) {
            if (tryApplyTextToNode(preferredNode)) {
                delay(350L)
                return true
            }
        }

        val root = rootInActiveWindow ?: return false
        var setSuccess = false

        val focusedInput = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedInput != null && tryApplyTextToNode(focusedInput)) {
            delay(350L)
            return true
        }

        fun traverseEditable(node: AccessibilityNodeInfo?, requireFocused: Boolean) {
            if (node == null || setSuccess) return
            val supportsSetText = node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_SET_TEXT || it.id == AccessibilityNodeInfo.ACTION_PASTE
            }
            val isEdit = node.isEditable ||
                node.className?.toString()?.contains("EditText", true) == true ||
                supportsSetText
            if (isEdit && (!requireFocused || node.isFocused)) {
                if (tryApplyTextToNode(node)) {
                    setSuccess = true
                    return
                }
            }
            for (i in 0 until node.childCount) {
                traverseEditable(node.getChild(i), requireFocused)
            }
        }

        traverseEditable(root, requireFocused = true)
        if (!setSuccess) {
            traverseEditable(root, requireFocused = false)
        }

        delay(400L)
        return setSuccess
    }

    private suspend fun handleSmartPostOrSearchAfterType(
        typedText: String,
        targetLabel: String,
        decisionReason: String,
        typedOk: Boolean
    ): Boolean {
        val specs = getCalibratedDeviceBodySpecs()
        val elements = scanScreenInteractiveElements(specs)

        val combinedContext = "$targetLabel $decisionReason".lowercase(Locale.ROOT)
        val isCommentContext = combinedContext.contains("comment") ||
            combinedContext.contains("कमेंट") ||
            elements.any { it.label.contains("Join the conversation", true) || it.label.contains("Add a comment", true) }

        val isShareSearchContext = combinedContext.contains("share") ||
            combinedContext.contains("friend") ||
            elements.any { it.label.contains("Send separately", true) || it.label.contains("Write a message", true) }

        if (isShareSearchContext) {
            closeKeyboardCompletely()
            return false
        }

        if (isCommentContext) {
            val postBtn = elements.find {
                it.label.equals("Post", true) ||
                    it.label.equals("Send", true) ||
                    it.label.contains("post_comment", true) ||
                    it.label.equals("पोस्ट करें", true)
            }
            if (postBtn != null) {
                tapElementSmartly(postBtn, forceAccessibilityClick = false)
            } else {
                // Tap the blue upward-arrow Post button on the right side of the comment input bar
                val inputNode = elements.find {
                    it.className.contains("EditText", true) ||
                        it.label.contains("Join the conversation", true) ||
                        it.label.contains(typedText.take(10), true)
                }
                val arrowY = inputNode?.centerY ?: (specs.screenHeight * 0.56f).toInt()
                performPhysicalTap((specs.screenWidth * 0.91f).toInt(), arrowY)
            }
            delay(1800L)

            closeKeyboardCompletely()
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(1200L)
            return true
        }

        val searchSuggestion = elements.find {
            it.label.contains("row_search_for_x", true) ||
                (it.centerY in (specs.cutoutSafeTop + 120)..(specs.screenHeight / 2) &&
                    it.label.equals(typedText.trim(), true) &&
                    !it.className.contains("EditText", true))
        }
        if (searchSuggestion != null) {
            tapElementSmartly(searchSuggestion, forceAccessibilityClick = false)
            return false
        }

        if (isSoftKeyboardOpen() && typedOk) {
            performPhysicalTap((specs.screenWidth * 0.90f).toInt(), (specs.screenHeight * 0.91f).toInt())
        }
        return false
    }

    private suspend fun clickWhatsAppSendIfPresent() {
        val specs = getCalibratedDeviceBodySpecs()
        val elements = scanScreenInteractiveElements(specs)
        val sendNode = elements.find {
            it.label.equals("Send", true) ||
                it.label.contains("send", true) ||
                it.label.contains("भेजें", true)
        }
        if (sendNode != null) {
            tapElementSmartly(sendNode, forceAccessibilityClick = false)
        }
    }

    internal suspend fun closeKeyboardCompletely() {
        var attempts = 0
        while (isSoftKeyboardOpen() && attempts < 2) {
            attempts++
            log("⌨️ Closing keyboard with BACK (#$attempts)")
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(1100L)
        }
    }

    internal suspend fun dismissDiscardDialogIfPresent(specs: DeviceBodySpecs) {
        val els = scanScreenInteractiveElements(specs)
        dismissUnwantedPopupsIfPresent(els, specs)
    }

    internal fun isSoftKeyboardOpen(): Boolean {
        return try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (_: Exception) {
            false
        }
    }

    internal suspend fun takeHdDisplayScreenshotWithTimeout(timeoutMs: Long = 2500L): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val elapsed = System.currentTimeMillis() - lastScreenshotTimestamp
        if (elapsed in 1..1050L) {
            delay(1050L - elapsed)
        }

        suspend fun attemptCapture(): Bitmap? = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                try {
                    lastScreenshotTimestamp = System.currentTimeMillis()
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshotResult: ScreenshotResult) {
                                val hwBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val bmp = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                                hwBuffer.close()
                                if (cont.isActive) cont.resume(bmp)
                            }

                            override fun onFailure(errorCode: Int) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

        val firstTry = attemptCapture()
        if (firstTry != null) return firstTry

        delay(950L)
        return attemptCapture()
    }

    internal fun launchAppByNameOrPackage(query: String) {
        val q = query.trim().lowercase(Locale.ROOT)
        val knownMap = mapOf(
            "instagram" to "com.instagram.android",
            "insta" to "com.instagram.android",
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "gemini" to "com.google.android.apps.bard",
            "chrome" to "com.android.chrome",
            "yt studio" to "com.google.android.apps.youtube.creator",
            "settings" to "com.android.settings"
        )

        val pm = packageManager
        val targetPkg = knownMap.entries.find { q.contains(it.key) }?.value ?: run {
            val installed = pm.getInstalledApplications(0)
            installed.find {
                val label = pm.getApplicationLabel(it).toString().lowercase(Locale.ROOT)
                label.contains(q) || it.packageName.lowercase(Locale.ROOT).contains(q)
            }?.packageName
        }

        if (targetPkg != null) {
            val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(launchIntent)
                log("📱 Directly Launched App: $targetPkg")
                return
            }
        }
        log("⚠️ App not found for '$query'")
    }

    private fun openSystemSettingsSection(section: String) {
        val s = section.lowercase(Locale.ROOT)
        val action = when {
            s.contains("wifi") || s.contains("wi-fi") -> Settings.ACTION_WIFI_SETTINGS
            s.contains("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS
            s.contains("battery") || s.contains("power") -> Intent.ACTION_POWER_USAGE_SUMMARY
            s.contains("display") || s.contains("brightness") -> Settings.ACTION_DISPLAY_SETTINGS
            s.contains("sound") || s.contains("volume") -> Settings.ACTION_SOUND_SETTINGS
            s.contains("accessibility") -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            log("⚙️ Opened System Settings: $section")
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
