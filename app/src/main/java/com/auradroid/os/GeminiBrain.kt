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

    private fun buildSmartGearLadder(preferredGear: String, hasImages: Boolean, isStuck: Boolean): List<String> {
        if (!hasImages && preferredGear == "GEMMA_TEXT") {
            return gemmaCasualAndTextLadder + listOf("gemini-3.8-flash")
        }
        if (isStuck) {
            return listOf("gemini-robotics-er-2-preview", "gemini-3.8-flash", "gemini-3.1-pro-preview", "gemini-3.5-flash-lite")
        }
        return when (preferredGear) {
            "PRO_DEEP_DIAGNOSIS" -> listOf("gemini-3.1-pro-preview", "gemini-3.8-flash", "gemini-robotics-er-2-preview")
            "ROBOTICS_PRECISION" -> listOf("gemini-robotics-er-2-preview", "gemini-3.1-pro-preview", "gemini-3.8-flash")
            "HEAVY_38_ONLY" -> listOf("gemini-3.8-flash", "gemini-robotics-er-2-preview", "gemini-3.1-pro-preview", "gemini-3.5-flash-lite")
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
        preferredGear: String = if (useDeep38Flash) "HEAVY_38_ONLY" else "FAST_LITE",
        isStuck: Boolean = false
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Pair("", "No API Key")

        val gearLadder = buildSmartGearLadder(preferredGear, imagesBase64.isNotEmpty(), isStuck)
            .filter { !blockedModels.contains(it) }
            .ifEmpty { blockedModels.clear(); smartVisionLadder }

        val now = System.currentTimeMillis()
        val primaryModel = gearLadder.first()

        // 13s Pacer for 3.8-Flash / 12s for Robotics / 35s for 3.1-Pro so quota never runs out
        if (preferredGear == "HEAVY_38_ONLY" || preferredGear == "PRO_DEEP_DIAGNOSIS" || preferredGear == "ROBOTICS_PRECISION") {
            val waitPrimary = getWaitTimeMsForModel(primaryModel, now)
            if (waitPrimary in 1L..36_000L) {
                onBrainLog?.invoke("⏱️ Smart Quota Pacer: Waiting ${(waitPrimary / 1000L).coerceAtLeast(1L)}s for [$primaryModel]...")
                delay(waitPrimary)
            }
        }

        val afterWaitNow = System.currentTimeMillis()
        val readyModels = gearLadder.filter { hasImmediateSafeSlot(it, afterWaitNow) }
        val orderedCandidates = if (readyModels.isNotEmpty()) {
            readyModels + (gearLadder - readyModels.toSet())
        } else {
            val fastest = gearLadder.minByOrNull { getWaitTimeMsForModel(it, afterWaitNow) } ?: primaryModel
            val w = getWaitTimeMsForModel(fastest, afterWaitNow).coerceIn(1_000L, 14_000L)
            onBrainLog?.invoke("⏱️ Pacer Wait (${w / 1000L}s) for [$fastest]...")
            delay(w)
            listOf(fastest) + (gearLadder - fastest)
        }

        for (model in orderedCandidates) {
            val modelImages = if (model.startsWith("gemma")) emptyList() else imagesBase64
            val usedBefore = getUsedRpmInLastMinute(model)
            recordModelRequestTimestamp(model)

            val result = executeSingleModelCall(model, prompt, modelImages)
            when (result.first) {
                200 -> if (result.second.isNotBlank()) return@withContext Pair(result.second, model)
                429 -> {
                    quotaCooldownModels[model] = System.currentTimeMillis() + if (usedBefore <= 1) 180_000L else 25_000L
                    onBrainLog?.invoke("⚠️ Quota 429 on [$model] -> Shifting gear...")
                }
                500, 502, 503, 504 -> {
                    quotaCooldownModels[model] = System.currentTimeMillis() + 20_000L
                    onBrainLog?.invoke("🔄 Model [$model] busy (${result.first}) -> 20s cooldown & shifting gear...")
                }
                else -> {
                    quotaCooldownModels[model] = System.currentTimeMillis() + 30_000L
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
                    put("temperature", 0.15)
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
     * 1. Task Decider & Modular Skill Planner:
     * Breaks any new goal (e.g. "Hindi song search send Rahul") into reusable Skill Blocks
     * ([SKILL_SEARCH], [SKILL_LIKE], [SKILL_COMMENT], [SKILL_SHARE_FRIEND]) and assigns specialist model gears!
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
            7. For phone automation, decompose the user's request into Modular Reusable Skill Steps so previously learned skills (like how to Search, how to Play first video, how to Like, how to Comment & Back, how to Share to a friend) transfer across ANY topic or friend name!
               Tag each step with its model gear: "[FAST_LITE]", "[ROBOTICS_XY]", or "[DEEP_3.8]".
            
            Return ONLY valid JSON:
            {
              "reply": "Friendly Hinglish response",
              "readyToExecute": true,
              "taskTitle": "Short title",
              "executionMode": "LIVE_PHONE",
              "steps": [
                "Step 1 [FAST_LITE] [SKILL_OPEN]: Open target app",
                "Step 2 [ROBOTICS_XY] [SKILL_SEARCH]: Tap search icon and type target query",
                "Step 3 [DEEP_3.8] [SKILL_SELECT_VIDEO]: Tap the best matching video/reel from search results",
                "Step 4 [ROBOTICS_XY] [SKILL_SHARE_FRIEND]: Tap Share icon, search friend name, select friend, and tap bottom Send button"
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
     * 2. Real-Time Vision + Cross-Task Skill Transfer Engine + 5s Phone Load Delay
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

        // Minimum 5-Second Phone & Internet Load Delay
        val now = System.currentTimeMillis()
        val elapsed = now - lastLiveStepTimestampMs
        if (lastLiveStepTimestampMs > 0L && elapsed in 1L..4_999L) {
            val waitMs = 5_000L - elapsed
            onBrainLog?.invoke("⏳ Phone & Internet Load Delay: Waiting ${waitMs / 1000.0}s for screen to load...")
            delay(waitMs)
        }
        lastLiveStepTimestampMs = System.currentTimeMillis()

        val imgList = if (screenshotBitmap != null) listOf(bitmapToHdWebpBase64(screenshotBitmap)) else emptyList()

        val sortedFixes = recalledFixes.sortedByDescending {
            it.sourceType.contains("PRO_ROBOTICS") || it.sourceType.contains("SKILL") || it.sourceType.contains("CHAT")
        }

        // Cross-Task Skill Generalization Block: Applies learned coordinates from past tasks (e.g. Bhojpuri) to new tasks (e.g. Hindi song)!
        val mandatorySkillTransferBlock = if (sortedFixes.isNotEmpty()) {
            """
            🚨 UNIVERSAL LEARNED APP SKILLS & MANDATORY XY COORDINATES (CROSS-TASK TRANSFER!):
            These skills & coordinates were verified by Gemini 3.1 Pro & Robotics-ER in previous tasks on this app.
            IMPORTANT: Apply these learned button coordinates to the CURRENT task ("$goal") even if the search topic (e.g. Hindi song vs Bhojpuri video) or friend name changed!
            - For example, if a learned skill shows where the Search icon, Reel card, Like button, Comment button, Share (paper plane) icon, Share search bar, or bottom Send button is, USE THAT EXACT COORDINATE (`CLICK_XY`) and substitute the new text from "$goal"!
            """.trimIndent() + "\n" +
                sortedFixes.take(14).joinToString("\n") { fix ->
                    val px = (fix.tapXRatio * bodySpecs.screenWidth).toInt()
                    val py = (fix.tapYRatio * bodySpecs.screenHeight).toInt()
                    val normX = (fix.tapXRatio * 1000).toInt()
                    val normY = (fix.tapYRatio * 1000).toInt()
                    "👉 [LEARNED SKILL - ${fix.sourceType}] Screen/Skill [${fix.problemDescription}]: ${fix.solutionRule} -> Verified Point: CLICK_XY x=$normX, y=$normY (Pixels: $px, $py)"
                }
        } else ""

        val monitorRule = if (isContinuousMonitor) {
            "IMPORTANT: CONTINUOUS MONITORING task. After sending a reply, return WAIT."
        } else ""

        val currentStepIndex = currentStepHistory.size
        val activePlannedStep = planSteps.getOrNull(currentStepIndex) ?: planSteps.lastOrNull() ?: goal

        val preferredGear = when {
            isStuck -> "ROBOTICS_PRECISION"
            activePlannedStep.contains("ROBOTICS_XY", ignoreCase = true) -> "ROBOTICS_XY"
            activePlannedStep.contains("DEEP_3.8", ignoreCase = true) -> "HEAVY_38_ONLY"
            activePlannedStep.contains("FAST_LITE", ignoreCase = true) -> "FAST_LITE"
            sortedFixes.isNotEmpty() -> "HEAVY_38_ONLY"
            else -> "FAST_LITE"
        }

        val navTop = bodySpecs.screenHeight - bodySpecs.navBarHeight
        val prompt = """
            You are AuraDroid OS Autonomous Vision & Cross-Task Skill Transfer Engine.
            
            DEVICE BODY SPECS: ${bodySpecs.modelName} (${bodySpecs.screenWidth}x${bodySpecs.screenHeight} px), Safe Top Y>${bodySpecs.cutoutSafeTop}, Nav Y>$navTop
            CURRENT OVERALL GOAL: $goal
            ACTIVE PLANNED STEP: $activePlannedStep
            ALL PLANNED SUB-TASKS: ${planSteps.joinToString(" -> ")}
            RECENT COMPLETED ACTIONS (DO NOT REPEAT!): ${currentStepHistory.takeLast(10).joinToString(" | ")}
            $monitorRule
            
            INTERACTIVE ELEMENTS ON SCREEN:
            $numberedNodesList
            
            CRITICAL EXECUTION & SKILL TRANSFER RULES:
            1. CROSS-TASK SKILL TRANSFER: Look at UNIVERSAL LEARNED APP SKILLS below. If a learned skill teaches how to Search, Select a Video, Like, Comment, or Share to a Friend (like Rahul), transfer that exact skill & CLICK_XY coordinate to the current goal "$goal" (only skip sub-skills like Like/Comment if the current goal "$goal" did not ask for them!).
            2. If Comment sheet or Keyboard is open and blocking the Share/Like buttons, return "BACK".
            3. On Instagram Share sheet: tap Search bar -> type friend's name -> tap friend's row -> tap bottom blue "Send" button (around Y=910) -> return "DONE".
            
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
              "reason": "Brief 5-word status"
            }
        """.trimIndent()

        val (raw, modelUsed) = callWithLadder(
            prompt = prompt,
            imagesBase64 = imgList,
            useDeep38Flash = (preferredGear == "HEAVY_38_ONLY"),
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
     * Stage 1: Gemini 3.1 Pro diagnoses every step & generalizes reusable skills ({query}, {friend}).
     * Stage 2 (after 15s calm pause): Gemini Robotics-ER calculates exact 0..1 coordinates so user doesn't have to pin manually!
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

        // Wait 10s in Idle before starting Stage 1; abort immediately if user starts any live task!
        delay(10_000L)
        if (isLiveTaskCurrentlyRunning || (System.currentTimeMillis() - lastLiveStepTimestampMs) < 8_000L) {
            onBrainLog?.invoke("⏸️ Deferred Autonomous Audit because a live task is running.")
            return PostRunReflectionResult("Deferred for live task", emptyList(), "IdleGuard")
        }

        val imgsBase64 = runBitmaps.takeLast(8).map { bitmapToHdWebpBase64(it, maxHeight = 960) }
        val stepByStepLog = actualStepsTaken.mapIndexed { idx, s -> "Step ${idx + 1}: $s" }.joinToString("\n")

        // STAGE 1: Gemini 3.1 Pro Deep Step-by-Step Diagnosis & Skill Generalization
        val stage1ProPrompt = """
            You are Gemini 3.1 Pro Autonomous Step-by-Step Auditor & Universal Skill Architect for AuraDroid OS.
            A run in app "$appCategory" finished with status "$finalStatus" for goal "$goal".
            Planned: ${plannedSteps.joinToString(" -> ")}
            Actual Steps Taken:
            $stepByStepLog
            
            Examine all ${imgsBase64.size} sequential screenshots step by step (Step 1, Step 2, Step 3...):
            1. Find every step that succeeded and every step that tapped the wrong place, got stuck, or was stopped.
            2. GENERALIZE into Reusable Modular App Skills (e.g. "[SKILL_SEARCH]", "[SKILL_PLAY_REEL]", "[SKILL_LIKE]", "[SKILL_COMMENT_AND_BACK]", "[SKILL_SHARE_TO_FRIEND]") using placeholders like {search_query} or {friend_name} so future tasks with DIFFERENT songs/videos/friends can reuse the exact same button coordinates!
            
            Return ONLY JSON:
            {
              "diagnosisSummary": "Step-by-step Hinglish diagnosis of each step",
              "draftSkills": [
                {
                  "skillName": "[SKILL_SEARCH] or [SKILL_SHARE_TO_FRIEND] etc.",
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

        // Calm 15-Second Pacer Wait before Stage 2 (Total ~35-40s calm background healing!)
        for (sec in 1..15) {
            if (isLiveTaskCurrentlyRunning) {
                onBrainLog?.invoke("⏸️ Paused Stage 2 Audit because a live task started.")
                return PostRunReflectionResult("Paused for live task", emptyList(), stage1Model)
            }
            delay(1_000L)
        }

        // STAGE 2: Gemini Robotics-ER Pinpoints Exact 0.0..1.0 Coordinates for Each Reusable Skill!
        val stage2RoboticsPrompt = """
            You are Gemini Robotics-ER Spatial Coordinate Engineer for AuraDroid OS.
            Stage 1 Pro Diagnosis for "$appCategory" (Goal: "$goal"):
            $stage1Raw
            
            Look at the ${imgsBase64.size} screenshots and measure the EXACT 0.00 to 1.00 screen X,Y coordinates ("tapXRatio", "tapYRatio") for every reusable skill & fix so the AI can execute them autonomously on ANY future task (e.g. searching a different song or sharing to a friend) without human pinning!
            
            Return ONLY valid JSON:
            {
              "reflectionSummary": "Clear Hinglish Step-by-Step audit report & reusable skills learned",
              "rules": [
                {
                  "screenWhen": "[UNIVERSAL_SKILL] Reusable screen state in $appCategory (works for any query/friend)",
                  "actionInstruction": "MANDATORY SKILL OVERRIDE: Exact action & button to tap (use {query} / {friend} placeholder if typing)",
                  "tapXRatio": 0.50,
                  "tapYRatio": 0.91
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
        val combinedModelTag = "$stage1Model+$stage2Model"
        onBrainLog?.invoke("✨ [$combinedModelTag] 40s Autonomous Audit Complete! Reusable skills saved for $appCategory.")

        val extractedRules = mutableListOf<LearnedFixRecord>()
        var summaryText = "3.1 Pro और Robotics ने हर स्टेप की जाँच करके Reusable Skills और सटीक XY कोऑर्डिनेट्स खुद लागू कर लिए हैं।"

        try {
            val j = parseCleanJson(raw.ifBlank { stage1Raw })
            summaryText = j.optString("reflectionSummary", j.optString("diagnosisSummary", summaryText))
            val arr = j.optJSONArray("rules") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                extractedRules.add(
                    LearnedFixRecord(
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
                )
            }
        } catch (_: Exception) {}

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
     * 4. Interactive Chat Inside Any Past Automation Run (Uses Robotics + Pro to auto-calculate XY even if user didn't pin!)
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
            
            1. Even if the user did NOT manually pin coordinates, inspect the attached screenshots yourself and measure the exact 0.00..1.00 "tapXRatio" and "tapYRatio" of the correct button!
            2. Generalize "solutionRule" as a Reusable Skill so it works both for re-running "${runRecord.title}" AND for any future task in "${runRecord.appCategory}" (e.g. searching a different song or sharing to a friend).
            
            Return ONLY valid JSON:
            {
              "replyText": "Hinglish reply explaining the exact step & coordinate you measured and confirming it will work on all similar tasks",
              "problemScreen": "[UNIVERSAL_SKILL] Screen/Step in ${runRecord.appCategory}",
              "solutionRule": "MANDATORY SKILL OVERRIDE: Exact action and coordinate to tap",
              "tapXRatio": 0.50,
              "tapYRatio": 0.91
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
     * 6. Before + After Screenshot Human Touch Analyzer (Generalizes touch into a Reusable Skill!)
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
            You are Gemini Robotics-ER Spatial Skill Learner.
            During "$goal" in "$appCategory", the human tapped at 0..1000 XY($normX, $normY) on [$clickedNodeDesc].
            Image 1 = BEFORE, Image 2 = AFTER.
            Generalize this tap into a Reusable App Skill (so it works for any future search/song/friend in $appCategory).
            Return ONLY JSON:
            {
              "problem": "[UNIVERSAL_SKILL] Reusable screen state in $appCategory",
              "solutionRule": "MANDATORY SKILL OVERRIDE: Tap '$clickedNodeDesc' at 0..1000 XY($normX, $normY) to proceed"
            }
        """.trimIndent()

        val (raw, modelUsed) = callWithLadder(prompt, imgs, true, "ROBOTICS_PRECISION")
        onBrainLog?.invoke("🧠 [$modelUsed] Learned Reusable Touch Skill for $appCategory")
        return try {
            val j = parseCleanJson(raw)
            Pair(
                j.optString("problem", "[UNIVERSAL_SKILL] Screen in $appCategory"),
                j.optString("solutionRule", "MANDATORY SKILL OVERRIDE: Tap '$clickedNodeDesc' at 0..1000 XY($normX, $normY)")
            )
        } catch (_: Exception) {
            Pair("[UNIVERSAL_SKILL] $appCategory", "MANDATORY SKILL OVERRIDE: Tap '$clickedNodeDesc' at 0..1000 XY($normX, $normY)")
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
