package io.github.conchad.smartgarage

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.conchad.smartgarage.core.sync.SyncWorker

@HiltAndroidApp
class SmartGarageApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedule(this)
    }
}
