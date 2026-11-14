package com.ekrancevirmeni

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Ayarlar ekranı ve başlat/durdur. Arayüz koddan oluşturulur (XML yok). */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private val scope = MainScope()

    private lateinit var engineGemini: RadioButton
    private lateinit var engineDevice: RadioButton
    private lateinit var apiKeyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var intervalInput: EditText
    private lateinit var sizeInput: EditText
    private lateinit var stableCheck: CheckBox
    private lateinit var originalCheck: CheckBox
    private lateinit var fallbackCheck: CheckBox
    private lateinit var startButton: Button
    private lateinit var statusText: TextView

    private var askedNotification = false

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val intent = Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, intent)
                setStatus("Çalışıyor. Mavi çerçeveyi altyazının üstüne yerleştirip Tamam'a bas.")
                startButton.text = "Durdur"
                moveTaskToBack(true)
            } else {
                setStatus("Ekran yakalama izni verilmedi.")
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            launchProjection()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 30) {
            // Açık zeminde durum çubuğu simgeleri koyu görünsün.
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(light, light)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- akış

    private fun onStartStopClicked() {
        saveSettings()
        if (CaptureService.isRunning) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            startButton.text = "Başlat"
            setStatus("Durduruldu.")
            return
        }
        if (prefs.engine == Prefs.ENGINE_GEMINI && prefs.apiKey.isBlank()) {
            setStatus("Gemini için API anahtarı gir ya da 'Cihaz içi çeviri'yi seç.")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            setStatus("Önce açılan ekranda 'Diğer uygulamaların üzerinde göster' iznini aç, sonra geri dönüp tekrar Başlat'a bas.")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotification &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotification = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchProjection()
    }

    private fun launchProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)!!
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Yalnızca "tüm ekran" seçeneği sunulsun; tek uygulama paylaşımında koordinatlar kayar.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun onTestClicked() {
        saveSettings()
        val sample = "Hey, where do you think you're going? We need to talk."
        scope.launch {
            val translator: TextTranslator = if (prefs.engine == Prefs.ENGINE_DEVICE) {
                setStatus("Cihaz içi çeviri modeli hazırlanıyor (ilk seferde yaklaşık 30 MB indirilir)…")
                DeviceTranslator()
            } else {
                if (prefs.apiKey.isBlank()) {
                    setStatus("Önce API anahtarını gir.")
                    return@launch
                }
                setStatus("Gemini'ye bağlanılıyor…")
                GeminiTranslator(prefs.apiKey, prefs.model)
            }
            try {
                val out = translator.translate(sample, emptyList())
                setStatus("Bağlantı başarılı ✓\n\nÖrnek: $sample\nÇeviri: $out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("Hata: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                translator.close()
            }
        }
    }

    private fun refreshState() {
        startButton.text = if (CaptureService.isRunning) "Durdur" else "Başlat"
        if (!Settings.canDrawOverlays(this)) {
            setStatus("Not: 'Diğer uygulamaların üzerinde göster' izni henüz kapalı. Başlat'a basınca açman istenecek.")
        }
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    private fun saveSettings() {
        if (!::apiKeyInput.isInitialized) return
        prefs.engine = if (engineDevice.isChecked) Prefs.ENGINE_DEVICE else Prefs.ENGINE_GEMINI
        prefs.apiKey = apiKeyInput.text.toString()
        prefs.model = modelInput.text.toString().ifBlank { Prefs.DEFAULT_MODEL }
        intervalInput.text.toString().toIntOrNull()?.let { prefs.intervalMs = it }
        sizeInput.text.toString().toIntOrNull()?.let { prefs.textSizeSp = it }
        prefs.waitStable = stableCheck.isChecked
        prefs.showOriginal = originalCheck.isChecked
        prefs.useFallback = fallbackCheck.isChecked
    }

    // ---------------------------------------------------------------- arayüz

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        root.addView(TextView(this).apply {
            text = "Ekran Çevirmeni"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(note("Ekranda seçtiğin bölgedeki İngilizce yazıları anında Türkçeye çevirir. Oyun, video, uygulama fark etmez."))

        // Çeviri motoru
        root.addView(section("Çeviri motoru"))
        engineGemini = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Gemini yapay zekâ (çevrimiçi, en iyi kalite)"
        }
        engineDevice = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Cihaz içi çeviri (internetsiz, daha basit)"
        }
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(engineGemini)
            addView(engineDevice)
        }
        group.check(if (prefs.engine == Prefs.ENGINE_DEVICE) engineDevice.id else engineGemini.id)
        root.addView(group)

        // Gemini ayarları
        root.addView(section("Gemini API anahtarı"))
        apiKeyInput = EditText(this).apply {
            setSingleLine(true)
            hint = "AIza…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.apiKey)
        }
        root.addView(apiKeyInput)
        root.addView(CheckBox(this).apply {
            text = "Anahtarı göster"
            setOnCheckedChangeListener { _, checked ->
                apiKeyInput.inputType = InputType.TYPE_CLASS_TEXT or
                    if (checked) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
                apiKeyInput.setSelection(apiKeyInput.text.length)
            }
        })
        root.addView(Button(this).apply {
            text = "Ücretsiz anahtar al (Google AI Studio)"
            isAllCaps = false
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey")))
                } catch (_: Exception) {
                    setStatus("Tarayıcıda aç: aistudio.google.com/app/apikey")
                }
            }
        })

        root.addView(section("Model"))
        modelInput = EditText(this).apply {
            setSingleLine(true)
            setText(prefs.model)
        }
        root.addView(modelInput)
        root.addView(note("Varsayılan: ${Prefs.DEFAULT_MODEL} (en hızlısı). Daha güçlü çeviri için örneğin gemini-3.8-flash yazabilirsin; biraz daha yavaştır."))

        // Okuma ayarları
        root.addView(section("Okuma ve görünüm"))
        intervalInput = numberField(prefs.intervalMs)
        root.addView(labeled("Okuma aralığı (milisaniye, ${Prefs.MIN_INTERVAL}–${Prefs.MAX_INTERVAL})", intervalInput))
        sizeInput = numberField(prefs.textSizeSp)
        root.addView(labeled("Altyazı yazı boyutu (12–48)", sizeInput))

        stableCheck = CheckBox(this).apply {
            text = "Metin netleşince çevir (önerilir, gereksiz istekleri azaltır)"
            isChecked = prefs.waitStable
        }
        originalCheck = CheckBox(this).apply {
            text = "İngilizce aslını da küçük yazıyla göster"
            isChecked = prefs.showOriginal
        }
        fallbackCheck = CheckBox(this).apply {
            text = "Gemini çalışmazsa cihaz içi çeviriye geç"
            isChecked = prefs.useFallback
        }
        root.addView(stableCheck)
        root.addView(originalCheck)
        root.addView(fallbackCheck)

        // Düğmeler
        root.addView(Button(this).apply {
            text = "Bağlantıyı test et"
            isAllCaps = false
            setOnClickListener { onTestClicked() }
        }, marginTop(dp(16)))
        startButton = Button(this).apply {
            text = "Başlat"
            textSize = 18f
            isAllCaps = false
            setOnClickListener { onStartStopClicked() }
        }
        root.addView(startButton, marginTop(dp(8)))

        statusText = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(statusText)

        root.addView(section("Nasıl kullanılır?"))
        root.addView(note(
            "1. Gemini API anahtarını gir ve 'Bağlantıyı test et'e bas.\n" +
                "2. Başlat'a bas ve istenen izinleri ver (ekran yakalamada 'Tüm ekran' seçili olmalı).\n" +
                "3. Mavi çerçeveyi sürükleyip altyazının olduğu yere koy, sağ alt köşesinden boyutlandır, Tamam'a bas.\n" +
                "4. Oyuna veya uygulamaya geç. Türkçe çeviri çerçevenin hemen altında (yer yoksa üstünde) görünür.\n\n" +
                "Paneldeki nokta: yeşil = hazır, sarı = çevriliyor, kırmızı = hata, gri = duraklatıldı.\n" +
                "Ayar değişiklikleri bir sonraki Başlat'ta geçerli olur.\n\n" +
                "Gizlilik: Gemini seçiliyken çerçevedeki yazı Google'a gönderilir. Çerçeveyi kişisel bilgilerin üstüne koyma."
        ))

        return scroll
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xFF5F6368.toInt())
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun numberField(value: Int) = EditText(this).apply {
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.toString())
    }

    private fun labeled(label: String, field: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(note(label))
        addView(field)
    }

    private fun marginTop(px: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = px }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
