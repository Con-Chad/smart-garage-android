package io.github.conchad.smartgarage

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import io.github.conchad.smartgarage.core.push.PushTapRouter
import io.github.conchad.smartgarage.ui.AppRoot
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var pushTaps: PushTapRouter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh start: a rotation recreates the activity with the same intent and must not re-navigate.
        if (savedInstanceState == null) pushTaps.open(intent)
        setContent { AppRoot() }
    }

    /** `singleTask`: a notification tapped while the app is open lands here instead of in a second activity. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pushTaps.open(intent)
    }
}
