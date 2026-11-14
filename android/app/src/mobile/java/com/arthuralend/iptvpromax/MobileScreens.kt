@file:OptIn(ExperimentalMaterial3Api::class)

package com.arthuralend.iptvpromax

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun MobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Orange, onPrimary = Color.White, secondary = C.OrangeDeep,
            background = C.Bg, surface = C.Rail, onSurface = C.Text, onBackground = C.Text,
            surfaceVariant = C.Card, onSurfaceVariant = C.Dim, secondaryContainer = C.OrangeSoft,
            onSecondaryContainer = C.Text, outline = Color(0x33FFFFFF),
        ),
    ) { Surface(Modifier.fillMaxSize(), color = C.Bg) { content() } }
}

@Composable
fun MobileRoot(app: AppState, player: PlayerCtl, act: MainActivity) {
    val conf = LocalConfiguration.current
    val landscape = conf.orientation == Configuration.ORIENTATION_LANDSCAPE
    val tablet = conf.smallestScreenWidthDp >= 600
    var userFull by remember { mutableStateOf(false) }
    val playing = app.current != null
    // phones: turning the phone sideways while watching = full screen
    val full = playing && (userFull || (landscape && !tablet))

    LaunchedEffect(full) { act.immersive(full) }

    if (act.inPip) { VideoSurface(player); return }

    fun closePlayer() {
        player.exo.stop(); app.current = null; userFull = false; act.unlockOrientation()
    }

    Box(Modifier.fillMaxSize()) {
        when {
            app.screen == Screen.Settings -> MobileSettings(app)
            full -> FullPlayer(app, player, act,
                onExitFull = { userFull = false; if (!tablet) act.lockPortrait() })
            tablet && conf.screenWidthDp >= 840 -> Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(400.dp).fillMaxHeight().background(C.Rail)) {
                    Library(app, player, compact = false)
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (playing) {
                        InlinePlayer(app, player, onFull = { userFull = true }, onClose = { closePlayer() })
                        GuideList(app, app.current!!, Modifier.weight(1f))
                    } else Placeholder()
                }
            }
            else -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                if (playing) InlinePlayer(app, player, onFull = { userFull = true; if (!tablet) act.lockLandscape() }, onClose = { closePlayer() })
                Library(app, player, compact = !tablet)
            }
        }
        app.toast?.let {
            Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
                Text(it, color = Color.White, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xE6000000)).padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }
    }

    BackHandler(enabled = full || playing || app.screen == Screen.Settings) {
        when {
            app.screen == Screen.Settings && app.prefs.source.isNotEmpty() -> app.screen = Screen.Home
            full -> { userFull = false; if (!tablet) act.lockPortrait() }
            playing -> closePlayer()
        }
    }
}

@Composable
private fun Placeholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.foundation.Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(140.dp))
            Spacer(Modifier.height(12.dp))
            Text(L.t("Выберите канал"), color = C.Dim, fontSize = 18.sp)
        }
    }
}

// ═══════════════════════ library: sections, search, list / grid ═══════════════════════

@Composable
private fun Library(app: AppState, player: PlayerCtl, compact: Boolean) {
    var searchOpen by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        // header
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (app.current == null) {
                androidx.compose.foundation.Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(app.sectionTitle(), fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${app.sectionChannels().size} ${L.t("каналов")}", fontSize = 12.sp, color = C.Dim)
            }
            HeaderButton("⌕") { searchOpen = !searchOpen; if (!searchOpen) { app.search = ""; if (app.section == "search") app.selectSection("all") } }
            HeaderButton(if (app.listMode) "▦" else "☰") { app.toggleListMode() }
            if (app.section == "recent" && app.recents.isNotEmpty()) HeaderButton("🗑") { app.clearRecents() }
            HeaderButton("⚙") { app.screen = Screen.Settings }
        }
        if (searchOpen) {
            OutlinedTextField(
                value = app.search,
                onValueChange = { app.search = it; app.selectSection("search") },
                placeholder = { Text(L.t("Поиск")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
            )
        }
        SectionChips(app)

        val list = app.sectionChannels()
        val open: (Channel) -> Unit = { ch -> app.playList = list; player.play(ch) }
        when {
            app.channels.isEmpty() && app.loading -> CenterText(L.t("Загрузка…"))
            app.channels.isEmpty() && app.error != null -> CenterText(app.error!!)
            app.channels.isEmpty() -> CenterText(L.t("Откройте «Настройки» и введите код или ссылку на плейлист"))
            list.isEmpty() -> CenterText(L.t("Нет каналов"))
            app.listMode -> LazyColumn(contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(list, key = { it.id }) { ch -> MobileRow(app, ch, onClick = { open(ch) }) }
            }
            else -> LazyVerticalGrid(
                columns = if (compact) GridCells.Fixed(4) else GridCells.Adaptive(116.dp),
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { ch -> MobileTile(app, ch, compact, onClick = { open(ch) }) }
            }
        }
    }
}

@Composable
private fun HeaderButton(text: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 20.sp, color = C.Text)
    }
}

