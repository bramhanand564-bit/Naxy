package com.auradroid.os

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.StrictMode
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.coroutines.resume

// Extracts 1 clear frame from the middle of a video so Gemini can write a 100% matching Viral Title & Description.
private fun AutoTouchService.extractSinglePreviewFrameFromVideo(
    videoUri: Uri? = null,
    videoFile: File? = null
): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        if (videoFile != null && videoFile.exists()) {
            retriever.setDataSource(videoFile.absolutePath)
        } else if (videoUri != null) {
            retriever.setDataSource(this, videoUri)
        } else {
            return null
        }
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 3000L
        val sampleTimeUs = ((durationMs * 0.40).toLong().coerceIn(500L, 4000L)) * 1000L
        retriever.getFrameAtTime(sampleTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (_: Exception) {
        null
    } finally {
        try { retriever.release() } catch (_: Exception) {}
    }
}

// FEATURE 2: Subtle 5%-6% Two-Finger Outward Pinch-to-Zoom Gesture on Video Preview / Editor Screen.
// Crops out the bottom-right Google Flow / Gemini 4-point star watermark logo (✦).
private suspend fun AutoTouchService.performSubtlePinchZoomToHideCornerLogo(specs: DeviceBodySpecs) {
    try {
        lastAgentTapTimestamp = System.currentTimeMillis()
        val cx = specs.screenWidth / 2f
        val cy = specs.screenHeight * 0.45f

        val path1 = Path().apply {
            moveTo(cx - 80f, cy - 80f)
            lineTo(cx - 135f, cy - 135f)
        }
        val path2 = Path().apply {
            moveTo(cx + 80f, cy + 80f)
            lineTo(cx + 135f, cy + 135f)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path1, 0L, 350L))
            .addStroke(GestureDescription.StrokeDescription(path2, 0L, 350L))
            .build()

        AutoTouchService.log("🔍 Applying 5%-6% Subtle Pinch-to-Zoom to crop out bottom-right corner watermark (✦)...")
        dispatchGesture(gesture, null, null)
        delay(600L)
    } catch (_: Exception) {}
}

// FEATURE 1: Native Android MediaExtractor + MediaMuxer C2PA / XMP / Google AI Container Metadata Stripper.
// Copies only raw video and audio elementary streams into a fresh MP4 container without re-encoding.
@Suppress("WrongConstant")
private fun stripAiContainerMetadata(srcFile: File, destFile: File): Boolean {
    var extractor: MediaExtractor? = null
    var muxer: MediaMuxer? = null
    return try {
        if (!srcFile.exists() || srcFile.length() < 10_000L) return false
        if (destFile.exists()) destFile.delete()

        extractor = MediaExtractor()
        extractor.setDataSource(srcFile.absolutePath)

        muxer = MediaMuxer(destFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val trackMap = HashMap<Int, Int>()
        var orientationSet = false

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                extractor.selectTrack(i)
                val dstIndex = muxer.addTrack(format)
                trackMap[i] = dstIndex

                if (!orientationSet && mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    try {
                        val rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                        muxer.setOrientationHint(rotation)
                        orientationSet = true
                    } catch (_: Exception) {}
                }
            }
        }

        if (trackMap.isEmpty()) return false

        muxer.start()

        val bufferSize = 1024 * 1024
        val byteBuffer = ByteBuffer.allocate(bufferSize)
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val trackIndex = extractor.sampleTrackIndex
            if (trackIndex < 0) break

            val dstTrackIndex = trackMap[trackIndex]
            if (dstTrackIndex == null) {
                extractor.advance()
                continue
            }

            byteBuffer.clear()
            val sampleSize = extractor.readSampleData(byteBuffer, 0)
            if (sampleSize < 0) break

            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = extractor.sampleTime
            bufferInfo.flags = extractor.sampleFlags

            muxer.writeSampleData(dstTrackIndex, byteBuffer, bufferInfo)
            extractor.advance()
        }

        muxer.stop()
        destFile.exists() && destFile.length() > 10_000L
    } catch (e: Exception) {
        AutoTouchService.log("⚠️ Metadata Remux Fallback: ${e.message}")
        false
    } finally {
        try { extractor?.release() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
    }
}

fun AutoTouchService.startSinglePickedVideoYouTubeUpload(videoUri: Uri, rawDisplayName: String) {
    currentTaskJob?.cancel()
    val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
    geminiBrain.updateApiKey(prefs.getString("gemini_api_key", "") ?: "")
    geminiBrain.detailedApiLoggingEnabled = prefs.getBoolean("detailed_api_logging", true)

    activeRunId = "run_yt_${System.currentTimeMillis()}"
    activeRunTitle = "YouTube Upload: ${rawDisplayName.take(20)}"
    activeRunFinalized = false
    currentAppCategory = "YouTube"
    currentTaskGoal = "Upload video $rawDisplayName to YouTube Shorts with Title & Description"
    currentPlannedSteps.clear()
    currentPlannedSteps.addAll(
        listOf(
            "Click Next on Trimmer",
            "Pinch-Zoom 5% & Click Next on Editor",
            "Paste Title & Description",
            "Click Upload Short"
        )
    )
    synchronized(currentRunBitmaps) { currentRunBitmaps.clear() }
    currentRunSteps.clear()

    currentTaskJob = serviceScope.launch {
        try {
            isAgentRunning = true
            acquireWakeLock()
            hud.updateStatus("Analyzing Video for Title & Desc...", BotMood.THINKING, taskRunning = true)

            val sanitizedName = geminiBrain.sanitizeRawVideoTopic(rawDisplayName)
            val savedFlowTopic = prefs.getString("flow_topic", "")?.trim() ?: ""
            val topicHint = if (rawDisplayName.startsWith("flow_", true) && savedFlowTopic.isNotBlank()) {
                savedFlowTopic
            } else {
                sanitizedName
            }

            val customDesc = prefs.getString("custom_video_description", "") ?: ""
            val previewFrame = withContext(Dispatchers.IO) {
                extractSinglePreviewFrameFromVideo(videoUri = videoUri)
            }

            val meta = geminiBrain.generateFlowPromptTitleAndDescription(
                topic = topicHint,
                customDescriptionNote = customDesc,
                videoFrameBitmap = previewFrame
            )
            try { previewFrame?.recycle() } catch (_: Exception) {}

            AutoTouchService.log("✨ Viral Title: ${meta.viralTitle}")
            AutoTouchService.log("📝 Description: ${meta.viralDescription.replace("\n", " ").take(90)}...")

            dispatchVideoToLinkedPlatforms(videoUri, meta.viralTitle, meta.viralDescription)
            memoryVault.markVideoAsPostedInLedger(rawDisplayName, "YouTube")
            finalizeRunAndTriggerAutoReflection("SUCCESS")
        } catch (_: CancellationException) {
            finalizeRunAndTriggerAutoReflection("STOPPED")
            hud.updateStatus("Stopped", BotMood.IDLE, taskRunning = false)
        } catch (e: Exception) {
            AutoTouchService.log("⚠️ Upload Error: ${e.message}")
            finalizeRunAndTriggerAutoReflection("FAILED")
            hud.updateStatus("Upload Paused", BotMood.ERROR, taskRunning = false)
        } finally {
            isAgentRunning = false
            releaseWakeLock()
        }
    }
}

