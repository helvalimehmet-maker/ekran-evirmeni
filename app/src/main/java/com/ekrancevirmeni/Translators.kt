package com.ekrancevirmeni

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Ortak çeviri arayüzü. history: son çevrilen satırlar (İngilizce, Türkçe), bağlam için. */
interface TextTranslator {
    suspend fun translate(text: String, history: List<Pair<String, String>>): String
    fun close() {}
}

class TranslateException(message: String, val code: Int = 0) : Exception(message)

/** Google Gemini ile çevrimiçi çeviri (generateContent REST uç noktası). */
class GeminiTranslator(apiKey: String, model: String) : TextTranslator {

    private val key = apiKey.trim()
    val model: String = model.trim().removePrefix("models/").ifBlank { Prefs.DEFAULT_MODEL }

    override suspend fun translate(text: String, history: List<Pair<String, String>>): String =
        withContext(Dispatchers.IO) {
            val body = buildBody(text, history).toString().toByteArray(Charsets.UTF_8)
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("x-goog-api-key", key)
                conn.outputStream.use { it.write(body) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) throw TranslateException(errorMessage(code, resp), code)
                parse(resp)
            } catch (e: IOException) {
                throw TranslateException("İnternet bağlantısı kurulamadı (${e.javaClass.simpleName})")
            } catch (e: JSONException) {
                throw TranslateException("Gemini yanıtı okunamadı")
            } finally {
                conn.disconnect()
            }
        }

    private fun buildBody(text: String, history: List<Pair<String, String>>): JSONObject {
        val sb = StringBuilder()
        if (history.isNotEmpty()) {
            sb.append("Previous subtitles, for context only (do not translate them again):\n")
            for ((en, tr) in history) {
                sb.append("EN: ").append(en).append('\n')
                sb.append("TR: ").append(tr).append('\n')
            }
            sb.append('\n')
        }
        sb.append("Translate this into Turkish:\n").append(text)

        return JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)))
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", sb.toString())))
                )
            )
    }

    private fun parse(resp: String): String {
        val root = JSONObject(resp)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val reason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw TranslateException(if (reason.isNotEmpty()) "Gemini bu metni çevirmedi ($reason)" else "Gemini boş yanıt verdi")
        }
        val cand = candidates.getJSONObject(0)
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.optBoolean("thought", false)) continue
                sb.append(p.optString("text", ""))
            }
        }
        val out = sb.toString().trim()
        if (out.isEmpty()) {
            throw TranslateException("Gemini boş yanıt verdi (${cand.optString("finishReason", "?")})")
        }
        return out
    }

    private fun errorMessage(code: Int, resp: String): String {
        val apiMsg = try {
            JSONObject(resp).optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: JSONException) {
            ""
        }
        return when {
            apiMsg.contains("API key not valid", ignoreCase = true) -> "API anahtarı geçersiz"
            code == 400 -> "İstek hatalı: ${apiMsg.take(120)}"
            code == 401 || code == 403 -> "Erişim reddedildi, API anahtarını kontrol et"
            code == 404 -> "Model bulunamadı: $model (ayarlardan model adını değiştir)"
            code == 429 -> "Kota veya hız sınırı doldu, biraz bekle"
            code >= 500 -> "Gemini sunucusu şu an yanıt vermiyor ($code)"
            else -> "Gemini hatası $code: ${apiMsg.take(120)}"
        }
    }

    companion object {
        private val SYSTEM_PROMPT = """
            You translate on-screen English text into Turkish in real time.
            The text comes from OCR of a selected screen region (video game subtitles, menus, apps), so it may contain recognition errors.
            Rules:
            - Reply with ONLY the Turkish translation. No explanations, quotes or labels.
            - Silently fix obvious OCR mistakes before translating.
            - Keep names of people, places, brands and items unchanged.
            - If a line starts with a speaker name like "Name:", keep the name and translate only the spoken part.
            - Use natural, everyday spoken Turkish. Address people with "sen" unless the context is clearly formal.
            - Keep separate lines on separate lines.
            - If the text is already Turkish or is not real language, return it unchanged.
        """.trimIndent()
    }
}

/** Google ML Kit ile cihaz içinde, internetsiz çeviri (ilk kullanımda yaklaşık 30 MB model indirir). */
class DeviceTranslator : TextTranslator {

    private val client = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
    )

    @Volatile
    private var ready = false

    suspend fun prepare(wifiOnly: Boolean = false) {
        if (ready) return
        val builder = DownloadConditions.Builder()
        if (wifiOnly) builder.requireWifi()
        val conditions = builder.build()
        client.downloadModelIfNeeded(conditions).await()
        ready = true
    }

    override suspend fun translate(text: String, history: List<Pair<String, String>>): String {
        prepare()
        return client.translate(text).await()
    }

    override fun close() {
        client.close()
    }
}
