package dev.mkzk.manifold.hub.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.mkzk.manifold.hub.R

// Registers its result handler on creation, so it has to be created while the activity is being set up.
internal class CameraShareRequest(private val activity: ComponentActivity) {
    private var explainedBefore = false

    private val askForCamera = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            CameraShareService.start(activity)
            return@registerForActivityResult
        }
        // Android shows no dialog any more after "Don't allow" twice, and answers at once with a refusal.
        val blocked = !explainedBefore && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        if (blocked) {
            Toast.makeText(activity, R.string.camera_allow_in_settings, Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
        }
    }

    fun begin() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            CameraShareService.start(activity)
        } else {
            explainedBefore = activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            askForCamera.launch(Manifest.permission.CAMERA)
        }
    }
}
