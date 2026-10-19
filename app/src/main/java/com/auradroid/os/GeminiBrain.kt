package com.auradroid.os

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class DeviceBodySpecs(
    val modelName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val statusBarHeight: Int,
    val cutoutSafeTop: Int,
    val navBarHeight: Int,
    val densityDpi: Int
)

data class AgentStepDecision(
    val action: String,         // CLICK_TAG, CLICK_XY, LONG_PRESS_XY, TYPE, SWIPE, BACK, LAUNCH_APP, OPEN_SETTINGS, WAIT, DONE
    val tagId: Int = -1,
    val x: Int = 0,
    val y: Int = 0,
    val endX: Int = 0,
    val endY: Int = 0,
    val text: String = "",
    val packageNameOrSetting: String = "",
    val reason: String = "",
    val modelUsed: String = ""
)

data class MemoryChatReply(
    val replyText: String,
    val extractedProblem: String,
    val extractedSolutionRule: String,
    val tapXRatio: Float,
    val tapYRatio: Float,
    val modelUsed: String
)

data class PostRunReflectionResult(
    val reflectionSummary: String,
    val learnedRules: List<LearnedFixRecord>,
    val modelUsed: String
)

class GeminiBrain(private var apiKey: String) {

    private val rateLock = Any()

    // SMART VISION & HIGH-ACCURACY LADDER (Starts with 3.8 Flash up to 9 RPM, then rotates to 3.5 Lite -> 3.1 Lite -> 3.1 Pro)
    private val smartVisionLadder = listOf(
        "gemini-3.8-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-3.1-pro-preview",
        "gemini-robotics-er-2-preview",
        "gemma-4-31b-it",
        "gemma-4-26b-a4b-it"
    )

