package uz.hunta.bot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.WindowManager
import android.widget.*

class MainActivity : Activity() {
    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var stats: TextView
    private lateinit var logView: TextView
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            stats.text = "Аккаунт: ${Store.acc()} | подписок сегодня: ${Store.followedToday()} | в списке бота: ${Store.followed().size} | " +
                (if (BotService.instance == null) "спецвозможности ВЫКЛ" else if (BotService.running) "РАБОТАЕТ" else "готов")
            logView.text = BotLog.text()
            h.postDelayed(this, 1000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 28, 28, 28) }

        fun field(key: String, label: String, def: String, multi: Boolean = false, num: Boolean = false) {
            root.addView(TextView(this).apply { text = label; setPadding(0, 16, 0, 0) })
            val e = EditText(this).apply {
                setText(Store.s(key, def))
                if (num) inputType = InputType.TYPE_CLASS_NUMBER
                else if (multi) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3 }
            }
            fields[key] = e; root.addView(e)
        }
        fun btn(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply { text = label; setOnClickListener { onClick() } })
        }

        stats = TextView(this); root.addView(stats)
        btn("1. Включить спецвозможности (HuntaBot)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        field("acc", "Ник ТЕКУЩЕГО аккаунта (для кнопок ниже без переключения)", "acc1")
        field("accounts", "ВСЕ аккаунты (ники по одному в строке) — для режима «ВСЕ АККАУНТЫ», бот сам переключается между ними", "", multi = true)
        field("donors", "Аккаунты-доноры (по одному в строке) — с их подписчиков бот будет подписываться", "", multi = true)
        field("followDay", "Подписок в день", "20", num = true)
        field("unfollowWeek", "Отписок раз в неделю", "50", num = true)
        field("likes", "Лайков за сессию (10 или 20)", "10", num = true)
        field("likesPerProfile", "Лайков на один профиль", "2", num = true)
        field("storiesPerProfile", "Историй смотреть на профиль", "5", num = true)
        field("commentPct", "Шанс комментария после лайка, %", "20", num = true)
        field("maxMin", "Лимит времени сессии активности, мин", "30", num = true)
        field("comments", "Комментарии (по одному в строке, бот выбирает случайный)",
            "Красиво!\nУдивительно!\nВау, очень круто\nОтличная работа\nОчень стильно\nПотрясающе!", multi = true)

        btn("Подписки (до дневного лимита)") { run("follow") }
        btn("Активность: истории + лайки + комменты") { run("engage") }
        btn("Отписка (самых старых)") { run("unfollow") }
        btn("АВТО: подписки → активность → отписка раз в неделю") { run("auto") }
        btn("ВСЕ АККАУНТЫ: по очереди, авто-режим на каждом") { run("all") }
        btn("СТОП") { BotService.instance?.stop() }

        logView = TextView(this).apply { textSize = 12f; setPadding(0, 24, 0, 0) }
        root.addView(logView)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun save() = fields.forEach { (k, e) -> Store.put(k, e.text.toString()) }

    private fun run(mode: String) {
        save()
        val s = BotService.instance
        if (s == null) Toast.makeText(this, "Сначала включи спецвозможности HuntaBot", Toast.LENGTH_LONG).show()
        else { s.start(mode); moveTaskToBack(true) }
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); save(); h.removeCallbacks(tick) }
}
