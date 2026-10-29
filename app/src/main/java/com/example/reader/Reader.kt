@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.reader

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List as ListIcon
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

enum class Dlg { NONE, CONTENTS, SEARCH, SETTINGS }

fun bgOf(theme: Int): Color = when (theme) {
    1 -> Color(0xFFF4ECD8)
    2 -> Color(0xFF121212)
    else -> Color.White
}

fun fgOf(theme: Int): Color = when (theme) {
    1 -> Color(0xFF4A3B2A)
    2 -> Color(0xFFD0D0D0)
    else -> Color(0xFF1A1A1A)
}

private val INVERT = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )
)

@Composable
fun ReaderScreen(
    book: Book,
    lib: Library,
    settings: ReaderSettings,
    onSettings: (ReaderSettings) -> Unit,
    onProgress: (Int, Int) -> Unit,
    onBookmarks: (List<Bookmark>) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    if (book.format.isPaged) {
        PagedReader(book, lib, settings, onSettings, onProgress, onBookmarks, onClose)
    } else {
        TextReader(book, lib, settings, onSettings, onProgress, onBookmarks, onClose)
    }
}

/** Общая оболочка: верхняя панель, ползунок позиции внизу, тап по тексту прячет/показывает панели. */
@Composable
fun ReaderChrome(
    title: String,
    bg: Color,
    label: String,
    position: Int,
    count: Int,
    onSeek: (Int) -> Unit,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    var bars by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(bg)) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { bars = !bars }) }
        ) {
            content()
        }
        if (bars) {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = actions,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                tonalElevation = 3.dp,
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = position.coerceIn(0, max(count - 1, 0)).toFloat(),
                        onValueChange = { onSeek(it.roundToInt()) },
                        valueRange = 0f..max(count - 1, 1).toFloat(),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Текстовые книги

@Composable
fun TextReader(
    book: Book,
    lib: Library,
    settings: ReaderSettings,
    onSettings: (ReaderSettings) -> Unit,
    onProgress: (Int, Int) -> Unit,
    onBookmarks: (List<Bookmark>) -> Unit,
    onClose: () -> Unit,
) {
    var failed by remember { mutableStateOf(false) }
    val parsed by produceState<ParsedText?>(null, book.id) {
        value = try {
            withContext(Dispatchers.IO) { Parsers.parseText(lib.bookFile(book), book.format, book.title) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed = true
            null
        }
    }
    val p = parsed
    if (p == null) {
        Box(Modifier.fillMaxSize().background(bgOf(settings.theme)), contentAlignment = Alignment.Center) {
            if (failed) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Не удалось открыть книгу", color = fgOf(settings.theme))
                    TextButton(onClick = onClose) { Text("Назад к библиотеке") }
                }
            } else {
                CircularProgressIndicator()
            }
        }
    } else {
        TextBookView(book, p, settings, onSettings, onProgress, onBookmarks, onClose)
    }
}

@Composable
fun TextBookView(
    book: Book,
    p: ParsedText,
    settings: ReaderSettings,
    onSettings: (ReaderSettings) -> Unit,
    onProgress: (Int, Int) -> Unit,
    onBookmarks: (List<Bookmark>) -> Unit,
    onClose: () -> Unit,
) {
    val blocks = p.blocks
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = book.pos.coerceIn(0, max(blocks.size - 1, 0))
    )
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val progressCb by rememberUpdatedState(onProgress)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { progressCb(it, blocks.size) }
    }

    var dlg by remember { mutableStateOf(Dlg.NONE) }
    val bg = bgOf(settings.theme)
    val fg = fgOf(settings.theme)
    val fam = if (settings.serif) FontFamily.Serif else FontFamily.SansSerif
    val pos = listState.firstVisibleItemIndex
    val percent = if (blocks.size > 1) pos * 100 / (blocks.size - 1) else 0

    fun jump(i: Int) {
        scope.launch { listState.scrollToItem(i.coerceIn(0, max(blocks.size - 1, 0))) }
    }

    ReaderChrome(
        title = book.title,
        bg = bg,
        label = "$percent%",
        position = pos,
        count = blocks.size,
        onSeek = { i -> jump(i) },
        onBack = onClose,
        actions = {
            IconButton(onClick = {
                val label = blocks.getOrNull(pos)?.text?.take(60) ?: "Закладка"
                onBookmarks(book.bookmarks + Bookmark(pos, label))
                Toast.makeText(ctx, "Закладка добавлена", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Default.Star, contentDescription = "Добавить закладку") }
            IconButton(onClick = { dlg = Dlg.CONTENTS }) {
                Icon(Icons.AutoMirrored.Filled.ListIcon, contentDescription = "Содержание и закладки")
            }
            IconButton(onClick = { dlg = Dlg.SEARCH }) {
                Icon(Icons.Default.Search, contentDescription = "Поиск")
            }
            IconButton(onClick = { dlg = Dlg.SETTINGS }) {
                Icon(Icons.Default.Settings, contentDescription = "Настройки чтения")
            }
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 72.dp, bottom = 96.dp),
        ) {
            itemsIndexed(blocks) { _, b ->
                Text(
                    text = b.text,
                    color = fg,
                    fontFamily = fam,
                    fontSize = (if (b.heading) settings.fontSize + 4 else settings.fontSize).sp,
                    fontWeight = if (b.heading) FontWeight.Bold else FontWeight.Normal,
                    lineHeight = (settings.fontSize * settings.lineHeight).sp,
                    textAlign = if (b.heading) TextAlign.Center else TextAlign.Justify,
                    style = TextStyle(
                        textIndent = if (b.heading) TextIndent.None else TextIndent(firstLine = 22.sp)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (b.heading) 20.dp else 0.dp, bottom = 8.dp),
                )
            }
        }
    }

    when (dlg) {
        Dlg.CONTENTS -> ContentsDialog(
            bookmarks = book.bookmarks,
            toc = p.toc,
            onJump = { i ->
                dlg = Dlg.NONE
                jump(i)
            },
            onDeleteBookmark = { b -> onBookmarks(book.bookmarks - b) },
            onDismiss = { dlg = Dlg.NONE },
        )
        Dlg.SEARCH -> SearchDialog(
            blocks = blocks,
            onJump = { i ->
                dlg = Dlg.NONE
                jump(i)
            },
            onDismiss = { dlg = Dlg.NONE },
        )
        Dlg.SETTINGS -> SettingsDialog(
            s = settings,
            showText = true,
            onChange = onSettings,
            onDismiss = { dlg = Dlg.NONE },
        )
        Dlg.NONE -> {}
    }
}