@Composable
private fun CenterText(t: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(t, color = C.Dim, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SectionChips(app: AppState) {
    val items = buildList {
        add("all" to L.t("Все каналы")); add("fav" to "★ " + L.t("Избранное")); add("recent" to L.t("Недавние"))
        app.groups.forEach { (g, _) -> add("g:$g" to g) }
    }
    val sel = items.indexOfFirst { it.first == app.section }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = sel)
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.first }) { (key, title) ->
            FilterChip(
                selected = app.section == key,
                onClick = { app.search = ""; app.selectSection(key) },
                label = { Text(title, maxLines = 1) },
                shape = RoundedCornerShape(18.dp),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = C.Card, labelColor = C.Text,
                    selectedContainerColor = C.Orange, selectedLabelColor = Color.White),
                border = null,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MobileRow(app: AppState, ch: Channel, onClick: () -> Unit) {
    val current = app.current?.id == ch.id
    val now = remember(ch.id, app.epgVersion) { Epg.nowNext(ch.id).first }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (current) C.OrangeSoft else C.Card)
            .combinedClickable(onClick = onClick, onLongClick = { app.toggleFav(ch) })
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${ch.number}", fontSize = 12.sp, color = C.Dim, modifier = Modifier.width(34.dp))
        ChannelLogo(ch, 42.dp, Modifier.size(42.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
            if (now != null) {
                Text("${hhmm(now.start)}  ${now.title}", fontSize = 12.sp, color = C.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = { now.progress() }, modifier = Modifier.padding(top = 3.dp).fillMaxWidth().height(2.dp),
                    color = C.Orange, trackColor = Color(0x22FFFFFF))
            }
        }
        if (ch.id in app.favorites) Text(" ★", color = C.Orange, fontSize = 15.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MobileTile(app: AppState, ch: Channel, compact: Boolean, onClick: () -> Unit) {
    val current = app.current?.id == ch.id
    Box(
        Modifier.height(if (compact) 100.dp else 124.dp).clip(RoundedCornerShape(14.dp))
            .background(if (current) C.OrangeSoft else C.Card)
            .then(if (current) Modifier.border(1.5.dp, C.Orange, RoundedCornerShape(14.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = { app.toggleFav(ch) })
    ) {
        Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val s = if (compact) 44.dp else 58.dp
            ChannelLogo(ch, s, Modifier.size(s))
            Spacer(Modifier.height(6.dp))
            Text(ch.name, fontSize = if (compact) 10.sp else 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, lineHeight = if (compact) 12.sp else 14.sp)
        }
        if (ch.id in app.favorites) Text("★", color = C.Orange, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp))
    }
}

// ═══════════════════════ player ═══════════════════════

@Composable
private fun VideoSurface(player: PlayerCtl, modifier: Modifier = Modifier.fillMaxSize()) {
    val exo = player.exo
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                this.player = exo
            }
        },
        update = { it.player = exo },
        modifier = modifier.background(Color.Black),
    )
}

private fun zap(app: AppState, player: PlayerCtl, d: Int) {
    val list = app.playList.ifEmpty { app.channels }
    val cur = app.current ?: return
    if (list.isEmpty()) return
    val i = list.indexOfFirst { it.id == cur.id }.let { if (it < 0) 0 else it }
    player.play(list[(i + d + list.size) % list.size])
}

@Composable
private fun InlinePlayer(app: AppState, player: PlayerCtl, onFull: () -> Unit, onClose: () -> Unit) {
    var controls by remember { mutableStateOf(true) }
    var guide by remember { mutableStateOf(false) }
    LaunchedEffect(controls, app.current?.id) { if (controls) { delay(4000); controls = false } }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
        .pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls }, onDoubleTap = { onFull() }) }) {
        VideoSurface(player)
        StatusOverlay(player)
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut()) {
            PlayerControls(app, player, isFull = false, onFull = onFull, onClose = onClose, onGuide = { guide = true })
        }
    }
    if (guide) GuideSheet(app, app.current!!) { guide = false }
}

