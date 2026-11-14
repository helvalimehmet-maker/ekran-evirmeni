package com.ekrancevirmeni

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ön planda çalışan hizmet:
 * ekranı yakalar → seçili bölgeyi keser → ML Kit ile yazıyı okur → Gemini ile çevirir → altyazıyı gösterir.
 */
class CaptureService : Service(), OverlayController.Listener {

    companion object {
        const val ACTION_START = "com.ekrancevirmeni.START"
        const val ACTION_STOP = "com.ekrancevirmeni.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "ceviri_hizmeti"
        private const val NOTIF_ID = 42
        private const val TAG = "EkranCevirmeni"

        @Volatile
        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: Prefs

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile
    private var imageReader: ImageReader? = null
    private var captureW = 0
    private var captureH = 0
    private var captureThread: HandlerThread? = null
    private var uiContext: Context? = null
    private var overlay: OverlayController? = null

    private var recognizer: TextRecognizer? = null
    private var translator: TextTranslator? = null
    private var fallback: DeviceTranslator? = null

    private var loopJob: Job? = null
    private var translateJob: Job? = null
    private var stopped = false

    @Volatile
    private var paused = false
    @Volatile
    private var editing = true

    // Metin takibi
    private var requestedKey = ""   // en son çevirisi istenen metin
    private var pendingKey = ""     // bir kez görülen, sabitlenmesi beklenen metin
    private var pendingText = ""
    private var emptyReads = 0
    private var cooldownUntil = 0L
    private var geminiDownUntil = 0L
    private val cache = LruCache<String, String>(300)
    private val recent = ArrayDeque<Pair<String, String>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                // Android 14+: ekran yakalama başlamadan önce ön plan hizmeti başlatılmış olmalı.
                try {
                    startInForeground()
                } catch (e: Exception) {
                    Log.e(TAG, "Ön plan hizmeti başlatılamadı", e)
                    Toast.makeText(this, "Hizmet başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
                    stopEverything()
                    return START_NOT_STICKY
                }
                if (projection != null) return START_NOT_STICKY
                if (resultCode != Activity.RESULT_OK || data == null) {
                    stopEverything()
                    return START_NOT_STICKY
                }
                try {
                    startCapture(resultCode, data)
                } catch (e: Exception) {
                    Log.e(TAG, "Başlatılamadı", e)
                    Toast.makeText(this, "Ekran yakalama başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
                    stopEverything()
                }
            }
            ACTION_STOP -> stopEverything()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- başlatma

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)!!
        val channel = NotificationChannel(CHANNEL_ID, "Çeviri hizmeti", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Ekran çevirisi çalışırken görünen bildirim"
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ceviri)
            .setContentTitle("Ekran Çevirmeni çalışıyor")
            .setContentText("Seçili bölgedeki İngilizce yazılar Türkçeye çevriliyor")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Durdur", stopIntent)
            .build()
    }

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)!!
        val mp = mpm.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("İzin alınamadı")
        projection = mp

        val thread = HandlerThread("ekran-yakalama").also { it.start() }
        captureThread = thread
        val handler = Handler(thread.looper)

        // Android 14+: geri çağırma, sanal ekran oluşturulmadan önce kaydedilmeli.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                scope.launch { stopEverything() }
            }
        }, handler)

        val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val ui: Context = if (Build.VERSION.SDK_INT >= 30 && display != null) {
            createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            this
        }
        uiContext = ui

        val size = DisplayUtil.realSize(ui)
        captureW = size.x
        captureH = size.y
        val reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = mp.createVirtualDisplay(
            "EkranCevirmeni",
            captureW, captureH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        translator = buildTranslator()

        overlay = OverlayController(ui, prefs, this).also { it.show() }
        editing = true
        paused = false
        isRunning = true

        prepareOfflineModel()
        loopJob = scope.launch { captureLoop() }
    }

    private fun buildTranslator(): TextTranslator {
        return if (prefs.engine == Prefs.ENGINE_DEVICE || prefs.apiKey.isBlank()) {
            deviceTranslator()
        } else {
            GeminiTranslator(prefs.apiKey, prefs.model)
        }
    }

    private fun deviceTranslator(): DeviceTranslator =
        fallback ?: DeviceTranslator().also { fallback = it }

    /** Cihaz içi çeviri modelini önceden indir (yalnızca Wi-Fi'de, Gemini kullanılıyorsa yedek olarak). */
    private fun prepareOfflineModel() {
        val main = translator
        scope.launch {
            try {
                if (main is DeviceTranslator) {
                    overlay?.setStatus(OverlayController.Status.WORKING)
                    main.prepare()
                    overlay?.setStatus(OverlayController.Status.IDLE)
                } else if (prefs.useFallback) {
                    deviceTranslator().prepare(wifiOnly = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Çevrimdışı model indirilemedi", e)
                if (main is DeviceTranslator) {
                    overlay?.showError("Çeviri modeli indirilemedi. İnternet bağlantını kontrol et.")
                    overlay?.setStatus(OverlayController.Status.ERROR)
                }
            }
        }
    }

    // ---------------------------------------------------------------- döngü

    private suspend fun captureLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                if (paused || editing) {
                    withContext(Dispatchers.Default) { drainFrames() }
                } else {
                    reconfigureIfNeeded()
                    val rect = overlay?.captureRect()
                    if (rect != null) {
                        val crop = withContext(Dispatchers.Default) { grabCrop(rect) }
                        if (crop != null) {
                            val text = recognize(crop)
                            crop.recycle()
                            handleText(text)
                        } else if (pendingKey.isNotEmpty()) {
                            // Ekran değişmedi: bekleyen metin artık sabit sayılır.
                            requestTranslation(pendingText, pendingKey)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Döngü hatası", e)
            }
            delay(prefs.intervalMs.toLong())
        }
    }

    /** Duraklatılmışken eski kareleri at ki devam edince bayat görüntü okunmasın. */
    private fun drainFrames() {
        try {
            imageReader?.acquireLatestImage()?.close()
        } catch (_: Exception) {
        }
    }

    /** Ekran döndüyse yakalama boyutunu yeni ekrana uydur. */
    private fun reconfigureIfNeeded() {
        val ui = uiContext ?: return
        val vd = virtualDisplay ?: return
        val s = DisplayUtil.realSize(ui)
        if (s.x == captureW && s.y == captureH) return
        val newReader = ImageReader.newInstance(s.x, s.y, PixelFormat.RGBA_8888, 2)
        vd.resize(s.x, s.y, resources.displayMetrics.densityDpi)
        vd.surface = newReader.surface
        val old = imageReader
        imageReader = newReader
        captureW = s.x
        captureH = s.y
        try {
            old?.close()
        } catch (_: Exception) {
        }
    }

    /** Yeni bir kare varsa yalnızca seçili bölgeyi Bitmap olarak döndürür; ekran değişmediyse null. */
    private fun grabCrop(rect: Rect): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return null
        try {
            val r = Rect(rect)
            if (!r.intersect(0, 0, image.width, image.height)) return null
            val w = r.width()
            val h = r.height()
            if (w < 8 || h < 8) return null

            val plane = image.planes[0]
            val buf = plane.buffer
            val ps = plane.pixelStride
            val rs = plane.rowStride
            val row = ByteArray(w * ps)
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                buf.position((r.top + y) * rs + r.left * ps)
                buf.get(row, 0, row.size)
                var i = 0
                val base = y * w
                for (x in 0 until w) {
                    val red = row[i].toInt() and 0xFF
                    val green = row[i + 1].toInt() and 0xFF
                    val blue = row[i + 2].toInt() and 0xFF
                    pixels[base + x] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                    i += ps
                }
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } finally {
            image.close()
        }
    }

    private suspend fun recognize(bmp: Bitmap): String {
        val rec = recognizer ?: return ""
        val result = rec.process(InputImage.fromBitmap(bmp, 0)).await()
        return OcrText.extract(result)
    }

    private fun handleText(raw: String) {
        val key = TextTools.normalizeKey(raw)
        if (key.length < 2) {
            pendingKey = ""
            pendingText = ""
            emptyReads++
            // İki okuma boyunca yazı yoksa altyazıyı kaldır.
            if (emptyReads >= 2 && requestedKey.isNotEmpty()) {
                translateJob?.cancel()
                requestedKey = ""
                overlay?.hideSubtitle()
                overlay?.setStatus(OverlayController.Status.IDLE)
            }
            return
        }
        emptyReads = 0
        if (requestedKey.isNotEmpty() && TextTools.similarity(key, requestedKey) >= 0.88) return
        if (prefs.waitStable && TextTools.similarity(key, pendingKey) < 0.95) {
            pendingKey = key
            pendingText = raw
            return
        }
        requestTranslation(raw, key)
    }

    private fun requestTranslation(text: String, key: String) {
        if (SystemClock.elapsedRealtime() < cooldownUntil) return
        pendingKey = ""
        pendingText = ""
        requestedKey = key

        val cached = cache.get(key)
        if (cached != null) {
            overlay?.showSubtitle(text, cached, null)
            overlay?.setStatus(OverlayController.Status.IDLE)
            return
        }

        translateJob?.cancel()
        val history = recent.toList()
        overlay?.setStatus(OverlayController.Status.WORKING)
        translateJob = scope.launch {
            try {
                val (out, note) = translateWithFallback(text, history)
                if (note == null) cache.put(key, out)
                recent.addLast(text to out)
                while (recent.size > 4) recent.removeFirst()
                overlay?.showSubtitle(text, out, note)
                overlay?.setStatus(OverlayController.Status.IDLE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Çeviri hatası", e)
                requestedKey = ""
                val wait = if (e is TranslateException && e.code == 429) 15_000L else 5_000L
                cooldownUntil = SystemClock.elapsedRealtime() + wait
                overlay?.showError("Çeviri hatası: ${e.message ?: e.javaClass.simpleName}")
                overlay?.setStatus(OverlayController.Status.ERROR)
            }
        }
    }

    /** Gemini'yi dener; hata olursa (ayar açıksa) cihaz içi çeviriye geçer. Dönen ikinci değer: kullanıcıya not. */
    private suspend fun translateWithFallback(
        text: String,
        history: List<Pair<String, String>>,
    ): Pair<String, String?> {
        val main = translator ?: throw TranslateException("Çevirmen hazır değil")
        val canFallback = main is GeminiTranslator && prefs.useFallback
        if (canFallback && SystemClock.elapsedRealtime() < geminiDownUntil) {
            return deviceTranslator().translate(text, history) to "Cihaz içi çeviri (Gemini kısa süre kullanılamıyor)"
        }
        return try {
            main.translate(text, history) to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!canFallback) throw e
            geminiDownUntil = SystemClock.elapsedRealtime() + 30_000L
            val out = try {
                deviceTranslator().translate(text, history)
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                throw e
            }
            out to "Gemini: ${e.message} — cihaz içi çeviri kullanıldı"
        }
    }

    private fun resetTracking() {
        translateJob?.cancel()
        requestedKey = ""
        pendingKey = ""
        pendingText = ""
        emptyReads = 0
    }

    // ---------------------------------------------------------------- panel düğmeleri

    override fun onPauseToggled(paused: Boolean) {
        this.paused = paused
        resetTracking()
    }

    override fun onEditModeChanged(editing: Boolean) {
        this.editing = editing
        resetTracking()
    }

    override fun onStopRequested() {
        stopEverything()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onScreenChanged()
    }

    // ---------------------------------------------------------------- kapatma

    private fun stopEverything() {
        if (stopped) return
        stopped = true
        isRunning = false
        loopJob?.cancel()
        translateJob?.cancel()
        overlay?.remove()
        overlay = null
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        recognizer = null
        translator?.close()
        if (fallback !== translator) fallback?.close()
        translator = null
        fallback = null
        captureThread?.quitSafely()
        captureThread = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        scope.cancel()
        super.onDestroy()
    }
}
