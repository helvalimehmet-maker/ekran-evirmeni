package com.ekrancevirmeni

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * Ekrandaki üç pencereyi yönetir:
 *  1) Seçim çerçevesi (okunacak bölge)
 *  2) Altyazı şeridi (Türkçe çeviri, çerçevenin dışında durur ki tekrar okunmasın)
 *  3) Kontrol paneli (Duraklat / Bölge / Kapat)
 */
class OverlayController(
    private val ctx: Context,
    private val prefs: Prefs,
    private val listener: Listener,
) {
    interface Listener {
        fun onPauseToggled(paused: Boolean)
        fun onEditModeChanged(editing: Boolean)
        fun onStopRequested()
    }

    enum class Status { IDLE, WORKING, ERROR, PAUSED }

    private val wm: WindowManager = ctx.getSystemService(WindowManager::class.java)!!
    private val density = ctx.resources.displayMetrics.density

    private val frameView = FrameView(ctx)
    private val frameParams = baseParams(touchable = true)

    private val subtitleRoot = LinearLayout(ctx)
    private val originalText = TextView(ctx)
    private val translationText = TextView(ctx)
    private val noteText = TextView(ctx)
    private val subtitleParams = baseParams(touchable = false)

    private val panel = LinearLayout(ctx)
    private val statusDot = TextView(ctx)
    private val pauseBtn = TextView(ctx)
    private val editBtn = TextView(ctx)
    private val closeBtn = TextView(ctx)
    private val handle = TextView(ctx)
    private val panelParams = baseParams(touchable = true)

    var editing = true
        private set
    var paused = false
        private set
    private var shown = false

    // ---------------------------------------------------------------- kurulum

    fun show() {
        buildSubtitle()
        buildPanel()
        setupFrameTouch()
        computeFrameFromPrefs()
        frameView.editing = true

        wm.addView(frameView, frameParams)
        wm.addView(subtitleRoot, subtitleParams)
        wm.addView(panel, panelParams)
        shown = true

        positionSubtitle()
        showPlaceholder()
    }

    fun remove() {
        if (!shown) return
        shown = false
        for (v in listOf<View>(frameView, subtitleRoot, panel)) {
            try {
                wm.removeView(v)
            } catch (_: Exception) {
            }
        }
    }

    /** Ekran döndüğünde çağrılır. */
    fun onScreenChanged() {
        if (!shown) return
        computeFrameFromPrefs()
        update(frameView, frameParams)
        val s = screen()
        panelParams.x = clamp(panelParams.x, 0, s.x - dp(120))
        panelParams.y = clamp(panelParams.y, 0, s.y - dp(48))
        update(panel, panelParams)
        positionSubtitle()
    }

    /** Okunacak bölgenin ekran koordinatları (kenar çizgileri hariç). Ana iş parçacığında çağrılmalı. */
    fun captureRect(): Rect? {
        if (!shown || frameView.width == 0) return null
        val loc = IntArray(2)
        frameView.getLocationOnScreen(loc)
        val inset = dp(8)
        val r = Rect(
            loc[0] + inset,
            loc[1] + inset,
            loc[0] + frameView.width - inset,
            loc[1] + frameView.height - inset
        )
        return if (r.width() >= 16 && r.height() >= 16) r else null
    }

    // ---------------------------------------------------------------- altyazı

    fun showSubtitle(original: String?, translation: String, note: String?) {
        if (!shown) return
        originalText.text = original ?: ""
        originalText.visibility =
            if (prefs.showOriginal && !original.isNullOrBlank()) View.VISIBLE else View.GONE
        translationText.setTextColor(Color.WHITE)
        translationText.text = translation
        noteText.text = note ?: ""
        noteText.visibility = if (note.isNullOrBlank()) View.GONE else View.VISIBLE
        subtitleRoot.visibility = View.VISIBLE
    }

    fun showError(message: String) {
        if (!shown) return
        originalText.visibility = View.GONE
        noteText.visibility = View.GONE
        translationText.setTextColor(0xFFFFB74D.toInt())
        translationText.text = message
        subtitleRoot.visibility = View.VISIBLE
    }

    fun hideSubtitle() {
        if (!shown) return
        subtitleRoot.visibility = View.GONE
    }

    private fun showPlaceholder() {
        showSubtitle(null, "Çeviri burada görünecek", "Çerçeveyi altyazının üstüne yerleştir, sonra Tamam'a bas")
        translationText.setTextColor(0xFFB0BEC5.toInt())
    }

    fun setStatus(status: Status) {
        val color = when (status) {
            Status.IDLE -> 0xFF66BB6A
            Status.WORKING -> 0xFFFFCA28
            Status.ERROR -> 0xFFEF5350
            Status.PAUSED -> 0xFF9E9E9E
        }
        statusDot.setTextColor(color.toInt())
    }

    // ---------------------------------------------------------------- modlar

    fun setEditing(value: Boolean) {
        editing = value
        frameView.editing = value
        frameParams.flags = if (value) {
            frameParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            frameParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        // Android 12+ dokunmaların alttaki oyuna geçmesi için dokunulmaz pencerenin opaklığı en fazla 0.8 olmalı.
        frameParams.alpha = if (value) 1f else 0.8f
        update(frameView, frameParams)
        editBtn.text = if (value) "Tamam" else "Bölge"
        if (value) showPlaceholder() else hideSubtitle()
        listener.onEditModeChanged(value)
    }

    private fun setPaused(value: Boolean) {
        paused = value
        pauseBtn.text = if (value) "Devam" else "Duraklat"
        if (value) hideSubtitle()
        setStatus(if (value) Status.PAUSED else Status.IDLE)
        listener.onPauseToggled(value)
    }

    // ---------------------------------------------------------------- yerleşim

    private fun computeFrameFromPrefs() {
        val s = screen()
        val w = clamp((prefs.regionWidth * s.x).toInt(), dp(80), s.x)
        val h = clamp((prefs.regionHeight * s.y).toInt(), dp(40), s.y)
        frameParams.width = w
        frameParams.height = h
        frameParams.x = clamp((prefs.regionLeft * s.x).toInt(), 0, s.x - w)
        frameParams.y = clamp((prefs.regionTop * s.y).toInt(), 0, s.y - h)
    }

    private fun saveFrameToPrefs() {
        val s = screen()
        if (s.x <= 0 || s.y <= 0) return
        prefs.regionLeft = frameParams.x.toFloat() / s.x
        prefs.regionTop = frameParams.y.toFloat() / s.y
        prefs.regionWidth = frameParams.width.toFloat() / s.x
        prefs.regionHeight = frameParams.height.toFloat() / s.y
    }

    /** Altyazıyı çerçevenin altına koyar; altta yer yoksa üstüne. */
    private fun positionSubtitle() {
        if (!shown) return
        val s = screen()
        val fx = frameParams.x
        val fy = frameParams.y
        val fw = frameParams.width
        val fh = frameParams.height
        val w = min(max(fw, dp(300)), s.x)
        subtitleParams.width = w
        subtitleParams.x = clamp(fx + (fw - w) / 2, 0, s.x - w)

        val spaceBelow = s.y - (fy + fh)
        if (spaceBelow >= dp(110) || spaceBelow >= fy) {
            subtitleParams.gravity = Gravity.TOP or Gravity.START
            subtitleParams.y = fy + fh + dp(8)
        } else {
            // Pencere yukarı doğru büyüsün diye alttan konumlandırılır.
            subtitleParams.gravity = Gravity.BOTTOM or Gravity.START
            subtitleParams.y = s.y - fy + dp(8)
        }
        update(subtitleRoot, subtitleParams)
    }

    // ---------------------------------------------------------------- görünümler

    private fun buildSubtitle() {
        subtitleRoot.orientation = LinearLayout.VERTICAL
        subtitleRoot.background = GradientDrawable().apply {
            setColor(0xE0101010.toInt())
            cornerRadius = dp(10).toFloat()
        }
        subtitleRoot.setPadding(dp(14), dp(8), dp(14), dp(10))

        val size = prefs.textSizeSp.toFloat()
        originalText.textSize = max(11f, size * 0.65f)
        originalText.setTextColor(0xFFB0BEC5.toInt())

        translationText.textSize = size
        translationText.setTextColor(Color.WHITE)
        translationText.setTypeface(translationText.typeface, Typeface.BOLD)
        translationText.setShadowLayer(3f, 0f, 1f, Color.BLACK)

        noteText.textSize = 12f
        noteText.setTextColor(0xFFFFB74D.toInt())

        subtitleRoot.addView(originalText)
        subtitleRoot.addView(translationText)
        subtitleRoot.addView(noteText)
        subtitleRoot.visibility = View.GONE
    }

    private fun buildPanel() {
        panel.orientation = LinearLayout.HORIZONTAL
        panel.gravity = Gravity.CENTER_VERTICAL
        panel.background = GradientDrawable().apply {
            setColor(0xEE202124.toInt())
            cornerRadius = dp(22).toFloat()
        }
        panel.setPadding(dp(6), dp(2), dp(6), dp(2))

        styleButton(handle, "≡")
        handle.textSize = 22f
        statusDot.text = "●"
        statusDot.textSize = 14f
        statusDot.setPadding(dp(4), 0, dp(6), 0)
        styleButton(pauseBtn, "Duraklat")
        styleButton(editBtn, "Tamam")
        styleButton(closeBtn, "Kapat")
        closeBtn.setTextColor(0xFFFF8A80.toInt())

        panel.addView(handle)
        panel.addView(statusDot)
        panel.addView(pauseBtn)
        panel.addView(editBtn)
        panel.addView(closeBtn)

        pauseBtn.setOnClickListener { setPaused(!paused) }
        editBtn.setOnClickListener { setEditing(!editing) }
        closeBtn.setOnClickListener { listener.onStopRequested() }
        setupPanelDrag()
        setStatus(Status.IDLE)

        val s = screen()
        panelParams.width = WindowManager.LayoutParams.WRAP_CONTENT
        panelParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        panelParams.x = if (prefs.panelX >= 0) clamp(prefs.panelX, 0, s.x - dp(120)) else dp(16)
        panelParams.y = if (prefs.panelY >= 0) clamp(prefs.panelY, 0, s.y - dp(48)) else dp(96)
    }

    private fun styleButton(tv: TextView, label: String) {
        tv.text = label
        tv.textSize = 15f
        tv.setTextColor(Color.WHITE)
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPanelDrag() {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = panelParams.x
                    startY = panelParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val s = screen()
                    panelParams.x = clamp(startX + (e.rawX - downX).toInt(), 0, s.x - panel.width)
                    panelParams.y = clamp(startY + (e.rawY - downY).toInt(), 0, s.y - panel.height)
                    update(panel, panelParams)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.panelX = panelParams.x
                    prefs.panelY = panelParams.y
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFrameTouch() {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var startW = 0
        var startH = 0
        var resizing = false
        frameView.setOnTouchListener { _, e ->
            if (!editing) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = frameParams.x
                    startY = frameParams.y
                    startW = frameParams.width
                    startH = frameParams.height
                    resizing = e.x > frameView.width - dp(56) && e.y > frameView.height - dp(56)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    val s = screen()
                    if (resizing) {
                        frameParams.width = clamp(startW + dx, dp(80), s.x - frameParams.x)
                        frameParams.height = clamp(startH + dy, dp(40), s.y - frameParams.y)
                    } else {
                        frameParams.x = clamp(startX + dx, 0, s.x - frameParams.width)
                        frameParams.y = clamp(startY + dy, 0, s.y - frameParams.height)
                    }
                    update(frameView, frameParams)
                    positionSubtitle()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    saveFrameToPrefs()
                    true
                }
                else -> false
            }
        }
    }

    // ---------------------------------------------------------------- yardımcılar

    private fun baseParams(touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (!touchable) alpha = 0.8f
        }
    }

    private fun update(v: View, p: WindowManager.LayoutParams) {
        if (!shown) return
        try {
            wm.updateViewLayout(v, p)
        } catch (_: Exception) {
        }
    }

    private fun screen(): Point = DisplayUtil.realSize(ctx)

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun clamp(v: Int, lo: Int, hi: Int): Int = if (hi < lo) lo else v.coerceIn(lo, hi)
}

