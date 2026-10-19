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

class GeminiBrain(private var apiKey: String) {

    // 100% Free-Tier Fastest -> Smartest Model Ladder (Matches AI Studio Free Models)
    // Starts with ultra-fast Lite models to save quota, then steps up to 3.8 Flash & Robotics-ER-2!
    private val freeTierFastLadder = mutableListOf(
        "gemini-3.1-flash-lite",
        "gemini-3.5-flash-lite",
        "gemini-flash-lite-latest",
        "gemini-3.8-flash",
        "gemini-flash-latest",
        "gemini-robotics-er-2-preview",
        "gemma-4-31b-it",
        "gemma-4-26b-a4b-it",
        "gemini-3.1-pro-preview"
    )

    // Deep Analyzer Ladder for Frame-by-Frame Video Training & Before/After Touch Learning
    // Uses gemini-3.8-flash first (Fast + High-Intelligence Agent Model) before ever touching Pro!
    private val deepLearningLadder = listOf(
        "gemini-3.8-flash",
        "gemini-flash-latest",
        "gemini-robotics-er-2-preview",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-3.1-pro-preview"
    )

    // Models that returned 404/403 (Unavailable/Paid) or 429 (Quota Exhausted)
    private val blockedModels = mutableSetOf<String>()
    private val quotaCooldownModels = mutableMapOf<String, Long>()

    var onBrainLog: ((String) -> Unit)? = null

    fun updateApiKey(newKey: String) {
        apiKey = newKey.trim()
    }

