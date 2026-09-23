package com.octelium.client

import android.app.Application

class OcteliumApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        container = AppContainer(this)
        container.start()
    }
}
