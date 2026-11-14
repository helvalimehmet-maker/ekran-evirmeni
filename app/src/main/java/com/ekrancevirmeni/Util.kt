package com.ekrancevirmeni

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object DisplayUtil {
    /** Ekranın gerçek piksel boyutu (durum çubuğu ve gezinme çubuğu dahil). */
    fun realSize(context: Context): Point {
        val wm = context.getSystemService(WindowManager::class.java)!!
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }
}

object TextTools {
    /** Karşılaştırma için metni sadeleştirir: küçük harf, yalnızca harf/rakam ve tek boşluk. */
    fun normalizeKey(s: String): String {
        val sb = StringBuilder(s.length)
        var lastSpace = true
        for (ch in s) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch.lowercaseChar())
                lastSpace = false
            } else if (!lastSpace) {
                sb.append(' ')
                lastSpace = true
            }
        }
        return sb.toString().trim()
    }

    /** 0 ile 1 arası benzerlik (1 = aynı). OCR'ın küçük okuma farklarını yok saymak için. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val x = if (a.length > 400) a.substring(0, 400) else a
        val y = if (b.length > 400) b.substring(0, 400) else b
        var prev = IntArray(y.length + 1) { it }
        var cur = IntArray(y.length + 1)
        for (i in 1..x.length) {
            cur[0] = i
            for (j in 1..y.length) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        val dist = prev[y.length]
        return 1.0 - dist.toDouble() / maxOf(x.length, y.length)
    }
}

object OcrText {
    /** ML Kit sonucunu düz metne çevirir; tek harflik gürültüyü (para simgesi, sayaç vb.) atar. */
    fun extract(result: Text): String {
        val blocks = ArrayList<String>()
        for (block in result.textBlocks) {
            val lines = block.lines
                .map { it.text.trim() }
                .filter { line -> line.count { it.isLetter() } >= 2 }
            if (lines.isNotEmpty()) blocks.add(lines.joinToString(" "))
        }
        return blocks.joinToString("\n")
    }
}

/** Google Play Services görevlerini (Task) coroutine içinde beklemek için. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        if (!cont.isActive) return@addOnCompleteListener
        if (task.isCanceled) {
            cont.cancel()
        } else {
            val e = task.exception
            if (e != null) cont.resumeWithException(e) else cont.resume(task.result)
        }
    }
}
