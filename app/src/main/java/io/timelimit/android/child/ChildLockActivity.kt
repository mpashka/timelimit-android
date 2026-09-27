package io.timelimit.android.child

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.ui.IsAppInForeground
import io.timelimit.android.ui.lock.LockActivity
import io.timelimit.ui.R
import io.timelimit.ui.child.AppAccessScreen
import kotlinx.coroutines.launch
import java.io.IOException

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
        val openUpstream = { LockActivity.start(this, packageName, null) }
        val logic = DefaultAppLogic.with(this)
        var switching = false
        // ponytail: no progress while the server answers — the screen turns to "open" by itself on success
        val onUseThisDevice: () -> Unit = {
            if (!switching) {
                switching = true
                lifecycleScope.launch {
                    try {
                        when (useThisDevice(logic)) {
                            UseThisDeviceResult.Done -> {}
                            UseThisDeviceResult.NeedsParent -> openUpstream()
                            UseThisDeviceResult.OtherDeviceKeepsIt -> toast(R.string.child_use_this_device_busy)
                        }
                    } catch (_: IOException) {
                        toast(R.string.child_use_this_device_offline)
                    } finally {
                        switching = false
                    }
                }
            }
        }

        setContent {
            AppAccessScreen(
                api = ChildApiOverLogic.with(this),
                packageName = packageName,
                onParentNearby = openUpstream,
                onUseThisDevice = onUseThisDevice,
                onClosedNever = { finish() },
            )
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
    }

    private fun toast(text: Int) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    override fun onStart() {
        super.onStart()
        IsAppInForeground.reportStart()
    }

    override fun onStop() {
        super.onStop()
        IsAppInForeground.reportStop()
    }
}
