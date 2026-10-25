package com.yomu.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.RectF
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import com.yomu.app.MainActivity
import com.yomu.app.capture.ScreenCaptureManager
import com.yomu.app.detection.DetectionThresholdStore
import com.yomu.app.overlay.FloatingButtonView
import com.yomu.app.overlay.FloatingButtonOverlay
import com.yomu.app.overlay.OverlayBounds
import com.yomu.app.overlay.OverlayBubbleState
import com.yomu.app.overlay.OverlayLanguageSwitch
import com.yomu.app.overlay.QuickSettingsPopup
import com.yomu.app.overlay.TranslationRenderOverlay
import com.yomu.app.overlay.TranslationStatusOverlay
import com.yomu.app.overlay.boundsOnScreen
import com.yomu.app.db.entities.ModelType
import com.yomu.app.db.entities.TranslationSessionEntity
import com.yomu.app.translation.ResourceLimitsStore
import com.yomu.app.translation.NameGlossaryStore
import com.yomu.app.translation.TargetLanguageStore
import com.yomu.app.translation.TranslationModelSelection
import com.yomu.core.Constants
import com.yomu.core.TargetLanguage
import com.yomu.pipeline.ModelPaths
import com.yomu.pipeline.PipelineResult
import com.yomu.pipeline.TranslationPipeline
import com.yomu.pipeline.translation.readerFailure
import com.yomu.pipeline.translation.untranslatedNotice
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume

@AndroidEntryPoint
class OverlayService : Service() {

    @Inject lateinit var screenCaptureManager: ScreenCaptureManager
    @Inject lateinit var translationPipeline: TranslationPipeline
    @Inject lateinit var sharedPreferences: SharedPreferences
    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var modelManager: ModelManager
    @Inject lateinit var readingSelection: ReadingModelSelection
    @Inject lateinit var slotSelection: ModelSlotSelection
    @Inject lateinit var translationSelection: TranslationModelSelection

