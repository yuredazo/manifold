package dev.mkzk.manifold.hub.app

import android.app.Application

class HubApp : Application() {
    internal lateinit var graph: HubGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = HubGraph(this)
    }
}
