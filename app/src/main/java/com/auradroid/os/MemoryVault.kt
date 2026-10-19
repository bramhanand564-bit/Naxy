package com.auradroid.os

import android.content.Context
import android.graphics.Bitmap
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
    val sourceType: String,         // "HUMAN_TOUCH_FIX" or "VIDEO_FRAME_TRAINING"
    val screenSignature: String,
    val problemDescription: String,
    val solutionRule: String,
    val tapXRatio: Float,
    val tapYRatio: Float,
    val timestamp: String
)

class MemoryVault(private val context: Context) {

    private val rootDir: File = File(context.filesDir, "Memory").apply {
        if (!exists()) mkdirs()
    }

    private val profileFile: File = File(rootDir, "user_profile_rules.json")
    private val persistentLogsFile: File = File(rootDir, "system_action_logs.txt")

    /**
     * App-Specific Folder Name Resolver (Memory/Instagram, Memory/YouTube, Memory/WhatsApp, etc.)
     */
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
        return File(rootDir, category).apply { if (!exists()) mkdirs() }
    }

    /**
     * Saves Before + After Human Touch Fix or Frame-by-Frame Video Training Rule
     * into Memory/<AppCategory>/fixes.json along with WebP screenshots.
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

    /**
     * Loads all Learned Human Touch Fixes & Video-Trained Rules for an App Category
     * so the AI applies them right from Step 1 on future runs!
     */
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
        return result.takeLast(18)
    }

    fun getAllCategoriesSummary(): List<Pair<String, Int>> {
        val folders = rootDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return folders.map { folder ->
            val fixesFile = File(folder, "fixes.json")
            val count = if (fixesFile.exists()) {
                try { JSONArray(fixesFile.readText()).length() } catch (_: Exception) { 0 }
            } else 0
            Pair(folder.name, count)
        }.sortedByDescending { it.second }
    }

    /**
     * Extracts up to 12 sequential high-clarity frames across an uploaded screen-recording video
     * so Gemini 3.8 Flash can inspect every single tap, mistake, and screen transition!
     */
    @Suppress("DEPRECATION")
    fun extractKeyFramesBase64FromVideo(videoUri: Uri, maxFrames: Int = 12): List<String> {
        val frames = mutableListOf<String>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) return emptyList()

            val safeCount = maxFrames.coerceIn(4, 14)
            val stepMs = (durationMs / (safeCount + 1)).coerceAtLeast(400L)
            for (i in 1..safeCount) {
                val timeUs = (stepMs * i) * 1000L
                val bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                val ratio = bmp.width.toFloat() / bmp.height.toFloat()
                val targetH = 960
                val targetW = (targetH * ratio).toInt().coerceAtLeast(360)
                val scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
                val bos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.WEBP, 82, bos)
                frames.add(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        return frames
    }

    /**
     * Persistent System & Action Log History (For the new Logs Section)
     */
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
