package com.cdz.solver

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private lateinit var webViewMesh: WebView
    private lateinit var webViewAdmin: WebView
    private lateinit var btnToggleAdmin: Button
    private lateinit var btnInjectAnswers: Button
    private lateinit var sbTimer: SeekBar
    private lateinit var tvTimerDisplay: TextView
    private lateinit var tvMeshStatus: TextView

    private lateinit var db: SQLiteDatabase
    private var durationMinutes: Int = 10
    private var inAdminMode: Boolean = false
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initLocalDatabase()

        webViewMesh = findViewById(R.id.webViewMesh)
        webViewAdmin = findViewById(R.id.webViewAdmin)
        btnToggleAdmin = findViewById(R.id.btnToggleAdmin)
        btnInjectAnswers = findViewById(R.id.btnInjectAnswers)
        sbTimer = findViewById(R.id.sbTimer)
        tvTimerDisplay = findViewById(R.id.tvTimerDisplay)
        tvMeshStatus = findViewById(R.id.tvMeshStatus)

        // 1. Настройка браузера МЭШ
        setupWebView(webViewMesh)
        webViewMesh.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                detectCdzTest(url ?: "")
            }
        }
        webViewMesh.loadUrl("https://school.mos.ru")

        // 2. Настройка Админки
        setupWebView(webViewAdmin)
        webViewAdmin.addJavascriptInterface(AdminBridge(), "AndroidDB")
        webViewAdmin.loadUrl("file:///android_asset/index.html")

        // Переключение режимов
        btnToggleAdmin.setOnClickListener {
            inAdminMode = !inAdminMode
            if (inAdminMode) {
                webViewAdmin.visibility = View.VISIBLE
                webViewMesh.visibility = View.GONE
                findViewById<View>(R.id.panelMeshControl).visibility = View.GONE
                btnToggleAdmin.text = "✕"
            } else {
                webViewAdmin.visibility = View.GONE
                webViewMesh.visibility = View.VISIBLE
                findViewById<View>(R.id.panelMeshControl).visibility = View.VISIBLE
                btnToggleAdmin.text = "⚙"
            }
        }

        // Выбор времени
        sbTimer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                durationMinutes = if (progress < 1) 1 else progress
                tvTimerDisplay.text = "$durationMinutes мин"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnInjectAnswers.setOnClickListener {
            runAutoSolver()
        }
    }

    private fun setupWebView(wv: WebView) {
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
    }

    private fun initLocalDatabase() {
        db = openOrCreateDatabase("cdz_store.db", Context.MODE_PRIVATE, null)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS answers_store (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                subject TEXT,
                title TEXT,
                test_id TEXT UNIQUE,
                answers_json TEXT
            )
        """)
    }

    private fun detectCdzTest(url: String) {
        val m = Pattern.compile("(?:test_id=|task/|spec/)(\\d+)").matcher(url)
        if (m.find()) {
            val id = m.group(1)
            tvMeshStatus.text = "Готов к сдаче теста #$id"
            tvMeshStatus.setTextColor(0xFF30D158.toInt())
        } else {
            tvMeshStatus.text = "Откройте страницу с тестом"
            tvMeshStatus.setTextColor(0xFF8E8E93.toInt())
        }
    }

    private fun runAutoSolver() {
        val url = webViewMesh.url ?: ""
        val m = Pattern.compile("(?:test_id=|task/|spec/)(\\d+)").matcher(url)
        if (!m.find()) {
            Toast.makeText(this, "Сначала откройте тест ЦДЗ!", Toast.LENGTH_SHORT).show()
            return
        }

        val testId = m.group(1) ?: return

        // Поиск ответов в локальной базе
        val cursor = db.rawQuery("SELECT answers_json FROM answers_store WHERE test_id = ?", arrayOf(testId))
        if (!cursor.moveToFirst()) {
            cursor.close()
            Toast.makeText(this, "Ответы для теста #$testId не найдены в админке!", Toast.LENGTH_LONG).show()
            return
        }

        val rawAnswers = cursor.getString(0)
        cursor.close()

        btnInjectAnswers.isEnabled = false
        tvMeshStatus.text = "Решается... Ожидание $durationMinutes мин."
        tvMeshStatus.setTextColor(0xFFFFC93C.toInt())

        val delayMillis = durationMinutes * 60 * 1000L
        handler.postDelayed({
            injectScript(rawAnswers)
        }, delayMillis)

        Toast.makeText(this, "Таймер запущен на $durationMinutes мин. Не выходите из теста.", Toast.LENGTH_LONG).show()
    }

    private fun injectScript(rawJsonAnswers: String) {
        val script = """
            (function() {
                try {
                    const answers = $rawJsonAnswers;
                    const tasks = document.querySelectorAll('.test-task, [data-task-id], .task-view, .question-block');
                    let applied = 0;

                    answers.forEach((ans, idx) => {
                        const block = tasks[idx] || document;
                        const cleanAns = ans.trim().toLowerCase();

                        // Поиск радио / чекбоксов
                        const labels = Array.from(block.querySelectorAll('label, .answer-variant, [role="radio"]'));
                        const found = labels.find(l => l.innerText.trim().toLowerCase().includes(cleanAns));
                        if (found) {
                            found.click();
                            applied++;
                            return;
                        }

                        // Поле ввода
                        const inp = block.querySelector('input[type="text"], textarea');
                        if (inp) {
                            inp.value = ans;
                            inp.dispatchEvent(new Event('input', { bubbles: true }));
                            inp.dispatchEvent(new Event('change', { bubbles: true }));
                            applied++;
                        }
                    });

                    // Завершение теста
                    const finishBtn = document.querySelector('button[type="submit"], .finish-button, [data-test-action="finish"]');
                    if (finishBtn) finishBtn.click();

                    return 'Подставлено: ' + applied + '/' + answers.length;
                } catch(e) {
                    return 'Ошибка: ' + e.message;
                }
            })();
        """.trimIndent()

        webViewMesh.evaluateJavascript(script) { res ->
            btnInjectAnswers.isEnabled = true
            tvMeshStatus.text = "Тест успешно сдан!"
            tvMeshStatus.setTextColor(0xFF30D158.toInt())
            Toast.makeText(this, res, Toast.LENGTH_LONG).show()
        }
    }

    // Мост данных между WebView Админки и SQLite
    inner class AdminBridge {
        @JavascriptInterface
        fun saveTest(subject: String, title: String, testId: String, answersJsonStr: String): Boolean {
            return try {
                db.execSQL("""
                    INSERT OR REPLACE INTO answers_store (subject, title, test_id, answers_json)
                    VALUES (?, ?, ?, ?)
                """, arrayOf(subject, title, testId, answersJsonStr))
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun getAllTestsJson(): String {
            val array = JSONArray()
            val c = db.rawQuery("SELECT subject, title, test_id, answers_json FROM answers_store ORDER BY id DESC", null)
            while (c.moveToNext()) {
                val obj = JSONObject()
                obj.put("subject", c.getString(0))
                obj.put("title", c.getString(1))
                obj.put("test_id", c.getString(2))
                obj.put("answers", JSONArray(c.getString(3)))
                array.put(obj)
            }
            c.close()
            return array.toString()
        }
    }

    override fun onBackPressed() {
        if (inAdminMode) {
            btnToggleAdmin.performClick()
        } else if (webViewMesh.canGoBack()) {
            webViewMesh.goBack()
        } else {
            super.onBackPressed()
        }
    }
}