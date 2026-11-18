package com.tiff.screenassistant.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.tiff.screenassistant.R
import com.tiff.screenassistant.ai.VisionAiClient
import com.tiff.screenassistant.data.*
import com.tiff.screenassistant.intents.AppIntentExecutor
import com.tiff.screenassistant.telegram.TelegramAppAutoDriver
import com.tiff.screenassistant.telegram.TelegramBotClient
import com.tiff.screenassistant.ui.LiveArrowOverlayView
import com.tiff.screenassistant.voice.TiffVoiceManager
import kotlinx.coroutines.*
import java.util.Locale

class TiffOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: PrefsManager
    private lateinit var soulRepo: SoulRepository
    private lateinit var memoryRepo: MemoryRepository
    private lateinit var macroRepo: MacroRepository
    private lateinit var tgRepo: TelegramRepository
    private lateinit var tgClient: TelegramBotClient
    private lateinit var tgAppDriver: TelegramAppAutoDriver
    private lateinit var aiClient: VisionAiClient
    private lateinit var voiceManager: TiffVoiceManager

    private var hudView: View? = null
    private var arrowView: LiveArrowOverlayView? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeDriveJob: Job? = null
    private var telegramPollingJob: Job? = null

    private enum class Mode { POINT, DRIVE_PLAN, DRIVE_CONFIRM, TELEGRAM_SEND }
    private var currentMode = Mode.POINT
    private var pendingTask = ""
    private var pendingPlan: List<String> = emptyList()

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "tiff_overlay_channel"
        private const val NOTIF_ID = 2001
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = PrefsManager(this)
        soulRepo = SoulRepository(this)
        memoryRepo = MemoryRepository(this)
        macroRepo = MacroRepository(this)
        tgRepo = TelegramRepository(this)
        tgClient = TelegramBotClient(tgRepo)
        tgAppDriver = TelegramAppAutoDriver(this, tgRepo)
        aiClient = VisionAiClient(prefs, soulRepo, memoryRepo)
        voiceManager = TiffVoiceManager(this, prefs)

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        setupOverlays()
        startTelegramPollingLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
            setupVirtualDisplay()
            voiceManager.speak("Hey ${soulRepo.userNickname}, I'm right here whenever you need me.")
            if (tgRepo.isConfigured() && tgRepo.pollingEnabled) {
                serviceScope.launch {
                    tgClient.sendMessage("Tiff is awake on your phone, ${soulRepo.userNickname}! Send /screen, /memory, /remember <fact>, or chat with me anytime.")
                }
            }
        }
        return START_STICKY
    }

    private fun startTelegramPollingLoop() {
        telegramPollingJob?.cancel()
        telegramPollingJob = serviceScope.launch {
            while (isActive) {
                if (tgRepo.isConfigured() && tgRepo.pollingEnabled) {
                    val incoming = tgClient.pollNewIncomingMessages()
                    for (msg in incoming) {
                        handleIncomingTelegramCommand(msg.chatId, msg.senderName, msg.text)
                    }
                }
                delay(4000)
            }
        }
    }

    private suspend fun handleIncomingTelegramCommand(chatId: String, sender: String, rawText: String) {
        val lower = rawText.lowercase(Locale.ROOT).trim()
        memoryRepo.recordScreenEvent("Telegram from $sender: $rawText")

        when {
            lower == "/screen" || lower == "screen" || lower == "screenshot" -> {
                val bmp = captureScreenBitmap()
                if (bmp != null) {
                    tgClient.sendScreenshot(bmp, "Live screen capture from Tiff", chatId)
                } else {
                    tgClient.sendMessage("Screen capture isn't active right now.", chatId)
                }
            }
            lower.startsWith("/remember ") || lower.startsWith("remember ") -> {
                val fact = rawText.substringAfter(" ").trim()
                if (fact.isNotEmpty()) {
                    memoryRepo.addLongTermMemory(fact, MemoryCategory.USER_FACT)
                    tgClient.sendMessage("Got it! Saved to Tiff's memory: \"$fact\"", chatId)
                    voiceManager.speak("Saved a new note from Telegram, ${soulRepo.userNickname}.")
                }
            }
            lower == "/memory" || lower == "memory" -> {
                val mems = memoryRepo.getAllLongTerm().take(10)
                val summary = if (mems.isEmpty()) "Memory bank is empty."
                else mems.joinToString("\n") { "• ${it.fact}" }
                tgClient.sendMessage("Tiff's Long-Term Memory:\n$summary", chatId)
            }
            lower == "/stop" || lower == "stop" -> {
                activeDriveJob?.cancel()
                arrowView?.hidePointer()
                tgClient.sendMessage("Stopped all screen actions immediately.", chatId)
                voiceManager.speak("Stopped via Telegram, ${soulRepo.userNickname}.")
            }
            lower.startsWith("/askscreen ") -> {
                val question = rawText.substringAfter(" ").trim()
                val bmp = captureScreenBitmap()
                val reply = try {
                    aiClient.chatWithTiff("User asks about their current phone screen: $question", bmp)
                } catch (e: Exception) {
                    "Sorry, I hit an error: ${e.message}"
                }
                tgClient.sendMessage(reply, chatId)
            }
            else -> {
                voiceManager.speak("Telegram message from $sender: $rawText")
                val reply = try {
                    aiClient.chatWithTiff(rawText, null)
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                tgClient.sendMessage(reply, chatId)
            }
        }
    }

    private fun launchTelegramAutoDriver(recipient: String, message: String) {
        activeDriveJob?.cancel()
        activeDriveJob = serviceScope.launch {
            memoryRepo.recordScreenEvent("Telegram Auto-Driver -> $recipient: $message")
            tgAppDriver.driveSendPersonalMessage(recipient, message) { statusMsg ->
                voiceManager.speak(statusMsg)
                Toast.makeText(this@TiffOverlayService, statusMsg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupVirtualDisplay() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "TiffScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun captureScreenBitmap(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    private fun setupOverlays() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        arrowView = LiveArrowOverlayView(this).apply { visibility = View.GONE }
        val arrowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(arrowView, arrowParams)

        val inflater = LayoutInflater.from(this)
        hudView = inflater.inflate(R.layout.overlay_hud, null)

        val hudParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 130
        }

        windowManager.addView(hudView, hudParams)
        bindHudActions(hudView!!, hudParams)
    }

    private fun bindHudActions(view: View, params: WindowManager.LayoutParams) {
        val hudBar = view.findViewById<LinearLayout>(R.id.hudBar)
        val btnPoint = view.findViewById<Button>(R.id.btnHudPoint)
        val btnDrive = view.findViewById<Button>(R.id.btnHudDrive)
        val btnTelegram = view.findViewById<Button>(R.id.btnHudTelegram)
        val btnMacros = view.findViewById<Button>(R.id.btnHudMacros)
        val btnMic = view.findViewById<Button>(R.id.btnHudMic)
        val btnQuiet = view.findViewById<Button>(R.id.btnHudQuiet)
        val btnStop = view.findViewById<Button>(R.id.btnHudStop)

        val panelMacrosDrawer = view.findViewById<LinearLayout>(R.id.panelMacrosDrawer)
        val hudMacrosContainer = view.findViewById<LinearLayout>(R.id.hudMacrosContainer)
        val btnCloseMacrosDrawer = view.findViewById<Button>(R.id.btnCloseMacrosDrawer)

        val panelInput = view.findViewById<LinearLayout>(R.id.panelInput)
        val tvPanelTitle = view.findViewById<TextView>(R.id.tvPanelTitle)
        val etHudCommand = view.findViewById<EditText>(R.id.etHudCommand)
        val tvPlanPreview = view.findViewById<TextView>(R.id.tvPlanPreview)
        val btnSecondary = view.findViewById<Button>(R.id.btnPanelSecondary)
        val btnCancel = view.findViewById<Button>(R.id.btnPanelCancel)
        val btnExecute = view.findViewById<Button>(R.id.btnPanelExecute)

        btnQuiet.text = if (prefs.quietMode) "Muted" else "Voice"

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        hudBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        btnPoint.setOnClickListener {
            arrowView?.hidePointer()
            panelMacrosDrawer.visibility = View.GONE
            btnSecondary.visibility = View.GONE
            currentMode = Mode.POINT
            tvPanelTitle.text = "Ask Tiff where to click..."
            etHudCommand.hint = "e.g. schedule this email"
            etHudCommand.text.clear()
            etHudCommand.visibility = View.VISIBLE
            tvPlanPreview.visibility = View.GONE
            btnExecute.text = "Point"
            panelInput.visibility = View.VISIBLE
        }

        btnDrive.setOnClickListener {
            arrowView?.hidePointer()
            panelMacrosDrawer.visibility = View.GONE
            btnSecondary.visibility = View.GONE
            currentMode = Mode.DRIVE_PLAN
            tvPanelTitle.text = "Tiff, take over and..."
            etHudCommand.hint = "e.g. open a new note titled Monday plan"
            etHudCommand.text.clear()
            etHudCommand.visibility = View.VISIBLE
            tvPlanPreview.visibility = View.GONE
            btnExecute.text = "Plan"
            panelInput.visibility = View.VISIBLE
        }

        btnTelegram.setOnClickListener {
            arrowView?.hidePointer()
            panelMacrosDrawer.visibility = View.GONE
            currentMode = Mode.TELEGRAM_SEND
            tvPanelTitle.text = "Telegram Auto-Driver ('Name: message')"
            etHudCommand.hint = "e.g. Sarah: On my way! (or plain text for Bot)"
            etHudCommand.text.clear()
            etHudCommand.visibility = View.VISIBLE
            tvPlanPreview.visibility = View.GONE
            btnSecondary.visibility = View.VISIBLE
            btnSecondary.text = "Send Screen"
            btnExecute.text = "Drive TG"
            panelInput.visibility = View.VISIBLE
        }

        btnSecondary.setOnClickListener {
            panelInput.visibility = View.GONE
            serviceScope.launch {
                delay(250)
                val bmp = captureScreenBitmap()
                if (bmp == null) {
                    Toast.makeText(this@TiffOverlayService, "Screen capture unavailable.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val res = tgClient.sendScreenshot(bmp, "Shared from Tiff on Android")
                res.onSuccess {
                    voiceManager.speak("Sent your screen to Telegram, ${soulRepo.userNickname}.")
                    Toast.makeText(this@TiffOverlayService, it, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this@TiffOverlayService, "Telegram error: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        btnMacros.setOnClickListener {
            panelInput.visibility = View.GONE
            populateHudMacrosDrawer(
                hudMacrosContainer,
                panelMacrosDrawer,
                panelInput,
                tvPanelTitle,
                etHudCommand,
                tvPlanPreview,
                btnExecute
            )
            panelMacrosDrawer.visibility =
                if (panelMacrosDrawer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        btnCloseMacrosDrawer.setOnClickListener {
            panelMacrosDrawer.visibility = View.GONE
        }

        btnQuiet.setOnClickListener {
            prefs.quietMode = !prefs.quietMode
            btnQuiet.text = if (prefs.quietMode) "Muted" else "Voice"
            if (prefs.quietMode) voiceManager.stop()
        }

        btnStop.setOnClickListener {
            activeDriveJob?.cancel()
            activeDriveJob = null
            arrowView?.hidePointer()
            panelInput.visibility = View.GONE
            panelMacrosDrawer.visibility = View.GONE
            voiceManager.speak("Stopped—all yours, ${soulRepo.userNickname}.")
            Toast.makeText(this, "Tiff stopped.", Toast.LENGTH_SHORT).show()
        }

        btnCancel.setOnClickListener {
            panelInput.visibility = View.GONE
            arrowView?.hidePointer()
        }

        btnMic.setOnClickListener {
            panelMacrosDrawer.visibility = View.GONE
            startVoiceCommand(panelInput, tvPanelTitle, etHudCommand, tvPlanPreview, btnExecute)
        }

        btnExecute.setOnClickListener {
            when (currentMode) {
                Mode.POINT -> {
                    val query = etHudCommand.text.toString().trim()
                    if (query.isNotEmpty()) {
                        panelInput.visibility = View.GONE
                        runPointFlow(query)
                    }
                }
                Mode.DRIVE_PLAN -> {
                    val task = etHudCommand.text.toString().trim()
                    if (task.isNotEmpty()) {
                        pendingTask = task
                        runDrivePlanFlow(task, tvPanelTitle, etHudCommand, tvPlanPreview, btnExecute)
                    }
                }
                Mode.DRIVE_CONFIRM -> {
                    panelInput.visibility = View.GONE
                    runDriveExecutionLoop(pendingTask, pendingPlan)
                }
                Mode.TELEGRAM_SEND -> {
                    val raw = etHudCommand.text.toString().trim()
                    if (raw.isNotEmpty()) {
                        panelInput.visibility = View.GONE
                        handleTelegramHudSubmit(raw)
                    }
                }
            }
        }
    }

    private fun handleTelegramHudSubmit(raw: String) {
        if (raw.contains(":")) {
            val recipient = raw.substringBefore(":").trim()
            val body = raw.substringAfter(":").trim()
            if (recipient.isNotEmpty() && body.isNotEmpty()) {
                launchTelegramAutoDriver(recipient, body)
                return
            }
        }
        serviceScope.launch {
            val res = tgClient.sendMessage(raw, tgRepo.defaultChatId)
            res.onSuccess {
                voiceManager.speak("Sent to your Telegram bot.")
                Toast.makeText(this@TiffOverlayService, it, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@TiffOverlayService, "Use 'Name: message' to drive Telegram app, or configure Bot Token.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun populateHudMacrosDrawer(
        container: LinearLayout,
        drawer: LinearLayout,
        panelInput: LinearLayout,
        tvTitle: TextView,
        etCmd: EditText,
        tvPlan: TextView,
        btnExec: Button
    ) {
        container.removeAllViews()
        val macros = macroRepo.getAll()
        if (macros.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No macros saved yet. Open Tiff app to add macros."
                setTextColor(Color.LTGRAY)
                textSize = 12f
            }
            container.addView(emptyTv)
            return
        }

        for (macro in macros) {
            val btn = Button(this).apply {
                text = "\"${macro.triggerPhrase}\" (${macro.actionType.label})"
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#262B36"))
                isAllCaps = false
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 6 }
                layoutParams = lp
                setOnClickListener {
                    drawer.visibility = View.GONE
                    triggerVoiceMacro(macro, panelInput, tvTitle, etCmd, tvPlan, btnExec)
                }
            }
            container.addView(btn)
        }
    }

    private fun triggerVoiceMacro(
        macro: VoiceMacro,
        panelInput: LinearLayout,
        tvTitle: TextView,
        etCmd: EditText,
        tvPlan: TextView,
        btnExec: Button
    ) {
        memoryRepo.recordScreenEvent("Ran macro: ${macro.triggerPhrase}")
        val outcome = AppIntentExecutor.executeMacro(this, macro)
        voiceManager.speak(outcome.spokenMessage)
        if (!outcome.success) {
            Toast.makeText(this, outcome.spokenMessage, Toast.LENGTH_SHORT).show()
            return
        }
        outcome.telegramAppDriveRequest?.let { (recipient, msg) ->
            launchTelegramAutoDriver(recipient, msg)
        }
        outcome.telegramBotSendRequest?.let { (chatId, msg) ->
            serviceScope.launch {
                val res = tgClient.sendMessage(msg, chatId)
                res.onFailure {
                    Toast.makeText(this@TiffOverlayService, "Telegram bot failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        outcome.screenDriveTask?.let { task ->
            pendingTask = task
            currentMode = Mode.DRIVE_PLAN
            panelInput.visibility = View.VISIBLE
            runDrivePlanFlow(task, tvTitle, etCmd, tvPlan, btnExec)
        }
    }

    private fun runPointFlow(query: String) {
        serviceScope.launch {
            delay(250)
            val bitmap = captureScreenBitmap()
            if (bitmap == null) {
                Toast.makeText(this@TiffOverlayService, "Screen capture not ready. Restart Tiff.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                val result = aiClient.findTargetOnScreen(bitmap, query)
                arrowView?.showPointer(result.xPercent, result.yPercent, result.label)
                voiceManager.speak(result.spokenLine)
                val note = result.screenSummary.ifBlank { "Pointed at '${result.label}' for '$query'" }
                memoryRepo.recordScreenEvent(note)
            } catch (e: Exception) {
                Toast.makeText(this@TiffOverlayService, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runDrivePlanFlow(
        task: String,
        tvTitle: TextView,
        etCmd: EditText,
        tvPlan: TextView,
        btnExec: Button
    ) {
        serviceScope.launch {
            tvPlan.visibility = View.VISIBLE
            tvPlan.text = "Tiff is inspecting the screen..."
            val bitmap = captureScreenBitmap()
            if (bitmap == null) {
                tvPlan.text = "Screen capture unavailable."
                return@launch
            }
            try {
                val steps = aiClient.generateDrivePlan(bitmap, task)
                if (steps.firstOrNull()?.startsWith("REFUSED:") == true) {
                    val msg = steps.first()
                    tvPlan.text = msg
                    voiceManager.speak(msg.removePrefix("REFUSED:").trim())
                    btnExec.text = "Blocked"
                    return@launch
                }
                pendingPlan = steps
                currentMode = Mode.DRIVE_CONFIRM
                tvTitle.text = "Tiff's Plan (Tap GO to start)"
                etCmd.visibility = View.GONE
                tvPlan.text = steps.mapIndexed { idx, s -> "${idx + 1}. $s" }.joinToString("\n")
                btnExec.text = "GO"
                voiceManager.speak("Here's my plan, ${soulRepo.userNickname}. Say go or tap Go when you're ready.")
            } catch (e: Exception) {
                tvPlan.text = "Error: ${e.message}"
            }
        }
    }

    private fun runDriveExecutionLoop(task: String, plan: List<String>) {
        val a11y = TiffAccessibilityService.instance
        if (a11y == null) {
            Toast.makeText(this, "Enable Tiff Accessibility Service first!", Toast.LENGTH_LONG).show()
            return
        }

        activeDriveJob?.cancel()
        activeDriveJob = serviceScope.launch {
            val history = mutableListOf<String>()
            memoryRepo.recordScreenEvent("Started drive: $task")
            voiceManager.speak("Taking the wheel now.")

            for (stepIndex in 1..8) {
                if (!isActive) break
                delay(700)
                val bitmap = captureScreenBitmap() ?: break
                val step = try {
                    aiClient.decideNextDriveStep(bitmap, task, plan, history)
                } catch (e: Exception) {
                    voiceManager.speak("Hit a snag, stopping here.")
                    break
                }

                if (!isActive) break
                voiceManager.speak(step.narration)
                history.add("${step.action}: ${step.narration}")

                when (step.action) {
                    "tap" -> {
                        arrowView?.showPointer(step.xPercent, step.yPercent, "Tapping")
                        delay(450)
                        arrowView?.hidePointer()
                        a11y.performTap(step.xPercent, step.yPercent)
                    }
                    "type" -> a11y.typeTextIntoFocusedField(step.text)
                    "scroll_down" -> a11y.performScroll(down = true)
                    "scroll_up" -> a11y.performScroll(down = false)
                    "back" -> a11y.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    "refuse", "done" -> break
                }
                delay(1100)
            }
            arrowView?.hidePointer()
            memoryRepo.recordScreenEvent("Completed drive: $task")
        }
    }

    private fun startVoiceCommand(
        panelInput: LinearLayout,
        tvTitle: TextView,
        etCmd: EditText,
        tvPlan: TextView,
        btnExec: Button
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition unavailable on device", Toast.LENGTH_SHORT).show()
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Toast.makeText(this@TiffOverlayService, "Tiff is listening...", Toast.LENGTH_SHORT).show()
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onResults(results: Bundle?) {
                    val rawSpoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    val spoken = rawSpoken.lowercase(Locale.ROOT).trim()
                    val cleanedForWakeWord = spoken
                        .removePrefix("hey tiff")
                        .removePrefix("tiff")
                        .removePrefix(",")
                        .trim()

                    if (cleanedForWakeWord.startsWith("remember that ") || cleanedForWakeWord.startsWith("remember ")) {
                        val fact = rawSpoken
                            .replace(Regex("(?i)^(hey\s+)?tiff[,\s]*"), "")
                            .replace(Regex("(?i)^remember(\s+that)?\s+"), "")
                            .trim()
                        if (fact.isNotEmpty()) {
                            memoryRepo.addLongTermMemory(fact, MemoryCategory.USER_FACT)
                            voiceManager.speak("Got it, ${soulRepo.userNickname}. I'll remember that.")
                            Toast.makeText(this@TiffOverlayService, "Saved to Tiff's memory.", Toast.LENGTH_SHORT).show()
                            return
                        }
                    }

                    if (cleanedForWakeWord.contains("send screen to telegram") || cleanedForWakeWord.contains("screenshot to telegram")) {
                        serviceScope.launch {
                            val bmp = captureScreenBitmap()
                            if (bmp != null) {
                                tgClient.sendScreenshot(bmp, "Sent by voice from Tiff")
                                voiceManager.speak("Sent your screenshot to Telegram.")
                            }
                        }
                        return
                    }

                    val matchedMacro = macroRepo.findMatchingMacro(cleanedForWakeWord)
                    if (matchedMacro != null) {
                        triggerVoiceMacro(matchedMacro, panelInput, tvTitle, etCmd, tvPlan, btnExec)
                        return
                    }

                    val directIntentOutcome = AppIntentExecutor.tryParseDirectVoiceIntent(
                        this@TiffOverlayService,
                        rawSpoken.replace(Regex("(?i)^(hey\s+)?tiff[,\s]*"), "").trim()
                    )
                    if (directIntentOutcome != null) {
                        directIntentOutcome.telegramAppDriveRequest?.let { (recipient, msg) ->
                            launchTelegramAutoDriver(recipient, msg)
                            return
                        }
                        voiceManager.speak(directIntentOutcome.spokenMessage)
                        directIntentOutcome.telegramBotSendRequest?.let { (chatId, msg) ->
                            serviceScope.launch {
                                val res = tgClient.sendMessage(msg, chatId)
                                res.onFailure {
                                    Toast.makeText(this@TiffOverlayService, "Telegram error: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        return
                    }

                    when {
                        cleanedForWakeWord.contains("stop") || cleanedForWakeWord.contains("hand me back") -> {
                            activeDriveJob?.cancel()
                            arrowView?.hidePointer()
                            panelInput.visibility = View.GONE
                            voiceManager.speak("All yours, ${soulRepo.userNickname}.")
                        }
                        cleanedForWakeWord == "go" && currentMode == Mode.DRIVE_CONFIRM -> {
                            panelInput.visibility = View.GONE
                            runDriveExecutionLoop(pendingTask, pendingPlan)
                        }
                        cleanedForWakeWord.contains("quiet") || cleanedForWakeWord.contains("mute") -> {
                            prefs.quietMode = true
                            voiceManager.stop()
                        }
                        cleanedForWakeWord.contains("narrate") || cleanedForWakeWord.contains("unmute") -> {
                            prefs.quietMode = false
                            voiceManager.speak("I'm back!")
                        }
                        cleanedForWakeWord.startsWith("take over") -> {
                            val task = cleanedForWakeWord.removePrefix("take over").removePrefix("and").trim()
                            pendingTask = task
                            currentMode = Mode.DRIVE_PLAN
                            panelInput.visibility = View.VISIBLE
                            runDrivePlanFlow(task, tvTitle, etCmd, tvPlan, btnExec)
                        }
                        else -> {
                            panelInput.visibility = View.GONE
                            runPointFlow(cleanedForWakeWord)
                        }
                    }
                }
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
        }
        speechRecognizer?.startListening(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tiff Screen Assistant",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tiff is awake")
            .setContentText("Telegram Auto-Driver, screen guide & memory active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
    }

    override fun onDestroy() {
        activeDriveJob?.cancel()
        telegramPollingJob?.cancel()
        serviceScope.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        voiceManager.shutdown()
        speechRecognizer?.destroy()
        hudView?.let { windowManager.removeView(it) }
        arrowView?.let { windowManager.removeView(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
