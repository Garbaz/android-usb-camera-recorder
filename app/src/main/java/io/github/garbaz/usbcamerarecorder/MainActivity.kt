package io.github.garbaz.usbcamerarecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.darkColorScheme
import androidx.core.content.ContextCompat
import io.github.garbaz.usbcamerarecorder.ui.CameraScreen

class MainActivity : ComponentActivity() {
    private var launchIntent: Intent? = null

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            controller.onCameraPermission(granted(Manifest.permission.CAMERA), launchIntent)
        }

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { controller.onMicrophonePermission(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Asked only once a camera with a microphone is connected; without it, recordings have no sound.
            val askMicrophone = controller.microphone != null && !controller.microphonePermission
            LaunchedEffect(askMicrophone) {
                if (askMicrophone) microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            MaterialTheme(colorScheme = darkColorScheme()) {
                CameraScreen(controller, onRequestCameraPermission = ::requestPermissions)
            }
        }
        launchIntent = intent
        if (granted(Manifest.permission.CAMERA)) controller.onCameraPermission(true, intent)
        if (wanted.any { !granted(it) }) requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        // It may have been changed in the system settings meanwhile.
        controller.onMicrophonePermission(granted(Manifest.permission.RECORD_AUDIO))
        controller.setUiVisible(true)
    }

    override fun onStop() {
        controller.setUiVisible(false)
        super.onStop()
    }

    /** USB_DEVICE_ATTACHED while running (launchMode singleTop). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchIntent = intent
        controller.findCamera(intent)
    }

    /** Camera: Android 9+ only gives apps holding it USB access to video devices. Notifications: recording status. */
    private val wanted = listOfNotNull(
        Manifest.permission.CAMERA,
        Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= 33 },
    )

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() = permissions.launch(wanted.filterNot(::granted).toTypedArray())
}
