@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.arthuralend.iptvpromax

import android.view.KeyEvent as AKey
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
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
import androidx.tv.material3.*
import kotlinx.coroutines.delay

@Composable
fun Root(app: AppState, player: PlayerCtl) {
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        when (app.screen) {
            Screen.Home -> HomeScreen(app, player)
            Screen.Player -> PlayerScreen(app, player)
            Screen.Settings -> SettingsScreen(app)
        }
        app.toast?.let {
            Box(Modifier.fillMaxSize().padding(top = 28.dp), contentAlignment = Alignment.TopCenter) {
                Text(it, color = Color.White, fontSize = 18.sp,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(C.Scrim).padding(horizontal = 20.dp, vertical = 10.dp))
            }
        }
    }
}

// ═══════════════════════════ HOME ═══════════════════════════

@Composable
fun HomeScreen(app: AppState, player: PlayerCtl) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // phones (and narrow windows): rail becomes a pull-out panel, touch-friendly top bar
        val compact = maxWidth < 700.dp
        var drawer by remember { mutableStateOf(false) }
        val railFocus = remember { FocusRequester() }
        if (!compact) LaunchedEffect(Unit) { delay(80); runCatching { railFocus.requestFocus() } }

        Row(Modifier.fillMaxSize()) {
            if (!compact) {
                Column(Modifier.width(290.dp).fillMaxHeight().background(C.Rail).padding(vertical = 22.dp)) {
                    RailContent(app, railFocus) { drawer = false }
                }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .padding(start = if (compact) 12.dp else 36.dp, end = if (compact) 12.dp else 36.dp, top = if (compact) 12.dp else 28.dp)
            ) {
                val list = app.sectionChannels()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (compact) {
                        SmallButton("☰") { drawer = true }
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(app.sectionTitle(), fontSize = if (compact) 22.sp else 32.sp, fontWeight = FontWeight.Bold, color = C.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${list.size} ${L.t("каналов")}", color = C.Dim, fontSize = if (compact) 12.sp else 16.sp)
                    }
                    if (app.section == "recent" && app.recents.isNotEmpty()) {
                        SmallButton(if (compact) "🗑" else L.t("Очистить недавние")) { app.clearRecents() }
                        Spacer(Modifier.width(8.dp))
                    }
                    // grid (4 in a row) / list
                    SmallButton(if (app.listMode) "▦" else "☰") { app.toggleListMode() }
                    if (compact) {
                        Spacer(Modifier.width(8.dp))
                        SmallButton("⌕") { app.selectSection("search") }
                        Spacer(Modifier.width(8.dp))
                        SmallButton("⚙") { app.screen = Screen.Settings }
                    }
                }
                if (!compact) Text(L.t("Долгое нажатие OK — в избранное"), color = C.Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(if (compact) 10.dp else 14.dp))

                if (app.section == "search") SearchField(app)

                val open: (Channel) -> Unit = { ch ->
                    app.playList = list
                    player.play(ch)
                    app.screen = Screen.Player
                }
                when {
                    app.channels.isEmpty() && app.loading -> Center(L.t("Загрузка…"))
                    app.channels.isEmpty() && app.error != null -> Center(app.error!!)
                    list.isEmpty() -> Center(L.t("Нет каналов"))
                    app.listMode -> LazyColumn(
                        contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
                    ) {
                        items(list, key = { it.id }) { ch ->
                            ChannelRow(ch, fav = ch.id in app.favorites, current = app.current?.id == ch.id, compact = compact,
                                epg = app.epgVersion, onClick = { open(ch) }, onLong = { app.toggleFav(ch) })
                        }
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(top = 6.dp, bottom = 40.dp, start = 4.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 18.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 20.dp),
                    ) {
                        items(list, key = { it.id }) { ch ->
                            ChannelCard(ch, fav = ch.id in app.favorites, current = app.current?.id == ch.id, compact = compact,
                                onClick = { open(ch) }, onLong = { app.toggleFav(ch) })
                        }
                    }
                }
            }
        }

        // pull-out sections panel on phones
        if (compact) {
            AnimatedVisibility(drawer, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { drawer = false })
            }
            AnimatedVisibility(drawer, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
                Column(Modifier.width(290.dp).fillMaxHeight().background(C.Rail).padding(vertical = 18.dp)) {
                    RailContent(app, railFocus) { drawer = false }
                }
            }
            BackHandler(enabled = drawer) { drawer = false }
        }
    }
}

