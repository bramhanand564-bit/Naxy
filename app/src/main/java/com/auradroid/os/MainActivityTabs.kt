package com.auradroid.os

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Normalizes any time string like "3:36" or "3.36" into strict 24-Hr "03:36" format
 * so the 24/7 background clock matcher NEVER misses a trigger!
 */
internal fun normalizeTimeHm24(rawTime: String, defaultHm: String = "18:00"): String {
    val clean = rawTime.trim().replace(".", ":")
    val match = Regex("(\\d{1,2}):(\\d{2})").find(clean) ?: return defaultHm
    val h = (match.groupValues[1].toIntOrNull() ?: 18).coerceIn(0, 23)
    val m = (match.groupValues[2].toIntOrNull() ?: 0).coerceIn(0, 59)
    return String.format(Locale.US, "%02d:%02d", h, m)
}

internal fun addMinutesToHm24(baseHm: String, addMinutes: Int): String {
    val norm = normalizeTimeHm24(baseHm)
    val parts = norm.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val total = (h * 60 + m + addMinutes + 24 * 60) % (24 * 60)
    return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
}

internal fun loadN8nWorkflowsFromPrefs(prefs: SharedPreferences): MutableList<N8nScheduleWorkflow> {
    val raw = prefs.getString("n8n_time_workflows_json", "") ?: ""
    val list = mutableListOf<N8nScheduleWorkflow>()
    if (raw.isNotBlank()) {
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(N8nScheduleWorkflow.fromJson(obj))
            }
        } catch (_: Exception) {}
    }
    if (list.isEmpty()) {
        val defaultCreate = normalizeTimeHm24(prefs.getString("flow_create_time_hm", "18:00") ?: "18:00")
        val defaultUpload = normalizeTimeHm24(prefs.getString("folder_upload_time_hm", "19:00") ?: "19:00")
        val savedFlowUrl = prefs.getString("saved_flow_url", "https://flow.google.com/?pli=1") ?: "https://flow.google.com/?pli=1"
        val isStory = prefs.getBoolean("flow_story_mode", true)
        list.add(
            N8nScheduleWorkflow(
                id = "wf_default_1",
                triggerTimeHm = defaultCreate,
                enabled = true,
                sourceNodeType = "GOOGLE_FLOW",
                sourceWebUrl = savedFlowUrl,
                customStoryPrompt = "",
                storyContinuationMode = isStory,
                targetActionNode = if (defaultCreate == defaultUpload) "YOUTUBE_DIRECT" else "DELAYED_UPLOAD",
                delayedUploadTimeHm = defaultUpload,
                linkedExtraSteps = mutableListOf("💾 Save to AutoVideos Folder", "📺 YouTube Shorts Auto-Title")
            )
        )
    }
    return list
}

internal fun saveN8nWorkflowsToPrefs(prefs: SharedPreferences, workflows: List<N8nScheduleWorkflow>) {
    val arr = JSONArray()
    workflows.forEach { wf ->
        wf.triggerTimeHm = normalizeTimeHm24(wf.triggerTimeHm)
        if (wf.delayedUploadTimeHm.isNotBlank()) {
            wf.delayedUploadTimeHm = normalizeTimeHm24(wf.delayedUploadTimeHm)
        }
        arr.put(wf.toJson())
    }
    val editor = prefs.edit().putString("n8n_time_workflows_json", arr.toString())
    // Sync primary workflow with legacy keys so HUD & Stage 1/2 stay in sync
    val firstActive = workflows.firstOrNull { it.enabled } ?: workflows.firstOrNull()
    if (firstActive != null) {
        val createHm = normalizeTimeHm24(firstActive.triggerTimeHm)
        val uploadHm = if (firstActive.targetActionNode == "DELAYED_UPLOAD" && firstActive.delayedUploadTimeHm.isNotBlank()) {
            normalizeTimeHm24(firstActive.delayedUploadTimeHm)
        } else {
            createHm
        }
        editor.putString("flow_create_time_hm", createHm)
        editor.putString("folder_upload_time_hm", uploadHm)
        editor.putString("schedule_time_hm", uploadHm)
    }
    editor.apply()
}

