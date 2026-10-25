package com.auradroid.os

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

data class LearnedFixRecord(
    val id: String,
    val appCategory: String,
    val sourceType: String,
    val screenSignature: String,
    val problemDescription: String,
    val solutionRule: String,
    val tapXRatio: Float,
    val tapYRatio: Float,
    val timestamp: String
)

data class AutomationRunRecord(
    val runId: String,
    val title: String,
    val appCategory: String,
    val status: String,
    val timestamp: String,
    val stepsSummary: List<String>,
    val screenshotPaths: List<String>,
    val chatHistory: List<String>
)

data class VideoTrainingSession(
    val sessionId: String,
    val appCategory: String,
    val userNote: String,
    val timestamp: String,
    val framePaths: List<String>,
    val extractedRulesSummary: List<String>,
    val chatHistory: List<String>
)

class MemoryVault(private val context: Context) {

    private val vaultLock = Any()
    private val logLock = Any()

    private val rootDir: File = File(context.filesDir, "Memory").apply {
        if (!exists()) mkdirs()
    }

    private val runsDir: File = File(rootDir, "AutomationRuns").apply {
        if (!exists()) mkdirs()
    }

    private val videosDir: File = File(rootDir, "VideoSessions").apply {
        if (!exists()) mkdirs()
    }

    private val profileFile: File = File(rootDir, "user_profile_rules.json")
    private val persistentLogsFile: File = File(rootDir, "system_action_logs.txt")
    private val hermesLedgerFile: File = File(rootDir, "hermes_posted_ledger.json")

    private val cachedLogs = ArrayDeque<String>()
    private var logsInitialized = false

    fun resolveCategoryFolder(packageName: String, taskGoal: String): String {
        val combined = "$packageName $taskGoal".lowercase(Locale.ROOT)
        return when {
            combined.contains("instagram") || combined.contains("इंस्टाग्राम") || combined.contains("insta") -> "Instagram"
            combined.contains("youtube") || combined.contains("यूट्यूब") || combined.contains("shorts") -> "YouTube"
            combined.contains("whatsapp") || combined.contains("व्हाट्सएप") -> "WhatsApp"
            combined.contains("chrome") || combined.contains("web") || combined.contains("flow") || combined.contains("browser") || combined.contains("qwen") -> "WebStudio"
            combined.contains("setting") || combined.contains("सेटिंग") -> "PhoneSettings"
            combined.contains("telegram") || combined.contains("टेलीग्राम") -> "Telegram"
            combined.contains("facebook") || combined.contains("फेसबुक") -> "Facebook"
            combined.contains("snapchat") -> "Snapchat"
            combined.contains("twitter") || combined.contains("com.twitter") -> "X_Twitter"
            combined.contains("vending") || combined.contains("play store") -> "PlayStore"
            combined.contains("github") -> "GitHub"
            else -> {
                val raw = packageName.substringAfterLast('.')
                    .replace(Regex("[^a-zA-Z0-9_]"), "")
                    .replaceFirstChar { it.uppercase() }
                if (raw.isEmpty() || raw.equals("Android", true)) "General" else raw
            }
        }
    }

    private fun getCategoryDir(category: String): File {
        val canonical = resolveCategoryFolder(category, category)
        val safeName = canonical.replace(Regex("[^a-zA-Z0-9_]"), "").ifEmpty { "General" }
        return File(rootDir, safeName).apply { if (!exists()) mkdirs() }
    }

    private fun isPoisonedRuleText(problem: String, rule: String, source: String): Boolean {
        if (rule.contains("@213") || problem.contains("@213")) return true
        if (rule.contains("Chat not selected", ignoreCase = true)) return true
        if (rule.contains("All models busy", ignoreCase = true) || source.contains("All models busy", ignoreCase = true)) return true
        if (source.contains("No API Key", ignoreCase = true)) return true
        return false
    }