// ---------------------------------------------------------------- PDF и комиксы (CBZ)

private fun openSource(book: Book, lib: Library): PageSource? {
    return try {
        if (book.format == Format.PDF) PdfSource(lib.bookFile(book)) else ComicSource(lib.bookFile(book))
    } catch (e: Exception) {
        null
    }
}

@Composable
fun PagedReader(
    book: Book,
    lib: Library,
    settings: ReaderSettings,
    onSettings: (ReaderSettings) -> Unit,
    onProgress: (Int, Int) -> Unit,
    onBookmarks: (List<Bookmark>) -> Unit,
    onClose: () -> Unit,
) {
    val source = remember(book.id) { openSource(book, lib) }
    DisposableEffect(source) { onDispose { source?.close() } }
    if (source == null || source.count == 0) {
        Box(Modifier.fillMaxSize().background(bgOf(settings.theme)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Не удалось открыть книгу", color = fgOf(settings.theme))
                TextButton(onClick = onClose) { Text("Назад к библиотеке") }
            }
        }
    } else {
        PagedView(book, source, settings, onSettings, onProgress, onBookmarks, onClose)
    }
}

@Composable
fun PagedView(
    book: Book,
    source: PageSource,
    settings: ReaderSettings,
    onSettings: (ReaderSettings) -> Unit,
    onProgress: (Int, Int) -> Unit,
    onBookmarks: (List<Bookmark>) -> Unit,
    onClose: () -> Unit,
) {
    val count = source.count
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = book.pos.coerceIn(0, max(count - 1, 0))
    )
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val progressCb by rememberUpdatedState(onProgress)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { progressCb(it, count) }
    }

    var dlg by remember { mutableStateOf(Dlg.NONE) }
    val pos = listState.firstVisibleItemIndex
    val density = LocalDensity.current
    val pageWidthDp = (LocalConfiguration.current.screenWidthDp * settings.zoom).dp
    val widthPx = with(density) { pageWidthDp.roundToPx() }

    fun jump(i: Int) {
        scope.launch { listState.scrollToItem(i.coerceIn(0, max(count - 1, 0))) }
    }

    ReaderChrome(
        title = book.title,
        bg = bgOf(settings.theme),
        label = "Страница ${pos + 1} из $count",
        position = pos,
        count = count,
        onSeek = { i -> jump(i) },
        onBack = onClose,
        actions = {
            IconButton(onClick = {
                onBookmarks(book.bookmarks + Bookmark(pos, "Страница ${pos + 1}"))
                Toast.makeText(ctx, "Закладка добавлена", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Default.Star, contentDescription = "Добавить закладку") }
            IconButton(onClick = { dlg = Dlg.CONTENTS }) {
                Icon(Icons.AutoMirrored.Filled.ListIcon, contentDescription = "Закладки")
            }
            IconButton(onClick = { dlg = Dlg.SETTINGS }) {
                Icon(Icons.Default.Settings, contentDescription = "Настройки чтения")
            }
        },
    ) {
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(pageWidthDp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(count) { i ->
                    PageItem(source, i, widthPx, pageWidthDp, settings.theme == 2)
                }
            }
        }
    }

    when (dlg) {
        Dlg.CONTENTS -> ContentsDialog(
            bookmarks = book.bookmarks,
            toc = emptyList(),
            onJump = { i ->
                dlg = Dlg.NONE
                jump(i)
            },
            onDeleteBookmark = { b -> onBookmarks(book.bookmarks - b) },
            onDismiss = { dlg = Dlg.NONE },
        )
        Dlg.SETTINGS -> SettingsDialog(
            s = settings,
            showText = false,
            onChange = onSettings,
            onDismiss = { dlg = Dlg.NONE },
        )
        else -> {}
    }
}

