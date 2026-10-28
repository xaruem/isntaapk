package uz.hunta.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.random.Random

class BotService : AccessibilityService() {

    companion object {
        @Volatile var instance: BotService? = null
        @Volatile var running = false
        private const val IG = "com.instagram.android"
        private const val DAY = 86_400_000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    private var wl: PowerManager.WakeLock? = null

    override fun onServiceConnected() { instance = this; BotLog.add("Сервис подключён") }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; scope.cancel(); super.onDestroy() }

    // ───────────── запуск / остановка ─────────────
    fun start(mode: String) {
        if (job?.isActive == true) { BotLog.add("Уже работает"); return }
        job = scope.launch {
            running = true; acquire()
            try {
                when (mode) {
                    "follow" -> follow()
                    "engage" -> engage()
                    "unfollow" -> unfollow()
                    "all" -> runAll()
                    "auto" -> {
                        follow(); engage()
                        if (System.currentTimeMillis() - Store.lastUnfollow() > 7 * DAY) unfollow()
                    }
                }
                BotLog.add("Готово")
            } catch (e: CancellationException) {
                BotLog.add("Остановлено")
            } catch (e: Exception) {
                BotLog.add("Ошибка: ${e.message}")
            } finally { running = false; Store.cur = null; release() }
        }
    }

    fun stop() { job?.cancel() }