    fun getActiveModelListStatus(): String {
        val now = System.currentTimeMillis()
        return freeTierFastLadder.joinToString("\n") { m ->
            val cooldownUntil = quotaCooldownModels[m] ?: 0L
            when {
                blockedModels.contains(m) -> "🚫 $m (Blocked - Not Available)"
                cooldownUntil > now -> "⏳ $m (Quota Cooldown - Skipped)"
                else -> "✅ $m (Active Free-Tier)"
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

    /**
     * Step-by-Step Free-Tier Ladder Executor:
     * Always tries the fastest low-quota model first. If a model fails or hits quota (429),
     * it automatically skips to the next free model and logs the exact transition.
     */
    private suspend fun callWithLadder(
        prompt: String,
        imagesBase64: List<String> = emptyList(),
        useDeepAnalyzer: Boolean = false
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Pair("", "No API Key")

        val now = System.currentTimeMillis()
        val baseLadder = if (useDeepAnalyzer) deepLearningLadder else freeTierFastLadder

        val candidates = baseLadder.filter { m ->
            !blockedModels.contains(m) && (quotaCooldownModels[m] ?: 0L) <= now
        }.ifEmpty {
            quotaCooldownModels.clear()
            baseLadder.filter { !blockedModels.contains(it) }.ifEmpty {
                blockedModels.clear()
                freeTierFastLadder
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
                    // Quota limit hit on this model -> put on 3-minute cooldown & move to next free model!
                    quotaCooldownModels[model] = System.currentTimeMillis() + 180_000L
                    onBrainLog?.invoke("⚠️ Quota full on [$model] (429) -> Auto-switching to next model...")
                }
                404, 403, 400 -> {
                    blockedModels.add(model)
                    onBrainLog?.invoke("🚫 Model [$model] unavailable (${result.first}) -> Blocked & skipping...")
                }
                else -> {
                    onBrainLog?.invoke("🔄 Model [$model] busy (${result.first}) -> Trying next model...")
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
                if (name.contains("flash") && !name.contains("tts") && !name.contains("image") && !blockedModels.contains(name)) {
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
     * 1. Interactive Chat & Detailed Task Planner
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
            
            Analyze if the user is asking a question, discussing an idea, or giving an automation task.
            If critical info is missing, set "readyToExecute": false and ask briefly in "reply".
            If the task is clear, break it down into small, crystal-clear sequential steps in "steps" array and set "readyToExecute": true.
            Also decide "executionMode": "LIVE_PHONE" (for Instagram, WhatsApp, YouTube App, Phone Settings) or "BACKGROUND_WEB" (for websites, Google Flow, forms).
            
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

        val (raw, model) = callWithLadder(sysPrompt, useDeepAnalyzer = false)
        return try {
            parseCleanJson(raw).put("modelUsed", model)
        } catch (_: Exception) {
            JSONObject().apply {
                put("reply", if (raw.isNotBlank()) raw else "Bhai, apni Gemini API Key ek baar Engine tab mein check kar lo.")
                put("readyToExecute", false)
                put("steps", JSONArray())
            }
        }
    }

    /**
     * 2. Real-Time Vision + Learned App Playbook Step Engine
     * CRITICAL UPGRADE: Always injects Learned Video & Human-Touch Fixes for this App from Step 1
     * so the Baymax Hand goes straight to the exact learned point!
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

        // Always include learned playbook rules for this app so AI never repeats past mistakes!
        val memoryBlock = if (recalledFixes.isNotEmpty()) {
            "🧠 LEARNED APP MEMORY & VIDEO-TRAINED COORDINATES (FOLLOW STRICTLY!):\n" +
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
            RECENT ACTIONS ALREADY COMPLETED (DO NOT REPEAT COMPLETED ACTIONS!):
            $recentJoined
            $monitorRule
            $memoryBlock
            
            INTERACTIVE ELEMENTS ON SCREEN (with [TAG_ID] and exact Center X,Y):
            $numberedNodesList
            
            CRITICAL EXECUTION RULES:
            1. Look at RECENT ACTIONS ALREADY COMPLETED: If you already tapped "Like" on the current video, DO NOT tap Like again (tapping Like twice unlikes it!). Move immediately to the next step (e.g., Comment or Share).
            2. If you opened a Comment sheet on Instagram and see "Comments on this post have been limited" or no comment input box:
               - First close the comment sheet (or press "BACK") and then "SWIPE" up (from Y=800 to Y=200) to move to the next Reel where comments are open!
            3. Check LEARNED APP MEMORY above: If a learned rule matches the current screen, use its exact TAG_ID or Learned 0..1000 XY coordinates!
            4. Prefer "CLICK_TAG" with the exact "tagId" whenever the target button/box is in the list (guarantees dead-center tap!).
            5. If an icon/element is visible in the screenshot but NOT in the tag list, use "CLICK_XY" with 0..1000 normalized coordinates.
            6. To type text into a focused input or search bar, return "TYPE" with "tagId" (or x,y) and "text".
            7. To scroll or swipe to the next Reel/Page, return "SWIPE" with x=500, y=800, endX=500, endY=200.
            8. To close a popup/sheet, return "BACK".
            
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

        // Normal steps use fastest free model first; if stuck, escalates to 3.8-flash deep analyzer
        val (raw, modelUsed) = callWithLadder(prompt, imgList, useDeepAnalyzer = isStuck)
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
     * 3. Before + After Screenshot Human Touch Analyzer (Powered by Gemini 3.8 Flash)
     * Compares Screenshot 1 (Before Human Tap) and Screenshot 2 (After Human Tap) + Exact Tap Point
     * to learn why the AI was wrong and how the user fixed it!
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
            During the task "$goal" inside app "$appCategory", the AI agent was stuck or making a mistake, so the Human User manually tapped the screen!
            
            - Image 1 (if present): BEFORE Screenshot (the screen where AI was stuck/confused).
            - Human Touch Action: Tapped at 0..1000 coordinates X=$normX, Y=$normY on UI element [$clickedNodeDesc].
            - Image 2 (if present): AFTER Screenshot (the new screen that opened right after the human's tap).
            
            Compare Image 1 and Image 2 carefully:
            1. Identify what screen/situation Image 1 was ("problem").
            2. Write a crystal-clear actionable instruction ("solutionRule") so that next time the agent sees Image 1, it immediately taps [$clickedNodeDesc] at 0..1000 coordinates ($normX, $normY) to reach Image 2!
            
            Return ONLY valid JSON:
            {
              "problem": "Exact screen state in $appCategory before tap",
              "solutionRule": "Tap '$clickedNodeDesc' at 0..1000 XY($normX, $normY) to proceed"
            }
        """.trimIndent()

        val (raw, modelUsed) = callWithLadder(prompt, imgs, useDeepAnalyzer = true)
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
     * 4. Frame-by-Frame Uploaded Video Error & Coordinate Extractor (Powered by Gemini 3.8 Flash)
     * Inspects up to 12 sequential video frames, spots where the agent went wrong or where the user tapped,
     * and returns structured step-by-step coordinate rules for that app!
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
            1. Detect every mistake the AI made in the video (for example: tapping the wrong place, getting stuck on 'Comments on this post have been limited', or pressing Back when keyboard wasn't open).
            2. Detect every correct button/location that needs to be tapped on each screen (estimate its 0.0 to 1.0 screen X,Y ratio from the frame!).
            3. Extract 3 to 6 concrete step rules for "$appCategory" so that when the user runs this task again, the Baymax Hand taps the 100% exact coordinates on every screen.
            
            Return ONLY valid JSON with a "rules" array:
            {
              "rules": [
                {
                  "screenWhen": "Instagram Search tab open",
                  "actionInstruction": "Tap top search bar 'Search with Meta AI' and type query",
                  "tapXRatio": 0.45,
                  "tapYRatio": 0.07
                },
                {
                  "screenWhen": "Instagram Reel Comment sheet says 'Comments on this post have been limited'",
                  "actionInstruction": "Press BACK to close comment sheet, then SWIPE up (500,800 -> 500,200) to open next Reel",
                  "tapXRatio": 0.50,
                  "tapYRatio": 0.50
                }
              ]
            }
        """.trimIndent()

        val (raw, modelUsed) = callWithLadder(prompt, framesBase64, useDeepAnalyzer = true)
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
     * 5. Zero-Policy-Violation Safe Video Prompt + Viral YouTube Shorts Title Generator
     */
    suspend fun generateFlowSafePromptAndTitle(topic: String): Pair<String, String> {
        val safeRegex = Regex("(?i)\\b(boy|girl|child|kid|minor|baby|skin)\\b")
        val sanitizedTopic = topic.replace(safeRegex, "cybernetic explorer")

        val prompt = """
            Create:
            1. A policy-safe, cinematic 8-second vertical 9:16 AI video generation prompt about: "$sanitizedTopic".
               STRICT RULE: NEVER use words like boy, girl, child, kid, minor, teen, or skin.
            2. A viral YouTube Shorts title with emojis and #shorts #viral hashtags (under 75 chars).
            
            Return ONLY JSON:
            {
              "videoPrompt": "Cinematic 8k vertical video of...",
              "youtubeTitle": "Amazing Title! #shorts #viral"
            }
        """.trimIndent()

        val (raw, _) = callWithLadder(prompt, useDeepAnalyzer = false)
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