@Composable
private fun ColumnScope.RailContent(app: AppState, railFocus: FocusRequester, onPicked: () -> Unit) {
    Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Text(L.t("Телевизор"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.Text)
    }
    Spacer(Modifier.height(18.dp))
    fun pick(sec: String) { app.selectSection(sec); onPicked() }
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
        item { RailItem("⌕", L.t("Поиск"), null, app.section == "search") { pick("search") } }
        item {
            RailItem("▣", L.t("Все каналы"), app.channels.size, app.section == "all",
                if (app.section == "all" || app.section == "search") Modifier.focusRequester(railFocus) else Modifier) { pick("all") }
        }
        item {
            RailItem("★", L.t("Избранное"), app.favorites.count { f -> app.channels.any { it.id == f } }, app.section == "fav",
                if (app.section == "fav") Modifier.focusRequester(railFocus) else Modifier) { pick("fav") }
        }
        item {
            RailItem("◷", L.t("Недавние"), app.recents.size, app.section == "recent",
                if (app.section == "recent") Modifier.focusRequester(railFocus) else Modifier) { pick("recent") }
        }
        item {
            Text(L.t("Группы").uppercase(), color = C.Dim, fontSize = 12.sp, letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 6.dp))
        }
        items(app.groups, key = { it.first }) { (g, n) ->
            val key = "g:$g"
            RailItem("▸", g, n, app.section == key,
                if (app.section == key) Modifier.focusRequester(railFocus) else Modifier) { pick(key) }
        }
    }
    RailItem("⚙", L.t("Настройки"), null, false, Modifier.padding(horizontal = 14.dp)) { onPicked(); app.screen = Screen.Settings }
}

/** list ("строчный") mode row */
@Composable
fun ChannelRow(ch: Channel, fav: Boolean, current: Boolean, compact: Boolean, epg: Int, onClick: () -> Unit, onLong: () -> Unit) {
    val now = remember(ch.id, epg) { Epg.nowNext(ch.id).first }
    Surface(
        onClick = onClick,
        onLongClick = onLong,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (current) C.OrangeSoft else C.Card, contentColor = C.Text,
            focusedContainerColor = C.Orange, focusedContentColor = Color.White),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${ch.number}", fontSize = 13.sp, modifier = Modifier.width(if (compact) 36.dp else 48.dp),
                color = LocalContentColor.current.copy(alpha = .6f))
            val sz = if (compact) 40.dp else 52.dp
            ChannelLogo(ch, sz, Modifier.size(sz))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ch.name, fontSize = if (compact) 15.sp else 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                if (now != null) Text("${hhmm(now.start)}  ${now.title}", fontSize = if (compact) 12.sp else 14.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = LocalContentColor.current.copy(alpha = .65f))
            }
            if (ch.days > 0) Text("⟲${ch.days}", fontSize = 12.sp, color = C.Screen, modifier = Modifier.padding(start = 8.dp))
            if (fav) Text(" ★", fontSize = 15.sp, color = if (current) Color.White else C.Orange)
        }
    }
}

@Composable
private fun Center(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = C.Dim, fontSize = 20.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun RailItem(icon: String, title: String, count: Int?, selected: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    Surface(
        onClick = onSelect,
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)
            .onFocusChanged { if (it.isFocused && icon != "⚙" && icon != "⌕") onSelect() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) C.OrangeSoft else Color.Transparent,
            contentColor = C.Text,
            focusedContainerColor = C.Orange,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 16.sp, modifier = Modifier.width(26.dp))
            Text(title, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (count != null && count > 0) Text("$count", fontSize = 13.sp, color = LocalContentColor.current.copy(alpha = .6f))
        }
    }
}