    @Suppress("DEPRECATION")
    private fun acquire() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "hbot:run")
        wl?.acquire(12 * 3600_000L)
    }
    private fun release() { try { if (wl?.isHeld == true) wl?.release() } catch (_: Exception) {} }

    // ───────────── ВСЕ АККАУНТЫ ПО ОЧЕРЕДИ ─────────────
    private suspend fun runAll() {
        val list = Store.accounts()
        if (list.isEmpty()) { BotLog.add("Впиши ники в поле «ВСЕ аккаунты»"); return }
        for ((idx, a) in list.withIndex()) {
            Store.cur = a
            BotLog.add("══ @$a (${idx + 1}/${list.size})")
            if (!switchAccount(a)) { BotLog.add("Не смог переключиться на @$a — пропуск"); continue }
            try {
                follow(); engage()
                if (System.currentTimeMillis() - Store.lastUnfollow() > 7 * DAY) unfollow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BotLog.add("@$a: ${e.message} — перехожу к следующему")
            }
            delay(rnd(120_000, 300_000))   // пауза между аккаунтами 2–5 мин
        }
    }

    private fun profileTab() = first {
        val d = desc(it)
        d.equals("Profile", true) || (d.startsWith("Profile", true) && !d.contains("picture", true) && it.isClickable)
    }

    private suspend fun switchAccount(u: String): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(IG) ?: return false
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(rnd(3500, 5500))

        var tab = profileTab(); var n = 0
        while (tab == null && n < 5) { performGlobalAction(GLOBAL_ACTION_BACK); delay(1200); tab = profileTab(); n++ }
        if (tab == null) { BotLog.add("  не вижу вкладку Profile"); return false }

        click(tab); delay(rnd(2000, 3000))
        if (first { txt(it).equals(u, true) } != null) { BotLog.add("  уже в @$u"); return true }

        // долгое нажатие на вкладку Profile -> список аккаунтов
        val r = Rect(); (profileTab() ?: tab).getBoundsInScreen(r)
        gesture(Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 900)
        delay(rnd(1800, 2800))
        val row = first { txt(it).equals(u, true) }
        if (row == null) { BotLog.add("  @$u нет в списке аккаунтов"); performGlobalAction(GLOBAL_ACTION_BACK); return false }
        click(row); delay(rnd(4000, 6000))

        profileTab()?.let { click(it) }; delay(rnd(2000, 3000))
        val ok = first { txt(it).equals(u, true) } != null
        BotLog.add(if (ok) "  переключился на @$u" else "  не подтвердил вход в @$u")
        return ok
    }

    // ───────────── ПОДПИСКИ ─────────────
    private suspend fun follow() {
        val limit = Store.i("followDay", 20)
        val donors = Store.s("donors").lines().map { it.trim().removePrefix("@") }.filter { it.isNotEmpty() }
        if (donors.isEmpty()) { BotLog.add("Укажи аккаунты-доноры"); return }
        var done = Store.followedToday()
        BotLog.add("Подписки: сегодня $done из $limit")
        val known = Store.followed().map { it.first }.toMutableSet()

        for (d in donors.shuffled()) {
            if (done >= limit) break
            BotLog.add("Донор @$d")
            openProfile(d)
            val f = first { txt(it).contains("followers", true) || desc(it).contains("followers", true) }
            if (f == null) { BotLog.add("  не нашёл «followers»"); continue }
            click(f); delay(rnd(2500, 4000))

            var idle = 0
            while (done < limit && idle < 6) {
                checkBlock()
                var acted = false
                for (b in all { txt(it) == "Follow" }) {
                    if (done >= limit) break
                    val u = usernameNear(b)
                    if (u == null || u in known) continue
                    if (click(b)) {
                        done++; acted = true; known.add(u); Store.addFollowed(u)
                        BotLog.add("  подписка @$u ($done/$limit)")
                        delay(rnd(25_000, 70_000))
                    }
                }
                idle = if (acted) 0 else idle + 1
                swipeUp(); delay(rnd(1500, 3000))
            }
        }
    }

    // ───────────── АКТИВНОСТЬ (только на тех, на кого подписан бот) ─────────────
    private suspend fun engage() {
        val list = Store.followed().map { it.first }.shuffled()
        if (list.isEmpty()) { BotLog.add("Список подписок пуст — сначала сделай подписки"); return }
        val likeLimit = Store.i("likes", 10)
        val perProfile = Store.i("likesPerProfile", 2).coerceAtLeast(1)
        val stories = Store.i("storiesPerProfile", 5)
        val cPct = Store.i("commentPct", 20)
        val comments = Store.s("comments").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val deadline = System.currentTimeMillis() + Store.i("maxMin", 30) * 60_000L
        var liked = 0

        for (u in list) {
            if (liked >= likeLimit || System.currentTimeMillis() > deadline) break
            BotLog.add("@$u")
            openProfile(u)
            checkBlock()
            if (byText("Following") == null) { BotLog.add("  не в подписках — пропуск"); continue }
            watchStories(stories)
            ensureProfile()
            liked += likePosts(minOf(perProfile, likeLimit - liked), cPct, comments)
            ensureProfile()
            delay(rnd(8_000, 20_000))
        }
        BotLog.add("Лайков за сессию: $liked")
    }

    private suspend fun watchStories(n: Int) {
        if (n <= 0) return
        val av = first { desc(it).contains("story", true) }
        if (av == null) { BotLog.add("  историй нет"); return }
        click(av); delay(rnd(2000, 3500))
        var seen = 0
        repeat(n) {
            delay(rnd(2500, 6500)); seen++
            if (byText("Following") != null) return@repeat   // уже вышли в профиль
            tapXY(0.92f, 0.5f)
        }
        BotLog.add("  истории: $seen")
    }

    private suspend fun likePosts(n: Int, cPct: Int, comments: List<String>): Int {
        if (n <= 0) return 0
        val post = first { desc(it).contains("Row 1, Column 1", true) }
            ?: first { desc(it).contains("Column 1", true) }
        if (post == null) { BotLog.add("  постов нет"); return 0 }
        click(post); delay(rnd(2500, 4000))
        var done = 0; var tries = 0
        while (done < n && tries < n + 3) {
            tries++
            checkBlock()
            val like = first { desc(it).equals("Like", true) }
            if (like != null && click(like)) {
                done++; BotLog.add("  лайк")
                delay(rnd(1500, 3500))
                if (comments.isNotEmpty() && Random.nextInt(100) < cPct) comment(comments.random())
            }
            swipeUp(); delay(rnd(2000, 4500))
        }
        performGlobalAction(GLOBAL_ACTION_BACK); delay(1500)
        return done
    }

    private suspend fun comment(base: String) {
        val emoji = listOf("🔥", "✨", "👏", "😍", "💯", "")
        val text = (base + " " + emoji.random()).trim()
        val c = first { desc(it).equals("Comment", true) } ?: return
        click(c); delay(rnd(2000, 3500))
        val field = first { it.className == "android.widget.EditText" }
        if (field == null) { performGlobalAction(GLOBAL_ACTION_BACK); return }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(rnd(1500, 3000))
        val send = first { txt(it).equals("Post", true) || desc(it).equals("Post", true) }
        if (send != null && click(send)) BotLog.add("  комментарий: $text")
        delay(rnd(1500, 2500))
        performGlobalAction(GLOBAL_ACTION_BACK); delay(1000)
        if (first { it.className == "android.widget.EditText" } != null) {
            performGlobalAction(GLOBAL_ACTION_BACK); delay(1000)
        }
    }

    // ───────────── ОТПИСКА (самых старых, не младше 2 дней) ─────────────
    private suspend fun unfollow() {
        val n = Store.i("unfollowWeek", 50)
        val now = System.currentTimeMillis()
        val targets = Store.followed().sortedBy { it.second }.filter { now - it.second > 2 * DAY }.take(n)
        if (targets.isEmpty()) { BotLog.add("Некого отписывать"); return }
        BotLog.add("Отписка: ${targets.size} шт.")
        var done = 0
        for ((u, _) in targets) {
            openProfile(u)
            checkBlock()
            val b = byText("Following")
            if (b == null) {
                if (byText("Follow") != null || byText("Follow back") != null) Store.removeFollowed(u)
                continue
            }
            click(b); delay(rnd(1500, 2500))
            val un = byText("Unfollow")
            if (un != null && click(un)) {
                done++; Store.removeFollowed(u)
                BotLog.add("  отписка @$u ($done/${targets.size})")
                delay(rnd(1500, 2500))
            } else performGlobalAction(GLOBAL_ACTION_BACK)
            delay(rnd(20_000, 50_000))
        }
        Store.markUnfollow()
    }

    // ───────────── навигация / поиск узлов ─────────────
    private suspend fun openProfile(u: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/_u/$u"))
            .setPackage(IG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(i)
        delay(rnd(3500, 5500))
    }

    private suspend fun ensureProfile() {
        repeat(3) {
            if (byText("Following") != null) return
            performGlobalAction(GLOBAL_ACTION_BACK); delay(1200)
        }
    }

    private fun checkBlock() {
        if (first { txt(it).contains("Try Again Later", true) || txt(it).contains("Action Blocked", true) } != null)
            throw IllegalStateException("Instagram ограничил действия — остановка")
    }

    private fun usernameNear(btn: AccessibilityNodeInfo): String? {
        val re = Regex("^[a-zA-Z0-9._]{1,30}$")
        val skip = setOf("follow", "following", "remove", "message")
        var p = btn.parent; var depth = 0
        while (p != null && depth < 4) {
            val ts = ArrayList<String>()
            fun walk(n: AccessibilityNodeInfo?) {
                if (n == null) return
                val t = txt(n); if (t.isNotEmpty()) ts.add(t)
                for (i in 0 until n.childCount) walk(n.getChild(i))
            }
            walk(p)
            ts.firstOrNull { re.matches(it) && it.lowercase() !in skip }?.let { return it.lowercase() }
            p = p.parent; depth++
        }
        return null
    }

    private fun all(pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun dfs(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (pred(n)) out.add(n)
            for (i in 0 until n.childCount) dfs(n.getChild(i))
        }
        dfs(rootInActiveWindow)
        return out
    }
    private fun first(pred: (AccessibilityNodeInfo) -> Boolean) = all(pred).firstOrNull()
    private fun txt(n: AccessibilityNodeInfo) = n.text?.toString() ?: ""
    private fun desc(n: AccessibilityNodeInfo) = n.contentDescription?.toString() ?: ""
    private fun byText(t: String) = first { txt(it).equals(t, true) }

    private fun click(n: AccessibilityNodeInfo?): Boolean {
        var c = n
        while (c != null && !c.isClickable) c = c.parent
        return c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    // ───────────── жесты ─────────────
    private fun rnd(a: Long, b: Long) = Random.nextLong(a, b)

    private suspend fun gesture(p: Path, dur: Long) = suspendCancellableCoroutine<Unit> { c ->
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build()
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(Unit) }
            override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(Unit) }
        }, null)
        if (!ok && c.isActive) c.resume(Unit)
    }

    private suspend fun tapXY(fx: Float, fy: Float) {
        val m = resources.displayMetrics
        gesture(Path().apply { moveTo(m.widthPixels * fx, m.heightPixels * fy) }, 60)
    }

    private suspend fun swipeUp() {
        val m = resources.displayMetrics
        val x = m.widthPixels * Random.nextDouble(0.4, 0.6).toFloat()
        val p = Path().apply {
            moveTo(x, m.heightPixels * 0.75f)
            lineTo(x + Random.nextInt(-30, 30), m.heightPixels * 0.30f)
        }
        gesture(p, rnd(250, 450))
    }
}
