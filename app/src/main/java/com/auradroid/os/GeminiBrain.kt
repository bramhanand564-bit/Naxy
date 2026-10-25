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
    val modelUsed: String = "",
    val isFinalAction: Boolean = false,
    val expectedNextScreen: String = ""
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

data class FlowVideoMetadata(
    val videoPrompt: String,
    val viralTitle: String,
    val viralDescription: String
)

data class ApiCallTelemetryRecord(
    val callNumber: Int,
    val purpose: String,
    val model: String,
    val httpCode: Int,
    val durationMs: Long,
    val pacerWaitMs: Long,
    val gapSincePrevMs: Long,
    val tokensUsed: Int,
    val timestampMs: Long
)

class GeminiBrain(private var apiKey: String) {

    private val rateLock = Any()

    // Supports FreeLLMAPI (localhost:3001) and 1 Official Google Gemini API Key (100% Google ToS Safe)
    @Volatile
    private var freeLlmBaseUrl: String = "http://127.0.0.1:3001"
    @Volatile
    private var freeLlmApiKey: String = ""
    @Volatile
    private var googleGeminiKey: String = ""

    // Proven High-Speed Official Trio: 3.5-flash-lite (#1 fastest & highest quota), 3.8-flash (#2), robotics (#3)
    private val smartVisionLadder = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.8-flash",
        "gemini-robotics-er-2-preview"
    )

    private val textAndMetadataLadder = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.8-flash"
    )

    private val modelRequestTimestamps = mutableMapOf<String, MutableList<Long>>()
    private val lastModelCallTimeMs = mutableMapOf<String, Long>()
    private val blockedModels = mutableSetOf<String>()
    private val quotaCooldownModels = mutableMapOf<String, Long>()

    // 3-Minute Cooldown Lock (180,000 ms) when any model errors or is overloaded
    private val threeMinuteCooldownLockMs = 180_000L

    // Minimum safe inter-call gap so two calls NEVER hit Google with 0.01s gap
    private val minGlobalInterCallGapMs = 2_200L

    // Detailed API Telemetry & Timing State
    @Volatile
    var detailedApiLoggingEnabled: Boolean = true
    private var totalApiCallsSession = 0
    private var totalSuccessCallsSession = 0
    private var totalQuota429HitsSession = 0
    private var totalTokensUsedSession = 0L
    private var totalApiTimeMsSession = 0L
    private var lastGlobalApiCallFinishMs = 0L
    private val recentApiCallHistory = mutableListOf<ApiCallTelemetryRecord>()
    private val perModelCallCount = mutableMapOf<String, Int>()
    private val perModelTokensUsed = mutableMapOf<String, Long>()
    private val perModelTotalTimeMs = mutableMapOf<String, Long>()

    @Volatile
    var isLiveTaskCurrentlyRunning: Boolean = false
    @Volatile
    private var lastLiveStepTimestampMs: Long = 0L

    var onBrainLog: ((String) -> Unit)? = null

    init {
        parseAndApplyKeyConfig(apiKey)
    }

    fun updateApiKey(newKey: String) {
        apiKey = newKey.trim()
        parseAndApplyKeyConfig(apiKey)
    }

    private fun parseAndApplyKeyConfig(rawInput: String) {
        val raw = rawInput.trim()
        var detectedFreeKey = ""
        var detectedGoogleKey = ""
        var detectedUrl = "http://127.0.0.1:3001"

        val urlRegex = Regex("(https?://[^\\s\"'|,;]+)", RegexOption.IGNORE_CASE)
        urlRegex.find(raw)?.groupValues?.get(1)?.let { foundUrl ->
            detectedUrl = foundUrl
                .replace("localhost", "127.0.0.1")
                .trimEnd('/')
                .removeSuffix("/v1")
                .removeSuffix("/agents")
                .removeSuffix("/models/chat")
        }

        val freeKeyRegex = Regex("((?:freellma|sk_omniroute|sk-)[a-zA-Z0-9_\\-]+)", RegexOption.IGNORE_CASE)
        freeKeyRegex.find(raw)?.groupValues?.get(1)?.let {
            detectedFreeKey = it.trim()
        }

        val googleKeyRegex = Regex("(AIza[a-zA-Z0-9_\\-]{20,})")
        googleKeyRegex.find(raw)?.groupValues?.get(1)?.let {
            detectedGoogleKey = it.trim()
        }

        if (detectedFreeKey.isEmpty() && detectedGoogleKey.isEmpty() && raw.isNotBlank()) {
            if (raw.contains("3001") || raw.contains("20128") || raw.contains("freellm", ignoreCase = true) || !raw.startsWith("AIza")) {
                val cleanedKey = raw.substringAfter("API_KEY=", raw).lineSequence().firstOrNull()?.trim() ?: raw
                detectedFreeKey = cleanedKey
            } else {
                detectedGoogleKey = raw
            }
        }

        freeLlmBaseUrl = detectedUrl
        freeLlmApiKey = detectedFreeKey
        googleGeminiKey = detectedGoogleKey
    }

    private fun isFreeLlmConfigured(): Boolean = freeLlmApiKey.isNotBlank()
    private fun isGoogleGeminiConfigured(): Boolean = googleGeminiKey.isNotBlank()

    private fun getMaxSafeRpmForModel(model: String): Int = when {
        model.startsWith("freellmapi:") -> 30
        model.contains("flash-lite") -> 10
        model.contains("3.8-flash") -> 5
        model.contains("robotics") -> 5
        else -> 5
    }

    private fun getMinSafeGapMsForModel(model: String): Long = when {
        model.startsWith("freellmapi:") -> 1_500L
        model.contains("flash-lite") -> 6_000L
        model.contains("3.8-flash") -> 12_000L
        model.contains("robotics") -> 12_000L
        else -> 10_000L
    }

    private fun isModelInHardCooldown(model: String, nowMs: Long = System.currentTimeMillis()): Boolean =
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

    private fun formatSec(ms: Long): String {
        return String.format(Locale.US, "%.2fs", ms / 1000.0)
    }

    private fun recordAndLogApiCallTelemetry(
        purpose: String,
        model: String,
        httpCode: Int,
        durationMs: Long,
        pacerWaitMs: Long,
        tokensInCall: Int
    ) {
        val now = System.currentTimeMillis()
        var callNum: Int
        var gapMs: Long
        var oneMinCalls: Int
        var oneMinDurationMs: Long
        var usedRpm: Int
        var maxRpm: Int
        var totalTokSession: Long

        synchronized(rateLock) {
            totalApiCallsSession++
            callNum = totalApiCallsSession
            if (httpCode == 200) totalSuccessCallsSession++
            if (httpCode == 429) totalQuota429HitsSession++

            gapMs = if (lastGlobalApiCallFinishMs > 0L) (now - durationMs - lastGlobalApiCallFinishMs).coerceAtLeast(0L) else 0L
            lastGlobalApiCallFinishMs = now

            totalTokensUsedSession += tokensInCall
            totalApiTimeMsSession += durationMs
            totalTokSession = totalTokensUsedSession

            perModelCallCount[model] = (perModelCallCount[model] ?: 0) + 1
            perModelTokensUsed[model] = (perModelTokensUsed[model] ?: 0L) + tokensInCall
            perModelTotalTimeMs[model] = (perModelTotalTimeMs[model] ?: 0L) + durationMs

            val rec = ApiCallTelemetryRecord(
                callNumber = callNum,
                purpose = purpose,
                model = model,
                httpCode = httpCode,
                durationMs = durationMs,
                pacerWaitMs = pacerWaitMs,
                gapSincePrevMs = gapMs,
                tokensUsed = tokensInCall,
                timestampMs = now
            )
            recentApiCallHistory.add(rec)
            recentApiCallHistory.removeAll { now - it.timestampMs > 300_000L }

            val lastMinRecords = recentApiCallHistory.filter { now - it.timestampMs <= 60_000L }
            oneMinCalls = lastMinRecords.size
            oneMinDurationMs = lastMinRecords.sumOf { it.durationMs }

            val baseModelKey = if (model.startsWith("FreeLLM")) "freellmapi:auto" else model
            usedRpm = getUsedRpmInLastMinute(baseModelKey, now)
            maxRpm = getMaxSafeRpmForModel(baseModelKey)
        }

        if (detailedApiLoggingEnabled) {
            val leftRpm = (maxRpm - usedRpm).coerceAtLeast(0)
            val statusLabel = if (httpCode == 200) "200 OK" else "HTTP $httpCode"
            val logLine = "📊 [API #$callNum | $statusLabel] काम: $purpose | Model: $model | " +
                "⏱️ Time: ${formatSec(durationMs)} (Wait: ${formatSec(pacerWaitMs)} | Gap: ${formatSec(gapMs)}) | " +
                "📈 1-Min: $oneMinCalls calls (${formatSec(oneMinDurationMs)} लगा) | " +
                "🔋 Quota: $usedRpm/$maxRpm RPM ($leftRpm left) | " +
                "🪙 Tokens: $tokensInCall (Total: $totalTokSession)"
            onBrainLog?.invoke(logLine)
        }
    }

    fun resetApiTelemetryStats() = synchronized(rateLock) {
        totalApiCallsSession = 0
        totalSuccessCallsSession = 0
        totalQuota429HitsSession = 0
        totalTokensUsedSession = 0L
        totalApiTimeMsSession = 0L
        lastGlobalApiCallFinishMs = 0L
        recentApiCallHistory.clear()
        perModelCallCount.clear()
        perModelTokensUsed.clear()
        perModelTotalTimeMs.clear()
    }

    fun getDetailedApiTelemetryReport(): String = synchronized(rateLock) {
        val now = System.currentTimeMillis()
        val lastMinList = recentApiCallHistory.filter { now - it.timestampMs <= 60_000L }
        val oneMinCalls = lastMinList.size
        val oneMinSec = formatSec(lastMinList.sumOf { it.durationMs })
        val oneMinWaitSec = formatSec(lastMinList.sumOf { it.pacerWaitMs })
        val avgSec = if (totalApiCallsSession > 0) formatSec(totalApiTimeMsSession / totalApiCallsSession) else "0.00s"

        val sb = StringBuilder()
        sb.append("⚡ 1-Min Live Meter: $oneMinCalls Calls | ⏱️ $oneMinSec API Time (Wait: $oneMinWaitSec)\n")
        sb.append("📊 Session Total: $totalApiCallsSession Calls (✅ $totalSuccessCallsSession OK | ⚠️ $totalQuota429HitsSession Quota 429)\n")
        sb.append("⏱️ Total API Time: ${formatSec(totalApiTimeMsSession)} (Avg: $avgSec/call) | 🪙 Total Tokens: $totalTokensUsedSession\n")

        if (perModelCallCount.isNotEmpty()) {
            sb.append("\n🤖 Model-Wise Usage Breakdown:\n")
            for ((m, cnt) in perModelCallCount) {
                val tMs = perModelTotalTimeMs[m] ?: 0L
                val tok = perModelTokensUsed[m] ?: 0L
                sb.append(" • $m: $cnt calls | ⏱️ ${formatSec(tMs)} | 🪙 $tok tok\n")
            }
        }

        val last4 = recentApiCallHistory.takeLast(4).reversed()
        if (last4.isNotEmpty()) {
            sb.append("\n🕒 Recent API Calls (किस काम में कितना समय लगा):\n")
            for (r in last4) {
                sb.append(" • #${r.callNumber} [${r.purpose}] ➔ ${formatSec(r.durationMs)} (Gap: ${formatSec(r.gapSincePrevMs)}, ${r.tokensUsed} tok, ${r.model})\n")
            }
        }
        return sb.toString().trim()
    }

    fun getActiveModelListStatus(): String {
        val now = System.currentTimeMillis()
        val lines = mutableListOf<String>()

        if (isFreeLlmConfigured()) {
            val used = getUsedRpmInLastMinute("freellmapi:auto", now)
            lines.add("🚀 FreeLLM / OmniRoute ($freeLlmBaseUrl/v1 | Ready | $used/30 RPM used)")
        }

        if (isGoogleGeminiConfigured() || !isFreeLlmConfigured()) {
            smartVisionLadder.forEach { m ->
                val used = getUsedRpmInLastMinute(m, now)
                val maxRpm = getMaxSafeRpmForModel(m)
                val left = (maxRpm - used).coerceAtLeast(0)
                val gapSec = getMinSafeGapMsForModel(m) / 1000L
                val waitMs = getWaitTimeMsForModel(m, now)
                val isLocked = isModelInHardCooldown(m, now)
                val status = when {
                    blockedModels.contains(m) -> "🚫 $m (Daily Quota Full)"
                    isLocked -> "🔒 $m (3-Min Cooldown Lock: ${(waitMs / 1000L).coerceAtLeast(1L)}s left)"
                    waitMs > 0L -> "⏳ $m (Pacer ${(waitMs / 1000L).coerceAtLeast(1L)}s | Used $used/$maxRpm RPM, $left left | ${gapSec}s Gap)"
                    else -> "✅ $m (Ready | Used $used/$maxRpm RPM, $left left | ${gapSec}s Gap)"
                }
                lines.add(status)
            }
        }
        return lines.joinToString("\n")
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
        val noThink = raw
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<think>[\\s\\S]*$", RegexOption.IGNORE_CASE), "")
            .replace("```json", "")
            .replace("```", "")
            .trim()

        val firstBrace = noThink.indexOf('{')
        if (firstBrace == -1) return JSONObject()

        var depth = 0
        var inString = false
        var escaped = false
        var endBrace = -1

        for (i in firstBrace until noThink.length) {
            val c = noThink[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\' && inString) {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        endBrace = i
                        break
                    }
                }
            }
        }

        val jsonCandidate = if (endBrace != -1) {
            noThink.substring(firstBrace, endBrace + 1)
        } else {
            val body = noThink.substringAfter("{").substringBeforeLast("}")
            "{$body}"
        }
        return JSONObject(jsonCandidate)
    }

    private fun buildFreeLlmGearCandidates(preferredGear: String): List<String> = when (preferredGear) {
        "FAST_LITE", "GEMMA_TEXT" -> listOf("freellmapi:auto", "freellmapi:auto:fast")
        else -> listOf("freellmapi:auto", "freellmapi:auto:smart")
    }

    private fun buildSmartGearLadder(preferredGear: String, hasImages: Boolean, isStuck: Boolean): List<String> {
        val freeLlmList = if (isFreeLlmConfigured()) buildFreeLlmGearCandidates(preferredGear) else emptyList()

        if (isFreeLlmConfigured() && !isGoogleGeminiConfigured()) {
            return freeLlmList
        }

        val geminiList = if (!hasImages && preferredGear == "GEMMA_TEXT") {
            textAndMetadataLadder
        } else if (isStuck) {
            listOf("gemini-3.5-flash-lite", "gemini-robotics-er-2-preview", "gemini-3.8-flash")
        } else {
            smartVisionLadder
        }

        return if (!hasImages) {
            freeLlmList + geminiList
        } else {
            geminiList + freeLlmList
        }
    }

    private suspend fun enforceSafeGlobalInterCallGap(): Long {
        val lastFinish = synchronized(rateLock) { lastGlobalApiCallFinishMs }
        if (lastFinish <= 0L) return 0L
        val elapsed = System.currentTimeMillis() - lastFinish
        if (elapsed in 0L until minGlobalInterCallGapMs) {
            val waitMs = minGlobalInterCallGapMs - elapsed
            delay(waitMs)
            return waitMs
        }
        return 0L
    }

    private suspend fun callWithLadder(
        prompt: String,
        imagesBase64: List<String> = emptyList(),
        useDeep38Flash: Boolean = true,
        preferredGear: String = if (useDeep38Flash) "COPILOT_38_ROBOTICS" else "FAST_LITE",
        isStuck: Boolean = false,
        callPurpose: String = "AI Call"
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        if (!isFreeLlmConfigured() && !isGoogleGeminiConfigured()) {
            onBrainLog?.invoke("⚠️ [$callPurpose] API Call Skipped: No API Key configured!")
            return@withContext Pair("", "No API Key")
        }

        val gearLadder = buildSmartGearLadder(preferredGear, imagesBase64.isNotEmpty(), isStuck)
            .filter { !blockedModels.contains(it) }
            .ifEmpty {
                blockedModels.clear()
                if (isFreeLlmConfigured()) buildFreeLlmGearCandidates(preferredGear) else smartVisionLadder
            }

        val now = System.currentTimeMillis()
        // Strictly skip any model that is in its 3-minute cooldown lock!
        val nonCooldownModels = gearLadder.filter { !isModelInHardCooldown(it, now) }
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

            if (isModelInHardCooldown(model, loopNow) && orderedCandidates.size > 1) {
                continue
            }

            // 1. Enforce minimum global gap so two requests NEVER hit Google 0.01s apart
            var actualPacerWaitMs = enforceSafeGlobalInterCallGap()

            // 2. Enforce per-model RPM pacer (6s for 3.5-flash-lite, 12s for 3.8-flash/robotics)
            val waitNeeded = getWaitTimeMsForModel(model, System.currentTimeMillis())
            if (waitNeeded > 0L) {
                if (waitNeeded <= 8_000L) {
                    onBrainLog?.invoke("⏱️ [$callPurpose] Safe Pacer: Waiting ${formatSec(waitNeeded)} for [$model]...")
                    val wStart = System.currentTimeMillis()
                    delay(waitNeeded)
                    actualPacerWaitMs += (System.currentTimeMillis() - wStart)
                } else {
                    continue
                }
            }

            recordModelRequestTimestamp(model)

            if (model.startsWith("freellmapi:")) {
                val routeMode = model.removePrefix("freellmapi:")
                val startMs = System.currentTimeMillis()
                val freeResult = executeFreeLlmApiCall(routeMode, prompt, imagesBase64)
                val durMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)

                recordAndLogApiCallTelemetry(
                    purpose = callPurpose,
                    model = freeResult.third,
                    httpCode = freeResult.first,
                    durationMs = durMs,
                    pacerWaitMs = actualPacerWaitMs,
                    tokensInCall = freeResult.fourth
                )

                if (freeResult.first == 200 && freeResult.second.isNotBlank()) {
                    return@withContext Pair(freeResult.second, freeResult.third)
                } else {
                    onBrainLog?.invoke("🔄 [$callPurpose] FreeLLMAPI [$routeMode] (${freeResult.first}) -> Trying next route...")
                    continue
                }
            }

            val startMs = System.currentTimeMillis()
            val result = executeSingleModelCall(model, prompt, imagesBase64)
            val durMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)

            recordAndLogApiCallTelemetry(
                purpose = callPurpose,
                model = model,
                httpCode = result.httpCode,
                durationMs = durMs,
                pacerWaitMs = actualPacerWaitMs,
                tokensInCall = result.tokensUsed
            )

            when (result.httpCode) {
                200 -> if (result.text.isNotBlank()) return@withContext Pair(result.text, model)
                429 -> {
                    if (result.isDailyQuotaExhausted) {
                        synchronized(rateLock) {
                            blockedModels.add(model)
                            quotaCooldownModels[model] = System.currentTimeMillis() + 3_600_000L
                        }
                        onBrainLog?.invoke("🚫 [$callPurpose] Daily Quota (PerDay) full on [$model] -> Locked for today, switching to partner model!")
                    } else {
                        synchronized(rateLock) {
                            quotaCooldownModels[model] = System.currentTimeMillis() + threeMinuteCooldownLockMs
                        }
                        onBrainLog?.invoke("🔒 [$callPurpose] Quota 429 on [$model] -> Locked for 3 Minutes (180s), switching immediately to partner model!")
                    }
                }
                else -> {
                    // 500 / 502 / 503 / 504 -> Immediately lock for 3 Minutes (180s) and switch to next model with ZERO wasted retry!
                    synchronized(rateLock) {
                        quotaCooldownModels[model] = System.currentTimeMillis() + threeMinuteCooldownLockMs
                    }
                    onBrainLog?.invoke("🔒 [$callPurpose] [$model] busy (${result.httpCode}) -> Locked for 3 Minutes (180s), switching immediately to partner model!")
                }
            }
        }
        Pair("", "All models busy")
    }

    private data class FreeLlmResponse(
        val first: Int,
        val second: String,
        val third: String,
        val fourth: Int
    )

    private data class GeminiCallResult(
        val httpCode: Int,
        val text: String,
        val tokensUsed: Int,
        val retryDelayMs: Long = 0L,
        val isDailyQuotaExhausted: Boolean = false
    )

    private fun executeFreeLlmApiCall(
        routeModel: String,
        prompt: String,
        imagesBase64: List<String>
    ): FreeLlmResponse {
        val validImages = imagesBase64.filter { it.isNotBlank() }

        if (validImages.isNotEmpty()) {
            val visionAttempt = performOpenAiCompatibleRequest(routeModel, prompt, validImages)
            if (visionAttempt.first == 200 && visionAttempt.second.isNotBlank()) {
                return visionAttempt
            }
            onBrainLog?.invoke("ℹ️ FreeLLMAPI Vision unavailable on current model -> Using XML Nodes Text Mode...")
        }

        return performOpenAiCompatibleRequest(routeModel, prompt, emptyList())
    }

    private fun performOpenAiCompatibleRequest(
        routeModel: String,
        prompt: String,
        imagesBase64: List<String>
    ): FreeLlmResponse {
        return try {
            val endpoint = "$freeLlmBaseUrl/v1/chat/completions"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $freeLlmApiKey")
                connectTimeout = 8_000
                readTimeout = 20_000
                doOutput = true
            }

            val messageObj = JSONObject().apply {
                put("role", "user")
                if (imagesBase64.isEmpty()) {
                    put("content", prompt)
                } else {
                    val contentArray = JSONArray()
                    contentArray.put(JSONObject().put("type", "text").put("text", prompt))
                    for (imgB64 in imagesBase64) {
                        val imageUrlObj = JSONObject().put("url", "data:image/webp;base64,$imgB64")
                        contentArray.put(JSONObject().put("type", "image_url").put("image_url", imageUrlObj))
                    }
                    put("content", contentArray)
                }
            }

            val payload = JSONObject().apply {
                put("model", routeModel)
                put("temperature", 0.1)
                put("messages", JSONArray().put(messageObj))
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 200) {
                val rawResp = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(rawResp)
                val actualModel = json.optString("model", routeModel).takeIf { it.isNotBlank() } ?: routeModel
                val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val text = message?.optString("content", "")?.trim() ?: ""
                val usageObj = json.optJSONObject("usage")
                val totalTok = usageObj?.optInt("total_tokens", 0)?.takeIf { it > 0 }
                    ?: ((prompt.length + text.length) / 4 + imagesBase64.size * 258)
                FreeLlmResponse(200, text, "FreeLLM[$actualModel]", totalTok)
            } else {
                FreeLlmResponse(code, "", "FreeLLM[$routeModel]", 0)
            }
        } catch (_: Exception) {
            FreeLlmResponse(500, "", "FreeLLM[$routeModel]", 0)
        }
    }

    /**
     * Executes Official Google Gemini REST Call with fast 11s read timeout so a slow server never hangs the task!
     */
    private fun executeSingleModelCall(
        model: String,
        prompt: String,
        imagesBase64: List<String>
    ): GeminiCallResult {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$googleGeminiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 7_000
                readTimeout = 11_000
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
                    put("responseMimeType", "application/json")
                })
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val text = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                    ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "") ?: ""
                val usageMeta = json.optJSONObject("usageMetadata")
                val totalTokens = usageMeta?.optInt("totalTokenCount", 0)?.takeIf { it > 0 }
                    ?: ((prompt.length + text.length) / 4 + imagesBase64.size * 258)
                GeminiCallResult(200, text.trim(), totalTokens)
            } else if (code == 429) {
                val errBody = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                val isPerDay = errBody.contains("PerDay", ignoreCase = true) ||
                    errBody.contains("RequestsPerDay", ignoreCase = true)
                val retryMatch = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").find(errBody)
                val retrySec = retryMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 30.0
                GeminiCallResult(
                    httpCode = 429,
                    text = "",
                    tokensUsed = 0,
                    retryDelayMs = (retrySec * 1000L).toLong(),
                    isDailyQuotaExhausted = isPerDay
                )
            } else {
                GeminiCallResult(code, "", 0)
            }
        } catch (_: Exception) {
            GeminiCallResult(500, "", 0)
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

    private fun isValidReusableSkill(fix: LearnedFixRecord): Boolean {
        val rule = fix.solutionRule
        if (rule.contains("@213") || rule.contains("Chat not selected", ignoreCase = true)) return false
        if (rule.contains("All models busy", ignoreCase = true) || fix.sourceType.contains("All models busy", ignoreCase = true)) return false
        return true
    }

    /**
     * 1. Universal Task Decider & Modular Micro-Step Planner (Works for ANY App or Website in the Universe!)
     */
    suspend fun chatAndBuildDetailedPlan(
        userMessage: String,
        chatHistory: String,
        userProfileRules: String
    ): JSONObject {
        val isCasual = isCasualGreetingOrChitchat(userMessage)

        val sysPrompt = """
            You are AuraDroid OS, an intelligent Hinglish Universal AI Companion & Planner for ANY Android App or Website.
            User's saved profile rules: $userProfileRules
            Recent conversation: $chatHistory
            User just said: "$userMessage"
            
            RULES:
            1. If casual greeting/chitchat ("Good morning", "Hi", "Kaise ho"), reply warmly, set "readyToExecute": false, "steps": [].
            2. Daily upload schedule -> "executionMode": "SCHEDULE_YT_HH:mm", "readyToExecute": true.
            3. Run only when phone is free -> "executionMode": "IDLE_PHONE_MODE", "readyToExecute": true.
            4. Folder to YouTube -> "executionMode": "YOUTUBE_FOLDER_UPLOAD", "readyToExecute": true.
            5. Google Flow to YouTube -> "executionMode": "GOOGLE_FLOW_UPLOAD", "readyToExecute": true.
            6. Background web mode -> "executionMode": "BACKGROUND_WEB", "readyToExecute": true.
            7. For ANY phone app or website automation, break every action into SEPARATE, CONFIRMABLE "Road-Crossing" micro-steps:
               - Never combine "Open search tab" and "Type query" into one step!
               - Never combine "Select recipient/item" and "Tap final Send/Submit/Buy button" into one step!
            
            Return ONLY valid JSON:
            {
              "reply": "Friendly Hinglish response",
              "readyToExecute": true,
              "taskTitle": "Short title",
              "executionMode": "LIVE_PHONE",
              "steps": [
                "Step 1 [FAST_LITE]: Open target app or website",
                "Step 2 [ROBOTICS_XY]: Navigate to search or target section",
                "Step 3 [FAST_LITE]: Type query/input and submit",
                "Step 4 [DEEP_3.8]: Select target item/result and complete final action"
              ]
            }
        """.trimIndent()

        val gear = if (isCasual) "GEMMA_TEXT" else "FAST_LITE"
        val purposeName = if (isCasual) "Chat Reply" else "Task Plan Builder"
        val (raw, model) = callWithLadder(
            prompt = sysPrompt,
            useDeep38Flash = true,
            preferredGear = gear,
            callPurpose = purposeName
        )
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
     * Universal Deterministic Road-Crossing Confirmation:
     * Detects when recipient is selected ("Write a message..." / bottom "Send" button visible)
     * and clicks the exact bottom blue Send button (500, 940) without ever hitting a mid-screen container!
     */
    private fun checkDeterministicClickConfirmation(
        goal: String,
        currentStepHistory: List<String>,
        numberedNodesList: String
    ): AgentStepDecision? {
        val lines = numberedNodesList.lines()
        val hasWriteMessage = lines.any { it.contains("Write a message", ignoreCase = true) }
        val hasAddToStoryOrCopyLink = lines.any {
            it.contains("Add to story", ignoreCase = true) || it.contains("Copy link", ignoreCase = true)
        }

        // Match "[12] Send (Button)" or "Send separately" without requiring quotes!
        val sendButtonLine = lines.firstOrNull { line ->
            val afterBracket = line.substringAfter("]", "").trim()
            (afterBracket.startsWith("Send (", ignoreCase = true) ||
                afterBracket.startsWith("Send separately", ignoreCase = true)) &&
                !afterBracket.startsWith("Send a", ignoreCase = true) &&
                !afterBracket.startsWith("Send message", ignoreCase = true) &&
                !afterBracket.startsWith("Send to", ignoreCase = true)
        }

        // PROOF #1: If "Write a message..." is visible (or "Send" button is visible and "Add to story/Copy link" is gone),
        // the friend IS 100% SELECTED with a blue checkmark!
        if ((hasWriteMessage && !hasAddToStoryOrCopyLink) || sendButtonLine != null) {
            val xyMatch = sendButtonLine?.let { Regex("Center\\((\\d+),\\s*(\\d+)\\)").find(it) }
            val rawX = xyMatch?.groupValues?.get(1)?.toIntOrNull() ?: 500
            val rawY = xyMatch?.groupValues?.get(2)?.toIntOrNull() ?: 940

            // Area Guard: Ensure Y is strictly in the bottom Send bar (not in the middle of the friend grid!)
            val safeSendX = if (rawX in 100..2000) rawX else 500
            val safeSendY = if (rawY > 1800 || rawY in 880..985) rawY else 940

            onBrainLog?.invoke("✅ [0.00s Road-Crossing Proof]: Friend checkmark & 'Write a message / Send' confirmed! Tapping bottom Send bar...")
            return AgentStepDecision(
                action = "CLICK_XY",
                tagId = -1,
                x = safeSendX,
                y = safeSendY,
                reason = "Confirmed recipient selected -> Tapping bottom blue Send button",
                modelUsed = "ConfirmEngine",
                isFinalAction = true,
                expectedNextScreen = "Share sheet closes and returns to main screen"
            )
        }

        return null
    }

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

        val now = System.currentTimeMillis()
        val elapsed = now - lastLiveStepTimestampMs
        if (lastLiveStepTimestampMs > 0L && elapsed in 1L..3_999L) {
            val waitMs = 4_000L - elapsed
            delay(waitMs)
        }
        lastLiveStepTimestampMs = System.currentTimeMillis()

        if (!isContinuousMonitor) {
            checkDeterministicClickConfirmation(goal, currentStepHistory, numberedNodesList)?.let { confirmedDecision ->
                return confirmedDecision
            }
        }

        val imgList = if (screenshotBitmap != null) listOf(bitmapToHdWebpBase64(screenshotBitmap)) else emptyList()

        val cleanFixes = recalledFixes.filter { isValidReusableSkill(it) }
        val sortedFixes = cleanFixes.sortedByDescending {
            it.sourceType.contains("PRO_ROBOTICS") || it.sourceType.contains("SKILL") || it.sourceType.contains("CHAT")
        }

        val mandatorySkillTransferBlock = if (sortedFixes.isNotEmpty()) {
            """
            🚨 CONFIRMED UNIVERSAL APP SKILLS & MANDATORY XY COORDINATES:
            Apply these verified coordinates from previous runs to "$goal":
            """.trimIndent() + "\n" +
                sortedFixes.take(10).joinToString("\n") { fix ->
                    val px = (fix.tapXRatio * bodySpecs.screenWidth).toInt()
                    val py = (fix.tapYRatio * bodySpecs.screenHeight).toInt()
                    val normX = (fix.tapXRatio * 1000).toInt()
                    val normY = (fix.tapYRatio * 1000).toInt()
                    "👉 [CONFIRMED SKILL] When on [${fix.problemDescription}]: ${fix.solutionRule} -> CLICK_XY x=$normX, y=$normY (Pixels: $px, $py)"
                }
        } else ""

        val monitorRule = if (isContinuousMonitor) {
            "IMPORTANT: CONTINUOUS MONITORING task. After sending a reply, return WAIT."
        } else ""

        val currentStepIndex = currentStepHistory.size
        val stepNum = currentStepIndex + 1
        val navTop = bodySpecs.screenHeight - bodySpecs.navBarHeight

        val prompt = """
            You are AuraDroid OS Universal Autonomous Vision & Road-Crossing Engine for ANY Android App or Website.
            
            DEVICE BODY SPECS: ${bodySpecs.modelName} (${bodySpecs.screenWidth}x${bodySpecs.screenHeight} px), Safe Top Y>${bodySpecs.cutoutSafeTop}, Nav Y>$navTop
            OVERALL GOAL: $goal
            PLANNED WORKFLOW: ${planSteps.joinToString(" -> ")}
            COMPLETED ACTIONS & MILESTONES SO FAR (DO NOT REPEAT COMPLETED STEPS!):
            ${currentStepHistory.takeLast(12).joinToString(" | ")}
            $monitorRule
            
            INTERACTIVE ELEMENTS CURRENTLY ON SCREEN:
            $numberedNodesList
            
            UNIVERSAL ROAD-CROSSING & VERIFICATION LAWS (FOR ANY APP OR WEBSITE):
            1. PRE-STEP VERIFICATION (CHECK WHICH ROAD YOU ARE ON):
               - Look at the attached screenshot and INTERACTIVE ELEMENTS to verify if the previous click succeeded before moving to the next step.
               - If the previous step was to select a person/item or open a page, check if the screen actually changed!
            2. SEARCH BAR LAW (ANY APP OR WEBSITE):
               - When on a search page where the search bar is visible and you have NOT typed the search query yet, use action "TYPE" on the search bar with text="<target query>". Never click random items before searching!
            3. COMMENT / MESSAGE INPUT LAW:
               - Never click "Share on Threads" or old user comments! Always tap/type in the bottom input box ("Join the conversation...", "Add a comment...", "Message...", etc. at y=935).
            4. SHARE / SEND / SUBMIT CONFIRMATION LAW:
               - When selecting a recipient in a Share sheet:
                 a) If the recipient is NOT visible in the grid, use "TYPE" on the Share sheet's "Search" box to find them.
                 b) Once a recipient is selected (blue checkmark + "Write a message..." or blue "Send" bar appears at bottom y=940), NEVER tap the recipient's avatar again (tapping again unselects them!).
                 c) To click the bottom blue "Send" / "Submit" / "Continue" bar, ALWAYS use action "CLICK_XY" with x=500, y=940 and set "isFinalAction": true!
            5. FINAL STEP ROAD-CROSSING:
               - Set "isFinalAction": true ONLY when you are clicking the VERY LAST button required to finish the entire OVERALL GOAL (e.g., clicking the final "Send", "Submit", "Upload", or "Save" button).
            
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
              "reason": "Confirmed current screen & exact action",
              "isFinalAction": false,
              "expectedNextScreen": "What the screen should look like after this action"
            }
        """.trimIndent()

        val (raw, modelUsed) = callWithLadder(
            prompt = prompt,
            imagesBase64 = imgList,
            useDeep38Flash = true,
            preferredGear = "COPILOT_38_ROBOTICS",
            isStuck = isStuck,
            callPurpose = "Step #$stepNum Screen Decision"
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
                modelUsed = modelUsed,
                isFinalAction = j.optBoolean("isFinalAction", false),
                expectedNextScreen = j.optString("expectedNextScreen", "")
            )
        } catch (_: Exception) {
            AgentStepDecision(action = "WAIT", reason = "Re-scanning screen...", modelUsed = modelUsed)
        }
    }

    suspend fun autoReflectAndImproveAfterRun(
        appCategory: String,
        goal: String,
        plannedSteps: List<String>,
        actualStepsTaken: List<String>,
        finalStatus: String,
        runBitmaps: List<Bitmap>
    ): PostRunReflectionResult {
        isLiveTaskCurrentlyRunning = false

        if (finalStatus.equals("SUCCESS", ignoreCase = true) || finalStatus.equals("STOPPED", ignoreCase = true)) {
            return PostRunReflectionResult(
                reflectionSummary = "टास्क सफलतापूर्वक पूरा हुआ (Quota Saver: फालतू ऑडिट स्किप किया गया)।",
                learnedRules = emptyList(),
                modelUsed = "QuotaSaver"
            )
        }

        delay(10_000L)
        if (isLiveTaskCurrentlyRunning || (System.currentTimeMillis() - lastLiveStepTimestampMs) < 8_000L) {
            return PostRunReflectionResult("Deferred for live task", emptyList(), "IdleGuard")
        }

        val imgsBase64 = runBitmaps.takeLast(4).map { bitmapToHdWebpBase64(it, maxHeight = 800) }
        val stepByStepLog = actualStepsTaken.mapIndexed { idx, s -> "Step ${idx + 1}: $s" }.joinToString("\n")

        val singlePassPrompt = """
            You are AuraDroid OS Universal Autonomous Auditor.
            A run in app "$appCategory" ended with status "$finalStatus" for goal "$goal".
            Planned: ${plannedSteps.joinToString(" -> ")}
            Actual Steps Taken:
            $stepByStepLog
            
            Inspect the screenshots, measure the exact 0.00..1.00 "tapXRatio" & "tapYRatio" where it got stuck, and output reusable skills.
            Return ONLY valid JSON:
            {
              "reflectionSummary": "Clear Hinglish diagnosis & fix",
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

        val (raw, modelUsed) = callWithLadder(
            prompt = singlePassPrompt,
            imagesBase64 = imgsBase64,
            useDeep38Flash = true,
            preferredGear = "COPILOT_38_ROBOTICS",
            callPurpose = "Failed-Run Diagnosis ($appCategory)"
        )

        if (modelUsed == "All models busy" || raw.isBlank()) {
            return PostRunReflectionResult("Skipped due to cooldown.", emptyList(), "CooldownGuard")
        }

        val extractedRules = mutableListOf<LearnedFixRecord>()
        var summaryText = "ऑडिट पूरा हुआ।"

        try {
            val j = parseCleanJson(raw)
            summaryText = j.optString("reflectionSummary", summaryText)
            val arr = j.optJSONArray("rules") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val record = LearnedFixRecord(
                    id = "refl_${System.currentTimeMillis()}_$i",
                    appCategory = appCategory,
                    sourceType = "PRO_ROBOTICS_SKILL ($modelUsed)",
                    screenSignature = "universal_skill_$i",
                    problemDescription = item.optString("screenWhen", "[UNIVERSAL_SKILL] Step ${i + 1} in $appCategory"),
                    solutionRule = item.optString("actionInstruction", "Follow autonomous skill coordinate"),
                    tapXRatio = item.optDouble("tapXRatio", 0.5).toFloat(),
                    tapYRatio = item.optDouble("tapYRatio", 0.5).toFloat(),
                    timestamp = "AutoFix"
                )
                if (isValidReusableSkill(record)) {
                    extractedRules.add(record)
                }
            }
        } catch (_: Exception) {}

        return PostRunReflectionResult(
            reflectionSummary = summaryText,
            learnedRules = extractedRules,
            modelUsed = modelUsed
        )
    }

    private fun extractPinnedXyFromUserMessage(msg: String): Pair<Float, Float>? {
        val regex = Regex("Pin XY:\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)", RegexOption.IGNORE_CASE)
        val match = regex.find(msg) ?: return null
        val x = match.groupValues[1].toFloatOrNull() ?: return null
        val y = match.groupValues[2].toFloatOrNull() ?: return null
        return Pair((x / 1000f).coerceIn(0.01f, 0.99f), (y / 1000f).coerceIn(0.01f, 0.99f))
    }

    suspend fun chatAboutAutomationRunWith38Flash(
        runRecord: AutomationRunRecord,
        userCorrectionMessage: String,
        screenshotsBase64: List<String>
    ): MemoryChatReply {
        val pinnedXy = extractPinnedXyFromUserMessage(userCorrectionMessage)

        val prompt = """
            You are AuraDroid OS Run-Correction Architect.
            The user is reviewing past run "${runRecord.title}" in app "${runRecord.appCategory}".
            Steps Taken: ${runRecord.stepsSummary.mapIndexed { i, s -> "Step ${i + 1}: $s" }.joinToString(" | ")}
            User Correction: "$userCorrectionMessage"
            
            1. Inspect the attached screenshots yourself and measure the exact 0.00..1.00 "tapXRatio" and "tapYRatio" of the correct button!
            2. Generalize "solutionRule" as a Reusable Skill so it works for any future task in "${runRecord.appCategory}".
            
            Return ONLY valid JSON:
            {
              "replyText": "Hinglish reply explaining the exact step & coordinate you measured",
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
            preferredGear = "ROBOTICS_PRECISION",
            callPurpose = "Run Correction Chat (${runRecord.appCategory})"
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

    suspend fun chatAboutTrainingVideoWith38Flash(
        videoSession: VideoTrainingSession,
        userMessage: String,
        videoFramesBase64: List<String>
    ): MemoryChatReply {
        val pinnedXy = extractPinnedXyFromUserMessage(userMessage)
        val prompt = """
            You are AuraDroid OS Video-Memory Coach for app "${videoSession.appCategory}".
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

        val (raw, modelUsed) = callWithLadder(
            prompt = prompt,
            imagesBase64 = videoFramesBase64,
            useDeep38Flash = true,
            preferredGear = "ROBOTICS_PRECISION",
            callPurpose = "Video Training Chat (${videoSession.appCategory})"
        )
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

        if (isLiveTaskCurrentlyRunning || (System.currentTimeMillis() - lastLiveStepTimestampMs) < 6_000L) {
            return Pair("", "")
        }

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
            You are AuraDroid OS Spatial Skill Learner.
            During "$goal" in "$appCategory", the human tapped at 0..1000 XY($normX, $normY) on [$cleanDesc].
            Image 1 = BEFORE, Image 2 = AFTER.
            Generalize this tap into a Reusable App Skill for $appCategory.
            Return ONLY JSON:
            {
              "problem": "[UNIVERSAL_SKILL] Reusable screen state in $appCategory",
              "solutionRule": "MANDATORY SKILL OVERRIDE: Tap '$cleanDesc' at 0..1000 XY($normX, $normY) to proceed"
            }
        """.trimIndent()

        val (raw, modelUsed) = callWithLadder(
            prompt = prompt,
            imagesBase64 = imgs,
            useDeep38Flash = true,
            preferredGear = "ROBOTICS_PRECISION",
            callPurpose = "Human Touch Learning ($appCategory)"
        )

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

    suspend fun analyzeTrainingVideoFrames(
        appCategory: String,
        userInstruction: String,
        framesBase64: List<String>
    ): List<LearnedFixRecord> {
        val prompt = """
            You are AuraDroid OS Video Skill Extractor for app "$appCategory" (Note: "$userInstruction").
            Inspect all ${framesBase64.size} sequential frames, measure exact 0.0..1.0 "tapXRatio" & "tapYRatio", and extract reusable modular skills that work on any future task!
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

        val (raw, modelUsed) = callWithLadder(
            prompt = prompt,
            imagesBase64 = framesBase64,
            useDeep38Flash = true,
            preferredGear = "ROBOTICS_PRECISION",
            callPurpose = "Video Frame Extractor ($appCategory, ${framesBase64.size} frames)"
        )
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

    fun sanitizeRawVideoTopic(rawTopicOrFileName: String): String {
        val withoutExt = rawTopicOrFileName
            .substringBeforeLast(".")
            .replace(Regex("(?i)\\b(flow|vid|video|lv|inshot|capcut|vn|km)[_\\-\\s]*\\d{5,}\\b"), "")
            .replace(Regex("\\b\\d{7,}\\b"), "")
            .replace("_", " ")
            .replace("-", " ")
            .trim()
        return withoutExt.ifBlank { "Amazing Cinematic Viral Short Video" }
    }

    suspend fun generateFlowPromptTitleAndDescription(
        topic: String,
        customDescriptionNote: String = "",
        videoFrameBitmap: Bitmap? = null
    ): FlowVideoMetadata {
        val cleanTopic = sanitizeRawVideoTopic(topic)
        val descHint = if (customDescriptionNote.isNotBlank()) {
            "Also include or follow this user description note: '$customDescriptionNote'."
        } else {
            "Write an engaging 3-line viral description with trending hashtags (#shorts #viral #trending)."
        }

        val frameB64List = if (videoFrameBitmap != null) {
            listOf(bitmapToHdWebpBase64(videoFrameBitmap, maxHeight = 640))
        } else {
            emptyList()
        }

        val visualInstruction = if (frameB64List.isNotEmpty()) {
            "Look at the attached video frame image and write a catchy, 100% matching viral YouTube Shorts / Instagram Reels title and description for what is shown in the video (Hint: '$cleanTopic'). NEVER include random file numbers or timestamps in the title!"
        } else {
            "Create a vertical 9:16 video generation prompt, a viral YouTube Shorts / Instagram Reels title, and a full engaging description strictly for: '$cleanTopic'. NEVER include random file numbers or timestamps in the title!"
        }

        val prompt = """
            $visualInstruction
            $descHint
            Return ONLY valid JSON:
            {
              "videoPrompt": "Cinematic 8k vertical 9:16 video of...",
              "youtubeTitle": "Catchy Viral Title! ✨ #shorts #viral",
              "youtubeDescription": "Full engaging description with hashtags..."
            }
        """.trimIndent()

        val (raw, _) = callWithLadder(
            prompt = prompt,
            imagesBase64 = frameB64List,
            useDeep38Flash = true,
            preferredGear = if (frameB64List.isNotEmpty()) "COPILOT_38_ROBOTICS" else "GEMMA_TEXT",
            callPurpose = "Flow Prompt + Viral Title & Description"
        )
        return try {
            val j = parseCleanJson(raw)
            val fallbackTitle = "${cleanTopic.take(45)} ✨ #shorts #viral"
            val fallbackDesc = buildString {
                append(cleanTopic)
                if (customDescriptionNote.isNotBlank()) append("\n\n").append(customDescriptionNote)
                append("\n\n#shorts #viral #trending #youtubeshorts #reels")
            }
            FlowVideoMetadata(
                videoPrompt = j.optString("videoPrompt", "Cinematic 8k vertical 9:16 video of $cleanTopic, natural lighting, expressive motion, ultra detailed"),
                viralTitle = j.optString("youtubeTitle", fallbackTitle),
                viralDescription = j.optString("youtubeDescription", fallbackDesc)
            )
        } catch (_: Exception) {
            val fallbackDesc = buildString {
                append(cleanTopic)
                if (customDescriptionNote.isNotBlank()) append("\n\n").append(customDescriptionNote)
                append("\n\n#shorts #viral #trending #youtubeshorts #reels")
            }
            FlowVideoMetadata(
                videoPrompt = "Cinematic 8k vertical 9:16 video of $cleanTopic, natural lighting, smooth camera movement, ultra detailed",
                viralTitle = "${cleanTopic.take(45)} ✨ #shorts #viral",
                viralDescription = fallbackDesc
            )
        }
    }

    suspend fun generateFlowSafePromptAndTitle(topic: String): Pair<String, String> {
        val meta = generateFlowPromptTitleAndDescription(topic)
        return Pair(meta.videoPrompt, meta.viralTitle)
    }
}