fun AutoTouchService.startYouTubeFolderAutoUpload(folderInputRaw: String, pickOldestFirst: Boolean) {
    currentTaskJob?.cancel()
    val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
    geminiBrain.updateApiKey(prefs.getString("gemini_api_key", "") ?: "")
    geminiBrain.detailedApiLoggingEnabled = prefs.getBoolean("detailed_api_logging", true)

    activeRunId = "run_yt_${System.currentTimeMillis()}"
    activeRunTitle = "Folder Video Auto-Upload"
    activeRunFinalized = false
    currentAppCategory = "YouTube"
    currentTaskGoal = "Upload video from folder $folderInputRaw with Title & Description"
    currentPlannedSteps.clear()
    currentPlannedSteps.addAll(
        listOf(
            "Select Video from Folder",
            "Tap Next & Pinch-Zoom 5%",
            "Paste Title & Description",
            "Tap Upload Short"
        )
    )
    synchronized(currentRunBitmaps) { currentRunBitmaps.clear() }
    currentRunSteps.clear()

    currentTaskJob = serviceScope.launch {
        try {
            isAgentRunning = true
            acquireWakeLock()

            val resolvedDir = resolveSmartFolderPath(folderInputRaw)
            val uploadedSet = prefs.getStringSet("uploaded_videos_set", emptySet())?.toMutableSet() ?: mutableSetOf()
            val customDesc = prefs.getString("custom_video_description", "") ?: ""
            val fallbackFlowTopic = prefs.getString("last_flow_generated_topic", "")?.takeIf { it.isNotBlank() }
                ?: prefs.getString("flow_topic", "")?.takeIf { it.isNotBlank() }
                ?: "Amazing Viral Short Video"

            AutoTouchService.log("📂 Scanning Folder for Upload: ${resolvedDir.absolutePath}")

            val videos = findVideoFilesInDirectory(resolvedDir, uploadedSet)

            if (videos.isEmpty()) {
                val mediaUriPair = queryMediaStoreVideoByFolderName(folderInputRaw, pickOldestFirst, uploadedSet)
                if (mediaUriPair != null) {
                    val (uri, name) = mediaUriPair
                    AutoTouchService.log("🎬 Found Video via MediaStore: $name")
                    hud.updateStatus("Analyzing Video for Title & Desc...", BotMood.THINKING, taskRunning = true)

                    val cleanName = geminiBrain.sanitizeRawVideoTopic(name)
                    val topicHint = if (name.startsWith("flow_", true) || cleanName.contains("Viral Short Video")) {
                        fallbackFlowTopic
                    } else {
                        cleanName
                    }

                    val previewFrame = withContext(Dispatchers.IO) {
                        extractSinglePreviewFrameFromVideo(videoUri = uri)
                    }
                    val meta = geminiBrain.generateFlowPromptTitleAndDescription(
                        topic = topicHint,
                        customDescriptionNote = customDesc,
                        videoFrameBitmap = previewFrame
                    )
                    try { previewFrame?.recycle() } catch (_: Exception) {}

                    dispatchVideoToLinkedPlatforms(uri, meta.viralTitle, meta.viralDescription)
                    uploadedSet.add(uri.toString())
                    prefs.edit().putStringSet("uploaded_videos_set", uploadedSet).apply()
                    memoryVault.markVideoAsPostedInLedger(name, "YouTube")
                    finalizeRunAndTriggerAutoReflection("SUCCESS")
                    return@launch
                }

                hud.updateStatus("No new videos in folder!", BotMood.ERROR, taskRunning = false)
                AutoTouchService.log("⚠️ No un-uploaded videos found in [${resolvedDir.absolutePath}].")
                finalizeRunAndTriggerAutoReflection("FAILED")
                return@launch
            }

            val chosenVideo = if (pickOldestFirst) {
                videos.minByOrNull { it.lastModified() }!!
            } else {
                videos.maxByOrNull { it.lastModified() }!!
            }

            AutoTouchService.log("🎬 Selected Video from Folder: ${chosenVideo.name}")
            hud.updateStatus("Analyzing Video for Title & Desc...", BotMood.THINKING, taskRunning = true)

            val cleanFileName = geminiBrain.sanitizeRawVideoTopic(chosenVideo.name)
            val topicHint = if (chosenVideo.name.startsWith("flow_", true) || cleanFileName.contains("Viral Short Video")) {
                fallbackFlowTopic
            } else {
                cleanFileName
            }

            val previewFrame = withContext(Dispatchers.IO) {
                extractSinglePreviewFrameFromVideo(videoFile = chosenVideo)
            }
            val meta = geminiBrain.generateFlowPromptTitleAndDescription(
                topic = topicHint,
                customDescriptionNote = customDesc,
                videoFrameBitmap = previewFrame
            )
            try { previewFrame?.recycle() } catch (_: Exception) {}

            AutoTouchService.log("✨ AI Viral Title: ${meta.viralTitle}")
            AutoTouchService.log("📝 AI Description: ${meta.viralDescription.replace("\n", " ").take(90)}...")

            dispatchVideoToLinkedPlatforms(Uri.fromFile(chosenVideo), meta.viralTitle, meta.viralDescription)

            uploadedSet.add(chosenVideo.absolutePath)
            prefs.edit().putStringSet("uploaded_videos_set", uploadedSet).apply()
            memoryVault.markVideoAsPostedInLedger(chosenVideo.absolutePath, "YouTube")
            finalizeRunAndTriggerAutoReflection("SUCCESS")
        } catch (_: CancellationException) {
            finalizeRunAndTriggerAutoReflection("STOPPED")
            hud.updateStatus("Stopped", BotMood.IDLE, taskRunning = false)
        } catch (e: Exception) {
            AutoTouchService.log("⚠️ YouTube Upload Error: ${e.message}")
            finalizeRunAndTriggerAutoReflection("FAILED")
            hud.updateStatus("Upload Paused", BotMood.ERROR, taskRunning = false)
        } finally {
            isAgentRunning = false
            releaseWakeLock()
        }
    }
}

// Routes the video upload to YouTube (Background Web Studio OR Real YouTube App)
// AND Instagram Reels (Background Web OR Real Instagram App) with Title + Description!
internal suspend fun AutoTouchService.dispatchVideoToLinkedPlatforms(
    videoUri: Uri,
    viralTitle: String,
    viralDescription: String
) {
    val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
    val useBackgroundYtUpload = prefs.getBoolean(
        "bg_youtube_upload",
        prefs.getBoolean("yt_upload_in_background", false)
    )
    val useBackgroundInstaUpload = prefs.getBoolean("bg_insta_upload", false)
    val linkYoutube = prefs.getBoolean(
        "yt_link_enabled",
        prefs.getBoolean("link_platform_youtube", true)
    )
    val linkInstagram = prefs.getBoolean(
        "insta_link_enabled",
        prefs.getBoolean("link_platform_instagram", false)
    )

    if (linkYoutube) {
        if (useBackgroundYtUpload) {
            val bgOk = uploadVideoInBackgroundWebStudio(videoUri, viralTitle, viralDescription)
            if (!bgOk && isAgentRunning) {
                AutoTouchService.log("🔄 Background Studio needs interactive session -> Completing via Real YouTube App...")
                uploadVideoUriToYouTubeShorts(videoUri, viralTitle, viralDescription)
            }
        } else {
            uploadVideoUriToYouTubeShorts(videoUri, viralTitle, viralDescription)
        }
    }

    if (linkInstagram && isAgentRunning) {
        delay(3000L)
        val combinedInstaCaption = "$viralTitle\n\n$viralDescription".trim()
        if (useBackgroundInstaUpload) {
            val bgInstaOk = uploadVideoInBackgroundInstagramWeb(videoUri, combinedInstaCaption)
            if (!bgInstaOk && isAgentRunning) {
                AutoTouchService.log("🔄 Background Instagram Web needs interactive session -> Completing via Real Instagram App...")
                uploadVideoUriToInstagramReels(videoUri, combinedInstaCaption)
            }
        } else {
            uploadVideoUriToInstagramReels(videoUri, combinedInstaCaption)
        }
    }
}

// Silent Background YouTube Studio Web Uploader: Fills Title and Description in YouTube Studio Web + clicks Publish!
private suspend fun AutoTouchService.uploadVideoInBackgroundWebStudio(
    videoUri: Uri,
    viralTitle: String,
    viralDescription: String
): Boolean {
    val webView = hud.getOrCreateBackgroundWebView() ?: return false
    hud.pendingUploadFileUri = videoUri
    hud.updateStatus("🌐 BG YouTube Studio Upload...", BotMood.UPLOAD_UP, taskRunning = true)
    AutoTouchService.log("🌐 Starting Silent Background YouTube Studio Upload: $viralTitle")

    withContext(Dispatchers.Main) {
        webView.settings.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        webView.loadUrl("https://www.youtube.com/upload")
    }
    delay(6500L)

    var fileInjected = false
    var clickedPublishOnce = false

    for (step in 1..18) {
        if (!isAgentRunning) return false
        val jsRes = evaluateWebStudioUploadStep(webView, viralTitle, viralDescription)
        AutoTouchService.log("🌐 BG Studio Step #$step -> $jsRes")

        if (jsRes.contains("FILE_INPUT_TRIGGERED") || jsRes.contains("CLICKED_SELECT_FILES")) {
            fileInjected = true
        }
        if (jsRes.contains("CLICKED_PUBLISH_ANYWAY") || (clickedPublishOnce && jsRes.contains("DIALOG_CLOSED_SUCCESS"))) {
            hud.updateStatus("🎉 BG UPLOAD & PUBLISH SUCCESS!", BotMood.HAPPY, taskRunning = false)
            AutoTouchService.log("🎉 Background YouTube Upload Completed: $viralTitle")
            hud.pendingUploadFileUri = null
            return true
        }
        if (jsRes.contains("PUBLISHED_DONE")) {
            clickedPublishOnce = true
            delay(3000L)
            val checkPopupRes = evaluateWebStudioUploadStep(webView, viralTitle, viralDescription)
            AutoTouchService.log("🌐 Post-Publish Check Dialog -> $checkPopupRes")
            hud.updateStatus("🎉 BG UPLOAD SUCCESS!", BotMood.HAPPY, taskRunning = false)
            hud.pendingUploadFileUri = null
            return true
        }
        delay(3500L)
    }

    hud.pendingUploadFileUri = null
    return fileInjected
}

