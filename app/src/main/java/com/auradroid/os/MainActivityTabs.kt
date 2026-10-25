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

internal fun MainActivity.buildTab2AutomationsAndScheduler(): View {
    val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
    val scroll = ScrollView(this)
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(28))
    }
    val defaultDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

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

    var isStoryMode = prefs.getBoolean("flow_story_mode", false)
    val storyModeBtn = createPrimaryButton(
        if (isStoryMode) "📖 Video Type: Step-by-Step Story Base (कहानी के भाग 1, 2, 3 बनेंगे)"
        else "🎬 Video Type: Single Exact Prompt Video (जो प्रॉम्प्ट देंगे वही बनेगा)",
        Color.parseColor("#262B36")
    ) { btn ->
        isStoryMode = !isStoryMode
        prefs.edit().putBoolean("flow_story_mode", isStoryMode).apply()
        btn.text = if (isStoryMode) {
            "📖 Video Type: Step-by-Step Story Base (कहानी के भाग 1, 2, 3 बनेंगे)"
        } else {
            "🎬 Video Type: Single Exact Prompt Video (जो प्रॉम्प्ट देंगे वही बनेगा)"
        }
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
        showMiniTaskAiAssistantDialog(topicInput, creditLimitInput)
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
        if ( ::folderPathEditText.isInitialized ) folderPathEditText.setText(customSaveDir)
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
        if ( ::folderPathEditText.isInitialized ) folderPathEditText.setText(customSaveDir)
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
    // 3️⃣ 24/7 DUAL-TIME AUTO SCHEDULER (FLOW CREATE TIME + FOLDER UPLOAD TIME)
    // =========================================================================
    val schedCard = createSectionCard("⏰ 24/7 Auto Schedule: वीडियो बनने का टाइम + अपलोड होने का टाइम")

    schedCard.addView(TextView(this).apply {
        text = "1️⃣ कितने बजे ऑटोमैटिक Flow से वीडियो बनकर फोल्डर में सेव हो? (24-Hr, जैसे 18:00):"
        setTextColor(textPureWhite)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, 0, 0, dp(4))
    })
    val flowCreateTimeInput = createCleanInput(
        prefs.getString("flow_create_time_hm", "18:00") ?: "18:00",
        "Flow Video Create Time (जैसे 18:00 = शाम 6:00 बजे)"
    )

    schedCard.addView(flowCreateTimeInput)
    schedCard.addView(spacer(dp(10)))

    schedCard.addView(TextView(this).apply {
        text = "2️⃣ कितने बजे उस फोल्डर से वीडियो उठकर Title + Description के साथ अपलोड हो? (जैसे 19:00):"
        setTextColor(textPureWhite)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, 0, 0, dp(4))
    })
    val folderUploadTimeInput = createCleanInput(
        prefs.getString("folder_upload_time_hm", prefs.getString("schedule_time_hm", "19:00")) ?: "19:00",
        "Folder Video Upload Time (जैसे 19:00 = शाम 7:00 बजे)"
    )
    schedCard.addView(folderUploadTimeInput)
    schedCard.addView(spacer(dp(8)))

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
        val tCreate = flowCreateTimeInput.text.toString().trim().ifEmpty { "18:00" }
        val tUpload = folderUploadTimeInput.text.toString().trim().ifEmpty { "19:00" }
        val saveDir = flowDownloadFolderEditText.text.toString().trim().ifEmpty { defaultDownloadDir }
        prefs.edit()
            .putString("flow_create_time_hm", tCreate)
            .putString("folder_upload_time_hm", tUpload)
            .putString("schedule_time_hm", tUpload)
            .putString("custom_download_folder", saveDir)
            .putString("yt_folder_path", folderPathEditText.text.toString().trim().ifEmpty { saveDir })
            .putString("custom_video_description", customDescriptionInput.text.toString().trim())
            .apply()
        Toast.makeText(
            this,
            "✅ सेव हो गया!\n🎬 Flow वीडियो बनेगा: $tCreate बजे\n📤 फोल्डर से अपलोड होगा: $tUpload बजे",
            Toast.LENGTH_LONG
        ).show()
    }.apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })

    var schedEnabled = prefs.getBoolean("scheduler_enabled", false)
    val enableSchedBtn = createPrimaryButton(
        if (schedEnabled) "✅ 24/7 Daily Auto-Scheduler: ON" else "⚪ 24/7 Daily Auto-Scheduler: OFF (Tap to Enable)",
        if (schedEnabled) accentEmerald else Color.parseColor("#475569")
    ) { btn ->
        schedEnabled = !schedEnabled
        val tCreate = flowCreateTimeInput.text.toString().trim().ifEmpty { "18:00" }
        val tUpload = folderUploadTimeInput.text.toString().trim().ifEmpty { "19:00" }
        val saveDir = flowDownloadFolderEditText.text.toString().trim().ifEmpty { defaultDownloadDir }
        prefs.edit()
            .putBoolean("scheduler_enabled", schedEnabled)
            .putString("flow_create_time_hm", tCreate)
            .putString("folder_upload_time_hm", tUpload)
            .putString("schedule_time_hm", tUpload)
            .putString("custom_download_folder", saveDir)
            .putString("yt_folder_path", folderPathEditText.text.toString().trim().ifEmpty { saveDir })
            .putString("custom_video_description", customDescriptionInput.text.toString().trim())
            .apply()
        btn.text = if (schedEnabled) "✅ 24/7 Daily Auto-Scheduler: ON" else "⚪ 24/7 Daily Auto-Scheduler: OFF (Tap to Enable)"
        btn.background = roundedBox(if (schedEnabled) accentEmerald else Color.parseColor("#475569"), dp(12))
    }

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

