package com.arthuralend.iptvpromax

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** Android TV: remote-control UI (Screens.kt). */
class MainActivity : ComponentActivity() {
    private lateinit var app: AppState
    private lateinit var player: PlayerCtl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupImageLoader(applicationContext, "Android TV")
        app = AppState(applicationContext)
        player = PlayerCtl(this, app)
        setContent { AppTheme { Root(app, player) } }
    }

    override fun onStop() { super.onStop(); player.exo.pause() }
    override fun onStart() { super.onStart(); if (app.screen == Screen.Player) player.exo.play() }
    override fun onDestroy() { player.release(); super.onDestroy() }
}