@Composable
fun ChannelCard(ch: Channel, fav: Boolean, current: Boolean, compact: Boolean = false, onClick: () -> Unit, onLong: () -> Unit) {
    Card(
        onClick = onClick,
        onLongClick = onLong,
        modifier = Modifier.height(if (compact) 112.dp else 168.dp),
        shape = CardDefaults.shape(RoundedCornerShape(18.dp)),
        colors = CardDefaults.colors(
            containerColor = if (current) C.OrangeSoft else C.Card,
            focusedContainerColor = C.CardFocused,
        ),
        scale = CardDefaults.scale(focusedScale = 1.1f),
        border = CardDefaults.border(
            border = if (current) Border(BorderStroke(2.dp, C.Orange.copy(alpha = .6f)), shape = RoundedCornerShape(18.dp)) else Border.None,
            focusedBorder = Border(BorderStroke(3.dp, C.Orange), shape = RoundedCornerShape(18.dp)),
        ),
        glow = CardDefaults.glow(focusedGlow = Glow(C.Orange.copy(alpha = .5f), 14.dp)),
    ) {
        Box(Modifier.fillMaxSize()) {
            val logo = if (compact) 46.dp else 76.dp
            Column(Modifier.fillMaxSize().padding(if (compact) 6.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                ChannelLogo(ch, logo, Modifier.size(logo))
                Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                Text(ch.name, fontSize = if (compact) 11.sp else 14.sp, color = C.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, lineHeight = if (compact) 13.sp else 17.sp)
            }
            Text("${ch.number}", fontSize = 11.sp, color = C.Dim, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                if (ch.days > 0) Text("⟲${ch.days}", fontSize = 11.sp, color = C.Screen)
                if (fav) Text(" ★", fontSize = 13.sp, color = C.Orange)
            }
        }
    }
}

@Composable
fun SmallButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = C.Card, contentColor = C.Text,
            focusedContainerColor = C.Orange, focusedContentColor = Color.White),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) { Text(text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
}

@Composable
private fun SearchField(app: AppState) {
    TvTextField(app.search, { app.search = it }, L.t("Поиск"), Modifier.fillMaxWidth().padding(bottom = 8.dp))
}

@Composable
fun TvTextField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, password: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = C.Text, fontSize = 20.sp),
        cursorBrush = SolidColor(C.Orange),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(C.Card)
                    .then(if (focused) Modifier.background(C.OrangeSoft) else Modifier)
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                if (value.isEmpty()) Text(hint, color = C.Dim, fontSize = 20.sp)
                inner()
            }
        },
    )
}

// ═══════════════════════════ PLAYER ═══════════════════════════

