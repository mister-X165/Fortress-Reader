package com.fortress.reader

import android.app.Application
import com.fortress.reader.security.SecureTemp
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FortressApp : Application() {
    @Inject lateinit var temp: SecureTemp

    override fun onCreate() {
        super.onCreate()
        temp.wipeAll() // clear plaintext leftovers from a crash / kill
    }
}