@Composable
private fun FullPlayer(app: AppState, player: PlayerCtl, act: MainActivity, onExitFull: () -> Unit) {
    var controls by remember { mutableStateOf(true) }
    var guide by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf<Pair<String, Float>?>(null) }
    LaunchedEffect(controls, app.current?.id) { if (controls) { delay(4000); controls = false } }
    LaunchedEffect(level) { if (level != null) { delay(900); level = null } }

    Box(Modifier.fillMaxSize().background(Color.Black)
        .pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls }, onDoubleTap = { onExitFull() }) }
        .pointerInput(Unit) {
            // ◀▶ swipe: change channel · ▲▼ swipe: right half = volume, left half = brightness
            var dx = 0f; var dy = 0f; var vertical: Boolean? = null; var rightHalf = true
            detectDragGestures(
                onDragStart = { o -> dx = 0f; dy = 0f; vertical = null; rightHalf = o.x > size.width / 2 },
                onDragEnd = {
                    if (vertical == false && abs(dx) > size.width * 0.18f) zap(app, player, if (dx < 0) 1 else -1)
                },
            ) { change, drag ->
                change.consume()
                dx += drag.x; dy += drag.y
                if (vertical == null && (abs(dx) > 24 || abs(dy) > 24)) vertical = abs(dy) > abs(dx)
                if (vertical == true) {
                    val delta = -drag.y / size.height
                    level = if (rightHalf) "🔊" to act.changeVolume(delta * 1.4f) else "☀" to act.changeBrightness(delta * 1.4f)
                }
            }
        }
    ) {
        VideoSurface(player)
        StatusOverlay(player)
        level?.let { (icon, v) ->
            Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color(0xB3000000)).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(icon, fontSize = 28.sp)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { v }, modifier = Modifier.width(140.dp), color = C.Orange, trackColor = Color(0x33FFFFFF))
            }
        }
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut()) {
            PlayerControls(app, player, isFull = true, onFull = onExitFull, onClose = onExitFull,
                onGuide = { guide = true }, onList = { list = true })
        }
        if (list) ChannelSheet(app, player) { list = false }
    }
    if (guide) GuideSheet(app, app.current!!) { guide = false }
}