internal fun MainActivity.buildTab2AutomationsAndScheduler(): View {
    val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
    val scroll = ScrollView(this)
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(28))
    }
    val defaultDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    // Forward references so AI Configurator & n8n Builder can update all inputs live
    lateinit var flowCreateTimeInput: EditText
    lateinit var folderUploadTimeInput: EditText
    lateinit var enableSchedBtn: TextView
    lateinit var storyModeBtn: TextView
    var refreshN8nWorkflowCardsUi: () -> Unit = {}

    // =========================================================================
    // 1️⃣ TASK 1: GOOGLE FLOW AI VIDEO STUDIO (BACKGROUND OR REAL CHROME APP)
    // =========================================================================
    val flowCard = createSectionCard("1️⃣ Stage 1: Google Flow से वीडियो बनाएं और फोल्डर में सेव करें")

    var flowExecMode = prefs.getString("flow_execution_mode", "BACKGROUND") ?: "BACKGROUND"
    val flowModeSwitchBtn = createPrimaryButton(
        if (flowExecMode == "BACKGROUND") "🌐 Flow Mode: Background Browser (पीछे चुपचाप बनेगा, Emoji टैप करने पर दिखेगा)"
        else "📱 Flow Mode: Real Chrome App (फोन का असली Chrome खोलकर सामने बनाएगा)",
        if (flowExecMode == "BACKGROUND") accentRoyalBlue else accentPurple
    ) { btn ->
        flowExecMode = if (flowExecMode == "BACKGROUND") "LIVE_PHONE" else "BACKGROUND"
        prefs.edit().putString("flow_execution_mode", flowExecMode).apply()
        btn.text = if (flowExecMode == "BACKGROUND") {
            "🌐 Flow Mode: Background Browser (पीछे चुपचाप बनेगा, Emoji टैप करने पर दिखेगा)"
        } else {
            "📱 Flow Mode: Real Chrome App (फोन का असली Chrome खोलकर सामने बनाएगा)"
        }
        btn.background = roundedBox(if (flowExecMode == "BACKGROUND") accentRoyalBlue else accentPurple, dp(12))
    }

    var isStoryMode = prefs.getBoolean("flow_story_mode", true)
    storyModeBtn = createPrimaryButton(
        if (isStoryMode) "📖 Video Type: Step-by-Step Story Base (कहानी के भाग 1, 2, 3 आगे बढ़ते रहेंगे)"
        else "🎬 Video Type: Single Exact Prompt Video (जो प्रॉम्प्ट देंगे वही बनेगा)",
        if (isStoryMode) Color.parseColor("#1E3A8A") else Color.parseColor("#262B36")
    ) { btn ->
        isStoryMode = !isStoryMode
        prefs.edit().putBoolean("flow_story_mode", isStoryMode).apply()
        btn.text = if (isStoryMode) {
            "📖 Video Type: Step-by-Step Story Base (कहानी के भाग 1, 2, 3 आगे बढ़ते रहेंगे)"
        } else {
            "🎬 Video Type: Single Exact Prompt Video (जो प्रॉम्प्ट देंगे वही बनेगा)"
        }
        btn.background = roundedBox(if (isStoryMode) Color.parseColor("#1E3A8A") else Color.parseColor("#262B36"), dp(12))
    }

    val topicInput = createCleanInput(
        prefs.getString("flow_topic", "Young Indian girl dancing in traditional attire")
            ?: "Young Indian girl dancing in traditional attire",
        "Video Prompt / Story Topic"
    )
    val creditLimitInput = createCleanInput(
        prefs.getInt("flow_daily_videos", 1).toString(),
        "कितनी वीडियो बनानी हैं? (1, 2, या 3)"
    )
    flowDownloadFolderEditText = createCleanInput(
        prefs.getString("custom_download_folder", defaultDownloadDir) ?: defaultDownloadDir,
        "किस फोल्डर में वीडियो डाउनलोड होकर सेव होगा?"
    )

    val pickFlowFolderBtn = createPrimaryButton("📂 सेव करने का फोल्डर चुनें (Choose Save Folder)", Color.parseColor("#334155")) {
        if (requestStoragePermissionsOnDemand()) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), MainActivity.REQ_PICK_FLOW_SAVE_FOLDER)
        }
    }

    val customDescriptionInput = createCleanInput(
        prefs.getString("custom_video_description", "") ?: "",
        "📝 अपलोड के लिए Description / Hashtags (खाली छोड़ने पर AI खुद वायरल Description लिखेगा)"
    ).apply { minLines = 2 }

    val aiTaskHelperBtn = createPrimaryButton("🤖 AI से बात करके टॉपिक, स्टोरी और टाइम सेट करें", accentPurple) {
        // Save whatever story the user already typed in topicInput BEFORE opening the voice dialog!
        val currentTypedTopic = topicInput.text.toString().trim()
        if (currentTypedTopic.isNotEmpty()) {
            prefs.edit().putString("flow_topic", currentTypedTopic).apply()
        }
        showMiniTaskAiAssistantDialog(
            topicField = topicInput,
            countField = creditLimitInput,
            onSettingsUpdated = { createHm, uploadHm, storyEnabled ->
                flowCreateTimeInput.setText(createHm)
                folderUploadTimeInput.setText(uploadHm)
                if (storyEnabled) {
                    isStoryMode = true
                    storyModeBtn.text = "📖 Video Type: Step-by-Step Story Base (कहानी के भाग 1, 2, 3 आगे बढ़ते रहेंगे)"
                    storyModeBtn.background = roundedBox(Color.parseColor("#1E3A8A"), dp(12))
                }
                enableSchedBtn.text = "✅ 24/7 Daily Auto-Scheduler: ON"
                enableSchedBtn.background = roundedBox(accentEmerald, dp(12))
                refreshN8nWorkflowCardsUi()
            }
        )
    }

    val createAndSaveOnlyBtn = createPrimaryButton("📥 अभी सिर्फ Flow से वीडियो बनाकर फोल्डर में सेव करें", accentRoyalBlue) {
        val rawTopic = topicInput.text.toString().trim()
        val count = creditLimitInput.text.toString().toIntOrNull()?.coerceIn(1, 5) ?: 1
        val customSaveDir = flowDownloadFolderEditText.text.toString().trim().ifEmpty { defaultDownloadDir }
        val customDesc = customDescriptionInput.text.toString().trim()
        val finalTopic = if (isStoryMode) "Step-by-step continuous story: $rawTopic" else rawTopic
        prefs.edit()
            .putString("flow_topic", rawTopic)
            .putInt("flow_daily_videos", count)
            .putString("custom_download_folder", customSaveDir)
            .putString("yt_folder_path", customSaveDir)
            .putString("custom_video_description", customDesc)
            .apply()
        try { folderPathEditText.setText(customSaveDir) } catch (_: Exception) {}
        AutoTouchService.instance?.startGoogleFlowToYouTubePipeline(
            topic = finalTopic,
            maxVideosToCreate = count,
            uploadImmediatelyAfterCreate = false
        ) ?: Toast.makeText(this, "पहले Accessibility Service ऑन करें!", Toast.LENGTH_SHORT).show()
    }

    val runFlowAndUploadNowBtn = createPrimaryButton("🚀 अभी Flow से वीडियो बनाएं + तुरंत अपलोड भी करें", accentEmerald) {
        val rawTopic = topicInput.text.toString().trim()
        val count = creditLimitInput.text.toString().toIntOrNull()?.coerceIn(1, 5) ?: 1
        val customSaveDir = flowDownloadFolderEditText.text.toString().trim().ifEmpty { defaultDownloadDir }
        val customDesc = customDescriptionInput.text.toString().trim()
        val finalTopic = if (isStoryMode) "Step-by-step continuous story: $rawTopic" else rawTopic
        prefs.edit()
            .putString("flow_topic", rawTopic)
            .putInt("flow_daily_videos", count)
            .putString("custom_download_folder", customSaveDir)
            .putString("yt_folder_path", customSaveDir)
            .putString("custom_video_description", customDesc)
            .apply()
        try { folderPathEditText.setText(customSaveDir) } catch (_: Exception) {}
        AutoTouchService.instance?.startGoogleFlowToYouTubePipeline(
            topic = finalTopic,
            maxVideosToCreate = count,
            uploadImmediatelyAfterCreate = true
        ) ?: Toast.makeText(this, "पहले Accessibility Service ऑन करें!", Toast.LENGTH_SHORT).show()
    }

    flowCard.addView(flowModeSwitchBtn)
    flowCard.addView(spacer(dp(8)))
    flowCard.addView(storyModeBtn)
    flowCard.addView(spacer(dp(8)))
    flowCard.addView(topicInput)
    flowCard.addView(spacer(dp(8)))
    flowCard.addView(creditLimitInput)
    flowCard.addView(spacer(dp(8)))
    flowCard.addView(flowDownloadFolderEditText)
    flowCard.addView(spacer(dp(6)))
    flowCard.addView(pickFlowFolderBtn)
    flowCard.addView(spacer(dp(8)))
    flowCard.addView(customDescriptionInput)
    flowCard.addView(spacer(dp(10)))
    flowCard.addView(buildMultiPlatformLinkerBox(prefs))
    flowCard.addView(spacer(dp(10)))
    flowCard.addView(aiTaskHelperBtn)
    flowCard.addView(spacer(dp(10)))
    flowCard.addView(createAndSaveOnlyBtn)
    flowCard.addView(spacer(dp(8)))
    flowCard.addView(runFlowAndUploadNowBtn)

    // =========================================================================
    // 2️⃣ TASK 2: FOLDER VIDEO -> YOUTUBE & INSTAGRAM UPLOADER (TITLE + DESC)
    // =========================================================================
    val ytCard = createSectionCard("2️⃣ Stage 2: फोल्डर से वीडियो उठाकर अपलोड करें (Title + Description)")

    var folderUploadBgMode = prefs.getBoolean("bg_youtube_upload", false)
    val folderModeSwitchBtn = createPrimaryButton(
        if (folderUploadBgMode) "🌐 YouTube Upload Mode: Background Browser (पीछे चुपचाप अपलोड होगा)"
        else "📱 YouTube Upload Mode: Real YouTube App (असली YouTube ऐप खोलकर अपलोड करेगा)",
        if (folderUploadBgMode) accentRoyalBlue else accentPurple
    ) { btn ->
        folderUploadBgMode = !folderUploadBgMode
        prefs.edit().putBoolean("bg_youtube_upload", folderUploadBgMode).apply()
        btn.text = if (folderUploadBgMode) {
            "🌐 YouTube Upload Mode: Background Browser (पीछे चुपचाप अपलोड होगा)"
        } else {
            "📱 YouTube Upload Mode: Real YouTube App (असली YouTube ऐप खोलकर अपलोड करेगा)"
        }
        btn.background = roundedBox(if (folderUploadBgMode) accentRoyalBlue else accentPurple, dp(12))
    }

    var instaUploadBgMode = prefs.getBoolean("bg_insta_upload", false)
    val instaModeSwitchBtn = createPrimaryButton(
        if (instaUploadBgMode) "🌐 Instagram Upload Mode: Background Browser (पीछे ब्राउज़र में अपलोड होगा)"
        else "📱 Instagram Upload Mode: Real Instagram App (असली Insta ऐप खोलकर रील डालेगा)",
        if (instaUploadBgMode) accentRoyalBlue else Color.parseColor("#DB2777")
    ) { btn ->
        instaUploadBgMode = !instaUploadBgMode
        prefs.edit().putBoolean("bg_insta_upload", instaUploadBgMode).apply()
        btn.text = if (instaUploadBgMode) {
            "🌐 Instagram Upload Mode: Background Browser (पीछे ब्राउज़र में अपलोड होगा)"
        } else {
            "📱 Instagram Upload Mode: Real Instagram App (असली Insta ऐप खोलकर रील डालेगा)"
        }
        btn.background = roundedBox(if (instaUploadBgMode) accentRoyalBlue else Color.parseColor("#DB2777"), dp(12))
    }

    folderPathEditText = createCleanInput(
        prefs.getString("yt_folder_path", prefs.getString("custom_download_folder", defaultDownloadDir)) ?: defaultDownloadDir,
        "Video Folder Path (जहाँ से वीडियो उठाकर अपलोड करना है)"
    )
    val browseFolderBtn = createPrimaryButton("📂 अपलोड फोल्डर चुनें (Browse Upload Folder)", accentRoyalBlue) {
        if (requestStoragePermissionsOnDemand()) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), MainActivity.REQ_PICK_FOLDER_TREE)
        }
    }

    var pickOldest = prefs.getBoolean("yt_pick_oldest", true)
    val orderToggleBtn = createPrimaryButton(
        if (pickOldest) "🔃 Order: Oldest Video First (पुरानी पहले)" else "🔃 Order: Newest Video First (नई बनी वीडियो पहले)",
        Color.parseColor("#262B36")
    ) { btn ->
        pickOldest = !pickOldest
        prefs.edit().putBoolean("yt_pick_oldest", pickOldest).apply()
        btn.text = if (pickOldest) "🔃 Order: Oldest Video First (पुरानी पहले)" else "🔃 Order: Newest Video First (नई बनी वीडियो पहले)"
    }

    val runYtNowBtn = createPrimaryButton("🚀 अभी फोल्डर से 1 वीडियो (Title + Description के साथ) अपलोड करें", accentEmerald) {
        if (!requestStoragePermissionsOnDemand()) return@createPrimaryButton
        val path = folderPathEditText.text.toString().trim()
        val customDesc = customDescriptionInput.text.toString().trim()
        prefs.edit()
            .putString("yt_folder_path", path)
            .putString("custom_video_description", customDesc)
            .apply()
        AutoTouchService.instance?.startYouTubeFolderAutoUpload(path, pickOldest)
            ?: Toast.makeText(this, "पहले Accessibility Service ऑन करें!", Toast.LENGTH_SHORT).show()
    }

    val pickSingleVideoBtn = createPrimaryButton("🎬 सीधे गैलरी से 1 वीडियो चुनें और अभी अपलोड करें", Color.parseColor("#0D9488")) {
        prefs.edit().putString("custom_video_description", customDescriptionInput.text.toString().trim()).apply()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        startActivityForResult(intent, MainActivity.REQ_PICK_SINGLE_VIDEO_FOR_YT)
    }

    ytCard.addView(folderModeSwitchBtn)
    ytCard.addView(spacer(dp(8)))
    ytCard.addView(instaModeSwitchBtn)
    ytCard.addView(spacer(dp(8)))
    ytCard.addView(folderPathEditText)
    ytCard.addView(spacer(dp(8)))
    ytCard.addView(browseFolderBtn)
    ytCard.addView(spacer(dp(8)))
    ytCard.addView(orderToggleBtn)
    ytCard.addView(spacer(dp(10)))
    ytCard.addView(runYtNowBtn)
    ytCard.addView(spacer(dp(8)))
    ytCard.addView(pickSingleVideoBtn)

    // =========================================================================
    // 3️⃣ 24/7 AUTO SCHEDULE + 🔗 n8n-STYLE MULTI-TIME VISUAL WORKFLOW BUILDER
    // =========================================================================
    val schedCard = createSectionCard("⏰ 24/7 Auto Schedule & 🔗 n8n Multi-Time Workflow Builder")

    schedCard.addView(TextView(this).apply {
        text = "1️⃣ मुख्य टाइम: कितने बजे ऑटोमैटिक Flow से वीडियो बनकर फोल्डर में सेव हो? (24-Hr, जैसे 03:36 या 18:00):"
        setTextColor(textPureWhite)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, 0, 0, dp(4))
    })
    flowCreateTimeInput = createCleanInput(
        normalizeTimeHm24(prefs.getString("flow_create_time_hm", "18:00") ?: "18:00"),
        "Flow Video Create Time (जैसे 03:36 या 18:00)"
    )

    schedCard.addView(flowCreateTimeInput)
    schedCard.addView(spacer(dp(10)))

    schedCard.addView(TextView(this).apply {
        text = "2️⃣ मुख्य अपलोड टाइम: कितने बजे वीडियो अपलोड हो? (तुरंत अपलोड के लिए दोनों टाइम सेम रखें):"
        setTextColor(textPureWhite)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, 0, 0, dp(4))
    })
    folderUploadTimeInput = createCleanInput(
        normalizeTimeHm24(prefs.getString("folder_upload_time_hm", prefs.getString("schedule_time_hm", "19:00")) ?: "19:00"),
        "Folder Video Upload Time (जैसे 03:36 या 19:00)"
    )
    schedCard.addView(folderUploadTimeInput)
    schedCard.addView(spacer(dp(12)))

    // -------------------------------------------------------------------------
    // 🔗 n8n VISUAL WORKFLOW BUILDER CONTAINER (+ Add Time -> + Add Link/Target)
    // -------------------------------------------------------------------------
    val n8nHeaderBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundedBox(Color.parseColor("#111827"), dp(12), accentRoyalBlue)
    }

    n8nHeaderBox.addView(TextView(this).apply {
        text = "🔗 n8n Visual Time Workflows (हर टाइम पर ➕ से लिंक, वेबसाइट और YouTube/Insta जोड़ें):"
        setTextColor(Color.parseColor("#38BDF8"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(6))
    })

    val n8nListLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    n8nHeaderBox.addView(n8nListLayout)

    refreshN8nWorkflowCardsUi = {
        n8nListLayout.removeAllViews()
        val workflows = loadN8nWorkflowsFromPrefs(prefs)
        workflows.forEachIndexed { index, wf ->
            val wfCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBox(Color.parseColor("#1E293B"), dp(10), if (wf.enabled) accentEmerald else borderSubtle)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }

            // Top Row: Node 1 (Time Trigger) + ON/OFF + Run + Delete
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(TextView(this).apply {
                text = "⏰ Time #${index + 1}: ${wf.triggerTimeHm}"
                setTextColor(textPureWhite)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    showEditWorkflowTimeDialog(wf) {
                        val updated = loadN8nWorkflowsFromPrefs(prefs).map { if (it.id == wf.id) wf else it }
                        saveN8nWorkflowsToPrefs(prefs, updated)
                        flowCreateTimeInput.setText(normalizeTimeHm24(wf.triggerTimeHm))
                        refreshN8nWorkflowCardsUi()
                    }
                }
            })

            // Toggle ON/OFF chip
            topRow.addView(TextView(this).apply {
                text = if (wf.enabled) "✅ ON" else "⚪ OFF"
                setTextColor(textPureWhite)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = roundedBox(if (wf.enabled) accentEmerald else Color.parseColor("#475569"), dp(6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    wf.enabled = !wf.enabled
                    val updated = loadN8nWorkflowsFromPrefs(prefs).map { if (it.id == wf.id) wf else it }
                    saveN8nWorkflowsToPrefs(prefs, updated)
                    refreshN8nWorkflowCardsUi()
                }
            })

            // Test Run Now button
            topRow.addView(TextView(this).apply {
                text = "▶️ Run"
                setTextColor(textPureWhite)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = roundedBox(accentRoyalBlue, dp(6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    val rawTopic = topicInput.text.toString().trim()
                    if (rawTopic.isNotEmpty()) {
                        prefs.edit().putString("flow_topic", rawTopic).apply()
                    }
                    executeN8nWorkflowNow(wf, topicInput.text.toString().trim())
                }
            })

            // Delete Workflow button
            if (workflows.size > 1) {
                topRow.addView(TextView(this).apply {
                    text = "🗑️"
                    setTextColor(Color.parseColor("#F87171"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    setOnClickListener {
                        val updated = loadN8nWorkflowsFromPrefs(prefs).filterNot { it.id == wf.id }
                        saveN8nWorkflowsToPrefs(prefs, updated)
                        refreshN8nWorkflowCardsUi()
                    }
                })
            }
            wfCard.addView(topRow)

            // Visual n8n Node 2: Source / Website Link + Story Mode
            val sourceLabel = when (wf.sourceNodeType) {
                "GOOGLE_FLOW" -> "🌊 Google Flow (${wf.sourceWebUrl.take(36)})"
                "CUSTOM_WEB_URL" -> "🌐 Custom Link (${wf.sourceWebUrl.take(38)})"
                else -> "📂 Folder Video Only (/Download/AutoVideos)"
            }
            val storyBadge = if (wf.storyContinuationMode) "📖 Story: Auto Next Episode (कहानी आगे बढ़ेगी)" else "🎬 Single Prompt"
            wfCard.addView(TextView(this).apply {
                text = "   ⬇️ [Node 2 - Source Link]\n   🔗 $sourceLabel\n   ✨ $storyBadge"
                setTextColor(Color.parseColor("#93C5FD"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, dp(6), 0, dp(4))
                setOnClickListener {
                    showAddOrEditNodeInWorkflowDialog(wf, prefs) {
                        refreshN8nWorkflowCardsUi()
                    }
                }
            })

            // Visual n8n Node 3: Target Upload Action + Extra Linked Nodes
            val targetLabel = when (wf.targetActionNode) {
                "YOUTUBE_DIRECT" -> "📺 YouTube Shorts (बनते ही तुरंत डायरेक्ट अपलोड)"
                "INSTA_DIRECT" -> "📸 Instagram Reels (बनते ही तुरंत रील अपलोड)"
                "BOTH_DIRECT" -> "📺 YouTube + 📸 Instagram (दोनों पर तुरंत अपलोड)"
                "SAVE_FOLDER_ONLY" -> "💾 Only Save in Folder (सिर्फ फोल्डर में सेव)"
                else -> "⏰ फोल्डर से ${wf.delayedUploadTimeHm.ifBlank { wf.triggerTimeHm }} बजे YouTube पर अपलोड"
            }
            val extraChainText = if (wf.linkedExtraSteps.isNotEmpty()) {
                "\n   🔗 + Linked: " + wf.linkedExtraSteps.joinToString(" ➔ ")
            } else ""

            wfCard.addView(TextView(this).apply {
                text = "   ⬇️ [Node 3 - Target Action]\n   🚀 $targetLabel$extraChainText"
                setTextColor(accentEmerald)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, dp(2), 0, dp(8))
                setOnClickListener {
                    showAddOrEditNodeInWorkflowDialog(wf, prefs) {
                        refreshN8nWorkflowCardsUi()
                    }
                }
            })

            // Inside-Workflow [➕ लिंक / स्टेप जोड़ें] button (n8n Node Connector!)
            val addInsideNodeBtn = createPrimaryButton(
                "➕ इस टाइम (${wf.triggerTimeHm}) में लिंक / YouTube / स्टेप जोड़ें (+ Node)",
                Color.parseColor("#334155")
            ) {
                showAddOrEditNodeInWorkflowDialog(wf, prefs) {
                    val listNow = loadN8nWorkflowsFromPrefs(prefs)
                    val first = listNow.firstOrNull()
                    if (first != null) {
                        flowCreateTimeInput.setText(normalizeTimeHm24(first.triggerTimeHm))
                        folderUploadTimeInput.setText(
                            if (first.targetActionNode == "DELAYED_UPLOAD" && first.delayedUploadTimeHm.isNotBlank())
                                normalizeTimeHm24(first.delayedUploadTimeHm)
                            else normalizeTimeHm24(first.triggerTimeHm)
                        )
                    }
                    refreshN8nWorkflowCardsUi()
                }
            }
            wfCard.addView(addInsideNodeBtn)
            n8nListLayout.addView(wfCard)
        }
    }

    val addNewTimeWorkflowBtn = createPrimaryButton("➕ नया टाइम वर्कफ़्लो जोड़ें (+ Add New Time & Link Nodes)", accentPurple) {
        showCreateNewTimeWorkflowDialog(prefs) { newWf ->
            flowCreateTimeInput.setText(normalizeTimeHm24(newWf.triggerTimeHm))
            folderUploadTimeInput.setText(
                if (newWf.targetActionNode == "DELAYED_UPLOAD" && newWf.delayedUploadTimeHm.isNotBlank())
                    normalizeTimeHm24(newWf.delayedUploadTimeHm)
                else normalizeTimeHm24(newWf.triggerTimeHm)
            )
            prefs.edit().putBoolean("scheduler_enabled", true).apply()
            enableSchedBtn.text = "✅ 24/7 Daily Auto-Scheduler: ON"
            enableSchedBtn.background = roundedBox(accentEmerald, dp(12))
            refreshN8nWorkflowCardsUi()
        }
    }

    n8nHeaderBox.addView(addNewTimeWorkflowBtn)
    schedCard.addView(n8nHeaderBox)
    schedCard.addView(spacer(dp(10)))

    var onlyWhenIdle = prefs.getBoolean("run_only_when_idle", false)
    val idleModeToggleBtn = createPrimaryButton(
        if (onlyWhenIdle) "🌙 Smart Trigger: ON (जब मैं फोन न चला रहा हूँ तभी चलाएं)"
        else "⌚ Smart Trigger: Fixed Time (ठीक समय पर अपने-आप चलेगा)",
        if (onlyWhenIdle) accentPurple else Color.parseColor("#262B36")
    ) { btn ->
        onlyWhenIdle = !onlyWhenIdle
        prefs.edit().putBoolean("run_only_when_idle", onlyWhenIdle).apply()
        btn.text = if (onlyWhenIdle) {
            "🌙 Smart Trigger: ON (जब मैं फोन न चला रहा हूँ तभी चलाएं)"
        } else {
            "⌚ Smart Trigger: Fixed Time (ठीक समय पर अपने-आप चलेगा)"
        }
        btn.background = roundedBox(if (onlyWhenIdle) accentPurple else Color.parseColor("#262B36"), dp(12))
    }

    val snoozeRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, dp(4))
    }
    snoozeRow.addView(createPrimaryButton("⏳ Skip 10 Min", accentAmber) {
        prefs.edit().putLong("snooze_until_ms", System.currentTimeMillis() + 10 * 60_000L).apply()
        Toast.makeText(this, "⏳ 10 मिनट के लिए Skip कर दिया गया!", Toast.LENGTH_SHORT).show()
    }.apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
    })

    snoozeRow.addView(createPrimaryButton("💾 Save Schedule Times", accentRoyalBlue) {
        val tCreate = normalizeTimeHm24(flowCreateTimeInput.text.toString().trim().ifEmpty { "18:00" })
        val tUpload = normalizeTimeHm24(folderUploadTimeInput.text.toString().trim().ifEmpty { tCreate }, tCreate)
        flowCreateTimeInput.setText(tCreate)
        folderUploadTimeInput.setText(tUpload)
        val saveDir = flowDownloadFolderEditText.text.toString().trim().ifEmpty { defaultDownloadDir }
        val currentTopic = topicInput.text.toString().trim()
        prefs.edit()
            .putString("flow_create_time_hm", tCreate)
            .putString("folder_upload_time_hm", tUpload)
            .putString("schedule_time_hm", tUpload)
            .putString("flow_topic", currentTopic)
            .putBoolean("scheduler_enabled", true)
            .putString("custom_download_folder", saveDir)
            .putString("yt_folder_path", folderPathEditText.text.toString().trim().ifEmpty { saveDir })
            .putString("custom_video_description", customDescriptionInput.text.toString().trim())
            .apply()

        // Also sync with first n8n workflow card
        val wfs = loadN8nWorkflowsFromPrefs(prefs)
        if (wfs.isNotEmpty()) {
            wfs[0].triggerTimeHm = tCreate
            wfs[0].targetActionNode = if (tCreate == tUpload) "YOUTUBE_DIRECT" else "DELAYED_UPLOAD"
            wfs[0].delayedUploadTimeHm = tUpload
            wfs[0].enabled = true
            saveN8nWorkflowsToPrefs(prefs, wfs)
        }
        enableSchedBtn.text = "✅ 24/7 Daily Auto-Scheduler: ON"
        enableSchedBtn.background = roundedBox(accentEmerald, dp(12))
        refreshN8nWorkflowCardsUi()

        Toast.makeText(
            this,
            "✅ सेव हो गया!\n🎬 Flow वीडियो बनेगा: $tCreate बजे\n📤 अपलोड होगा: $tUpload बजे",
            Toast.LENGTH_LONG
        ).show()
    }.apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })

    var schedEnabled = prefs.getBoolean("scheduler_enabled", true)
    enableSchedBtn = createPrimaryButton(
        if (schedEnabled) "✅ 24/7 Daily Auto-Scheduler: ON" else "⚪ 24/7 Daily Auto-Scheduler: OFF (Tap to Enable)",
        if (schedEnabled) accentEmerald else Color.parseColor("#475569")
    ) { btn ->
        schedEnabled = !schedEnabled
        val tCreate = normalizeTimeHm24(flowCreateTimeInput.text.toString().trim().ifEmpty { "18:00" })
        val tUpload = normalizeTimeHm24(folderUploadTimeInput.text.toString().trim().ifEmpty { tCreate }, tCreate)
        flowCreateTimeInput.setText(tCreate)
        folderUploadTimeInput.setText(tUpload)
        val saveDir = flowDownloadFolderEditText.text.toString().trim().ifEmpty { defaultDownloadDir }
        prefs.edit()
            .putBoolean("scheduler_enabled", schedEnabled)
            .putString("flow_create_time_hm", tCreate)
            .putString("folder_upload_time_hm", tUpload)
            .putString("schedule_time_hm", tUpload)
            .putString("flow_topic", topicInput.text.toString().trim())
            .putString("custom_download_folder", saveDir)
            .putString("yt_folder_path", folderPathEditText.text.toString().trim().ifEmpty { saveDir })
            .putString("custom_video_description", customDescriptionInput.text.toString().trim())
            .apply()
        btn.text = if (schedEnabled) "✅ 24/7 Daily Auto-Scheduler: ON" else "⚪ 24/7 Daily Auto-Scheduler: OFF (Tap to Enable)"
        btn.background = roundedBox(if (schedEnabled) accentEmerald else Color.parseColor("#475569"), dp(12))
    }

    refreshN8nWorkflowCardsUi()

    schedCard.addView(idleModeToggleBtn)
    schedCard.addView(snoozeRow)
    schedCard.addView(spacer(dp(10)))
    schedCard.addView(enableSchedBtn)

    // =========================================================================
    // 4️⃣ MULTI-WEBSITE FREE AI VIDEO HUB
    // =========================================================================
    val freeHubCard = createSectionCard("3️⃣ Multi-Website Free AI Video Hub")
    val freeSiteInput = createCleanInput(
        prefs.getString("free_hub_site", "https://gemini.google.com") ?: "https://gemini.google.com",
        "Free AI Website URL or App"
    )

    val siteChipsScroll = HorizontalScrollView(this)
    val siteChipsRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, dp(8))
    }
    listOf(
        "✨ Gemini" to "https://gemini.google.com",
        "🎨 Qwen Image" to "https://chat.qwen.ai",
        "🎥 Synthesia Free" to "https://www.synthesia.io/features/text-to-video",
        "🎬 Hailuo / MiniMax" to "https://hailuoai.video",
        "🎥 Kling / Wan AI" to "https://klingai.com",
        "🌊 Google Flow" to "https://labs.google/fx/tools/flow"
    ).forEach { (label, url) ->
        siteChipsRow.addView(TextView(this).apply {
            text = label
            setTextColor(textPureWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBox(Color.parseColor("#262B36"), dp(8), borderSubtle)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener {
                freeSiteInput.setText(url)
                prefs.edit().putString("free_hub_site", url).apply()
            }
        })
    }
    siteChipsScroll.addView(siteChipsRow)

    var freeHubExecMode = prefs.getString("free_hub_exec_mode", "BACKGROUND") ?: "BACKGROUND"
    val freeHubModeBtn = createPrimaryButton(
        when (freeHubExecMode) {
            "BACKGROUND" -> "🌐 Mode: Background Browser (पीछे ब्राउज़र में चलेगा, Emoji टैप पर दिखेगा)"
            "LIVE_WHEN_IDLE" -> "🌙 Mode: Real App When Phone is Free (जब फोन फ्री हो तब असली ऐप चलाएगा)"
            else -> "📱 Mode: Real Chrome / App Now (सामने असली ऐप में चलाएगा)"
        },
        Color.parseColor("#1E3A8A")
    ) { btn ->
        freeHubExecMode = when (freeHubExecMode) {
            "BACKGROUND" -> "LIVE_WHEN_IDLE"
            "LIVE_WHEN_IDLE" -> "LIVE_FIXED_TIME"
            else -> "BACKGROUND"
        }
        prefs.edit()
            .putString("free_hub_exec_mode", freeHubExecMode)
            .putBoolean("run_only_when_idle", freeHubExecMode == "LIVE_WHEN_IDLE")
            .apply()
        btn.text = when (freeHubExecMode) {
            "BACKGROUND" -> "🌐 Mode: Background Browser (पीछे ब्राउज़र में चलेगा, Emoji टैप पर दिखेगा)"
            "LIVE_WHEN_IDLE" -> "🌙 Mode: Real App When Phone is Free (जब फोन फ्री हो तब असली ऐप चलाएगा)"
            else -> "📱 Mode: Real Chrome / App Now (सामने असली ऐप में चलाएगा)"
        }
    }

    val freeHubPromptInput = createCleanInput(
        prefs.getString("free_hub_prompt", "Create a viral 9:16 short video and download it") ?: "",
        "कैसी वीडियो बनवानी है?"
    )
    freeWebDownloadFolderEditText = createCleanInput(
        prefs.getString("custom_download_folder", defaultDownloadDir) ?: defaultDownloadDir,
        "डाउनलोड फोल्डर पाथ"
    )

    val pickFreeWebFolderBtn = createPrimaryButton("📂 फोल्डर पाथ चुनें (Browse Download Path)", Color.parseColor("#334155")) {
        if (requestStoragePermissionsOnDemand()) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), MainActivity.REQ_PICK_FREEWEB_SAVE_FOLDER)
        }
    }

    val runFreeHubBtn = createPrimaryButton("🎬 Free Video Hub चलाएं (Download ➔ YouTube/Insta)", accentRoyalBlue) {
        val site = freeSiteInput.text.toString().trim()
        val prompt = freeHubPromptInput.text.toString().trim()
        val saveDir = freeWebDownloadFolderEditText.text.toString().trim().ifEmpty { defaultDownloadDir }
        prefs.edit()
            .putString("free_hub_site", site)
            .putString("free_hub_prompt", prompt)
            .putString("custom_download_folder", saveDir)
            .apply()
        val svc = AutoTouchService.instance
        if (svc == null) {
            Toast.makeText(this, "पहले Accessibility Service ऑन करें!", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            val ytOn = prefs.getBoolean("yt_link_enabled", true)
            val instaOn = prefs.getBoolean("insta_link_enabled", false)
            val targets = listOfNotNull(if (ytOn) "YouTube Shorts" else null, if (instaOn) "Instagram Reels" else null).joinToString(" & ")
            val mode = if (freeHubExecMode == "BACKGROUND") "BACKGROUND_WEB" else "LIVE_PHONE"
            svc.startUniversalTask("Open $site, generate video for '$prompt', save to $saveDir, and upload to $targets", emptyList(), mode)
        }
    }

    freeHubCard.addView(siteChipsScroll)
    freeHubCard.addView(freeSiteInput)
    freeHubCard.addView(spacer(dp(8)))
    freeHubCard.addView(freeHubModeBtn)
    freeHubCard.addView(spacer(dp(8)))
    freeHubCard.addView(freeHubPromptInput)
    freeHubCard.addView(spacer(dp(8)))
    freeHubCard.addView(freeWebDownloadFolderEditText)
    freeHubCard.addView(spacer(dp(6)))
    freeHubCard.addView(pickFreeWebFolderBtn)
    freeHubCard.addView(spacer(dp(10)))
    freeHubCard.addView(runFreeHubBtn)

    container.addView(flowCard)
    container.addView(ytCard)
    container.addView(schedCard)
    container.addView(freeHubCard)
    scroll.addView(container)
    return scroll
}

