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

data class LearnedFixRecord(
    val id: String,
    val appCategory: String,
    val sourceType: String,         // "HUMAN_TOUCH_FIX", "VIDEO_FRAME_TRAINING", "RUN_CHAT_CORRECTION", "VIDEO_CHAT_CORRECTION"
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
    val status: String,             // "SUCCESS", "FAILED", "STOPPED", "RUNNING"
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

    fun resolveCategoryFolder(packageName: String, taskGoal: String): String {
        val combined = "$packageName $taskGoal".lowercase(Locale.ROOT)
        return when {
            combined.contains("instagram") || combined.contains("इंस्टाग्राम") -> "Instagram"
            combined.contains("youtube") || combined.contains("यूट्यूब") -> "YouTube"
            combined.contains("whatsapp") || combined.contains("व्हाट्सएप") -> "WhatsApp"
            combined.contains("chrome") || combined.contains("web") || combined.contains("flow") -> "WebStudio"
            combined.contains("setting") || combined.contains("सेटिंग") -> "PhoneSettings"
            combined.contains("github") -> "GitHub"
            else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }.ifEmpty { "General" }
        }
    }

    private fun getCategoryDir(category: String): File {
        val clean = category.trim().replaceFirstChar { it.uppercase() }.ifEmpty { "General" }
        return File(rootDir, clean).apply { if (!exists()) mkdirs() }
    }

    /**
     * Saves any Learned Rule (from Human Touch, Video Frame Training, Run Review Chat, or Video Chat)
     */
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
    ) {
        val catDir = getCategoryDir(category)
        val fixesFile = File(catDir, "fixes.json")
        val arr = if (fixesFile.exists()) {
            try { JSONArray(fixesFile.readText()) } catch (_: Exception) { JSONArray() }
        } else {
            JSONArray()
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

        val obj = JSONObject().apply {
            put("id", id)
            put("appCategory", category)
            put("sourceType", sourceType)
            put("screenSignature", screenSignature.take(250))
            put("problemDescription", problemDescription)
            put("solutionRule", solutionRule)
            put("tapXRatio", tapXRatio.toDouble())
            put("tapYRatio", tapYRatio.toDouble())
            put("timestamp", SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date()))
        }
        arr.put(obj)
        fixesFile.writeText(arr.toString(2))
    }

    fun recallFixesForApp(category: String): List<LearnedFixRecord> {
        val catDir = getCategoryDir(category)
        val fixesFile = File(catDir, "fixes.json")
        if (!fixesFile.exists()) return emptyList()

        val result = mutableListOf<LearnedFixRecord>()
        try {
            val arr = JSONArray(fixesFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(
                    LearnedFixRecord(
                        id = o.optString("id"),
                        appCategory = o.optString("appCategory", category),
                        sourceType = o.optString("sourceType", "HUMAN_TOUCH_FIX"),
                        screenSignature = o.optString("screenSignature"),
                        problemDescription = o.optString("problemDescription"),
                        solutionRule = o.optString("solutionRule"),
                        tapXRatio = o.optDouble("tapXRatio", -1.0).toFloat(),
                        tapYRatio = o.optDouble("tapYRatio", -1.0).toFloat(),
                        timestamp = o.optString("timestamp")
                    )
                )
            }
        } catch (_: Exception) {}
        return result.takeLast(20)
    }

    fun getAllCategoriesSummary(): List<Pair<String, Int>> {
        val folders = rootDir.listFiles()?.filter {
            it.isDirectory && it.name != "AutomationRuns" && it.name != "VideoSessions"
        } ?: return emptyList()
        return folders.map { folder ->
            val fixesFile = File(folder, "fixes.json")
            val count = if (fixesFile.exists()) {
                try { JSONArray(fixesFile.readText()).length() } catch (_: Exception) { 0 }
            } else 0
            Pair(folder.name, count)
        }.sortedByDescending { it.second }
    }

    // =========================================================================
    // 1. AUTOMATION RUN SESSIONS (Save every run + Screenshots + Interactive Chat)
    // =========================================================================

    @Suppress("DEPRECATION")
    fun saveAutomationRunSession(
        runId: String,
        title: String,
        appCategory: String,
        status: String,
        stepsSummary: List<String>,
        capturedBitmaps: List<Bitmap>,
        existingChat: List<String> = emptyList()
    ): AutomationRunRecord {
        val runFolder = File(runsDir, runId).apply { if (!exists()) mkdirs() }
        val savedPaths = mutableListOf<String>()

        capturedBitmaps.takeLast(8).forEachIndexed { idx, bmp ->
            try {
                val imgFile = File(runFolder, "step_${idx + 1}.webp")
                imgFile.outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.WEBP, 80, out)
                }
                savedPaths.add(imgFile.absolutePath)
            } catch (_: Exception) {}
        }

        val timestamp = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date())
        val metaFile = File(runFolder, "run_meta.json")
        val json = JSONObject().apply {
            put("runId", runId)
            put("title", title)
            put("appCategory", appCategory)
            put("status", status)
            put("timestamp", timestamp)
            put("stepsSummary", JSONArray(stepsSummary))
            put("screenshotPaths", JSONArray(savedPaths))
            put("chatHistory", JSONArray(existingChat))
        }
        metaFile.writeText(json.toString(2))

        return AutomationRunRecord(
            runId = runId,
            title = title,
            appCategory = appCategory,
            status = status,
            timestamp = timestamp,
            stepsSummary = stepsSummary,
            screenshotPaths = savedPaths,
            chatHistory = existingChat
        )
    }

    fun appendChatToAutomationRun(runId: String, sender: String, message: String) {
        val runFolder = File(runsDir, runId)
        val metaFile = File(runFolder, "run_meta.json")
        if (!metaFile.exists()) return
        try {
            val j = JSONObject(metaFile.readText())
            val chatArr = j.optJSONArray("chatHistory") ?: JSONArray()
            chatArr.put("$sender: $message")
            j.put("chatHistory", chatArr)
            metaFile.writeText(j.toString(2))
        } catch (_: Exception) {}
    }

    fun getAllAutomationRuns(): List<AutomationRunRecord> {
        val folders = runsDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }
            ?: return emptyList()
        val list = mutableListOf<AutomationRunRecord>()
        for (folder in folders.take(20)) {
            val metaFile = File(folder, "run_meta.json")
            if (!metaFile.exists()) continue
            try {
                val j = JSONObject(metaFile.readText())
                val steps = mutableListOf<String>()
                val sArr = j.optJSONArray("stepsSummary") ?: JSONArray()
                for (i in 0 until sArr.length()) steps.add(sArr.optString(i))

                val paths = mutableListOf<String>()
                val pArr = j.optJSONArray("screenshotPaths") ?: JSONArray()
                for (i in 0 until pArr.length()) paths.add(pArr.optString(i))

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

    // =========================================================================
    // 2. VIDEO TRAINING SESSIONS (Save extracted frames + Interactive Video Chat)
    // =========================================================================

    @Suppress("DEPRECATION")
    fun createVideoTrainingSession(
        appCategory: String,
        userNote: String,
        videoUri: Uri,
        maxFrames: Int = 12
    ): Pair<VideoTrainingSession, List<String>> {
        val sessionId = "vid_${System.currentTimeMillis()}"
        val sessionFolder = File(videosDir, sessionId).apply { if (!exists()) mkdirs() }
        val framePaths = mutableListOf<String>()
        val framesBase64 = mutableListOf<String>()

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durationMs > 0L) {
                val safeCount = maxFrames.coerceIn(4, 12)
                val stepMs = (durationMs / (safeCount + 1)).coerceAtLeast(400L)
                for (i in 1..safeCount) {
                    val timeUs = (stepMs * i) * 1000L
                    val bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                    val ratio = bmp.width.toFloat() / bmp.height.toFloat()
                    val targetH = 960
                    val targetW = (targetH * ratio).toInt().coerceAtLeast(360)
                    val scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)

                    val frameFile = File(sessionFolder, "frame_$i.webp")
                    frameFile.outputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.WEBP, 82, out)
                    }
                    framePaths.add(frameFile.absolutePath)

                    val bos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.WEBP, 82, bos)
                    framesBase64.add(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
                }
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        val timestamp = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())
        val session = VideoTrainingSession(
            sessionId = sessionId,
            appCategory = appCategory,
            userNote = userNote,
            timestamp = timestamp,
            framePaths = framePaths,
            extractedRulesSummary = emptyList(),
            chatHistory = listOf("User Note: $userNote")
        )
        saveVideoTrainingSessionMeta(session)
        return Pair(session, framesBase64)
    }

    fun saveVideoTrainingSessionMeta(session: VideoTrainingSession) {
        val sessionFolder = File(videosDir, session.sessionId).apply { if (!exists()) mkdirs() }
        val metaFile = File(sessionFolder, "video_meta.json")
        val json = JSONObject().apply {
            put("sessionId", session.sessionId)
            put("appCategory", session.appCategory)
            put("userNote", session.userNote)
            put("timestamp", session.timestamp)
            put("framePaths", JSONArray(session.framePaths))
            put("extractedRulesSummary", JSONArray(session.extractedRulesSummary))
            put("chatHistory", JSONArray(session.chatHistory))
        }
        metaFile.writeText(json.toString(2))
    }

    fun appendChatToVideoSession(sessionId: String, sender: String, message: String, newRuleAdded: String? = null) {
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
            metaFile.writeText(j.toString(2))
        } catch (_: Exception) {}
    }

    fun getAllVideoTrainingSessions(): List<VideoTrainingSession> {
        val folders = videosDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }
            ?: return emptyList()
        val list = mutableListOf<VideoTrainingSession>()
        for (folder in folders.take(15)) {
            val metaFile = File(folder, "video_meta.json")
            if (!metaFile.exists()) continue
            try {
                val j = JSONObject(metaFile.readText())
                val fPaths = mutableListOf<String>()
                val fArr = j.optJSONArray("framePaths") ?: JSONArray()
                for (i in 0 until fArr.length()) fPaths.add(fArr.optString(i))

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

    /**
     * Loads saved WebP files from disk as Base64 so Gemini 3.8 Flash can see them during Run Chat or Video Chat!
     */
    fun loadSavedImagesAsBase64(paths: List<String>, maxImages: Int = 6): List<String> {
        val result = mutableListOf<String>()
        for (path in paths.takeLast(maxImages)) {
            try {
                val f = File(path)
                if (f.exists()) {
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
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        } catch (_: Exception) {
            null
        }
    }

    fun appendPersistentLog(logLine: String) {
        try {
            val existing = if (persistentLogsFile.exists()) persistentLogsFile.readLines() else emptyList()
            val updated = (listOf(logLine) + existing).take(250)
            persistentLogsFile.writeText(updated.joinToString("\n"))
        } catch (_: Exception) {}
    }

    fun getPersistentLogs(): List<String> {
        return try {
            if (persistentLogsFile.exists()) persistentLogsFile.readLines() else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearPersistentLogs() {
        try {
            if (persistentLogsFile.exists()) persistentLogsFile.delete()
        } catch (_: Exception) {}
    }

    fun saveUserProfileRules(rulesText: String) {
        profileFile.writeText(rulesText)
    }

    fun getUserProfileRules(): String {
        return if (profileFile.exists()) profileFile.readText() else ""
    }

    fun clearCategoryMemory(category: String) {
        val dir = getCategoryDir(category)
        dir.deleteRecursively()
    }
}
