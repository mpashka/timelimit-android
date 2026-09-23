package io.timelimit.android.child

import android.content.Context
import io.timelimit.android.ui.lock.LockActivity

/** Which child interface this device shows: the new one (module :ui) or the upstream one. */
// @tag:new-ui
object UiChoice {
    private const val PREFS = "ui_choice"
    private const val KEY_NEW = "new"

    // ponytail: kept per device outside the synced config; move into ConfigDao or the protocol
    // when a parent has to switch it remotely
    fun isNew(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NEW, true)

    fun setNew(context: Context, enable: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NEW, enable).apply()
    }

    fun startLockScreen(context: Context, packageName: String, activityName: String?) {
        if (isNew(context)) ChildLockActivity.start(context, packageName)
        else LockActivity.start(context, packageName, activityName)
    }
}
