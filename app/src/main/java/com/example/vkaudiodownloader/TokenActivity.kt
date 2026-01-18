package com.example.vkaudiodownloader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class TokenActivity : AppCompatActivity() {

    private val oauthUrl =
        "https://oauth.vk.com/authorize?client_id=6121396&display=page&redirect_uri=https://oauth.vk.com/blank.html&scope=audio,offline&response_type=token&v=5.199"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val web = WebView(this)
        setContentView(web)

        web.settings.javaScriptEnabled = true

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && url.startsWith("https://oauth.vk.com/blank.html#")) {

                    val token = extractToken(url)
                    if (token != null) {
                        saveToken(token)
                        finish()
                        startActivity(Intent(this@TokenActivity, MainActivity::class.java))
                    }
                }
            }
        }

        web.loadUrl(oauthUrl)
    }

    private fun extractToken(url: String): String? {
        val regex = Regex("access_token=([^&]+)")
        val match = regex.find(url)
        return match?.groupValues?.get(1)
    }

    private fun saveToken(token: String) {
        val prefs = getSharedPreferences("vkdata", Context.MODE_PRIVATE)
        prefs.edit().putString("token", token).apply()
    }
}