/**
 * Runs a single n8n Workflow card immediately (for testing or scheduled trigger)
 */
internal fun MainActivity.executeN8nWorkflowNow(wf: N8nScheduleWorkflow, mainBoxStoryTopic: String) {
    val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
    val svc = AutoTouchService.instance
    if (svc == null) {
        Toast.makeText(this, "पहले Accessibility Service ऑन करें!", Toast.LENGTH_SHORT).show()
        return
    }
    val rawTopic = wf.customStoryPrompt.trim().ifEmpty {
        mainBoxStoryTopic.trim().ifEmpty {
            prefs.getString("flow_topic", "Young Indian fashion designer story") ?: "Young Indian fashion designer story"
        }
    }
    val finalTopic = if (wf.storyContinuationMode) "Step-by-step continuous story: $rawTopic" else rawTopic
    val count = prefs.getInt("flow_daily_videos", 1).coerceIn(1, 5)

    when (wf.targetActionNode) {
        "YOUTUBE_DIRECT" -> {
            prefs.edit().putBoolean("yt_link_enabled", true).apply()
            svc.startGoogleFlowToYouTubePipeline(finalTopic, count, uploadImmediatelyAfterCreate = true)
        }
        "INSTA_DIRECT" -> {
            prefs.edit().putBoolean("yt_link_enabled", false).putBoolean("insta_link_enabled", true).apply()
            svc.startGoogleFlowToYouTubePipeline(finalTopic, count, uploadImmediatelyAfterCreate = true)
        }
        "BOTH_DIRECT" -> {
            prefs.edit().putBoolean("yt_link_enabled", true).putBoolean("insta_link_enabled", true).apply()
            svc.startGoogleFlowToYouTubePipeline(finalTopic, count, uploadImmediatelyAfterCreate = true)
        }
        else -> {
            svc.startGoogleFlowToYouTubePipeline(finalTopic, count, uploadImmediatelyAfterCreate = false)
        }
    }
}

