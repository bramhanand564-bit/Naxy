package com.auradroid.os

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

enum class FeatureAccessType(val label: String) {
    BRAIN_SKILL("🧠 AI Brain Skill & Prompt Injection"),
    BACKGROUND_DOT_WEB("🖥️ OpenDots Background Browser (Watch Live)"),
    STORAGE_FOLDER("📁 Auto Folder Watcher & Media Access"),
    MICROPHONE_VOICE("🎙️ Real-Time Voice & Mic Access"),
    LOCAL_GATEWAY("🔓 Localhost Gateway (FreeLLM / OmniRoute)"),
    ACCESSIBILITY_UI("📱 Live Phone UI Auto-Touch Access"),
    PUBLIC_DATA_API("🌍 Free Public APIs & Web Research")
}

data class AuraFeaturePlugin(
    val id: String,
    val title: String,
    val badge: String,
    val sourceRepo: String,
    val descriptionHinglish: String,
    val accessTypes: List<FeatureAccessType>,
    val brainSkillInstruction: String,
    val defaultBackgroundUrl: String = ""
)

object AuraPluginStore {

    private const val PREFS_NAME = "auradroid_plugin_store"
    private const val KEY_INSTALLED_SET = "installed_plugin_ids"

    @Volatile
    private var cachedBrainSkillsText: String =
        "- Use ultra-compact token routing across FreeLLMAPI & OmniRoute.\n- Be concise, direct, and actionable (ADHD & No-AI-Slop active)."

    @SuppressLint("StaticFieldLeak")
    private var backgroundDotWebView: WebView? = null
    private var currentDotTaskTitle: String = "Idle"
    private var currentDotStatusLog: String = "Ready for background task"

