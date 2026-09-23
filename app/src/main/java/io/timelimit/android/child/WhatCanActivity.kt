package io.timelimit.android.child

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.timelimit.android.ui.IsAppInForeground
import io.timelimit.ui.child.WhatCanScreen

// @tag:new-ui
class WhatCanActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WhatCanScreen(ChildApiOverLogic.with(this)) }
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
