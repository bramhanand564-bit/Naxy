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
    val action: String,         // CLICK_TAG, CLICK_XY, LONG_PRESS_XY, TYPE, SWIPE, LAUNCH_APP, OPEN_SETTINGS, WAIT, ASK_USER, DONE
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

    // Free-Tier Fastest -> Smartest Model Ladder
    private val freeTierLadder = mutableListOf(
        "gemini-2.5-flash",
        "gemini-3.1-flash-lite",
        "gemini-3.5-flash-lite",
        "gemini-flash-lite-latest",
        "gemini-3.8-flash",
        "gemini-flash-latest",
        "gemini-3.1-pro-preview",
        "gemini-pro-latest",
        "gemini-robotics-er-2-preview"
    )

    // High-Thinking Pro Ladder (Used when AI is Stuck or Analyzing Human Fixes / Training Videos)
    private val highThinkingLadder = listOf(
        "gemini-3.8-flash",
        "gemini-3.1-pro-preview",
        "gemini-pro-latest",
        "gemini-robotics-er-2-preview",
        "gemini-2.5-flash"
    )

    // Automatically blocks models returning 404/403 so we never waste time on them again
    private val blockedModels = mutableSetOf<String>()

    fun updateApiKey(newKey: String) {
        apiKey = newKey.trim()
    }

    fun getActiveModelListStatus(): String {
        return freeTierLadder.joinToString("\n") { m ->
            if (blockedModels.contains(m)) "🚫 $m (Blocked/Unavailable)" else "✅ $m (Ready)"
        }
    }

    /**
     * Converts any Screenshot Bitmap into sharp HD WebP (85% quality) Base64
     */
    fun bitmapToHdWebpBase64(bitmap: Bitmap, maxHeight: Int = 1280): String {
        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val targetH = bitmap.height.coerceAtMost(maxHeight)
        val targetW = (targetH * ratio).toInt().coerceAtLeast(360)
        val scaled = if (bitmap.height > targetH) {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else bitmap

        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.WEBP, 85, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Core Multi-Model Executor: Tries models in sequence, auto-blocks dead/404 models,
     * and falls back to ListModels if needed.
     */
    private suspend fun callWithLadder(
        prompt: String,
        imagesBase64: List<String> = emptyList(),
        useHighThinkingFirst: Boolean = false
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Pair("", "No API Key")

        val ladder = if (useHighThinkingFirst) highThinkingLadder else freeTierLadder
        val candidates = ladder.filter { !blockedModels.contains(it) }.ifEmpty {
            blockedModels.clear()
            freeTierLadder
        }

        for (model in candidates) {
            val result = executeSingleModelCall(model, prompt, imagesBase64)
            if (result.first == 200 && result.second.isNotBlank()) {
                return@withContext Pair(result.second, model)
            } else if (result.first == 404 || result.first == 400) {
                blockedModels.add(model)
            }
        }

        // Dynamic ListModels Fallback if standard names shifted
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
                connectTimeout = 20000
                readTimeout = 35000
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
                    put("temperature", 0.2)
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
                    freeTierLadder.add(0, name)
                    return name
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 1. Interactive Chat & Detailed Task Planner (Discuss -> Breakdown into Small Sub-Tasks -> Execute)
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
            If critical info is missing (like which folder, URL, or friend's name), set "readyToExecute": false and ask briefly in "reply".
            If the task is clear, break it down into small, crystal-clear sequential steps in "steps" array and set "readyToExecute": true.
            Also decide "executionMode": "LIVE_PHONE" (for Instagram, WhatsApp, YouTube App, Phone Settings) or "BACKGROUND_WEB" (for websites, Google Flow, forms).
            
            Return ONLY valid JSON in this exact format:
            {
              "reply": "Friendly Hinglish response explaining your understanding",
              "readyToExecute": true,
              "taskTitle": "Short title of task",
              "executionMode": "LIVE_PHONE",
              "steps": [
                "Step 1: Open Instagram app directly",
                "Step 2: Tap Search tab and type Bhojpuri song",
                "Step 3: Open first video reel, tap Like heart, and share to Rahul"
              ]
            }
        """.trimIndent()

        val (raw, model) = callWithLadder(sysPrompt, useHighThinkingFirst = false)
        return try {
            val clean = raw.substringAfter("{").substringBeforeLast("}")
            JSONObject("{$clean}").put("modelUsed", model)
        } catch (_: Exception) {
            JSONObject().apply {
                put("reply", if (raw.isNotBlank()) raw else "भाई, अपनी Gemini API Key एक बार Settings टैब में चेक कर लो।")
                put("readyToExecute", false)
                put("steps", JSONArray())
            }
        }
    }

    /**
     * 2. Real-Time Vision + Set-of-Mark Tagging + Device Body Calibration Step Engine
     * Notes: Only loads Learned Memory if `isStuck == true` so normal steps run blazing fast!
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

        val memoryBlock = if (isStuck && recalledFixes.isNotEmpty()) {
            "🚨 STUCK RECOVERY MEMORY (Past Human Fixes for this App):\n" +
                recalledFixes.joinToString("\n") {
                    "- When stuck on [${it.problemDescription}]:${it.solutionRule} (Tap ratio X=${it.tapXRatio}, Y=${it.tapYRatio})"
                }
        } else ""

        val monitorRule = if (isContinuousMonitor) {
            "IMPORTANT: This is a CONTINUOUS MONITORING / LIVE CHAT task. NEVER return DONE after sending 1 reply! After sending a message, return WAIT to watch for the next incoming message on the left side."
        } else ""

        val prompt = """
            You are AuraDroid OS Autonomous Vision Engine controlling an Android device.
            
            DEVICE BODY SPECS:
            - Phone Model: ${bodySpecs.modelName}
            - Exact Full Screen Resolution: ${bodySpecs.screenWidth}x${bodySpecs.screenHeight} px
            - Top Punch-Hole / Status Bar Unsafe Zone: Y = 0 to ${bodySpecs.cutoutSafeTop} px (NEVER tap inside this top status bar!)
            - Bottom Navigation Bar Zone: Y = ${bodySpecs.screenHeight - bodySpecs.navBarHeight} to${bodySpecs.screenHeight} px
            
            OVERALL GOAL: $goal
            PLANNED SUB-TASKS: ${planSteps.joinToString(" -> ")}
            RECENT ACTIONS TAKEN: ${currentStepHistory.takeLast(6).joinToString(" | ")}
            $monitorRule$memoryBlock
            
            INTERACTIVE ELEMENTS ON SCREEN (with [TAG_ID] and exact Center X,Y):
            $numberedNodesList
            
            INSTRUCTIONS:
            1. Look carefully at the attached HD screenshot and the interactive element list.
            2. Prefer "CLICK_TAG" with the exact "tagId" whenever the target button/box is in the list (this guarantees dead-center tap!).
            3. If an icon/element is visible in the screenshot but NOT in the tag list, use "CLICK_XY" with 0..1000 normalized coordinates (where 0,0 is top-left and 1000,1000 is bottom-right).
            4. If you need to open an app (like Instagram, YouTube, WhatsApp, Chrome) and it's not open yet, return "LAUNCH_APP" with "packageNameOrSetting" set to the app name (e.g. "instagram", "youtube", "whatsapp", "chrome").
            5. If you need to fix a phone setting (Wi-Fi, Bluetooth, Battery, Sound, Display, Accessibility), return "OPEN_SETTINGS" with "packageNameOrSetting" (e.g. "battery", "wifi", "sound", "display").
            6. To type text into a focused input or search bar, return "TYPE" with "tagId" (or x,y) and "text".
            7. To scroll or swipe Reels/Pages, return "SWIPE" with start x,y and endX,endY (in 0..1000 scale).
            
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
              "reason": "Brief 5-word status of what you are doing"
            }
        """.trimIndent()

        // If stuck, automatically escalate to High-Thinking 3.8 / Pro models!
        val (raw, modelUsed) = callWithLadder(prompt, imgList, useHighThinkingFirst = isStuck)
        return try {
            val clean = raw.substringAfter("{").substringBeforeLast("}")
            val j = JSONObject("{$clean}")
            AgentStepDecision(
                action = j.optString("action", "WAIT").uppercase(),
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
     * 3. High-Thinking Memory Analyzer (Triggered when Human fixes a stuck screen or uploads a Training Video)
     */
    suspend fun analyzeHumanFixWithProModel(
        appCategory: String,
        goal: String,
        stuckScreenshot: Bitmap?,
        humanTapXRatio: Float,
        humanTapYRatio: Float,
        clickedNodeDesc: String
    ): Pair<String, String> {
        val imgs = if (stuckScreenshot != null) listOf(bitmapToHdWebpBase64(stuckScreenshot)) else emptyList()
        val prompt = """
            You are the High-Thinking Memory Architect (Gemini 3.8 / Pro) for AuraDroid OS.
            While executing "$goal" inside "$appCategory", the agent got stuck or the human intervened.
            The human tapped at screen ratio X=${"%.2f".format(humanTapXRatio)}, Y=${"\%.2f".format(humanTapYRatio)} (Element: $clickedNodeDesc).
            
            Look at the screenshot and create a permanent rule for future runs in "$appCategory".
            Return ONLY JSON:
            {
              "problem": "Short description of what screen/obstacle this was",
              "solutionRule": "Exact instruction on where to tap or what to do next time on this screen"
            }
        """.trimIndent()

        val (raw, _) = callWithLadder(prompt, imgs, useHighThinkingFirst = true)
        return try {
            val j = JSONObject("{${raw.substringAfter("{").substringBeforeLast("}")}}")
            Pair(
                j.optString("problem", "Stuck screen in $appCategory"),
                j.optString("solutionRule", "Tap element '$clickedNodeDesc' at ratio ($humanTapXRatio,$humanTapYRatio)")
            )
        } catch (_: Exception) {
            Pair("User Intervention in $appCategory", "Tap '$clickedNodeDesc' at ratio ($humanTapXRatio,$humanTapYRatio)")
        }
    }

    suspend fun analyzeTrainingVideoFrames(
        appCategory: String,
        userInstruction: String,
        framesBase64: List<String>
    ): String {
        val prompt = """
            The user uploaded a screen-recording training video for "$appCategory" with instructions: "$userInstruction".
            Analyze these sequential video frames carefully and extract a step-by-step learned workflow rule so our agent can repeat this task anytime.
            Keep it concise and actionable.
        """.trimIndent()
        val (raw, model) = callWithLadder(prompt, framesBase64, useHighThinkingFirst = true)
        return if (raw.isNotBlank()) "[$model]$raw" else "Workflow saved for $appCategory."
    }

    /**
     * 4. Zero-Policy-Violation Safe Video Prompt + Viral YouTube Shorts Title Generator
     */
    suspend fun generateFlowSafePromptAndTitle(topic: String): Pair<String, String> {
        val sanitizedTopic = topic
            .replace(Regex("(?i)\\b(boy|girl|child|kid|minor|baby|skin)\\b"), "cybernetic explorer")

        val prompt = """
            Create:
            1. A policy-safe, cinematic 8-second vertical 9:16 AI video generation prompt about: "$sanitizedTopic".
               STRICT RULE: NEVER use words like boy, girl, child, kid, minor, teen, or skin. Use adult/robotic/cinematic subjects only.
            2. A viral YouTube Shorts title with emojis and #shorts #viral hashtags (under 75 chars).
            
            Return ONLY JSON:
            {
              "videoPrompt": "Cinematic 8k vertical video of...",
              "youtubeTitle": "Amazing Title! 🔥✨ #shorts #viral"
            }
        """.trimIndent()

        val (raw, _) = callWithLadder(prompt, useHighThinkingFirst = false)
        return try {
            val j = JSONObject("{${raw.substringAfter("{").substringBeforeLast("}")}}")
            Pair(
                j.optString("videoPrompt", "Cinematic 8k vertical sci-fi scene of $sanitizedTopic, dramatic lighting, ultra detailed"),
                j.optString("youtubeTitle", "$sanitizedTopic 🔥✨ #shorts #viral")
            )
        } catch (_: Exception) {
            Pair(
                "Cinematic 8k vertical sci-fi scene of $sanitizedTopic, unreal engine 5, hyper-realistic lighting",
                "$sanitizedTopic 🔥✨ #shorts #viral"
            )
        }
    }
}
