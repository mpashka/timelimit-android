package io.timelimit.android.child

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import io.timelimit.android.data.model.UserType
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.ui.MainActivity
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

    private const val EXTRA_STAY_OLD = "stayInOldInterface"

    fun oldMainIntent(context: Context): Intent = Intent(context, MainActivity::class.java).putExtra(EXTRA_STAY_OLD, true)

    /** The launcher opens [MainActivity]; with the new interface a parent is sent on to [ParentActivity] once the user is known. */
    fun redirectParent(activity: ComponentActivity, isFreshStart: Boolean) {
        if (!isFreshStart || activity.intent.getBooleanExtra(EXTRA_STAY_OLD, false) || !isNew(activity)) return

        var done = false
        DefaultAppLogic.with(activity).deviceUserEntry.observe(activity) { user ->
            if (!done && user?.type == UserType.Parent) {
                done = true
                activity.startActivity(Intent(activity, ParentActivity::class.java))
                activity.finish()
            }
        }
    }

    fun startLockScreen(context: Context, packageName: String, activityName: String?) {
        if (isNew(context)) ChildLockActivity.start(context, packageName)
        else LockActivity.start(context, packageName, activityName)
    }
}
