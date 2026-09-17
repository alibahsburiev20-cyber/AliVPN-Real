package com.alivpn.app

import android.app.Application
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File

class AliVpnApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val basePath = filesDir.apply { mkdirs() }
        val workingPath = (getExternalFilesDir(null) ?: filesDir).apply { mkdirs() }
        val tempPath = cacheDir.apply { mkdirs() }

        runCatching {
            Libbox.setup(
                SetupOptions().also {
                    it.basePath = basePath.absolutePath
                    it.workingPath = workingPath.absolutePath
                    it.tempPath = tempPath.absolutePath
                    it.debug = BuildConfig.DEBUG
                    it.logMaxLines = 2000
                    it.appVersion = BuildConfig.VERSION_CODE.toString()
                    it.appMarketingVersion = BuildConfig.VERSION_NAME
                }
            )
        }.onFailure {
            Log.e("AliVPN", "Libbox.setup failed", it)
        }
    }
}