private fun MainActivity.showEditWorkflowTimeDialog(wf: N8nScheduleWorkflow, onSaved: () -> Unit) {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }
    val timeEdit = createCleanInput(wf.triggerTimeHm, "24-Hr Time (जैसे 03:36, 09:00, 18:30)")
    box.addView(timeEdit)
    darkDialogBuilder()
        .setTitle("⏰ वर्कफ़्लो का टाइम बदलें")
        .setView(box)
        .setPositiveButton("💾 सेव करें") { _, _ ->
            wf.triggerTimeHm = normalizeTimeHm24(timeEdit.text.toString(), wf.triggerTimeHm)
            onSaved()
        }
        .setNegativeButton("बंद करें", null)
        .show()
}

/**
 * Dialog to create a brand-new n8n Time Workflow Card with + Link & + YouTube/Insta Node
 */
private fun MainActivity.showCreateNewTimeWorkflowDialog(
    prefs: SharedPreferences,
    onCreated: (N8nScheduleWorkflow) -> Unit
) {
    val wrapper = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }
    val timeInput = createCleanInput("03:36", "⏰ Node 1: किस टाइम चले? (जैसे 03:36 या 18:00)")
    val linkInput = createCleanInput(
        prefs.getString("saved_flow_url", "https://flow.google.com/?pli=1") ?: "https://flow.google.com/?pli=1",
        "🔗 Node 2 (+ Link): वेबसाइट / Flow प्रोजेक्ट लिंक"
    )

    var selectedTarget = "YOUTUBE_DIRECT"
    val targetBtn = createPrimaryButton(
        "🚀 Node 3 (+ Action): 📺 YouTube Shorts (बनते ही डायरेक्ट अपलोड)",
        Color.parseColor("#DC2626")
    ) { btn ->
        selectedTarget = when (selectedTarget) {
            "YOUTUBE_DIRECT" -> "BOTH_DIRECT"
            "BOTH_DIRECT" -> "INSTA_DIRECT"
            "INSTA_DIRECT" -> "SAVE_FOLDER_ONLY"
            else -> "YOUTUBE_DIRECT"
        }
        btn.text = when (selectedTarget) {
            "YOUTUBE_DIRECT" -> "🚀 Node 3 (+ Action): 📺 YouTube Shorts (बनते ही डायरेक्ट अपलोड)"
            "BOTH_DIRECT" -> "🚀 Node 3 (+ Action): 📺 YouTube + 📸 Insta (दोनों पर डायरेक्ट अपलोड)"
            "INSTA_DIRECT" -> "🚀 Node 3 (+ Action): 📸 Instagram Reels (डायरेक्ट अपलोड)"
            else -> "🚀 Node 3 (+ Action): 💾 सिर्फ फोल्डर में सेव करें"
        }
    }

    var storyNextEp = true
    val storyToggleBtn = createPrimaryButton(
        "📖 Story Node: ON (हर बार कहानी का अगला भाग/पार्ट बनेगा)",
        Color.parseColor("#1E3A8A")
    ) { btn ->
        storyNextEp = !storyNextEp
        btn.text = if (storyNextEp) {
            "📖 Story Node: ON (हर बार कहानी का अगला भाग/पार्ट बनेगा)"
        } else {
            "🎬 Story Node: OFF (Single Exact Prompt)"
        }
    }

    wrapper.addView(timeInput)
    wrapper.addView(spacer(dp(8)))
    wrapper.addView(linkInput)
    wrapper.addView(spacer(dp(8)))
    wrapper.addView(storyToggleBtn)
    wrapper.addView(spacer(dp(8)))
    wrapper.addView(targetBtn)

    darkDialogBuilder()
        .setTitle("➕ नया n8n Time Workflow बनाएं")
        .setView(wrapper)
        .setPositiveButton("⚡ वर्कफ़्लो जोड़ें") { _, _ ->
            val normHm = normalizeTimeHm24(timeInput.text.toString(), "08:00")
            val url = linkInput.text.toString().trim().ifEmpty { "https://flow.google.com/?pli=1" }
            val newWf = N8nScheduleWorkflow(
                triggerTimeHm = normHm,
                enabled = true,
                sourceNodeType = if (url.contains("flow.google") || url.contains("labs.google")) "GOOGLE_FLOW" else "CUSTOM_WEB_URL",
                sourceWebUrl = url,
                customStoryPrompt = "",
                storyContinuationMode = storyNextEp,
                targetActionNode = selectedTarget,
                delayedUploadTimeHm = normHm,
                linkedExtraSteps = mutableListOf("💾 Auto-Save MP4", "✍️ AI Viral Title")
            )
            val currentList = loadN8nWorkflowsFromPrefs(prefs)
            currentList.add(0, newWf)
            saveN8nWorkflowsToPrefs(prefs, currentList)
            onCreated(newWf)
            Toast.makeText(this, "✅ $normHm बजे का नया n8n वर्कफ़्लो जुड़ गया!", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("बंद करें", null)
        .show()
}

/**
 * Inside-Workflow [➕ Node Linker] Dialog: Lets user link/edit Website URL, YouTube, Insta, Story Mode, or Extra Steps
 */
private fun MainActivity.showAddOrEditNodeInWorkflowDialog(
    wf: N8nScheduleWorkflow,
    prefs: SharedPreferences,
    onUpdated: () -> Unit
) {
    val wrapper = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }

    val timeInput = createCleanInput(wf.triggerTimeHm, "⏰ किस टाइम चले? (24-Hr, जैसे 03:36)")
    val urlInput = createCleanInput(wf.sourceWebUrl, "🔗 + Website / Flow Link (यहाँ लिंक टाइप या पेस्ट करें)")
    val extraStepInput = createCleanInput("", "➕ कोई एक्स्ट्रा स्टेप या लिंक जोड़ें (जैसे: Add Hashtags / Insta Share)")

    var currentTarget = wf.targetActionNode
    val targetCycleBtn = createPrimaryButton(
        when (currentTarget) {
            "YOUTUBE_DIRECT" -> "➕ Connected: 📺 YouTube Shorts (तुरंत डायरेक्ट अपलोड)"
            "BOTH_DIRECT" -> "➕ Connected: 📺 YouTube + 📸 Insta (दोनों से जुड़ा है)"
            "INSTA_DIRECT" -> "➕ Connected: 📸 Instagram Reels (तुरंत रील अपलोड)"
            else -> "➕ Connected: 💾 Save to Folder Only"
        },
        accentEmerald
    ) { btn ->
        currentTarget = when (currentTarget) {
            "YOUTUBE_DIRECT" -> "BOTH_DIRECT"
            "BOTH_DIRECT" -> "INSTA_DIRECT"
            "INSTA_DIRECT" -> "SAVE_FOLDER_ONLY"
            else -> "YOUTUBE_DIRECT"
        }
        btn.text = when (currentTarget) {
            "YOUTUBE_DIRECT" -> "➕ Connected: 📺 YouTube Shorts (तुरंत डायरेक्ट अपलोड)"
            "BOTH_DIRECT" -> "➕ Connected: 📺 YouTube + 📸 Insta (दोनों से जुड़ा है)"
            "INSTA_DIRECT" -> "➕ Connected: 📸 Instagram Reels (तुरंत रील अपलोड)"
            else -> "➕ Connected: 💾 Save to Folder Only"
        }
    }

    var storyCont = wf.storyContinuationMode
    val storyBtn = createPrimaryButton(
        if (storyCont) "📖 + Story Link: Auto Next Episode (हर बार अगली स्टोरी बनेगी)"
        else "🎬 + Story Link: Single Exact Prompt",
        Color.parseColor("#1E3A8A")
    ) { btn ->
        storyCont = !storyCont
        btn.text = if (storyCont) {
            "📖 + Story Link: Auto Next Episode (हर बार अगली स्टोरी बनेगी)"
        } else {
            "🎬 + Story Link: Single Exact Prompt"
        }
    }

    wrapper.addView(timeInput)
    wrapper.addView(spacer(dp(8)))
    wrapper.addView(urlInput)
    wrapper.addView(spacer(dp(8)))
    wrapper.addView(targetCycleBtn)
    wrapper.addView(spacer(dp(8)))
    wrapper.addView(storyBtn)
    wrapper.addView(spacer(dp(8)))
    wrapper.addView(extraStepInput)

    darkDialogBuilder()
        .setTitle("🔗 n8n Node Linker (${wf.triggerTimeHm})")
        .setView(wrapper)
        .setPositiveButton("💾 लिंक सेव करें") { _, _ ->
            wf.triggerTimeHm = normalizeTimeHm24(timeInput.text.toString(), wf.triggerTimeHm)
            val newUrl = urlInput.text.toString().trim().ifEmpty { "https://flow.google.com/?pli=1" }
            wf.sourceWebUrl = newUrl
            wf.sourceNodeType = if (newUrl.contains("flow.google") || newUrl.contains("labs.google")) "GOOGLE_FLOW" else "CUSTOM_WEB_URL"
            wf.targetActionNode = currentTarget
            wf.storyContinuationMode = storyCont
            val extra = extraStepInput.text.toString().trim()
            if (extra.isNotEmpty()) {
                wf.linkedExtraSteps.add(extra)
            }
            val list = loadN8nWorkflowsFromPrefs(prefs).map { if (it.id == wf.id) wf else it }
            saveN8nWorkflowsToPrefs(prefs, list)
            onUpdated()
            Toast.makeText(this, "✅ n8n नोड्स और लिंक अपडेट हो गए!", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("बंद करें", null)
        .show()
}

private fun MainActivity.buildMultiPlatformLinkerBox(prefs: SharedPreferences): View {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundedBox(Color.parseColor("#161922"), dp(12), borderSubtle)
    }
    box.addView(TextView(this).apply {
        text = "🔗 कहाँ-कहाँ अपलोड करना है (YouTube / Instagram):"
        setTextColor(textPureWhite)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(8))
    })

    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    var ytLinked = prefs.getBoolean("yt_link_enabled", true)
    val ytChip = createPrimaryButton(
        if (ytLinked) "✅ YouTube ON" else "⚪ YouTube OFF",
        if (ytLinked) Color.parseColor("#DC2626") else Color.parseColor("#262B36")
    ) { btn ->
        ytLinked = !ytLinked
        prefs.edit().putBoolean("yt_link_enabled", ytLinked).apply()
        btn.text = if (ytLinked) "✅ YouTube ON" else "⚪ YouTube OFF"
        btn.background = roundedBox(if (ytLinked) Color.parseColor("#DC2626") else Color.parseColor("#262B36"), dp(10))
    }.apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
    }

    var instaLinked = prefs.getBoolean("insta_link_enabled", false)
    val instaChip = createPrimaryButton(
        if (instaLinked) "✅ Insta Reels ON" else "⚪ Insta Reels OFF",
        if (instaLinked) Color.parseColor("#DB2777") else Color.parseColor("#262B36")
    ) { btn ->
        instaLinked = !instaLinked
        prefs.edit().putBoolean("insta_link_enabled", instaLinked).apply()
        btn.text = if (instaLinked) "✅ Insta Reels ON" else "⚪ Insta Reels OFF"
        btn.background = roundedBox(if (instaLinked) Color.parseColor("#DB2777") else Color.parseColor("#262B36"), dp(10))
    }.apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    row.addView(ytChip)
    row.addView(instaChip)
    box.addView(row)
    return box
}

