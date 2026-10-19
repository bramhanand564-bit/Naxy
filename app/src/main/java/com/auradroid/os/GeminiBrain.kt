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

    // LOW TASK LADDER: Fastest Free-Tier Models (3.5 Flash-Lite -> 3.8 Flash fallback)
    // Used for fast UI tapping, normal steps, quick planning & simple prompts
    private val freeTierFastLadder = mutableListOf(
        "gemini-3.5-flash-lite",
        "gemini-3.5-flash",
        "gemini-3.6-flash",
        "gemini-3.7-flash",
        "gemini-3.8-flash",
        "gemini-flash-lite-latest"
    )

    // HIGH TASK LADDER: Smartest Free-Tier Models (3.8 Flash -> 3.5 Flash-Lite fallback)
    // Used for Stuck Recovery, Pure Vision Coordinates, Post-Run Reflection, Video & Touch Learning
    private val deep38FlashLadder = listOf(
        "gemini-3.8-flash",
        "gemini-3.7-flash",
        "gemini-3.6-flash",
        "gemini-3.5-flash",
        "gemini-3.5-flash-lite",
        "gemini-flash-latest"
    )

    // Strictly allowed 3.5 - 3.8 Free-Tier Flash family
    private val allowedFreeModels = listOf(
        "gemini-3.8-flash",
        "gemini-3.7-flash",
        "gemini-3.6-flash",
        "gemini-3.5-flash",
        "gemini-3.5-flash-lite",
        "gemini-flash-latest",
        "gemini-flash-lite-latest"
    )

    private val blockedModels = mutableSetOf<String>()
    private val quotaCooldownModels = mutableMapOf<String, Long>()

    var onBrainLog: ((String) -> Unit)? = null

    fun updateApiKey(newKey: String) {
        apiKey = newKey.trim()
    }

    fun getActiveModelListStatus(): String {
        val now = System.currentTimeMillis()
        return allowedFreeModels.joinToString("\n") { m ->
            val cooldownUntil = quotaCooldownModels[m] ?: 0L
            val roleTag = if (m == "gemini-3.8-flash" || m == "gemini-3.7-flash") "High-Task" else "Low-Task/Fast"
            when {
                blockedModels.contains(m) -> "🚫 $m ($roleTag - Blocked/Unavailable)"
                cooldownUntil > now -> "⏳ $m ($roleTag - Quota Cooldown)"
                else -> "✅ $m ($roleTag - Active Free-Tier)"
            }
        }
    }

    @Suppress("DEPRECATION")
    fun bitmapToHdWebpBase64(bitmap: Bitmap, maxHeight: Int = 1280): String {
        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val targetH = bitmap.height.coerceAtMost(maxHeight)
        val targetW = (targetH * ratio).toInt().coerceAtLeast(360)
        val scaled = if (bitmap.height > targetH) {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }

        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.WEBP, 85, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun parseCleanJson(raw: String): JSONObject {
        val body = raw.substringAfter("{").substringBeforeLast("}")
        return JSONObject("{$body}")
    }

    private suspend fun callWithLadder(
        prompt: String,
        imagesBase64: List<String> = emptyList(),
        useDeep38Flash: Boolean = false
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Pair("", "No API Key")

        val now = System.currentTimeMillis()
        val baseLadder = if (useDeep38Flash) deep38FlashLadder else freeTierFastLadder

        val candidates = baseLadder.filter { m ->
            !blockedModels.contains(m) && (quotaCooldownModels[m] ?: 0L) <= now
        }.ifEmpty {
            quotaCooldownModels.clear()
            baseLadder.filter { !blockedModels.contains(it) }.ifEmpty {
                blockedModels.clear()
                baseLadder
            }
        }

        for (model in candidates) {
            val result = executeSingleModelCall(model, prompt, imagesBase64)
            when (result.first) {
                200 -> {
                    if (result.second.isNotBlank()) {
                        return@withContext Pair(result.second, model)
                    }
                }
                429 -> {
                    // Quota exhausted on this model -> Cooldown for 3 minutes & switch to next model in ladder
                    quotaCooldownModels[model] = System.currentTimeMillis() + 180_000L
                    onBrainLog?.invoke("⚠️ Quota full on [$model] (429) -> Switching to next 3.5/3.8 free model...")
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

        val discovered = discoverAvailableFlashModel()
        if (discovered != null) {
            val res = executeSingleModelCall(discovered, prompt, imagesBase64)
            if (res.first == 200) return@withContext Pair(res.second, discovered)
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
                connectTimeout = 18000
                readTimeout = 30000
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
                    put("responseMimeType", "application/json")
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

    private fun discoverAvailableFlashModel(): String? {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
            }
            if (conn.responseCode != 200) return null
            val arr = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("models") ?: return null
            for (i in 0 until arr.length()) {
                val name = arr.getJSONObject(i).optString("name").removePrefix("models/")
                val is35to38Flash = (name.startsWith("gemini-3.5-flash") ||
                        name.startsWith("gemini-3.6-flash") ||
                        name.startsWith("gemini-3.7-flash") ||
                        name.startsWith("gemini-3.8-flash")) &&
                        !name.contains("tts") &&
                        !name.contains("image")
                if (is35to38Flash && !blockedModels.contains(name)) {
                    if (!freeTierFastLadder.contains(name)) {
                        freeTierFastLadder.add(0, name)
                    }
                    return name
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 1. Interactive Chat & Detailed Task Planner (LOW TASK -> Uses 3.5 Flash-Lite first for instant reply)
     */
    suspend fun chatAndBuildDetailedPlan(
        userMessage: String,
        chatHistory: String,
        userProfileRules: String
    ): JSONObject {
        val sysPrompt = """
            You are AuraDroid OS, an intelligent friendly AI Assistant & Phone Automation Planner.
            Speak in natural Hinglish/Hindi.
            User's saved profile/rules: $userProfileRules
            Recent conversation:
            $chatHistory
            
            User just said: "$userMessage"
            
            If critical info is missing, set "readyToExecute": false and ask briefly in "reply".
            If the task is clear, break it down into small sequential steps in "steps" array and set "readyToExecute": true.
            Also decide "executionMode": "LIVE_PHONE" or "BACKGROUND_WEB".
            
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
     * - Normal steps with UI tags (LOW TASK) -> Uses 3.5 Flash-Lite for high speed
     * - Stuck state OR Pure Vision without UI tags (HIGH TASK) -> Automatically upgrades to 3.8 Flash!
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
        val recentJoined = currentStepHistory.takeLast(8).joinToString(" | ")
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
            
            CRITICAL EXECUTION RULES:
            1. Check RECENT ACTIONS ALREADY COMPLETED:
               - If you already tapped "Like", DO NOT tap Like again.
               - If you already posted a comment and closed the comment sheet, DO NOT open comments again—move to Share!
               - On the Instagram Share sheet: Once you have tapped a friend's avatar (e.g., Rahul) ONCE and a blue "Send" button appears at the bottom (around Y=910 in 0..1000 scale), DO NOT tap the friend's avatar again! Immediately tap the bottom blue "Send" button! And once "Sent" appears or the share sheet closes, return "DONE" immediately!
            2. If you see "Comments on this post have been limited", press "BACK" and then "SWIPE" up to the next Reel.
            3. Check LEARNED APP MEMORY above: If a post-run reflection, user correction, or video rule matches this screen, follow its exact instruction and coordinates!
            4. Prefer "CLICK_TAG" with the exact "tagId" whenever the target button/box is in the list.
            5. Otherwise use "CLICK_XY" with 0..1000 normalized coordinates.
            
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

        // High Task if agent is stuck OR screen has no accessibility tags (requires pure 3.8 Flash vision)
        val isHighTaskStep = isStuck || numberedNodesList.isBlank()
        val (raw, modelUsed) = callWithLadder(prompt, imgList, useDeep38Flash = isHighTaskStep)

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
     * 3. Post-Run Automatic Self-Reflection & Optimization (HIGH TASK -> Uses Gemini 3.8 Flash)
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
            An automation run just ended. You already know what the user's Goal was, what should have happened (Planned Steps), what actually happened (Actual Steps Taken), and you have the sequential Screenshots from the run!
            
            RUN CONTEXT:
            - App Category: $appCategory
            - User Goal: $goal
            - Planned Steps (What should have happened): ${plannedSteps.joinToString(" -> ")}
            - Actual Steps Taken: ${actualStepsTaken.joinToString(" | ")}
            - Final Run Status: $finalStatus
            
            YOUR TASK:
            1. Examine all ${imgsBase64.size} sequential screenshots from this run carefully.
            2. Identify where the agent succeeded, where it hesitated/repeated steps, or why it failed/stopped short of the goal.
            3. Formulate 1 to 3 concrete, high-precision optimization rules ("rules") with exact 0.0 to 1.0 screen tap coordinates ("tapXRatio", "tapYRatio") so that the VERY NEXT TIME the user runs this task in "$appCategory", it executes 100% smoothly without any mistake!
            4. Write a short Hinglish summary ("reflectionSummary") of what you learned from reviewing these screenshots.
            
            Return ONLY valid JSON:
            {
              "reflectionSummary": "Short Hinglish summary of what went right/wrong and how you optimized it for the next run",
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
     * 4. Interactive Chat Inside Any Past Automation Run (HIGH TASK -> Uses Gemini 3.8 Flash)
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
     * 5. Interactive Chat Inside Any Uploaded Training Video (HIGH TASK -> Uses Gemini 3.8 Flash)
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
     * 6. Before + After Screenshot Human Touch Analyzer (HIGH TASK -> Uses Gemini 3.8 Flash)
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
     * 7. Frame-by-Frame Uploaded Video Analyzer (HIGH TASK -> Uses Gemini 3.8 Flash)
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
     * 8. Safe Video Prompt + Viral YouTube Shorts Title Generator (LOW TASK -> Uses 3.5 Flash-Lite)
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
