package dev.plume

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV's launcher has to stay enabled (disabling it boot-loops the TV), so we take the Home key before
 * the system sees it and open our home directly; Google TV never flashes up. Holding Home still opens the
 * Google TV dashboard. Covering Google TV whenever it shows stays as the fallback for anything else that
 * opens it (e.g. Back out of the last app). On Fire TV that cover is the only way: Fire OS keeps Home to itself.
 */
class HomeOverride : AccessibilityService() {
    override fun onServiceConnected() {
        running = this
        applyKeyFilter()
        loadHomes()
        if (!listening) {
            listening = true
            registerReceiver(packageReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            })
            // A wake from standby brings the system's own launcher back in front but fires no window event
            // for it (the window was never closed), so the watcher below never sees it. SCREEN_ON/USER_PRESENT
            // let us re-check on every wake while the service stays bound.
            registerReceiver(wakeReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            })
        }
        // The service connects at boot and again whenever Android rebinds it after killing the process during
        // a long standby. In both cases Google TV's launcher may already be on screen with no window event
        // left to catch, so cover it here if it is the window in front. (No longer gated to the first minutes
        // after boot: turning the TV off with the remote only suspends it, so a wake hours later rebinds the
        // service long past any boot window — that was why Google TV kept reappearing.)
        scheduleCover()
    }

    /**
     * Seeing keys costs volume its hold on TVs: Android acts on Volume before any service sees it and the key never
     * repeats. So Smooth volume turns key filtering off, unless remote keys need it (set, or one being learned).
     * Home is then caught by the Google TV window watcher below, which shows Google TV for about a second.
     * Fire OS never passes remote keys to us, so there filtering is always off.
     */
    fun applyKeyFilter() {
        val filter = !fireTv(this) && (!getSharedPreferences("home", MODE_PRIVATE).getBoolean("smooth_volume", false) ||
            Remap.all(this).isNotEmpty() || Remap.learn != null)
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = serviceInfo.apply { flags = if (filter) flags or flag else flags and flag.inv() }
    }

    /** Turned off, from Plume's switch or Android's Accessibility settings: the app mustn't turn it back on. */
    override fun onUnbind(intent: Intent?): Boolean {
        running = null
        handler.removeCallbacks(jump)
        handler.removeCallbacks(connectCover)
        if (listening) {
            listening = false
            unregisterReceiver(packageReceiver)
            unregisterReceiver(wakeReceiver)
        }
        getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("home_off", true).apply()
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_HOME) return remap(e)
        // Decided on release, by how long it was held: this TV's remote doesn't reliably send key repeats.
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) {
            if (e.eventTime - e.downTime < LONG_PRESS_MS) goHome() else performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
        return true
    }

    /** Keys the user changed in Plume settings (see [Remap]); any other key goes on to the system untouched. */
    private fun remap(e: KeyEvent): Boolean {
        if (Remap.kept(e)) return false
        val id = Remap.id(e)
        if (id == Remap.pending) { // the key just learned: its repeats and release never reach anyone
            if (e.action == KeyEvent.ACTION_UP) Remap.pending = null
            return true
        }
        Remap.learn?.let { learn ->
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                Remap.pending = id
                learn(e)
            }
            return true
        }
        val m = Remap.lookup(Remap.all(this), e.keyCode, e.scanCode) ?: return false
        // Decided on release, like Home: this TV's remote doesn't reliably send key repeats.
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) {
            val action = if (e.eventTime - e.downTime >= LONG_PRESS_MS && m.long.isNotEmpty()) m.long else m.short
            if (action.isNotEmpty()) Remap.run(this, action)
        }
        return true
    }

    private val handler = Handler(Looper.getMainLooper())
    private val watch by lazy { HomeWatch(fireTv(this)) { googleHomes } }
    private var googleHomes = emptySet<String>()
    private var listening = false
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = loadHomes()
    }
    private val jump = Runnable { watch.jumped(); goHome() }

    // The package of the most recent window to come to the front, taken from TYPE_WINDOW_STATE_CHANGED events
    // (the package name is there even though this service runs without content retrieval). Used to decide
    // whether a boot or a wake should assert Plume.
    @Volatile private var lastWindowPkg: String? = null
    private val connectCover = Runnable { coverOnWake() }
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = scheduleCover()
    }

    private fun scheduleCover() {
        handler.removeCallbacks(connectCover)
        handler.postDelayed(connectCover, 1200L)
    }

    /**
     * On a boot or a wake from standby the system can bring its own launcher back in front with no window
     * event for the watcher above to catch. The service runs without content retrieval, so we can't read the
     * active window; instead we go by the last window we did see. Assert Plume when nothing was seen yet (a
     * fresh boot or a just-rebound process), when Plume itself was last (cover whatever the system raised over
     * it), or when the Google launcher was last. Leave a real app alone, so a short screen-off that wakes back
     * into Netflix is never yanked to Plume.
     */
    private fun coverOnWake() {
        val p = lastWindowPkg
        if (p == null || p == packageName || p == HomeWatch.GOOGLE_TV) goHome()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        // System overlays aren't "the foreground app", so they don't count as what's in front (see coverOnWake).
        if (pkg != "com.android.systemui" && pkg != "android") lastWindowPkg = pkg
        val after = watch.opened(pkg, e.className?.toString(), SystemClock.uptimeMillis())
        if (after == HomeWatch.KEEP) return // the waiting jump stays
        handler.removeCallbacks(jump) // any other window opening means Home wasn't where we were going
        after?.let { handler.postDelayed(jump, it) }
    }

    /** The Google TV launcher's home screens, asked of the system when the service starts and when a package changes. */
    private fun loadHomes() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        googleHomes = googleTvHomes(packageManager.queryIntentActivities(home, 0).map {
            val a = it.activityInfo
            HomeEntry(a.packageName, a.name, a.targetActivity)
        })
    }

    // Marked as Home so the setup check can tell it from Plume being opened from the apps list.
    private fun goHome() = startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    override fun onInterrupt() {}

    companion object {
        /** The bound service, so Plume's switch can turn it off without any permission. */
        var running: HomeOverride? = null
            private set
        private const val LONG_PRESS_MS = 500L

        /** Fire OS keeps Amazon's home and ignores the home role; Plume can only cover it (see [HomeWatch]). */
        fun fireTv(c: Context) = c.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    }
}