/**
 * FIXED Mini Task AI Configurator:
 * 1. NEVER overwrites the user's existing story in topicField when user only speaks schedule/time instructions!
 * 2. Normalizes spoken time (e.g. "3:36" -> "03:36") and immediately updates UI EditTexts & n8n Workflow cards!
 */
private fun MainActivity.showMiniTaskAiAssistantDialog(
    topicField: EditText,
    countField: EditText,
    onSettingsUpdated: (createHm: String, uploadHm: String, storyEnabled: Boolean) -> Unit
) {
    val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
    val existingStoryInBox = topicField.text.toString().trim()

    val wrapper = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }
    val input = createCleanInput(
        "",
        "जैसे: '3:36 पर वीडियो बनाना और वही वीडियो फिर YouTube पर डायरेक्ट अपलोड करना' (आपकी ऊपर लिखी स्टोरी सुरक्षित रहेगी!)"
    ).apply { minLines = 3 }
    wrapper.addView(input)

    darkDialogBuilder()
        .setTitle("🤖 Mini Task AI Configurator")
        .setView(wrapper)
        .setPositiveButton("⚡ ऑटो-सेट करें") { _, _ ->
            val userWish = input.text.toString().trim()
            if (userWish.isEmpty()) return@setPositiveButton
            uiScope.launch {
                val brain = getActiveBrain()
                val isTimeOnly = brain.isTimeOrScheduleOnlyCommand(userWish)
                val plan = brain.chatAndBuildDetailedPlan(userWish, "", "")

                // 1. Preserve existing story in topicField unless user explicitly dictated a brand-new long story!
                val keepExisting = isTimeOnly || plan.optBoolean("keepExistingStory", true) || existingStoryInBox.length > 25
                val newStoryCandidate = plan.optString("storyTopicPrompt", "").trim()
                val finalStoryTopic = if (!keepExisting && newStoryCandidate.length > 20) {
                    newStoryCandidate
                } else {
                    existingStoryInBox.ifEmpty {
                        prefs.getString("flow_topic", "Young Indian girl fashion designer story") ?: "Young Indian girl fashion designer story"
                    }
                }
                topicField.setText(finalStoryTopic)
                prefs.edit().putString("flow_topic", finalStoryTopic).apply()

                // 2. Extract and normalize 24-Hr times ("3:36" -> "03:36")
                val spokenTimes = Regex("(\\d{1,2})[:.](\\d{2})")
                    .findAll(userWish)
                    .map { normalizeTimeHm24(it.value) }
                    .toList()

                val aiCreateHm = plan.optString("createTimeHm", "").takeIf { it.isNotBlank() }?.let { normalizeTimeHm24(it) }
                val createHm = spokenTimes.getOrNull(0)
                    ?: aiCreateHm
                    ?: normalizeTimeHm24(prefs.getString("flow_create_time_hm", "18:00") ?: "18:00")

                val lowerWish = userWish.lowercase(Locale.ROOT)
                val wantsDirectUpload = lowerWish.contains("direct") ||
                    lowerWish.contains("turant") ||
                    lowerWish.contains("तुरंत") ||
                    lowerWish.contains("vahi video") ||
                    lowerWish.contains("fir youtube") ||
                    plan.optBoolean("directUploadAfterCreate", false)

                val uploadHm = when {
                    spokenTimes.size >= 2 -> spokenTimes[1]
                    wantsDirectUpload -> createHm // Same time = Direct Create + Immediate Upload mode!
                    else -> createHm
                }

                val storyModeOn = lowerWish.contains("story") ||
                    lowerWish.contains("कहानी") ||
                    lowerWish.contains("पार्ट") ||
                    finalStoryTopic.contains("story", ignoreCase = true) ||
                    prefs.getBoolean("flow_story_mode", true)

                prefs.edit()
                    .putString("flow_create_time_hm", createHm)
                    .putString("folder_upload_time_hm", uploadHm)
                    .putString("schedule_time_hm", uploadHm)
                    .putBoolean("scheduler_enabled", true)
                    .putBoolean("flow_story_mode", storyModeOn)
                    .apply()

                if (lowerWish.contains("insta") || lowerWish.contains("इंस्टा")) {
                    prefs.edit().putBoolean("insta_link_enabled", true).apply()
                }
                if (lowerWish.contains("youtube") || lowerWish.contains("यूट्यूब")) {
                    prefs.edit().putBoolean("yt_link_enabled", true).apply()
                }

                // 3. Add or update the n8n Time Workflow card for this spoken time!
                val workflows = loadN8nWorkflowsFromPrefs(prefs)
                val targetAction = when {
                    lowerWish.contains("insta") && lowerWish.contains("youtube") -> "BOTH_DIRECT"
                    lowerWish.contains("insta") && !lowerWish.contains("youtube") -> "INSTA_DIRECT"
                    wantsDirectUpload || createHm == uploadHm -> "YOUTUBE_DIRECT"
                    else -> "DELAYED_UPLOAD"
                }
                val existingIdx = workflows.indexOfFirst { normalizeTimeHm24(it.triggerTimeHm) == createHm }
                if (existingIdx >= 0) {
                    workflows[existingIdx].enabled = true
                    workflows[existingIdx].targetActionNode = targetAction
                    workflows[existingIdx].delayedUploadTimeHm = uploadHm
                    workflows[existingIdx].storyContinuationMode = storyModeOn
                    workflows[existingIdx].lastRunMinuteKey = "" // Reset so it can trigger fresh!
                } else {
                    workflows.add(
                        0,
                        N8nScheduleWorkflow(
                            triggerTimeHm = createHm,
                            enabled = true,
                            sourceNodeType = "GOOGLE_FLOW",
                            sourceWebUrl = prefs.getString("saved_flow_url", "https://flow.google.com/?pli=1") ?: "https://flow.google.com/?pli=1",
                            customStoryPrompt = "",
                            storyContinuationMode = storyModeOn,
                            targetActionNode = targetAction,
                            delayedUploadTimeHm = uploadHm,
                            linkedExtraSteps = mutableListOf("💾 Auto-Save MP4", "📺 Direct YouTube Upload")
                        )
                    )
                }
                saveN8nWorkflowsToPrefs(prefs, workflows)

                onSettingsUpdated(createHm, uploadHm, storyModeOn)

                Toast.makeText(
                    this@showMiniTaskAiAssistantDialog,
                    "✅ सेट हो गया! ⏰ $createHm बजे वीडियो बनेगा और YouTube पर अपलोड होगा (आपकी स्टोरी सुरक्षित है)!",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        .setNegativeButton("बंद करें", null)
        .show()
}

internal fun MainActivity.buildTab3FullControlBrowser(): View {
    val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
    val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val navBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundColor(cardSlate)
    }
    val urlInput = EditText(this).apply {
        setText(prefs.getString("saved_flow_url", "https://labs.google/fx/tools/flow"))
        setTextColor(textPureWhite)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setSingleLine(true)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = roundedBox(bgMatteDark, dp(8), borderSubtle)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    fun navBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(textPureWhite)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setPadding(dp(9), dp(8), dp(9), dp(8))
        setOnClickListener { onClick() }
    }
    navBar.addView(navBtn("⬅") { if (studioWebView?.canGoBack() == true) studioWebView?.goBack() })
    navBar.addView(navBtn("➡") { if (studioWebView?.canGoForward() == true) studioWebView?.goForward() })
    navBar.addView(navBtn("🔄") { studioWebView?.reload() })
    navBar.addView(urlInput)
    navBar.addView(navBtn("Go") {
        var u = urlInput.text.toString().trim()
        if (!u.startsWith("http")) u = "https://$u"
        studioWebView?.loadUrl(u)
    })

    val toolStripScroll = HorizontalScrollView(this).apply {
        setBackgroundColor(Color.parseColor("#181B20"))
        isHorizontalScrollBarEnabled = false
    }
    val toolStrip = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(6), dp(4), dp(6), dp(4))
    }
    toolStrip.addView(navBtn("🔑 Gmail") { studioWebView?.loadUrl("https://accounts.google.com") })
    toolStrip.addView(navBtn("🎬 Flow") { studioWebView?.loadUrl("https://labs.google/fx/tools/flow") })
    toolStrip.addView(navBtn("📺 YT Studio") { studioWebView?.loadUrl("https://studio.youtube.com") })
    toolStrip.addView(navBtn("📸 Insta Web") { studioWebView?.loadUrl("https://www.instagram.com") })
    toolStrip.addView(navBtn("🤖 Send to Emoji BG") {
        val currentUrl = studioWebView?.url ?: "https://labs.google/fx/tools/flow"
        prefs.edit().putString("saved_flow_url", currentUrl).apply()
        AutoTouchService.instance?.hud?.openBackgroundDotUrl(currentUrl, desktopMode = true, showCard = true)
        Toast.makeText(this, "🤖 यह प्रोजेक्ट अब Floating Emoji के Background Browser में खुल गया है!", Toast.LENGTH_LONG).show()
    })
    toolStrip.addView(navBtn("🖥️ Desktop") {
        isDesktopMode = !isDesktopMode
        studioWebView?.settings?.userAgentString = if (isDesktopMode)
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        else
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        studioWebView?.reload()
    })
    toolStrip.addView(navBtn("📌 Save") {
        prefs.edit().putString("saved_flow_url", studioWebView?.url ?: "https://labs.google/fx/tools/flow").apply()
        CookieManager.getInstance().flush()
        Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
    })
    toolStripScroll.addView(toolStrip)

    studioWebView = WebView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                urlInput.setText(url ?: "")
                CookieManager.getInstance().flush()
            }
        }
        webChromeClient = WebChromeClient()
        setDownloadListener(DownloadListener { url, _, _, _, _ -> downloadedFilesLog.add(0, url) })
        loadUrl(prefs.getString("saved_flow_url", "https://labs.google/fx/tools/flow") ?: "https://labs.google/fx/tools/flow")
    }
    container.addView(navBar)
    container.addView(toolStripScroll)
    container.addView(studioWebView)
    return container
}

