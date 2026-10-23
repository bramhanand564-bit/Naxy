Package com.auradroid.os

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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.StrictMode
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.MotionEvent
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
import java.io.File
import java.io.FileOutputStream
import java.net.URL
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
                if (liveLogs.size > 200) {
                    liveLogs.removeAt(liveLogs.lastIndex)
                }
            }
            instance?.memoryVault?.appendPersistentLog(line)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentTaskJob: Job? = null
    private var schedulerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    lateinit var hud: AiOverlayHud
    lateinit var memoryVault: MemoryVault
    lateinit var geminiBrain: GeminiBrain

    @Volatile
    private var isAgentRunning = false
    private var currentTaskGoal = ""
    private var currentAppCategory = "General"
    private var lastStepScreenshot: Bitmap? = null
    private var lastAgentTapTimestamp = 0L
    private var lastUserScreenInteractionTimeMs = System.currentTimeMillis()
    private var lastScreenshotTimestamp = 0L
    private var lastAiCallTimestamp = 0L
    private var consecutiveSameScreenCount = 0
    private var lastScreenSignature = ""

    private val currentRunBitmaps = mutableListOf<Bitmap>()
    private val currentRunSteps = mutableListOf<String>()
    private val currentPlannedSteps = mutableListOf<String>()
    private var activeRunId = ""
    private var activeRunTitle = ""
    private var activeRunFinalized = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("gemini_api_key", "") ?: ""

        memoryVault = MemoryVault(this)
        geminiBrain = GeminiBrain(savedKey).apply {
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

    private fun recordRunBitmapSafely(bmp: Bitmap?) {
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
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuraDroidOS::AutonomousWakeLock")
            }
            if (wakeLock?.isHeld == false) wakeLock?.acquire()
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
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

    /**
     * Checks if the user is currently NOT actively touching/using the phone (Idle for >= 90 seconds or screen off)
     */
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

    private fun finalizeRunAndTriggerAutoReflection(finalStatus: String) {
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

        if (rBitmaps.isNotEmpty() || rSteps.isNotEmpty()) {
            serviceScope.launch(Dispatchers.IO) {
                log("🧠 Post-Run Auto-Reflection started for '$rTitle' [$finalStatus]...")
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
                    sender = "Auto-Reflection (${reflection.modelUsed})",
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
                log("✨ Auto-Reflection Complete! Saved ${reflection.learnedRules.size} optimized rules in Memory/$rCat.")
            }
        }
    }

    fun stopCurrentTask(reason: String = "Stopped") {
        if (isAgentRunning) {
            val status = if (reason.contains("Complete", true)) "SUCCESS" else "STOPPED"
            finalizeRunAndTriggerAutoReflection(status)
        }
        isAgentRunning = false
        currentTaskJob?.cancel()
        releaseWakeLock()
        hud.updateStatus(reason, BotMood.IDLE, taskRunning = false)
        log("■ Task Stopped: $reason")
    }

    private suspend fun hideWebPreviewCardIfOpen() {
        withContext(Dispatchers.Main) {
            try {
                (hud.getOrCreateBackgroundWebView()?.parent as? View)?.visibility = View.GONE
            } catch (_: Exception) {}
        }
    }

    /**
     * Universal Autonomous Goal-Based Executor + Idle Mode + Chat Scheduler & Multi-Platform Trigger
     */
    fun startUniversalTask(
        goal: String,
        plannedSteps: List<String> = emptyList(),
        forceMode: String? = null
    ) {
        currentTaskJob?.cancel()
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        geminiBrain.updateApiKey(prefs.getString("gemini_api_key", "") ?: "")
        AuraPluginStore.syncFromContext(this)

        val execMode = forceMode ?: prefs.getString("execution_mode", "LIVE_PHONE") ?: "LIVE_PHONE"

        if (execMode.startsWith("SCHEDULE_YT_")) {
            val timeHm = execMode.removePrefix("SCHEDULE_YT_").trim().ifEmpty { "19:00" }
            val pipeType = if (goal.contains("flow", true)) "FLOW_YT" else "FOLDER_YT"
            prefs.edit()
                .putBoolean("scheduler_enabled", true)
                .putString("schedule_time_hm", timeHm)
                .putString("scheduled_pipeline_type", pipeType)
                .apply()
            hud.updateStatus("⏰ Daily Upload Set: $timeHm", BotMood.HAPPY, taskRunning = false)
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
            startGoogleFlowToYouTubePipeline(topic, count)
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
                    stepHistory.add("Opened $autoTargetApp app")
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

                    val taggedElements = scanScreenInteractiveElements(specs)
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
                                it.label.contains("Add a comment", true) ||
                                it.label.equals("Reply", true)
                        }
                        if (commentSheetStillOpen) {
                            log("⌨️ Comment already posted! Auto-closing Comment Sheet to expose Share button...")
                            hud.updateStatus("Closing Comment Sheet...", BotMood.WORKING, taskRunning = true)
                            closeKeyboardCompletely()
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            delay(1400L)
                            stepHistory.add("Closed comment sheet to return to Reel screen")
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
                        log("⚠️ Detected limited comments! Swiping to next Reel...")
                        hud.updateStatus("Comments limited -> Next Reel", BotMood.WORKING, taskRunning = true)
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        delay(1200L)
                        performPhysicalSwipe(
                            specs.screenWidth / 2,
                            (specs.screenHeight * 0.78f).toInt(),
                            specs.screenWidth / 2,
                            (specs.screenHeight * 0.22f).toInt()
                        )
                        stepHistory.add("Closed limited comment sheet and swiped to next Reel")
                        currentRunSteps.add("Step $stepNumber: Skipped limited comments reel")
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
                    if (elapsedSinceLastAi in 1..2500L) {
                        delay(2500L - elapsedSinceLastAi)
                    }

                    hud.updateStatus("🧠 Step $stepNumber Thinking...", BotMood.THINKING, taskRunning = true)
                    lastAiCallTimestamp = System.currentTimeMillis()

                    val effectiveHistory = if (isStuck) {
                        stepHistory + "⚠️ WARNING: Screen did NOT change after last action! Do NOT repeat the last tap!"
                    } else {
                        stepHistory
                    }

                    val decision = withTimeoutOrNull(24000L) {
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
                            stepHistory.add("Launched ${decision.packageNameOrSetting}")
                            delay(3200L)
                        }

                        "OPEN_SETTINGS" -> {
                            openSystemSettingsSection(decision.packageNameOrSetting.ifBlank { decision.text })
                            stepHistory.add("Opened Settings ${decision.packageNameOrSetting}")
                            delay(2500L)
                        }

                        "CLICK_TAG" -> {
                            val target = taggedElements.find { it.tagId == decision.tagId }
                            if (target != null) {
                                tapElementSmartly(target, forceAccessibilityClick = isStuck)
                                stepHistory.add("Tapped [${target.tagId}] ${target.label}")
                            } else {
                                val px = convertNormalizedX(decision.x, specs)
                                val py = convertNormalizedY(decision.y, specs)
                                performPhysicalTap(px, py)
                                stepHistory.add("Tapped XY($px,$py)")
                            }
                            delay(2200L + Random.nextLong(150L, 350L))
                        }

                        "CLICK_XY" -> {
                            val px = convertNormalizedX(decision.x, specs)
                            val py = convertNormalizedY(decision.y, specs)
                            val snapped = findNearestElementToSnap(px, py, taggedElements)
                            if (snapped != null && !isStuck) {
                                tapElementSmartly(snapped, forceAccessibilityClick = false)
                                stepHistory.add("Tapped ${snapped.label} at ($px,$py)")
                            } else {
                                performPhysicalTap(px, py)
                                stepHistory.add("Tapped XY($px,$py)")
                            }
                            delay(2200L + Random.nextLong(150L, 350L))
                        }

                        "LONG_PRESS_XY" -> {
                            val px = convertNormalizedX(decision.x, specs)
                            val py = convertNormalizedY(decision.y, specs)
                            performPhysicalLongPress(px, py)
                            stepHistory.add("Long-Pressed ($px,$py)")
                            delay(2000L)
                        }

                        "TYPE" -> {
                            val target = taggedElements.find { it.tagId == decision.tagId }
                            val targetLabel = target?.label ?: ""
                            if (target != null) {
                                tapElementSmartly(target, forceAccessibilityClick = false)
                                delay(900L)
                            } else if (decision.x > 0 && decision.y > 0) {
                                performPhysicalTap(
                                    convertNormalizedX(decision.x, specs),
                                    convertNormalizedY(decision.y, specs)
                                )
                                delay(900L)
                            }

                            val typedOk = typeAndPasteTextReliably(decision.text, target?.node)
                            delay(1000L)

                            if (activePkg.contains("whatsapp") || isContinuousMonitor) {
                                clickWhatsAppSendIfPresent()
                                stepHistory.add("Typed and sent '${decision.text}'")
                            } else if (activePkg.contains("instagram") || activePkg.contains("youtube")) {
                                val wasCommentSubmitted = handleSmartPostOrSearchAfterType(
                                    typedText = decision.text,
                                    targetLabel = targetLabel,
                                    decisionReason = decision.reason,
                                    typedOk = typedOk
                                )
                                if (wasCommentSubmitted) {
                                    hasAlreadyCommentedInThisRun = true
                                    stepHistory.add("COMPLETED COMMENT: Typed and posted comment '${decision.text}' and closed comment sheet!")
                                } else {
                                    stepHistory.add("Typed '${decision.text}' and submitted")
                                }
                            } else {
                                stepHistory.add("Typed '${decision.text}'")
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
                            delay(2800L)
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

    fun startSinglePickedVideoYouTubeUpload(videoUri: Uri, rawDisplayName: String) {
        currentTaskJob?.cancel()
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        geminiBrain.updateApiKey(prefs.getString("gemini_api_key", "") ?: "")

        activeRunId = "run_yt_${System.currentTimeMillis()}"
        activeRunTitle = "YouTube Upload: ${rawDisplayName.take(20)}"
        activeRunFinalized = false
        currentAppCategory = "YouTube"
        currentTaskGoal = "Upload video $rawDisplayName to YouTube Shorts"
        currentPlannedSteps.clear()
        currentPlannedSteps.addAll(listOf("Click Next on Trimmer", "Click Next on Editor", "Paste Caption", "Click Upload Short"))
        synchronized(currentRunBitmaps) { currentRunBitmaps.clear() }
        currentRunSteps.clear()

        currentTaskJob = serviceScope.launch {
            try {
                isAgentRunning = true
                acquireWakeLock()
                hud.updateStatus("Generating Viral Title...", BotMood.THINKING, taskRunning = true)
                val cleanTopic = rawDisplayName.substringBeforeLast(".").replace("_", " ").ifBlank { "Amazing Viral Short Video" }
                val (_, viralTitle) = geminiBrain.generateFlowSafePromptAndTitle(cleanTopic)
                log("✨ Generated Viral Title: $viralTitle")
                dispatchVideoToLinkedPlatforms(videoUri, viralTitle)
                memoryVault.markVideoAsPostedInLedger(rawDisplayName, "YouTube")
                finalizeRunAndTriggerAutoReflection("SUCCESS")
            } catch (_: CancellationException) {
                finalizeRunAndTriggerAutoReflection("STOPPED")
                hud.updateStatus("Stopped", BotMood.IDLE, taskRunning = false)
            } catch (e: Exception) {
                log("⚠️ Upload Error: ${e.message}")
                finalizeRunAndTriggerAutoReflection("FAILED")
                hud.updateStatus("Upload Paused", BotMood.ERROR, taskRunning = false)
            } finally {
                isAgentRunning = false
                releaseWakeLock()
            }
        }
    }

    fun startYouTubeFolderAutoUpload(folderInputRaw: String, pickOldestFirst: Boolean) {
        currentTaskJob?.cancel()
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        geminiBrain.updateApiKey(prefs.getString("gemini_api_key", "") ?: "")

        activeRunId = "run_yt_${System.currentTimeMillis()}"
        activeRunTitle = "YouTube Folder Auto-Upload"
        activeRunFinalized = false
        currentAppCategory = "YouTube"
        currentTaskGoal = "Upload video from folder $folderInputRaw to YouTube Shorts"
        currentPlannedSteps.clear()
        currentPlannedSteps.addAll(listOf("Select Video", "Tap Next twice", "Paste Title in Caption box", "Tap Upload Short"))
        synchronized(currentRunBitmaps) { currentRunBitmaps.clear() }
        currentRunSteps.clear()

        currentTaskJob = serviceScope.launch {
            try {
                isAgentRunning = true
                acquireWakeLock()

                val resolvedDir = resolveSmartFolderPath(folderInputRaw)
                val uploadedSet = prefs.getStringSet("uploaded_videos_set", emptySet())?.toMutableSet() ?: mutableSetOf()
                log("📂 Scanning Folder: ${resolvedDir.absolutePath}")

                val videos = findVideoFilesInDirectory(resolvedDir, uploadedSet)

                if (videos.isEmpty()) {
                    val mediaUriPair = queryMediaStoreVideoByFolderName(folderInputRaw, pickOldestFirst, uploadedSet)
                    if (mediaUriPair != null) {
                        val (uri, name) = mediaUriPair
                        log("🎬 Found Video via MediaStore: $name")
                        hud.updateStatus("Generating Viral Title...", BotMood.THINKING, taskRunning = true)
                        val (_, viralTitle) = geminiBrain.generateFlowSafePromptAndTitle(name.substringBeforeLast("."))
                        dispatchVideoToLinkedPlatforms(uri, viralTitle)
                        uploadedSet.add(uri.toString())
                        prefs.edit().putStringSet("uploaded_videos_set", uploadedSet).apply()
                        memoryVault.markVideoAsPostedInLedger(name, "YouTube")
                        finalizeRunAndTriggerAutoReflection("SUCCESS")
                        return@launch
                    }

                    hud.updateStatus("No new videos found!", BotMood.ERROR, taskRunning = false)
                    log("⚠️ No un-uploaded videos found in [${resolvedDir.absolutePath}].")
                    finalizeRunAndTriggerAutoReflection("FAILED")
                    return@launch
                }

                val chosenVideo = if (pickOldestFirst) {
                    videos.minByOrNull { it.lastModified() }!!
                } else {
                    videos.maxByOrNull { it.lastModified() }!!
                }

                log("🎬 Selected Video: ${chosenVideo.name}")
                hud.updateStatus("Generating Viral Title...", BotMood.THINKING, taskRunning = true)

                val (_, viralTitle) = geminiBrain.generateFlowSafePromptAndTitle(
                    chosenVideo.nameWithoutExtension.replace("_", " ")
                )
                log("✨ AI Viral Title: $viralTitle")

                dispatchVideoToLinkedPlatforms(Uri.fromFile(chosenVideo), viralTitle)

                uploadedSet.add(chosenVideo.absolutePath)
                prefs.edit().putStringSet("uploaded_videos_set", uploadedSet).apply()
                memoryVault.markVideoAsPostedInLedger(chosenVideo.absolutePath, "YouTube")
                finalizeRunAndTriggerAutoReflection("SUCCESS")
            } catch (_: CancellationException) {
                finalizeRunAndTriggerAutoReflection("STOPPED")
                hud.updateStatus("Stopped", BotMood.IDLE, taskRunning = false)
            } catch (e: Exception) {
                log("⚠️ YouTube Upload Error: ${e.message}")
                finalizeRunAndTriggerAutoReflection("FAILED")
                hud.updateStatus("Upload Paused", BotMood.ERROR, taskRunning = false)
            } finally {
                isAgentRunning = false
                releaseWakeLock()
            }
        }
    }

    /**
     * Routes the video upload to YouTube (Background Web Studio OR Live App) AND Instagram Reels if linked!
     * Reads both MainActivity keys ('bg_youtube_upload', 'yt_link_enabled', 'insta_link_enabled') and legacy keys.
     */
    private suspend fun dispatchVideoToLinkedPlatforms(videoUri: Uri, viralTitle: String) {
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val useBackgroundYtUpload = prefs.getBoolean(
            "bg_youtube_upload",
            prefs.getBoolean("yt_upload_in_background", false)
        )
        val linkYoutube = prefs.getBoolean(
            "yt_link_enabled",
            prefs.getBoolean("link_platform_youtube", true)
        )
        val linkInstagram = prefs.getBoolean(
            "insta_link_enabled",
            prefs.getBoolean("link_platform_instagram", false)
        )

        if (linkYoutube) {
            if (useBackgroundYtUpload) {
                val bgOk = uploadVideoInBackgroundWebStudio(videoUri, viralTitle)
                if (!bgOk && isAgentRunning) {
                    log("🔄 Background Studio needs interactive session -> Completing via YouTube Shorts...")
                    uploadVideoUriToYouTubeShorts(videoUri, viralTitle)
                }
            } else {
                uploadVideoUriToYouTubeShorts(videoUri, viralTitle)
            }
        }

        if (linkInstagram && isAgentRunning) {
            delay(3000L)
            uploadVideoUriToInstagramReels(videoUri, viralTitle)
        }
    }

    /**
     * Silent Background YouTube Studio Web Uploader (Hermes CDP-style):
     * Handles file injection, Title/Description, Not Made for Kids, Public radio, AND "Publish anyway" content-check dialog!
     */
    private suspend fun uploadVideoInBackgroundWebStudio(videoUri: Uri, viralTitle: String): Boolean {
        val webView = hud.getOrCreateBackgroundWebView() ?: return false
        hud.pendingUploadFileUri = videoUri
        hud.updateStatus("🌐 Hermes BG YouTube Upload...", BotMood.UPLOAD_UP, taskRunning = true)
        log("🌐 Starting Hermes Silent Background YouTube Studio Upload for: $viralTitle")

        withContext(Dispatchers.Main) {
            webView.settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            webView.loadUrl("https://www.youtube.com/upload")
        }
        delay(6500L)

        var fileInjected = false
        var clickedPublishOnce = false

        for (step in 1..18) {
            if (!isAgentRunning) return false
            val jsRes = evaluateWebStudioUploadStep(webView, viralTitle)
            log("🌐 Hermes BG Studio Step #$step -> $jsRes")

            if (jsRes.contains("FILE_INPUT_TRIGGERED") || jsRes.contains("CLICKED_SELECT_FILES")) {
                fileInjected = true
            }
            if (jsRes.contains("CLICKED_PUBLISH_ANYWAY") || (clickedPublishOnce && jsRes.contains("DIALOG_CLOSED_SUCCESS"))) {
                hud.updateStatus("🎉 BG UPLOAD & PUBLISH SUCCESS!", BotMood.HAPPY, taskRunning = false)
                log("🎉 Hermes Background YouTube Upload Completed: $viralTitle")
                hud.pendingUploadFileUri = null
                return true
            }
            if (jsRes.contains("PUBLISHED_DONE")) {
                clickedPublishOnce = true
                // Wait 1 extra cycle to catch the "We're still checking your content -> Publish anyway" popup if it appears!
                delay(3000L)
                val checkPopupRes = evaluateWebStudioUploadStep(webView, viralTitle)
                log("🌐 Post-Publish Check Dialog -> $checkPopupRes")
                hud.updateStatus("🎉 BG UPLOAD SUCCESS!", BotMood.HAPPY, taskRunning = false)
                hud.pendingUploadFileUri = null
                return true
            }
            delay(3500L)
        }

        hud.pendingUploadFileUri = null
        return fileInjected
    }

    private suspend fun evaluateWebStudioUploadStep(webView: WebView, viralTitle: String): String =
        suspendCancellableCoroutine { cont ->
            val js = """
                (function() {
                    // 1. Hermes Check: Handle "We're still checking your content" -> "Publish anyway" button!
                    const allButtons = Array.from(document.querySelectorAll('ytcp-button, button, [role="button"]'));
                    const publishAnywayBtn = allButtons.find(b => {
                        const txt = (b.innerText || b.getAttribute('aria-label') || '').toLowerCase();
                        return txt.includes('publish anyway') || b.id === 'secondary-action-button';
                    });
                    if (publishAnywayBtn && publishAnywayBtn.getBoundingClientRect().width > 10) {
                        publishAnywayBtn.click();
                        return 'CLICKED_PUBLISH_ANYWAY';
                    }

                    // 2. File input injection
                    const fileInput = document.querySelector('input[type="file"]');
                    if (fileInput && !window.__auraFileInjected) {
                        window.__auraFileInjected = true;
                        fileInput.click();
                        return 'FILE_INPUT_TRIGGERED';
                    }
                    const selectBtn = document.querySelector('#select-files-button, ytcp-button#select-files-button');
                    if (selectBtn && !window.__auraFileInjected) {
                        selectBtn.click();
                        return 'CLICKED_SELECT_FILES';
                    }

                    // 3. Title & Description boxes
                    const titleBox = document.querySelector('#textbox[aria-label*="title" i], #title-textarea #textbox');
                    if (titleBox && (!titleBox.innerText || titleBox.innerText.length < 5)) {
                        titleBox.focus();
                        titleBox.innerText = ${JSONObject.quote(viralTitle)};
                        titleBox.dispatchEvent(new InputEvent('input', { bubbles: true }));
                    }

                    // 4. Audience: Not Made For Kids
                    const notForKids = document.querySelector('tp-yt-paper-radio-button[name="VIDEO_MADE_FOR_KIDS_NOT_MFK"], #audience tp-yt-paper-radio-button:nth-child(2)');
                    if (notForKids) notForKids.click();

                    // 5. Visibility: Public + Publish (#done-button)
                    const doneBtn = document.querySelector('#done-button, ytcp-button#done-button');
                    if (doneBtn && !doneBtn.disabled && doneBtn.getBoundingClientRect().width > 10) {
                        const publicRadio = document.querySelector('tp-yt-paper-radio-button[name="PUBLIC"]');
                        if (publicRadio) publicRadio.click();
                        setTimeout(() => { doneBtn.click(); }, 400);
                        return 'PUBLISHED_DONE';
                    }

                    // 6. Step Next button
                    const nextBtn = document.querySelector('#next-button, ytcp-button#next-button');
                    if (nextBtn && !nextBtn.disabled && nextBtn.getBoundingClientRect().width > 10) {
                        nextBtn.click();
                        return 'CLICKED_STUDIO_NEXT';
                    }

                    return 'WAITING_STUDIO';
                })();
            """.trimIndent()
            withContextOrMain(webView, js) { res ->
                cont.resume(res)
            }
        }

    /**
     * Multi-Platform Linker: Auto-Uploads the same video to Instagram Reels!
     */
    suspend fun uploadVideoUriToInstagramReels(videoUri: Uri, caption: String) {
        hideWebPreviewCardIfOpen()
        val specs = getCalibratedDeviceBodySpecs()
        log("📸 Multi-Platform Link: Sharing Video to Instagram Reels...")
        hud.updateStatus("📸 Sharing to Instagram Reels...", BotMood.WORKING, taskRunning = true)

        withContext(Dispatchers.Main) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("aura_ig_caption", caption))

            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                setPackage("com.instagram.android")
                putExtra(Intent.EXTRA_STREAM, videoUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {
                log("⚠️ Instagram app not installed.")
                return@withContext
            }
        }

        delay(4500L)
        for (step in 1..4) {
            if (!isAgentRunning) return
            val els = scanScreenInteractiveElements(specs)
            val reelOrNext = els.find {
                it.label.equals("Reels", true) ||
                    it.label.equals("Next", true) ||
                    it.label.equals("आगे बढ़ें", true)
            }
            if (reelOrNext != null) {
                tapElementSmartly(reelOrNext, forceAccessibilityClick = false)
                delay(3200L)
            }
            val captionBox = els.find {
                it.label.contains("Write a caption", true) ||
                    it.label.contains("Add a caption", true) ||
                    it.className.contains("EditText", true)
            }
            if (captionBox != null) {
                tapElementSmartly(captionBox, forceAccessibilityClick = false)
                delay(900L)
                typeAndPasteTextReliably(caption, captionBox.node)
                delay(1000L)
                closeKeyboardCompletely()
                break
            }
        }

        val finalEls = scanScreenInteractiveElements(specs)
        val shareBtn = finalEls.find {
            it.label.equals("Share", true) || it.label.equals("शेयर करें", true)
        }
        if (shareBtn != null) {
            tapElementSmartly(shareBtn, forceAccessibilityClick = false)
        } else {
            performPhysicalTap((specs.screenWidth * 0.72f).toInt(), (specs.screenHeight * 0.94f).toInt())
        }
        delay(3500L)
        hud.updateStatus("🎉 Insta Reel Shared!", BotMood.HAPPY, taskRunning = false)
        log("🎉 Multi-Platform: Instagram Reel Shared Successfully!")
    }

    private fun resolveSmartFolderPath(rawInput: String): File {
        val rootStorage = Environment.getExternalStorageDirectory()
        val cleaned = rawInput.trim()
        val lower = cleaned.lowercase(Locale.ROOT)

        if (cleaned.isEmpty() || lower == "download" || lower == "downloads" || lower == "/storage/download") {
            return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }
        if (lower == "storage" || lower == "/storage") {
            return rootStorage
        }
        if (lower == "camera" || lower == "dcim") {
            return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
        }
        if (lower == "movies" || lower == "video" || lower == "videos") {
            return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        }

        val directFile = File(cleaned)
        if (directFile.exists() && directFile.isDirectory) return directFile

        val relative = File(rootStorage, cleaned.removePrefix("/").removePrefix("storage/"))
        if (relative.exists() && relative.isDirectory) return relative

        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    private fun findVideoFilesInDirectory(dir: File, uploadedSet: Set<String>): List<File> {
        val results = mutableListOf<File>()
        if (!dir.exists()) return results

        fun isVideo(f: File): Boolean {
            val n = f.name.lowercase(Locale.ROOT)
            return f.isFile &&
                (n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv") || n.endsWith(".webm")) &&
                !uploadedSet.contains(f.absolutePath) &&
                !memoryVault.isVideoPostedInLedger(f.absolutePath)
        }

        dir.listFiles()?.forEach { f ->
            if (isVideo(f)) {
                results.add(f)
            } else if (f.isDirectory && !f.name.startsWith(".")) {
                f.listFiles()?.forEach { sub ->
                    if (isVideo(sub)) results.add(sub)
                }
            }
        }
        return results
    }

    private fun queryMediaStoreVideoByFolderName(
        folderKeyword: String,
        pickOldestFirst: Boolean,
        uploadedSet: Set<String>
    ): Pair<Uri, String>? {
        return try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED
            )
            val sortOrder = if (pickOldestFirst) "${MediaStore.Video.Media.DATE_ADDED} ASC" else "${MediaStore.Video.Media.DATE_ADDED} DESC"
            val cursor = contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            ) ?: return null

            val kw = folderKeyword.trim().lowercase(Locale.ROOT).substringAfterLast("/")
            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: "video.mp4"
                    val bucket = (c.getString(bucketCol) ?: "").lowercase(Locale.ROOT)
                    val contentUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())

                    val matchesBucket = kw.isEmpty() || kw == "storage" || kw == "download" || bucket.contains(kw) || kw.contains(bucket)
                    if (matchesBucket && !uploadedSet.contains(contentUri.toString()) && !memoryVault.isVideoPostedInLedger(name)) {
                        return Pair(contentUri, name)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun uploadVideoUriToYouTubeShorts(videoUri: Uri, viralTitle: String) {
        hideWebPreviewCardIfOpen()
        val specs = getCalibratedDeviceBodySpecs()
        val recalledYouTubeRules = memoryVault.recallFixesForApp("YouTube")
        if (recalledYouTubeRules.isNotEmpty()) {
            log("🧠 Applied ${recalledYouTubeRules.size} Learned Rules from Memory/YouTube!")
        }

        withContext(Dispatchers.Main) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("aura_yt_title", viralTitle))

            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                setPackage("com.google.android.youtube")
                putExtra(Intent.EXTRA_STREAM, videoUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }

        hud.updateStatus("Opening YouTube Shorts...", BotMood.WORKING, taskRunning = true)
        log("🚀 Sent video to YouTube Shorts. Waiting for Trimmer...")
        delay(5000L)

        for (attempt in 1..2) {
            if (!isAgentRunning) return
            val screenElements = scanScreenInteractiveElements(specs)

            val alreadyOnAddDetails = screenElements.any {
                it.label.contains("Add details", true) ||
                    it.label.contains("Caption your Short", true) ||
                    it.label.contains("Create a title", true) ||
                    it.label.contains("Upload Short", true)
            }
            if (alreadyOnAddDetails) {
                log("✅ Already on 'Add details' screen! Skipping Next (#$attempt).")
                break
            }

            recordRunBitmapSafely(takeHdDisplayScreenshotWithTimeout(2000L))
            val nextNode = screenElements.find {
                it.label.equals("Next", true) || it.label.equals("आगे बढ़ें", true) || it.label.equals("Done", true)
            }

            val learnedNext = recalledYouTubeRules.find {
                it.solutionRule.contains("Next", true) && it.tapYRatio > 0.85f
            }

            val fallbackNextX = if (attempt == 1) (specs.screenWidth * 0.89f).toInt() else (specs.screenWidth * 0.74f).toInt()
            val tapX = nextNode?.centerX
                ?: learnedNext?.let { (it.tapXRatio * specs.screenWidth).toInt() }
                ?: fallbackNextX
            val tapY = nextNode?.centerY
                ?: learnedNext?.let { (it.tapYRatio * specs.screenHeight).toInt() }
                ?: (specs.screenHeight * 0.96f).toInt()

            hud.updateStatus("Tap: Next (#$attempt)", BotMood.WORKING, taskRunning = true)
            log("👆 Tapping Next button ($tapX, $tapY)")
            performPhysicalTap(tapX, tapY)
            currentRunSteps.add("Tapped Next (#$attempt) at ($tapX, $tapY)")
            delay(4200L)
        }

        for (waitLoad in 1..3) {
            val checkEls = scanScreenInteractiveElements(specs)
            val loaded = checkEls.any {
                it.label.contains("Caption", true) ||
                    it.label.contains("Create a title", true) ||
                    it.label.contains("Upload", true) ||
                    it.label.contains("Public", true)
            }
            if (loaded) break
            delay(1500L)
        }

        dismissDiscardDialogIfPresent(specs)

        recordRunBitmapSafely(takeHdDisplayScreenshotWithTimeout(2000L))
        hud.updateStatus("Typing Viral Caption...", BotMood.WORKING, taskRunning = true)

        val detailsElements = scanScreenInteractiveElements(specs)
        val captionNode = detailsElements.find {
            it.label.contains("Caption your Short", true) ||
                it.label.contains("Create a title", true) ||
                it.label.contains("Add a title", true) ||
                (it.className.contains("EditText", true) && it.centerY < specs.screenHeight / 2)
        }

        val isRegularUploadScreen = detailsElements.any { it.label.contains("Create a title", true) }
        val capX = captionNode?.centerX ?: if (isRegularUploadScreen) {
            (specs.screenWidth * 0.45f).toInt()
        } else {
            (specs.screenWidth * 0.56f).toInt()
        }
        val capY = captionNode?.centerY ?: if (isRegularUploadScreen) {
            (specs.screenHeight * 0.43f).toInt()
        } else {
            (specs.screenHeight * 0.118f).toInt()
        }

        log("👆 Tapping Caption/Title Box at ($capX, $capY)")
        performPhysicalTap(capX, capY)
        currentRunSteps.add("Tapped Caption Box at ($capX, $capY)")
        delay(1500L)

        val pastedSuccess = pasteTitleIntoYouTubeCaptionBoxCleanly(viralTitle, specs)
        log("✍️ Caption Paste Verified ($pastedSuccess): $viralTitle")
        currentRunSteps.add("Pasted Viral Title ($pastedSuccess): $viralTitle")
        delay(1100L)

        for (retry in 1..3) {
            if (!isAgentRunning) return
            dismissDiscardDialogIfPresent(specs)
            val snap = takeHdDisplayScreenshotWithTimeout(2000L)
            recordRunBitmapSafely(snap)

            val postElements = scanScreenInteractiveElements(specs)
            val uploadNode = postElements.find {
                it.label.contains("Upload Short", true) ||
                    it.label.equals("Upload", true) ||
                    it.label.contains("शॉर्ट अपलोड करें", true)
            }

            if (uploadNode != null) {
                hud.updateStatus("Tap: Upload Short (#$retry)", BotMood.WORKING, taskRunning = true)
                log("👆 Tapping visible Upload Short button at (${uploadNode.centerX}, ${uploadNode.centerY})")
                performPhysicalTap(uploadNode.centerX, uploadNode.centerY)
                currentRunSteps.add("Tapped Upload Short (#$retry) at (${uploadNode.centerX}, ${uploadNode.centerY})")
            } else {
                if (isSoftKeyboardOpen()) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    delay(1200L)
                    dismissDiscardDialogIfPresent(specs)
                }
                val reScan = scanScreenInteractiveElements(specs)
                val btnAfterClose = reScan.find {
                    it.label.contains("Upload Short", true) || it.label.equals("Upload", true)
                }
                val tapX = btnAfterClose?.centerX ?: (specs.screenWidth / 2)
                val tapY = btnAfterClose?.centerY ?: (specs.screenHeight * 0.962f).toInt()
                hud.updateStatus("Tap: Upload Short (#$retry)", BotMood.WORKING, taskRunning = true)
                log("👆 Physical Tap on Upload Short Center ($tapX, $tapY)")
                performPhysicalTap(tapX, tapY)
                currentRunSteps.add("Tapped Upload Short (#$retry) at ($tapX, $tapY)")
            }

            delay(4200L)

            val afterElements = scanScreenInteractiveElements(specs)
            val stillOnDetails = afterElements.any {
                it.label.contains("Add details", true) ||
                    it.label.contains("Upload Short", true) ||
                    it.label.contains("Select audience", true)
            }
            if (!stillOnDetails) {
                break
            }
        }

        hud.updateStatus("🎉 UPLOAD SUCCESS!", BotMood.HAPPY, taskRunning = false)
        log("🎉 YouTube Short Uploaded Successfully: $viralTitle")
    }

    private suspend fun pasteTitleIntoYouTubeCaptionBoxCleanly(
        viralTitle: String,
        specs: DeviceBodySpecs
    ): Boolean {
        val titlePrefix = viralTitle.take(10).trim()

        if (typeAndPasteTextReliably(viralTitle, null)) {
            delay(600L)
            val els = scanScreenInteractiveElements(specs)
            val placeholderGone = els.none { it.label.equals("Caption your Short", true) }
            if (placeholderGone) {
                log("✅ Layer 1 (Accessibility SetText) pasted title!")
                return true
            }
        }

        if (isSoftKeyboardOpen()) {
            val gboardChipNode = findInputMethodClipboardNode(titlePrefix)
            if (gboardChipNode != null) {
                val r = Rect()
                gboardChipNode.getBoundsInScreen(r)
                log("👆 Tapping Gboard Clipboard Node at (${r.centerX()}, ${r.centerY()})")
                performPhysicalTap(r.centerX(), r.centerY())
                delay(1000L)
                return true
            } else {
                val pillX = (specs.screenWidth * 0.50f).toInt()
                val pillY = (specs.screenHeight * 0.606f).toInt()
                log("👆 Tapping Gboard Center Clipboard Pill at ($pillX, $pillY)")
                performPhysicalTap(pillX, pillY)
                delay(1000L)
                return true
            }
        }

        return false
    }

    private fun findInputMethodClipboardNode(titlePrefix: String): AccessibilityNodeInfo? {
        return try {
            for (win in windows) {
                if (win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    val root = win.root ?: continue
                    var found: AccessibilityNodeInfo? = null
                    fun search(n: AccessibilityNodeInfo?) {
                        if (n == null || found != null) return
                        val t = n.text?.toString() ?: ""
                        val d = n.contentDescription?.toString() ?: ""
                        if (t.contains(titlePrefix, true) || d.contains(titlePrefix, true) || d.contains("Paste", true)) {
                            found = n
                            return
                        }
                        for (i in 0 until n.childCount) search(n.getChild(i))
                    }
                    search(root)
                    if (found != null) return found
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun closeKeyboardCompletely() {
        var attempts = 0
        while (isSoftKeyboardOpen() && attempts < 2) {
            attempts++
            log("⌨️ Closing keyboard with BACK (#$attempts)")
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(1100L)
        }
    }

    private suspend fun dismissDiscardDialogIfPresent(specs: DeviceBodySpecs) {
        val els = scanScreenInteractiveElements(specs)
        val hasDiscardPopup = els.any {
            it.label.contains("Discard changes", true) || it.label.contains("not be saved", true)
        }
        if (hasDiscardPopup) {
            val cancelBtn = els.find { it.label.equals("Cancel", true) || it.label.equals("रद्द करें", true) }
            if (cancelBtn != null) {
                log("⚠️ Dismissing 'Discard changes?' popup by tapping Cancel!")
                tapElementSmartly(cancelBtn, forceAccessibilityClick = true)
            } else {
                performPhysicalTap((specs.screenWidth * 0.55f).toInt(), (specs.screenHeight * 0.57f).toInt())
            }
            delay(1200L)
        }
    }

    private fun isSoftKeyboardOpen(): Boolean {
        return try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun dispatchInternalWebViewTouch(webView: WebView, cssX: Float, cssY: Float) {
        withContext(Dispatchers.Main) {
            try {
                @Suppress("DEPRECATION")
                val scale = if (webView.scale > 0.1f) webView.scale else resources.displayMetrics.density
                val maxW = (if (webView.width > 100) webView.width else (300 * scale).toInt()) - 5
                val maxH = (if (webView.height > 100) webView.height else (420 * scale).toInt()) - 5
                val viewX = (cssX * scale).coerceIn(5f, maxW.coerceAtLeast(10).toFloat())
                val viewY = (cssY * scale).coerceIn(5f, maxH.coerceAtLeast(10).toFloat())
                val downTime = SystemClock.uptimeMillis()
                val downEv = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, viewX, viewY, 0)
                val upEv = MotionEvent.obtain(downTime, downTime + 75L, MotionEvent.ACTION_UP, viewX, viewY, 0)
                webView.dispatchTouchEvent(downEv)
                delay(75L)
                webView.dispatchTouchEvent(upEv)
                downEv.recycle()
                upEv.recycle()
            } catch (_: Exception) {}
        }
    }

    private suspend fun closeFlowVideoPlayerCardIfOpen(webView: WebView) {
        val jsClose = """
            (function() {
                const bodyTxt = document.body ? (document.body.innerText || '') : '';
                const hasVideoTag = document.querySelectorAll('video').length > 0;
                if (bodyTxt.includes('0:00 /') || hasVideoTag) {
                    const btns = Array.from(document.querySelectorAll('button, [role="button"], a'));
                    const backBtn = btns.find(b => {
                        const r = b.getBoundingClientRect();
                        const t = (b.innerText || b.getAttribute('aria-label') || '').toLowerCase();
                        return r.top >= 0 && r.top < 95 && r.left >= 0 && r.left < 95 && r.width > 10 &&
                            (t.includes('back') || t.includes('close') || b.querySelector('svg') || t.includes('arrow'));
                    });
                    if (backBtn) {
                        backBtn.click();
                        return 'CLICKED_BACK_BTN';
                    }
                    window.history.back();
                    return 'HISTORY_BACK';
                }
                return 'NOT_IN_PLAYER';
            })();
        """.trimIndent()

        var res = ""
        suspendCancellableCoroutine<Unit> { cont ->
            withContextOrMain(webView, jsClose) { r ->
                res = r
                cont.resume(Unit)
            }
        }
        if (res == "CLICKED_BACK_BTN" || res == "HISTORY_BACK") {
            log("🔙 Auto-closed big Flow Video Card ($res) to return to prompt composer!")
            delay(1800L)
        }
    }

    fun startGoogleFlowToYouTubePipeline(topic: String, maxVideosToCreate: Int = 1) {
        currentTaskJob?.cancel()
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        geminiBrain.updateApiKey(prefs.getString("gemini_api_key", "") ?: "")

        currentTaskJob = serviceScope.launch {
            try {
                isAgentRunning = true
                acquireWakeLock()
                val targetUrl = prefs.getString("saved_flow_url", "https://flow.google.com")
                    ?: "https://flow.google.com"
                val isStoryMode = prefs.getBoolean("flow_story_mode", false)

                for (vidIndex in 1..maxVideosToCreate.coerceIn(1, 3)) {
                    if (!isAgentRunning) break
                    hud.updateStatus("Flow #$vidIndex: Creating Prompt...", BotMood.THINKING, taskRunning = true)

                    val effectiveTopic = if (isStoryMode && maxVideosToCreate > 1) {
                        "$topic (Story Part $vidIndex of $maxVideosToCreate, continuous cinematic scene)"
                    } else {
                        topic
                    }
                    val (safePrompt, viralTitle) = geminiBrain.generateFlowSafePromptAndTitle(effectiveTopic)
                    log("🎬 Flow Prompt: $safePrompt")

                    val webView = hud.getOrCreateBackgroundWebView()
                    if (webView == null) {
                        log("⚠️ WebView not ready.")
                        break
                    }

                    closeFlowVideoPlayerCardIfOpen(webView)
                    val initialVideoUrl = extractExistingVideoUrlOnly(webView)

                    withContext(Dispatchers.Main) {
                        val curUrl = webView.url ?: ""
                        if (!curUrl.contains("flow.google") && !curUrl.contains("labs.google")) {
                            webView.loadUrl(targetUrl)
                        }
                    }
                    delay(3800L)

                    for (prepTick in 1..18) {
                        if (!isAgentRunning) break
                        val stepResult = runFlowComposerStateMachineStep(webView, safePrompt)
                        log("🌐 Flow Background Step #$prepTick -> $stepResult")

                        when {
                            stepResult == "BACK_FROM_PLAYER" -> {
                                dispatchInternalWebViewTouch(webView, 32f, 42f)
                                delay(2200L)
                            }
                            stepResult.startsWith("CLICKED_NEW_PROJECT:") -> {
                                hud.updateStatus("Flow: Opening + New Project...", BotMood.WORKING, taskRunning = true)
                                val coords = stepResult.substringAfter(":").split(",")
                                if (coords.size == 2) {
                                    dispatchInternalWebViewTouch(
                                        webView,
                                        coords[0].toFloatOrNull() ?: 170f,
                                        coords[1].toFloatOrNull() ?: 480f
                                    )
                                }
                                delay(3500L)
                            }
                            stepResult.startsWith("SWITCHING_TO_VIDEO_MODE:") -> {
                                hud.updateStatus("Flow: Switching to 9:16 Video Mode...", BotMood.WORKING, taskRunning = true)
                                val coords = stepResult.substringAfter(":").split(",")
                                if (coords.size == 2) {
                                    dispatchInternalWebViewTouch(
                                        webView,
                                        coords[0].toFloatOrNull() ?: 200f,
                                        coords[1].toFloatOrNull() ?: 520f
                                    )
                                }
                                delay(2200L)
                            }
                            stepResult.startsWith("SUBMITTED:") -> {
                                hud.updateStatus("Flow: Submitting 8s Prompt...", BotMood.WORKING, taskRunning = true)
                                val coords = stepResult.substringAfter(":").split(",")
                                if (coords.size == 2) {
                                    delay(450L)
                                    dispatchInternalWebViewTouch(
                                        webView,
                                        coords[0].toFloatOrNull() ?: 295f,
                                        coords[1].toFloatOrNull() ?: 540f
                                    )
                                }
                                log("✅ Flow Prompt Injected & Submit Arrow Clicked!")
                                break
                            }
                            else -> {
                                hud.updateStatus("Flow: Preparing Editor ($prepTick)...", BotMood.THINKING, taskRunning = true)
                                delay(2500L)
                            }
                        }
                    }

                    var downloadedVideoFile: File? = null
                    var sawGeneratingProgress = false
                    var ticksAfterProgressFinished = 0

                    for (waitTick in 1..60) {
                        if (!isAgentRunning) break
                        delay(3800L)

                        val shouldClickCardNow = (sawGeneratingProgress && ticksAfterProgressFinished >= 1) || waitTick >= 12
                        val pollRes = pollOrOpenGeneratedFlowVideo(
                            webView = webView,
                            initialVideoUrl = initialVideoUrl,
                            allowAutoClickCard = shouldClickCardNow
                        )

                        when {
                            pollRes.startsWith("VIDEO_URL:") -> {
                                val videoUrl = pollRes.removePrefix("VIDEO_URL:").trim()
                                hud.updateStatus("📥 Downloading 8s MP4...", BotMood.WORKING, taskRunning = true)
                                log("📥 Found Generated MP4 Stream: ${videoUrl.take(70)}...")
                                downloadedVideoFile = downloadVideoFromUrlToStorage(videoUrl, "flow_${System.currentTimeMillis()}.mp4")
                                if (downloadedVideoFile != null) {
                                    closeFlowVideoPlayerCardIfOpen(webView)
                                    break
                                }
                            }
                            pollRes.startsWith("PROGRESS:") -> {
                                val pct = pollRes.removePrefix("PROGRESS:")
                                sawGeneratingProgress = true
                                ticksAfterProgressFinished = 0
                                hud.updateStatus("⏳ Flow Generating ($pct)...", BotMood.THINKING, taskRunning = true)
                                log("⏳ Flow Video Progress: $pct")
                            }
                            pollRes.startsWith("CLICKED_CARD:") -> {
                                ticksAfterProgressFinished++
                                val coords = pollRes.removePrefix("CLICKED_CARD:").split(",")
                                hud.updateStatus("🎬 Opening Generated Video Card...", BotMood.WORKING, taskRunning = true)
                                log("👆 Auto-Opening Finished Video Card at CSS ($pollRes)!")
                                if (coords.size == 2) {
                                    val cx = coords[0].toFloatOrNull() ?: 88f
                                    val cy = coords[1].toFloatOrNull() ?: 215f
                                    dispatchInternalWebViewTouch(webView, cx, cy)
                                }
                            }
                            else -> {
                                if (sawGeneratingProgress) {
                                    ticksAfterProgressFinished++
                                }
                                hud.updateStatus("⏳ Generating 8s Video (${waitTick * 4}s)...", BotMood.THINKING, taskRunning = true)
                            }
                        }
                    }

                    if (downloadedVideoFile != null && downloadedVideoFile.exists()) {
                        log("✅ Downloaded 8s Video: ${downloadedVideoFile.absolutePath}")
                        hideWebPreviewCardIfOpen()
                        dispatchVideoToLinkedPlatforms(Uri.fromFile(downloadedVideoFile), viralTitle)
                        memoryVault.markVideoAsPostedInLedger(downloadedVideoFile.absolutePath, "YouTube")
                    } else {
                        log("⚠️ Could not auto-catch MP4 stream; check Google Flow login or Video mode.")
                        hud.updateStatus("Check Flow Login/Video Mode", BotMood.ERROR, taskRunning = false)
                    }
                }
            } catch (_: CancellationException) {
                hud.updateStatus("Stopped", BotMood.IDLE, taskRunning = false)
            } finally {
                isAgentRunning = false
                releaseWakeLock()
            }
        }
    }

    private suspend fun runFlowComposerStateMachineStep(webView: WebView, safePrompt: String): String =
        suspendCancellableCoroutine { cont ->
            val js = """
                (function() {
                    function isVisible(el) {
                        if (!el) return false;
                        const r = el.getBoundingClientRect();
                        return r.width > 10 && r.height > 10 && r.top >= 0;
                    }

                    const allInputs = Array.from(document.querySelectorAll('textarea, [contenteditable="true"], input[type="text"]')).filter(isVisible);
                    const promptBox = allInputs.find(el => {
                        const ph = (el.getAttribute('placeholder') || el.getAttribute('aria-label') || el.innerText || '').toLowerCase();
                        const r = el.getBoundingClientRect();
                        return ph.includes('create') || ph.includes('prompt') || ph.includes('describe') || (r.top > window.innerHeight * 0.45 && !ph.includes('search'));
                    });

                    if (!promptBox && (document.body.innerText.includes('0:00 /') || document.querySelectorAll('video').length > 0)) {
                        const backBtns = Array.from(document.querySelectorAll('button, [role="button"], a')).filter(isVisible);
                        const backBtn = backBtns.find(b => {
                            const r = b.getBoundingClientRect();
                            return r.top < 95 && r.left < 95;
                        });
                        if (backBtn) backBtn.click();
                        window.history.back();
                        return 'BACK_FROM_PLAYER';
                    }

                    const allBtns = Array.from(document.querySelectorAll('button, [role="button"], [role="tab"], div, span')).filter(isVisible);

                    const creditLabel = allBtns.find(el => (el.innerText || '').toLowerCase().includes('generating will use'));
                    if (creditLabel) {
                        const videoTab = allBtns.find(el => (el.innerText || '').trim() === 'Video' && el.getBoundingClientRect().top > 150);
                        if (videoTab && !document.body.innerText.includes('360p') && !document.body.innerText.includes('720p')) {
                            const r = videoTab.getBoundingClientRect();
                            videoTab.click();
                            return 'SWITCHING_TO_VIDEO_MODE:' + Math.round(r.left + r.width / 2) + ',' + Math.round(r.top + r.height / 2);
                        }
                        const btn916 = allBtns.find(el => (el.innerText || '').trim().includes('9:16'));
                        if (btn916) btn916.click();
                        const btnX1 = allBtns.find(el => (el.innerText || '').trim() === 'x1');
                        if (btnX1) btnX1.click();
                    }

                    if (promptBox) {
                        const nanoPill = allBtns.find(el => {
                            const t = (el.innerText || '').toLowerCase();
                            const r = el.getBoundingClientRect();
                            return (t.includes('nano banana') || (t.includes('image') && t.includes('x'))) && r.top > window.innerHeight * 0.65 && el.children.length <= 4;
                        });

                        if (nanoPill && !creditLabel) {
                            const nr = nanoPill.getBoundingClientRect();
                            nanoPill.click();
                            return 'SWITCHING_TO_VIDEO_MODE:' + Math.round(nr.left + nr.width / 2) + ',' + Math.round(nr.top + nr.height / 2);
                        }

                        promptBox.focus();
                        promptBox.click();

                        const promptText = ${JSONObject.quote(safePrompt)};
                        if (promptBox.tagName === 'TEXTAREA' || promptBox.tagName === 'INPUT') {
                            const proto = promptBox.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                            const nativeSetter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                            if (nativeSetter) {
                                nativeSetter.call(promptBox, promptText);
                            } else {
                                promptBox.value = promptText;
                            }
                        } else {
                            promptBox.innerText = promptText;
                        }

                        document.execCommand('selectAll', false, null);
                        document.execCommand('insertText', false, promptText);
                        promptBox.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, data: promptText }));
                        promptBox.dispatchEvent(new Event('change', { bubbles: true }));

                        const boxRect = promptBox.getBoundingClientRect();
                        const submitBtn = allBtns.find(b => {
                            const txt = (b.innerText || b.getAttribute('aria-label') || b.innerHTML || '').toLowerCase();
                            const r = b.getBoundingClientRect();
                            const isNearBottomRight = r.top >= boxRect.top - 20 && r.left > window.innerWidth * 0.68 && r.width < 90;
                            return (txt.includes('arrow_forward') || txt.includes('send') || txt.includes('submit') || isNearBottomRight) && !txt.includes('banana') && !txt.includes('video') && !txt.includes('new project');
                        });

                        if (submitBtn) {
                            const sr = submitBtn.getBoundingClientRect();
                            setTimeout(() => { submitBtn.click(); }, 350);
                            return 'SUBMITTED:' + Math.round(sr.left + sr.width / 2) + ',' + Math.round(sr.top + sr.height / 2);
                        }
                        return 'SUBMITTED:' + Math.round(window.innerWidth * 0.88) + ',' + Math.round(boxRect.bottom + 28);
                    }

                    const newProjBtn = allBtns.find(el => {
                        const t = (el.innerText || el.getAttribute('aria-label') || '').trim().toLowerCase();
                        return t === 'new project' || t === '+ new project' || (t.includes('new project') && t.length < 25);
                    });

                    if (newProjBtn) {
                        const nr = newProjBtn.getBoundingClientRect();
                        newProjBtn.click();
                        return 'CLICKED_NEW_PROJECT:' + Math.round(nr.left + nr.width / 2) + ',' + Math.round(nr.top + nr.height / 2);
                    }

                    return 'WAITING_FOR_FLOW_UI';
                })();
            """.trimIndent()

            withContextOrMain(webView, js) { res ->
                cont.resume(res)
            }
        }

    private suspend fun extractExistingVideoUrlOnly(webView: WebView): String =
        suspendCancellableCoroutine { cont ->
            val js = """
                (function() {
                    const vids = Array.from(document.querySelectorAll('video'));
                    for (const v of vids) {
                        if (v.src && v.src.startsWith('http')) return v.src;
                        const s = v.querySelector('source');
                        if (s && s.src && s.src.startsWith('http')) return s.src;
                    }
                    return '';
                })();
            """.trimIndent()
            withContextOrMain(webView, js) { res ->
                cont.resume(res)
            }
        }

    private suspend fun pollOrOpenGeneratedFlowVideo(
        webView: WebView,
        initialVideoUrl: String,
        allowAutoClickCard: Boolean
    ): String = suspendCancellableCoroutine { cont ->
        val js = """
            (function() {
                function fireFullHumanClickAt(cx, cy, extraEl) {
                    const hitEl = document.elementFromPoint(cx, cy) || extraEl;
                    const targets = [];
                    if (extraEl) targets.push(extraEl);
                    let cur = hitEl;
                    for (let d = 0; d < 7 && cur && cur !== document.body; d++) {
                        if (!targets.includes(cur)) targets.push(cur);
                        cur = cur.parentElement;
                    }
                    const evTypes = ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'];
                    for (const node of targets) {
                        for (const type of evTypes) {
                            try {
                                node.dispatchEvent(new PointerEvent(type, {
                                    view: window,
                                    bubbles: true,
                                    cancelable: true,
                                    composed: true,
                                    clientX: cx,
                                    clientY: cy,
                                    pointerId: 1,
                                    pointerType: 'touch',
                                    isPrimary: true,
                                    button: 0,
                                    buttons: 1
                                }));
                            } catch (e) {}
                            try {
                                node.dispatchEvent(new MouseEvent(type, {
                                    view: window,
                                    bubbles: true,
                                    cancelable: true,
                                    composed: true,
                                    clientX: cx,
                                    clientY: cy,
                                    button: 0,
                                    buttons: 1
                                }));
                            } catch (e) {}
                        }
                        try {
                            if (typeof node.click === 'function') node.click();
                        } catch (e) {}
                    }
                }

                const vids = Array.from(document.querySelectorAll('video'));
                for (const v of vids) {
                    const src = v.src || (v.querySelector('source') ? v.querySelector('source').src : '');
                    if (src && src.startsWith('http') && src !== ${JSONObject.quote(initialVideoUrl)}) {
                        return 'VIDEO_URL:' + src;
                    }
                }
                if ($allowAutoClickCard && vids.length > 0) {
                    const v0 = vids[0];
                    const s0 = v0.src || (v0.querySelector('source') ? v0.querySelector('source').src : '');
                    if (s0 && s0.startsWith('http')) return 'VIDEO_URL:' + s0;
                }

                const allEls = Array.from(document.querySelectorAll('div, span, p'));
                for (const el of allEls) {
                    if (el.children.length === 0) {
                        const txt = (el.innerText || '').trim();
                        if (txt.endsWith('%') && txt.length >= 2 && txt.length <= 4) {
                            const num = parseInt(txt.slice(0, -1), 10);
                            if (!isNaN(num) && num >= 0 && num <= 100) {
                                return 'PROGRESS:' + txt;
                            }
                        }
                    }
                }

                if ($allowAutoClickCard) {
                    const gridImgs = Array.from(document.querySelectorAll('img, canvas')).filter(el => {
                        const r = el.getBoundingClientRect();
                        return r.top >= 90 && r.top <= 340 && r.left >= 5 && r.left <= 180 && r.width >= 55 && r.height >= 75;
                    });

                    if (gridImgs.length > 0) {
                        const imgEl = gridImgs[0];
                        const r = imgEl.getBoundingClientRect();
                        const cx = Math.round(r.left + r.width * 0.5);
                        const cy = Math.round(r.top + r.height * 0.5);
                        fireFullHumanClickAt(cx, cy, imgEl);
                        fireFullHumanClickAt(Math.round(r.left + 22), Math.round(r.top + 22), imgEl.parentElement);
                        fireFullHumanClickAt(cx, Math.round(r.bottom - 16), imgEl.parentElement);
                        return 'CLICKED_CARD:' + cx + ',' + cy;
                    }

                    const fallbackX = Math.round(window.innerWidth * 0.27);
                    const fallbackY = 215;
                    fireFullHumanClickAt(fallbackX, fallbackY, null);
                    return 'CLICKED_CARD:' + fallbackX + ',' + fallbackY;
                }

                return 'WAITING';
            })();
        """.trimIndent()
        withContextOrMain(webView, js) { res ->
            cont.resume(res)
        }
    }

    private fun withContextOrMain(webView: WebView, js: String, onResult: (String) -> Unit) {
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

    private suspend fun runBackgroundWebAgentLoop(goal: String, plannedSteps: List<String>) {
        val webView = hud.getOrCreateBackgroundWebView() ?: return
        val specs = getCalibratedDeviceBodySpecs()
        val history = mutableListOf<String>()

        // Auto-load target website in OpenDots Background WebView if goal mentions a known platform or URL
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
            hud.updateStatus("🟣 Dot Web Step $step...", BotMood.WORKING, taskRunning = true)

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

            log("🟣 Dot Web Step $step [${decision.modelUsed}]: ${decision.action} -> ${decision.reason}")
            currentRunSteps.add("Web Step $step: ${decision.action} (${decision.reason})")
            if (decision.action == "DONE") {
                hud.updateStatus("🎉 Background Dot Task Done!", BotMood.HAPPY, taskRunning = false)
                break
            }

            executeWebStepInDom(webView, decision)
            history.add("${decision.action}: ${decision.reason}")
            delay(3200L)
        }
    }

    private suspend fun injectVisualNumberStickersInWebView(webView: WebView): String =
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

    private suspend fun executeWebStepInDom(webView: WebView, decision: AgentStepDecision) {
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

    private suspend fun downloadVideoFromUrlToStorage(videoUrl: String, fileName: String): File? =
        withContext(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
                val customPath = prefs.getString("custom_download_folder", "")?.trim() ?: ""
                val outDir = if (customPath.isNotEmpty()) {
                    resolveSmartFolderPath(customPath).apply { if (!exists()) mkdirs() }
                } else {
                    File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "AutoVideos"
                    ).apply { if (!exists()) mkdirs() }
                }
                val outFile = File(outDir, fileName)
                URL(videoUrl).openStream().use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                outFile
            } catch (_: Exception) {
                null
            }
        }

    private fun startDailySchedulerLoop() {
        schedulerJob?.cancel()
        schedulerJob = serviceScope.launch {
            var lastTriggeredMinute = ""
            var lastNightReportDay = ""
            while (isActive) {
                delay(20000L)
                val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
                if (isAgentRunning) continue

                val nowMs = System.currentTimeMillis()
                val snoozeUntil = prefs.getLong("snooze_until_ms", 0L)
                if (nowMs < snoozeUntil) continue

                val pendingIdleGoal = prefs.getString("pending_idle_goal", "") ?: ""
                if (pendingIdleGoal.isNotBlank() && isPhoneCurrentlyIdleOrFree()) {
                    prefs.edit().remove("pending_idle_goal").apply()
                    log("🌙 Phone is now Free/Idle -> Auto-starting queued task: $pendingIdleGoal")
                    startUniversalTask(pendingIdleGoal, emptyList(), "LIVE_PHONE")
                    continue
                }

                val nowHm = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
                val todayStr = java.text.SimpleDateFormat("dd MMM", Locale.getDefault()).format(java.util.Date())

                if (nowHm == "22:00" && lastNightReportDay != todayStr) {
                    lastNightReportDay = todayStr
                    val dailyStatus = prefs.getString("last_daily_action_log", "आज के सभी सिस्टम रेडी हैं!")
                    hud.triggerGreetingPeekIfAllowed("🌙 सर, 10 बज गए! आज की रिपोर्ट: $dailyStatus")
                }

                if (!prefs.getBoolean("scheduler_enabled", false)) continue
                val targetHm = prefs.getString("schedule_time_hm", "19:00") ?: "19:00"

                if (nowHm == targetHm && lastTriggeredMinute != nowHm) {
                    lastTriggeredMinute = nowHm
                    val mode = prefs.getString("scheduled_pipeline_type", "FLOW_YT") ?: "FLOW_YT"
                    val topic = prefs.getString("flow_topic", "Futuristic Sci-Fi World") ?: "Futuristic Sci-Fi World"
                    val credits = prefs.getInt("flow_daily_videos", 1)
                    val folder = prefs.getString(
                        "yt_folder_path",
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                    ) ?: ""
                    val oldest = prefs.getBoolean("yt_pick_oldest", true)

                    val runWhenIdleOnly = prefs.getBoolean("run_only_when_idle", false)
                    if (runWhenIdleOnly && !isPhoneCurrentlyIdleOrFree()) {
                        log("⏰ Scheduled time $nowHm reached while phone is in use -> Showing Smart Skip/Idle banner!")
                        hud.showScheduledTaskConfirmBanner(
                            taskTitle = if (mode == "FLOW_YT") "Flow AI Video" else "YouTube Upload",
                            onRunNow = {
                                if (mode == "FLOW_YT") startGoogleFlowToYouTubePipeline(topic, credits)
                                else startYouTubeFolderAutoUpload(folder, oldest)
                            },
                            onSkip10Min = {
                                prefs.edit().putLong("snooze_until_ms", System.currentTimeMillis() + 600_000L).apply()
                                lastTriggeredMinute = ""
                                log("⏳ User snoozed scheduled task for 10 minutes.")
                            },
                            onRunWhenIdle = {
                                prefs.edit().putString(
                                    "pending_idle_goal",
                                    if (mode == "FLOW_YT") "Google Flow video upload" else "YouTube folder upload"
                                ).apply()
                                log("🌙 Queued scheduled task to run when phone becomes idle.")
                            },
                            onRunBackground = {
                                prefs.edit().putBoolean("bg_youtube_upload", true).putBoolean("yt_upload_in_background", true).apply()
                                if (mode == "FLOW_YT") startGoogleFlowToYouTubePipeline(topic, credits)
                                else startYouTubeFolderAutoUpload(folder, oldest)
                            }
                        )
                    } else {
                        log("⏰ 24/7 Scheduler Triggered at $nowHm -> $mode")
                        if (mode == "FLOW_YT") {
                            startGoogleFlowToYouTubePipeline(topic, credits)
                        } else {
                            startYouTubeFolderAutoUpload(folder, oldest)
                        }
                    }
                }
            }
        }
    }

    private fun scanScreenInteractiveElements(specs: DeviceBodySpecs): List<TaggedUiElement> {
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
                viewId.isNotEmpty() -> viewId
                else -> ""
            }

            if (isValidSize && (node.isClickable || node.isEditable || node.isCheckable || node.isFocused || label.isNotEmpty())) {
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

    private suspend fun tapElementSmartly(element: TaggedUiElement, forceAccessibilityClick: Boolean = false) {
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

    private suspend fun performPhysicalTap(x: Int, y: Int) {
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

    private suspend fun performPhysicalSwipe(sx: Int, sy: Int, ex: Int, ey: Int) {
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

    private suspend fun typeAndPasteTextReliably(
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
            elements.any { it.label.contains("Send separately", true) || it.label.contains("Add a message", true) }

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
            } else if (isSoftKeyboardOpen()) {
                performPhysicalTap((specs.screenWidth * 0.91f).toInt(), (specs.screenHeight * 0.91f).toInt())
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

    private suspend fun takeHdDisplayScreenshotWithTimeout(timeoutMs: Long = 2500L): Bitmap? {
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

    private fun launchAppByNameOrPackage(query: String) {
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