    private var readingModelFiles: List<File> = emptyList()
    private val pageRecall = PageRecall()
    @Volatile private var lastPage: PageRecord? = null
        set(value) {
            field = value
            mainScope.launch { quickSettingsPopup?.refreshReportAvailability() }
        }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingButtonOverlay: FloatingButtonOverlay
    private lateinit var translationRenderOverlay: TranslationRenderOverlay
    private var floatingButton: FloatingButtonView? = null
    private lateinit var statusOverlay: TranslationStatusOverlay
    private var quickSettingsPopup: QuickSettingsPopup? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
        when (key) {
            Constants.PREF_THEME -> updateOverlayAppearance()
            Constants.PREF_FONT_SIZE_SCALE -> translationPipeline.fontSizeScale = preferences.getFloat(
                key,
                Constants.DEFAULT_FONT_SIZE_SCALE
            )
            DetectionThresholdStore.KEY -> translationPipeline.confidenceThreshold = DetectionThresholdStore(preferences).load()
            PageRecallStore.KEY -> pageRecall.enabled = PageRecallStore(preferences).load()
            // A new OCR model reads another language: the next capture loads it in place of the old one.
            Constants.PREF_OCR_MODEL -> {
                configureReadingModels()
                scope.launch { translationPipeline.unloadOcr() }
            }
            // The detector stays loaded across pages, so a new one only takes over once the old one is released.
            Constants.PREF_DETECTION_MODEL -> {
                configureReadingModels()
                scope.launch { translationPipeline.unloadDetection() }
            }
        }
    }

    private var buttonPositionX: Int = 0
    private var buttonPositionY: Int = 0

    private var translationJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "OverlayService"
        private const val MANGA_OCR_FILES = 3
        const val ACTION_SERVICE_STARTED = "com.yomu.app.SERVICE_STARTED"
        const val ACTION_SERVICE_STOPPED = "com.yomu.app.SERVICE_STOPPED"
        const val ACTION_SERVICE_START_FAILED = "com.yomu.app.SERVICE_START_FAILED"
        const val EXTRA_MEDIA_PROJECTION_DATA = "media_projection_data"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_FAILURE_REASON = "failure_reason"
        private const val CHANNEL_NAME = "Yomu Overlay"
        private const val CHANNEL_DESC = "Yomu translation overlay service"
        private const val RELEASE_WAIT_MS = 2_000L

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingButtonOverlay = FloatingButtonOverlay(this, windowManager)
        translationRenderOverlay = TranslationRenderOverlay(this, windowManager)
        statusOverlay = TranslationStatusOverlay(this, windowManager)
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        createNotificationChannel()
        configureReadingModels()
        translationPipeline.fontSizeScale = sharedPreferences.getFloat(
            Constants.PREF_FONT_SIZE_SCALE,
            Constants.DEFAULT_FONT_SIZE_SCALE
        )
        translationPipeline.confidenceThreshold = DetectionThresholdStore(sharedPreferences).load()
        pageRecall.enabled = PageRecallStore(sharedPreferences).load()
    }

    /** manga-ocr is an encoder, decoder and vocabulary; a PP-OCR recognizer is a model and its inference.yml. */
    private fun configureReadingModels() {
        val detectionFiles = modelManager.modelFiles(readingSelection.selected(ModelType.DETECTION))
        val ocrFiles = modelManager.modelFiles(readingSelection.selected(ModelType.OCR))
        readingModelFiles = detectionFiles + ocrFiles
        translationPipeline.modelPaths = ModelPaths(
            bubbleDetectionPath = detectionFiles[0].absolutePath,
            ocrEncoderPath = ocrFiles[0].absolutePath,
            ocrDecoderPath = if (ocrFiles.size == MANGA_OCR_FILES) ocrFiles[1].absolutePath else "",
            ocrVocabPath = ocrFiles.last().absolutePath,
            ocrSource = readingSelection.source()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            notifyStartFailed("Overlay permission missing")
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Constants.OVERLAY_NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(Constants.OVERLAY_NOTIFICATION_ID, notification)
        }

        val data = intent?.getParcelableExtra<Intent>(EXTRA_MEDIA_PROJECTION_DATA)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        if (data == null || resultCode != android.app.Activity.RESULT_OK) {
            notifyStartFailed("MediaProjection consent missing")
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, data)
        if (projection == null) {
            notifyStartFailed("MediaProjection initialization failed")
            stopSelf()
            return START_NOT_STICKY
        }

        // A button left after sharing ends would fail every tap; with the service gone, Home reads "Reading is OFF" (#353).
        screenCaptureManager.startProjection(projection) {
            Log.i(TAG, "Screen sharing ended; stopping")
            stopSelf()
        }

        showFloatingButton()
        sendBroadcast(Intent(ACTION_SERVICE_STARTED).setPackage(packageName))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removeQuickSettingsPopup()
        floatingButtonOverlay.keepInBounds()
        updateOverlayAppearance()
    }

    private fun updateOverlayAppearance() {
        floatingButton?.updateAppearance()
        quickSettingsPopup?.updateAppearance()
        statusOverlay.updateAppearance()
    }

    /** Cancels translation, clears session page recall and the last page, and releases overlays, capture, and pipeline resources. */
    override fun onDestroy() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        translationJob?.let { job ->
            // The models are freed below: let an in-flight native call take the abort first (#76).
            job.cancel()
            runBlocking { withTimeoutOrNull(RELEASE_WAIT_MS) { job.join() } }
        }
        pageRecall.clear()
        lastPage = null
        removeQuickSettingsPopup()
        removeFloatingButton()
        translationRenderOverlay.remove()
        statusOverlay.remove()
        screenCaptureManager.stopProjection()
        translationPipeline.release()
        translationPipeline.close()
        scope.cancel()
        mainScope.cancel()
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED).setPackage(packageName))
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.OVERLAY_CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, Constants.OVERLAY_CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Yomu")
            .setContentText("Tap the floating button to translate manga")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
    }

    private fun showFloatingButton() {
        val savedX = sharedPreferences.getInt(Constants.PREF_BUTTON_POSITION_X, 0)
        val savedY = sharedPreferences.getInt(Constants.PREF_BUTTON_POSITION_Y, 200)
        buttonPositionX = savedX
        buttonPositionY = savedY
        floatingButton = floatingButtonOverlay.show(
            initialX = savedX,
            initialY = savedY,
            onTap = {
                if (floatingButton?.currentState == FloatingButtonView.State.TRANSLATING) {
                    cancelTranslation()
                } else {
                    startTranslation()
                }
            },
            onDragEnd = { x, y -> persistButtonPosition(x, y) },
            onLongPress = { showQuickSettings() },
            onPress = ::removeQuickSettingsPopup
        )
    }

    private fun removeFloatingButton() {
        floatingButtonOverlay.remove()
        floatingButton = null
    }

    private val languageSwitch by lazy {
        OverlayLanguageSwitch(
            context = this,
            targetLanguage = slotSelection::targetLanguage,
            unavailable = { slotSelection.languageUnavailable(it, modelManager.deviceTotalMemBytes()) },
            onChosen = ::chooseLanguage
        )
    }

    private fun showQuickSettings() {
        val thresholdStore = DetectionThresholdStore(sharedPreferences)
        val popup = quickSettingsPopup ?: QuickSettingsPopup(
            context = this,
            windowManager = windowManager,
            modelName = ::translationModelName,
            languageSwitch = languageSwitch,
            onFontSizeChanged = { scale ->
                sharedPreferences.edit().putFloat(Constants.PREF_FONT_SIZE_SCALE, scale).apply()
            },
            onThresholdChanged = { thresholdStore.save(it) },
            hasLastPage = { lastPage != null },
            onReportRequested = ::reportLastPage,
            onOpenAppRequested = ::openApp,
            onStopRequested = { stopSelf() },
            blockTouchesBelow = floatingButtonOverlay::blockTouchesBelow,
            unblockTouchesBelow = floatingButtonOverlay::unblockTouchesBelow
        ).also { quickSettingsPopup = it }
        // The app can change either value while the service runs, so re-read both on every long-press.
        popup.updateFontSizeScale(
            sharedPreferences.getFloat(Constants.PREF_FONT_SIZE_SCALE, Constants.DEFAULT_FONT_SIZE_SCALE)
        )
        popup.updateThreshold(thresholdStore.load())
        popup.show(buttonPositionX, buttonPositionY)
    }

    /** Sets [language], or opens the app to offer the model it needs when the one in use writes English only (#338). */
    private fun chooseLanguage(language: TargetLanguage) {
        // On the main thread, like the app's screens, since the slot selection's pending picks are not shared across threads.
        scope.launch(Dispatchers.Main) {
            if (slotSelection.switchLanguage(language) is LanguageSwitch.Offer) {
                removeQuickSettingsPopup()
                openApp(language)
            } else {
                languageSwitch.update()
            }
        }
    }

    private fun openApp(language: TargetLanguage? = null) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { language?.let { putExtra(MainActivity.EXTRA_TARGET_LANGUAGE, it.code) } }
        )
    }

    /** Shares the last page's report through the share sheet; writing it stays off the main thread. */
    private fun reportLastPage() {
        val record = lastPage ?: return
        scope.launch {
            val share = runCatching { pageReportShareIntent(record) }
                .onFailure { Log.e(TAG, "Page report failed", it) }
                .getOrNull()
            withContext(Dispatchers.Main) {
                if (share != null) {
                    startActivity(share)
                } else {
                    statusOverlay.showOrUpdate("Could not write the page report")
                    delay(1500)
                    statusOverlay.remove()
                }
            }
        }
    }

    private fun translationModelName(): String = slotSelection.selectedId(ModelType.LLM)
        ?.let { id -> slotSelection.deliverables(ModelType.LLM).firstOrNull { it.id == id }?.name }
        ?: "No model selected"

    private fun removeQuickSettingsPopup() {
        quickSettingsPopup?.remove()
        quickSettingsPopup = null
    }

    private fun cancelTranslation() {
        translationJob?.cancel()
        translationRenderOverlay.remove()
        statusOverlay.remove()
        floatingButton?.setState(FloatingButtonView.State.IDLE)
    }

    /**
     * Starts a capture unless a translation is active, streams previews to the main thread, and saves successful results.
     * A page already translated this session with the same settings is shown at once, with no pipeline run or history row.
     * Late preview updates are ignored after cancellation so they cannot restore a cleared overlay.
     */
    private fun startTranslation() {
        // A cancelled job is no longer active; the new one queues behind it on the pipeline's lock.
        if (translationJob?.isActive == true) return
        removeQuickSettingsPopup()
        floatingButton?.setState(FloatingButtonView.State.TRANSLATING)

        translationJob = scope.launch {
            val job = coroutineContext.job
            val statuses = modelManager.getAllModels().first().associate { it.id to it.status }
            if (!slotSelection.ready(statuses) || !readingModelFiles.all { it.exists() }) {
                failTranslation(job, "Download a model in Settings")
                return@launch
            }

            updateStatus(job, "Capturing screen")
            if (!screenCaptureManager.isProjectionActive) {
                failTranslation(job, "No active MediaProjection")
                return@launch
            }

            Log.i(TAG, "Capture begin projectionActive=${screenCaptureManager.isProjectionActive}")
            val bitmap = captureBitmap()
            Log.i(TAG, "Capture result isNull=${bitmap == null}")
            if (bitmap == null) {
                failTranslation(job, "Capture returned no frame")
                return@launch
            }
            // A page that ends without a result must not leave the one before it to be reported.
            lastPage = null

            val ignored = withContext(Dispatchers.Main) { ignoredRegions(bitmap.width) }
            val fingerprintStart = System.currentTimeMillis()
            val fingerprint = PageFingerprint.of(bitmap, ignored)
            val settings = pageSettings()
            val recallBegunAt = pageRecall.timesTurnedOff
            val recalled = pageRecall.recall(fingerprint, settings)
            Log.i(TAG, "Page recall hit=${recalled != null} ms=${System.currentTimeMillis() - fingerprintStart} ignored=$ignored")
            recalled?.let { page ->
                lastPage = PageRecord(bitmap, page, settings, recalled = true)
                withContext(Dispatchers.Main) {
                    // Idle before the notice: a recall is instant, so a spinning button would read as a stall.
                    floatingButton?.setState(FloatingButtonView.State.IDLE)
                    val notices = listOfNotNull("Already translated", page.translationResult.untranslatedNotice())
                    showFinishedPage(page, notices.joinToString(" · "))
                }
                return@launch
            }

            val callback = object : TranslationPipeline.PipelineCallback {
                /** Logs pipeline progress and posts the stage message while the translation job is active. */
                override fun onStageProgress(stage: TranslationPipeline.Stage, progress: Float) {
                    Log.i(TAG, "Pipeline progress stage=$stage progress=$progress")
                    updateStatus(job, statusOverlay.messageForStage(stage))
                }

                /** Logs a pipeline error and posts its message while the translation job is active. */
                override fun onError(stage: TranslationPipeline.Stage, message: String) {
                    Log.e(TAG, "Pipeline error stage=$stage message=$message")
                    updateStatus(job, "Failed: $message")
                }

                /** Logs the completed page’s bubble count and timing, then posts the drawing status. */
                override fun onComplete(result: PipelineResult) {
                    Log.i(TAG, "Pipeline complete bubbles=${result.typesetBubbles.size} timeMs=${result.totalTimeMs}")
                    updateStatus(job, "Drawing translation")
                }
            }

            val ocrStates = mutableListOf<OverlayBubbleState>()

            val onOcrComplete: (Int, String, RectF) -> Unit = { bubbleId, ocrText, bounds ->
                val state = OverlayBubbleState(
                    bubbleId = bubbleId,
                    bounds = OverlayBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    ocrText = ocrText
                )
                ocrStates.add(state)
                mainScope.launch {
                    // A cancel already cleared the screen; a late OCR hit must not redraw it.
                    if (job.isActive) translationRenderOverlay.showOcrBubbles(ocrStates, bitmap.width, bitmap.height)
                }
            }

            val session = sessionManager.getOrCreateSession("manual")

            val result = translationPipeline.processPage(
                bitmap,
                callback = callback,
                onOcrComplete = onOcrComplete,
                onBubble = { bubble ->
                    mainScope.launch {
                        if (job.isActive) translationRenderOverlay.showTypesetBubble(bubble)
                    }
                },
                ignoredRegions = ignored.map { RectF(it.left, it.top, it.right, it.bottom) },
                targetLanguage = settings.targetLanguage,
                glossary = settings.glossary,
                sourceLanguage = readingSelection.source()
            )
            if (result != null) lastPage = PageRecord(bitmap, result, settings, recalled = false)
            val failure = result?.translationResult?.readerFailure()
            if (result != null && failure == null && result.translationResult.translations.isNotEmpty()) {
                saveSessionResult(session, result)
                // A setting changed mid-page leaves a result made under neither snapshot.
                if (pageSettings() == settings) pageRecall.remember(fingerprint, settings, result, recallBegunAt)
            }
            if (failure != null) {
                // The row that said READY has no file behind it: put the picker right now, rather
                // than leaving it to the next Home or Settings visit (#308).
                modelManager.refreshModelList()
            }
            withContext(Dispatchers.Main) {
                if (result != null && failure == null) {
                    showFinishedPage(result, result.translationResult.untranslatedNotice())
                } else {
                    // The OCR pass may already have drawn its bubbles, and that overlay is
                    // untouchable — without this the reader is left with pinned Japanese (#308).
                    translationRenderOverlay.remove()
                    // A null result already put the pipeline's own "Failed: …" on the status line.
                    failure?.let { statusOverlay.showOrUpdate("Failed: $it") }
                    delay(1500)
                    statusOverlay.remove()
                }
                floatingButton?.setState(FloatingButtonView.State.IDLE)
            }
        }
    }

    private suspend fun captureBitmap() = suspendCancellableCoroutine { continuation ->
        screenCaptureManager.captureScreen { bitmap ->
            if (continuation.isActive) {
                continuation.resume(bitmap)
            }
        }
    }

    /** Draws a finished page on the main thread, then shows [notice] above it for a moment. */
    private suspend fun showFinishedPage(page: PipelineResult, notice: String?) {
        if (page.typesetBubbles.isEmpty()) {
            translationRenderOverlay.remove()
        } else {
            translationRenderOverlay.show(page.typesetBubbles, page.pageWidth, page.pageHeight)
        }
        statusOverlay.remove()
        // Not a toast: Android drops toasts from a background app without notification permission.
        // Re-adding the status line after the page keeps it above the drawn bubbles.
        notice?.let {
            statusOverlay.showOrUpdate(it)
            delay(1500)
            statusOverlay.remove()
        }
    }

    /**
     * The status bar while it is shown, the status line under it and the floating button: they differ
     * between two captures of one page, and no bubble there is the reader's. Read on the main thread once the capture is in.
     */
    private fun ignoredRegions(pageWidth: Int): List<OverlayBounds> = listOfNotNull(
        OverlayBounds(0f, 0f, pageWidth.toFloat(), statusBarHeight().toFloat()),
        statusOverlay.boundsOnScreen(),
        floatingButton?.boundsOnScreen()
    )

    /**
     * Returns the height in pixels of the status bar while it is shown, and zero while the reader hides
     * it, since the reader then puts manga in its strip (#377). Before R nothing public tells an overlay
     * whether the bar is shown, so the legacy dimension always applies, or zero if it is unavailable.
     */
    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeight(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
    } else {
        resources.getIdentifier("status_bar_height", "dimen", "android").let { id ->
            if (id == 0) 0 else resources.getDimensionPixelSize(id)
        }
    }

    /** Snapshots the model choices, generation and runtime limits, rendering settings, target language and name glossary used as the recall key. */
    private fun pageSettings() = PageSettings(
        translationModel = slotSelection.selectedId(ModelType.LLM),
        detectionModel = slotSelection.selectedId(ModelType.DETECTION),
        ocrModel = slotSelection.selectedId(ModelType.OCR),
        generation = translationSelection.generationProfile().params,
        runtime = ResourceLimitsStore(sharedPreferences).runtime(),
        detectionThreshold = translationPipeline.confidenceThreshold,
        fontScale = translationPipeline.fontSizeScale,
        targetLanguage = TargetLanguageStore(sharedPreferences).load(),
        glossary = NameGlossaryStore(sharedPreferences).load()
    )

    private suspend fun saveSessionResult(session: TranslationSessionEntity, result: PipelineResult) {
        val translatedText = result.translationResult.translations.joinToString("\n") { translation ->
            "[${translation.bubbleId}] ${translation.originalText} → ${translation.translatedText}"
        }
        if (translatedText.isBlank()) return
        sessionManager.saveTranslation(
            sessionId = session.id,
            sourceImagePath = "",
            translatedText = translatedText,
            sourceLanguage = Constants.DEFAULT_SOURCE_LANGUAGE,
            targetLanguage = result.translationResult.targetLanguage.code,
            bubbleCount = result.translationResult.translations.size,
            translationTimeMs = result.totalTimeMs
        )
    }

    private suspend fun failTranslation(job: Job, reason: String) {
        Log.e(TAG, reason)
        updateStatus(job, "Failed: $reason")
        withContext(Dispatchers.Main) {
            delay(1500)
            statusOverlay.remove()
            floatingButton?.setState(FloatingButtonView.State.IDLE)
        }
    }

    private fun persistButtonPosition(x: Int, y: Int) {
        buttonPositionX = x
        buttonPositionY = y
        sharedPreferences.edit()
            .putInt(Constants.PREF_BUTTON_POSITION_X, x)
            .putInt(Constants.PREF_BUTTON_POSITION_Y, y)
            .apply()
    }

    private fun updateStatus(job: Job, message: String) {
        mainScope.launch {
            if (job.isActive) statusOverlay.showOrUpdate(message)
        }
    }

    private fun notifyStartFailed(reason: String) {
        Log.e(TAG, reason)
        sendBroadcast(
            Intent(ACTION_SERVICE_START_FAILED)
                .setPackage(packageName)
                .putExtra(EXTRA_FAILURE_REASON, reason)
        )
    }

}