internal fun MainActivity.buildTab4MemoryAndVideoTrainer(): View {
    val vault = getActiveVault()
    val scroll = ScrollView(this)
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(24))
    }
    val trainerCard = createSectionCard("📹 Frame-by-Frame Video Trainer (3.1 Pro + Robotics)")
    val catInput = createCleanInput("Instagram", "App Folder Name")
    val noteInput = createCleanInput(selectedTrainingNote, "निर्देश लिखें...")
    val pickVideoBtn = createPrimaryButton("📤 Upload Screen Recording Video & Train", accentRoyalBlue) {
        selectedTrainingCategory = catInput.text.toString().trim().ifEmpty { "General" }
        selectedTrainingNote = noteInput.text.toString().trim().ifEmpty { "Learn exact step coordinates" }
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "video/*" }, MainActivity.REQ_PICK_TRAINING_VIDEO)
    }
    trainerCard.addView(catInput)
    trainerCard.addView(spacer(dp(8)))
    trainerCard.addView(noteInput)
    trainerCard.addView(spacer(dp(12)))
    trainerCard.addView(pickVideoBtn)

    val videoSessionsCard = createSectionCard("🎬 Uploaded Video Training Sessions")
    videoSessionsListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    videoSessionsCard.addView(videoSessionsListLayout)

    val runsCard = createSectionCard("🖼️ Automation Runs History (1-2-3 Screenshot Editor)")
    automationRunsListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    runsCard.addView(automationRunsListLayout)

    val foldersCard = createSectionCard("📁 App-Wise Confirmed Laws (LIVE_CODE_RULEs)")
    memoryCategoriesLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    foldersCard.addView(memoryCategoriesLayout)

    val profileCard = createSectionCard("🔐 Personal Profile Memory")
    val profileInput = createCleanInput(vault.getUserProfileRules(), "अपना नाम, ईमेल या नियम लिखें...").apply { minLines = 3 }
    profileCard.addView(profileInput)
    profileCard.addView(spacer(dp(10)))
    profileCard.addView(createPrimaryButton("💾 Save Personal Memory", accentEmerald) {
        getActiveVault().saveUserProfileRules(profileInput.text.toString())
        Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
    })

    container.addView(trainerCard)
    container.addView(videoSessionsCard)
    container.addView(runsCard)
    container.addView(foldersCard)
    container.addView(profileCard)
    scroll.addView(container)
    return scroll
}

internal fun MainActivity.refreshMemoryEverything() {
    refreshMemoryFoldersList()
    refreshAutomationRunsList()
    refreshVideoTrainingSessionsList()
}

