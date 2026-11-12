package com.arthuralend.iptvpromax

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C as MC
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen { Home, Player, Settings }

/** All app state in one place (single-activity app). */
class AppState(ctx: Context) {
    val prefs = Prefs(ctx)
    val repo = Repository(ctx, prefs)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var channels by mutableStateOf(repo.cached())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var favorites by mutableStateOf(prefs.favorites)
    var recents by mutableStateOf(prefs.recents)
    var section by mutableStateOf(prefs.lastSection)
    var search by mutableStateOf("")
    var screen by mutableStateOf(if (prefs.source.isEmpty()) Screen.Settings else Screen.Home)
    var current by mutableStateOf<Channel?>(null)
    var toast by mutableStateOf<String?>(null)
    private var toastJob: Job? = null

    /** channels the player zaps through (the section it was started from) */
    var playList: List<Channel> = emptyList()

    init { if (prefs.source.isNotEmpty()) reload() }

    fun reload() {
        loading = true
        error = null
        scope.launch {
            try { channels = repo.load() }
            catch (e: Exception) { error = L.t("Не удалось загрузить список: ") + (e.message ?: e.javaClass.simpleName) }
            loading = false
        }
    }

    val groups: List<Pair<String, Int>>
        get() = channels.groupBy { it.group }.map { it.key to it.value.size }

    fun sectionChannels(sec: String = section): List<Channel> {
        val byId = channels.associateBy { it.id }
        val list = when {
            sec == "fav" -> channels.filter { it.id in favorites }
            sec == "recent" -> recents.mapNotNull { byId[it] }
            sec.startsWith("g:") -> channels.filter { it.group == sec.substring(2) }
            else -> channels
        }
        val q = search.trim()
        return if (sec == "search" && q.isNotEmpty()) list.filter { it.name.contains(q, ignoreCase = true) } else list
    }

    fun sectionTitle(sec: String = section) = when {
        sec == "all" -> L.t("Все каналы")
        sec == "fav" -> L.t("Избранное")
        sec == "recent" -> L.t("Недавние")
        sec == "search" -> L.t("Поиск")
        sec.startsWith("g:") -> sec.substring(2)
        else -> ""
    }

    fun selectSection(sec: String) {
        section = sec
        if (sec != "search") prefs.lastSection = sec
    }

    fun toggleFav(c: Channel) {
        favorites = if (c.id in favorites) favorites - c.id else favorites + c.id
        prefs.favorites = favorites
        showToast(if (c.id in favorites) L.t("Добавлено в избранное") else L.t("Убрано из избранного"))
    }

    fun clearRecents() { recents = emptyList(); prefs.recents = recents }

    fun markPlayed(c: Channel) {
        recents = (listOf(c.id) + recents.filter { it != c.id }).take(50)
        prefs.recents = recents
    }

    fun showToast(text: String) {
        toast = text
        toastJob?.cancel()
        toastJob = scope.launch { delay(2500); toast = null }
    }

    fun saveSource(input: String, password: String) {
        prefs.source = Sources.normalize(input)
        prefs.password = password
        prefs.cookie = ""
        channels = emptyList()
        if (prefs.source.isNotEmpty()) { reload(); screen = Screen.Home }
    }
}

/** ExoPlayer wrapper: direct-from-provider first, server proxy as fallback, archive/rewind. */
class PlayerCtl(ctx: Context, private val app: AppState) {
    private val http = DefaultHttpDataSource.Factory()
        .setUserAgent(Sources.UA)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10000)
        .setReadTimeoutMs(15000)

    val exo: ExoPlayer = ExoPlayer.Builder(ctx)
        .setLoadControl(
            DefaultLoadControl.Builder()
                // start fast: begin playback after 1.5 s of buffer
                .setBufferDurationsMs(4000, 30000, 1500, 3000)
                .build()
        )
        .build()

    var archiveStart by mutableStateOf<Long?>(null)      // unix seconds; null = live
    var status by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
    var tick by mutableLongStateOf(0L)
    var retries by mutableIntStateOf(0)
    private var usingDirect = false

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                status = when (state) {
                    Player.STATE_BUFFERING -> L.t("Подключение…")
                    Player.STATE_READY -> null
                    else -> status
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(error: PlaybackException) {
                val c = app.current ?: return
                if (usingDirect) { open(c, direct = false); return }        // provider refused direct -> via server
                if (retries < 2) { retries++; open(c, direct = false); return }
                status = L.t("Ошибка воспроизведения")
            }
        })
    }

    fun play(c: Channel) {
        app.current = c
        app.markPlayed(c)
        archiveStart = null
        retries = 0
        open(c, direct = true)
    }

    private fun open(c: Channel, direct: Boolean) {
        val useDirect = direct && app.repo.isServer
        usingDirect = useDirect
        val cookie = app.prefs.cookie
        http.setDefaultRequestProperties(if (cookie.isNotEmpty()) mapOf("Cookie" to cookie) else emptyMap())
        val url = app.repo.streamUrl(c, archiveStart, useDirect)
        val item = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        val src = HlsMediaSource.Factory(http).setAllowChunklessPreparation(true).createMediaSource(item)
        exo.setMediaSource(src)
        exo.prepare()
        exo.playWhenReady = true
        status = L.t("Подключение…")
    }

    fun positionSec(): Long {
        val a = archiveStart ?: return System.currentTimeMillis() / 1000
        val pos = exo.currentPosition.coerceAtLeast(0)
        return a + pos / 1000
    }

    fun seekBy(seconds: Long) {
        val c = app.current ?: return
        val now = System.currentTimeMillis() / 1000
        val target = positionSec() + seconds
        if (c.days > 0) {
            if (target >= now - 15) { if (archiveStart != null) { archiveStart = null; open(c, true) } ; return }
            archiveStart = maxOf(target, now - c.days * 86400L)
            open(c, true)
        } else if (exo.isCurrentMediaItemSeekable && exo.duration != MC.TIME_UNSET) {
            exo.seekTo((exo.currentPosition + seconds * 1000).coerceIn(0, exo.duration))
        } else app.showToast(L.t("Архив недоступен"))
    }

    fun goLive() { val c = app.current ?: return; archiveStart = null; open(c, true) }

    fun toggle() { if (exo.isPlaying) exo.pause() else exo.play() }

    fun release() = exo.release()
}

class MainActivity : ComponentActivity() {
    private lateinit var app: AppState
    private lateinit var player: PlayerCtl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // logos: SVG support + a proper User-Agent (Wikimedia rejects anonymous clients)
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(applicationContext)
                .components { add(coil.decode.SvgDecoder.Factory()) }
                .okHttpClient {
                    okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                        chain.proceed(chain.request().newBuilder()
                            .header("User-Agent", "IPTVProMax/1.0 (Android TV; +https://github.com/Arthuralend/Hald-IPTV)").build())
                    }.build()
                }
                .crossfade(true)
                .respectCacheHeaders(false)
                .build()
        )
        app = AppState(applicationContext)
        player = PlayerCtl(this, app)
        setContent { AppTheme { Root(app, player) } }
    }

    override fun onStop() { super.onStop(); player.exo.pause() }
    override fun onStart() { super.onStart(); if (app.screen == Screen.Player) player.exo.play() }
    override fun onDestroy() { player.release(); super.onDestroy() }
}
