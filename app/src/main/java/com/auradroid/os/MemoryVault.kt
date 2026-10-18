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

    /**
     * App-Specific Folder Name Resolver (e.g., Memory/Instagram, Memory/YouTube, Memory/Web)
     */
    fun resolveCategoryFolder(packageName: String, taskGoal: String): String {
        val combined = "$packageName $taskGoal".lowercase(Locale.ROOT)
        return when {
            combined.contains("instagram") -> "Instagram"
            combined.contains("youtube") -> "YouTube"
            combined.contains("whatsapp") -> "WhatsApp"
            combined.contains("chrome") || combined.contains("web") || combined.contains("flow") -> "WebStudio"
            combined.contains("settings") -> "PhoneSettings"
            combined.contains("github") -> "GitHub"
            else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }.ifEmpty { "General" }
        }
    }

    private fun getCategoryDir(category: String): File {
        return File(rootDir, category).apply { if (!exists()) mkdirs() }
    }

    /**
     * Save a Human-Fix or High-Thinking Stuck Resolution into Memory/<AppCategory>/fixes.json
     * Also saves the stuck screenshot for reference.
     */
    fun saveStuckOrHumanFix(
        category: String,
        screenSignature: String,
        problemDescription: String,
        solutionRule: String,
        tapXRatio: Float,
        tapYRatio: Float,
        stuckScreenshot: Bitmap? = null
    ) {
        val catDir = getCategoryDir(category)
        val fixesFile = File(catDir, "fixes.json")
        val arr = if (fixesFile.exists()) {
            try { JSONArray(fixesFile.readText()) } catch (_: Exception) { JSONArray() }
        } else {
            JSONArray()
        }

        val id = "fix_${System.currentTimeMillis()}"
        if (stuckScreenshot != null) {
            try {
                val imgFile = File(catDir, "$id.webp")
                imgFile.outputStream().use { out ->
                    stuckScreenshot.compress(Bitmap.CompressFormat.WEBP, 80, out)
                }
            } catch (_: Exception) {}
        }

        val obj = JSONObject().apply {
            put("id", id)
            put("appCategory", category)
            put("screenSignature", screenSignature.take(250))
            put("problemDescription", problemDescription)
            put("solutionRule", solutionRule)
            put("tapXRatio", tapXRatio.toDouble())
            put("tapYRatio", tapYRatio.toDouble())
            put("timestamp", SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date()))
        }
        arr.put(obj)
        fixesFile.writeText(arr.toString(2))
    }

    /**
     * Triggered ONLY when AI is Stuck or needs App-Specific Learned Guides!
     * Fast normal execution skips this to keep latency near zero.
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
        return result.takeLast(12)
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
     * Extracts 6 key frames across a user-recorded workflow video so Gemini 3.8 / Pro
     * can study every step and store it in the App Folder!
     */
    fun extractKeyFramesBase64FromVideo(videoUri: Uri, maxFrames: Int = 6): List<String> {
        val frames = mutableListOf<String>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) return emptyList()

            val stepMs = (durationMs / (maxFrames + 1)).coerceAtLeast(500L)
            for (i in 1..maxFrames) {
                val timeUs = (stepMs * i) * 1000L
                val bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                val scaled = Bitmap.createScaledBitmap(bmp, 540, 960, true)
                val bos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.WEBP, 80, bos)
                frames.add(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        return frames
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