@Composable
fun PlayerScreen(app: AppState, player: PlayerCtl) {
    val ch = app.current ?: run { app.screen = Screen.Home; return }
    var listOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var guideOpen by remember { mutableStateOf(false) }
    var infoUntil by remember { mutableLongStateOf(System.currentTimeMillis() + 5000) }
    var digits by remember { mutableStateOf("") }
    var longHandled by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }

    fun showInfo() { infoUntil = System.currentTimeMillis() + 5000 }
    fun zap(d: Int) {
        val list = app.playList.ifEmpty { app.channels }
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == ch.id }.let { if (it < 0) 0 else it }
        player.play(list[(i + d + list.size) % list.size]); showInfo()
    }

    // ticking clock for LIVE / rewind indicators
    LaunchedEffect(Unit) { while (true) { delay(1000); player.tick++ } }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1500)
        val n = digits.toIntOrNull()
        app.channels.firstOrNull { it.number == n }?.let { app.playList = app.channels; player.play(it); showInfo() }
        digits = ""
    }
    LaunchedEffect(listOpen, menuOpen) { if (!listOpen && !menuOpen) runCatching { rootFocus.requestFocus() } }

    BackHandler {
        when {
            guideOpen -> guideOpen = false
            listOpen -> listOpen = false
            menuOpen -> menuOpen = false
            else -> { player.exo.stop(); app.screen = Screen.Home }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { ev ->
                if (listOpen || menuOpen || guideOpen) return@onPreviewKeyEvent false
                val code = ev.nativeKeyEvent.keyCode
                if (ev.type == KeyEventType.KeyDown) {
                    when (code) {
                        AKey.KEYCODE_DPAD_UP, AKey.KEYCODE_CHANNEL_UP -> { zap(-1); true }
                        AKey.KEYCODE_DPAD_DOWN, AKey.KEYCODE_CHANNEL_DOWN -> { zap(1); true }
                        // no rewinding: ◀ opens the channel list, ▶ the TV guide of this channel
                        AKey.KEYCODE_DPAD_LEFT -> { listOpen = true; true }
                        AKey.KEYCODE_DPAD_RIGHT -> { guideOpen = true; true }
                        AKey.KEYCODE_MEDIA_REWIND, AKey.KEYCODE_MEDIA_FAST_FORWARD -> true
                        AKey.KEYCODE_MEDIA_PLAY_PAUSE, AKey.KEYCODE_MEDIA_PLAY, AKey.KEYCODE_MEDIA_PAUSE, AKey.KEYCODE_SPACE -> { player.toggle(); showInfo(); true }
                        AKey.KEYCODE_MENU, AKey.KEYCODE_INFO, AKey.KEYCODE_GUIDE -> { menuOpen = true; true }
                        in AKey.KEYCODE_0..AKey.KEYCODE_9 -> { digits = (digits + (code - AKey.KEYCODE_0)).take(4); true }
                        AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER, AKey.KEYCODE_NUMPAD_ENTER -> {
                            if (ev.nativeKeyEvent.repeatCount == 1 && !longHandled) { longHandled = true; menuOpen = true }
                            true
                        }
                        else -> false
                    }
                } else if (ev.type == KeyEventType.KeyUp) {
                    when (code) {
                        AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER, AKey.KEYCODE_NUMPAD_ENTER -> {
                            if (!longHandled) menuOpen = true
                            longHandled = false
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .focusable()
    ) {
        val exo = player.exo
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    isFocusable = false
                    this.player = exo
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // touch screens: tap the video to open the control menu
        Box(Modifier.fillMaxSize().clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
        ) { if (!listOpen && !guideOpen) menuOpen = !menuOpen })

        player.status?.let {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(it, color = Color.White, fontSize = 20.sp,
                    modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(C.Scrim).padding(horizontal = 24.dp, vertical = 12.dp))
            }
        }

        if (digits.isNotEmpty()) {
            Text(digits, fontSize = 64.sp, fontWeight = FontWeight.Bold, color = C.Orange,
                modifier = Modifier.align(Alignment.TopEnd).padding(36.dp))
        }

        val tick = player.tick
        val showBar = !listOpen && !menuOpen && !guideOpen && (System.currentTimeMillis() < infoUntil || !player.playing)
        AnimatedVisibility(showBar, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            InfoBar(ch, player, tick)
        }

        AnimatedVisibility(listOpen, enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut()) {
            SideList(app, player, ch, onClose = { listOpen = false }, onPlay = { c -> player.play(c); listOpen = false; showInfo() })
        }

        AnimatedVisibility(menuOpen, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            ControlMenu(app, player, ch, onClose = { menuOpen = false }, onGuide = { menuOpen = false; guideOpen = true },
                onList = { menuOpen = false; listOpen = true }, onExit = { player.exo.stop(); app.screen = Screen.Home })
        }

        AnimatedVisibility(guideOpen, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)) {
            GuidePanel(app, player, ch, onClose = { guideOpen = false })
        }
    }
}

@Composable
private fun InfoBar(ch: Channel, player: PlayerCtl, tick: Long) {
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE0E0E0F))))
            .padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 34.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(ch, 72.dp, Modifier.size(72.dp))
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${ch.number}", color = C.Orange, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    Text(ch.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val (now, next) = remember(ch.id, tick / 30) { Epg.nowNext(ch.id) }
                if (now != null) {
                    Text("${hhmm(now.start)}–${hhmm(now.stop)}  ${now.title}", color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.padding(top = 6.dp).width(360.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(now.progress()).background(C.Orange))
                    }
                    if (next != null) Text(L.t("Далее:") + " ${hhmm(next.start)} ${next.title}", color = C.Dim, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                } else Text(ch.group, color = C.Dim, fontSize = 16.sp)
            }
            LiveBadge(player, tick)
        }
        Spacer(Modifier.height(14.dp))
        Text(L.t("◀ — список каналов · ▶ — телепрограмма · ▲▼ — переключение · OK — меню"), color = C.Dim, fontSize = 14.sp)
    }
}