private fun MainActivity.showMiniTaskAiAssistantDialog(topicField: EditText, countField: EditText) {
    val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
    val wrapper = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }
    val input = createCleanInput(
        "",
        "जैसे: 'रोज़ शाम 6:00 बजे Flow से वीडियो बनाकर फोल्डर में सेव करो और 7:00 बजे YouTube और Insta पर अपलोड करो'"
    ).apply { minLines = 3 }
    wrapper.addView(input)
    darkDialogBuilder()
        .setTitle("🤖 Mini Task AI Configurator")
        .setView(wrapper)
        .setPositiveButton("⚡ ऑटो-सेट करें") { _, _ ->
            val userWish = input.text.toString().trim()
            if (userWish.isEmpty()) return@setPositiveButton
            uiScope.launch {
                val plan = getActiveBrain().chatAndBuildDetailedPlan("Extract video topic from: $userWish", "", "")
                topicField.setText(plan.optString("taskTitle", userWish).ifEmpty { userWish })
                val times = Regex("(\\d{1,2})[:.](\\d{2})").findAll(userWish).map { it.value.replace(".", ":") }.toList()
                if (times.size >= 2) {
                    prefs.edit()
                        .putString("flow_create_time_hm", times[0])
                        .putString("folder_upload_time_hm", times[1])
                        .putString("schedule_time_hm", times[1])
                        .putBoolean("scheduler_enabled", true)
                        .apply()
                } else if (times.size == 1) {
                    prefs.edit()
                        .putString("flow_create_time_hm", times[0])
                        .putString("folder_upload_time_hm", times[0])
                        .putString("schedule_time_hm", times[0])
                        .putBoolean("scheduler_enabled", true)
                        .apply()
                }
                if (userWish.contains("story", true) || userWish.contains("कहानी") || userWish.contains("पार्ट")) {
                    prefs.edit().putBoolean("flow_story_mode", true).apply()
                    countField.setText("2")
                }
                if (userWish.contains("insta", true) || userWish.contains("इंस्टा")) {
                    prefs.edit().putBoolean("insta_link_enabled", true).apply()
                }
                Toast.makeText(this@showMiniTaskAiAssistantDialog, "✅ AI ने सेटिंग्स अपडेट कर दीं!", Toast.LENGTH_LONG).show()
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
    if (!::memoryCategoriesLayout.isInitialized) return
    memoryCategoriesLayout.removeAllViews()
    val vault = getActiveVault()
    val summary = vault.getAllCategoriesSummary()
    if (summary.isEmpty()) {
        memoryCategoriesLayout.addView(TextView(this).apply {
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
        memoryCategoriesLayout.addView(row)
    }
}

private fun MainActivity.refreshAutomationRunsList() {
    if (!::automationRunsListLayout.isInitialized) return
    automationRunsListLayout.removeAllViews()
    val vault = getActiveVault()
    val runs = vault.getAllAutomationRuns()
    if (runs.isEmpty()) {
        automationRunsListLayout.addView(TextView(this).apply {
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
        automationRunsListLayout.addView(card)
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
    if (!::videoSessionsListLayout.isInitialized) return
    videoSessionsListLayout.removeAllViews()
    val sessions = getActiveVault().getAllVideoTrainingSessions()
    if (sessions.isEmpty()) {
        videoSessionsListLayout.addView(TextView(this).apply {
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
        videoSessionsListLayout.addView(card)
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
    if (::apiTelemetryTextView.isInitialized) {
        apiTelemetryTextView.text = getActiveBrain().getDetailedApiTelemetryReport()
    }
    if (::ladderInfoTextView.isInitialized) {
        ladderInfoTextView.text = getActiveBrain().getActiveModelListStatus()
    }
    if (::calibratedSpecsTextView.isInitialized) {
        val specs = AutoTouchService.instance?.getCalibratedDeviceBodySpecs()
        calibratedSpecsTextView.text = if (specs != null) {
            "• Phone: ${specs.modelName} (${specs.screenWidth}×${specs.screenHeight} px)\n• Cutout Safe Zone: ${specs.cutoutSafeTop} px | Nav: ${specs.navBarHeight} px"
        } else {
            "Enable Accessibility Service below to calibrate your screen."
        }
    }
}

private fun MainActivity.refreshLiveLogsBox() {
    if (!::liveLogsTextView.isInitialized) return
    val inMemory = synchronized(AutoTouchService.liveLogs) { AutoTouchService.liveLogs.toList() }
    val lines = if (inMemory.isNotEmpty()) inMemory.take(50) else getActiveVault().getPersistentLogs().take(50)
    liveLogsTextView.text = if (lines.isEmpty()) "No logs yet." else lines.joinToString("\n")
}