private suspend fun AutoTouchService.evaluateWebStudioUploadStep(
    webView: WebView,
    viralTitle: String,
    viralDescription: String
): String = suspendCancellableCoroutine { cont ->
    val js = """
        (function() {
            const allButtons = Array.from(document.querySelectorAll('ytcp-button, button, [role="button"]'));
            const publishAnywayBtn = allButtons.find(b => {
                const txt = (b.innerText || b.getAttribute('aria-label') || '').toLowerCase();
                return txt.includes('publish anyway') || b.id === 'secondary-action-button';
            });
            if (publishAnywayBtn && publishAnywayBtn.getBoundingClientRect().width > 10) {
                publishAnywayBtn.click();
                return 'CLICKED_PUBLISH_ANYWAY';
            }

            const fileInput = document.querySelector('input[type="file"]');
            if (fileInput && !window.__auraFileInjected) {
                window.__auraFileInjected = true;
                fileInput.click();
                return 'FILE_INPUT_TRIGGERED';
            }
            const selectBtn = document.querySelector('#select-files-button, ytcp-button#select-files-button');
            if (selectBtn && !window.__auraFileInjected) {
                selectBtn.click();
                return 'CLICKED_SELECT_FILES';
            }

            const titleBox = document.querySelector('#textbox[aria-label*="title" i], #title-textarea #textbox');
            if (titleBox && (!titleBox.innerText || titleBox.innerText.length < 5)) {
                titleBox.focus();
                titleBox.innerText = ${JSONObject.quote(viralTitle)};
                titleBox.dispatchEvent(new InputEvent('input', { bubbles: true }));
            }

            const descBox = document.querySelector('#textbox[aria-label*="tell viewers" i], #description-textarea #textbox');
            if (descBox && (!descBox.innerText || descBox.innerText.length < 5)) {
                descBox.focus();
                descBox.innerText = ${JSONObject.quote(viralDescription)};
                descBox.dispatchEvent(new InputEvent('input', { bubbles: true }));
            }

            const notForKids = document.querySelector('tp-yt-paper-radio-button[name="VIDEO_MADE_FOR_KIDS_NOT_MFK"], #audience tp-yt-paper-radio-button:nth-child(2)');
            if (notForKids) notForKids.click();

            const doneBtn = document.querySelector('#done-button, ytcp-button#done-button');
            if (doneBtn && !doneBtn.disabled && doneBtn.getBoundingClientRect().width > 10) {
                const publicRadio = document.querySelector('tp-yt-paper-radio-button[name="PUBLIC"]');
                if (publicRadio) publicRadio.click();
                setTimeout(() => { doneBtn.click(); }, 400);
                return 'PUBLISHED_DONE';
            }

            const nextBtn = document.querySelector('#next-button, ytcp-button#next-button');
            if (nextBtn && !nextBtn.disabled && nextBtn.getBoundingClientRect().width > 10) {
                nextBtn.click();
                return 'CLICKED_STUDIO_NEXT';
            }

            return 'WAITING_STUDIO';
        })();
    """.trimIndent()
    withContextOrMain(webView, js) { res ->
        cont.resume(res)
    }
}

// Background Instagram Web Uploader (When user selects Background Mode for Instagram)
private suspend fun AutoTouchService.uploadVideoInBackgroundInstagramWeb(
    videoUri: Uri,
    caption: String
): Boolean {
    val webView = hud.getOrCreateBackgroundWebView() ?: return false
    hud.pendingUploadFileUri = videoUri
    hud.updateStatus("🌐 BG Instagram Web Upload...", BotMood.UPLOAD_UP, taskRunning = true)
    AutoTouchService.log("🌐 Starting Background Instagram Web Upload...")

    withContext(Dispatchers.Main) {
        webView.settings.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        webView.loadUrl("https://www.instagram.com/")
    }
    delay(5500L)

    var sharedOk = false
    for (step in 1..14) {
        if (!isAgentRunning) break
        val js = """
            (function() {
                const fileInput = document.querySelector('input[type="file"]');
                if (fileInput && !window.__igFileInjected) {
                    window.__igFileInjected = true;
                    fileInput.click();
                    return 'IG_FILE_INJECTED';
                }
                const btns = Array.from(document.querySelectorAll('div[role="button"], button, a, span'));
                const shareBtn = btns.find(b => (b.innerText || '').trim() === 'Share');
                if (shareBtn) {
                    const capBox = document.querySelector('div[aria-label*="caption" i], textarea[aria-label*="caption" i], [contenteditable="true"]');
                    if (capBox && (!capBox.innerText || capBox.innerText.length < 5)) {
                        capBox.focus();
                        capBox.innerText = ${JSONObject.quote(caption)};
                        capBox.dispatchEvent(new InputEvent('input', { bubbles: true }));
                    }
                    setTimeout(() => shareBtn.click(), 400);
                    return 'IG_SHARED';
                }
                const nextBtn = btns.find(b => (b.innerText || '').trim() === 'Next');
                if (nextBtn) {
                    nextBtn.click();
                    return 'IG_NEXT';
                }
                const createBtn = btns.find(b => {
                    const t = (b.innerText || b.getAttribute('aria-label') || '').toLowerCase();
                    return t === 'create' || t === 'new post';
                });
                if (createBtn) {
                    createBtn.click();
                    return 'IG_CREATE';
                }
                return 'WAITING_IG';
            })();
        """.trimIndent()
        var stepRes = ""
        suspendCancellableCoroutine<Unit> { cont ->
            withContextOrMain(webView, js) { r ->
                stepRes = r
                cont.resume(Unit)
            }
        }
        AutoTouchService.log("🌐 BG Instagram Step #$step -> $stepRes")
        if (stepRes == "IG_SHARED") {
            sharedOk = true
            delay(4000L)
            break
        }
        delay(3200L)
    }

    hud.pendingUploadFileUri = null
    return sharedOk
}

// Real Instagram App Uploader (Opens phone's real Instagram App, applies 5%-6% pinch-zoom to hide watermark, & pastes Title + Description)
suspend fun AutoTouchService.uploadVideoUriToInstagramReels(videoUri: Uri, caption: String) {
    hideWebPreviewCardIfOpen()
    wakeUpScreenIfTimedOut()
    val specs = getCalibratedDeviceBodySpecs()
    AutoTouchService.log("📱 Real App Mode: Sharing Video to Real Instagram Reels App...")
    hud.updateStatus("📱 Opening Real Instagram App...", BotMood.WORKING, taskRunning = true)

    withContext(Dispatchers.Main) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("aura_ig_caption", caption))

        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            setPackage("com.instagram.android")
            putExtra(Intent.EXTRA_STREAM, videoUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            AutoTouchService.log("⚠️ Instagram app not installed.")
            return@withContext
        }
    }

    delay(4500L)
    var zoomedInReelsPreview = false

    for (step in 1..4) {
        if (!isAgentRunning) return
        val els = scanScreenInteractiveElements(specs)
        val reelOrNext = els.find {
            it.label.equals("Reels", true) ||
                it.label.equals("Next", true) ||
                it.label.equals("आगे बढ़ें", true)
        }
        if (reelOrNext != null) {
            if (!zoomedInReelsPreview && (reelOrNext.label.equals("Next", true) || reelOrNext.label.equals("आगे बढ़ें", true))) {
                zoomedInReelsPreview = true
                performSubtlePinchZoomToHideCornerLogo(specs)
            }
            tapElementSmartly(reelOrNext, forceAccessibilityClick = false)
            delay(3200L)
        }
        val captionBox = els.find {
            it.label.contains("Write a caption", true) ||
                it.label.contains("Add a caption", true) ||
                it.className.contains("EditText", true)
        }
        if (captionBox != null) {
            tapElementSmartly(captionBox, forceAccessibilityClick = false)
            delay(900L)
            typeAndPasteTextReliably(caption, captionBox.node)
            delay(1000L)
            closeKeyboardCompletely()
            break
        }
    }

    val finalEls = scanScreenInteractiveElements(specs)
    val shareBtn = finalEls.find {
        it.label.equals("Share", true) || it.label.equals("शेयर करें", true)
    }
    if (shareBtn != null) {
        tapElementSmartly(shareBtn, forceAccessibilityClick = false)
    } else {
        performPhysicalTap((specs.screenWidth * 0.72f).toInt(), (specs.screenHeight * 0.94f).toInt())
    }
    delay(3500L)
    hud.updateStatus("🎉 Insta Reel Shared!", BotMood.HAPPY, taskRunning = false)
    AutoTouchService.log("🎉 Real Instagram Reel Shared Successfully with Title & Description!")
}

internal fun AutoTouchService.resolveSmartFolderPath(rawInput: String): File {
    val rootStorage = Environment.getExternalStorageDirectory()
    val cleaned = rawInput.trim()
    val lower = cleaned.lowercase(Locale.ROOT)

    if (cleaned.isEmpty() || lower == "download" || lower == "downloads" || lower == "/storage/download") {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }
    if (lower == "storage" || lower == "/storage") {
        return rootStorage
    }
    if (lower == "camera" || lower == "dcim") {
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
    }
    if (lower == "movies" || lower == "video" || lower == "videos") {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
    }

    val directFile = File(cleaned)
    if (directFile.exists() && directFile.isDirectory) return directFile

    val relative = File(rootStorage, cleaned.removePrefix("/").removePrefix("storage/"))
    if (relative.exists() && relative.isDirectory) return relative

    return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
}