@Composable
private fun LiveBadge(player: PlayerCtl, @Suppress("UNUSED_PARAMETER") tick: Long) {
    val start = player.archiveStart
    val text = if (start == null) L.t("ЭФИР") else {
        val behind = (System.currentTimeMillis() / 1000 - player.positionSec()).coerceAtLeast(0)
        val h = behind / 3600; val m = behind / 60 % 60; val s = behind % 60
        "−" + (if (h > 0) "$h:" else "") + "%02d:%02d".format(m, s)
    }
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x33FFFFFF)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (start == null) C.Orange else Color.Gray))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SideList(app: AppState, player: PlayerCtl, ch: Channel, onClose: () -> Unit, onPlay: (Channel) -> Unit) {
    val list = app.playList.ifEmpty { app.channels }
    val start = list.indexOfFirst { it.id == ch.id }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { focus.requestFocus() } }

    Column(
        Modifier.width(470.dp).fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color(0xF2141516), Color(0xE0141516))))
            .padding(top = 28.dp)
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == AKey.KEYCODE_DPAD_LEFT) { onClose(); true } else false
            }
    ) {
        Text(app.sectionTitle().ifEmpty { L.t("Все каналы") }, color = C.Orange, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp))
        LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
            itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
                val isCur = c.id == ch.id
                Surface(
                    onClick = { onPlay(c) },
                    onLongClick = { app.toggleFav(c) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).then(if (i == start) Modifier.focusRequester(focus) else Modifier),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (isCur) C.OrangeSoft else Color.Transparent, contentColor = C.Text,
                        focusedContainerColor = C.Orange, focusedContentColor = Color.White),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.number}", fontSize = 14.sp, modifier = Modifier.width(48.dp), color = LocalContentColor.current.copy(alpha = .7f))
                        ChannelLogo(c, 44.dp, Modifier.size(44.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal)
                            Epg.nowNext(c.id).first?.let {
                                Text(it.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = LocalContentColor.current.copy(alpha = .65f))
                            }
                        }
                        if (c.id in app.favorites) Text("★", color = if (isCur) Color.White else C.Orange)
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlMenu(app: AppState, player: PlayerCtl, ch: Channel, onClose: () -> Unit, onGuide: () -> Unit,
                        onList: () -> Unit = {}, onExit: () -> Unit = {}) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF00E0E0F))))
            .padding(start = 48.dp, end = 48.dp, top = 70.dp, bottom = 40.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(ch, 56.dp, Modifier.size(56.dp))
            Spacer(Modifier.width(16.dp))
            Text(ch.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            LiveBadge(player, player.tick)
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MenuButton(if (player.playing) "❚❚" else "▶", Modifier.focusRequester(focus)) { player.toggle() }
            MenuButton(if (ch.id in app.favorites) "★" else "☆") { app.toggleFav(ch) }
            MenuButton("☰ " + L.t("Телепрограмма")) { onGuide() }
            MenuButton("🔊 " + L.t("Звук")) {
                val label = player.nextAudioTrack()
                app.showToast(if (label == null) L.t("У канала одна звуковая дорожка") else L.t("Звуковая дорожка") + ": " + label)
            }
            MenuButton("▤ " + L.t("Каналы")) { onList() }
            MenuButton("✕") { onExit() }
        }
    }
    BackHandler { onClose() }
}

@Composable
private fun MenuButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0x33FFFFFF), contentColor = Color.White,
            focusedContainerColor = C.Orange, focusedContentColor = Color.White),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
    ) { Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) }
}

// ═══════════════════════════ SETTINGS ═══════════════════════════

