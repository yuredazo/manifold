package dev.mkzk.manifold.hub.screen

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** Registers its result handlers on creation, so it has to be created while the activity is being set up. */
internal class ScreenShareRequest(private val activity: ComponentActivity) {
    private var withSound = false

    // Sound is optional: a refusal still shares the picture.
    private val askForSound = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        withSound = granted
        askForCapture()
    }

    private val capture = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val consent = result.data
        if (result.resultCode == Activity.RESULT_OK && consent != null) ScreenShareService.start(activity, consent, withSound)
    }

    fun begin(sound: Boolean) {
        withSound = sound
        val needsPermission = sound && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) askForSound.launch(Manifest.permission.RECORD_AUDIO) else askForCapture()
    }

    private fun askForCapture() {
        capture.launch(activity.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }
}