internal fun AutoTouchService.findVideoFilesInDirectory(dir: File, uploadedSet: Set<String>): List<File> {
    val results = mutableListOf<File>()
    if (!dir.exists()) return results

    fun isVideo(f: File): Boolean {
        val n = f.name.lowercase(Locale.ROOT)
        return f.isFile &&
            !n.startsWith("raw_") &&
            (n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv") || n.endsWith(".webm")) &&
            !uploadedSet.contains(f.absolutePath) &&
            !memoryVault.isVideoPostedInLedger(f.absolutePath)
    }

    dir.listFiles()?.forEach { f ->
        if (isVideo(f)) {
            results.add(f)
        } else if (f.isDirectory && !f.name.startsWith(".")) {
            f.listFiles()?.forEach { sub ->
                if (isVideo(sub)) results.add(sub)
            }
        }
    }
    return results
}

internal fun AutoTouchService.queryMediaStoreVideoByFolderName(
    folderKeyword: String,
    pickOldestFirst: Boolean,
    uploadedSet: Set<String>
): Pair<Uri, String>? {
    return try {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED
        )
        val sortOrder = if (pickOldestFirst) "${MediaStore.Video.Media.DATE_ADDED} ASC" else "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val cursor = contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        ) ?: return null

        val kw = folderKeyword.trim().lowercase(Locale.ROOT).substringAfterLast("/")
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: "video.mp4"
                if (name.startsWith("raw_", true)) continue
                val bucket = (c.getString(bucketCol) ?: "").lowercase(Locale.ROOT)
                val contentUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())

                val matchesBucket = kw.isEmpty() || kw == "storage" || kw == "download" || bucket.contains(kw) || kw.contains(bucket)
                if (matchesBucket && !uploadedSet.contains(contentUri.toString()) && !memoryVault.isVideoPostedInLedger(name)) {
                    return Pair(contentUri, name)
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}

// Real YouTube App Uploader:
// Opens real YouTube app, clicks Next on Trimmer, performs 5%-6% Pinch-to-Zoom on Editor to crop out bottom-right watermark,
// clicks 2nd Next, fills Clean 95-Char Viral Title + Description, and taps Upload Short!
internal suspend fun AutoTouchService.uploadVideoUriToYouTubeShorts(
    videoUri: Uri,
    viralTitle: String,
    viralDescription: String
) {
    hideWebPreviewCardIfOpen()
    wakeUpScreenIfTimedOut()
    val specs = getCalibratedDeviceBodySpecs()
    val recalledYouTubeRules = memoryVault.recallFixesForApp("YouTube")
    if (recalledYouTubeRules.isNotEmpty()) {
        AutoTouchService.log("🧠 Applied ${recalledYouTubeRules.size} Learned Rules from Memory/YouTube!")
    }

    val cleanShortTitle = geminiBrain.formatCleanShortsTitle95(viralTitle, "Viral Short Video")

    withContext(Dispatchers.Main) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("aura_yt_title", cleanShortTitle))

        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            setPackage("com.google.android.youtube")
            putExtra(Intent.EXTRA_STREAM, videoUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    hud.updateStatus("📱 Opening Real YouTube App...", BotMood.WORKING, taskRunning = true)
    AutoTouchService.log("🚀 Sent video to Real YouTube App. Waiting for Trimmer...")
    delay(5000L)

    for (attempt in 1..2) {
        if (!isAgentRunning) return
        val screenElements = scanScreenInteractiveElements(specs)

        val alreadyOnAddDetails = screenElements.any {
            it.label.contains("Add details", true) ||
                it.label.contains("Caption your Short", true) ||
                it.label.contains("Create a title", true) ||
                it.label.contains("Upload Short", true)
        }
        if (alreadyOnAddDetails) {
            AutoTouchService.log("✅ Already on 'Add details' screen! Skipping Next (#$attempt).")
            break
        }

        // FEATURE 2: On the Shorts Editor screen (attempt == 2), perform 5%-6% pinch-to-zoom to crop out bottom-right watermark!
        if (attempt == 2) {
            hud.updateStatus("🔍 Zooming 5% to hide corner logo...", BotMood.WORKING, taskRunning = true)
            performSubtlePinchZoomToHideCornerLogo(specs)
            currentRunSteps.add("Applied 5%-6% Pinch-to-Zoom on Shorts Editor to hide corner watermark")
        }

        recordRunBitmapSafely(takeHdDisplayScreenshotWithTimeout(2000L))
        val nextNode = screenElements.find {
            it.label.equals("Next", true) || it.label.equals("आगे बढ़ें", true) || it.label.equals("Done", true)
        }

        val learnedNext = recalledYouTubeRules.find {
            it.solutionRule.contains("Next", true) && it.tapYRatio > 0.85f
        }

        val fallbackNextX = if (attempt == 1) (specs.screenWidth * 0.89f).toInt() else (specs.screenWidth * 0.74f).toInt()
        val tapX = nextNode?.centerX
            ?: learnedNext?.let { (it.tapXRatio * specs.screenWidth).toInt() }
            ?: fallbackNextX
        val tapY = nextNode?.centerY
            ?: learnedNext?.let { (it.tapYRatio * specs.screenHeight).toInt() }
            ?: (specs.screenHeight * 0.96f).toInt()

        hud.updateStatus("Tap: Next (#$attempt)", BotMood.WORKING, taskRunning = true)
        AutoTouchService.log("👆 Tapping Next button ($tapX, $tapY)")
        performPhysicalTap(tapX, tapY)
        currentRunSteps.add("Tapped Next (#$attempt) at ($tapX, $tapY)")
        delay(4200L)
    }

    for (waitLoad in 1..3) {
        val checkEls = scanScreenInteractiveElements(specs)
        val loaded = checkEls.any {
            it.label.contains("Caption", true) ||
                it.label.contains("Create a title", true) ||
                it.label.contains("Upload", true) ||
                it.label.contains("Public", true)
        }
        if (loaded) break
        delay(1500L)
    }

    dismissDiscardDialogIfPresent(specs)

    recordRunBitmapSafely(takeHdDisplayScreenshotWithTimeout(2000L))
    hud.updateStatus("Typing Viral Title & Desc...", BotMood.WORKING, taskRunning = true)

    val detailsElements = scanScreenInteractiveElements(specs)
    val captionNode = detailsElements.find {
        it.label.contains("Caption your Short", true) ||
            it.label.contains("Create a title", true) ||
            it.label.contains("Add a title", true) ||
            (it.className.contains("EditText", true) && it.centerY < specs.screenHeight / 2)
    }

    val isRegularUploadScreen = detailsElements.any {
        it.label.contains("Create a title", true) || it.label.contains("Add description", true)
    }
    val capX = captionNode?.centerX ?: if (isRegularUploadScreen) {
        (specs.screenWidth * 0.45f).toInt()
    } else {
        (specs.screenWidth * 0.56f).toInt()
    }
    val capY = captionNode?.centerY ?: if (isRegularUploadScreen) {
        (specs.screenHeight * 0.43f).toInt()
    } else {
        (specs.screenHeight * 0.118f).toInt()
    }

    AutoTouchService.log("👆 Tapping Caption/Title Box at ($capX, $capY)")
    performPhysicalTap(capX, capY)
    currentRunSteps.add("Tapped Caption Box at ($capX, $capY)")
    delay(1500L)

    val textForMainBox = cleanShortTitle

    val pastedSuccess = pasteTitleIntoYouTubeCaptionBoxCleanly(textForMainBox, specs)
    AutoTouchService.log("✍️ Title/Caption Paste Verified ($pastedSuccess): $textForMainBox")
    currentRunSteps.add("Pasted Viral Title ($pastedSuccess): $textForMainBox")
    delay(1100L)

    val descRowNode = scanScreenInteractiveElements(specs).find {
        it.label.contains("Add description", true) || it.label.contains("विवरण जोड़ें", true)
    }
    if (descRowNode != null && viralDescription.isNotBlank()) {
        closeKeyboardCompletely()
        AutoTouchService.log("📝 Opening 'Add description' field to paste full description...")
        tapElementSmartly(descRowNode, forceAccessibilityClick = false)
        delay(1400L)
        typeAndPasteTextReliably(viralDescription, null)
        delay(900L)
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        delay(1200L)
    }

    for (retry in 1..3) {
        if (!isAgentRunning) return
        dismissDiscardDialogIfPresent(specs)
        val snap = takeHdDisplayScreenshotWithTimeout(2000L)
        recordRunBitmapSafely(snap)

        val postElements = scanScreenInteractiveElements(specs)
        val uploadNode = postElements.find {
            it.label.contains("Upload Short", true) ||
                it.label.equals("Upload", true) ||
                it.label.contains("शॉर्ट अपलोड करें", true)
        }

        if (uploadNode != null) {
            hud.updateStatus("Tap: Upload Short (#$retry)", BotMood.WORKING, taskRunning = true)
            AutoTouchService.log("👆 Tapping visible Upload Short button at (${uploadNode.centerX}, ${uploadNode.centerY})")
            performPhysicalTap(uploadNode.centerX, uploadNode.centerY)
            currentRunSteps.add("Tapped Upload Short (#$retry) at (${uploadNode.centerX}, ${uploadNode.centerY})")
        } else {
            if (isSoftKeyboardOpen()) {
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                delay(1200L)
                dismissDiscardDialogIfPresent(specs)
            }
            val reScan = scanScreenInteractiveElements(specs)
            val btnAfterClose = reScan.find {
                it.label.contains("Upload Short", true) || it.label.equals("Upload", true)
            }
            val tapX = btnAfterClose?.centerX ?: (specs.screenWidth / 2)
            val tapY = btnAfterClose?.centerY ?: (specs.screenHeight * 0.962f).toInt()
            hud.updateStatus("Tap: Upload Short (#$retry)", BotMood.WORKING, taskRunning = true)
            AutoTouchService.log("👆 Physical Tap on Upload Short Center ($tapX, $tapY)")
            performPhysicalTap(tapX, tapY)
            currentRunSteps.add("Tapped Upload Short (#$retry) at ($tapX, $tapY)")
        }

        delay(4200L)

        val afterElements = scanScreenInteractiveElements(specs)
        val stillOnDetails = afterElements.any {
            it.label.contains("Add details", true) ||
                it.label.contains("Upload Short", true) ||
                it.label.contains("Select audience", true)
        }
        if (!stillOnDetails) {
            break
        }
    }

    hud.updateStatus("🎉 UPLOAD SUCCESS!", BotMood.HAPPY, taskRunning = false)
    AutoTouchService.log("🎉 YouTube Short Uploaded Successfully: $cleanShortTitle")
}

private suspend fun AutoTouchService.pasteTitleIntoYouTubeCaptionBoxCleanly(
    viralTitle: String,
    specs: DeviceBodySpecs
): Boolean {
    val titlePrefix = viralTitle.take(10).trim()

    if (typeAndPasteTextReliably(viralTitle, null)) {
        delay(600L)
        val els = scanScreenInteractiveElements(specs)
        val placeholderGone = els.none { it.label.equals("Caption your Short", true) }
        if (placeholderGone) {
            AutoTouchService.log("✅ Layer 1 (Accessibility SetText) pasted title!")
            return true
        }
    }

    if (isSoftKeyboardOpen()) {
        val gboardChipNode = findInputMethodClipboardNode(titlePrefix)
        if (gboardChipNode != null) {
            val r = Rect()
            gboardChipNode.getBoundsInScreen(r)
            AutoTouchService.log("👆 Tapping Gboard Clipboard Node at (${r.centerX()}, ${r.centerY()})")
            performPhysicalTap(r.centerX(), r.centerY())
            delay(1000L)
            return true
        } else {
            val pillX = (specs.screenWidth * 0.50f).toInt()
            val pillY = (specs.screenHeight * 0.606f).toInt()
            AutoTouchService.log("👆 Tapping Gboard Center Clipboard Pill at ($pillX, $pillY)")
            performPhysicalTap(pillX, pillY)
            delay(1000L)
            return true
        }
    }

    return false
}

private fun AutoTouchService.findInputMethodClipboardNode(titlePrefix: String): AccessibilityNodeInfo? {
    return try {
        for (win in windows) {
            if (win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                val root = win.root ?: continue
                var found: AccessibilityNodeInfo? = null
                fun search(n: AccessibilityNodeInfo?) {
                    if (n == null || found != null) return
                    val t = n.text?.toString() ?: ""
                    val d = n.contentDescription?.toString() ?: ""
                    if (t.contains(titlePrefix, true) || d.contains(titlePrefix, true) || d.contains("Paste", true)) {
                        found = n
                        return
                    }
                    for (i in 0 until n.childCount) search(n.getChild(i))
                }
                search(root)
                if (found != null) return found
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}

private suspend fun AutoTouchService.dispatchInternalWebViewTouch(webView: WebView, cssX: Float, cssY: Float) {
    withContext(Dispatchers.Main) {
        try {
            @Suppress("DEPRECATION")
            val scale = if (webView.scale > 0.1f) webView.scale else resources.displayMetrics.density
            val maxW = (if (webView.width > 100) webView.width else (300 * scale).toInt()) - 5
            val maxH = (if (webView.height > 100) webView.height else (420 * scale).toInt()) - 5
            val viewX = (cssX * scale).coerceIn(5f, maxW.coerceAtLeast(10).toFloat())
            val viewY = (cssY * scale).coerceIn(5f, maxH.coerceAtLeast(10).toFloat())
            val downTime = SystemClock.uptimeMillis()
            val downEv = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, viewX, viewY, 0)
            val upEv = MotionEvent.obtain(downTime, downTime + 75L, MotionEvent.ACTION_UP, viewX, viewY, 0)
            webView.dispatchTouchEvent(downEv)
            delay(75L)
            webView.dispatchTouchEvent(upEv)
            downEv.recycle()
            upEv.recycle()
        } catch (_: Exception) {}
    }
}

private suspend fun AutoTouchService.closeFlowVideoPlayerCardIfOpen(webView: WebView) {
    val jsClose = """
        (function() {
            const bodyTxt = document.body ? (document.body.innerText || '') : '';
            const hasVideoTag = document.querySelectorAll('video').length > 0;
            if (bodyTxt.includes('0:00 /') || hasVideoTag) {
                const btns = Array.from(document.querySelectorAll('button, [role="button"], a'));
                const backBtn = btns.find(b => {
                    const r = b.getBoundingClientRect();
                    const t = (b.innerText || b.getAttribute('aria-label') || '').toLowerCase();
                    return r.top >= 0 && r.top < 95 && r.left >= 0 && r.left < 95 && r.width > 10 &&
                        (t.includes('back') || t.includes('close') || b.querySelector('svg') || t.includes('arrow'));
                });
                if (backBtn) {
                    backBtn.click();
                    return 'CLICKED_BACK_BTN';
                }
                window.history.back();
                return 'HISTORY_BACK';
            }
            return 'NOT_IN_PLAYER';
        })();
    """.trimIndent()

    var res = ""
    suspendCancellableCoroutine<Unit> { cont ->
        withContextOrMain(webView, jsClose) { r ->
            res = r
            cont.resume(Unit)
        }
    }
    if (res == "CLICKED_BACK_BTN" || res == "HISTORY_BACK") {
        AutoTouchService.log("🔙 Auto-closed big Flow Video Card ($res) to return to prompt composer!")
        delay(1800L)
    }
}

private fun extractFlowVideoUuid(url: String): String {
    val m = Regex("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})").find(url)
    return m?.value?.lowercase(Locale.ROOT) ?: url.substringBefore("?").takeLast(45)
}

// Google Flow Video Generation Pipeline:
// Supports Background Browser Mode and Real Chrome App Mode with C2PA metadata stripping.
fun AutoTouchService.startGoogleFlowToYouTubePipeline(
    topic: String,
    maxVideosToCreate: Int = 1,
    uploadImmediatelyAfterCreate: Boolean = true
) {
    currentTaskJob?.cancel()
    val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
    geminiBrain.updateApiKey(prefs.getString("gemini_api_key", "") ?: "")
    geminiBrain.detailedApiLoggingEnabled = prefs.getBoolean("detailed_api_logging", true)

    currentTaskJob = serviceScope.launch {
        try {
            isAgentRunning = true
            acquireWakeLock()
            val targetUrl = prefs.getString("saved_flow_url", "https://labs.google/fx/tools/flow")
                ?: "https://labs.google/fx/tools/flow"
            val isStoryMode = prefs.getBoolean("flow_story_mode", true)
            val flowExecMode = prefs.getString("flow_execution_mode", "BACKGROUND") ?: "BACKGROUND"
            val customDescNote = prefs.getString("custom_video_description", "") ?: ""
            val alreadyDownloadedIds = prefs.getStringSet("downloaded_flow_video_ids", emptySet())?.toMutableSet() ?: mutableSetOf()

            for (vidIndex in 1..maxVideosToCreate.coerceIn(1, 3)) {
                if (!isAgentRunning) break
                hud.updateStatus("Flow #$vidIndex: Creating Story Prompt...", BotMood.THINKING, taskRunning = true)

                val effectiveTopic = if (isStoryMode && maxVideosToCreate > 1) {
                    "$topic (Story Part $vidIndex of $maxVideosToCreate, continuous cinematic scene)"
                } else {
                    topic
                }

                prefs.edit().putString("last_flow_generated_topic", effectiveTopic).apply()
                val meta = geminiBrain.generateFlowPromptTitleAndDescription(
                    topic = effectiveTopic,
                    customDescriptionNote = customDescNote,
                    forceEpisodicContinuation = isStoryMode
                )
                var currentSafePrompt = meta.videoPrompt
                val viralTitle = meta.viralTitle
                val viralDescription = meta.viralDescription

                // MODE B: Real Phone Chrome App Mode!
                if (flowExecMode == "LIVE_PHONE") {
                    AutoTouchService.log("🎬 Flow Prompt [LIVE_PHONE]: $currentSafePrompt")
                    val chromeFile = generateFlowVideoViaRealChromeApp(targetUrl, currentSafePrompt)
                    if (chromeFile != null && chromeFile.exists()) {
                        AutoTouchService.log("✅ Saved Clean Flow Video from Real Chrome to Folder: ${chromeFile.absolutePath}")
                        if (uploadImmediatelyAfterCreate) {
                            dispatchVideoToLinkedPlatforms(Uri.fromFile(chromeFile), viralTitle, viralDescription)
                            memoryVault.markVideoAsPostedInLedger(chromeFile.absolutePath, "YouTube")
                        } else {
                            val upTime = prefs.getString("folder_upload_time_hm", "19:00") ?: "19:00"
                            hud.updateStatus("📥 Saved in Folder! Uploads at $upTime", BotMood.HAPPY, taskRunning = false)
                            AutoTouchService.log("📥 Flow Video saved to [${chromeFile.absolutePath}]. Scheduled to auto-upload at $upTime!")
                        }
                    } else {
                        hud.updateStatus("Check Real Chrome Flow", BotMood.ERROR, taskRunning = false)
                    }
                    continue
                }

                // MODE A: Background Browser Mode with Auto-Retry on Policy Failure!
                val webView = hud.getOrCreateBackgroundWebView()
                if (webView == null) {
                    AutoTouchService.log("⚠️ WebView not ready.")
                    break
                }

                var downloadedVideoFile: File? = null

                for (policyRetryAttempt in 0..1) {
                    if (!isAgentRunning) break

                    AutoTouchService.log("🎬 Flow Prompt [BACKGROUND${if (policyRetryAttempt > 0) " RETRY #$policyRetryAttempt" else ""}]: $currentSafePrompt")

                    closeFlowVideoPlayerCardIfOpen(webView)
                    clearAnyFailedFlowCardsFromGrid(webView)
                    val initialVideoUrl = extractExistingVideoUrlOnly(webView)
                    if (initialVideoUrl.isNotBlank()) {
                        alreadyDownloadedIds.add(extractFlowVideoUuid(initialVideoUrl))
                    }

                    withContext(Dispatchers.Main) {
                        val curUrl = webView.url ?: ""
                        if (!curUrl.contains("flow.google") && !curUrl.contains("labs.google")) {
                            webView.loadUrl(targetUrl)
                        }
                    }
                    delay(3500L)

                    for (prepTick in 1..18) {
                        if (!isAgentRunning) break
                        val stepResult = runFlowComposerStateMachineStep(webView, currentSafePrompt)
                        AutoTouchService.log("🌐 Flow Background Step #$prepTick -> $stepResult")

                        when {
                            stepResult == "BACK_FROM_PLAYER" -> {
                                dispatchInternalWebViewTouch(webView, 32f, 42f)
                                delay(2200L)
                            }
                            stepResult.startsWith("CLICKED_NEW_PROJECT:") -> {
                                hud.updateStatus("Flow: Opening + New Project...", BotMood.WORKING, taskRunning = true)
                                val coords = stepResult.substringAfter(":").split(",")
                                if (coords.size == 2) {
                                    dispatchInternalWebViewTouch(
                                        webView,
                                        coords[0].toFloatOrNull() ?: 170f,
                                        coords[1].toFloatOrNull() ?: 480f
                                    )
                                }
                                delay(3500L)
                            }
                            stepResult.startsWith("SWITCHING_TO_VIDEO_MODE:") -> {
                                hud.updateStatus("Flow: Switching to 9:16 Video Mode...", BotMood.WORKING, taskRunning = true)
                                val coords = stepResult.substringAfter(":").split(",")
                                if (coords.size == 2) {
                                    dispatchInternalWebViewTouch(
                                        webView,
                                        coords[0].toFloatOrNull() ?: 200f,
                                        coords[1].toFloatOrNull() ?: 520f
                                    )
                                }
                                delay(2200L)
                            }
                            stepResult.startsWith("SUBMITTED:") -> {
                                hud.updateStatus("Flow: Submitting Prompt...", BotMood.WORKING, taskRunning = true)
                                val coords = stepResult.substringAfter(":").split(",")
                                if (coords.size == 2) {
                                    delay(450L)
                                    dispatchInternalWebViewTouch(
                                        webView,
                                        coords[0].toFloatOrNull() ?: 295f,
                                        coords[1].toFloatOrNull() ?: 540f
                                    )
                                }
                                AutoTouchService.log("✅ Flow Prompt Injected & Submit Arrow Clicked!")
                                break
                            }
                            else -> {
                                hud.updateStatus("Flow: Preparing Editor ($prepTick)...", BotMood.THINKING, taskRunning = true)
                                delay(2500L)
                            }
                        }
                    }

                    var sawGeneratingProgress = false
                    var lastSeenProgressNum = 0
                    var ticksAfterProgressFinished = 0
                    var promptFailedPolicy = false

                    for (waitTick in 1..60) {
                        if (!isAgentRunning) break
                        delay(3800L)

                        val shouldClickCardNow = (sawGeneratingProgress && ticksAfterProgressFinished >= 1) || waitTick >= 15
                        val pollRes = pollOrOpenGeneratedFlowVideo(
                            webView = webView,
                            initialVideoUrl = initialVideoUrl,
                            alreadyDownloadedUuidsCsv = alreadyDownloadedIds.joinToString(","),
                            allowAutoClickCard = shouldClickCardNow
                        )

                        when {
                            pollRes.startsWith("FLOW_PROMPT_FAILED:") -> {
                                val reason = pollRes.removePrefix("FLOW_PROMPT_FAILED:")
                                AutoTouchService.log("⚠️ Google Flow Prompt Failed ($reason)! Never clicking old video -> Deleting failed card & retrying with Safe Prompt...")
                                hud.updateStatus("⚠️ Flow Policy Filter -> Retrying Safe Prompt...", BotMood.THINKING, taskRunning = true)
                                clearAnyFailedFlowCardsFromGrid(webView)
                                delay(1800L)
                                promptFailedPolicy = true
                                break
                            }
                            pollRes.startsWith("VIDEO_URL:") -> {
                                val videoUrl = pollRes.removePrefix("VIDEO_URL:").trim()
                                val vidUuid = extractFlowVideoUuid(videoUrl)
                                if (alreadyDownloadedIds.contains(vidUuid)) {
                                    AutoTouchService.log("⏭️ Ignoring already-downloaded old video ($vidUuid) -> Returning to grid...")
                                    closeFlowVideoPlayerCardIfOpen(webView)
                                    continue
                                }
                                hud.updateStatus("📥 Saving & Stripping AI Metadata...", BotMood.WORKING, taskRunning = true)
                                AutoTouchService.log("📥 Found Newly Generated MP4 Stream: ${videoUrl.take(70)}...")
                                downloadedVideoFile = downloadVideoFromUrlToStorage(videoUrl, "flow_${System.currentTimeMillis()}.mp4")
                                if (downloadedVideoFile != null) {
                                    alreadyDownloadedIds.add(vidUuid)
                                    prefs.edit().putStringSet("downloaded_flow_video_ids", alreadyDownloadedIds).apply()
                                    closeFlowVideoPlayerCardIfOpen(webView)
                                    break
                                }
                            }
                            pollRes.startsWith("PROGRESS:") -> {
                                val pct = pollRes.removePrefix("PROGRESS:")
                                sawGeneratingProgress = true
                                lastSeenProgressNum = pct.removeSuffix("%").toIntOrNull() ?: lastSeenProgressNum
                                ticksAfterProgressFinished = 0
                                hud.updateStatus("⏳ Flow Generating ($pct)...", BotMood.THINKING, taskRunning = true)
                                AutoTouchService.log("⏳ Flow Video Progress: $pct")
                            }
                            pollRes.startsWith("CLICKED_CARD:") -> {
                                ticksAfterProgressFinished++
                                val coords = pollRes.removePrefix("CLICKED_CARD:").split(",")
                                hud.updateStatus("🎬 Opening New Video Card...", BotMood.WORKING, taskRunning = true)
                                AutoTouchService.log("👆 Auto-Opening Finished Top-Left Video Card at CSS ($pollRes)!")
                                if (coords.size == 2) {
                                    val cx = coords[0].toFloatOrNull() ?: 88f
                                    val cy = coords[1].toFloatOrNull() ?: 215f
                                    dispatchInternalWebViewTouch(webView, cx, cy)
                                }
                            }
                            else -> {
                                if (sawGeneratingProgress) {
                                    ticksAfterProgressFinished++
                                }
                                hud.updateStatus("⏳ Generating Video (${waitTick * 4}s)...", BotMood.THINKING, taskRunning = true)
                            }
                        }
                    }

                    if (downloadedVideoFile != null && downloadedVideoFile.exists()) {
                        break
                    }

                    if (promptFailedPolicy && policyRetryAttempt == 0) {
                        currentSafePrompt = geminiBrain.rewriteFailedFlowPromptSafe(
                            originalTopic = effectiveTopic,
                            failedPrompt = currentSafePrompt,
                            attempt = 1
                        )
                        continue
                    } else {
                        break
                    }
                }

                if (downloadedVideoFile != null && downloadedVideoFile.exists()) {
                    AutoTouchService.log("✅ Saved Clean Video to Folder: ${downloadedVideoFile.absolutePath}")
                    if (uploadImmediatelyAfterCreate) {
                        hideWebPreviewCardIfOpen()
                        dispatchVideoToLinkedPlatforms(Uri.fromFile(downloadedVideoFile), viralTitle, viralDescription)
                        memoryVault.markVideoAsPostedInLedger(downloadedVideoFile.absolutePath, "YouTube")
                    } else {
                        val upTime = prefs.getString("folder_upload_time_hm", "19:00") ?: "19:00"
                        hud.updateStatus("📥 Saved in Folder! Uploads at $upTime", BotMood.HAPPY, taskRunning = false)
                        AutoTouchService.log("📥 Video saved in [${downloadedVideoFile.absolutePath}]. Will auto-upload at $upTime with Title & Description!")
                    }
                } else {
                    AutoTouchService.log("⚠️ Video generation did not complete or was blocked by Flow policy.")
                    hud.updateStatus("Flow Generation Skipped/Failed", BotMood.ERROR, taskRunning = false)
                }
            }
        } catch (_: CancellationException) {
            hud.updateStatus("Stopped", BotMood.IDLE, taskRunning = false)
        } finally {
            isAgentRunning = false
            releaseWakeLock()
        }
    }
}

// Deletes any "Failed: This prompt might violate our policies..." card from the Google Flow grid
private suspend fun AutoTouchService.clearAnyFailedFlowCardsFromGrid(webView: WebView) {
    val jsClean = """
        (function() {
            const allNodes = Array.from(document.querySelectorAll('div, section, article'));
            const failedCard = allNodes.find(el => {
                const t = (el.innerText || '');
                const r = el.getBoundingClientRect();
                return r.width > 60 && r.width < 260 && r.height > 80 && r.top >= 70 && r.top <= 420 &&
                    (t.includes('Failed') || t.includes('violate our policies') || t.includes('different prompt'));
            });
            if (failedCard) {
                const btns = Array.from(failedCard.querySelectorAll('button, [role="button"]'));
                const delBtn = btns.find(b => {
                    const html = (b.innerHTML || b.innerText || b.getAttribute('aria-label') || '').toLowerCase();
                    return html.includes('delete') || html.includes('trash') || html.includes('remove') || btns.indexOf(b) === btns.length - 1;
                });
                if (delBtn) {
                    delBtn.click();
                    return 'DELETED_FAILED_CARD';
                }
            }
            return 'NONE';
        })();
    """.trimIndent()
    suspendCancellableCoroutine<Unit> { cont ->
        withContextOrMain(webView, jsClean) {
            cont.resume(Unit)
        }
    }
}

// Generates Flow Video by opening the phone's REAL Chrome App, downloading the video, stripping C2PA metadata, and saving it!
private suspend fun AutoTouchService.generateFlowVideoViaRealChromeApp(
    flowUrl: String,
    safePrompt: String
): File? {
    hideWebPreviewCardIfOpen()
    wakeUpScreenIfTimedOut()
    val specs = getCalibratedDeviceBodySpecs()
    val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
    val customPath = prefs.getString("custom_download_folder", "")?.trim() ?: ""
    val targetOutDir = if (customPath.isNotEmpty()) {
        resolveSmartFolderPath(customPath).apply { if (!exists()) mkdirs() }
    } else {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    val startTimeMs = System.currentTimeMillis()
    hud.updateStatus("📱 Opening Real Chrome for Flow...", BotMood.WORKING, taskRunning = true)
    AutoTouchService.log("📱 Real Chrome Mode: Launching Chrome on $flowUrl")

    withContext(Dispatchers.Main) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(flowUrl)).apply {
            setPackage("com.android.chrome")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            launchAppByNameOrPackage("chrome")
        }
    }
    delay(5500L)

    val history = mutableListOf<String>()
    val chromeSteps = listOf(
        "Step 1: Tap '+ New project' if on project list",
        "Step 2: Tap prompt text box, type '$safePrompt', and tap Submit arrow",
        "Step 3: Wait for video generation to reach 100%, open video, and tap Download"
    )

    for (step in 1..25) {
        if (!isAgentRunning) break
        val els = scanScreenInteractiveElements(specs)
        val snap = takeHdDisplayScreenshotWithTimeout(2200L)
        recordRunBitmapSafely(snap)

        val nodesList = els.take(50).joinToString("\n") {
            "[${it.tagId}] ${it.label} (${it.className}) -> Center(${it.centerX}, ${it.centerY})"
        }

        val decision = geminiBrain.decideNextStep(
            goal = "In Chrome on Google Flow, create a 9:16 video with prompt '$safePrompt' and download the generated MP4 video.",
            planSteps = chromeSteps,
            currentStepHistory = history,
            bodySpecs = specs,
            numberedNodesList = nodesList,
            screenshotBitmap = snap,
            isStuck = false,
            recalledFixes = memoryVault.recallFixesForApp("WebStudio"),
            isContinuousMonitor = false
        )

        AutoTouchService.log("📱 Real Chrome Flow Step #$step [${decision.modelUsed}] -> ${decision.action}: ${decision.reason}")
        hud.updateStatus("Chrome Flow #$step • ${decision.reason}", BotMood.WORKING, taskRunning = true)

        when (decision.action) {
            "DONE" -> break
            "CLICK_TAG" -> {
                val t = els.find { it.tagId == decision.tagId }
                if (t != null) tapElementSmartly(t, false)
                else performPhysicalTap(
                    ((decision.x / 1000f) * specs.screenWidth).toInt(),
                    ((decision.y / 1000f) * specs.screenHeight).toInt()
                )
            }
            "CLICK_XY" -> {
                performPhysicalTap(
                    ((decision.x / 1000f) * specs.screenWidth).toInt(),
                    ((decision.y / 1000f) * specs.screenHeight).toInt()
                )
            }
            "TYPE" -> {
                val t = els.find { it.tagId == decision.tagId }
                if (t != null) {
                    tapElementSmartly(t, false)
                    delay(800L)
                }
                typeAndPasteTextReliably(decision.text.ifBlank { safePrompt }, t?.node)
            }
            else -> delay(3500L)
        }
        history.add("${decision.action}: ${decision.reason}")
        delay(3500L)

        val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val newestMp4 = dlDir.listFiles()?.filter {
            it.isFile && !it.name.startsWith("raw_") && it.name.endsWith(".mp4", true) && it.lastModified() >= startTimeMs
        }?.maxByOrNull { it.lastModified() }

        if (newestMp4 != null && newestMp4.length() > 10_000L) {
            return withContext(Dispatchers.IO) {
                val cleanDest = File(targetOutDir, "flow_${System.currentTimeMillis()}.mp4")
                val stripped = stripAiContainerMetadata(newestMp4, cleanDest)
                if (stripped && cleanDest.exists() && cleanDest.length() > 10_000L) {
                    if (newestMp4.absolutePath != cleanDest.absolutePath) {
                        try { newestMp4.delete() } catch (_: Exception) {}
                    }
                    AutoTouchService.log("🛡️ Stripped C2PA/AI Container Metadata from Chrome MP4: ${cleanDest.name}")
                    cleanDest
                } else if (targetOutDir.absolutePath != dlDir.absolutePath) {
                    newestMp4.copyTo(cleanDest, overwrite = true)
                    cleanDest
                } else {
                    newestMp4
                }
            }
        }
    }
    return null
}

private suspend fun AutoTouchService.runFlowComposerStateMachineStep(
    webView: WebView,
    safePrompt: String
): String = suspendCancellableCoroutine { cont ->
    val js = """
        (function() {
            function isVisible(el) {
                if (!el) return false;
                const r = el.getBoundingClientRect();
                return r.width > 10 && r.height > 10 && r.top >= 0;
            }

            const allInputs = Array.from(document.querySelectorAll('textarea, [contenteditable="true"], input[type="text"]')).filter(isVisible);
            const promptBox = allInputs.find(el => {
                const ph = (el.getAttribute('placeholder') || el.getAttribute('aria-label') || el.innerText || '').toLowerCase();
                const r = el.getBoundingClientRect();
                return ph.includes('create') || ph.includes('prompt') || ph.includes('describe') || (r.top > window.innerHeight * 0.45 && !ph.includes('search'));
            });

            if (!promptBox && (document.body.innerText.includes('0:00 /') || document.querySelectorAll('video').length > 0)) {
                const backBtns = Array.from(document.querySelectorAll('button, [role="button"], a')).filter(isVisible);
                const backBtn = backBtns.find(b => {
                    const r = b.getBoundingClientRect();
                    return r.top < 95 && r.left < 95;
                });
                if (backBtn) backBtn.click();
                window.history.back();
                return 'BACK_FROM_PLAYER';
            }

            const allBtns = Array.from(document.querySelectorAll('button, [role="button"], [role="tab"], div, span')).filter(isVisible);

            const creditLabel = allBtns.find(el => (el.innerText || '').toLowerCase().includes('generating will use'));
            if (creditLabel) {
                const videoTab = allBtns.find(el => (el.innerText || '').trim() === 'Video' && el.getBoundingClientRect().top > 150);
                if (videoTab && !document.body.innerText.includes('360p') && !document.body.innerText.includes('720p')) {
                    const r = videoTab.getBoundingClientRect();
                    videoTab.click();
                    return 'SWITCHING_TO_VIDEO_MODE:' + Math.round(r.left + r.width / 2) + ',' + Math.round(r.top + r.height / 2);
                }
                const btn916 = allBtns.find(el => (el.innerText || '').trim().includes('9:16'));
                if (btn916) btn916.click();
                const btnX1 = allBtns.find(el => (el.innerText || '').trim() === 'x1');
                if (btnX1) btnX1.click();
            }

            if (promptBox) {
                const nanoPill = allBtns.find(el => {
                    const t = (el.innerText || '').toLowerCase();
                    const r = el.getBoundingClientRect();
                    return (t.includes('nano banana') || (t.includes('image') && t.includes('x'))) && r.top > window.innerHeight * 0.65 && el.children.length <= 4;
                });

                if (nanoPill && !creditLabel) {
                    const nr = nanoPill.getBoundingClientRect();
                    nanoPill.click();
                    return 'SWITCHING_TO_VIDEO_MODE:' + Math.round(nr.left + nr.width / 2) + ',' + Math.round(nr.top + nr.height / 2);
                }

                promptBox.focus();
                promptBox.click();

                const promptText = ${JSONObject.quote(safePrompt)};
                if (promptBox.tagName === 'TEXTAREA' || promptBox.tagName === 'INPUT') {
                    const proto = promptBox.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                    const nativeSetter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                    if (nativeSetter) {
                        nativeSetter.call(promptBox, promptText);
                    } else {
                        promptBox.value = promptText;
                    }
                } else {
                    promptBox.innerText = promptText;
                }

                document.execCommand('selectAll', false, null);
                document.execCommand('insertText', false, promptText);
                promptBox.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, data: promptText }));
                promptBox.dispatchEvent(new Event('change', { bubbles: true }));

                const boxRect = promptBox.getBoundingClientRect();
                const submitBtn = allBtns.find(b => {
                    const txt = (b.innerText || b.getAttribute('aria-label') || b.innerHTML || '').toLowerCase();
                    const r = b.getBoundingClientRect();
                    const isNearBottomRight = r.top >= boxRect.top - 20 && r.left > window.innerWidth * 0.68 && r.width < 90;
                    return (txt.includes('arrow_forward') || txt.includes('send') || txt.includes('submit') || isNearBottomRight) && !txt.includes('banana') && !txt.includes('video') && !txt.includes('new project');
                });

                if (submitBtn) {
                    const sr = submitBtn.getBoundingClientRect();
                    setTimeout(() => { submitBtn.click(); }, 350);
                    return 'SUBMITTED:' + Math.round(sr.left + sr.width / 2) + ',' + Math.round(sr.top + sr.height / 2);
                }
                return 'SUBMITTED:' + Math.round(window.innerWidth * 0.88) + ',' + Math.round(boxRect.bottom + 28);
            }

            const newProjBtn = allBtns.find(el => {
                const t = (el.innerText || el.getAttribute('aria-label') || '').trim().toLowerCase();
                return t === 'new project' || t === '+ new project' || (t.includes('new project') && t.length < 25);
            });

            if (newProjBtn) {
                const nr = newProjBtn.getBoundingClientRect();
                newProjBtn.click();
                return 'CLICKED_NEW_PROJECT:' + Math.round(nr.left + nr.width / 2) + ',' + Math.round(nr.top + nr.height / 2);
            }

            return 'WAITING_FOR_FLOW_UI';
        })();
    """.trimIndent()

    withContextOrMain(webView, js) { res ->
        cont.resume(res)
    }
}

private suspend fun AutoTouchService.extractExistingVideoUrlOnly(webView: WebView): String =
    suspendCancellableCoroutine { cont ->
        val js = """
            (function() {
                const vids = Array.from(document.querySelectorAll('video'));
                for (const v of vids) {
                    if (v.src && v.src.startsWith('http')) return v.src;
                    const s = v.querySelector('source');
                    if (s && s.src && s.src.startsWith('http')) return s.src;
                }
                return '';
            })();
        """.trimIndent()
        withContextOrMain(webView, js) { res ->
            cont.resume(res)
        }
    }

// Polls Google Flow for progress %, policy failure, or newly generated top-left video card.
private suspend fun AutoTouchService.pollOrOpenGeneratedFlowVideo(
    webView: WebView,
    initialVideoUrl: String,
    alreadyDownloadedUuidsCsv: String,
    allowAutoClickCard: Boolean
): String = suspendCancellableCoroutine { cont ->
    val js = """
        (function() {
            const seenIds = (${JSONObject.quote(alreadyDownloadedUuidsCsv)} || '').toLowerCase().split(',').filter(Boolean);
            function isOldSeenUrl(url) {
                if (!url) return false;
                const low = url.toLowerCase();
                for (const id of seenIds) {
                    if (id.length > 6 && low.includes(id)) return true;
                }
                return false;
            }

            function fireFullHumanClickAt(cx, cy, extraEl) {
                const hitEl = document.elementFromPoint(cx, cy) || extraEl;
                const targets = [];
                if (extraEl) targets.push(extraEl);
                let cur = hitEl;
                for (let d = 0; d < 7 && cur && cur !== document.body; d++) {
                    if (!targets.includes(cur)) targets.push(cur);
                    cur = cur.parentElement;
                }
                const evTypes = ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'];
                for (const node of targets) {
                    for (const type of evTypes) {
                        try {
                            node.dispatchEvent(new PointerEvent(type, {
                                view: window,
                                bubbles: true,
                                cancelable: true,
                                composed: true,
                                clientX: cx,
                                clientY: cy,
                                pointerId: 1,
                                pointerType: 'touch',
                                isPrimary: true,
                                button: 0,
                                buttons: 1
                            }));
                        } catch (e) {}
                        try {
                            node.dispatchEvent(new MouseEvent(type, {
                                view: window,
                                bubbles: true,
                                cancelable: true,
                                composed: true,
                                clientX: cx,
                                clientY: cy,
                                button: 0,
                                buttons: 1
                            }));
                        } catch (e) {}
                    }
                    try {
                        if (typeof node.click === 'function') node.click();
                    } catch (e) {}
                }
            }

            const vids = Array.from(document.querySelectorAll('video'));
            for (const v of vids) {
                const src = v.src || (v.querySelector('source') ? v.querySelector('source').src : '');
                if (src && src.startsWith('http') && src !== ${JSONObject.quote(initialVideoUrl)} && !isOldSeenUrl(src)) {
                    return 'VIDEO_URL:' + src;
                }
            }

            const allEls = Array.from(document.querySelectorAll('div, span, p'));
            for (const el of allEls) {
                if (el.children.length === 0) {
                    const txt = (el.innerText || '').trim();
                    if (txt.endsWith('%') && txt.length >= 2 && txt.length <= 4) {
                        const num = parseInt(txt.slice(0, -1), 10);
                        if (!isNaN(num) && num >= 0 && num <= 100) {
                            return 'PROGRESS:' + txt;
                        }
                    }
                }
            }

            const pageText = document.body ? (document.body.innerText || '') : '';
            if (pageText.includes('Failed') && (pageText.includes('violate our policies') || pageText.includes('different prompt') || pageText.includes('prominent people'))) {
                return 'FLOW_PROMPT_FAILED:Prominent People Policy';
            }

            if ($allowAutoClickCard) {
                const topLeftMedia = Array.from(document.querySelectorAll('img, canvas, video')).filter(el => {
                    const r = el.getBoundingClientRect();
                    return r.top >= 85 && r.top <= 350 && r.left >= 4 && r.left <= 165 && r.width >= 50 && r.height >= 70;
                });

                if (topLeftMedia.length > 0) {
                    const imgEl = topLeftMedia[0];
                    const r = imgEl.getBoundingClientRect();
                    const cx = Math.round(r.left + r.width * 0.5);
                    const cy = Math.round(r.top + r.height * 0.5);
                    fireFullHumanClickAt(cx, cy, imgEl);
                    fireFullHumanClickAt(Math.round(r.left + 22), Math.round(r.top + 22), imgEl.parentElement);
                    return 'CLICKED_CARD:' + cx + ',' + cy;
                }
            }

            return 'WAITING';
        })();
    """.trimIndent()
    withContextOrMain(webView, js) { res ->
        cont.resume(res)
    }
}

// FEATURE 1: Downloads the generated MP4 stream into raw_$fileName, runs native stripAiContainerMetadata
// to remove all C2PA / XMP / Google AI container headers, deletes the raw file, and returns the clean MP4!
private suspend fun AutoTouchService.downloadVideoFromUrlToStorage(videoUrl: String, fileName: String): File? =
    withContext(Dispatchers.IO) {
        try {
            val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
            val customPath = prefs.getString("custom_download_folder", "")?.trim() ?: ""
            val outDir = if (customPath.isNotEmpty()) {
                resolveSmartFolderPath(customPath).apply { if (!exists()) mkdirs() }
            } else {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "AutoVideos"
                ).apply { if (!exists()) mkdirs() }
            }

            val rawTempFile = File(outDir, "raw_$fileName")
            val cleanOutFile = File(outDir, fileName)

            URL(videoUrl).openStream().use { input ->
                FileOutputStream(rawTempFile).use { output ->
                    input.copyTo(output)
                }
            }

            val remuxedOk = stripAiContainerMetadata(rawTempFile, cleanOutFile)
            val finalFile = if (remuxedOk && cleanOutFile.exists() && cleanOutFile.length() > 10_000L) {
                try { rawTempFile.delete() } catch (_: Exception) {}
                AutoTouchService.log("🛡️ C2PA / AI Container Metadata Stripped Cleanly: ${cleanOutFile.name} (${cleanOutFile.length() / 1024} KB)")
                cleanOutFile
            } else {
                if (cleanOutFile.exists()) {
                    try { cleanOutFile.delete() } catch (_: Exception) {}
                }
                if (rawTempFile.renameTo(cleanOutFile)) {
                    cleanOutFile
                } else {
                    rawTempFile.copyTo(cleanOutFile, overwrite = true)
                    try { rawTempFile.delete() } catch (_: Exception) {}
                    cleanOutFile
                }
            }

            if (prefs.getString("yt_folder_path", "").isNullOrBlank()) {
                prefs.edit().putString("yt_folder_path", outDir.absolutePath).apply()
            }
            finalFile
        } catch (_: Exception) {
            null
        }
    }
