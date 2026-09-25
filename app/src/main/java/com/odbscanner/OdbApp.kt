package com.odbscanner

import android.app.Application
import android.content.Context

class OdbApp : Application() {
    lateinit var manager: ObdManager
        private set

    override fun onCreate() {
        super.onCreate()
        manager = ObdManager(this)
    }

    companion object {
        fun manager(context: Context) = (context.applicationContext as OdbApp).manager
    }
}
