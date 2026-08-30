package com.garo.remsound

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import com.garo.remsound.kit.ReceiverController
import com.garo.remsound.ui.ReceiverRootScreen
import com.garo.remsound.ui.theme.RemSoundTheme
import kotlinx.coroutines.flow.filterNotNull

/**
 * The window onto the receiver. It owns no pipeline state: [RemSoundService] holds the shared
 * [ReceiverController] so the socket, heartbeats and audio survive this activity going away.
 */
class MainActivity : ComponentActivity() {
    private lateinit var controller: ReceiverController

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) controller.setSendEnabled(true) else controller.reportMicrophonePermissionDenied()
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // The foreground service runs either way; without the permission its notification is
        // simply not shown, which is the user's choice to make.
        RemSoundService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = ReceiverController.shared(this)
        startServiceWithNotificationPermission()

        setContent {
            RemSoundTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val view = LocalView.current
                    // The controller raises announcements for state changes that came from
                    // somewhere the relevant control is not focused — a headset press, a profile
                    // applying, a copy. There is no view to attach them to down in the kit, so
                    // they surface here.
                    LaunchedEffect(Unit) {
                        controller.announcement.filterNotNull().collect { message ->
                            view.announceForAccessibility(message)
                            controller.consumeAnnouncement()
                        }
                    }
                    ReceiverRootScreen(
                        controller = controller,
                        onRequestMicrophonePermission = { requestMicrophone() },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Only the presentation half of the refresh tick is gated on this; the functional half
        // keeps running in the service whatever this activity is doing.
        controller.setUiVisible(true)
        // The input list is refreshed on hardware changes, never on a timer — but a change that
        // happened while the app was away produced no callback we were listening to.
        controller.refreshMicrophoneList()
    }

    override fun onStop() {
        controller.setUiVisible(false)
        super.onStop()
    }

    private fun startServiceWithNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        RemSoundService.start(this)
    }

    /**
     * Turning the send switch on asks for the microphone first, and only turns sending on once
     * permission actually exists. The switch therefore never sits in an "on but silent" state
     * while a prompt is up, and a denial is reported as an error line instead.
     */
    private fun requestMicrophone() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            controller.setSendEnabled(true)
            return
        }
        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
}
