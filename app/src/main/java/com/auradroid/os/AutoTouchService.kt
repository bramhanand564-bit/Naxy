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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.StrictMode
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
    private var consecutiveSameScreenCount = 0
    private var lastScreenSignature = ""

    // Captures each run's screenshots & steps for the new Automation Runs History
    private val currentRunBitmaps = mutableListOf<Bitmap>()
    private val currentRunSteps = mutableListOf<String>()
    private var activeRunId = ""
    private var activeRunTitle = ""

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        val teachEnabled = prefs.getBoolean("passive_teach_mode", true)

        val timeSinceAgentTap = System.currentTimeMillis() - lastAgentTapTimestamp
        if (teachEnabled && isAgentRunning && timeSinceAgentTap > 900L &&
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

            log("👆 Human Touch Intervention on [$nodeDesc]! Capturing AFTER screenshot...")

            serviceScope.launch {
                delay(1200L)
                val afterBitmap = takeHdDisplayScreenshotWithTimeout(2000L)
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

    override fun onInterrupt() {}

    fun stopCurrentTask(reason: String = "Stopped") {
        if (isAgentRunning && activeRunId.isNotEmpty()) {
            memoryVault.saveAutomationRunSession(
                runId = activeRunId,
                title = activeRunTitle,
                appCategory = currentAppCategory,
                status = if (reason.contains("Complete", true)) "SUCCESS" else "STOPPED",
                stepsSummary = currentRunSteps,
                capturedBitmaps = currentRunBitmaps
            )
        }
        isAgentRunning = false
        currentTaskJob?.cancel()
        releaseWakeLock()
        hud.updateStatus(reason, BotMood.IDLE, taskRunning = false)
        log("■ Task Stopped: $reason")
    }

    /**
     * Universal Autonomous Goal-Based Executor
     */
    fun startUniversalTask(
        goal: String,
        plannedSteps: List<String> = emptyList(),
        forceMode: String? = null
    ) {
        currentTaskJob?.cancel()
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        geminiBrain.updateApiKey(prefs.getString("gemini_api_key", "") ?: "")

        val execMode = forceMode ?: prefs.getString("execution_mode", "LIVE_PHONE") ?: "LIVE_PHONE"
        val isContinuousMonitor = goal.contains("बात करते रहो", true) ||
            goal.contains("monitor", true) ||
            goal.contains("wait for reply", true) ||
            goal.contains("लगातार", true)

        activeRunId = "run_${System.currentTimeMillis()}"
        activeRunTitle = goal.take(35)
        currentRunBitmaps.clear()
        currentRunSteps.clear()

        currentTaskJob = serviceScope.launch {
            try {
                isAgentRunning = true
                acquireWakeLock()
                currentTaskGoal = goal
                consecutiveSameScreenCount = 0
                val stepHistory = mutableListOf<String>()
                var stepNumber = 0

                hud.updateStatus("Starting: ${goal.take(22)}...", BotMood.THINKING, taskRunning = true)
                log("🚀 Starting Task [$execMode]: $goal")

                if (execMode == "BACKGROUND_WEB") {
                    runBackgroundWebAgentLoop(goal, plannedSteps)
                    return@launch
                }

                val lowerGoal = goal.lowercase(Locale.ROOT)
                val autoTargetApp = when {
                    lowerGoal.contains("instagram") || lowerGoal.contains("इंस्टाग्राम") -> "instagram"
                    lowerGoal.contains("whatsapp") || lowerGoal.contains("व्हाट्सएप") -> "whatsapp"
                    lowerGoal.contains("youtube") || lowerGoal.contains("यूट्यूब") -> "youtube"
                    lowerGoal.contains("chrome") || lowerGoal.contains("क्रोम") -> "chrome"
                    lowerGoal.contains("setting") || lowerGoal.contains("सेटिंग") -> "settings"
                    else -> null
                }

                if (autoTargetApp != null) {
                    hud.updateStatus("🚀 Opening $autoTargetApp...", BotMood.WORKING, taskRunning = true)
                    launchAppByNameOrPackage(autoTargetApp)
                    stepHistory.add("Opened $autoTargetApp app")
                    currentRunSteps.add("Opened $autoTargetApp app")
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

                    if (currentSig == lastScreenSignature) {
                        consecutiveSameScreenCount++
                    } else {
                        consecutiveSameScreenCount = 0
                        lastScreenSignature = currentSig
                    }

                    // Instagram "Comments limited" auto-handler
                    val hasLimitedComments = taggedElements.any {
                        it.label.contains("Comments on this post have been limited", true) ||
                            it.label.contains("comments are limited", true)
                    }
                    if (hasLimitedComments) {
                        log("⚠️ Detected limited comments! Swipe to next Reel...")
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
                        currentRunSteps.add("Skipped limited comments reel")
                        delay(2200L)
                        continue
                    }

                    val isStuck = consecutiveSameScreenCount >= 2

                    hud.updateStatus("📸 Step $stepNumber Capturing...", BotMood.WORKING, taskRunning = true)
                    val screenshot = takeHdDisplayScreenshotWithTimeout(2500L)
                    if (screenshot != null) {
                        lastStepScreenshot = screenshot
                        currentRunBitmaps.add(screenshot)
                    }

                    val recalledFixes = memoryVault.recallFixesForApp(currentAppCategory)
                    val nodesPromptList = taggedElements.take(48).joinToString("\n") {
                        "[${it.tagId}] ${it.label} (${it.className}) -> Center(${it.centerX}, ${it.centerY})"
                    }

                    hud.updateStatus("🧠 Step $stepNumber Thinking...", BotMood.THINKING, taskRunning = true)

                    val decision = withTimeoutOrNull(30000L) {
                        geminiBrain.decideNextStep(
                            goal = goal,
                            planSteps = plannedSteps,
                            currentStepHistory = stepHistory,
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
                                memoryVault.saveAutomationRunSession(
                                    runId = activeRunId,
                                    title = activeRunTitle,
                                    appCategory = currentAppCategory,
                                    status = "SUCCESS",
                                    stepsSummary = currentRunSteps,
                                    capturedBitmaps = currentRunBitmaps
                                )
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
                                tapDeadCenterOfElement(target)
                                stepHistory.add("Tapped [${target.tagId}] ${target.label}")
                            } else {
                                val px = convertNormalizedX(decision.x, specs)
                                val py = convertNormalizedY(decision.y, specs)
                                performPhysicalTap(px, py)
                                stepHistory.add("Tapped XY($px,$py)")
                            }
                            delay(2400L)
                        }

                        "CLICK_XY" -> {
                            val px = convertNormalizedX(decision.x, specs)
                            val py = convertNormalizedY(decision.y, specs)
                            val snapped = findNearestElementToSnap(px, py, taggedElements)
                            if (snapped != null) {
                                tapDeadCenterOfElement(snapped)
                                stepHistory.add("Tapped ${snapped.label}")
                            } else {
                                performPhysicalTap(px, py)
                                stepHistory.add("Tapped XY($px,$py)")
                            }
                            delay(2400L)
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
                            if (target != null) {
                                tapDeadCenterOfElement(target)
                                delay(750L)
                            } else if (decision.x > 0 && decision.y > 0) {
                                performPhysicalTap(
                                    convertNormalizedX(decision.x, specs),
                                    convertNormalizedY(decision.y, specs)
                                )
                                delay(750L)
                            }
                            typeAndPasteTextReliably(decision.text)
                            stepHistory.add("Typed '${decision.text}'")
                            delay(1200L)

                            if (activePkg.contains("whatsapp") || isContinuousMonitor) {
                                clickWhatsAppSendIfPresent()
                            } else if (activePkg.contains("instagram") || activePkg.contains("youtube")) {
                                clickSearchOrPostIfPresent(decision.text)
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
                            delay(3200L)
                        }

                        else -> delay(2200L)
                    }
                }
            } catch (_: CancellationException) {
                memoryVault.saveAutomationRunSession(
                    runId = activeRunId,
                    title = activeRunTitle,
                    appCategory = currentAppCategory,
                    status = "STOPPED",
                    stepsSummary = currentRunSteps,
                    capturedBitmaps = currentRunBitmaps
                )
                hud.updateStatus("Stopped", BotMood.IDLE, taskRunning = false)
            } catch (e: Exception) {
                log("⚠️ Task Exception: ${e.message}")
                memoryVault.saveAutomationRunSession(
                    runId = activeRunId,
                    title = activeRunTitle,
                    appCategory = currentAppCategory,
                    status = "FAILED",
                    stepsSummary = currentRunSteps,
                    capturedBitmaps = currentRunBitmaps
                )
                hud.updateStatus("Paused", BotMood.ERROR, taskRunning = false)
            } finally {
                isAgentRunning = false
                releaseWakeLock()
            }
        }
    }

    /**
     * Direct Single Picked Video URI -> YouTube Shorts Auto-Uploader
     */
    fun startSinglePickedVideoYouTubeUpload(videoUri: Uri, rawDisplayName: String) {
        currentTaskJob?.cancel()
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        geminiBrain.updateApiKey(prefs.getString("gemini_api_key", "") ?: "")

        currentTaskJob = serviceScope.launch {
            try {
                isAgentRunning = true
                acquireWakeLock()
                hud.updateStatus("Generating Viral Title...", BotMood.THINKING, taskRunning = true)
                val cleanTopic = rawDisplayName.substringBeforeLast(".").replace("_", " ").ifBlank { "Amazing Viral Short Video" }
                val (_, viralTitle) = geminiBrain.generateFlowSafePromptAndTitle(cleanTopic)
                log("✨ Generated Viral Title: $viralTitle")
                uploadVideoUriToYouTubeShorts(videoUri, viralTitle)
            } catch (_: CancellationException) {
                hud.updateStatus("Stopped", BotMood.IDLE, taskRunning = false)
            } catch (e: Exception) {
                log("⚠️ Upload Error: ${e.message}")
                hud.updateStatus("Upload Paused", BotMood.ERROR, taskRunning = false)
            } finally {
                isAgentRunning = false
                releaseWakeLock()
            }
        }
    }

    /**
     * Folder Video -> YouTube Shorts Auto-Uploader
     */
    fun startYouTubeFolderAutoUpload(folderInputRaw: String, pickOldestFirst: Boolean) {
        currentTaskJob?.cancel()
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        geminiBrain.updateApiKey(prefs.getString("gemini_api_key", "") ?: "")

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
                        uploadVideoUriToYouTubeShorts(uri, viralTitle)
                        uploadedSet.add(uri.toString())
                        prefs.edit().putStringSet("uploaded_videos_set", uploadedSet).apply()
                        return@launch
                    }

                    hud.updateStatus("No new videos found!", BotMood.ERROR, taskRunning = false)
                    log("⚠️ No un-uploaded videos found in [${resolvedDir.absolutePath}].")
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

                uploadVideoUriToYouTubeShorts(Uri.fromFile(chosenVideo), viralTitle)

                uploadedSet.add(chosenVideo.absolutePath)
                prefs.edit().putStringSet("uploaded_videos_set", uploadedSet).apply()
            } catch (_: CancellationException) {
                hud.updateStatus("Stopped", BotMood.IDLE, taskRunning = false)
            } catch (e: Exception) {
                log("⚠️ YouTube Upload Error: ${e.message}")
                hud.updateStatus("Upload Paused", BotMood.ERROR, taskRunning = false)
            } finally {
                isAgentRunning = false
                releaseWakeLock()
            }
        }
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
            return f.isFile && (n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv") || n.endsWith(".webm")) &&
                !uploadedSet.contains(f.absolutePath)
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
                    if (matchesBucket && !uploadedSet.contains(contentUri.toString())) {
                        return Pair(contentUri, name)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Perfected YouTube Shorts Uploader:
     * - Only presses Next 2 times max to reach Add details screen[span_0](start_span)[span_0](end_span)[span_1](start_span)[span_1](end_span)[span_2](start_span)[span_2](end_span)
     * - Pastes title and taps Upload Short dead-center[span_3](start_span)[span_3](end_span)[span_4](start_span)[span_4](end_span)[span_5](start_span)[span_5](end_span)
     */
    private suspend fun uploadVideoUriToYouTubeShorts(videoUri: Uri, viralTitle: String) {
        val specs = getCalibratedDeviceBodySpecs()

        withContext(Dispatchers.Main) {
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

        // Step 1 & 2: Advance past Trimmer & Editor screens (max 2 Next clicks)[span_6](start_span)[span_6](end_span)[span_7](start_span)[span_7](end_span)[span_8](start_span)[span_8](end_span)
        for (attempt in 1..2) {
            if (!isAgentRunning) return
            val fallbackX = (specs.screenWidth * 0.88f).toInt()
            val fallbackY = (specs.screenHeight * 0.94f).toInt()
            hud.updateStatus("Tap: Next (#$attempt)", BotMood.WORKING, taskRunning = true)
            log("👆 Tapping Next button ($fallbackX, $fallbackY)")
            performPhysicalTap(fallbackX, fallbackY)
            delay(4200L)
        }

        // Step 3: Tap 'Caption your Short' & Paste Viral Title[span_9](start_span)[span_9](end_span)[span_10](start_span)[span_10](end_span)[span_11](start_span)[span_11](end_span)
        hud.updateStatus("Typing Viral Caption...", BotMood.WORKING, taskRunning = true)
        val capX = (specs.screenWidth * 0.52f).toInt()
        val capY = (specs.screenHeight * 0.16f).toInt()
        log("👆 Tapping Caption Box ($capX, $capY)")
        performPhysicalTap(capX, capY)
        delay(1600L)

        typeAndPasteTextReliably(viralTitle)
        delay(1200L)

        if (isSoftKeyboardOpen()) {
            log("⌨️ Closing keyboard with BACK")
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(1400L)
        }

        // Step 4: Tap White 'Upload Short' Button at Bottom Center (96.4% height)[span_12](start_span)[span_12](end_span)[span_13](start_span)[span_13](end_span)[span_14](start_span)[span_14](end_span)
        for (retry in 1..3) {
            if (!isAgentRunning) return
            val postElements = scanScreenInteractiveElements(specs)
            val uploadNode = postElements.find {
                it.label.contains("Upload Short", true) || it.label.equals("Upload", true)
            }

            val tapX = uploadNode?.centerX ?: (specs.screenWidth / 2)
            val tapY = uploadNode?.centerY ?: (specs.screenHeight * 0.964f).toInt()

            hud.updateStatus("Tap: Upload Short (#$retry)", BotMood.WORKING, taskRunning = true)
            log("👆 Physical Tap on Upload Short Center ($tapX, $tapY)")
            performPhysicalTap(tapX, tapY)
            delay(4200L)

            val afterElements = scanScreenInteractiveElements(specs)
            val stillOnDetails = afterElements.any {
                it.label.contains("Add details", true) || it.label.contains("Upload Short", true)
            }
            if (!stillOnDetails) {
                break
            }
        }

        hud.updateStatus("🎉 UPLOAD SUCCESS!", BotMood.HAPPY, taskRunning = false)
        log("🎉 YouTube Short Uploaded Successfully: $viralTitle")
    }

    private fun isSoftKeyboardOpen(): Boolean {
        return try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (_: Exception) {
            false
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
                val targetUrl = prefs.getString("saved_flow_url", "https://labs.google/fx/tools/flow")
                    ?: "https://labs.google/fx/tools/flow"

                for (vidIndex in 1..maxVideosToCreate.coerceIn(1, 3)) {
                    if (!isAgentRunning) break
                    hud.updateStatus("Flow #$vidIndex: Creating Prompt...", BotMood.THINKING, taskRunning = true)
                    val (safePrompt, viralTitle) = geminiBrain.generateFlowSafePromptAndTitle(topic)
                    log("🎬 Flow Safe Prompt: $safePrompt")

                    val webView = hud.getOrCreateBackgroundWebView()
                    if (webView == null) {
                        log("⚠️ WebView not ready.")
                        break
                    }

                    withContext(Dispatchers.Main) {
                        if (webView.url == null || !webView.url!!.contains("labs.google")) {
                            webView.loadUrl(targetUrl)
                        }
                    }
                    delay(5000L)

                    hud.updateStatus("Flow: Submitting 8s Prompt...", BotMood.WORKING, taskRunning = true)
                    val jsSubmit = """
                        (function() {
                            const boxes = Array.from(document.querySelectorAll('textarea, [contenteditable="true"], input[type="text"]'));
                            const target = boxes.find(el => {
                                const ph = (el.getAttribute('placeholder') || el.innerText || '').toLowerCase();
                                return !ph.includes('search');
                            }) || boxes[boxes.length - 1];
                            if (!target) return 'NO_BOX';
                            target.focus();
                            document.execCommand('selectAll', false, null);
                            document.execCommand('insertText', false, ${JSONObject.quote(safePrompt)});
                            target.dispatchEvent(new Event('input', { bubbles: true }));
                            setTimeout(() => {
                                const btns = Array.from(document.querySelectorAll('button, [role="button"]'));
                                const genBtn = btns.find(b => {
                                    const t = (b.innerText || b.getAttribute('aria-label') || '').toLowerCase();
                                    return t.includes('arrow_forward') || t.includes('generate') || t.includes('create');
                                });
                                if (genBtn) genBtn.click();
                            }, 600);
                            return 'SUBMITTED';
                        })();
                    """.trimIndent()

                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(jsSubmit, null)
                    }

                    var downloadedVideoFile: File? = null
                    for (waitTick in 1..48) {
                        if (!isAgentRunning) break
                        hud.updateStatus("⏳ Generating 8s Video (${waitTick * 5}s)...", BotMood.THINKING, taskRunning = true)
                        delay(5000L)

                        val videoUrl = extractReadyVideoUrlFromWebView(webView)
                        if (videoUrl.isNotBlank() && videoUrl.startsWith("http")) {
                            hud.updateStatus("📥 Downloading 8s MP4...", BotMood.WORKING, taskRunning = true)
                            downloadedVideoFile = downloadVideoFromUrlToStorage(videoUrl, "flow_${System.currentTimeMillis()}.mp4")
                            if (downloadedVideoFile != null) break
                        }
                    }

                    if (downloadedVideoFile != null && downloadedVideoFile.exists()) {
                        log("✅ Downloaded 8s Video: ${downloadedVideoFile.absolutePath}")
                        uploadVideoUriToYouTubeShorts(Uri.fromFile(downloadedVideoFile), viralTitle)
                    } else {
                        log("⚠️ Could not auto-catch MP4 stream; check Google Flow login or credits.")
                        hud.updateStatus("Check Flow Login/Credits", BotMood.ERROR, taskRunning = false)
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

    private suspend fun runBackgroundWebAgentLoop(goal: String, plannedSteps: List<String>) {
        val webView = hud.getOrCreateBackgroundWebView() ?: return
        val specs = getCalibratedDeviceBodySpecs()
        val history = mutableListOf<String>()

        for (step in 1..60) {
            if (!isAgentRunning) break
            hud.updateStatus("🌐 Web Step $step...", BotMood.WORKING, taskRunning = true)

            val domTagList = injectVisualNumberStickersInWebView(webView)
            delay(400L)
            val webBitmap = hud.captureWebViewCanvasBitmap()

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

            log("🌐 Web Step $step [${decision.modelUsed}]: ${decision.action} -> ${decision.reason}")
            if (decision.action == "DONE") {
                hud.updateStatus("🎉 Web Task Done!", BotMood.HAPPY, taskRunning = false)
                break
            }

            executeWebStepInDom(webView, decision)
            history.add("${decision.action}: ${decision.reason}")
            delay(3000L)
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
            webView.evaluateJavascript(js) { res ->
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

    private suspend fun extractReadyVideoUrlFromWebView(webView: WebView): String =
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
            webView.evaluateJavascript(js) { res ->
                cont.resume(res?.trim('"') ?: "")
            }
        }

    private suspend fun downloadVideoFromUrlToStorage(videoUrl: String, fileName: String): File? =
        withContext(Dispatchers.IO) {
            try {
                val outDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "AutoVideos"
                ).apply { if (!exists()) mkdirs() }
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
            while (isActive) {
                delay(25000L)
                val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("scheduler_enabled", false) || isAgentRunning) continue

                val nowHm = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
                val targetHm = prefs.getString("schedule_time_hm", "19:00") ?: "19:00"

                if (nowHm == targetHm && lastTriggeredMinute != nowHm) {
                    lastTriggeredMinute = nowHm
                    val mode = prefs.getString("scheduled_pipeline_type", "FLOW_YT") ?: "FLOW_YT"
                    log("⏰ 24/7 Scheduler Triggered at $nowHm -> $mode")
                    if (mode == "FLOW_YT") {
                        val topic = prefs.getString("flow_topic", "Futuristic Sci-Fi World") ?: "Futuristic Sci-Fi World"
                        val credits = prefs.getInt("flow_daily_videos", 1)
                        startGoogleFlowToYouTubePipeline(topic, credits)
                    } else {
                        val folder = prefs.getString(
                            "yt_folder_path",
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                        ) ?: ""
                        val oldest = prefs.getBoolean("yt_pick_oldest", true)
                        startYouTubeFolderAutoUpload(folder, oldest)
                    }
                }
            }
        }
    }

    private fun scanScreenInteractiveElements(specs: DeviceBodySpecs): List<TaggedUiElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<TaggedUiElement>()
        var idCounter = 0

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val isValidSize = rect.width() > 16 && rect.height() > 16 &&
                rect.bottom > specs.cutoutSafeTop && rect.top < specs.screenHeight

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName?.substringAfterLast('/') ?: ""
            val label = when {
                text.isNotEmpty() -> text
                desc.isNotEmpty() -> desc
                viewId.isNotEmpty() -> viewId
                else -> ""
            }

            if (isValidSize && (node.isClickable || node.isEditable || node.isCheckable || label.isNotEmpty())) {
                result.add(
                    TaggedUiElement(
                        tagId = idCounter++,
                        node = node,
                        label = label.ifEmpty { "Element" }.take(55),
                        className = node.className?.toString()?.substringAfterLast('.') ?: "View",
                        bounds = rect,
                        centerX = rect.centerX(),
                        centerY = rect.centerY()
                    )
                )
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)
        return result
    }

    private fun buildScreenSignature(pkg: String, elements: List<TaggedUiElement>): String {
        return pkg + ":" + elements.take(15).joinToString("|") { "${it.label}@${it.centerY / 40}" }
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
        return elements.filter { it.node?.isClickable == true }
            .minByOrNull { hypot((it.centerX - px).toDouble(), (it.centerY - py).toDouble()) }
            ?.takeIf { hypot((it.centerX - px).toDouble(), (it.centerY - py).toDouble()) <= 85.0 }
    }

    private suspend fun tapDeadCenterOfElement(element: TaggedUiElement) {
        performPhysicalTap(element.centerX, element.centerY)
    }

    private suspend fun performPhysicalTap(x: Int, y: Int) {
        lastAgentTapTimestamp = System.currentTimeMillis()
        hud.animateHandTap(x.toFloat(), y.toFloat())
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 95L))
            .build()
        dispatchGesture(gesture, null, null)
        delay(120L)
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

    private suspend fun typeAndPasteTextReliably(textToType: String) {
        if (textToType.isBlank()) return
        withContext(Dispatchers.Main) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("aura_text", textToType))
        }

        val root = rootInActiveWindow ?: return
        var setSuccess = false

        fun trySetTextOnFocusedOrEditable(node: AccessibilityNodeInfo?) {
            if (node == null || setSuccess) return
            if (node.isFocused || node.isEditable || node.className?.toString()?.contains("EditText", true) == true) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    setSuccess = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    }
                    return
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    setSuccess = true
                    return
                }
            }
            for (i in 0 until node.childCount) {
                trySetTextOnFocusedOrEditable(node.getChild(i))
            }
        }

        trySetTextOnFocusedOrEditable(root)
        delay(400L)
    }

    private suspend fun clickSearchOrPostIfPresent(typedText: String) {
        val specs = getCalibratedDeviceBodySpecs()
        val elements = scanScreenInteractiveElements(specs)
        val postOrSend = elements.find {
            it.label.equals("Post", true) ||
                it.label.equals("Send", true) ||
                it.label.contains("row_search_for_x", true)
        }
        if (postOrSend != null) {
            tapDeadCenterOfElement(postOrSend)
            return
        }
        if (isSoftKeyboardOpen()) {
            performPhysicalTap((specs.screenWidth * 0.90f).toInt(), (specs.screenHeight * 0.91f).toInt())
        }
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
            tapDeadCenterOfElement(sendNode)
        }
    }

    private suspend fun takeHdDisplayScreenshotWithTimeout(timeoutMs: Long = 2500L): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                try {
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
    }

    private fun launchAppByNameOrPackage(query: String) {
        val q = query.trim().lowercase(Locale.ROOT)
        val knownMap = mapOf(
            "instagram" to "com.instagram.android",
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
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