@Composable
fun SettingsScreen(app: AppState) {
    var source by remember { mutableStateOf(app.prefs.source) }
    var password by remember { mutableStateOf(app.prefs.password) }
    val focus = remember { FocusRequester() }
    BackHandler(enabled = app.prefs.source.isNotEmpty()) { app.screen = Screen.Home }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 700.dp
        // on TV focus the field for the remote; on phones don't pop the keyboard by itself
        if (!compact) LaunchedEffect(Unit) { delay(80); runCatching { focus.requestFocus() } }

        val form: @Composable ColumnScope.() -> Unit = {
            Text(L.t("Настройки"), fontSize = if (compact) 26.sp else 36.sp, fontWeight = FontWeight.Bold, color = C.Text)
            Spacer(Modifier.height(10.dp))
            if (app.prefs.source.isEmpty()) {
                Text(L.t("Откройте «Настройки» и введите код или ссылку на плейлист"), color = C.Dim, fontSize = 15.sp)
            } else {
                Text(L.t("Подключено") + " · ${app.channels.size} ${L.t("каналов")}", color = C.Orange, fontSize = 15.sp)
            }
            Spacer(Modifier.height(22.dp))
            Text(L.t("Источник"), color = C.Dim, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            TvTextField(
                if (source == Sources.PRESET) Sources.CODE else source, { source = it },
                L.t("Код или адрес сервера / ссылка M3U"), Modifier.fillMaxWidth().focusRequester(focus))
            Spacer(Modifier.height(14.dp))
            TvTextField(password, { password = it }, L.t("Пароль сайта (если включён)"), Modifier.fillMaxWidth(), password = true)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallButton(L.t("Сохранить")) { app.saveSource(source, password) }
                if (app.prefs.source.isNotEmpty()) {
                    SmallButton(L.t("Обновить список")) { app.reload(); app.screen = Screen.Home }
                }
            }
            if (app.prefs.source.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallButton(L.t("Очистить недавние")) { app.clearRecents() }
                    SmallButton("← " + L.t("Каналы")) { app.screen = Screen.Home }
                }
            }
            app.error?.let { Spacer(Modifier.height(16.dp)); Text(it, color = Color(0xFFFF7A6A), fontSize = 15.sp) }
        }

        if (compact) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(56.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(L.t("Телевизор"), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.Text)
                }
                Spacer(Modifier.height(24.dp))
                form()
            }
        } else Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(420.dp).fillMaxHeight().background(C.Rail).padding(48.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.foundation.Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(180.dp))
                Spacer(Modifier.height(18.dp))
                Text(L.t("Телевизор"), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Text)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(64.dp), verticalArrangement = Arrangement.Center, content = form)
        }
    }
}


fun hhmm(sec: Long): String {
    val c = java.util.Calendar.getInstance(); c.timeInMillis = sec * 1000
    return "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}

private fun dayLabel(sec: Long): String {
    val c = java.util.Calendar.getInstance(); c.timeInMillis = sec * 1000
    return "%02d.%02d".format(c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.MONTH) + 1)
}

/** TV guide for the channel: past programmes start from the archive. */
@Composable
private fun GuidePanel(app: AppState, player: PlayerCtl, ch: Channel, onClose: () -> Unit) {
    val now = System.currentTimeMillis() / 1000
    val list = remember(ch.id, app.epgVersion) {
        Epg.schedule(ch.id).filter { it.stop > now - 2 * 86400 && it.start < now + 86400 }
    }
    val cur = list.indexOfFirst { it.start <= now && it.stop > now }.let { if (it < 0) 0 else it }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (cur - 4).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { focus.requestFocus() } }
    BackHandler { onClose() }

    Column(
        Modifier.width(560.dp).fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color(0xE0141516), Color(0xF5141516))))
            .padding(top = 28.dp)
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == AKey.KEYCODE_DPAD_RIGHT) { onClose(); true } else false
            }
    ) {
        Text(L.t("Телепрограмма"), color = C.Orange, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 28.dp))
        Text(ch.name, color = C.Dim, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
        if (list.isEmpty()) {
            Text(L.t("Нет телепрограммы для этого канала"), color = C.Dim, fontSize = 18.sp, modifier = Modifier.padding(28.dp))
            Box(Modifier.size(1.dp).focusRequester(focus).focusable())
        } else LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
            itemsIndexed(list) { i, p ->
                val past = p.stop <= now
                val isNow = i == cur && p.start <= now
                val canWatch = false   // viewing only: playback from the archive is disabled
                Surface(
                    onClick = { if (canWatch) { player.playFrom(ch, p.start); onClose() } },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).then(if (i == cur) Modifier.focusRequester(focus) else Modifier),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (isNow) C.OrangeSoft else Color.Transparent, contentColor = C.Text,
                        focusedContainerColor = C.Orange, focusedContentColor = Color.White),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(70.dp)) {
                            Text(hhmm(p.start), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(dayLabel(p.start), fontSize = 11.sp, color = LocalContentColor.current.copy(alpha = .55f))
                        }
                        Text(p.title, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            color = if (past) LocalContentColor.current.copy(alpha = .5f) else LocalContentColor.current)
                        if (canWatch) Text(if (isNow) "⏮" else "▶", fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}
