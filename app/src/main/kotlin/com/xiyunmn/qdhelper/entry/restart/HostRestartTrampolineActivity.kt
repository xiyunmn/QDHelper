package com.xiyunmn.qdhelper.entry.restart

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.xiyunmn.qdhelper.foundation.android.PendingIntentCompat
import com.xiyunmn.qdhelper.foundation.logging.AndroidLog
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost

/** Manifest-owned trampoline; restart policy lives in settings-ui. */
class HostRestartTrampolineActivity : Activity() {
    private var restartPendingIntent: PendingIntent? = null
    private var relaunchScheduled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restartPendingIntent = intent.hostRestartPendingIntent()
        window.setGravity(Gravity.TOP or Gravity.START)
        window.setLayout(1, 1)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setContentView(TextView(this).apply { text = "" })
        scheduleRelaunch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        restartPendingIntent = intent.hostRestartPendingIntent()
        scheduleRelaunch()
    }

    private fun scheduleRelaunch() {
        if (relaunchScheduled) {
            return
        }
        relaunchScheduled = true
        window.decorView.postDelayed({ relaunchHost() }, RELAUNCH_DELAY_MS)
    }

    private fun relaunchHost() {
        val sent = restartPendingIntent?.let { pendingIntent ->
            sendHostRestartPendingIntent(pendingIntent)
        } == true
        if (!sent) {
            startHostActivityDirectly()
        }
        window.decorView.postDelayed({ finishAndRemoveTask() }, FINISH_DELAY_MS)
    }

    private fun sendHostRestartPendingIntent(pendingIntent: PendingIntent): Boolean {
        return runCatching {
            pendingIntent.send(this, 0, null, null, null, null, PendingIntentCompat.senderOptions())
            true
        }.onFailure { throwable ->
            AndroidLog.warn(TAG, "PendingIntent host restart failed", throwable)
        }.getOrDefault(false)
    }

    private fun startHostActivityDirectly() {
        runCatching { startActivity(hostLaunchIntent()) }
            .onFailure { throwable -> AndroidLog.warn(TAG, "Direct host restart failed", throwable) }
    }

    private fun hostLaunchIntent(): Intent {
        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(QDReaderHost.PACKAGE, QDReaderHost.Classes.SPLASH_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }

    private fun Intent.hostRestartPendingIntent(): PendingIntent? {
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(EXTRA_HOST_RESTART_PENDING_INTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(EXTRA_HOST_RESTART_PENDING_INTENT) as? PendingIntent
        }
    }

    companion object {
        private const val TAG = "QDHelper.Restart"
        private const val MODULE_PACKAGE = "io.github.xiyunmn.qdhelper"
        private const val EXTRA_HOST_RESTART_PENDING_INTENT =
            "io.github.xiyunmn.qdhelper.extra.HOST_RESTART_PENDING_INTENT"
        private const val RELAUNCH_DELAY_MS = 1100L
        private const val FINISH_DELAY_MS = 900L

        fun createIntent(restartPendingIntent: PendingIntent): Intent {
            return Intent().apply {
                setClassName(MODULE_PACKAGE, HostRestartTrampolineActivity::class.java.name)
                putExtra(EXTRA_HOST_RESTART_PENDING_INTENT, restartPendingIntent)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                )
            }
        }
    }
}
