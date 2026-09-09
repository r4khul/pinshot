package com.escapebranch.pinshot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.escapebranch.pinshot.notifications.ExpirationScheduler
import com.escapebranch.pinshot.notifications.NotificationDestinations
import com.escapebranch.pinshot.notifications.PinshotNotifications
import com.escapebranch.pinshot.ui.MainScreen
import com.escapebranch.pinshot.ui.theme.PinshotTheme

class MainActivity : ComponentActivity() {
    private var notificationDestination by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        notificationDestination = intent.destination()
        PinshotNotifications.ensureChannel(this)
        ExpirationScheduler.schedule(this)
        enableEdgeToEdge()
        setContent {
            PinshotTheme {
                MainScreen(initialDestination = notificationDestination)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationDestination = intent.destination()
    }

    private fun android.content.Intent.destination(): String? =
        getStringExtra(NotificationDestinations.EXTRA_DESTINATION)
}
