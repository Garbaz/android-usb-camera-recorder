package com.example.usbcamerarecorder

import android.app.Application

class App : Application() {
    val controller by lazy { CameraController(this) }
}

val android.content.Context.controller get() = (applicationContext as App).controller