/** Seçim çerçevesini çizen görünüm. Düzenleme modunda dolu ve kesikli, çalışırken yalnızca köşeler. */
class FrameView(context: Context) : View(context) {

    var editing = true
        set(value) {
            field = value
            invalidate()
        }

    private val d = context.resources.displayMetrics.density

    private val fill = Paint().apply {
        color = 0x332196F3
        style = Paint.Style.FILL
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2196F3.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2 * d
        pathEffect = DashPathEffect(floatArrayOf(10 * d, 6 * d), 0f)
    }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3 * d
        strokeCap = Paint.Cap.ROUND
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2196F3.toInt()
        style = Paint.Style.FILL
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14 * d
        textAlign = Paint.Align.CENTER
        setShadowLayer(3 * d, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (editing) {
            canvas.drawRect(0f, 0f, w, h, fill)
            val i = border.strokeWidth / 2
            canvas.drawRect(i, i, w - i, h - i, border)
            canvas.drawCircle(w - 16 * d, h - 16 * d, 11 * d, knob)
            canvas.drawText("Sürükle • sağ alt köşeden boyutlandır", w / 2, h / 2 + 5 * d, label)
        } else {
            val len = min(18 * d, min(w, h) / 3)
            val s = corner.strokeWidth / 2
            canvas.drawLine(s, s, s + len, s, corner)
            canvas.drawLine(s, s, s, s + len, corner)
            canvas.drawLine(w - s, s, w - s - len, s, corner)
            canvas.drawLine(w - s, s, w - s, s + len, corner)
            canvas.drawLine(s, h - s, s + len, h - s, corner)
            canvas.drawLine(s, h - s, s, h - s - len, corner)
            canvas.drawLine(w - s, h - s, w - s - len, h - s, corner)
            canvas.drawLine(w - s, h - s, w - s, h - s - len, corner)
        }
    }
}
