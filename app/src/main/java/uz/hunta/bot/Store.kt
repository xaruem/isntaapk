package uz.hunta.bot

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Настройки и состояние (по каждому аккаунту отдельно). */
object Store {
    private lateinit var p: SharedPreferences

    fun init(c: Context) { p = c.getSharedPreferences("hbot", Context.MODE_PRIVATE) }

    fun s(k: String, d: String = ""): String = p.getString(k, d) ?: d
    fun i(k: String, d: Int): Int = s(k, d.toString()).trim().toIntOrNull() ?: d
    fun put(k: String, v: String) { p.edit().putString(k, v).apply() }

    /** Аккаунт, с которым бот работает прямо сейчас (в режиме «ВСЕ АККАУНТЫ»). */
    @Volatile var cur: String? = null

    fun acc(): String = cur ?: s("acc", "acc1").trim().ifEmpty { "acc1" }

    fun accounts(): List<String> =
        s("accounts").lines().map { it.trim().removePrefix("@").lowercase() }.filter { it.isNotEmpty() }.distinct()

    // список «на кого подписался бот»: ник|время
    fun followed(): MutableList<Pair<String, Long>> =
        s("fl_${acc()}").lines().mapNotNull {
            val a = it.split("|")
            if (a.size == 2) a[0] to (a[1].toLongOrNull() ?: 0L) else null
        }.toMutableList()

    private fun saveFollowed(l: List<Pair<String, Long>>) =
        put("fl_${acc()}", l.joinToString("\n") { "${it.first}|${it.second}" })

    fun addFollowed(u: String) {
        val l = followed(); l.add(u to System.currentTimeMillis()); saveFollowed(l)
        put("fd_${acc()}", "${today()}|${followedToday() + 1}")
    }

    fun removeFollowed(u: String) = saveFollowed(followed().filter { it.first != u })

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun followedToday(): Int {
        val v = s("fd_${acc()}").split("|")
        return if (v.size == 2 && v[0] == today()) v[1].toIntOrNull() ?: 0 else 0
    }

    fun lastUnfollow(): Long = s("lu_${acc()}", "0").toLongOrNull() ?: 0L
    fun markUnfollow() = put("lu_${acc()}", System.currentTimeMillis().toString())
}

object BotLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized fun add(s: String) {
        lines.addLast("${fmt.format(Date())}  $s")
        while (lines.size > 80) lines.removeFirst()
    }
    @Synchronized fun text(): String = lines.reversed().joinToString("\n")
}
