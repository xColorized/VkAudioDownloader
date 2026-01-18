package com.example.vkaudiodownloader

import android.content.Context
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val API_VERSION = "5.199"

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()

    data class AudioInfo(
        val m3u8Url: String,
        val artist: String?,
        val title: String?
    )

    data class Segment(val url: String, val key: ByteArray?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val urlInput = findViewById<EditText>(R.id.urlInput)
        val tokenInput = findViewById<EditText>(R.id.tokenInput)
        val saveTokenButton = findViewById<Button>(R.id.saveTokenButton)
        val downloadButton = findViewById<Button>(R.id.downloadButton)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val statusText = findViewById<TextView>(R.id.statusText)

        progressBar.max = 100
        progressBar.progress = 0

        val prefs = getSharedPreferences("vkdata", Context.MODE_PRIVATE)
        tokenInput.setText(prefs.getString("token", ""))

        saveTokenButton.setOnClickListener {
            val token = tokenInput.text.toString().trim()
            if (token.isEmpty()) {
                Toast.makeText(this, "Введите токен", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("token", token).apply()
            Toast.makeText(this, "Токен сохранён", Toast.LENGTH_SHORT).show()
        }

        downloadButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            val token = tokenInput.text.toString().trim()

            if (token.isEmpty()) {
                Toast.makeText(this, "Введите токен VK", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (url.isEmpty()) {
                Toast.makeText(this, "Введите ссылку", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            progressBar.progress = 0
            statusText.text = "Начинаю загрузку..."

            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val file = downloadAudio(url, token) { percent ->
                            runOnUiThread {
                                progressBar.progress = percent
                                statusText.text = "Загрузка: $percent%"
                            }
                        }
                        runOnUiThread {
                            statusText.text = "Готово: ${file.name}"
                            Toast.makeText(
                                this@MainActivity,
                                "Сохранено: ${file.absolutePath}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    statusText.text = "Ошибка: ${e.message}"
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun extractIds(url: String): Triple<String, String, String> {
        val regex = Regex("audio(-?\\d+)_(\\d+)(\\w+)?")
        val match = regex.find(url) ?: throw IllegalArgumentException("Неверная ссылка")
        return Triple(match.groupValues[1], match.groupValues[2], match.groupValues.getOrNull(3) ?: "")
    }

    private fun apiGetAudio(ownerId: String, audioId: String, accessKey: String, token: String): AudioInfo {
        val url = "https://api.vk.com/method/audio.getById" +
                "?audios=${ownerId}_${audioId}_$accessKey" +
                "&access_token=$token&v=$API_VERSION"

        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        val body = resp.body?.string() ?: throw RuntimeException("Пустой ответ")

        val json = JSONObject(body)
        if (json.has("error")) throw RuntimeException(json.getJSONObject("error").getString("error_msg"))

        val info = json.getJSONArray("response").getJSONObject(0)

        return AudioInfo(
            m3u8Url = info.getString("url"),
            artist = info.optString("artist", null),
            title = info.optString("title", null)
        )
    }

    private fun httpGetBytes(url: String): ByteArray {
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        if (!resp.isSuccessful) throw RuntimeException("HTTP error ${resp.code}")
        return resp.body?.bytes() ?: ByteArray(0)
    }

    private fun httpGetText(url: String): String =
        httpGetBytes(url).toString(Charsets.UTF_8)

    private fun parseM3u8(text: String, baseUrl: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        var currentKey: ByteArray? = null

        text.lineSequence().forEach { line ->
            when {
                line.startsWith("#EXT-X-KEY") -> {
                    val method = Regex("METHOD=([^,]+)").find(line)?.groupValues?.get(1) ?: "NONE"
                    val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)

                    currentKey = if (method == "NONE" || uri == null) null
                    else httpGetBytes(if (uri.startsWith("http")) uri else baseUrl + uri)
                }

                line.endsWith(".ts") || line.contains(".ts?") -> {
                    val segUrl = if (line.startsWith("http")) line else baseUrl + line
                    segments.add(Segment(segUrl, currentKey))
                }
            }
        }

        return segments
    }

    private fun decryptTs(data: ByteArray, key: ByteArray?): ByteArray {
        if (key == null) return data
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
        return cipher.doFinal(data)
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/*?:\"<>|]"), "_")

    private fun autoFilename(info: AudioInfo, ownerId: String, audioId: String): String {
        return when {
            !info.artist.isNullOrBlank() && !info.title.isNullOrBlank() ->
                sanitize("${info.artist} — ${info.title}.mp3")

            !info.title.isNullOrBlank() ->
                sanitize("${info.title}.mp3")

            else ->
                "audio_${ownerId}_${audioId}.mp3"
        }
    }

    private fun downloadAudio(vkUrl: String, token: String, onProgress: (Int) -> Unit): File {
        val (ownerId, audioId, accessKey) = extractIds(vkUrl)
        val info = apiGetAudio(ownerId, audioId, accessKey, token)

        val m3u8Text = httpGetText(info.m3u8Url)
        val baseUrl = info.m3u8Url.substringBeforeLast("/") + "/"
        val segments = parseM3u8(m3u8Text, baseUrl)

        val tsFile = File(cacheDir, "temp_audio.ts")
        tsFile.outputStream().use { out ->
            segments.forEachIndexed { index, seg ->
                out.write(decryptTs(httpGetBytes(seg.url), seg.key))
                onProgress(((index + 1) * 100f / segments.size).toInt())
            }
        }

        val filename = autoFilename(info, ownerId, audioId)
        val outFile = File(getExternalFilesDir(null), filename)

        val cmd = arrayOf(
            "-y",
            "-i", tsFile.absolutePath,
            "-acodec", "libmp3lame",
            "-ab", "320k",
            "-metadata", "artist=${info.artist ?: ""}",
            "-metadata", "title=${info.title ?: ""}",
            outFile.absolutePath
        )

        val rc = com.arthenica.mobileffmpeg.FFmpeg.execute(cmd)
        if (rc != 0) throw RuntimeException("FFmpeg error: $rc")

        tsFile.delete()
        return outFile
    }
}