package com.david.livetranslator

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

// ============================================================================
// NASTAVITELNÉ PARAMETRY APLIKACE (Zde můžeš ladit chování překladače)
// ============================================================================
object Config {
    const val SCAN_INTERVAL_MS = 700L          // Interval snímání obrazovky v ms (0.7 s)
    const val SUBTITLE_AREA_TOP_RATIO = 0.72f  // Kde začíná zóna titulků (72 % výšky odshora)
    const val SUBTITLE_AREA_BOTTOM_RATIO = 0.96f // Kde končí zóna titulků (96 % výšky odshora)
    const val SPEECH_RATE = 1.15f              // Rychlost českého hlasu (1.0 = normální)
    const val OVERLAY_TEXT_SIZE_SP = 18f       // Velikost písma českých titulků
}

// ============================================================================
// HLAVNÍ OBRAZOVKA APLIKACE (UI generované přímo v kódu bez nutnosti XML)
// ============================================================================
class MainActivity : AppCompatActivity() {

    private lateinit var tvModelStatus: TextView
    private lateinit var switchTts: SwitchMaterial
    private lateinit var btnOverlayPermission: Button
    private lateinit var btnStartCapture: Button
    private lateinit var btnStopCapture: Button

    private var isModelReady = false

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, TranslatorService::class.java).apply {
                action = TranslatorService.ACTION_START
                putExtra(TranslatorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(TranslatorService.EXTRA_DATA, result.data)
                putExtra(TranslatorService.EXTRA_ENABLE_TTS, switchTts.isChecked)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Překladač běží! Přepni se do PS Remote Play nebo YouTube.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true) // Minimalizuje aplikaci na pozadí
        } else {
            Toast.makeText(this, "Snímání obrazovky nebylo povoleno.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupProgrammaticUI()
        prepareOfflineTranslator()
    }

    override fun onResume() {
        super.onResume()
        updateButtonsState()
    }

    // Vytvoření kompletní obrazovky přímo v Kotlinu (bez XML souboru)
    private fun setupProgrammaticUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 80, 60, 60)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val tvTitle = TextView(this).apply {
            text = "GTA 6 & Video Překladač"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 40)
        }

        tvModelStatus = TextView(this).apply {
            text = "Stav slovníku: Kontroluji offline model..."
            textSize = 15f
            setTextColor(Color.parseColor("#E0E0E0"))
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(36, 36, 36, 36)
        }

        switchTts = SwitchMaterial(this).apply {
            text = "Číst překlad nahlas (Český dabing)"
            isChecked = true
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 50, 0, 50)
        }

        btnOverlayPermission = Button(this).apply {
            text = "1. Povolit plovoucí okno (Overlay)"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this@MainActivity, "Plovoucí okno už je povolené!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnStartCapture = Button(this).apply {
            text = "2. Spustit překládání obrazovky"
            isEnabled = false
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Nejdřív povol plovoucí okno v kroku 1.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(pm.createScreenCaptureIntent())
            }
        }

        btnStopCapture = Button(this).apply {
            text = "Zastavit překladač"
            setBackgroundColor(Color.parseColor("#B00020"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val stopIntent = Intent(this@MainActivity, TranslatorService::class.java).apply {
                    action = TranslatorService.ACTION_STOP
                }
                startService(stopIntent)
                Toast.makeText(this@MainActivity, "Překladač zastaven.", Toast.LENGTH_SHORT).show()
            }
        }

        rootLayout.addView(tvTitle)
        rootLayout.addView(tvModelStatus)
        rootLayout.addView(switchTts)
        rootLayout.addView(btnOverlayPermission)
        rootLayout.addView(btnStartCapture)
        rootLayout.addView(btnStopCapture)

        setContentView(rootLayout)
    }

    private fun prepareOfflineTranslator() {
        tvModelStatus.text = "Stav slovníku: Stahuji offline EN -> CZ model (cca 30 MB)..."

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.CZECH)
            .build()

        val translator = Translation.getClient(options)
        val conditions = DownloadConditions.Builder().build()

        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                isModelReady = true
                tvModelStatus.text = "Stav slovníku: PŘIPRAVEN ✓ (100% Offline)"
                tvModelStatus.setTextColor(Color.parseColor("#81C784"))
                updateButtonsState()
                translator.close()
            }
            .addOnFailureListener { e ->
                tvModelStatus.text = "Chyba stahování slovníku: ${e.localizedMessage}"
                tvModelStatus.setTextColor(Color.parseColor("#E57373"))
                translator.close()
            }
    }

    private fun updateButtonsState() {
        val hasOverlay = Settings.canDrawOverlays(this)
        btnOverlayPermission.text = if (hasOverlay) "1. Plovoucí okno POVOLENO ✓" else "1. Povolit plovoucí okno (Overlay)"
        btnStartCapture.isEnabled = isModelReady && hasOverlay
    }

    // ========================================================================
    // SLUŽBA NA POZADÍ: Snímání obrazovky + OCR + Překlad + Overlay + Hlas
    // ========================================================================
    class TranslatorService : Service(), TextToSpeech.OnInitListener {

        companion object {
            const val ACTION_START = "ACTION_START"
            const val ACTION_STOP = "ACTION_STOP"
            const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
            const val EXTRA_DATA = "EXTRA_DATA"
            const val EXTRA_ENABLE_TTS = "EXTRA_ENABLE_TTS"
            const val CHANNEL_ID = "TranslatorServiceChannel"
        }

        private var mediaProjection: MediaProjection? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var imageReader: ImageReader? = null
        private var windowManager: WindowManager? = null
        private var overlayView: TextView? = null

        private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        private lateinit var translator: Translator
        private var tts: TextToSpeech? = null

        private var enableTts = true
        private var isTtsReady = false
        private var lastRecognizedText = ""
        private var isProcessingFrame = false

        private val handler = Handler(Looper.getMainLooper())
        private var screenWidth = 0
        private var screenHeight = 0
        private var screenDensity = 0

        private val scanRunnable = object : Runnable {
            override fun run() {
                captureAndProcessScreen()
                handler.postDelayed(this, Config.SCAN_INTERVAL_MS)
            }
        }

        override fun onCreate() {
            super.onCreate()
            createNotificationChannel()

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.CZECH)
                .build()
            translator = Translation.getClient(options)
            tts = TextToSpeech(this, this)

            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
        }

        override fun onInit(status: Int) {
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale("cs", "CZ"))
                if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setSpeechRate(Config.SPEECH_RATE)
                    isTtsReady = true
                }
            }
        }

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            when (intent?.action) {
                ACTION_START -> {
                    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("Real-Time Překladač aktivní")
                        .setContentText("Sleduji titulky na obrazovce...")
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .build()
                    startForeground(1, notification)

                    enableTts = intent.getBooleanExtra(EXTRA_ENABLE_TTS, true)
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                    @Suppress("DEPRECATION")
                    val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)

                    if (data != null) {
                        setupOverlay()
                        startMediaProjection(resultCode, data)
                        handler.post(scanRunnable)
                    }
                }
                ACTION_STOP -> {
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }

        // Vytvoření plovoucího okna s překladem (lze jej prstem přesouvat po obrazovce)
        private fun setupOverlay() {
            if (overlayView != null) return
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            overlayView = TextView(this).apply {
                text = "Čekám na anglické titulky..."
                textSize = Config.OVERLAY_TEXT_SIZE_SP
                setTextColor(Color.YELLOW)
                setBackgroundColor(Color.parseColor("#B3000000")) // Poloprůhledná černá
                setPadding(32, 20, 32, 20)
                gravity = Gravity.CENTER
            }

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 120 // Výška od spodního okraje obrazovky
            }

            // Možnost posouvat plovoucí okno prstem nahoru/dolů
            overlayView?.setOnTouchListener(object : View.OnTouchListener {
                private var initialY = 0
                private var initialTouchY = 0f

                override fun onTouch(v: View?, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialY = params.y
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.y = initialY - (event.rawY - initialTouchY).toInt()
                            windowManager?.updateViewLayout(overlayView, params)
                            return true
                        }
                    }
                    return false
                }
            })

            windowManager?.addView(overlayView, params)
        }

        private fun startMediaProjection(resultCode: Int, data: Intent) {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = pm.getMediaProjection(resultCode, data)

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "SubtitleCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
        }

        private fun captureAndProcessScreen() {
            if (isProcessingFrame) return
            val image = imageReader?.acquireLatestImage() ?: return
            isProcessingFrame = true

            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val fullBitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)
                image.close()

                // Ořízneme pouze spodní část obrazovky, kde jsou titulky
                val cropTop = (screenHeight * Config.SUBTITLE_AREA_TOP_RATIO).toInt()
                val cropBottom = (screenHeight * Config.SUBTITLE_AREA_BOTTOM_RATIO).toInt()
                val cropHeight = (cropBottom - cropTop).coerceAtLeast(50)

                val croppedBitmap = Bitmap.createBitmap(
                    fullBitmap,
                    0,
                    cropTop,
                    screenWidth,
                    cropHeight
                )
                fullBitmap.recycle()

                val inputImage = InputImage.fromBitmap(croppedBitmap, 0)
                textRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val detectedText = visionText.text.replace("\n", " ").trim()
                        if (detectedText.length > 2 && !isSimilarText(detectedText, lastRecognizedText)) {
                            lastRecognizedText = detectedText
                            translateAndSpeak(detectedText)
                        }
                        croppedBitmap.recycle()
                        isProcessingFrame = false
                    }
                    .addOnFailureListener {
                        croppedBitmap.recycle()
                        isProcessingFrame = false
                    }
            } catch (e: Exception) {
                image.close()
                isProcessingFrame = false
            }
        }

        // Jednoduchý filtr, aby se stejná věta nečetla znovu kvůli drobné chybě OCR
        private fun isSimilarText(newText: String, oldText: String): Boolean {
            if (oldText.isEmpty()) return false
            val cleanNew = newText.lowercase().filter { it.isLetterOrDigit() }
            val cleanOld = oldText.lowercase().filter { it.isLetterOrDigit() }
            return cleanNew == cleanOld || (cleanNew.length > 10 && cleanOld.contains(cleanNew))
        }

        private fun translateAndSpeak(englishText: String) {
            translator.translate(englishText)
                .addOnSuccessListener { czechText ->
                    overlayView?.text = czechText
                    if (enableTts && isTtsReady) {
                        tts?.speak(czechText, TextToSpeech.QUEUE_FLUSH, null, "utteranceId")
                    }
                }
        }

        private fun createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Překladač obrazovky",
                    NotificationManager.IMPORTANCE_LOW
                )
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            }
        }

        override fun onDestroy() {
            handler.removeCallbacks(scanRunnable)
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            if (overlayView != null) {
                windowManager?.removeView(overlayView)
            }
            tts?.stop()
            tts?.shutdown()
            translator.close()
            super.onDestroy()
        }

        override fun onBind(intent: Intent?): IBinder? = null
    }
}
