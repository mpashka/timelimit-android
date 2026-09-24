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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NEW, enable).commit()
        // apps suspended for the old interface are released at once, not at the next recount
        DefaultAppLogic.with(context).suspendAppsLogic.triggerUpdate()
    }

    /**
     * A suspended app is stopped by the system's own «blocked by your organisation» dialog, so the new lock
     * screen with «Попросить» never shows; with the new interface apps are not suspended at all.
     * ponytail: without suspension a closed app flashes before the lock screen covers it; if the flash
     * matters, suspend again and open our lock screen from the launcher (TimeLimit as the home screen)
     */
    fun suspendsApps(systemLevelBlocking: Boolean, newUi: Boolean): Boolean = systemLevelBlocking && !newUi

    private const val EXTRA_STAY_OLD = "stayInOldInterface"
    private const val EXTRA_SIGN_IN = "signInKeepingIt"

    /** The upstream sign-in with «don't ask again at this device» ticked; back to the parent's screens once it holds. */
    fun signInIntent(context: Context): Intent = oldMainIntent(context).putExtra(EXTRA_SIGN_IN, true)

    fun oldMainIntent(context: Context): Intent = Intent(context, MainActivity::class.java).putExtra(EXTRA_STAY_OLD, true)

    /** The launcher opens [MainActivity]; with the new interface a parent is sent on to [ParentActivity] once the user is known. */
    fun redirectParent(activity: ComponentActivity, isFreshStart: Boolean, showSignIn: () -> Unit) {
        if (activity.intent.getBooleanExtra(EXTRA_SIGN_IN, false)) {
            if (isFreshStart) showSignIn()

            val logic = DefaultAppLogic.with(activity)
            var done = false
            logic.deviceEntry.observe(activity) { device ->
                if (!done && device?.isUserKeptSignedIn == true && logic.deviceUserEntry.value?.type == UserType.Parent) {
                    done = true
                    activity.startActivity(Intent(activity, ParentActivity::class.java))
                    activity.finish()
                }
            }
            logic.deviceUserEntry.observe(activity) {}
            return
        }

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