@Composable
private fun StatusOverlay(player: PlayerCtl) {
    player.status?.let {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(it, color = Color.White, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xB3000000)).padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun PlayerControls(app: AppState, player: PlayerCtl, isFull: Boolean, onFull: () -> Unit, onClose: () -> Unit,
                           onGuide: () -> Unit, onList: (() -> Unit)? = null) {
    val ch = app.current ?: return
    val (now, next) = remember(ch.id, app.epgVersion, player.tick / 30) { Epg.nowNext(ch.id) }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent, Color(0xCC000000))))) {
        // top: channel + programme
        Row(Modifier.fillMaxWidth().padding(10.dp).then(if (isFull) Modifier.padding(horizontal = 24.dp, vertical = 8.dp) else Modifier),
            verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(ch, 36.dp, Modifier.size(36.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${ch.number}  ${ch.name}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (now != null) Text("${hhmm(now.start)}–${hhmm(now.stop)}  ${now.title}", color = Color(0xDDFFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (isFull && next != null) Text(L.t("Далее:") + " ${hhmm(next.start)} ${next.title}", color = Color(0x99FFFFFF), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CtrlButton("✕", 18) { onClose() }
        }
        // center: prev / play / next
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            CtrlButton("⏮", 26) { zap(app, player, -1) }
            CtrlButton(if (player.playing) "❚❚" else "▶", 30, big = true) { player.toggle() }
            CtrlButton("⏭", 26) { zap(app, player, 1) }
        }
        // bottom: actions
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = if (isFull) 24.dp else 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (now != null) LinearProgressIndicator(progress = { now.progress() }, modifier = Modifier.weight(1f).height(3.dp),
                color = C.Orange, trackColor = Color(0x33FFFFFF)) else Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            CtrlButton(if (ch.id in app.favorites) "★" else "☆", 20) { app.toggleFav(ch) }
            CtrlButton("🔊", 18) {
                val label = player.nextAudioTrack()
                app.showToast(if (label == null) L.t("У канала одна звуковая дорожка") else L.t("Звуковая дорожка") + ": " + label)
            }
            CtrlButton("☰", 20) { onGuide() }
            if (onList != null) CtrlButton("▤", 20) { onList() }
            CtrlButton(if (isFull) "⤡" else "⛶", 20) { onFull() }
        }
    }
}

@Composable
private fun CtrlButton(text: String, size: Int, big: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(if (big) 64.dp else 44.dp).clip(CircleShape)
            .background(if (big) Color(0x55000000) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontSize = size.sp) }
}

// ═══════════════════════ sheets: TV guide, channel list ═══════════════════════

@Composable
private fun GuideList(app: AppState, ch: Channel, modifier: Modifier = Modifier) {
    val now = System.currentTimeMillis() / 1000
    val list = remember(ch.id, app.epgVersion) { Epg.schedule(ch.id).filter { it.stop > now - 6 * 3600 && it.start < now + 24 * 3600 } }
    val cur = list.indexOfFirst { it.start <= now && it.stop > now }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (cur - 2).coerceAtLeast(0))
    Column(modifier.fillMaxWidth()) {
        Text(L.t("Телепрограмма") + " · " + ch.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = C.Orange,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (list.isEmpty()) Text(L.t("Нет телепрограммы для этого канала"), color = C.Dim, modifier = Modifier.padding(16.dp))
        else LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(list) { i, p ->
                val isNow = i == cur && p.start <= now
                Row(Modifier.fillMaxWidth().background(if (isNow) C.OrangeSoft else Color.Transparent).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Text(hhmm(p.start), fontWeight = FontWeight.SemiBold, color = if (isNow) C.Orange else C.Text, modifier = Modifier.width(58.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, color = if (p.stop <= now) C.Dim else C.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (isNow) LinearProgressIndicator(progress = { p.progress() }, modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(2.dp),
                            color = C.Orange, trackColor = Color(0x22FFFFFF))
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideSheet(app: AppState, ch: Channel, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = C.Rail) {
        GuideList(app, ch, Modifier.fillMaxHeight(0.7f))
    }
}

@Composable
private fun ChannelSheet(app: AppState, player: PlayerCtl, onClose: () -> Unit) {
    val list = app.playList.ifEmpty { app.channels }
    val start = list.indexOfFirst { it.id == app.current?.id }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable { onClose() }) {
        LazyColumn(
            state = state,
            modifier = Modifier.width(360.dp).fillMaxHeight().background(Color(0xF2141516)).clickable(enabled = false) {},
            contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(list, key = { it.id }) { ch -> MobileRow(app, ch, onClick = { player.play(ch); onClose() }) }
        }
    }
}

// ═══════════════════════ settings ═══════════════════════

@Composable
private fun MobileSettings(app: AppState) {
    var source by remember { mutableStateOf(if (app.prefs.source == Sources.PRESET) Sources.CODE else app.prefs.source) }
    var password by remember { mutableStateOf(app.prefs.password) }
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(56.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(L.t("Телевизор"), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(L.t("Настройки"), color = C.Dim)
            }
        }
        Spacer(Modifier.height(20.dp))
        if (app.prefs.source.isEmpty()) Text(L.t("Откройте «Настройки» и введите код или ссылку на плейлист"), color = C.Dim)
        else Text(L.t("Подключено") + " · ${app.channels.size} ${L.t("каналов")}", color = C.Orange)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(source, {
                source = it
                // promo code: save and open the channels right away
                if (it.trim() == Sources.CODE) { focus.clearFocus(); app.saveSource(it, password) }
            }, label = { Text(L.t("Источник")) },
            placeholder = { Text(L.t("Код или адрес сервера / ссылка M3U")) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, { password = it }, label = { Text(L.t("Пароль сайта (если включён)")) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(20.dp))
        Button(onClick = { app.saveSource(source, password) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) {
            Text(L.t("Сохранить"), fontSize = 16.sp)
        }
        if (app.prefs.source.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { app.reload(); app.screen = Screen.Home }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text(L.t("Обновить список"))
            }
            OutlinedButton(onClick = { app.clearRecents() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text(L.t("Очистить недавние"))
            }
            TextButton(onClick = { app.screen = Screen.Home }, modifier = Modifier.fillMaxWidth()) { Text("← " + L.t("Каналы")) }
        }
        app.error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = Color(0xFFFF7A6A)) }
    }
}