private fun MainActivity.refreshMemoryFoldersList() {
    val layout = try { memoryCategoriesLayout } catch (_: Exception) { return }
    layout.removeAllViews()
    val vault = getActiveVault()
    val summary = vault.getAllCategoriesSummary()
    if (summary.isEmpty()) {
        layout.addView(TextView(this).apply {
            text = "अभी कोई Learned Fix नहीं है।"
            setTextColor(textMuted)
        })
        return
    }
    for ((cat, count) in summary) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(Color.parseColor("#262B36"), dp(10), borderSubtle)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topHeader.addView(TextView(this).apply {
            text = "📂 Memory/$cat/ ($count Laws)"
            setTextColor(textPureWhite)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        topHeader.addView(TextView(this).apply {
            text = "🧹 Clean Bad"
            setTextColor(accentAmber)
            setPadding(dp(8), dp(4), dp(10), dp(4))
            setOnClickListener {
                val removed = purgePoisonedRulesFromCategory(cat)
                Toast.makeText(this@refreshMemoryFoldersList, "🧹 $removed खराब नियम साफ किए गए!", Toast.LENGTH_SHORT).show()
                refreshMemoryEverything()
            }
        })
        topHeader.addView(TextView(this).apply {
            text = "🗑️ Clear All"
            setTextColor(Color.parseColor("#EF4444"))
            setPadding(dp(6), dp(4), dp(4), dp(4))
            setOnClickListener {
                vault.clearCategoryMemory(cat)
                refreshMemoryEverything()
            }
        })
        row.addView(topHeader)
        vault.recallFixesForApp(cat).take(10).forEach { f ->
            val nx = (f.tapXRatio * 1000).toInt()
            val ny = (f.tapYRatio * 1000).toInt()
            val isBad = isPoisonedFixRecord(f)
            row.addView(TextView(this).apply {
                text = if (isBad) " ⚠️ [BAD RULE - Tap 'Clean Bad'] ${f.solutionRule} (XY: $nx, $ny)"
                else " • [${f.sourceType}] ${f.problemDescription} ➔ ${f.solutionRule} (XY: $nx, $ny)"
                setTextColor(if (isBad) Color.parseColor("#F87171") else textMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            })
        }
        layout.addView(row)
    }
}

private fun MainActivity.refreshAutomationRunsList() {
    val layout = try { automationRunsListLayout } catch (_: Exception) { return }
    layout.removeAllViews()
    val vault = getActiveVault()
    val runs = vault.getAllAutomationRuns()
    if (runs.isEmpty()) {
        layout.addView(TextView(this).apply {
            text = "अभी कोई Automation Run सेव नहीं हुआ है।"
            setTextColor(textMuted)
        })
        return
    }
    for (run in runs) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(cardSlate, dp(10), borderSubtle)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        card.addView(TextView(this).apply {
            text = "📌 ${run.title} [${run.status}] (${run.screenshotPaths.size} Screenshots)"
            setTextColor(if (run.status == "SUCCESS") accentEmerald else accentAmber)
            typeface = Typeface.DEFAULT_BOLD
        })

        if (run.screenshotPaths.isNotEmpty()) {
            val hScroll = HorizontalScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(116)).apply { topMargin = dp(6) }
            }
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            run.screenshotPaths.forEachIndexed { idx, path ->
                val bmp = vault.loadBitmapFromPath(path) ?: return@forEachIndexed
                val stepDesc = run.stepsSummary.getOrNull(idx) ?: "Step #${idx + 1}"
                val img = ImageView(this).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = roundedBox(bgMatteDark, dp(6), accentRoyalBlue)
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    layoutParams = LinearLayout.LayoutParams(dp(68), dp(110)).apply { marginEnd = dp(8) }
                    setOnClickListener {
                        openMultiPointEditableScreenshotDialog(bmp, run.appCategory, "Step #${idx + 1}: $stepDesc")
                    }
                }
                strip.addView(img)
            }
            hScroll.addView(strip)
            card.addView(hScroll)
        }

        card.addView(createPrimaryButton("💬 1-2-3 पॉइंट & चैट सुधार", accentRoyalBlue) {
            openAutomationRunChatDialog(run)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })
        layout.addView(card)
    }
}

private fun MainActivity.openAutomationRunChatDialog(runRecord: AutomationRunRecord) {
    val vault = getActiveVault()
    val brain = getActiveBrain()
    val dialogView = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundColor(bgMatteDark)
    }
    val input = createCleanInput("", "भाई, यहाँ समझाओ क्या गलती हुई...")

    if (runRecord.screenshotPaths.isNotEmpty()) {
        dialogView.addView(TextView(this).apply {
            text = "👆 किसी भी स्क्रीनशॉट पर टैप करके **1-2-3 Multi-Point Editor** खोलें:"
            setTextColor(accentAmber)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(4))
        })
        val hScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(122))
        }
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        runRecord.screenshotPaths.forEachIndexed { idx, path ->
            val bmp = vault.loadBitmapFromPath(path) ?: return@forEachIndexed
            val stepInfo = runRecord.stepsSummary.getOrNull(idx) ?: "Step #${idx + 1}"
            val img = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedBox(cardSlate, dp(6), accentRoyalBlue)
                setPadding(dp(2), dp(2), dp(2), dp(2))
                layoutParams = LinearLayout.LayoutParams(dp(70), dp(116)).apply { marginEnd = dp(8) }
                setOnClickListener {
                    openMultiPointEditableScreenshotDialog(bmp, runRecord.appCategory, "Step #${idx + 1}: $stepInfo")
                }
            }
            strip.addView(img)
        }
        hScroll.addView(strip)
        dialogView.addView(hScroll)
    }

    val msgListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val chatScroll = ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190), 1f)
        addView(msgListLayout)
    }
    fun appendMsg(s: String, m: String) {
        msgListLayout.addView(TextView(this).apply {
            text = "$s: $m"
            setTextColor(if (s == "User") Color.parseColor("#60A5FA") else accentEmerald)
            setPadding(0, dp(4), 0, dp(4))
        })
    }
    appendMsg("3.1 Pro + Robotics", "मैंने '${runRecord.title}' के स्क्रीनशॉट्स लोड कर लिए हैं। फोटो पर टैप करके 1-2-3 पॉइंट लगाएं या नीचे नियम लिखें!")
    runRecord.chatHistory.forEach { appendMsg("History", it) }

    val sendBtn = createPrimaryButton("Send ➔", accentRoyalBlue) { btn ->
        val userText = input.text.toString().trim()
        if (userText.isEmpty()) return@createPrimaryButton
        input.setText("")
        btn.isEnabled = false
        appendMsg("User", userText)
        vault.appendChatToAutomationRun(runRecord.runId, "User", userText)
        uiScope.launch(Dispatchers.IO) {
            val imgs = vault.loadSavedImagesAsBase64(runRecord.screenshotPaths, 6)
            val rep = brain.chatAboutAutomationRunWith38Flash(runRecord, userText, imgs)
            vault.appendChatToAutomationRun(runRecord.runId, "Gemini 3.8", rep.replyText)
            if (isValidBrainReply(rep)) {
                vault.saveStuckOrHumanFix(
                    category = runRecord.appCategory,
                    screenSignature = "run_${runRecord.runId}",
                    problemDescription = rep.extractedProblem,
                    solutionRule = "MANDATORY LIVE RULE: ${rep.extractedSolutionRule}",
                    tapXRatio = rep.tapXRatio,
                    tapYRatio = rep.tapYRatio,
                    sourceType = "LIVE_CODE_RULE"
                )
            }
            withContext(Dispatchers.Main) {
                btn.isEnabled = true
                appendMsg("Gemini 3.8", rep.replyText)
                refreshMemoryEverything()
            }
        }
    }
    dialogView.addView(chatScroll)
    dialogView.addView(input)
    dialogView.addView(spacer(dp(6)))
    dialogView.addView(sendBtn)

    darkDialogBuilder()
        .setTitle("💬 Fix Run: ${runRecord.title}")
        .setView(dialogView)
        .setPositiveButton("बंद करें", null)
        .show()
}

private fun MainActivity.refreshVideoTrainingSessionsList() {
    val layout = try { videoSessionsListLayout } catch (_: Exception) { return }
    layout.removeAllViews()
    val sessions = getActiveVault().getAllVideoTrainingSessions()
    if (sessions.isEmpty()) {
        layout.addView(TextView(this).apply {
            text = "अभी कोई वीडियो ट्रेनिंग सेशन नहीं है।"
            setTextColor(textMuted)
        })
        return
    }
    for (session in sessions) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(cardSlate, dp(10), borderSubtle)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        card.addView(TextView(this).apply {
            text = "📹 ${session.appCategory} (${session.timestamp})"
            setTextColor(accentRoyalBlue)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(createPrimaryButton("💬 इस वीडियो पर Gemini 3.8 से चैट करें", accentEmerald) {
            openVideoSessionChatDialog(session)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })
        layout.addView(card)
    }
}

private fun MainActivity.openVideoSessionChatDialog(session: VideoTrainingSession) {
    val vault = getActiveVault()
    val brain = getActiveBrain()
    val dialogView = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setBackgroundColor(bgMatteDark)
    }
    val input = createCleanInput("", "वीडियो के बारे में सुधार बताएं...")
    val msgListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val chatScroll = ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220), 1f)
        addView(msgListLayout)
    }
    fun appendMsg(s: String, m: String) {
        msgListLayout.addView(TextView(this).apply {
            text = "$s: $m"
            setTextColor(if (s == "User") Color.parseColor("#60A5FA") else accentEmerald)
            setPadding(0, dp(4), 0, dp(4))
        })
    }
    appendMsg("Gemini 3.8", "मैंने इस वीडियो के ${session.framePaths.size} फ्रेम्स देख लिए हैं।")

    val sendBtn = createPrimaryButton("Send ➔", accentRoyalBlue) { btn ->
        val userText = input.text.toString().trim()
        if (userText.isEmpty()) return@createPrimaryButton
        input.setText("")
        btn.isEnabled = false
        appendMsg("User", userText)
        uiScope.launch(Dispatchers.IO) {
            val frames = vault.loadSavedImagesAsBase64(session.framePaths, 10)
            val rep = brain.chatAboutTrainingVideoWith38Flash(session, userText, frames)
            vault.appendChatToVideoSession(session.sessionId, "Gemini 3.8", rep.replyText, rep.extractedSolutionRule)
            if (isValidBrainReply(rep)) {
                vault.saveStuckOrHumanFix(
                    category = session.appCategory,
                    screenSignature = "vid_${session.sessionId}",
                    problemDescription = rep.extractedProblem,
                    solutionRule = "MANDATORY LIVE RULE: ${rep.extractedSolutionRule}",
                    tapXRatio = rep.tapXRatio,
                    tapYRatio = rep.tapYRatio,
                    sourceType = "VIDEO_CHAT"
                )
            }
            withContext(Dispatchers.Main) {
                btn.isEnabled = true
                appendMsg("Gemini 3.8", rep.replyText)
                refreshMemoryEverything()
            }
        }
    }
    dialogView.addView(chatScroll)
    dialogView.addView(input)
    dialogView.addView(spacer(dp(6)))
    dialogView.addView(sendBtn)

    darkDialogBuilder()
        .setTitle("💬 Video Chat: ${session.appCategory}")
        .setView(dialogView)
        .setPositiveButton("बंद करें", null)
        .show()
}

