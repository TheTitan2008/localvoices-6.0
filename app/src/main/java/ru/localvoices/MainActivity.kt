package ru.localvoices

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import kotlinx.coroutines.delay
import ru.localvoices.data.*
import ru.localvoices.audio.RecorderState
import ru.localvoices.audio.PcmFile
import java.io.File
import kotlin.math.abs

private fun statsTime(ms: Long): String = if (ms < 1000) "${ms} мс" else "%.2f с".format(ms / 1000.0)
private fun durationText(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds < 60) "${seconds} с" else "%d мин %02d с".format(seconds / 60, seconds % 60)
}
private fun progressFraction(rec: Recording): Float = if (rec.stage in 1..8) ((rec.stage - 1).toFloat() / 8f).coerceIn(0f, .99f) else 0f

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { VoicesUi() } }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicesUi(vm: VoicesViewModel = viewModel()) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xffb7c4ff), secondary = Color(0xffb5d3c4)) else lightColorScheme(primary = Color(0xff4257a6), secondary = Color(0xff356753))) {
        var page by rememberSaveable { mutableStateOf("home") }
        val recordings by vm.recordings.collectAsStateWithLifecycle()
        val profiles by vm.profiles.collectAsStateWithLifecycle()
        val selected by vm.selected.collectAsStateWithLifecycle()
        val busy by vm.busy.collectAsStateWithLifecycle()
        val mic by vm.microphone.state.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }
        val lifecycle = LocalLifecycleOwner.current
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && vm.microphone.state.value.recording && !vm.app.capture.conversation.value) vm.stopRecording() }
            lifecycle.lifecycle.addObserver(observer); onDispose { lifecycle.lifecycle.removeObserver(observer) }
        }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.RECORD_AUDIO] == true) vm.startRecording(if (page == "enroll") "sample" else "conversation")
            else vm.message.value = "Для записи разрешите доступ к микрофону"
        }
        fun record() { permissions.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO)) }
        // Single-document picker is the stable Android 4.0 path. Some OEM
        // providers return broken transient grants for OpenMultipleDocuments,
        // which made the 6.0 importer crash before decoding began.
        val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importAudio) }
        val exportAll = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {it?.let(vm::exportAll)}
        BackHandler(page != "home" && !mic.recording) { page = if (page == "enroll") "voices" else "home" }
        Scaffold(
            topBar = { TopAppBar(title = { Text(when (page) { "record" -> "Запись разговора"; "voices" -> "Мои голоса"; "enroll" -> "Регистрация голоса"; "result" -> "Расшифровка"; "settings" -> "Настройки"; else -> "Локальные голоса" }) }, navigationIcon = {
                if (page != "home") IconButton(onClick = { if (mic.recording) vm.stopRecording(); page = if (page == "enroll") "voices" else "home" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            }) },
            bottomBar = {
                if (!mic.recording) NavigationBar {
                    listOf(Triple("home", "Записи", Icons.Default.Home), Triple("voices", "Голоса", Icons.Default.People), Triple("settings", "Настройки", Icons.Default.Settings)).forEach { (key, label, icon) ->
                        NavigationBarItem(selected = page == key, onClick = { page = key }, icon = { Icon(icon, label) }, label = { Text(label) })
                    }
                }
            }, snackbarHost = { SnackbarHost(snackbar) }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (page) {
                    "home" -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Text("Разговоры остаются у вас", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                        item { Text("Русская речь, имена и временные метки. Обработка полностью на устройстве.", style = MaterialTheme.typography.bodyLarge) }
                        item { Button(onClick = { page = "record" }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Записать разговор") } }
                        item { OutlinedButton(onClick = { importAudio.launch(arrayOf("audio/*", "video/*")) }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text("Импортировать один аудиофайл") } }
                        item { TextButton(onClick = { page = "voices" }, modifier = Modifier.fillMaxWidth()) { Text("Мои голоса · ${profiles.size}") } }
                        if(recordings.isNotEmpty()) item { Row {TextButton(onClick={vm.processAll()},enabled=!busy){Text("Обработать готовые")}; TextButton(onClick={exportAll.launch("dialogues.zip")},enabled=!busy){Text("Экспорт всех")}} }
                        item { Text("Последние записи", style = MaterialTheme.typography.titleLarge) }
                        if (recordings.isEmpty()) item { Hint("Здесь появятся записанные и импортированные разговоры. Модели готовятся автоматически при первом запуске.") }
                        items(recordings, key = { it.id }) { recording ->
                            Card(onClick = { vm.selected.value = recording.id; page = "result" }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(recording.title, fontWeight = FontWeight.SemiBold)
                                    Text(recording.status, color = MaterialTheme.colorScheme.primary)
                                    recording.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                    if (recording.stage in 1..8 && recording.status != "Готово") {
                                        LinearProgressIndicator(progress = { progressFraction(recording) }, Modifier.fillMaxWidth())
                                        Text("Этап ${recording.stage} из 8", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(12.dp)) }
                    }
                    "record" -> Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Hint("Разговор записывается и при блокировке экрана. Черновик появляется во время записи; после остановки запустится точная обработка.")
                        RecordingControls(mic, busy, { record() }, vm::stopRecording, vm.microphone::pause)
                        val preview by vm.app.capture.preview.collectAsStateWithLifecycle()
                        val previewStatus by vm.app.capture.previewStatus.collectAsStateWithLifecycle()
                        Text(previewStatus,style=MaterialTheme.typography.labelMedium)
                        val oldPreview = preview.dropLast(minOf(24, preview.size)).joinToString("") { it.text }
                        val newPreview = preview.takeLast(24).joinToString("") { it.text }
                        if (oldPreview.isNotBlank()) Text(oldPreview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (newPreview.isNotBlank()) Text(newPreview, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                        if (!mic.recording && selected != null) {
                            Button(onClick = { selected?.let(vm::process); page = "result" }, enabled = !busy) { Text("Распознать последнюю запись") }
                        }
                    }
                    "voices" -> LazyColumn(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Button(onClick = { vm.beginProfile(); page = "enroll" }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Icon(Icons.Default.Add, null); Text("Добавить голос") } }
                        if (profiles.isEmpty()) item { Hint("Для человека достаточно одной записи с минимум 3 секундами речи. Длинная запись сохраняется целиком.") }
                        items(profiles, key = { it.profile.id }) { p -> ProfileCard(p, vm) { vm.beginProfile(p.profile.id); page = "enroll" } }
                    }
                    "enroll" -> Enrollment(vm, mic, busy) { record() }
                    "result" -> {
                        val recording = recordings.find { it.id == selected }
                        if (recording == null) Hint("Выберите запись на главной") else ResultScreen(vm, recording, profiles, busy)
                    }
                    "settings" -> SettingsScreen(vm, busy)
                }
            }
        }
    }
}
@Composable private fun Hint(text: String) { Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) } }
@Composable private fun RecordingControls(state: RecorderState, busy: Boolean, start: () -> Unit, stop: () -> Unit, pause: () -> Unit) {
    Text(timestamp(state.seconds), style = MaterialTheme.typography.displayLarge)
    LinearProgressIndicator(progress = { state.level }, modifier = Modifier.fillMaxWidth())
    Text(if (state.paused) "Пауза" else if (state.recording) "Идёт запись" else "Микрофон готов")
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.recording) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = pause) { Text(if (state.paused) "Продолжить" else "Пауза") }
        Button(onClick = stop, enabled = !busy) { Text("Остановить") }
    } else Button(onClick = start, enabled = !busy) { Icon(Icons.Default.Mic, null); Text("Начать запись") }
}
@Composable private fun ProfileCard(p: ProfileWithSamples, vm: VoicesViewModel, add: () -> Unit) {
    var rename by remember { mutableStateOf(false) }; var delete by remember { mutableStateOf(false) }; var name by remember(p.profile.name) { mutableStateOf(p.profile.name) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(p.profile.name, style = MaterialTheme.typography.titleLarge)
        Text("${p.samples.size} образцов · ${timestamp(p.samples.sumOf { it.duration })} речи")
        Text(if (p.profile.dispersion < .15) "Профиль готов · согласованные образцы" else "Профиль готов · повышенный разброс")
        Text(if (p.profile.encoder.startsWith("whisper")) "Экспериментальный Whisper" else "Специализированный speaker encoder", style = MaterialTheme.typography.labelSmall)
        Row { TextButton(onClick = add) { Text("Образцы") }; IconButton(onClick = { rename = true }) { Icon(Icons.Default.Edit, "Изменить имя") }; IconButton(onClick = { delete = true }) { Icon(Icons.Default.Delete, "Удалить голос") } }
    } }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("Изменить имя") }, text = { OutlinedTextField(name, { name = it }, label = { Text("Имя") }) }, confirmButton = { TextButton(onClick = { vm.renameProfile(p.profile, name); rename = false }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = { rename = false }) { Text("Отмена") } })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Удалить голос ${p.profile.name}?") }, text = { Text("Будут удалены образцы и эмбеддинги профиля. Имя в результатах будет обезличено.") }, confirmButton = { TextButton(onClick = { vm.deleteProfile(p.profile.id); delete = false }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("Отмена") } })
}
@Composable private fun Enrollment(vm: VoicesViewModel, mic: RecorderState, busy: Boolean, start: () -> Unit) {
    val drafts by vm.drafts.collectAsStateWithLifecycle(); val profileId by vm.editingProfile.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val existing = profiles.find { it.profile.id == profileId }
    var name by rememberSaveable(profileId) { mutableStateOf(existing?.profile?.name ?: "") }
    var replace by rememberSaveable(profileId) { mutableStateOf(false) }
    LazyColumn(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedTextField(name, { name = it }, label = { Text("Имя человека") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { Hint("Запишите один образец с минимум 3 секундами речи. Можно говорить сколько нужно: запись сохранится целиком, а голос будет проанализирован по всей длине.") }
        item { if (existing != null) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(replace, { replace = it }); Text("Зарегистрировать заново") } }
        item { Text("Новых образцов: ${drafts.size} / минимум 1", style = MaterialTheme.typography.titleMedium) }
        if (existing != null && !replace) items(existing.samples, key = { "stored_${it.id}" }) { sample ->
            Card { Column(Modifier.padding(12.dp)) { Text("Сохранённый образец · ${timestamp(sample.duration)}"); AudioPlayer(sample.path) } }
        }
        item { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { RecordingControls(mic, busy, start, vm::stopRecording, vm.microphone::pause) } }
        items(drafts, key = { it.id }) { d ->
            Card { Column(Modifier.padding(12.dp)) { Text("${d.quality.message} · запись ${timestamp(PcmFile(d.file).duration)} · речь ${timestamp(d.quality.duration)}"); AudioPlayer(d.file.absolutePath); TextButton(onClick = { vm.removeDraft(d.id) }, enabled = !busy) { Text("Удалить образец") } } }
        }
        item { Button(onClick = { vm.saveProfile(name, replace) }, enabled = !busy && !mic.recording && name.isNotBlank() && drafts.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Сохранить голос") } }
        item { Spacer(Modifier.height(20.dp)) }
    }
}
@Composable private fun AudioPlayer(path: String, seek: Double? = null, seekVersion: Int = 0) {
    var player by remember(path) { mutableStateOf<MediaPlayer?>(null) }; var position by remember(path) { mutableFloatStateOf(0f) }; var duration by remember(path) { mutableFloatStateOf(1f) }; var playing by remember(path) { mutableStateOf(false) }; var error by remember(path) { mutableStateOf<String?>(null) }
    DisposableEffect(path) {
        var p: MediaPlayer? = null
        try { p = MediaPlayer().apply { setDataSource(path); prepare(); setOnCompletionListener { playing = false } }; player = p; duration = p.duration.toFloat().coerceAtLeast(1f) } catch (e: Exception) { error = e.message; p?.release() }
        onDispose { player = null; p?.release() }
    }
    LaunchedEffect(seekVersion, player) { if (seek != null) { player?.seekTo((seek * 1000).toInt()); position = (seek * 1000).toFloat(); player?.start(); playing = true } }
    LaunchedEffect(player) { while (player != null) { position = runCatching { player?.currentPosition?.toFloat() ?: 0f }.getOrDefault(0f); delay(250) } }
    Column {
        error?.let { Text("Ошибка плеера: $it", color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player?.let { if (it.isPlaying) it.pause() else it.start(); playing = it.isPlaying } }, enabled = player != null) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Воспроизведение") }
            Text("${timestamp(position / 1000.0)} / ${timestamp(duration / 1000.0)}")
        }
        Slider(value = position.coerceIn(0f, duration), onValueChange = { position = it; player?.seekTo(it.toInt()) }, valueRange = 0f..duration)
    }
}
@Composable private fun ResultScreen(vm: VoicesViewModel, rec: Recording, profiles: List<ProfileWithSamples>, busy: Boolean) {
    val allRows by vm.utterances.collectAsStateWithLifecycle(); val rows = allRows.filter { it.recordingId == rec.id }; var editing by remember { mutableStateOf<UtteranceEntity?>(null) }
    var seek by remember { mutableStateOf<Double?>(null) }; var seekVersion by remember { mutableIntStateOf(0) }
    var format by rememberSaveable { mutableStateOf("txt") }; var remerge by remember { mutableStateOf(false) }
    var exportId by rememberSaveable { mutableStateOf(rec.id) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { it?.let { vm.export(it, format, exportId) } }
    val exportAudio = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { it?.let { vm.exportAudio(it, rec.id) } }
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
    AudioPlayer(rec.path,seek,seekVersion)
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(rec.title, style = MaterialTheme.typography.titleLarge) }
        item {Text("ID: ${rec.id}",style=MaterialTheme.typography.labelSmall)}
        if(rec.punctuationStatus!="none")item {Text(rec.punctuationStatus,style=MaterialTheme.typography.labelSmall)}
        if(rec.processingStats!="{}") item {
            val stats = remember(rec.processingStats) { ProcessingStatsCodec.decode(rec.processingStats) }
            val stageMs = stats.optJSONObject("stage_ms")
            val counters = stats.optJSONObject("counters")
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("Статистика обработки", fontWeight = FontWeight.SemiBold)
                Text("Общее время: ${statsTime(stats.optLong("total_ms", rec.processingMs))}", style = MaterialTheme.typography.bodyMedium)
                if (stats.has("audio_duration_ms")) Text("Длительность аудио: ${durationText(stats.optLong("audio_duration_ms"))}", style = MaterialTheme.typography.labelSmall)
                if (stats.has("realtime_factor") && !stats.isNull("realtime_factor")) Text("Скорость: %.2fx от длительности аудио".format(stats.optDouble("realtime_factor")), style = MaterialTheme.typography.labelSmall)
                stageMs?.let { obj ->
                    val names = listOf(
                        "stage_1" to "Подготовка", "stage_2" to "VAD", "stage_3" to "ASR",
                        "stage_4" to "Эмбеддинги", "stage_5" to "Профили", "stage_6" to "Кластеры",
                        "stage_7" to "Сборка текста", "stage_8" to "Сохранение"
                    )
                    names.filter { obj.has(it.first) }.forEach { (key, name) -> Text("$name: ${statsTime(obj.optLong(key))}", style = MaterialTheme.typography.labelSmall) }
                }
                counters?.let { obj ->
                    Text("Окна embeddings: ${obj.optLong("embedding_windows_total")} · live-кэш: ${obj.optLong("live_embedding_hits")}", style = MaterialTheme.typography.labelSmall)
                    Text("ASR блоков: ${obj.optLong("asr_blocks_total")} · переиспользовано live: ${obj.optLong("asr_blocks_reused_live")}", style = MaterialTheme.typography.labelSmall)
                    Text("VAD-кэш: ${obj.optLong("vad_cache_hits")} · полных запусков VAD: ${obj.optLong("vad_full_runs")}", style = MaterialTheme.typography.labelSmall)
                }
            } }
        }
        if(rec.acousticEvents!="[]")item {Hint("Обнаружены возможные наложения голосов. Говорящие и текст на этих участках требуют проверки: "+runCatching {val a=org.json.JSONArray(rec.acousticEvents);(0 until minOf(a.length(),8)).joinToString {val e=a.getJSONObject(it);timestamp(e.getLong("start_ms")/1000.0)+"–"+timestamp(e.getLong("end_ms")/1000.0)}}.getOrDefault("см. экспорт"))}
        item { SpeakerConnections(vm,rec) }
        item {
            Text(rec.status, color = MaterialTheme.colorScheme.primary); rec.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if ((rec.stage in 1..8 || rec.status=="В очереди") && rec.status != "Готово") {
                LinearProgressIndicator(progress = { progressFraction(rec) }, Modifier.fillMaxWidth())
                Text("${rec.status} · этап ${rec.stage} из 8", style = MaterialTheme.typography.bodyMedium)
                Text("${rec.status}", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { vm.cancel(rec.id) }) { Text("Остановить обработку") }
            }
            else Row { Button(onClick = { vm.process(rec.id) }, enabled = !busy) { Text(if (rows.isEmpty()) "Распознать" else "Повторить") }; if (rows.isNotEmpty()) TextButton(onClick = { remerge = true }, enabled = !busy) { Text("Пересчитать голоса") } }
        }
        if (rows.isNotEmpty()) item { Row { listOf("txt", "json", "srt").forEach { type -> TextButton(onClick = { format = type; exportId = rec.id; export.launch("transcript.${type}") }) { Text(type.uppercase()) } } } }
        item { TextButton(onClick = { exportAudio.launch("${rec.title.replace(Regex("[^A-Za-zА-Яа-я0-9_-]"), "_")}.wav") }, enabled = rec.path.isNotBlank()) { Text("Скачать исходное аудио (WAV)") } }
        item {Row{TextButton(onClick={vm.undo(rec.id)},enabled=rows.isNotEmpty()){Text("Отменить правку")};TextButton(onClick={vm.deleteRecording(rec.id)}){Text("Удалить диалог")}}}
        items(rows, key = { it.id }) { row ->
            val colors = listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.tertiaryContainer)
            Card(colors = CardDefaults.cardColors(containerColor = if (row.speakerId == null) MaterialTheme.colorScheme.surfaceContainer else colors[(row.speakerId.hashCode() and Int.MAX_VALUE) % colors.size]), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { seek = row.start; seekVersion++ }) { Text("${timestamp(row.start)}–${timestamp(row.end)} · ${row.name}", fontWeight = FontWeight.Bold); Text("Нажмите для перехода к аудио", style = MaterialTheme.typography.labelSmall) }
                        IconButton(onClick = { editing = row }) { Icon(Icons.Default.Edit, "Изменить реплику") }
                    }
                    Text(row.text.trim(), style = MaterialTheme.typography.bodyLarge)
                    if (row.confidence > 0) Text("Акустическое сходство: %.2f".format(row.confidence), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    }
    editing?.let { row -> EditDialog(row, profiles, vm, rec) { editing = null } }
    if (remerge) AlertDialog(onDismissRequest = { remerge = false }, title = { Text("Пересчитать диаризацию?") }, text = { Text("Сохранённые акустические окна будут сопоставлены с текущими профилями и настройками. Ручные исправления текста и говорящих будут заменены исходными результатами.") }, confirmButton = { TextButton(onClick = { vm.remerge(rec.id); remerge = false }) { Text("Пересчитать") } }, dismissButton = { TextButton(onClick = { remerge = false }) { Text("Отмена") } })
}
@Composable private fun EditDialog(row: UtteranceEntity, profiles: List<ProfileWithSamples>, vm: VoicesViewModel, rec: Recording, dismiss: () -> Unit) {
    var text by remember(row.id) { mutableStateOf(row.text) }; var name by remember(row.id) { mutableStateOf(row.name) }; var speaker by remember(row.id) { mutableStateOf<String?>(null) }; var entire by remember { mutableStateOf(false) }; var menu by remember { mutableStateOf(false) }
    val utterances by vm.utterances.collectAsStateWithLifecycle()
    val clusters = utterances.filter { it.recordingId == rec.id && it.speakerId != null && profiles.none { p -> p.profile.id == it.speakerId } }.distinctBy { it.speakerId }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Исправить реплику") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(text, { text = it }, label = { Text("Текст") }, minLines = 3)
            OutlinedTextField(name, { name = it; speaker = null }, label = { Text("Имя говорящего") })
            Box {
                TextButton(onClick = { menu = true }) { Text("Выбрать говорящего / объединить") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Оставить неизвестным") }, onClick = { name = "Неизвестный"; speaker = "__unknown__"; menu = false })
                    profiles.forEach { p -> DropdownMenuItem(text = { Text(p.profile.name) }, onClick = { name = p.profile.name; speaker = p.profile.id; menu = false }) }
                    clusters.filter { it.speakerId != row.speakerId }.forEach { target ->
                        DropdownMenuItem(text = { Text("Объединить с ${target.name}") }, onClick = { name = target.name; speaker = target.speakerId; menu = false })
                    }
                }
            }
            if (row.speakerId != null) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(entire, { entire = it }); Text("Изменить говорящего во всём кластере") }
            if (row.speakerId != null && profiles.none { it.profile.id == row.speakerId }) TextButton(onClick = { vm.enrollCluster(rec, row.speakerId, name) }) { Text("Сохранить голос как новый профиль") }
        }
    }, confirmButton = { TextButton(onClick = { vm.edit(row, text, name, speaker, entire); dismiss() }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
@Composable private fun SpeakerConnections(vm: VoicesViewModel, rec: Recording) {
    val speakers by vm.dialogueSpeakers.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val all by vm.allSpeakers.collectAsStateWithLifecycle()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    var naming by remember {mutableStateOf<PersonEntity?>(null)}
    var name by remember {mutableStateOf("")}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        speakers.filter{it.recordingId==rec.id}.forEach { s ->
            val person=people.find{it.id==s.personId}
            Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
                Text(person?.name ?: "Неизвестный участник · ${s.localId}",fontWeight=FontWeight.SemiBold)
                Text("Участник: ${s.personId.takeLast(12)}",style=MaterialTheme.typography.labelSmall)
                if(person!=null)TextButton(onClick={naming=person;name=person.name ?: ""}){Text("Назвать человека")}
                all.filter{it.personId==s.personId && it.recordingId!=rec.id}.distinctBy{it.recordingId}.forEach{link->
                    val target=recordings.find{it.id==link.recordingId}
                    if(target!=null)TextButton(onClick={vm.selected.value=target.id}){Text("Также: ${target.title}")}
                }
                if(s.candidateId!=null) {
                    val candidate=people.find{it.id==s.candidateId}
                    Text("Возможное совпадение: ${candidate?.name ?: "неизвестный участник другого диалога"} · сходство %.2f".format(s.similarity))
                    all.filter{it.personId==s.candidateId}.distinctBy{it.recordingId}.forEach{link->recordings.find{it.id==link.recordingId}?.let{target->TextButton(onClick={vm.selected.value=target.id}){Text("Сравнить: ${target.title}")}}}
                    Row{TextButton(onClick={vm.decideLink(s,true)}){Text("Один человек")};TextButton(onClick={vm.decideLink(s,false)}){Text("Другой человек")}}
                }
            }}
        }
    }
    naming?.let { person -> AlertDialog(onDismissRequest={naming=null},title={Text("Имя во всех связанных диалогах")},text={OutlinedTextField(name,{name=it},label={Text("Имя")})},confirmButton={TextButton(onClick={vm.namePerson(person.id,name);naming=null},enabled=name.isNotBlank()){Text("Сохранить")}},dismissButton={TextButton(onClick={naming=null}){Text("Отмена")}}) }
}
@Composable private fun SettingsScreen(vm: VoicesViewModel, busy: Boolean) {
    val prefs = vm.app.prefs
    var encoder by remember { mutableStateOf(prefs.encoder) }; var advanced by rememberSaveable { mutableStateOf(false) }
    val preparation by vm.app.models.preparation.collectAsStateWithLifecycle()
    LazyColumn(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Hint("Приложение работает без интернета. Все необходимые модели включены в APK и готовятся автоматически при первом запуске. Состояние: $preparation") }
        item { Text("Модель распознавания", style = MaterialTheme.typography.titleLarge) }
        item { Text(if (BuildConfig.ASR_BACKEND == "gigaam") "GigaAM · встроена" else "Whisper base · встроена") }
        item { Text("Голосовые эмбеддинги", style = MaterialTheme.typography.titleLarge) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(encoder == "speaker", onClick = { encoder = "speaker"; prefs.encoder = encoder }, enabled = !busy); Text("Speaker encoder · рекомендуемый") } }
        if (BuildConfig.ASR_BACKEND != "gigaam") item { Hint("Профили голоса зависят от выбранного speaker encoder. При его смене голоса нужно зарегистрировать заново.") }
        item { Text("Локальные модели", style = MaterialTheme.typography.titleLarge) }
        items(vm.app.models.bundled) { m ->
            val file = vm.app.models.file(m)
            Card { Column(Modifier.padding(12.dp)) { Text(m); Text(if (file.exists()) "Подготовлена · ${file.length() / 1024 / 1024} МБ" else "Подготавливается") } }
        }
        item { TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Скрыть расширенные настройки" else "Расширенные настройки") } }
        if (advanced) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Экспериментальный каскад", style = MaterialTheme.typography.titleMedium)
                        Text("Редкие speaker-окна на длинных чистых монологах и дешёвый quality gate. Выключено по умолчанию.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = prefs.experimentalCascade, onCheckedChange = { if (!busy) prefs.experimentalCascade = it }, enabled = !busy)
                }
            }
            item { var language by remember { mutableStateOf(prefs.language) }; OutlinedTextField(language, { if (it == "ru" || it == "en" || it == "auto") { language = it; prefs.language = it } }, label = { Text("Язык: ru, en, auto") }); Row { listOf("ru", "en", "auto").forEach { lang -> TextButton(onClick = { language = lang; prefs.language = lang }) { Text(lang) } } } }
            item { var audioSource by remember { mutableStateOf(prefs.audioSource) }; Text("Источник микрофона", style = MaterialTheme.typography.titleMedium); Row { listOf("voice_recognition" to "Распознавание", "mic" to "MIC", "unprocessed" to "UNPROCESSED").forEach { (value, label) -> TextButton(onClick = { audioSource = value; prefs.audioSource = value }, enabled = !busy) { Text(if (audioSource == value) "✓ $label" else label) } } }; Text("Запись начинается заново после смены источника. Поддержка зависит от телефона.") }
            item { NumberSetting("Порог известного голоса", prefs.number("threshold", .78f).toFloat(), .4f.. .99f, busy) { prefs.setNumber("threshold", it) } }
            item { NumberSetting("Отрыв от второго кандидата", prefs.number("margin", .08f).toFloat(), 0f.. .3f, busy) { prefs.setNumber("margin", it) } }
            item { NumberSetting("Присоединение шумового окна", prefs.number("noise", .86f).toFloat(), .6f.. .99f, busy) { prefs.setNumber("noise", it) } }
            item { NumberSetting("Сходство для малой выборки", prefs.number("small", .92f).toFloat(), .7f.. .99f, busy) { prefs.setNumber("small", it) } }
            item { NumberSetting("Детекция смены голоса", prefs.number("change", .72f).toFloat(), .4f.. .95f, busy) { prefs.setNumber("change", it) } }
            item { NumberSetting("HDBSCAN: размер кластера", prefs.number("clusterSize", 3f).toFloat(), 2f..10f, busy, true) { prefs.setNumber("clusterSize", it) } }
            item { NumberSetting("HDBSCAN: min samples", prefs.number("minSamples", 2f).toFloat(), 1f..10f, busy, true) { prefs.setNumber("minSamples", it) } }
            item { NumberSetting("Потоки CPU", prefs.threads.toFloat(), 1f..8f, busy, true) { prefs.threads = it.toInt() } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}
@Composable private fun NumberSetting(title: String, initial: Float, range: ClosedFloatingPointRange<Float>, busy: Boolean, integer: Boolean = false, changed: (Float) -> Unit) {
    var value by remember { mutableFloatStateOf(initial) }
    Column { Text("$title: ${if (integer) value.toInt().toString() else "%.2f".format(value)}"); Slider(value, onValueChange = { value = if (integer) kotlin.math.round(it) else it }, onValueChangeFinished = { changed(value) }, valueRange = range, enabled = !busy) }
}