    @Suppress("DEPRECATION")
    fun saveStuckOrHumanFix(
        category: String,
        screenSignature: String,
        problemDescription: String,
        solutionRule: String,
        tapXRatio: Float,
        tapYRatio: Float,
        beforeScreenshot: Bitmap? = null,
        afterScreenshot: Bitmap? = null,
        sourceType: String = "HUMAN_TOUCH_FIX"
    ) = synchronized(vaultLock) {
        val cleanProblem = problemDescription.trim()
        val cleanRule = solutionRule.trim()
        if (cleanProblem.isEmpty() && cleanRule.isEmpty()) return
        if (isPoisonedRuleText(cleanProblem, cleanRule, sourceType)) return

        val canonicalCat = resolveCategoryFolder(category, category)
        val catDir = getCategoryDir(canonicalCat)
        val fixesFile = File(catDir, "fixes.json")

        val existingList = mutableListOf<JSONObject>()
        if (fixesFile.exists()) {
            try {
                val rawArr = JSONArray(fixesFile.readText())
                for (i in 0 until rawArr.length()) {
                    rawArr.optJSONObject(i)?.let { obj ->
                        val oldProb = obj.optString("problemDescription", "")
                        val oldRule = obj.optString("solutionRule", "")
                        val oldSrc = obj.optString("sourceType", "")
                        if (!isPoisonedRuleText(oldProb, oldRule, oldSrc)) {
                            existingList.add(obj)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val safeX = tapXRatio.coerceIn(0.01f, 0.99f)
        val safeY = tapYRatio.coerceIn(0.01f, 0.99f)

        val duplicateIndex = existingList.indexOfFirst { old ->
            val oldProb = old.optString("problemDescription", "").trim()
            val oldRule = old.optString("solutionRule", "").trim()
            val oldSig = old.optString("screenSignature", "")
            val oldX = old.optDouble("tapXRatio", -1.0).toFloat()
            val oldY = old.optDouble("tapYRatio", -1.0).toFloat()

            val sameProblemText = oldProb.equals(cleanProblem, ignoreCase = true) ||
                (oldProb.length > 10 && cleanProblem.contains(oldProb, ignoreCase = true))
            val sameCoordAndSig = oldSig.isNotEmpty() && oldSig == screenSignature.take(250) &&
                hypot((oldX - safeX).toDouble(), (oldY - safeY).toDouble()) < 0.035

            sameProblemText || (sameCoordAndSig && oldRule.equals(cleanRule, ignoreCase = true))
        }

        if (duplicateIndex >= 0) {
            val removed = existingList.removeAt(duplicateIndex)
            val oldId = removed.optString("id", "")
            if (oldId.isNotEmpty()) {
                try {
                    File(catDir, "${oldId}_before.webp").delete()
                    File(catDir, "${oldId}_after.webp").delete()
                } catch (_: Exception) {}
            }
        }

        val id = "fix_${System.currentTimeMillis()}"
        if (beforeScreenshot != null) {
            try {
                File(catDir, "${id}_before.webp").outputStream().use { out ->
                    beforeScreenshot.compress(Bitmap.CompressFormat.WEBP, 80, out)
                }
            } catch (_: Exception) {}
        }
        if (afterScreenshot != null) {
            try {
                File(catDir, "${id}_after.webp").outputStream().use { out ->
                    afterScreenshot.compress(Bitmap.CompressFormat.WEBP, 80, out)
                }
            } catch (_: Exception) {}
        }

        val newObj = JSONObject().apply {
            put("id", id)
            put("appCategory", canonicalCat)
            put("sourceType", sourceType)
            put("screenSignature", screenSignature.take(250))
            put("problemDescription", cleanProblem)
            put("solutionRule", cleanRule)
            put("tapXRatio", safeX.toDouble())
            put("tapYRatio", safeY.toDouble())
            put("timestamp", SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date()))
        }
        existingList.add(newObj)

        while (existingList.size > 60) {
            val dropIdx = existingList.indexOfFirst {
                it.optString("sourceType").contains("REFLECTION", true)
            }.takeIf { it >= 0 } ?: existingList.indexOfFirst {
                !it.optString("sourceType").contains("LIVE_CODE_RULE", true)
            }.takeIf { it >= 0 } ?: 0

            val dropped = existingList.removeAt(dropIdx)
            val droppedId = dropped.optString("id", "")
            if (droppedId.isNotEmpty()) {
                try {
                    File(catDir, "${droppedId}_before.webp").delete()
                    File(catDir, "${droppedId}_after.webp").delete()
                } catch (_: Exception) {}
            }
        }

        val outArr = JSONArray()
        existingList.forEach { outArr.put(it) }
        writeTextSafely(fixesFile, outArr.toString(2))
    }

    fun recallFixesForApp(category: String): List<LearnedFixRecord> = synchronized(vaultLock) {
        val canonicalCat = resolveCategoryFolder(category, category)
        val catDir = getCategoryDir(canonicalCat)
        val fixesFile = File(catDir, "fixes.json")
        if (!fixesFile.exists()) return emptyList()

        val allRecords = mutableListOf<LearnedFixRecord>()
        try {
            val arr = JSONArray(fixesFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val prob = o.optString("problemDescription")
                val rule = o.optString("solutionRule")
                val src = o.optString("sourceType", "HUMAN_TOUCH_FIX")
                if (isPoisonedRuleText(prob, rule, src)) continue

                allRecords.add(
                    LearnedFixRecord(
                        id = o.optString("id"),
                        appCategory = o.optString("appCategory", canonicalCat),
                        sourceType = src,
                        screenSignature = o.optString("screenSignature"),
                        problemDescription = prob,
                        solutionRule = rule,
                        tapXRatio = o.optDouble("tapXRatio", 0.5).toFloat(),
                        tapYRatio = o.optDouble("tapYRatio", 0.5).toFloat(),
                        timestamp = o.optString("timestamp")
                    )
                )
            }
        } catch (_: Exception) {}

        val liveLaws = allRecords.filter {
            it.sourceType.contains("LIVE_CODE_RULE", true) || it.sourceType.contains("CHAT_VIDEO_LAW", true)
        }.takeLast(14)

        val humanAndVideoRules = allRecords.filter {
            !it.sourceType.contains("LIVE_CODE_RULE", true) &&
                !it.sourceType.contains("CHAT_VIDEO_LAW", true) &&
                !it.sourceType.contains("REFLECTION", true)
        }.takeLast(8)

        val recentReflections = allRecords.filter {
            it.sourceType.contains("REFLECTION", true)
        }.takeLast(6)

        return (recentReflections + humanAndVideoRules + liveLaws).distinctBy { it.id }.reversed()
    }

    fun getAllCategoriesSummary(): List<Pair<String, Int>> = synchronized(vaultLock) {
        val folders = rootDir.listFiles()?.filter {
            it.isDirectory && it.name != "AutomationRuns" && it.name != "VideoSessions"
        } ?: return emptyList()

        return folders.mapNotNull { folder ->
            val fixesFile = File(folder, "fixes.json")
            val count = if (fixesFile.exists()) {
                try { JSONArray(fixesFile.readText()).length() } catch (_: Exception) { 0 }
            } else 0
            if (count > 0 || folder.name in listOf("Instagram", "YouTube", "WhatsApp", "WebStudio")) {
                Pair(folder.name, count)
            } else null
        }.sortedByDescending { it.second }
    }

    fun markVideoAsPostedInLedger(videoFilePathOrName: String, platform: String = "YouTube") = synchronized(vaultLock) {
        val key = File(videoFilePathOrName).name.lowercase(Locale.ROOT)
        val obj = try {
            if (hermesLedgerFile.exists()) JSONObject(hermesLedgerFile.readText()) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
        val entry = JSONObject().apply {
            put("platform", platform)
            put("postedAt", SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date()))
        }
        obj.put(key, entry)
        writeTextSafely(hermesLedgerFile, obj.toString(2))
    }

    fun isVideoPostedInLedger(videoFilePathOrName: String): Boolean = synchronized(vaultLock) {
        val key = File(videoFilePathOrName).name.lowercase(Locale.ROOT)
        return try {
            if (!hermesLedgerFile.exists()) false
            else JSONObject(hermesLedgerFile.readText()).has(key)
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    fun saveAutomationRunSession(
        runId: String,
        title: String,
        appCategory: String,
        status: String,
        stepsSummary: List<String>,
        capturedBitmaps: List<Bitmap>,
        existingChat: List<String> = emptyList()
    ): AutomationRunRecord = synchronized(vaultLock) {
        val canonicalCat = resolveCategoryFolder(appCategory, title)
        val runFolder = File(runsDir, runId).apply { if (!exists()) mkdirs() }
        val savedPaths = mutableListOf<String>()

        capturedBitmaps.takeLast(10).forEachIndexed { idx, bmp ->
            try {
                if (!bmp.isRecycled) {
                    val imgFile = File(runFolder, "step_${idx + 1}.webp")
                    imgFile.outputStream().use { out ->
                        bmp.compress(Bitmap.CompressFormat.WEBP, 80, out)
                    }
                    savedPaths.add(imgFile.absolutePath)
                }
            } catch (_: Exception) {}
        }

        val timestamp = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date())
        val metaFile = File(runFolder, "run_meta.json")
        val metaJson = JSONObject().apply {
            put("runId", runId)
            put("title", title)
            put("appCategory", canonicalCat)
            put("status", status)
            put("timestamp", timestamp)
            put("stepsSummary", JSONArray(stepsSummary))
            put("screenshotPaths", JSONArray(savedPaths))
            put("chatHistory", JSONArray(existingChat))
        }
        writeTextSafely(metaFile, metaJson.toString(2))

        pruneOldFolders(runsDir, maxKeep = 25)

        return AutomationRunRecord(
            runId = runId,
            title = title,
            appCategory = canonicalCat,
            status = status,
            timestamp = timestamp,
            stepsSummary = stepsSummary,
            screenshotPaths = savedPaths,
            chatHistory = existingChat
        )
    }

    fun appendChatToAutomationRun(runId: String, sender: String, message: String) = synchronized(vaultLock) {
        val runFolder = File(runsDir, runId)
        val metaFile = File(runFolder, "run_meta.json")
        if (!metaFile.exists()) return
        try {
            val j = JSONObject(metaFile.readText())
            val chatArr = j.optJSONArray("chatHistory") ?: JSONArray()
            chatArr.put("$sender: $message")
            j.put("chatHistory", chatArr)
            writeTextSafely(metaFile, j.toString(2))
        } catch (_: Exception) {}
    }

    fun getAllAutomationRuns(): List<AutomationRunRecord> = synchronized(vaultLock) {
        val folders = runsDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }
            ?: return emptyList()
        val list = mutableListOf<AutomationRunRecord>()
        for (folder in folders.take(25)) {
            val metaFile = File(folder, "run_meta.json")
            if (!metaFile.exists()) continue
            try {
                val j = JSONObject(metaFile.readText())
                val steps = mutableListOf<String>()
                val sArr = j.optJSONArray("stepsSummary") ?: JSONArray()
                for (i in 0 until sArr.length()) steps.add(sArr.optString(i))

                val paths = mutableListOf<String>()
                val pArr = j.optJSONArray("screenshotPaths") ?: JSONArray()
                for (i in 0 until pArr.length()) {
                    val p = pArr.optString(i)
                    if (p.isNotEmpty() && File(p).exists()) paths.add(p)
                }

                val chats = mutableListOf<String>()
                val cArr = j.optJSONArray("chatHistory") ?: JSONArray()
                for (i in 0 until cArr.length()) chats.add(cArr.optString(i))

                list.add(
                    AutomationRunRecord(
                        runId = j.optString("runId", folder.name),
                        title = j.optString("title", "Task Run"),
                        appCategory = j.optString("appCategory", "General"),
                        status = j.optString("status", "STOPPED"),
                        timestamp = j.optString("timestamp", ""),
                        stepsSummary = steps,
                        screenshotPaths = paths,
                        chatHistory = chats
                    )
                )
            } catch (_: Exception) {}
        }
        return list
    }

    @Suppress("DEPRECATION")
    fun createVideoTrainingSession(
        appCategory: String,
        userNote: String,
        videoUri: Uri,
        maxFrames: Int = 12
    ): Pair<VideoTrainingSession, List<String>> {
        val canonicalCat = resolveCategoryFolder(appCategory, userNote)
        val sessionId = "vid_${System.currentTimeMillis()}"
        val sessionFolder = File(videosDir, sessionId).apply { if (!exists()) mkdirs() }
        val framePaths = mutableListOf<String>()
        val framesBase64 = mutableListOf<String>()

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durationMs > 0L) {
                val safeCount = maxFrames.coerceIn(4, 14)
                val stepMs = (durationMs / (safeCount + 1)).coerceAtLeast(250L)
                var lastFrameHash = -1L
                var savedIdx = 0

                for (i in 1..safeCount) {
                    val timeUs = ((stepMs * i).coerceAtMost(durationMs - 100L)).coerceAtLeast(0L) * 1000L
                    val rawBmp = try {
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                            ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } catch (_: Exception) {
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } ?: continue

                    val ratio = rawBmp.width.toFloat() / rawBmp.height.toFloat().coerceAtLeast(1f)
                    val targetH = 960
                    val targetW = (targetH * ratio).toInt().coerceAtLeast(360)
                    val scaled = if (rawBmp.height != targetH) {
                        Bitmap.createScaledBitmap(rawBmp, targetW, targetH, true)
                    } else {
                        rawBmp
                    }

                    if (scaled !== rawBmp) {
                        try { rawBmp.recycle() } catch (_: Exception) {}
                    }

                    val currentHash = computeQuickVisualSignature(scaled)
                    val isNearIdentical = lastFrameHash != -1L && abs(currentHash - lastFrameHash) < 18L && i < safeCount
                    if (isNearIdentical && savedIdx >= 2) {
                        try { scaled.recycle() } catch (_: Exception) {}
                        continue
                    }
                    lastFrameHash = currentHash
                    savedIdx++

                    val frameFile = File(sessionFolder, "frame_$savedIdx.webp")
                    frameFile.outputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.WEBP, 82, out)
                    }
                    framePaths.add(frameFile.absolutePath)

                    val bos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.WEBP, 82, bos)
                    framesBase64.add(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))

                    try { scaled.recycle() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        val timestamp = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())
        val session = VideoTrainingSession(
            sessionId = sessionId,
            appCategory = canonicalCat,
            userNote = userNote,
            timestamp = timestamp,
            framePaths = framePaths,
            extractedRulesSummary = emptyList(),
            chatHistory = listOf("User Note: $userNote")
        )
        saveVideoTrainingSessionMeta(session)
        pruneOldFolders(videosDir, maxKeep = 20)
        return Pair(session, framesBase64)
    }

    private fun computeQuickVisualSignature(bmp: Bitmap): Long {
        return try {
            var sum = 0L
            val stepX = (bmp.width / 8).coerceAtLeast(1)
            val stepY = (bmp.height / 8).coerceAtLeast(1)
            var samples = 0
            var x = stepX / 2
            while (x < bmp.width) {
                var y = stepY / 2
                while (y < bmp.height) {
                    val px = bmp.getPixel(x, y)
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    sum += (r + g + b) / 3
                    samples++
                    y += stepY
                }
                x += stepX
            }
            if (samples > 0) sum / samples else 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun saveVideoTrainingSessionMeta(session: VideoTrainingSession) = synchronized(vaultLock) {
        val sessionFolder = File(videosDir, session.sessionId).apply { if (!exists()) mkdirs() }
        val metaFile = File(sessionFolder, "video_meta.json")
        val metaJson = JSONObject().apply {
            put("sessionId", session.sessionId)
            put("appCategory", resolveCategoryFolder(session.appCategory, session.userNote))
            put("userNote", session.userNote)
            put("timestamp", session.timestamp)
            put("framePaths", JSONArray(session.framePaths))
            put("extractedRulesSummary", JSONArray(session.extractedRulesSummary))
            put("chatHistory", JSONArray(session.chatHistory))
        }
        writeTextSafely(metaFile, metaJson.toString(2))
    }

    fun appendChatToVideoSession(
        sessionId: String,
        sender: String,
        message: String,
        newRuleAdded: String? = null
    ) = synchronized(vaultLock) {
        val sessionFolder = File(videosDir, sessionId)
        val metaFile = File(sessionFolder, "video_meta.json")
        if (!metaFile.exists()) return
        try {
            val j = JSONObject(metaFile.readText())
            val chatArr = j.optJSONArray("chatHistory") ?: JSONArray()
            chatArr.put("$sender: $message")
            j.put("chatHistory", chatArr)

            if (!newRuleAdded.isNullOrBlank()) {
                val rulesArr = j.optJSONArray("extractedRulesSummary") ?: JSONArray()
                rulesArr.put(newRuleAdded)
                j.put("extractedRulesSummary", rulesArr)
            }
            writeTextSafely(metaFile, j.toString(2))
        } catch (_: Exception) {}
    }

    fun getAllVideoTrainingSessions(): List<VideoTrainingSession> = synchronized(vaultLock) {
        val folders = videosDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }
            ?: return emptyList()
        val list = mutableListOf<VideoTrainingSession>()
        for (folder in folders.take(20)) {
            val metaFile = File(folder, "video_meta.json")
            if (!metaFile.exists()) continue
            try {
                val j = JSONObject(metaFile.readText())
                val fPaths = mutableListOf<String>()
                val fArr = j.optJSONArray("framePaths") ?: JSONArray()
                for (i in 0 until fArr.length()) {
                    val p = fArr.optString(i)
                    if (p.isNotEmpty() && File(p).exists()) fPaths.add(p)
                }

                val rules = mutableListOf<String>()
                val rArr = j.optJSONArray("extractedRulesSummary") ?: JSONArray()
                for (i in 0 until rArr.length()) rules.add(rArr.optString(i))

                val chats = mutableListOf<String>()
                val cArr = j.optJSONArray("chatHistory") ?: JSONArray()
                for (i in 0 until cArr.length()) chats.add(cArr.optString(i))

                list.add(
                    VideoTrainingSession(
                        sessionId = j.optString("sessionId", folder.name),
                        appCategory = j.optString("appCategory", "General"),
                        userNote = j.optString("userNote", ""),
                        timestamp = j.optString("timestamp", ""),
                        framePaths = fPaths,
                        extractedRulesSummary = rules,
                        chatHistory = chats
                    )
                )
            } catch (_: Exception) {}
        }
        return list
    }

    fun loadSavedImagesAsBase64(paths: List<String>, maxImages: Int = 8): List<String> {
        val result = mutableListOf<String>()
        for (path in paths.takeLast(maxImages)) {
            try {
                val f = File(path)
                if (f.exists() && f.length() > 0L) {
                    val bytes = f.readBytes()
                    result.add(Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
            } catch (_: Exception) {}
        }
        return result
    }

    fun loadBitmapFromPath(path: String): Bitmap? {
        return try {
            val f = File(path)
            if (!f.exists() || f.length() == 0L) return null
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(f.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun ensureLogsLoadedLocked() {
        if (logsInitialized) return
        logsInitialized = true
        try {
            if (persistentLogsFile.exists()) {
                persistentLogsFile.readLines().take(250).forEach { line ->
                    if (line.isNotBlank()) cachedLogs.addLast(line)
                }
            }
        } catch (_: Exception) {}
    }

    fun appendPersistentLog(logLine: String) = synchronized(logLock) {
        try {
            ensureLogsLoadedLocked()
            cachedLogs.addFirst(logLine)
            while (cachedLogs.size > 250) {
                cachedLogs.removeLast()
            }
            persistentLogsFile.writeText(cachedLogs.joinToString("\n"))
        } catch (_: Exception) {}
    }

    fun getPersistentLogs(): List<String> = synchronized(logLock) {
        return try {
            ensureLogsLoadedLocked()
            cachedLogs.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearPersistentLogs() = synchronized(logLock) {
        try {
            cachedLogs.clear()
            logsInitialized = true
            if (persistentLogsFile.exists()) persistentLogsFile.delete()
        } catch (_: Exception) {}
    }

    fun saveUserProfileRules(rulesText: String) = synchronized(vaultLock) {
        writeTextSafely(profileFile, rulesText.trim())
    }

    fun getUserProfileRules(): String = synchronized(vaultLock) {
        return try {
            if (profileFile.exists()) profileFile.readText() else ""
        } catch (_: Exception) {
            ""
        }
    }

    fun clearCategoryMemory(category: String) = synchronized(vaultLock) {
        try {
            val dir = getCategoryDir(category)
            dir.deleteRecursively()
        } catch (_: Exception) {}
    }

    private fun writeTextSafely(targetFile: File, content: String) {
        try {
            val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            tempFile.writeText(content)
            if (!tempFile.renameTo(targetFile)) {
                targetFile.writeText(content)
                tempFile.delete()
            }
        } catch (_: Exception) {
            try { targetFile.writeText(content) } catch (_: Exception) {}
        }
    }

    private fun pruneOldFolders(parentDir: File, maxKeep: Int) {
        try {
            val folders = parentDir.listFiles()?.filter { it.isDirectory }
                ?.sortedByDescending { it.lastModified() } ?: return
            if (folders.size > maxKeep) {
                for (oldFolder in folders.drop(maxKeep)) {
                    oldFolder.deleteRecursively()
                }
            }
        } catch (_: Exception) {}
    }
}
