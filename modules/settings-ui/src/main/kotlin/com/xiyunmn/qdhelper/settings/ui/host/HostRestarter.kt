package com.xiyunmn.qdhelper.settings.ui.host

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import android.widget.Toast
import com.xiyunmn.qdhelper.foundation.android.PendingIntentCompat
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols

/** Settings-owned restart policy; the app supplies its manifest trampoline factory. */
object HostRestarter {
    private const val HOST_RESTART_REQUEST_CODE = 0x514448
    private const val HOST_KILL_DELAY_MS = 650L

    fun restartHost(
        activity: Activity,
        beforeKill: () -> Unit,
        trampolineIntent: (PendingIntent) -> Intent,
    ) {
        if (activity.packageName != HostSymbols.PACKAGE) {
            Toast.makeText(activity, "请在起点内重启宿主", Toast.LENGTH_SHORT).show()
            return
        }
        val restartPendingIntent = createHostRestartPendingIntent(activity)
        val started = runCatching {
            activity.startActivity(trampolineIntent(restartPendingIntent))
        }.isSuccess
        if (!started) {
            Toast.makeText(activity, "重启失败，请手动重启", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(activity, "正在重启起点", Toast.LENGTH_SHORT).show()
        runCatching { beforeKill() }
        activity.window.decorView.postDelayed({
            Process.killProcess(Process.myPid())
            System.exit(0)
        }, HOST_KILL_DELAY_MS)
    }

    private fun createHostRestartPendingIntent(activity: Activity): PendingIntent = PendingIntent.getActivity(
        activity,
        HOST_RESTART_REQUEST_CODE,
        hostLaunchIntent(),
        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        PendingIntentCompat.creatorOptions(),
    )

    private fun hostLaunchIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        setClassName(HostSymbols.PACKAGE, HostSymbols.Classes.SPLASH_ACTIVITY)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
