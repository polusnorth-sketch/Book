@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.reader

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): ReaderSettings = ReaderSettings(
        fontSize = sp.getInt("font", 18),
        lineHeight = sp.getFloat("line", 1.5f),
        theme = sp.getInt("theme", 0),
        serif = sp.getBoolean("serif", true),
        zoom = sp.getFloat("zoom", 1f),
    )

    fun save(s: ReaderSettings) {
        sp.edit()
            .putInt("font", s.fontSize)
            .putFloat("line", s.lineHeight)
            .putInt("theme", s.theme)
            .putBoolean("serif", s.serif)
            .putFloat("zoom", s.zoom)
            .apply()
    }
}

@Composable
fun ReaderApp() {
    val appCtx = LocalContext.current.applicationContext
    val lib = remember { Library(appCtx) }
    val prefs = remember { Prefs(appCtx) }
    val books = remember { mutableStateListOf<Book>().apply { addAll(lib.load()) } }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(prefs.load()) }
    var importing by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lastSave = remember { LongArray(1) }

    fun persist() {
        lib.save(books.toList())
    }

    fun update(id: String, f: (Book) -> Book) {
        val i = books.indexOfFirst { it.id == id }
        if (i >= 0) books[i] = f(books[i])
    }

    val onImport: (List<Uri>) -> Unit = { uris ->
        scope.launch {
            importing = true
            val errors = ArrayList<String>()
            for (u in uris) {
                try {
                    val b = lib.import(u)
                    books.add(0, b)
                    persist()
                } catch (e: Throwable) {
                    errors.add(e.message ?: "Не удалось добавить книгу")
                }
            }
            importing = false
            if (errors.isNotEmpty()) snack.showSnackbar(errors.joinToString("\n"))
        }
    }

    MaterialTheme(colorScheme = if (settings.theme == 2) darkColorScheme() else lightColorScheme()) {
        val book = books.firstOrNull { it.id == openId }
        if (book == null) {
            LibraryScreen(
                books = books,
                lib = lib,
                importing = importing,
                snack = snack,
                onOpen = { openId = it.id },
                onImport = onImport,
                onDelete = { b ->
                    lib.delete(b)
                    books.remove(b)
                    persist()
                },
            )
        } else {
            ReaderScreen(
                book = book,
                lib = lib,
                settings = settings,
                onSettings = { s ->
                    settings = s
                    prefs.save(s)
                },
                onProgress = { pos, total ->
                    update(book.id) { it.copy(pos = pos, total = total) }
                    val now = System.currentTimeMillis()
                    if (now - lastSave[0] > 3000) {
                        lastSave[0] = now
                        persist()
                    }
                },
                onBookmarks = { marks ->
                    update(book.id) { it.copy(bookmarks = marks) }
                    persist()
                },
                onClose = {
                    persist()
                    openId = null
                },
            )
        }
    }
}

@Composable
fun LibraryScreen(
    books: List<Book>,
    lib: Library,
    importing: Boolean,
    snack: SnackbarHostState,
    onOpen: (Book) -> Unit,
    onImport: (List<Uri>) -> Unit,
    onDelete: (Book) -> Unit,
) {
    var toDelete by remember { mutableStateOf<Book?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onImport(uris)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Моя библиотека") }) },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Добавить книги") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (importing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (books.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Здесь пока нет книг.\nНажмите «Добавить книги» и выберите файлы: EPUB, FB2, TXT, PDF, CBZ, DOCX или HTML.",
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(books, key = { it.id }) { b ->
                        BookCard(b, lib, onOpen, onAskDelete = { toDelete = b })
                    }
                }
            }
        }
    }

    val d = toDelete
    if (d != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить книгу?") },
            text = { Text(d.title) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(d)
                    toDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
fun BookCard(b: Book, lib: Library, onOpen: (Book) -> Unit, onAskDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(b) }) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(0.7f)) {
                CoverImage(lib.coverFile(b.id), Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(b.title, maxLines = 5, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
                Text(
                    b.format.name,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            val progress = if (b.total > 1) b.pos.toFloat() / (b.total - 1) else 0f
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        b.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (b.author.isNotEmpty()) {
                        Text(
                            b.author,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                IconButton(onClick = onAskDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить")
                }
            }
        }
    }
}

@Composable
fun CoverImage(file: File, modifier: Modifier, placeholder: @Composable () -> Unit) {
    val bmp by produceState<ImageBitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) {
            if (file.exists()) BitmapFactory.decodeFile(file.path)?.asImageBitmap() else null
        }
    }
    val b = bmp
    if (b != null) {
        Image(bitmap = b, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        placeholder()
    }
}