    // TEXT-ONLY / LIGHT TASK LADDER (Saves 3.8 Flash's 9 RPM exclusively for live screen vision!)
    private val lightTextTaskLadder = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-3.8-flash",
        "gemma-4-31b-it",
        "gemini-3.1-pro-preview"
    )

    // Sliding-window 60-second request timestamp tracker per model bucket
    private val modelRequestTimestamps = mutableMapOf<String, MutableList<Long>>()
    private val blockedModels = mutableSetOf<String>()
    private val quotaCooldownModels = mutableMapOf<String, Long>()

    var onBrainLog: ((String) -> Unit)? = null

    fun updateApiKey(newKey: String) {
        apiKey = newKey.trim()
    }

    /**
     * Hard limit per 60 seconds (9 RPM Jugad so we NEVER hit Google's 10-15 RPM wall!)
     */
    private fun getMaxSafeRpmForModel(model: String): Int {
        return when {
            model.contains("pro") -> 4
            else -> 9 // Strictly max 9 requests per 60 seconds for 3.8-flash, 3.5-flash-lite, 3.1-flash-lite
        }
    }

    private fun getUsedRpmInLastMinute(model: String, nowMs: Long = System.currentTimeMillis()): Int = synchronized(rateLock) {
        val list = modelRequestTimestamps.getOrPut(model) { mutableListOf() }
        list.removeAll { nowMs - it > 60_000L }
        return list.size
    }

    private fun hasSafeRpmSlot(model: String, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(rateLock) {
        val used = getUsedRpmInLastMinute(model, nowMs)
        return used < getMaxSafeRpmForModel(model)
    }

    private fun recordModelRequestTimestamp(model: String) = synchronized(rateLock) {
        val nowMs = System.currentTimeMillis()
        val list = modelRequestTimestamps.getOrPut(model) { mutableListOf() }
        list.removeAll { nowMs - it > 60_000L }
        list.add(nowMs)
    }

    fun getActiveModelListStatus(): String {
        val now = System.currentTimeMillis()
        return smartVisionLadder.joinToString("\n") { m ->
            val used = getUsedRpmInLastMinute(m, now)
            val maxRpm = getMaxSafeRpmForModel(m)
            val cooldownUntil = quotaCooldownModels[m] ?: 0L
            when {
                blockedModels.contains(m) -> "🚫 $m (Unavailable)"
                cooldownUntil > now -> {
                    val remSec = ((cooldownUntil - now) / 1000L).coerceAtLeast(1L)
                    "⏳ $m (Cooldown ${remSec}s | $used/$maxRpm RPM)"
                }
                used >= maxRpm -> "🔄 $m (9-RPM Limit Reached: $used/$maxRpm -> Auto-Rotated)"
                else -> "✅ $m (Active Free | Used: $used/$maxRpm RPM)"
            }
        }
    }

    @Suppress("DEPRECATION")
    fun bitmapToHdWebpBase64(bitmap: Bitmap, maxHeight: Int = 1280): String {
        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
        val targetH = bitmap.height.coerceAtMost(maxHeight)
        val targetW = (targetH * ratio).toInt().coerceAtLeast(360)
        val scaled = if (bitmap.height > targetH) {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }

        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.WEBP, 84, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun parseCleanJson(raw: String): JSONObject {
        val body = raw.substringAfter("{").substringBeforeLast("}")
        return JSONObject("{$body}")
    }

    /**
     * Smart 9-RPM Load-Balanced Caller:
     * Uses 3.8 Flash up to 9 requests/min for maximum accuracy, then seamlessly rotates to
     * 3.5-flash-lite (9 RPM) -> 3.1-flash-lite (9 RPM) -> 3.1-pro-preview (4 RPM) -> robotics/gemma!
     */
    private suspend fun callWithLadder(
        prompt: String,
        imagesBase64: List<String> = emptyList(),
        useDeep38Flash: Boolean = true
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Pair("", "No API Key")

        val now = System.currentTimeMillis()
        val baseLadder = if (useDeep38Flash) smartVisionLadder else lightTextTaskLadder

        // 1. Pick models that are NOT blocked, NOT in 429 cooldown, AND under our 9-RPM guard
        val availableUnder9Rpm = baseLadder.filter { m ->
            !blockedModels.contains(m) &&
                (quotaCooldownModels[m] ?: 0L) <= now &&
                hasSafeRpmSlot(m, now)
        }

        if (useDeep38Flash && !hasSafeRpmSlot("gemini-3.8-flash", now) && availableUnder9Rpm.isNotEmpty()) {
            onBrainLog?.invoke("🛡️ 9-RPM Guard: [gemini-3.8-flash] at 9/9 RPM -> Auto-using [${availableUnder9Rpm.first()}]")
        }

        val candidates = availableUnder9Rpm.ifEmpty {
            baseLadder.filter { !blockedModels.contains(it) && (quotaCooldownModels[it] ?: 0L) <= now }.ifEmpty {
                quotaCooldownModels.clear()
                baseLadder.filter { !blockedModels.contains(it) }.ifEmpty {
                    blockedModels.clear()
                    baseLadder
                }
            }
        }

        for (model in candidates) {
            recordModelRequestTimestamp(model)
            val result = executeSingleModelCall(model, prompt, imagesBase64)
            when (result.first) {
                200 -> {
                    if (result.second.isNotBlank()) {
                        return@withContext Pair(result.second, model)
                    }
                }
                429 -> {
                    quotaCooldownModels[model] = System.currentTimeMillis() + 35_000L
                    onBrainLog?.invoke("⚠️ Quota 429 on [$model] -> Instant switch to next free model...")
                }
                404, 403, 400 -> {
                    blockedModels.add(model)
                    onBrainLog?.invoke("🚫 Model [$model] unavailable (${result.first}) -> Skipping...")
                }
                else -> {
                    onBrainLog?.invoke("🔄 Model [$model] busy (${result.first}) -> Trying next free model...")
                }
            }
        }

        Pair("", "All models busy")
    }

    private fun executeSingleModelCall(
        model: String,
        prompt: String,
        imagesBase64: List<String>
    ): Pair<Int, String> {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 10000
                readTimeout = 20000
                doOutput = true
            }

            val partsArray = JSONArray()
            partsArray.put(JSONObject().put("text", prompt))
            for (imgB64 in imagesBase64) {
                if (imgB64.isNotBlank()) {
                    val inlineData = JSONObject()
                        .put("mimeType", "image/webp")
                        .put("data", imgB64)
                    partsArray.put(JSONObject().put("inlineData", inlineData))
                }
            }

            val payload = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.15)
                    if (!model.startsWith("gemma")) {
                        put("responseMimeType", "application/json")
                    }
                })
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val text = json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text", "") ?: ""
                Pair(200, text.trim())
            } else {
                Pair(code, "")
            }
        } catch (_: Exception) {
            Pair(500, "")
        }
    }

    /**
     * 1. Interactive Chat, Detailed Task Planner & Chat-Based Upload Time Scheduler
     */
    suspend fun chatAndBuildDetailedPlan(
        userMessage: String,
        chatHistory: String,
        userProfileRules: String
    ): JSONObject {
        val sysPrompt = """
            You are AuraDroid OS, an intelligent friendly AI Assistant, Phone Automation Planner & Upload Scheduler.
            Speak in natural Hinglish/Hindi.
            User's saved profile/rules: $userProfileRules
            Recent conversation:
            $chatHistory
            
            User just said: "$userMessage"
            
            SPECIAL MODES YOU CAN SET IN "executionMode":
            1. If the user wants to set/change daily YouTube upload time (e.g., "upload time 8:30 PM set kar do", "roz 7 baje video upload karo"):
               - Set "executionMode": "SCHEDULE_YT_HH:mm" (replace HH:mm with 24-hour time like "SCHEDULE_YT_20:30" or "SCHEDULE_YT_19:00").
               - Set "readyToExecute": true, and add 1 step in "steps": ["Activate 24/7 Daily YouTube Scheduler at HH:mm"].
            2. If the user wants to upload a video from Folder to YouTube Shorts right now:
               - Set "executionMode": "YOUTUBE_FOLDER_UPLOAD", "readyToExecute": true.
            3. If the user wants to generate an 8s Google Flow video and upload to YouTube right now:
               - Set "executionMode": "GOOGLE_FLOW_UPLOAD", "readyToExecute": true.
            4. For normal phone automation (Instagram, WhatsApp, YouTube, Settings):
               - Set "executionMode": "LIVE_PHONE" (or "BACKGROUND_WEB"), break into clear steps in "steps", and set "readyToExecute": true.
            
            Return ONLY valid JSON:
            {
              "reply": "Friendly Hinglish response explaining your understanding",
              "readyToExecute": true,
              "taskTitle": "Short title of task",
              "executionMode": "LIVE_PHONE",
              "steps": [
                "Step 1: Open Instagram app directly",
                "Step 2: Tap Search icon at bottom bar and type query",
                "Step 3: Open first video reel, tap Like heart, comment, and share"
              ]
            }
        """.trimIndent()

        val (raw, model) = callWithLadder(sysPrompt, useDeep38Flash = false)
        return try {
            parseCleanJson(raw).put("modelUsed", model)
        } catch (_: Exception) {
            JSONObject().apply {
                put("reply", if (raw.isNotBlank()) raw else "भाई, अपनी Gemini API Key एक बार Engine टैब में चेक कर लो।")
                put("readyToExecute", false)
                put("steps", JSONArray())
            }
        }
    }

    /**
     * 2. Real-Time Vision + Learned Playbook Step Engine
     */
    suspend fun decideNextStep(
        goal: String,
        planSteps: List<String>,
        currentStepHistory: List<String>,
        bodySpecs: DeviceBodySpecs,
        numberedNodesList: String,
        screenshotBitmap: Bitmap?,
        isStuck: Boolean,
        recalledFixes: List<LearnedFixRecord>,
        isContinuousMonitor: Boolean
    ): AgentStepDecision {
        val imgList = if (screenshotBitmap != null) listOf(bitmapToHdWebpBase64(screenshotBitmap)) else emptyList()

        val memoryBlock = if (recalledFixes.isNotEmpty()) {
            "🧠 LEARNED APP MEMORY, POST-RUN AUTO-REFLECTIONS & VIDEO COORDINATES (HIGHEST PRIORITY!):\n" +
                recalledFixes.joinToString("\n") { fix ->
                    val px = (fix.tapXRatio * bodySpecs.screenWidth).toInt()
                    val py = (fix.tapYRatio * bodySpecs.screenHeight).toInt()
                    val normX = (fix.tapXRatio * 1000).toInt()
                    val normY = (fix.tapYRatio * 1000).toInt()
                    "- [${fix.sourceType}] When on [${fix.problemDescription}]: ${fix.solutionRule} -> Exact Learned Point: 0..1000XY=($normX, $normY) / Pixels=($px, $py)"
                }
        } else {
            ""
        }

        val monitorRule = if (isContinuousMonitor) {
            "IMPORTANT: This is a CONTINUOUS MONITORING / LIVE CHAT task. NEVER return DONE after sending 1 reply! After sending a message, return WAIT to watch for the next incoming message."
        } else {
            ""
        }

        val plannedJoined = planSteps.joinToString(" -> ")
        val recentJoined = currentStepHistory.takeLast(10).joinToString(" | ")
        val navTop = bodySpecs.screenHeight - bodySpecs.navBarHeight

        val prompt = """
            You are AuraDroid OS Autonomous Vision Engine controlling an Android device.
            
            DEVICE BODY SPECS:
            - Phone Model: ${bodySpecs.modelName}
            - Exact Full Screen Resolution: ${bodySpecs.screenWidth}x${bodySpecs.screenHeight} px
            - Top Punch-Hole / Status Bar Unsafe Zone: Y = 0 to ${bodySpecs.cutoutSafeTop} px (NEVER tap inside this top status bar!)
            - Bottom Navigation Bar Zone: Y = $navTop to ${bodySpecs.screenHeight} px
            
            OVERALL GOAL: $goal
            PLANNED SUB-TASKS: $plannedJoined
            RECENT ACTIONS ALREADY COMPLETED (NEVER REPEAT COMPLETED ACTIONS!):
            $recentJoined
            $monitorRule
            $memoryBlock
            
            INTERACTIVE ELEMENTS ON SCREEN (with [TAG_ID] and exact Center X,Y):
            $numberedNodesList
            
            CRITICAL EXECUTION RULES (ZERO MISTAKE POLICY):
            1. Check RECENT ACTIONS ALREADY COMPLETED:
               - If RECENT ACTIONS shows that you ALREADY typed or posted a comment (or if your comment is already visible in the comments list on screen), NEVER tap the comment box or keyboard send button again!
               - If the Instagram Comment Sheet or Keyboard is still open after commenting and you need to Share or Like, return action "BACK" immediately to close the comment sheet/keyboard so the main Reel buttons (Like, Comment, Share/Send paper plane) become visible!
               - If you already tapped "Like", DO NOT tap Like again.
               - On the Instagram Share sheet: Tap the Search bar on the share sheet and TYPE the friend's name (e.g., "rahul"). Once the friend's name appears, tap the friend's row/avatar ONCE. When the bottom blue "Send" button appears (around Y=910 in 0..1000 scale), immediately tap the bottom blue "Send" button! Once sent, return "DONE"!
            2. If you see "Comments on this post have been limited", press "BACK" and then "SWIPE" up to the next Reel.
            3. Prefer "CLICK_TAG" with the exact "tagId" whenever the target button/box is in the list.
            4. Otherwise use "CLICK_XY" with 0..1000 normalized coordinates.
            
            Return ONLY valid JSON:
            {
              "action": "CLICK_TAG",
              "tagId": 4,
              "x": 500,
              "y": 500,
              "endX": 500,
              "endY": 200,
              "text": "",
              "packageNameOrSetting": "",
              "reason": "Brief 5-word status"
            }
        """.trimIndent()

        val (raw, modelUsed) = callWithLadder(prompt, imgList, useDeep38Flash = true)

        return try {
            val j = parseCleanJson(raw)
            AgentStepDecision(
                action = j.optString("action", "WAIT").uppercase(Locale.ROOT),
                tagId = j.optInt("tagId", -1),
                x = j.optInt("x", 500),
                y = j.optInt("y", 500),
                endX = j.optInt("endX", 500),
                endY = j.optInt("endY", 200),
                text = j.optString("text", ""),
                packageNameOrSetting = j.optString("packageNameOrSetting", ""),
                reason = j.optString("reason", "Working..."),
                modelUsed = modelUsed
            )
        } catch (_: Exception) {
            AgentStepDecision(action = "WAIT", reason = "Re-scanning screen...", modelUsed = modelUsed)
        }
    }

    /**
     * 3. Post-Run Automatic Self-Reflection & Optimization
     */
    suspend fun autoReflectAndImproveAfterRun(
        appCategory: String,
        goal: String,
        plannedSteps: List<String>,
        actualStepsTaken: List<String>,
        finalStatus: String,
        runBitmaps: List<Bitmap>
    ): PostRunReflectionResult {
        val imgsBase64 = runBitmaps.takeLast(8).map { bitmapToHdWebpBase64(it, maxHeight = 960) }

        val prompt = """
            You are Gemini 3.8 Flash Autonomous Self-Reflection & Optimization Architect for AuraDroid OS.
            An automation run just ended.
            
            RUN CONTEXT:
            - App Category: $appCategory
            - User Goal: $goal
            - Planned Steps: ${plannedSteps.joinToString(" -> ")}
            - Actual Steps Taken: ${actualStepsTaken.joinToString(" | ")}
            - Final Run Status: $finalStatus
            
            YOUR TASK:
            1. Examine all ${imgsBase64.size} sequential screenshots from this run carefully.
            2. Identify where the agent succeeded, where it hesitated/repeated steps, or why it stopped short of the goal.
            3. Formulate 1 to 3 concrete, high-precision optimization rules ("rules") with exact 0.0 to 1.0 screen tap coordinates ("tapXRatio", "tapYRatio").
            4. Write a short Hinglish summary ("reflectionSummary") of what you learned.
            
            Return ONLY valid JSON:
            {
              "reflectionSummary": "Short Hinglish summary of what went right/wrong and how you optimized it",
              "rules": [
                {
                  "screenWhen": "Specific screen state seen in the screenshots",
                  "actionInstruction": "Exact optimized action and button to tap immediately next time",
                  "tapXRatio": 0.50,
                  "tapYRatio": 0.91
                }
              ]
            }
        """.trimIndent()

        val (raw, modelUsed) = callWithLadder(prompt, imgsBase64, useDeep38Flash = true)
        onBrainLog?.invoke("🧠 [$modelUsed] Completed Post-Run Auto-Reflection on ${imgsBase64.size} screenshots!")

        val extractedRules = mutableListOf<LearnedFixRecord>()
        var summaryText = "मैंने इस रन के सभी स्क्रीनशॉट्स का विश्लेषण करके अगले रन के लिए मेमोरी को और बेहतर बना दिया है।"

        try {
            val j = parseCleanJson(raw)
            summaryText = j.optString("reflectionSummary", summaryText)
            val arr = j.optJSONArray("rules") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                extractedRules.add(
                    LearnedFixRecord(
                        id = "refl_${System.currentTimeMillis()}_$i",
                        appCategory = appCategory,
                        sourceType = "AUTO_3.8_REFLECTION ($modelUsed)",
                        screenSignature = "post_run_reflection_$i",
                        problemDescription = item.optString("screenWhen", "Optimized step in $appCategory"),
                        solutionRule = item.optString("actionInstruction", "Follow auto-reflected coordinate"),
                        tapXRatio = item.optDouble("tapXRatio", 0.5).toFloat(),
                        tapYRatio = item.optDouble("tapYRatio", 0.5).toFloat(),
                        timestamp = "Auto-Reflected"
                    )
                )
            }
        } catch (_: Exception) {}

        return PostRunReflectionResult(
            reflectionSummary = summaryText,
            learnedRules = extractedRules,
            modelUsed = modelUsed
        )
    }

    /**
     * 4. Interactive Chat Inside Any Past Automation Run
     */
    suspend fun chatAboutAutomationRunWith38Flash(
        runRecord: AutomationRunRecord,
        userCorrectionMessage: String,
        screenshotsBase64: List<String>
    ): MemoryChatReply {
        val prompt = """
            You are Gemini 3.8 Flash Run-Correction Architect inside AuraDroid OS.
            The user is reviewing a past Automation Run and chatting with you to teach you what went wrong and how to fix it permanently.
            
            AUTOMATION RUN DETAILS:
            - Task Title: ${runRecord.title}
            - App Category: ${runRecord.appCategory}
            - Final Status: ${runRecord.status}
            - Steps Taken by Agent: ${runRecord.stepsSummary.joinToString(" -> ")}
            - Previous Discussion on this Run: ${runRecord.chatHistory.takeLast(6).joinToString(" | ")}
            
            Attached are the actual screenshots captured during this Automation Run.
            The User just told you: "$userCorrectionMessage"
            
            Return ONLY valid JSON:
            {
              "replyText": "Clear Hinglish reply explaining what you understood from the screenshots and how you fixed the rule",
              "problemScreen": "Which screen or step in ${runRecord.appCategory} had the issue",
              "solutionRule": "Exact action and coordinate to tap next time on this screen",
              "tapXRatio": 0.50,
              "tapYRatio": 0.94
            }
        """.trimIndent()

        val (raw, modelUsed) = callWithLadder(prompt, screenshotsBase64, useDeep38Flash = true)
        onBrainLog?.invoke("💬 [$modelUsed] Learned Run Correction for ${runRecord.appCategory}")
        return try {
            val j = parseCleanJson(raw)
            MemoryChatReply(
                replyText = j.optString("replyText", "समझ गया भाई! मैंने इस रन के स्क्रीनशॉट्स देखकर यह नियम हमेशा के लिए सेव कर लिया है।"),
                extractedProblem = j.optString("problemScreen", "Correction for ${runRecord.title}"),
                extractedSolutionRule = j.optString("solutionRule", userCorrectionMessage),
                tapXRatio = j.optDouble("tapXRatio", 0.5).toFloat(),
                tapYRatio = j.optDouble("tapYRatio", 0.5).toFloat(),
                modelUsed = modelUsed
            )
        } catch (_: Exception) {
            MemoryChatReply(
                replyText = if (raw.isNotBlank()) raw else "समझ गया भाई! मैंने आपकी बात को ${runRecord.appCategory} की मेमोरी में सेव कर लिया है।",
                extractedProblem = "Run Correction (${runRecord.title})",
                extractedSolutionRule = userCorrectionMessage,
                tapXRatio = 0.5f,
                tapYRatio = 0.5f,
                modelUsed = modelUsed
            )
        }
    }

    /**
     * 5. Interactive Chat Inside Any Uploaded Training Video
     */
    suspend fun chatAboutTrainingVideoWith38Flash(
        videoSession: VideoTrainingSession,
        userMessage: String,
        videoFramesBase64: List<String>
    ): MemoryChatReply {
        val prompt = """
            You are Gemini 3.8 Flash Video-Memory Coach inside AuraDroid OS.
            The user uploaded a training video for app "${videoSession.appCategory}" and is now chatting with you to explain specific frames, mistakes, or tapping points in the video.
            
            VIDEO SESSION CONTEXT:
            - App Category: ${videoSession.appCategory}
            - Initial Note: ${videoSession.userNote}
            - Rules Extracted So Far: ${videoSession.extractedRulesSummary.joinToString(" | ")}
            - Previous Chat History: ${videoSession.chatHistory.takeLast(6).joinToString(" | ")}
            
            Attached are the sequential frames from the user's training video.
            The User just said: "$userMessage"
            
            Return ONLY valid JSON:
            {
              "replyText": "Clear Hinglish response referencing the video frames and confirming the updated rule",
              "problemScreen": "Specific screen in the video",
              "solutionRule": "Exact action & button to tap based on user's guidance",
              "tapXRatio": 0.50,
              "tapYRatio": 0.85
            }
        """.trimIndent()

        val (raw, modelUsed) = callWithLadder(prompt, videoFramesBase64, useDeep38Flash = true)
        onBrainLog?.invoke("🎬 [$modelUsed] Answered Video Memory Chat for ${videoSession.appCategory}")
        return try {
            val j = parseCleanJson(raw)
            MemoryChatReply(
                replyText = j.optString("replyText", "समझ गया भाई! मैंने वीडियो के फ्रेम्स देखकर यह स्टेप और टैप पॉइंट अपडेट कर लिया है।"),
                extractedProblem = j.optString("problemScreen", "Video Frame Guidance (${videoSession.appCategory})"),
                extractedSolutionRule = j.optString("solutionRule", userMessage),
                tapXRatio = j.optDouble("tapXRatio", 0.5).toFloat(),
                tapYRatio = j.optDouble("tapYRatio", 0.5).toFloat(),
                modelUsed = modelUsed
            )
        } catch (_: Exception) {
            MemoryChatReply(
                replyText = if (raw.isNotBlank()) raw else "समझ गया भाई! मैंने इस वीडियो के लिए आपका निर्देश सेव कर लिया है।",
                extractedProblem = "Video Chat Guidance",
                extractedSolutionRule = userMessage,
                tapXRatio = 0.5f,
                tapYRatio = 0.5f,
                modelUsed = modelUsed
            )
        }
    }

    /**
     * 6. Before + After Screenshot Human Touch Analyzer
     */
    suspend fun analyzeBeforeAfterHumanTouchWith38Flash(
        appCategory: String,
        goal: String,
        beforeScreenshot: Bitmap?,
        afterScreenshot: Bitmap?,
        humanTapXRatio: Float,
        humanTapYRatio: Float,
        clickedNodeDesc: String
    ): Pair<String, String> {
        val imgs = mutableListOf<String>()
        if (beforeScreenshot != null) imgs.add(bitmapToHdWebpBase64(beforeScreenshot))
        if (afterScreenshot != null) imgs.add(bitmapToHdWebpBase64(afterScreenshot))

        val normX = (humanTapXRatio * 1000).toInt()
        val normY = (humanTapYRatio * 1000).toInt()

        val prompt = """
            You are Gemini 3.8 Flash Memory Architect for AuraDroid OS.
            During the task "$goal" inside app "$appCategory", the Human User manually tapped the screen!
            - Image 1: BEFORE Screenshot.
            - Human Touch Action: Tapped at 0..1000 coordinates X=$normX, Y=$normY on UI element [$clickedNodeDesc].
            - Image 2: AFTER Screenshot.
            
            Return ONLY valid JSON:
            {
              "problem": "Exact screen state in $appCategory before tap",
              "solutionRule": "Tap '$clickedNodeDesc' at 0..1000 XY($normX, $normY) to proceed"
            }
        """.trimIndent()

        val (raw, modelUsed) = callWithLadder(prompt, imgs, useDeep38Flash = true)
        onBrainLog?.invoke("🧠 [$modelUsed] Analyzed Before+After Human Touch for $appCategory")
        return try {
            val j = parseCleanJson(raw)
            Pair(
                j.optString("problem", "Screen state in $appCategory"),
                j.optString("solutionRule", "Tap '$clickedNodeDesc' at 0..1000 XY($normX, $normY)")
            )
        } catch (_: Exception) {
            Pair("Human Touch Fix in $appCategory", "Tap '$clickedNodeDesc' at 0..1000 XY($normX, $normY)")
        }
    }

    /**
     * 7. Frame-by-Frame Uploaded Video Analyzer
     */
    suspend fun analyzeTrainingVideoFrames(
        appCategory: String,
        userInstruction: String,
        framesBase64: List<String>
    ): List<LearnedFixRecord> {
        val prompt = """
            You are Gemini 3.8 Flash Frame-by-Frame Video Trainer for AuraDroid OS.
            The user uploaded a screen-recording video for app "$appCategory" with note: "$userInstruction".
            
            Look at all ${framesBase64.size} sequential video frames carefully:
            1. Detect every mistake the AI made in the video and every correct button that needs to be tapped.
            2. Estimate the exact 0.0 to 1.0 screen X,Y ratio for each target button.
            
            Return ONLY valid JSON with a "rules" array:
            {
              "rules": [
                {
                  "screenWhen": "Description of screen in $appCategory",
                  "actionInstruction": "Exact button to tap and what to do",
                  "tapXRatio": 0.50,
                  "tapYRatio": 0.90
                }
              ]
            }
        """.trimIndent()

        val (raw, modelUsed) = callWithLadder(prompt, framesBase64, useDeep38Flash = true)
        onBrainLog?.invoke("🎬 [$modelUsed] Completed Frame-by-Frame Video Analysis (${framesBase64.size} frames)")

        val extracted = mutableListOf<LearnedFixRecord>()
        try {
            val j = parseCleanJson(raw)
            val arr = j.optJSONArray("rules") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                extracted.add(
                    LearnedFixRecord(
                        id = "vid_${System.currentTimeMillis()}_$i",
                        appCategory = appCategory,
                        sourceType = "VIDEO_FRAME_TRAINING ($modelUsed)",
                        screenSignature = "video_frame_rule_$i",
                        problemDescription = item.optString("screenWhen", "Step ${i + 1} in $appCategory"),
                        solutionRule = item.optString("actionInstruction", "Follow trained video step"),
                        tapXRatio = item.optDouble("tapXRatio", 0.5).toFloat(),
                        tapYRatio = item.optDouble("tapYRatio", 0.5).toFloat(),
                        timestamp = "Video Trained"
                    )
                )
            }
        } catch (_: Exception) {}

        if (extracted.isEmpty() && raw.isNotBlank()) {
            extracted.add(
                LearnedFixRecord(
                    id = "vid_${System.currentTimeMillis()}",
                    appCategory = appCategory,
                    sourceType = "VIDEO_FRAME_TRAINING ($modelUsed)",
                    screenSignature = "video_rule",
                    problemDescription = "Video Workflow ($userInstruction)",
                    solutionRule = raw.take(300),
                    tapXRatio = 0.5f,
                    tapYRatio = 0.5f,
                    timestamp = "Video Trained"
                )
            )
        }
        return extracted
    }

    /**
     * 8. Safe Video Prompt + Viral YouTube Shorts Title Generator
     */
    suspend fun generateFlowSafePromptAndTitle(topic: String): Pair<String, String> {
        val safeRegex = Regex("(?i)\\b(boy|girl|child|kid|minor|baby|skin)\\b")
        val sanitizedTopic = topic.replace(safeRegex, "cybernetic explorer")

        val prompt = """
            Create:
            1. A policy-safe, cinematic 8-second vertical 9:16 AI video generation prompt about: "$sanitizedTopic".
            2. A viral YouTube Shorts title with emojis and #shorts #viral hashtags (under 75 chars).
            
            Return ONLY JSON:
            {
              "videoPrompt": "Cinematic 8k vertical video of...",
              "youtubeTitle": "Amazing Title! #shorts #viral"
            }
        """.trimIndent()

        val (raw, _) = callWithLadder(prompt, useDeep38Flash = false)
        return try {
            val j = parseCleanJson(raw)
            Pair(
                j.optString("videoPrompt", "Cinematic 8k vertical sci-fi scene of $sanitizedTopic, dramatic lighting, ultra detailed"),
                j.optString("youtubeTitle", "$sanitizedTopic #shorts #viral")
            )
        } catch (_: Exception) {
            Pair(
                "Cinematic 8k vertical sci-fi scene of $sanitizedTopic, unreal engine 5, hyper-realistic lighting",
                "$sanitizedTopic #shorts #viral"
            )
        }
    }
}