    val allPlugins: List<AuraFeaturePlugin> = listOf(
        AuraFeaturePlugin(
            id = "freellm_omniroute_9b",
            title = "9B Free Token Booster (FreeLLM + OmniRoute)",
            badge = "🔥 MUST HAVE",
            sourceRepo = "tashfeenahmed/freellmapi + diegosouzapw/OmniRoute",
            descriptionHinglish = "हर महीने 9 Billion+ फ्री टोकन्स! एक मॉडल का कोटा खत्म होते ही खुद दूसरे फ्री प्रोवाइडर पर शिफ्ट हो जाता है।",
            accessTypes = listOf(FeatureAccessType.LOCAL_GATEWAY, FeatureAccessType.BRAIN_SKILL),
            brainSkillInstruction = "Use ultra-compact token routing. Fallback seamlessly across FreeLLMAPI (localhost:3001) and OmniRoute pools."
        ),
        AuraFeaturePlugin(
            id = "opendots_bg_computer",
            title = "OpenDots Background PC (App के अंदर App)",
            badge = "🖥️ BACKGROUND",
            sourceRepo = "CopilotKit/OpenDots",
            descriptionHinglish = "हर AI एजेंट को अपना अलग बैकग्राउंड ब्राउज़र (Dot) देता है। आप फोन नॉर्मल चलाओ, AI बैकग्राउंड में काम करेगा। जब चाहो 'Watch Live' या 'Take Control' दबाओ!",
            accessTypes = listOf(FeatureAccessType.BACKGROUND_DOT_WEB),
            brainSkillInstruction = "For web tasks (YouTube Studio, AI generators, research), use executionMode 'BACKGROUND_WEB' so it runs inside the OpenDots Background Container without disturbing the user's screen.",
            defaultBackgroundUrl = "https://www.google.com"
        ),
        AuraFeaturePlugin(
            id = "hermes_yt_automation",
            title = "Hermes + AgentTube YouTube Auto-Uploader",
            badge = "🎬 YOUTUBE 24/7",
            sourceRepo = "Hermes CDP + darkzOGx/youtube-automation-agent",
            descriptionHinglish = "फोल्डर से अपने-आप वीडियो उठाता है, आवाज़ और ऑन-स्क्रीन टेक्स्ट पढ़कर वायरल Title/Tags लिखता है, और बैकग्राउंड में YouTube Studio से पब्लिश करके Telegram पर लिंक भेजता है!",
            accessTypes = listOf(
                FeatureAccessType.STORAGE_FOLDER,
                FeatureAccessType.BACKGROUND_DOT_WEB,
                FeatureAccessType.ACCESSIBILITY_UI
            ),
            brainSkillInstruction = "SKILL [HERMES_YT_PIPELINE]: Pick unposted video from folder, generate viral Hinglish Title/Description/Tags, upload via YouTube Studio UI, click 'Publish anyway' on check dialog, and verify playback.",
            defaultBackgroundUrl = "https://studio.youtube.com"
        ),
        AuraFeaturePlugin(
            id = "ruflo_adk_swarm",
            title = "Ruflo 60-Agent Swarm + Google ADK Hierarchy",
            badge = "🐝 MULTI-AGENT",
            sourceRepo = "ruvnet/ruflo + Google ADK (agents-cli)",
            descriptionHinglish = "एक अकेले AI के बजाय Queen Agent + Sub-Agents की टीम बनाता है (Researcher, Planner, Executor, Auditor) और आसान काम सस्ते मॉडल को व मुश्किल काम स्मार्ट मॉडल को देता है।",
            accessTypes = listOf(FeatureAccessType.BRAIN_SKILL),
            brainSkillInstruction = "SKILL [RUFLO_ADK_SWARM]: Act as Queen Orchestrator. Break complex goals into specialized Sub-Agent micro-steps ([FAST_LITE], [ROBOTICS_XY], [DEEP_3.8])."
        ),
        AuraFeaturePlugin(
            id = "moneyprinter_hypit_factory",
            title = "MoneyPrinterTurbo + Hypit Viral Video Cloner",
            badge = "💸 VIRAL SHORTS",
            sourceRepo = "harry0703/MoneyPrinterTurbo + hypit-ai/hypit",
            descriptionHinglish = "सिर्फ एक टॉपिक दो या कोई वायरल रील दो—यह खुद स्क्रिप्ट, B-Roll फुटेज, सबटाइटल्स और वॉइसओवर जोड़कर नए वायरल Shorts तैयार कर देगा!",
            accessTypes = listOf(FeatureAccessType.STORAGE_FOLDER, FeatureAccessType.BRAIN_SKILL),
            brainSkillInstruction = "SKILL [VIRAL_FACTORY]: Generate high-retention 3-second hooks, scene-by-scene visual prompts, karaoke subtitle timings, and viral hashtags."
        ),
        AuraFeaturePlugin(
            id = "open_generative_studio",
            title = "Open Generative AI Studio (Free Image & Video)",
            badge = "🎨 400+ MODELS",
            sourceRepo = "Anil-matcha/Open-Generative-AI + Qwen + Wan AI + MiniMax H3",
            descriptionHinglish = "Midjourney और Runway के पैसे बचाओ! ऐप के बैकग्राउंड में फ्री में Qwen Image, Wan Video, MiniMax H3, Synthesia और HeyGem Avatar से इमेज और वीडियो बनवाओ।",
            accessTypes = listOf(FeatureAccessType.BACKGROUND_DOT_WEB, FeatureAccessType.BRAIN_SKILL),
            brainSkillInstruction = "SKILL [OPEN_GEN_STUDIO]: Use free generative web endpoints (Qwen Image, Wan AI, MiniMax H3, Google Flow) in the background browser to generate images and 9:16 vertical videos.",
            defaultBackgroundUrl = "https://chat.qwen.ai"
        ),
        AuraFeaturePlugin(
            id = "omnivoice_handy_mic",
            title = "OmniVoice + Handy Real-Time Voice Agent",
            badge = "🎙️ VOICE AI",
            sourceRepo = "k2-fsa/OmniVoice + cjpais/Handy",
            descriptionHinglish = "ElevenLabs और Wispr Flow का फ्री विकल्प! आपकी आवाज़ सुनकर तुरंत कमांड टाइप करता है और इंसानों जैसी इमोशनल आवाज़ में बात करता है।",
            accessTypes = listOf(FeatureAccessType.MICROPHONE_VOICE, FeatureAccessType.BRAIN_SKILL),
            brainSkillInstruction = "SKILL [OMNIVOICE_TONE]: Reply in natural, expressive spoken Hinglish with human-like warmth and emotion."
        ),
        AuraFeaturePlugin(
            id = "adhd_no_ai_slop",
            title = "Token Saver & Human Writer (I Have ADHD + No Slop)",
            badge = "⚡ 50% FASTER",
            sourceRepo = "ayghri/i-have-adhd + petergyang/no-ai-slop",
            descriptionHinglish = "AI की फालतू बातें ('I hope this helps') और बनावटी शब्द हटाता है। सीधा पॉइंट-टू-पॉइंट जवाब देता है जिससे 50% टोकन्स बचते हैं और स्पीड दोगुनी हो जाती है!",
            accessTypes = listOf(FeatureAccessType.BRAIN_SKILL),
            brainSkillInstruction = "SKILL [ADHD_NO_SLOP]: Be concise and direct. Never use generic AI filler phrases. Use numbered actionable steps."
        ),
        AuraFeaturePlugin(
            id = "open_notebook_book_skill",
            title = "Open Notebook + Book-to-Skill PDF Brain",
            badge = "📚 PDF & NOTES",
            sourceRepo = "lfnovo/open-notebook + virgiliojr94/book-to-skill",
            descriptionHinglish = "किसी भी 400-पेज की PDF किताब, वेबसाइट या वीडियो को छोटे-छोटे AI Skills और नोट्स में बदल देता है ताकि आप उससे प्राइवेट चैट और रिसर्च कर सकें।",
            accessTypes = listOf(FeatureAccessType.STORAGE_FOLDER, FeatureAccessType.BRAIN_SKILL),
            brainSkillInstruction = "SKILL [BOOK_TO_SKILL]: Chunk large documents or notes into modular, reusable rules and load only the relevant section."
        ),
        AuraFeaturePlugin(
            id = "public_apis_openseo_job_strix",
            title = "Super Toolkit (4.8L Public APIs + OpenSEO + Job + Strix)",
            badge = "🛠️ PRO TOOLS",
            sourceRepo = "public-apis + open-seo + ai-job-search + usestrix/strix",
            descriptionHinglish = "एक ही पैक में 4 सुपर-टूल्स: दुनिया की सारी फ्री APIs (मौसम, क्रिप्टो, न्यूज़), YouTube/Web SEO ऑडिट, ऑटो जॉब/Resume मेकर और ऐप सिक्योरिटी चेकर!",
            accessTypes = listOf(FeatureAccessType.PUBLIC_DATA_API, FeatureAccessType.BRAIN_SKILL),
            brainSkillInstruction = "SKILL [PRO_TOOLKIT]: Provide expert SEO keyword/tag optimization (OpenSEO), tailored resume/job matching, security checks (Strix), and free public API integration."
        )
    )

