package com.lectern.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.lectern.app.ui.LocalPalette
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.lectern.app.player.Reader
import com.lectern.app.ui.LecternApp
import com.lectern.app.ui.LecternTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) handleViewIntent(intent)
        setContent {
            val state by Reader.state.collectAsState()
            LecternTheme(state.theme, state.accentMode) {
                // Status bar icons follow the app theme, not only the phone's.
                val dark = LocalPalette.current.dark
                LaunchedEffect(dark) {
                    val t = android.graphics.Color.TRANSPARENT
                    val style = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                LecternApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    // "Open with Lectern" from a file manager or a download notification
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { Reader.openUri(it) }
    }
}
