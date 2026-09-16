package com.obsidiancompanion

import android.app.Application

class ObsidianApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
