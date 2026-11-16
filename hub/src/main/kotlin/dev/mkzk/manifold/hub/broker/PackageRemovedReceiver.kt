package dev.mkzk.manifold.hub.broker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.mkzk.manifold.hub.app.hubGraph

class PackageRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val packageName = intent.data?.schemeSpecificPart ?: return
        context.hubGraph.registry.forget(packageName)
    }
}
