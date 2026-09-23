package io.timelimit.android.child

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.timelimit.android.ui.IsAppInForeground
import io.timelimit.android.ui.lock.LockActivity
import io.timelimit.ui.child.AppAccessScreen

// @tag:new-ui
class ChildLockActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_PACKAGE_NAME = "pkg"

        fun start(context: Context, packageName: String) {
            context.startActivity(
                Intent(context, ChildLockActivity::class.java)
                    .putExtra(EXTRA_PACKAGE_NAME, packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)!!
        // ponytail: "Родитель рядом" and the other-device switch open the upstream lock screen
        // with its parent password; replace with the parent code (C5) once it exists
        val openUpstream = { LockActivity.start(this, packageName, null) }

        setContent {
            AppAccessScreen(
                api = ChildApiOverLogic.with(this),
                packageName = packageName,
                onParentNearby = openUpstream,
                onUseThisDevice = openUpstream,
                onClosedNever = { finish() },
            )
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
    }

    override fun onStart() {
        super.onStart()
        IsAppInForeground.reportStart()
    }

    override fun onStop() {
        super.onStop()
        IsAppInForeground.reportStop()
    }
}
