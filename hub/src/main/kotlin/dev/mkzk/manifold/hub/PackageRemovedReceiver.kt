package dev.mkzk.manifold.hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PackageRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        Registry.instance.forget(packageName)
    }
}
