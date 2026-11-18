package com.example.olxautosender

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.JSONArray
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var etSearchQuery: EditText
    private lateinit var etMessageText: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

    private var isRunning = false
    private var botJob: Job? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etSearchQuery = findViewById(R.id.etSearchQuery)
        etMessageText = findViewById(R.id.etMessageText)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        webView = findViewById(R.id.webView)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
        }

        webView.webViewClient = WebViewClient()
        webView.loadUrl("https://www.olx.kz/myaccount/")

        btnStart.setOnClickListener { startBot() }
        btnStop.setOnClickListener { stopBot() }
    }

    private fun startBot() {
        val query = etSearchQuery.text.toString().trim()
        val message = etMessageText.text.toString().trim()

        if (query.isEmpty() || message.isEmpty()) {
            updateStatus("Ошибка: заполните запрос и текст!")
            return
        }

        isRunning = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true

        botJob = mainScope.launch {
            var currentPage = 1
            val encodedQuery = URLEncoder.encode(query, "UTF-8")

            while (isRunning) {
                val searchUrl = "https://www.olx.kz/list/q-$encodedQuery/?page=$currentPage"
                updateStatus("Стр. $currentPage: загрузка списка...")
                webView.loadUrl(searchUrl)
                delay(6000)

                evalJs("window.scrollTo(0, document.body.scrollHeight / 2);")
                delay(1500)
                evalJs("window.scrollTo(0, document.body.scrollHeight);")
                delay(2000)

                val linksJson = evalJs("""
                    (function() {
                        const anchors = Array.from(document.querySelectorAll('a[href*="/d/obyavlenie/"]'));
                        const urls = anchors.map(a => a.href.split('#')[0].split('?')[0]);
                        return JSON.stringify([...new Set(urls)]);
                    })();
                """.trimIndent())

                val links = parseJsonArray(linksJson)
                if (links.isEmpty()) {
                    updateStatus("Объявления закончились. Стоп.")
                    stopBot()
                    break
                }

                for ((index, adUrl) in links.withIndex()) {
                    if (!isRunning) break

                    if (isAlreadyContacted(adUrl)) {
                        updateStatus("Пропуск повтора (${index + 1}/${links.size})")
                        delay(400)
                        continue
                    }

                    updateStatus("Анкета ${index + 1}/${links.size}...")
                    webView.loadUrl(adUrl)
                    delay(5500)

                    val sellerId = evalJs("""
                        (function() {
                            const el = document.querySelector('[data-testid="user-profile-link"], h4');
                            return el ? el.innerText.trim() : "";
                        })();
                    """.trimIndent()).replace("\"", "")

                    if (sellerId.isNotEmpty() && isAlreadyContacted("SELLER_$sellerId")) {
                        updateStatus("Пропуск: '$sellerId' уже писали.")
                        markAsContacted(adUrl)
                        delay(800)
                        continue
                    }

                    evalJs("""
                        (function() {
                            const btns = Array.from(document.querySelectorAll('button, a'));
                            const msgBtn = btns.find(b => 
                                b.innerText.toLowerCase().includes('сообщение') || 
                                b.innerText.toLowerCase().includes('написать')
                            );
                            if (msgBtn) msgBtn.click();
                        })();
                    """.trimIndent())
                    delay(3500)

                    val safeMsg = message.replace("\"", "\\\"").replace("\n", "\\n")
                    val sendResult = evalJs("""
                        (function() {
                            const textarea = document.querySelector('textarea[name="message"], textarea');
                            if (!textarea) return "NO_CHAT";

                            const nativeSetter = Object.getOwnPropertyDescriptor(
                                window.HTMLTextAreaElement.prototype, "value"
                            ).set;
                            nativeSetter.call(textarea, "$safeMsg");
                            textarea.dispatchEvent(new Event('input', { bubbles: true }));
                            textarea.dispatchEvent(new Event('change', { bubbles: true }));

                            const submitBtn = document.querySelector('button[type="submit"]') || 
                                Array.from(document.querySelectorAll('button')).find(b => 
                                    b.innerText.toLowerCase().includes('отправить')
                                );

                            if (submitBtn && !submitBtn.disabled) {
                                submitBtn.click();
                                return "SENT";
                            }
                            return "NO_BUTTON";
                        })();
                    """.trimIndent())

                    if (sendResult.contains("SENT")) {
                        markAsContacted(adUrl)
                        if (sellerId.isNotEmpty()) markAsContacted("SELLER_$sellerId")

                        val waitSec = Random.nextInt(15, 29)
                        for (sec in waitSec downTo 1) {
                            if (!isRunning) break
                            updateStatus("Отправлено! Пауза: $sec сек...")
                            delay(1000)
                        }
                    } else {
                        updateStatus("Сообщения закрыты, идём дальше...")
                        markAsContacted(adUrl)
                        delay(2000)
                    }
                }
                currentPage++
            }
        }
    }

    private fun stopBot() {
        isRunning = false
        botJob?.cancel()
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        updateStatus("Статус: Остановлено")
    }

    private fun updateStatus(text: String) {
        tvStatus.text = text
    }

    private suspend fun evalJs(script: String): String = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(script) { result ->
            if (cont.isActive) cont.resume(result ?: "")
        }
    }

    private fun parseJsonArray(rawResult: String): List<String> {
        val list = mutableListOf<String>()
        try {
            val cleaned = rawResult
                .removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            val jsonArray = JSONArray(cleaned)
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getString(i))
            }
        } catch (_: Exception) {}
        return list
    }

    private fun isAlreadyContacted(key: String): Boolean {
        val prefs = getSharedPreferences("olx_sent_db", Context.MODE_PRIVATE)
        return prefs.getBoolean(key, false)
    }

    private fun markAsContacted(key: String) {
        val prefs = getSharedPreferences("olx_sent_db", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(key, true).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
    }
}
