package com.arthuralend.iptvpromax

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Phone & tablet: touch UI (MobileScreens.kt), full-screen video, gestures, picture-in-picture. */
class MainActivity : ComponentActivity() {
    private lateinit var app: AppState
    private lateinit var player: PlayerCtl
    var inPip by mutableStateOf(false)
        private set

    val isTablet: Boolean get() = resources.configuration.smallestScreenWidthDp >= 600

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupImageLoader(applicationContext, "Android")
        app = AppState(applicationContext)
        player = PlayerCtl(this, app)
        setContent { MobileTheme { MobileRoot(app, player, this) } }
    }

    /** hide status/navigation bars while the video is full screen */
    fun immersive(on: Boolean) {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, !on)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else c.show(WindowInsetsCompat.Type.systemBars())
    }

    fun lockLandscape() { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
    fun lockPortrait() { if (!isTablet) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT }
    fun unlockOrientation() { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }

    // ── volume & brightness for swipe gestures ──
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    /** delta in 0..1 of the full range; returns the new level 0..1 */
    fun changeVolume(delta: Float): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val next = (cur + Math.round(delta * max)).coerceIn(0, max)
        if (next != cur) audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        return next.toFloat() / max
    }

    fun changeBrightness(delta: Float): Float {
        val lp = window.attributes
        val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
        lp.screenBrightness = (cur + delta).coerceIn(0.02f, 1f)
        window.attributes = lp
        return lp.screenBrightness
    }

    // ── picture-in-picture: leaving the app keeps the video in a small window ──
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && app.current != null && player.exo.isPlaying &&
            packageManager.hasSystemFeature("android.software.picture_in_picture")
        ) {
            runCatching {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStop() { super.onStop(); if (!inPip) player.exo.pause() }
    override fun onStart() { super.onStart(); if (app.current != null) player.exo.play() }
    override fun onDestroy() { player.release(); super.onDestroy() }
}