@Composable
fun PageItem(source: PageSource, index: Int, widthPx: Int, widthDp: Dp, invert: Boolean) {
    val bmp by produceState<ImageBitmap?>(null, source, index, widthPx) {
        value = withContext(Dispatchers.IO) {
            try {
                source.render(index, widthPx)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
            colorFilter = if (invert) ColorFilter.colorMatrix(INVERT) else null,
        )
    } else {
        Box(Modifier.fillMaxWidth().height(widthDp * 1.4f), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

// ---------------------------------------------------------------- Диалоги

@Composable
fun ContentsDialog(
    bookmarks: List<Bookmark>,
    toc: List<TocItem>,
    onJump: (Int) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text("Содержание") },
        text = {
            LazyColumn {
                if (bookmarks.isNotEmpty()) {
                    item {
                        Text(
                            "Закладки",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    items(bookmarks) { b ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onJump(b.pos) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                b.label,
                                Modifier.weight(1f).padding(vertical = 10.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { onDeleteBookmark(b) }) {
                                Icon(Icons.Default.Close, contentDescription = "Удалить закладку")
                            }
                        }
                    }
                }
                if (toc.isNotEmpty()) {
                    item {
                        Text(
                            "Оглавление",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    items(toc) { t ->
                        Text(
                            t.title,
                            Modifier.fillMaxWidth().clickable { onJump(t.index) }.padding(vertical = 10.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (bookmarks.isEmpty() && toc.isEmpty()) {
                    item { Text("Пока пусто. Нажмите звёздочку на верхней панели, чтобы добавить закладку.") }
                }
            }
        },
    )
}

private fun snippet(t: String, q: String): String {
    val i = t.indexOf(q, ignoreCase = true).coerceAtLeast(0)
    val s = (i - 40).coerceAtLeast(0)
    val e = (i + q.length + 80).coerceAtMost(t.length)
    return (if (s > 0) "…" else "") + t.substring(s, e) + (if (e < t.length) "…" else "")
}

@Composable
fun SearchDialog(blocks: List<Block>, onJump: (Int) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Int>>(emptyList()) }
    LaunchedEffect(query) {
        results = if (query.length < 2) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                val out = ArrayList<Int>()
                for (i in blocks.indices) {
                    if (blocks[i].text.contains(query, ignoreCase = true)) {
                        out.add(i)
                        if (out.size >= 200) break
                    }
                }
                out
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text("Поиск по книге") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Что искать") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (query.length >= 2) {
                    val more = if (results.size >= 200) "+" else ""
                    Text(
                        if (results.isEmpty()) "Ничего не найдено" else "Найдено: ${results.size}$more",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(results) { i ->
                        Text(
                            snippet(blocks[i].text, query),
                            Modifier.fillMaxWidth().clickable { onJump(i) }.padding(vertical = 8.dp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
    )
}

@Composable
fun SettingsDialog(
    s: ReaderSettings,
    showText: Boolean,
    onChange: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        title = { Text("Настройки чтения") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showText) {
                    Text("Размер шрифта: ${s.fontSize}")
                    Slider(
                        value = s.fontSize.toFloat(),
                        onValueChange = { onChange(s.copy(fontSize = it.roundToInt())) },
                        valueRange = 12f..34f,
                    )
                    val lh = (s.lineHeight * 10).roundToInt() / 10f
                    Text("Межстрочный интервал: $lh")
                    Slider(
                        value = s.lineHeight,
                        onValueChange = { onChange(s.copy(lineHeight = it)) },
                        valueRange = 1.1f..2.2f,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Шрифт с засечками", Modifier.weight(1f))
                        Switch(checked = s.serif, onCheckedChange = { onChange(s.copy(serif = it)) })
                    }
                } else {
                    val z = (s.zoom * 10).roundToInt() / 10f
                    Text("Масштаб страниц: $z")
                    Slider(
                        value = s.zoom,
                        onValueChange = { onChange(s.copy(zoom = it)) },
                        valueRange = 1f..2f,
                    )
                }
                Text("Тема", Modifier.padding(top = 8.dp))
                listOf("Светлая", "Сепия", "Тёмная").forEachIndexed { i, name ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onChange(s.copy(theme = i)) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = s.theme == i, onClick = null)
                        Text(name, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
    )
}
