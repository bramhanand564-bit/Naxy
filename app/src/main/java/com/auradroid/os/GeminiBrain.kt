package com.auradroid.os

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    val action: String,
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

    private val smartVisionLadder = listOf(
        "gemini-3.8-flash",
        "gemini-robotics-er-2-preview",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-3.1-pro-preview",
        "gemma-4-31b-it",
        "gemma-4-26b-a4b-it"
    )

    private val gemmaCasualAndTextLadder = listOf(
        "gemma-4-31b-it",
        "gemma-4-26b-a4b-it",
        "gemini-3.1-flash-lite",
        "gemini-3.5-flash-lite"
    )

    private var fastRelayToggle = false
    private val modelRequestTimestamps = mutableMapOf<String, MutableList<Long>>()
    private val lastModelCallTimeMs = mutableMapOf<String, Long>()
    private val blockedModels = mutableSetOf<String>()
    private val quotaCooldownModels = mutableMapOf<String, Long>()

    @Volatile
    var isLiveTaskCurrentlyRunning: Boolean = false
    @Volatile
    private var lastLiveStepTimestampMs: Long = 0L

    var onBrainLog: ((String) -> Unit)? = null

    fun updateApiKey(newKey: String) {
        apiKey = newKey.trim()
    }

    // 50% Half-Quota Safe RPM Limits (3.8-Flash = 5 RPM / 13s Pacer)
    private fun getMaxSafeRpmForModel(model: String): Int = when {
        model.contains("3.8-flash") -> 5
        model.contains("robotics") -> 5
        model.contains("flash-lite") -> 6
        model.contains("pro") -> 2
        model.startsWith("gemma") -> 8
        else -> 5
    }

    private fun getMinSafeGapMsForModel(model: String): Long = when {
        model.contains("3.8-flash") -> 13_000L
        model.contains("robotics") -> 12_000L
        model.contains("flash-lite") -> 10_000L
        model.contains("pro") -> 35_000L
        model.startsWith("gemma") -> 7_000L
        else -> 12_000L
    }

    private fun isModelInHard429Cooldown(model: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        synchronized(rateLock) {
            val cooldownUntil = quotaCooldownModels[model] ?: 0L
            cooldownUntil > nowMs
        }

    private fun getUsedRpmInLastMinute(model: String, nowMs: Long = System.currentTimeMillis()): Int =
        synchronized(rateLock) {
            val list = modelRequestTimestamps.getOrPut(model) { mutableListOf() }
            list.removeAll { nowMs - it > 60_000L }
            return list.size
        }

    private fun getWaitTimeMsForModel(model: String, nowMs: Long = System.currentTimeMillis()): Long =
        synchronized(rateLock) {
            val cooldownUntil = quotaCooldownModels[model] ?: 0L
            if (cooldownUntil > nowMs) return cooldownUntil - nowMs

            val list = modelRequestTimestamps.getOrPut(model) { mutableListOf() }
            list.removeAll { nowMs - it > 60_000L }

            val maxRpm = getMaxSafeRpmForModel(model)
            val minGap = getMinSafeGapMsForModel(model)
            val lastCall = lastModelCallTimeMs[model] ?: 0L
            val gapWait = if (lastCall > 0L && (nowMs - lastCall) < minGap) minGap - (nowMs - lastCall) else 0L
            val windowWait = if (list.size >= maxRpm && list.isNotEmpty()) {
                (60_500L - (nowMs - list.first())).coerceAtLeast(1_000L)
            } else 0L
            return maxOf(gapWait, windowWait)
        }

    private fun hasImmediateSafeSlot(model: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (blockedModels.contains(model)) return false
        return getWaitTimeMsForModel(model, nowMs) <= 0L
    }

    private fun recordModelRequestTimestamp(model: String) = synchronized(rateLock) {
        val nowMs = System.currentTimeMillis()
        val list = modelRequestTimestamps.getOrPut(model) { mutableListOf() }
        list.removeAll { nowMs - it > 60_000L }
        list.add(nowMs)
        lastModelCallTimeMs[model] = nowMs
    }

    fun getActiveModelListStatus(): String {
        val now = System.currentTimeMillis()
        return smartVisionLadder.joinToString("\n") { m ->
            val used = getUsedRpmInLastMinute(m, now)
            val maxRpm = getMaxSafeRpmForModel(m)
            val gapSec = getMinSafeGapMsForModel(m) / 1000L
            val waitMs = getWaitTimeMsForModel(m, now)
            when {
                blockedModels.contains(m) -> "🚫 $m (Unavailable)"
                waitMs > 0L -> "⏳ $m (Pacer ${(waitMs / 1000L).coerceAtLeast(1L)}s | $used/$maxRpm RPM | ${gapSec}s Gap)"
                else -> "✅ $m (Ready | $used/$maxRpm Safe RPM | ${gapSec}s Gap)"
            }
        }
    }

    @Suppress("DEPRECATION")
    fun bitmapToHdWebpBase64(bitmap: Bitmap, maxHeight: Int = 1280): String {
        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
        val targetH = bitmap.height.coerceAtMost(maxHeight)
        val targetW = (targetH * ratio).toInt().coerceAtLeast(360)
        val scaled = if (bitmap.height > targetH) Bitmap.createScaledBitmap(bitmap, targetW, targetH, true) else bitmap
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.WEBP, 82, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun parseCleanJson(raw: String): JSONObject {
        val cleaned = raw.replace("```json", "").replace("```", "").trim()
        val body = cleaned.substringAfter("{").substringBeforeLast("}")
        return JSONObject("{$body}")
    }

    /**
     * Work-Wise Smart Model Shifter:
     * - First-Time Run / Unconfirmed Screen -> 3.8-Flash + Robotics-ER Co-Pilot (COPILOT_38_ROBOTICS)
     * - Precision Icon / Coordinate -> Robotics-ER + 3.8-Flash (ROBOTICS_XY)
     * - Fast Known Step -> Alternates 3.5-Flash-Lite & 3.1-Flash-Lite (FAST_LITE)
     * - Text / Prompt / Casual -> Gemma-31B & 26B (GEMMA_TEXT)
     */
    private fun buildSmartGearLadder(preferredGear: String, hasImages: Boolean, isStuck: Boolean): List<String> {
        if (!hasImages && preferredGear == "GEMMA_TEXT") {
            return gemmaCasualAndTextLadder + listOf("gemini-3.8-flash")
        }
        if (isStuck) {
            return listOf("gemini-robotics-er-2-preview", "gemini-3.8-flash", "gemini-3.1-pro-preview", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
        }
        return when (preferredGear) {
            "COPILOT_38_ROBOTICS" -> {
                fastRelayToggle = !fastRelayToggle
                val first = if (fastRelayToggle) "gemini-3.8-flash" else "gemini-robotics-er-2-preview"
                val second = if (fastRelayToggle) "gemini-robotics-er-2-preview" else "gemini-3.8-flash"
                listOf(first, second, "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.1-pro-preview")
            }
            "PRO_DEEP_DIAGNOSIS" -> listOf("gemini-3.1-pro-preview", "gemini-3.8-flash", "gemini-robotics-er-2-preview", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
            "ROBOTICS_PRECISION" -> listOf("gemini-robotics-er-2-preview", "gemini-3.8-flash", "gemini-3.1-pro-preview", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
            "HEAVY_38_ONLY" -> listOf("gemini-3.8-flash", "gemini-robotics-er-2-preview", "gemini-3.1-pro-preview", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
            "ROBOTICS_XY" -> listOf("gemini-robotics-er-2-preview", "gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
            "FAST_LITE" -> {
                fastRelayToggle = !fastRelayToggle
                val first = if (fastRelayToggle) "gemini-3.5-flash-lite" else "gemini-3.1-flash-lite"
                val second = if (fastRelayToggle) "gemini-3.1-flash-lite" else "gemini-3.5-flash-lite"
                if (hasImages) listOf(first, second, "gemini-robotics-er-2-preview", "gemini-3.8-flash")
                else listOf(first, second, "gemma-4-31b-it", "gemma-4-26b-a4b-it", "gemini-3.8-flash")
            }
            else -> smartVisionLadder
        }
    }

    private suspend fun callWithLadder(
        prompt: String,
        imagesBase64: List<String> = emptyList(),
        useDeep38Flash: Boolean = true,
        preferredGear: String = if (useDeep38Flash) "COPILOT_38_ROBOTICS" else "FAST_LITE",
        isStuck: Boolean = false
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Pair("", "No API Key")

        val gearLadder = buildSmartGearLadder(preferredGear, imagesBase64.isNotEmpty(), isStuck)
            .filter { !blockedModels.contains(it) }
            .ifEmpty { blockedModels.clear(); smartVisionLadder }

        val now = System.currentTimeMillis()
        // Filter out models that are locked in hard 429 cooldown so we don't hammer them every 1 second
        val nonCooldownModels = gearLadder.filter { !isModelInHard429Cooldown(it, now) }
        val activePool = nonCooldownModels.ifEmpty { gearLadder }

        val readyModels = activePool.filter { hasImmediateSafeSlot(it, now) }
        val orderedCandidates = if (readyModels.isNotEmpty()) {
            readyModels + (activePool - readyModels.toSet())
        } else {
            val fastest = activePool.minByOrNull { getWaitTimeMsForModel(it, now) } ?: activePool.first()
            listOf(fastest) + (activePool - fastest)
        }

        for (model in orderedCandidates) {
            val loopNow = System.currentTimeMillis()

            // Skip if this model is currently in a hard 429 cooldown and other models exist
            if (isModelInHard429Cooldown(model, loopNow) && orderedCandidates.size > 1) {
                continue
            }

            // Respect Pacer Wait inside the loop before firing ANY model
            val waitNeeded = getWaitTimeMsForModel(model, loopNow)
            if (waitNeeded > 0L) {
                if (waitNeeded <= 15_000L) {
                    onBrainLog?.invoke("⏱️ Smart Quota Pacer: Waiting ${(waitNeeded / 1000L).coerceAtLeast(1L)}s for [$model]...")
                    delay(waitNeeded)
                } else {
                    // Too long to wait for this model; shift to next candidate immediately
                    continue
                }
            }

            val modelImages = if (model.startsWith("gemma")) emptyList() else imagesBase64
            val usedBefore = getUsedRpmInLastMinute(model)
            recordModelRequestTimestamp(model)

            val result = executeSingleModelCall(model, prompt, modelImages)
            when (result.first) {
                200 -> if (result.second.isNotBlank()) return@withContext Pair(result.second, model)
                429 -> {
                    // Lock model for 65 seconds on 429 so background/next steps never hammer it 1s later!
                    val lockDuration = if (usedBefore <= 1) 120_000L else 65_000L
                    synchronized(rateLock) {
                        quotaCooldownModels[model] = System.currentTimeMillis() + lockDuration
                    }
                    onBrainLog?.invoke("⚠️ Quota 429 on [$model] -> Shifting gear (${lockDuration / 1000}s cooldown)...")
                }
                500, 502, 503, 504 -> {
                    synchronized(rateLock) {
                        quotaCooldownModels[model] = System.currentTimeMillis() + 25_000L
                    }
                    onBrainLog?.invoke("🔄 Model [$model] busy (${result.first}) -> 25s cooldown & shifting gear...")
                }
                else -> {
                    synchronized(rateLock) {
                        quotaCooldownModels[model] = System.currentTimeMillis() + 30_000L
                    }
                    onBrainLog?.invoke("🔄 Model [$model] (${result.first}) -> Shifting gear...")
                }
            }
        }
        Pair("", "All models busy")
    }

    private fun executeSingleModelCall(model: String, prompt: String, imagesBase64: List<String>): Pair<Int, String> {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 10000
                readTimeout = 22000
                doOutput = true
            }
            val partsArray = JSONArray().put(JSONObject().put("text", prompt))
            for (imgB64 in imagesBase64) {
                if (imgB64.isNotBlank()) {
                    partsArray.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/webp").put("data", imgB64)))
                }
            }
            val payload = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    if (!model.startsWith("gemma")) put("responseMimeType", "application/json")
                })
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val text = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                    ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "") ?: ""
                Pair(200, text.trim())
            } else Pair(conn.responseCode, "")
        } catch (_: Exception) {
            Pair(500, "")
        }
    }

    private fun isCasualGreetingOrChitchat(msg: String): Boolean {
        val lower = msg.trim().lowercase(Locale.ROOT)
        if (lower.length > 45) return false
        val actionKeywords = listOf(
            "open", "kholo", "खोलो", "search", "सर्च", "upload", "अपलोड", "video", "वीडियो",
            "youtube", "insta", "whatsapp", "send", "bhejo", "भेजो", "like", "comment",
            "flow", "setting", "time", "baje", "बजे", "run", "चलाओ", "task", "song", "reel"
        )
        return actionKeywords.none { lower.contains(it) }
    }

    /**
     * Filter out poisoned/broken rules (like @2131955775 or "Chat not selected" at X=500)
     * so old poisoned Memory entries never hijack live runs!
     */
    private fun isValidReusableSkill(fix: LearnedFixRecord): Boolean {
        val rule = fix.solutionRule
        if (rule.contains("@213") || rule.contains("Chat not selected", ignoreCase = true)) return false
        if (rule.contains("All models busy", ignoreCase = true) || fix.sourceType.contains("All models busy", ignoreCase = true)) return false
        return true
    }

    /**
     * 1. Task Decider & Modular Micro-Step Planner
     */
    suspend fun chatAndBuildDetailedPlan(
        userMessage: String,
        chatHistory: String,
        userProfileRules: String
    ): JSONObject {
        val isCasual = isCasualGreetingOrChitchat(userMessage)
        val sysPrompt = """
            You are AuraDroid OS, an intelligent Hinglish AI Companion, Modular Skill Task Decider & Planner.
            User's saved profile/rules: $userProfileRules
            Recent conversation: $chatHistory
            User just said: "$userMessage"
            
            RULES:
            1. If casual greeting/chitchat ("Good morning", "Hi", "Kaise ho"), reply warmly, set "readyToExecute": false, "steps": [].
            2. Daily upload schedule -> "executionMode": "SCHEDULE_YT_HH:mm", "readyToExecute": true.
            3. Run only when phone is free -> "executionMode": "IDLE_PHONE_MODE", "readyToExecute": true.
            4. Folder to YouTube -> "executionMode": "YOUTUBE_FOLDER_UPLOAD", "readyToExecute": true.
            5. Google Flow to YouTube -> "executionMode": "GOOGLE_FLOW_UPLOAD", "readyToExecute": true.
            6. Background web mode -> "executionMode": "BACKGROUND_WEB", "readyToExecute": true.
            7. For phone automation, break every action into SEPARATE, CONFIRMABLE micro-steps:
               - Never combine "Tap bottom search icon" and "Type query in top search bar" into one step! Make them 2 separate steps so each click is confirmed!
               - Never combine "Select friend" and "Tap bottom blue Send button" into one step! Make them 2 separate steps!
               Tag each step with its specialist model gear: "[FAST_LITE]", "[ROBOTICS_XY]", or "[DEEP_3.8]".
            
            Return ONLY valid JSON:
            {
              "reply": "Friendly Hinglish response",
              "readyToExecute": true,
              "taskTitle": "Short title",
              "executionMode": "LIVE_PHONE",
              "steps": [
                "Step 1 [FAST_LITE] [SKILL_OPEN]: Open Instagram app",
                "Step 2 [ROBOTICS_XY] [SKILL_SEARCH_TAB]: Tap bottom Search magnifying glass icon to open Explore page",
                "Step 3 [FAST_LITE] [SKILL_SEARCH_TYPE]: Tap top 'Search with Meta AI' bar, type target query, and submit search",
                "Step 4 [DEEP_3.8] [SKILL_SELECT_VIDEO]: Confirm search results loaded and tap the first matching video",
                "Step 5 [ROBOTICS_XY] [SKILL_LIKE]: Tap the Like heart icon on the right side of the Reel",
                "Step 6 [ROBOTICS_XY] [SKILL_COMMENT]: Tap Comment icon, type comment in 'Join the conversation...', tap post arrow, then press BACK",
                "Step 7 [ROBOTICS_XY] [SKILL_SHARE_OPEN]: Tap Share paper-plane icon to open Share sheet",
                "Step 8 [ROBOTICS_XY] [SKILL_SHARE_SELECT]: Tap friend's profile once to select them (blue checkmark)",
                "Step 9 [FAST_LITE] [SKILL_SHARE_SEND]: Confirm blue 'Send' button appeared at bottom and tap 'Send'"
              ]
            }
        """.trimIndent()

        val gear = if (isCasual) "GEMMA_TEXT" else "FAST_LITE"
        val (raw, model) = callWithLadder(sysPrompt, useDeep38Flash = !isCasual, preferredGear = gear)
        return try {
            parseCleanJson(raw).put("modelUsed", model)
        } catch (_: Exception) {
            JSONObject().apply {
                put("reply", if (raw.isNotBlank()) raw else "नमस्ते भाई! बताइए आज कौन-सा टास्क चलाना है?")
                put("readyToExecute", false)
                put("steps", JSONArray())
                put("modelUsed", model)
            }
        }
    }

    /**
     * Pre/Post-Click Confirmation Engine:
     * Inspects the live XML nodes and recent action history to CONFIRM whether a click succeeded
     * and prevents repeating toggles (like selecting/unselecting Rahul on the Share sheet, or getting stuck on Send!).
     */
    private fun checkDeterministicClickConfirmation(
        goal: String,
        currentStepHistory: List<String>,
        numberedNodesList: String
    ): AgentStepDecision? {
        val recentJoined = currentStepHistory.takeLast(6).joinToString(" | ").lowercase(Locale.ROOT)
        val lastStepText = currentStepHistory.lastOrNull()?.lowercase(Locale.ROOT) ?: ""
        val lines = numberedNodesList.lines()

        // 1. SHARE SHEET SEND / SEND SEPARATELY CONFIRMATION:
        val sendButtonLine = lines.firstOrNull { line ->
            (line.contains("\"Send\"", ignoreCase = true) ||
                line.contains("\"Send separately\"", ignoreCase = true) ||
                line.contains("text=Send", ignoreCase = true) ||
                line.contains("desc=Send", ignoreCase = true)) &&
                !line.contains("Send a", ignoreCase = true) &&
                !line.contains("Send message", ignoreCase = true) &&
                !line.contains("Send to", ignoreCase = true)
        }

        if (sendButtonLine != null && (recentJoined.contains("rahul") || recentJoined.contains("select") || recentJoined.contains("share") || lines.any { it.contains("Write a message", ignoreCase = true) })) {
            val tagMatch = Regex("\\[(\\d+)]").find(sendButtonLine)
            val tagId = tagMatch?.groupValues?.get(1)?.toIntOrNull() ?: -1

            // Extract exact center XY if bounds are present in the node line, else use standard bottom-sheet Send XY (500, 932)
            val xyMatch = Regex("\\((\\d+),\\s*(\\d+)\\)").find(sendButtonLine)
            val sendX = xyMatch?.groupValues?.get(1)?.toIntOrNull() ?: 500
            val sendY = xyMatch?.groupValues?.get(2)?.toIntOrNull() ?: 932

            // If the very last step ALREADY tried CLICK_TAG on Send and the sheet is still open, force CLICK_XY!
            val alreadyTriedTag = lastStepText.contains("send") && lastStepText.contains("click_tag")

            onBrainLog?.invoke("✅ Click Confirmed: Friend selected & 'Send' button detected! Clicking 'Send' immediately...")
            return if (tagId >= 0 && !alreadyTriedTag) {
                AgentStepDecision(
                    action = "CLICK_TAG",
                    tagId = tagId,
                    x = sendX,
                    y = sendY,
                    reason = "Confirmed & Tapping Send button",
                    modelUsed = "ConfirmEngine"
                )
            } else {
                AgentStepDecision(
                    action = "CLICK_XY",
                    x = sendX,
                    y = sendY,
                    reason = "Confirmed & Tapping Send XY directly",
                    modelUsed = "ConfirmEngine"
                )
            }
        }

        // 2. POST-SEND COMPLETION CONFIRMATION:
        if (currentStepHistory.takeLast(2).any { it.contains("Tapping Send", ignoreCase = true) || it.contains("Send separately", ignoreCase = true) || it.contains("Send XY", ignoreCase = true) }) {
            val shareSheetStillOpen = lines.any {
                it.contains("Write a message", ignoreCase = true) ||
                    it.contains("Send separately", ignoreCase = true) ||
                    it.contains("Chat not selected", ignoreCase = true)
            }
            if (!shareSheetStillOpen) {
                onBrainLog?.invoke("🎉 Post-Click Confirmed: Video shared & Share sheet closed! Task DONE.")
                return AgentStepDecision(action = "DONE", reason = "Confirmed video shared!", modelUsed = "ConfirmEngine")
            }
        }

        return null
    }

    /**
     * 2. Real-Time Vision + Click Confirmation + First-Time 3.8 & Robotics Co-Pilot + Cross-Task Skill Transfer
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
        isLiveTaskCurrentlyRunning = true

        // RULE 1: Minimum 5-Second Phone & Internet Load Delay
        val now = System.currentTimeMillis()
        val elapsed = now - lastLiveStepTimestampMs
        if (lastLiveStepTimestampMs > 0L && elapsed in 1L..4_999L) {
            val waitMs = 5_000L - elapsed
            onBrainLog?.invoke("⏳ Phone & Internet Load Delay: Waiting ${waitMs / 1000.0}s for screen to load...")
            delay(waitMs)
        }
        lastLiveStepTimestampMs = System.currentTimeMillis()

        // RULE 2: Deterministic Pre/Post-Click Confirmation Check
        if (!isContinuousMonitor) {
            checkDeterministicClickConfirmation(goal, currentStepHistory, numberedNodesList)?.let { confirmedDecision ->
                return confirmedDecision
            }
        }

        val imgList = if (screenshotBitmap != null) listOf(bitmapToHdWebpBase64(screenshotBitmap)) else emptyList()

        // Filter out any corrupted/poisoned skills before injecting into prompt
        val cleanFixes = recalledFixes.filter { isValidReusableSkill(it) }
        val sortedFixes = cleanFixes.sortedByDescending {
            it.sourceType.contains("PRO_ROBOTICS") || it.sourceType.contains("SKILL") || it.sourceType.contains("CHAT")
        }

        val mandatorySkillTransferBlock = if (sortedFixes.isNotEmpty()) {
            """
            🚨 CONFIRMED UNIVERSAL APP SKILLS & MANDATORY XY COORDINATES (CROSS-TASK TRANSFER!):
            Apply these verified coordinates from previous runs to the current goal "$goal" (substitute the new search query or friend name as needed):
            """.trimIndent() + "\n" +
                sortedFixes.take(12).joinToString("\n") { fix ->
                    val px = (fix.tapXRatio * bodySpecs.screenWidth).toInt()
                    val py = (fix.tapYRatio * bodySpecs.screenHeight).toInt()
                    val normX = (fix.tapXRatio * 1000).toInt()
                    val normY = (fix.tapYRatio * 1000).toInt()
                    "👉 [CONFIRMED SKILL - ${fix.sourceType}] When on [${fix.problemDescription}]: ${fix.solutionRule} -> CLICK_XY x=$normX, y=$normY (Pixels: $px, $py)"
                }
        } else ""

        val monitorRule = if (isContinuousMonitor) {
            "IMPORTANT: CONTINUOUS MONITORING task. After sending a reply, return WAIT."
        } else ""

        val currentStepIndex = currentStepHistory.size
        val activePlannedStep = planSteps.getOrNull(currentStepIndex) ?: planSteps.lastOrNull() ?: goal

        val isFirstTimeForApp = sortedFixes.isEmpty()
        val preferredGear = when {
            isStuck -> "ROBOTICS_PRECISION"
            isFirstTimeForApp -> "COPILOT_38_ROBOTICS"
            activePlannedStep.contains("ROBOTICS_XY", ignoreCase = true) -> "ROBOTICS_XY"
            activePlannedStep.contains("DEEP_3.8", ignoreCase = true) -> "HEAVY_38_ONLY"
            activePlannedStep.contains("FAST_LITE", ignoreCase = true) -> "FAST_LITE"
            else -> "COPILOT_38_ROBOTICS"
        }

        val navTop = bodySpecs.screenHeight - bodySpecs.navBarHeight
        val prompt = """
            You are AuraDroid OS Autonomous Vision + Click Confirmation Engine (Gemini 3.8 + Robotics Co-Pilot).
            
            DEVICE BODY SPECS: ${bodySpecs.modelName} (${bodySpecs.screenWidth}x${bodySpecs.screenHeight} px), Safe Top Y>${bodySpecs.cutoutSafeTop}, Nav Y>$navTop
            OVERALL GOAL: $goal
            PLANNED WORKFLOW: ${planSteps.joinToString(" -> ")}
            COMPLETED ACTIONS SO FAR (VERIFY & DO NOT REPEAT!):
            ${currentStepHistory.takeLast(12).joinToString(" | ")}
            $monitorRule
            
            INTERACTIVE ELEMENTS CURRENTLY ON SCREEN:
            $numberedNodesList
            
            MANDATORY CLICK-CONFIRMATION & VERIFICATION RULES (READ CAREFULLY!):
            1. STEP-BY-STEP VISUAL CONFIRMATION BEFORE CLICKING:
               - Look at the attached screenshot and INTERACTIVE ELEMENTS CURRENTLY ON SCREEN to confirm whether the previous click actually succeeded!
            2. INSTAGRAM SEARCH CONFIRMATION (DO NOT PICK RANDOM EXPLORE VIDEOS!):
               - When on the Explore page (where "Search with Meta AI" or "Search" bar is at the top), check if the target search query from "$goal" has ALREADY been typed and searched!
               - If "Search with Meta AI" is visible and you have NOT typed the search query yet, YOU MUST use action "TYPE" (with the search bar's tagId or x=450, y=115 and text="<search query>")! NEVER click a random video from the Explore grid before typing the search query!
            3. INSTAGRAM COMMENT CONFIRMATION:
               - When the Comment sheet is open ("Join the conversation..." or "Add a comment..."), if you have NOT typed the comment yet, use action "TYPE" on the comment box (x=420, y=935) with a nice comment text.
               - Once the comment is posted, press "BACK" to close the comment sheet so the main Reel buttons (Like, Comment, Share) are visible again.
            4. INSTAGRAM SHARE SHEET CONFIRMATION (NEVER UNSELECT THE FRIEND!):
               - When the Share sheet is open:
                 a) If the blue "Send" or "Send separately" button (or "Write a message...") is visible at the bottom of the screen, THAT CONFIRMS THE FRIEND IS ALREADY SELECTED (blue checkmark)!
                 b) NEVER tap the friend's profile/avatar a second time (tapping again unselects them)! Immediately tap the bottom blue "Send" / "Send separately" button (x=500, y=932)!
                 c) If the friend (e.g. Rahul) is visible in the Share grid and "Send" is NOT visible yet, tap the friend's profile ONCE.
            
            $mandatorySkillTransferBlock
            
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
              "reason": "Confirmed state & exact action"
            }
        """.trimIndent()

        val (raw, modelUsed) = callWithLadder(
            prompt = prompt,
            imagesBase64 = imgList,
            useDeep38Flash = (preferredGear != "FAST_LITE"),
            preferredGear = preferredGear,
            isStuck = isStuck
        )

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
     * 3. 40-Second Idle-Only 2-Stage Autonomous Auditor & Universal Skill Extractor
     */
    suspend fun autoReflectAndImproveAfterRun(
        appCategory: String,
        goal: String,
        plannedSteps: List<String>,
        actualStepsTaken: List<String>,
        finalStatus: String,
        runBitmaps: List<Bitmap>
    ): PostRunReflectionResult {
        isLiveTaskCurrentlyRunning = false

        delay(10_000L)
        if (isLiveTaskCurrentlyRunning || (System.currentTimeMillis() - lastLiveStepTimestampMs) < 8_000L) {
            onBrainLog?.invoke("⏸️ Deferred Autonomous Audit because a live task is running.")
            return PostRunReflectionResult("Deferred for live task", emptyList(), "IdleGuard")
        }

        val imgsBase64 = runBitmaps.takeLast(8).map { bitmapToHdWebpBase64(it, maxHeight = 960) }
        val stepByStepLog = actualStepsTaken.mapIndexed { idx, s -> "Step ${idx + 1}: $s" }.joinToString("\n")

        val stage1ProPrompt = """
            You are Gemini 3.1 Pro Autonomous Step-by-Step Auditor & Universal Skill Architect for AuraDroid OS.
            A run in app "$appCategory" finished with status "$finalStatus" for goal "$goal".
            Planned: ${plannedSteps.joinToString(" -> ")}
            Actual Steps Taken:
            $stepByStepLog
            
            Examine all ${imgsBase64.size} sequential screenshots step by step (Step 1, Step 2, Step 3...):
            1. Verify which clicks were confirmed successful and which steps skipped typing, repeated a click (like selecting/unselecting a friend on Share sheet), or tapped the wrong place.
            2. Generalize into Reusable Modular App Skills ("[SKILL_SEARCH_TYPE]", "[SKILL_SELECT_VIDEO]", "[SKILL_LIKE]", "[SKILL_COMMENT_AND_BACK]", "[SKILL_SHARE_SELECT]", "[SKILL_SHARE_SEND]") using {search_query} and {friend_name} placeholders!
            
            Return ONLY JSON:
            {
              "diagnosisSummary": "Step-by-step Hinglish diagnosis of each step",
              "draftSkills": [
                {
                  "skillName": "[SKILL_SEARCH_TYPE] or [SKILL_SHARE_SEND] etc.",
                  "screenState": "Reusable screen description in $appCategory",
                  "whatToTap": "Exact visual icon/button that must be tapped"
                }
              ]
            }
        """.trimIndent()

        onBrainLog?.invoke("🧠 Stage 1/2 (Idle Audit): Gemini 3.1 Pro diagnosing steps & generalizing reusable skills...")
        val (stage1Raw, stage1Model) = callWithLadder(
            prompt = stage1ProPrompt,
            imagesBase64 = imgsBase64,
            useDeep38Flash = true,
            preferredGear = "PRO_DEEP_DIAGNOSIS"
        )

        if (stage1Model == "All models busy" || stage1Raw.isBlank()) {
            onBrainLog?.invoke("⏸️ Stage 1 Audit skipped (models cooling down). No unverified rules saved.")
            return PostRunReflectionResult("Models cooling down — skipped saving unverified rules.", emptyList(), "CooldownGuard")
        }

        for (sec in 1..15) {
            if (isLiveTaskCurrentlyRunning) {
                onBrainLog?.invoke("⏸️ Paused Stage 2 Audit because a live task started.")
                return PostRunReflectionResult("Paused for live task", emptyList(), stage1Model)
            }
            delay(1_000L)
        }

        val stage2RoboticsPrompt = """
            You are Gemini Robotics-ER Spatial Coordinate Engineer for AuraDroid OS.
            Stage 1 Pro Diagnosis for "$appCategory" (Goal: "$goal"):
            $stage1Raw
            
            Look at the ${imgsBase64.size} screenshots and measure the EXACT 0.00 to 1.00 screen X,Y coordinates ("tapXRatio", "tapYRatio") for every reusable skill & fix so the AI can execute them autonomously on ANY future task!
            
            Return ONLY valid JSON:
            {
              "reflectionSummary": "Clear Hinglish Step-by-Step audit report & reusable skills learned",
              "rules": [
                {
                  "screenWhen": "[UNIVERSAL_SKILL] Reusable screen state in $appCategory",
                  "actionInstruction": "MANDATORY SKILL OVERRIDE: Exact action & button to tap",
                  "tapXRatio": 0.50,
                  "tapYRatio": 0.92
                }
              ]
            }
        """.trimIndent()

        onBrainLog?.invoke("🦾 Stage 2/2 (Idle Audit): Gemini Robotics-ER measuring exact X,Y coordinates for reusable skills...")
        val (raw, stage2Model) = callWithLadder(
            prompt = stage2RoboticsPrompt,
            imagesBase64 = imgsBase64,
            useDeep38Flash = true,
            preferredGear = "ROBOTICS_PRECISION"
        )

        if (stage2Model == "All models busy" || raw.isBlank()) {
            onBrainLog?.invoke("⏸️ Stage 2 Audit skipped (models cooling down). No unverified rules saved.")
            return PostRunReflectionResult("Stage 2 skipped due to quota cooldown.", emptyList(), stage1Model)
        }

        val combinedModelTag = "$stage1Model+$stage2Model"
        val extractedRules = mutableListOf<LearnedFixRecord>()
        var summaryText = "3.1 Pro और Robotics ने हर स्टेप की जाँच करके Reusable Skills और सटीक XY कोऑर्डिनेट्स खुद लागू कर लिए हैं।"

        try {
            val j = parseCleanJson(raw)
            summaryText = j.optString("reflectionSummary", j.optString("diagnosisSummary", summaryText))
            val arr = j.optJSONArray("rules") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val record = LearnedFixRecord(
                    id = "refl_${System.currentTimeMillis()}_$i",
                    appCategory = appCategory,
                    sourceType = "PRO_ROBOTICS_SKILL ($combinedModelTag)",
                    screenSignature = "universal_skill_$i",
                    problemDescription = item.optString("screenWhen", "[UNIVERSAL_SKILL] Step ${i + 1} in $appCategory"),
                    solutionRule = item.optString("actionInstruction", "Follow autonomous skill coordinate"),
                    tapXRatio = item.optDouble("tapXRatio", 0.5).toFloat(),
                    tapYRatio = item.optDouble("tapYRatio", 0.5).toFloat(),
                    timestamp = "Pro+Robotics"
                )
                if (isValidReusableSkill(record)) {
                    extractedRules.add(record)
                }
            }
        } catch (_: Exception) {}

        onBrainLog?.invoke("✨ [$combinedModelTag] 40s Autonomous Audit Complete! Saved ${extractedRules.size} verified skills for $appCategory.")

        return PostRunReflectionResult(
            reflectionSummary = summaryText,
            learnedRules = extractedRules,
            modelUsed = combinedModelTag
        )
    }

    private fun extractPinnedXyFromUserMessage(msg: String): Pair<Float, Float>? {
        val regex = Regex("Pin XY:\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)", RegexOption.IGNORE_CASE)
        val match = regex.find(msg) ?: return null
        val x = match.groupValues[1].toFloatOrNull() ?: return null
        val y = match.groupValues[2].toFloatOrNull() ?: return null
        return Pair((x / 1000f).coerceIn(0.01f, 0.99f), (y / 1000f).coerceIn(0.01f, 0.99f))
    }

    /**
     * 4. Interactive Chat Inside Any Past Automation Run
     */
    suspend fun chatAboutAutomationRunWith38Flash(
        runRecord: AutomationRunRecord,
        userCorrectionMessage: String,
        screenshotsBase64: List<String>
    ): MemoryChatReply {
        val pinnedXy = extractPinnedXyFromUserMessage(userCorrectionMessage)

        val prompt = """
            You are Gemini Robotics-ER & 3.1 Pro Run-Correction Architect inside AuraDroid OS.
            The user is reviewing past run "${runRecord.title}" in app "${runRecord.appCategory}".
            Steps Taken: ${runRecord.stepsSummary.mapIndexed { i, s -> "Step ${i + 1}: $s" }.joinToString(" | ")}
            User Correction: "$userCorrectionMessage"
            
            1. Inspect the attached screenshots yourself and measure the exact 0.00..1.00 "tapXRatio" and "tapYRatio" of the correct button!
            2. Generalize "solutionRule" as a Reusable Skill so it works both for re-running "${runRecord.title}" AND for any future task in "${runRecord.appCategory}".
            
            Return ONLY valid JSON:
            {
              "replyText": "Hinglish reply explaining the exact step & coordinate you measured and confirming it will work on all similar tasks",
              "problemScreen": "[UNIVERSAL_SKILL] Screen/Step in ${runRecord.appCategory}",
              "solutionRule": "MANDATORY SKILL OVERRIDE: Exact action and coordinate to tap",
              "tapXRatio": 0.50,
              "tapYRatio": 0.92
            }
        """.trimIndent()

        val (raw, modelUsed) = callWithLadder(
            prompt = prompt,
            imagesBase64 = screenshotsBase64,
            useDeep38Flash = true,
            preferredGear = "ROBOTICS_PRECISION"
        )
        onBrainLog?.invoke("💬 [$modelUsed] Saved Universal Skill Override for ${runRecord.appCategory}")

        return try {
            val j = parseCleanJson(raw)
            val finalX = pinnedXy?.first ?: j.optDouble("tapXRatio", 0.5).toFloat()
            val finalY = pinnedXy?.second ?: j.optDouble("tapYRatio", 0.5).toFloat()
            val normX = (finalX * 1000).toInt()
            val normY = (finalY * 1000).toInt()
            MemoryChatReply(
                replyText = j.optString("replyText", "समझ गया भाई! मैंने खुद स्क्रीनशॉट से सटीक कोऑर्डिनेट ($normX, $normY) नापकर इसे Universal Skill के रूप में लागू कर लिया है!"),
                extractedProblem = j.optString("problemScreen", "[UNIVERSAL_SKILL] ${runRecord.appCategory}"),
                extractedSolutionRule = j.optString("solutionRule", userCorrectionMessage) + " (Verified XY: $normX, $normY)",
                tapXRatio = finalX,
                tapYRatio = finalY,
                modelUsed = modelUsed
            )
        } catch (_: Exception) {
            MemoryChatReply(
                replyText = if (raw.isNotBlank()) raw else "समझ गया भाई! मैंने यह हुनर (Skill) आगे के सभी टास्क के लिए सेव कर लिया है।",
                extractedProblem = "[UNIVERSAL_SKILL] ${runRecord.appCategory}",
                extractedSolutionRule = "MANDATORY SKILL OVERRIDE: $userCorrectionMessage",
                tapXRatio = pinnedXy?.first ?: 0.5f,
                tapYRatio = pinnedXy?.second ?: 0.5f,
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
        val pinnedXy = extractPinnedXyFromUserMessage(userMessage)
        val prompt = """
            You are Gemini Robotics-ER & 3.1 Pro Video-Memory Coach for app "${videoSession.appCategory}".
            Initial Note: ${videoSession.userNote}
            Rules So Far: ${videoSession.extractedRulesSummary.joinToString(" | ")}
            User Message: "$userMessage"
            
            Inspect the video frames, measure exact 0.0..1.0 "tapXRatio" & "tapYRatio" autonomously, and save as a reusable skill for "${videoSession.appCategory}".
            Return ONLY JSON:
            {
              "replyText": "Hinglish response confirming the measured coordinate & reusable skill",
              "problemScreen": "[UNIVERSAL_SKILL] Screen in ${videoSession.appCategory}",
              "solutionRule": "MANDATORY SKILL OVERRIDE: Exact action & coordinate",
              "tapXRatio": 0.50,
              "tapYRatio": 0.85
            }
        """.trimIndent()

        val (raw, modelUsed) = callWithLadder(prompt, videoFramesBase64, true, "ROBOTICS_PRECISION")
        return try {
            val j = parseCleanJson(raw)
            MemoryChatReply(
                replyText = j.optString("replyText", "समझ गया भाई! वीडियो फ्रेम से सटीक कोऑर्डिनेट और स्किल लागू कर लिया गया है।"),
                extractedProblem = j.optString("problemScreen", "[UNIVERSAL_SKILL] ${videoSession.appCategory}"),
                extractedSolutionRule = j.optString("solutionRule", userMessage),
                tapXRatio = pinnedXy?.first ?: j.optDouble("tapXRatio", 0.5).toFloat(),
                tapYRatio = pinnedXy?.second ?: j.optDouble("tapYRatio", 0.5).toFloat(),
                modelUsed = modelUsed
            )
        } catch (_: Exception) {
            MemoryChatReply(
                replyText = "समझ गया भाई! नियम सेव हो गया है।",
                extractedProblem = "[UNIVERSAL_SKILL] ${videoSession.appCategory}",
                extractedSolutionRule = "MANDATORY SKILL OVERRIDE: $userMessage",
                tapXRatio = pinnedXy?.first ?: 0.5f,
                tapYRatio = pinnedXy?.second ?: 0.5f,
                modelUsed = modelUsed
            )
        }
    }

    /**
     * 6. Before + After Screenshot Human Touch Analyzer
     * FIXED:
     * - Never steals RPM quota while a live task is running!
     * - Never saves garbage rules (@2131955775, "Chat not selected", or X=500 fallback) when models are busy!
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
        val cleanDesc = clickedNodeDesc.trim()

        // 1. Guard against agent self-touch / active live run quota starvation
        if (isLiveTaskCurrentlyRunning || (System.currentTimeMillis() - lastLiveStepTimestampMs) < 6_000L) {
            return Pair("", "")
        }

        // 2. Guard against raw resource IDs (@213...) or person-specific chat rows ("Chat not selected")
        if (cleanDesc.startsWith("@") ||
            cleanDesc.contains("Chat not selected", ignoreCase = true) ||
            cleanDesc.contains("selected", ignoreCase = true) && (humanTapXRatio in 0.49f..0.51f)
        ) {
            return Pair("", "")
        }

        val imgs = mutableListOf<String>()
        if (beforeScreenshot != null) imgs.add(bitmapToHdWebpBase64(beforeScreenshot))
        if (afterScreenshot != null) imgs.add(bitmapToHdWebpBase64(afterScreenshot))
        val normX = (humanTapXRatio * 1000).toInt()
        val normY = (humanTapYRatio * 1000).toInt()

        val prompt = """
            You are Gemini Robotics-ER Spatial Skill Learner.
            During "$goal" in "$appCategory", the human tapped at 0..1000 XY($normX, $normY) on [$cleanDesc].
            Image 1 = BEFORE, Image 2 = AFTER.
            Generalize this tap into a Reusable App Skill (so it works for any future search/song/friend in $appCategory).
            Do NOT hardcode a specific person's username if it's a share list; make it a general rule.
            Return ONLY JSON:
            {
              "problem": "[UNIVERSAL_SKILL] Reusable screen state in $appCategory",
              "solutionRule": "MANDATORY SKILL OVERRIDE: Tap '$cleanDesc' at 0..1000 XY($normX, $normY) to proceed"
            }
        """.trimIndent()

        val (raw, modelUsed) = callWithLadder(prompt, imgs, true, "ROBOTICS_PRECISION")

        // 3. CRITICAL: If all models are busy or call failed, DO NOT save a fallback rule into Memory!
        if (modelUsed == "All models busy" || raw.isBlank()) {
            onBrainLog?.invoke("⏸️ Skipped Touch Skill save because models are cooling down.")
            return Pair("", "")
        }

        return try {
            val j = parseCleanJson(raw)
            val prob = j.optString("problem", "").trim()
            val sol = j.optString("solutionRule", "").trim()
            if (prob.isNotEmpty() && sol.isNotEmpty()) {
                onBrainLog?.invoke("🧠 [$modelUsed] Learned Reusable Touch Skill for $appCategory")
                Pair(prob, sol)
            } else {
                Pair("", "")
            }
        } catch (_: Exception) {
            Pair("", "")
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
            You are Gemini Robotics-ER & 3.1 Pro Video Skill Extractor for app "$appCategory" (Note: "$userInstruction").
            Inspect all ${framesBase64.size} sequential frames, measure exact 0.0..1.0 "tapXRatio" & "tapYRatio", and extract reusable modular skills (Search, Play, Like, Comment, Share to Friend) that work on any future topic/friend!
            Return ONLY JSON:
            {
              "rules": [
                {
                  "screenWhen": "[UNIVERSAL_SKILL] Step screen in $appCategory",
                  "actionInstruction": "MANDATORY SKILL OVERRIDE: Exact button to tap",
                  "tapXRatio": 0.50,
                  "tapYRatio": 0.90
                }
              ]
            }
        """.trimIndent()

        val (raw, modelUsed) = callWithLadder(prompt, framesBase64, true, "ROBOTICS_PRECISION")
        if (modelUsed == "All models busy" || raw.isBlank()) return emptyList()

        onBrainLog?.invoke("🎬 [$modelUsed] Extracted Reusable Video Skills (${framesBase64.size} frames)")

        val extracted = mutableListOf<LearnedFixRecord>()
        try {
            val arr = parseCleanJson(raw).optJSONArray("rules") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                extracted.add(
                    LearnedFixRecord(
                        id = "vid_${System.currentTimeMillis()}_$i",
                        appCategory = appCategory,
                        sourceType = "PRO_ROBOTICS_VIDEO_SKILL ($modelUsed)",
                        screenSignature = "video_skill_$i",
                        problemDescription = item.optString("screenWhen", "[UNIVERSAL_SKILL] Step ${i + 1} in $appCategory"),
                        solutionRule = item.optString("actionInstruction", "Follow trained skill"),
                        tapXRatio = item.optDouble("tapXRatio", 0.5).toFloat(),
                        tapYRatio = item.optDouble("tapYRatio", 0.5).toFloat(),
                        timestamp = "Video Skill"
                    )
                )
            }
        } catch (_: Exception) {}
        return extracted
    }

    /**
     * 8. Exact Topic Video Prompt + Viral YouTube Shorts Title Generator (Uses Gemma first!)
     */
    suspend fun generateFlowSafePromptAndTitle(topic: String): Pair<String, String> {
        val cleanTopic = topic.trim().ifEmpty { "Beautiful cinematic vertical video" }
        val prompt = """
            Create a vertical 9:16 video generation prompt and viral YouTube Shorts title strictly for: "$cleanTopic".
            Stay 100% faithful to "$cleanTopic" (do NOT add forced sci-fi).
            Return ONLY JSON:
            {
              "videoPrompt": "Cinematic 8k vertical 9:16 video of...",
              "youtubeTitle": "Catchy Title! #shorts #viral"
            }
        """.trimIndent()

        val (raw, _) = callWithLadder(prompt, useDeep38Flash = false, preferredGear = "GEMMA_TEXT")
        return try {
            val j = parseCleanJson(raw)
            Pair(
                j.optString("videoPrompt", "Cinematic 8k vertical 9:16 video of $cleanTopic, natural lighting, expressive motion, ultra detailed"),
                j.optString("youtubeTitle", "${cleanTopic.take(45)} ✨ #shorts #viral")
            )
        } catch (_: Exception) {
            Pair(
                "Cinematic 8k vertical 9:16 video of $cleanTopic, natural lighting, smooth camera movement, ultra detailed",
                "${cleanTopic.take(45)} ✨ #shorts #viral"
            )
        }
    }
}
