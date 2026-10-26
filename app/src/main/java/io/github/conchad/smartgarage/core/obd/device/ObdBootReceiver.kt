package io.github.conchad.smartgarage.core.obd.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.conchad.smartgarage.core.obd.ObdController

/**
 * After a reboot or an app update (D-A73): re-registers presence observation for an associated adapter, and starts
 * the waiting foreground service for one used without association (the autoConnect fallback).
 */
class ObdBootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun obdController(): ObdController
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).obdController().onBoot { pending.finish() }
    }
}