internal fun MainActivity.buildTab5EngineAndLogs(): View {
    val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
    engineScrollView = ScrollView(this)
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(24))
    }

    // =========================================================================
    // 📊 GEMINI API CALL LIVE TEST & TIMING METER (SECOND-BY-SECOND TRACKER)
    // =========================================================================
    val apiMeterCard = createSectionCard("📊 Gemini API Call Live Test & Timing Meter")

    var detailedApiLogOn = prefs.getBoolean("detailed_api_logging", true)
    val apiLogToggleBtn = createPrimaryButton(
        if (detailedApiLogOn) "✅ Detailed API Call Logging: ON (हर कॉल का काम, सेकंड, 1-मिनट का समय और कोटा दिखेगा)"
        else "⚪ Detailed API Call Logging: OFF",
        if (detailedApiLogOn) accentEmerald else Color.parseColor("#475569")
    ) { btn ->
        detailedApiLogOn = !detailedApiLogOn
        prefs.edit().putBoolean("detailed_api_logging", detailedApiLogOn).apply()
        getActiveBrain().detailedApiLoggingEnabled = detailedApiLogOn
        btn.text = if (detailedApiLogOn) {
            "✅ Detailed API Call Logging: ON (हर कॉल का काम, सेकंड, 1-मिनट का समय और कोटा दिखेगा)"
        } else {
            "⚪ Detailed API Call Logging: OFF"
        }
        btn.background = roundedBox(if (detailedApiLogOn) accentEmerald else Color.parseColor("#475569"), dp(12))
    }

    apiTelemetryTextView = TextView(this).apply {
        text = getActiveBrain().getDetailedApiTelemetryReport()
        setTextColor(Color.parseColor("#38BDF8"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        typeface = Typeface.MONOSPACE
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundedBox(bgMatteDark, dp(10), accentRoyalBlue)
    }

    val apiMeterBtnsRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, 0)
    }
    apiMeterBtnsRow.addView(createPrimaryButton("🔄 Refresh Meter", accentRoyalBlue) {
        refreshEngineTabDetails()
    }.apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
    })
    apiMeterBtnsRow.addView(createPrimaryButton("🗑️ Reset API Stats", Color.parseColor("#7F1D1D")) {
        getActiveBrain().resetApiTelemetryStats()
        refreshEngineTabDetails()
        Toast.makeText(this, "API Test Meter रीसेट हो गया!", Toast.LENGTH_SHORT).show()
    }.apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })

    apiMeterCard.addView(apiLogToggleBtn)
    apiMeterCard.addView(spacer(dp(8)))
    apiMeterCard.addView(apiTelemetryTextView)
    apiMeterCard.addView(apiMeterBtnsRow)

    // =========================================================================
    // 📜 LIVE SYSTEM & API ACTION HISTORY LOGS
    // =========================================================================
    val logsCard = createSectionCard("📜 Live System & API Call History Logs")
    val logControlsRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 0, 0, dp(8))
    }
    logControlsRow.addView(createPrimaryButton("🔄 Refresh", accentRoyalBlue) { refreshEngineTabDetails() }.apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
    })
    logControlsRow.addView(createPrimaryButton("📋 Copy", Color.parseColor("#334155")) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("logs", liveLogsTextView.text))
        Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
    }.apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
    })
    logControlsRow.addView(createPrimaryButton("🗑️ Clear", Color.parseColor("#7F1D1D")) {
        synchronized(AutoTouchService.liveLogs) { AutoTouchService.liveLogs.clear() }
        getActiveVault().clearPersistentLogs()
        refreshEngineTabDetails()
    }.apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })

    liveLogsTextView = TextView(this).apply {
        text = "Loading logs..."
        setTextColor(accentEmerald)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.MONOSPACE
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundedBox(bgMatteDark, dp(10), borderSubtle)
        minLines = 9
    }
    logsCard.addView(logControlsRow)
    logsCard.addView(liveLogsTextView)

    // =========================================================================
    // 👻 FLOATING BOT EMOJI: STEALTH & TRANSPARENCY
    // =========================================================================
    val stealthCard = createSectionCard("👻 Floating Bot Emoji: Stealth Mode & Corner Transparency")
    var isStealth = prefs.getBoolean("hud_stealth_mode", false)
    val stealthToggleBtn = createPrimaryButton(
        if (isStealth) "👻 Stealth Mode (गायब मोड): ON (बॉट अदृश्य रहेगा, पर बैकग्राउंड काम चलते रहेंगे)"
        else "👀 Stealth Mode (गायब मोड): OFF (चंचल बॉट कोने में दिखेगा, टैप करने पर Live Browser खुलेगा)",
        if (isStealth) accentPurple else Color.parseColor("#334155")
    ) { btn ->
        isStealth = !isStealth
        prefs.edit().putBoolean("hud_stealth_mode", isStealth).putBoolean("stealth_hide_bot", isStealth).apply()
        btn.text = if (isStealth) {
            "👻 Stealth Mode (गायब मोड): ON (बॉट अदृश्य रहेगा, पर बैकग्राउंड काम चलते रहेंगे)"
        } else {
            "👀 Stealth Mode (गायब मोड): OFF (चंचल बॉट कोने में दिखेगा, टैप करने पर Live Browser खुलेगा)"
        }
        btn.background = roundedBox(if (isStealth) accentPurple else Color.parseColor("#334155"), dp(12))
        AutoTouchService.instance?.hud?.applyUserTransparencyAndStealthSettings()
    }

    var greetingsOn = prefs.getBoolean("hud_greetings_enabled", true)
    val greetingsToggleBtn = createPrimaryButton(
        if (greetingsOn) "☀️ Good Morning / Good Night अपडेट्स: ON" else "🔕 Good Morning / Good Night अपडेट्स: OFF",
        if (greetingsOn) accentEmerald else Color.parseColor("#475569")
    ) { btn ->
        greetingsOn = !greetingsOn
        prefs.edit().putBoolean("hud_greetings_enabled", greetingsOn).putBoolean("bot_greetings_enabled", greetingsOn).apply()
        btn.text = if (greetingsOn) "☀️ Good Morning / Good Night अपडेट्स: ON" else "🔕 Good Morning / Good Night अपडेट्स: OFF"
        btn.background = roundedBox(if (greetingsOn) accentEmerald else Color.parseColor("#475569"), dp(12))
    }

    val currentAlphaPct = ((prefs.getFloat("hud_idle_alpha", 0.35f)) * 100).toInt().coerceIn(5, 100)
    val alphaLabel = TextView(this).apply {
        text = "🎚️ कोने में छुपने पर बॉट की पारदर्शिता (Edge Transparency): $currentAlphaPct%"
        setTextColor(textPureWhite)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setPadding(0, dp(10), 0, dp(6))
    }

    val alphaSeekBar = SeekBar(this).apply {
        max = 95
        progress = (currentAlphaPct - 5).coerceAtLeast(0)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = (progress + 5).coerceIn(5, 100)
                alphaLabel.text = "🎚️ कोने में छुपने पर बॉट की पारदर्शिता (Edge Transparency): $pct%"
                prefs.edit().putFloat("hud_idle_alpha", pct / 100f).putInt("hud_idle_alpha_percent", pct).apply()
                AutoTouchService.instance?.hud?.applyUserTransparencyAndStealthSettings()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    stealthCard.addView(stealthToggleBtn)
    stealthCard.addView(spacer(dp(8)))
    stealthCard.addView(greetingsToggleBtn)
    stealthCard.addView(alphaLabel)
    stealthCard.addView(alphaSeekBar)

    // =========================================================================
    // 🔑 API KEY & MODEL LADDER STATUS
    // =========================================================================
    val apiCard = createSectionCard("🔑 FreeLLMAPI + Gemini API Key & Quota Status")
    val keyRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    var isKeyHidden = true
    val keyInput = createCleanInput(
        prefs.getString("gemini_api_key", "") ?: "",
        "Paste AIza... key or freellma... key"
    ).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
    }
    val toggleKeyVisBtn = createPrimaryButton("👁️", Color.parseColor("#334155")) {
        isKeyHidden = !isKeyHidden
        keyInput.inputType = if (isKeyHidden) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        keyInput.setSelection(keyInput.text.length)
    }
    keyRow.addView(keyInput)
    keyRow.addView(toggleKeyVisBtn)

    val saveKeyBtn = createPrimaryButton("💾 Save API Key", accentRoyalBlue) {
        val k = keyInput.text.toString().trim()
        prefs.edit().putString("gemini_api_key", k).apply()
        localBrain.updateApiKey(k)
        AutoTouchService.instance?.geminiBrain?.updateApiKey(k)
        Toast.makeText(this, "API Key Updated!", Toast.LENGTH_SHORT).show()
        refreshEngineTabDetails()
    }
    ladderInfoTextView = TextView(this).apply {
        text = getActiveBrain().getActiveModelListStatus()
        setTextColor(textMuted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(10), 0, 0)
    }
    apiCard.addView(keyRow)
    apiCard.addView(spacer(dp(10)))
    apiCard.addView(saveKeyBtn)
    apiCard.addView(ladderInfoTextView)

    // =========================================================================
    // 🦾 CALIBRATED PHONE BODY & PERMISSIONS
    // =========================================================================
    val bodyCard = createSectionCard("🦾 Calibrated Phone Body & Permissions")
    calibratedSpecsTextView = TextView(this).apply {
        setTextColor(textPureWhite)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }
    bodyCard.addView(calibratedSpecsTextView)
    bodyCard.addView(spacer(dp(10)))
    bodyCard.addView(createPrimaryButton("🪟 1. Grant Floating Overlay Permission", Color.parseColor("#334155")) {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            AutoTouchService.instance?.hud?.showHud()
        }
    })
    bodyCard.addView(spacer(dp(8)))
    bodyCard.addView(createPrimaryButton("♿ 2. Enable Accessibility Service", accentRoyalBlue) {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    })
    bodyCard.addView(spacer(dp(8)))
    bodyCard.addView(createPrimaryButton("📁 3. Turn ON File Manager Access", Color.parseColor("#0D9488")) {
        requestStoragePermissionsOnDemand()
    })
    bodyCard.addView(spacer(dp(8)))
    bodyCard.addView(createPrimaryButton("🔋 4. Enable 24/7 Unstoppable Battery Mode", accentEmerald) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    })

    container.addView(apiMeterCard)
    container.addView(logsCard)
    container.addView(stealthCard)
    container.addView(apiCard)
    container.addView(bodyCard)
    engineScrollView.addView(container)
    refreshEngineTabDetails()
    return engineScrollView
}

internal fun MainActivity.refreshEngineTabDetails() {
    refreshLiveLogsBox()
    try {
        apiTelemetryTextView.text = getActiveBrain().getDetailedApiTelemetryReport()
    } catch (_: Exception) {}
    try {
        ladderInfoTextView.text = getActiveBrain().getActiveModelListStatus()
    } catch (_: Exception) {}
    try {
        val specs = AutoTouchService.instance?.getCalibratedDeviceBodySpecs()
        calibratedSpecsTextView.text = if (specs != null) {
            "• Phone: ${specs.modelName} (${specs.screenWidth}×${specs.screenHeight} px)\n• Cutout Safe Zone: ${specs.cutoutSafeTop} px | Nav: ${specs.navBarHeight} px"
        } else {
            "Enable Accessibility Service below to calibrate your screen."
        }
    } catch (_: Exception) {}
}

private fun MainActivity.refreshLiveLogsBox() {
    val tv = try { liveLogsTextView } catch (_: Exception) { return }
    val inMemory = synchronized(AutoTouchService.liveLogs) { AutoTouchService.liveLogs.toList() }
    val lines = if (inMemory.isNotEmpty()) inMemory.take(50) else getActiveVault().getPersistentLogs().take(50)
    tv.text = if (lines.isEmpty()) "No logs yet." else lines.joinToString("\n")
}
