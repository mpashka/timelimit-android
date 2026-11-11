/*
 * TimeLimit Copyright <C> 2019 - 2024 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.timelimit.android.ui.homescreen

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.timelimit.android.child.ChildApiOverLogic
import io.timelimit.android.child.ChildLockActivity
import io.timelimit.android.child.UiChoice
import io.timelimit.android.child.WhatCanActivity
import io.timelimit.android.ui.IsAppInForeground
import io.timelimit.ui.child.HomeScreen

// @tag:child-homescreen
class HomescreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // the home screen stays put: "back" here would only make the system start it again
        onBackPressedDispatcher.addCallback(this) {}
        setContent {
            HomeScreen(
                api = ChildApiOverLogic.with(this),
                launch = { app ->
                    val intent = packageManager.getLaunchIntentForPackage(app.packageName)

                    if (intent != null) startActivity(intent)
                    else Toast.makeText(this, getString(io.timelimit.ui.R.string.child_home_cannot_launch, app.title), Toast.LENGTH_SHORT).show()
                },
                askFor = { ChildLockActivity.start(this, it.packageName) },
                openWhatCan = { startActivity(Intent(this, WhatCanActivity::class.java)) },
                openParent = { startActivity(UiChoice.oldMainIntent(this)) },
            )
        }
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
