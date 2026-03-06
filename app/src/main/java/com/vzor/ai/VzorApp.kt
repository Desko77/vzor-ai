package com.vzor.ai

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VzorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initializeWearablesSdk()
    }

    /**
     * Инициализирует Meta Wearables DAT SDK при старте приложения.
     * SDK должен быть инициализирован до любых обращений к Wearables API.
     */
    private fun initializeWearablesSdk() {
        try {
            Wearables.initialize(this)
            Log.d("VzorApp", "Meta Wearables DAT SDK initialized")
        } catch (e: Exception) {
            Log.w("VzorApp", "Failed to initialize DAT SDK (glasses may not be available)", e)
        }
    }
}