    @JvmStatic
    fun getActiveSkillsForBrain(): String = cachedBrainSkillsText

    fun isInstalled(context: Context, pluginId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_INSTALLED_SET, setOf("freellm_omniroute_9b", "adhd_no_ai_slop")) ?: emptySet()
        return set.contains(pluginId)
    }

    fun refreshCachedSkills(context: Context): String {
        val active = allPlugins.filter { isInstalled(context, it.id) }
        cachedBrainSkillsText = if (active.isEmpty()) {
            ""
        } else {
            active.joinToString("\n") { "- ${it.brainSkillInstruction}" }
        }
        return cachedBrainSkillsText
    }

    fun installAndUnlockAccess(activity: Activity, plugin: AuraFeaturePlugin, onUpdated: () -> Unit) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_INSTALLED_SET, setOf("freellm_omniroute_9b", "adhd_no_ai_slop"))?.toMutableSet()
            ?: mutableSetOf()
        current.add(plugin.id)
        prefs.edit().putStringSet(KEY_INSTALLED_SET, current).apply()
        refreshCachedSkills(activity)

        for (access in plugin.accessTypes) {
            when (access) {
                FeatureAccessType.MICROPHONE_VOICE -> {
                    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
                    }
                }
                FeatureAccessType.STORAGE_FOLDER -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${activity.packageName}")
                            }
                            activity.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                }
                FeatureAccessType.BACKGROUND_DOT_WEB -> {
                    ensureBackgroundDotInitialized(activity.applicationContext, plugin.defaultBackgroundUrl)
                }
                else -> {}
            }
        }

        val unlockedNames = plugin.accessTypes.joinToString(", ") { it.label }
        Toast.makeText(
            activity,
            "✅ '${plugin.title}' Installed!\n🔓 Unlocked: $unlockedNames",
            Toast.LENGTH_LONG
        ).show()
        onUpdated()
    }

    fun uninstallPlugin(context: Context, pluginId: String, onUpdated: () -> Unit) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_INSTALLED_SET, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(pluginId)
        prefs.edit().putStringSet(KEY_INSTALLED_SET, current).apply()
        refreshCachedSkills(context)
        Toast.makeText(context, "🗑️ Feature Removed", Toast.LENGTH_SHORT).show()
        onUpdated()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun ensureBackgroundDotInitialized(context: Context, initialUrl: String = "https://www.google.com"): WebView {
        if (backgroundDotWebView == null) {
            backgroundDotWebView = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.userAgentString =
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                    override fun onPageFinished(view: WebView?, url: String?) {
                        currentDotStatusLog = "✅ Loaded in Background Dot: ${url?.take(55) ?: ""}"
                    }
                }
                if (initialUrl.isNotBlank()) loadUrl(initialUrl)
            }
        } else if (initialUrl.isNotBlank() && backgroundDotWebView?.url.isNullOrBlank()) {
            backgroundDotWebView?.loadUrl(initialUrl)
        }
        return backgroundDotWebView!!
    }

    fun runTaskInBackgroundDot(context: Context, taskTitle: String, targetUrl: String, jsScriptToRun: String = "") {
        val webView = ensureBackgroundDotInitialized(context, targetUrl)
        currentDotTaskTitle = taskTitle
        currentDotStatusLog = "🚀 Running '$taskTitle' in Background Dot..."
        webView.loadUrl(targetUrl)
        if (jsScriptToRun.isNotBlank()) {
            webView.postDelayed({
                webView.evaluateJavascript(jsScriptToRun, null)
            }, 5000L)
        }
    }

    fun showLiveBackgroundDotViewer(activity: Activity, startUrlIfEmpty: String = "https://studio.youtube.com") {
        val webView = ensureBackgroundDotInitialized(activity, startUrlIfEmpty)
        (webView.parent as? ViewGroup)?.removeView(webView)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 20)
            setBackgroundColor(Color.parseColor("#1E293B"))
        }

        val titleView = TextView(activity).apply {
            text = "🟣 OpenDots Live Background PC ($currentDotTaskTitle)"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }

        val statusView = TextView(activity).apply {
            text = currentDotStatusLog
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
        }

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 14, 0, 0)
        }

        val ytBtn = Button(activity).apply {
            text = "🎬 YT Studio"
            textSize = 11f
            setOnClickListener { webView.loadUrl("https://studio.youtube.com") }
        }

        val qwenBtn = Button(activity).apply {
            text = "🎨 Qwen AI"
            textSize = 11f
            setOnClickListener { webView.loadUrl("https://chat.qwen.ai") }
        }

        val minimizeBtn = Button(activity).apply {
            text = "⬇️ Back to Background"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#16A34A"))
            setTextColor(Color.WHITE)
        }

        btnRow.addView(ytBtn)
        btnRow.addView(qwenBtn)
        btnRow.addView(minimizeBtn)

        header.addView(titleView)
        header.addView(statusView)
        header.addView(btnRow)

        val webContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        webContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(header)
        root.addView(webContainer)

        val dialog = AlertDialog.Builder(activity)
            .setView(root)
            .setOnDismissListener {
                (webView.parent as? ViewGroup)?.removeView(webView)
            }
            .create()

        minimizeBtn.setOnClickListener {
            Toast.makeText(activity, "🟣 Dot continues running in background!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    fun showPluginStoreDialog(activity: Activity) {
        refreshCachedSkills(activity)
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(Color.parseColor("#0B1120"))
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 48)
        }
        scroll.addView(container)

        lateinit var refreshList: () -> Unit

        refreshList = {
            container.removeAllViews()

            val headerTitle = TextView(activity).apply {
                text = "🧩 AuraStore — 1-Tap AI Feature Installer"
                setTextColor(Color.WHITE)
                textSize = 19f
                setTypeface(null, Typeface.BOLD)
            }
            val subTitle = TextView(activity).apply {
                text = "जो फीचर चाहिए बस Install पर टैप करें—उसका Access Type और AI Skill अपने-आप आपके ऐप में जुड़ जाएगा!"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 13f
                setPadding(0, 8, 0, 20)
            }
            container.addView(headerTitle)
            container.addView(subTitle)

            val openLiveDotBtn = Button(activity).apply {
                text = "👁️ OpenDots Background PC (Watch Live / Take Control)"
                setBackgroundColor(Color.parseColor("#4F46E5"))
                setTextColor(Color.WHITE)
                setPadding(24, 20, 24, 20)
                setOnClickListener {
                    showLiveBackgroundDotViewer(activity)
                }
            }
            container.addView(openLiveDotBtn)

            for (plugin in allPlugins) {
                val installed = isInstalled(activity, plugin.id)

                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor(if (installed) "#132A1E" else "#1E293B"))
                    setPadding(32, 28, 32, 28)
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(0, 24, 0, 0)
                    layoutParams = lp
                }

                val badgeView = TextView(activity).apply {
                    text = "${plugin.badge}  •  📦 ${plugin.sourceRepo}"
                    setTextColor(Color.parseColor("#38BDF8"))
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                }

                val nameView = TextView(activity).apply {
                    text = plugin.title
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, 6, 0, 6)
                }

                val descView = TextView(activity).apply {
                    text = plugin.descriptionHinglish
                    setTextColor(Color.parseColor("#CBD5E1"))
                    textSize = 13f
                }

                val accessView = TextView(activity).apply {
                    text = "🔓 Unlocks Access:\n" + plugin.accessTypes.joinToString("\n") { "  • ${it.label}" }
                    setTextColor(Color.parseColor("#FACC15"))
                    textSize = 12f
                    setPadding(0, 12, 0, 16)
                }

                val actionRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }

                if (installed && plugin.defaultBackgroundUrl.isNotBlank()) {
                    val launchBgBtn = Button(activity).apply {
                        text = "▶️ Run in Background Dot"
                        textSize = 12f
                        setBackgroundColor(Color.parseColor("#2563EB"))
                        setTextColor(Color.WHITE)
                        setOnClickListener {
                            runTaskInBackgroundDot(activity, plugin.title, plugin.defaultBackgroundUrl)
                            showLiveBackgroundDotViewer(activity, plugin.defaultBackgroundUrl)
                        }
                    }
                    actionRow.addView(launchBgBtn)
                }

                val installBtn = Button(activity).apply {
                    text = if (installed) "✅ Installed (Tap to Remove)" else "⬇️ Install & Unlock Access"
                    textSize = 12f
                    setBackgroundColor(Color.parseColor(if (installed) "#15803D" else "#10B981"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        if (installed) {
                            uninstallPlugin(activity, plugin.id, refreshList)
                        } else {
                            installAndUnlockAccess(activity, plugin, refreshList)
                        }
                    }
                }
                actionRow.addView(installBtn)

                card.addView(badgeView)
                card.addView(nameView)
                card.addView(descView)
                card.addView(accessView)
                card.addView(actionRow)

                container.addView(card)
            }
        }

        refreshList()

        AlertDialog.Builder(activity)
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
    }
}
