package com.lectern.app

import android.app.Application
import com.lectern.app.player.Reader

class LecternApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Reader.init(this)
    }
}
