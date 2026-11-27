package moe.n4tsu.agyremote.ui.workspace

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import moe.n4tsu.agyremote.R
import moe.n4tsu.agyremote.net.Attachment
import moe.n4tsu.agyremote.net.ChatState
import moe.n4tsu.agyremote.net.FileHit
import moe.n4tsu.agyremote.state.VersionProblem
import moe.n4tsu.agyremote.state.WorkspaceViewModel
import moe.n4tsu.agyremote.ui.chat.OpenInCliNotice
import moe.n4tsu.agyremote.ui.chat.PlanNotice
import moe.n4tsu.agyremote.ui.chat.VersionNotice
import moe.n4tsu.agyremote.ui.components.liquidSquish
import moe.n4tsu.agyremote.ui.components.Melt
import moe.n4tsu.agyremote.ui.components.glass
import moe.n4tsu.agyremote.ui.components.plainClickable
import moe.n4tsu.agyremote.ui.theme.AppType
import moe.n4tsu.agyremote.ui.theme.LocalAppColors
import moe.n4tsu.agyremote.ui.theme.Metrics
import moe.n4tsu.agyremote.ui.theme.Motion
import android.text.format.Formatter
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow as TO
import moe.n4tsu.agyremote.state.Haptics
import moe.n4tsu.agyremote.ui.chat.ModePill
import moe.n4tsu.agyremote.ui.chat.ThumbnailCache
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Focus and cursor state for the composer. Text and attachments live in the view model's shared
 * draft (vm.draft / vm.draftAttachments, as on iOS) once [bind] has run; this keeps the cursor.
 */
class ComposerState(vm: WorkspaceViewModel? = null) {
    private var vm: WorkspaceViewModel? = vm
    private val local = androidx.compose.runtime.mutableStateListOf<Attachment>()
    private var tfv by mutableStateOf(TextFieldValue(""))
    var focused by mutableStateOf(false)
    var typingTick by mutableStateOf(0)
    val focusRequester = FocusRequester()

    fun bind(model: WorkspaceViewModel) { if (vm !== model) vm = model }

    var value: TextFieldValue
        get() {
            val d = vm?.draft ?: return tfv
            return if (tfv.text == d) tfv else TextFieldValue(d, TextRange(d.length))
        }
        set(v) { tfv = v; vm?.let { if (it.draft != v.text) it.draft = v.text } }

    val attachments: androidx.compose.runtime.snapshots.SnapshotStateList<Attachment> get() = vm?.draftAttachments ?: local

    fun insert(text: String) {
        val v = value.text
        val sep = if (v.isEmpty() || v.endsWith(" ") || v.endsWith("\n")) "" else " "
        val nt = v + sep + text
        value = TextFieldValue(nt, TextRange(nt.length))
    }
}

/** A string resource by name if one has been added, else the English text. */
@android.annotation.SuppressLint("DiscouragedApi")
private fun pasteText(context: android.content.Context, name: String, english: String): String {
    val id = context.resources.getIdentifier(name, "string", context.packageName)
    return if (id != 0) context.getString(id) else english
}

/**
 * iOS pasteFromClipboard: an image becomes an attachment, text (trimmed) is appended to the draft
 * on a new line, and a toast says which happened.
 */
fun pasteFromClipboard(vm: WorkspaceViewModel, context: android.content.Context) {
    val clip = context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
    val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
    val uri = item?.uri
    val mime = uri?.let { context.contentResolver.getType(it) }
    if (uri != null && mime?.startsWith("image/") == true) {
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes != null) {
            vm.addAttachment(Attachment("image", "pasted.jpg", mime, android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), preview = bytes))
            vm.toast(pasteText(context, "attached_image_from_clipboard", "Attached image from clipboard"))
            return
        }
    }
    val text = item?.coerceToText(context)?.toString()?.trim().orEmpty()
    if (text.isNotEmpty()) {
        vm.draft = if (vm.draft.isEmpty()) text else vm.draft + "\n" + text
        vm.toast(pasteText(context, "pasted_from_clipboard", "Pasted from clipboard"))
    } else vm.toast(pasteText(context, "clipboard_is_empty", "Clipboard is empty"))
}

/** The `@word` being typed at the end of the draft (words split on any whitespace), without the `@`. */
private fun atQuery(draft: String): String? {
    val tail = draft.takeLastWhile { !it.isWhitespace() }
    return if (tail.startsWith("@")) tail.drop(1) else null
}

/**
 * Everything floating at the bottom (spec 6): notices, `@` file suggestions, slash commands or the
 * mode pill, and the input plate (attachments, +, field, mic, send/stop).
 *
 * [showModes] lets the composer own the mode pill the way iOS does (shown in an empty chat, or
 * while typing, and never while a turn runs or slash commands are listed).
 */
@Composable
fun Composer(vm: WorkspaceViewModel, state: ComposerState, onPlus: () -> Unit, modifier: Modifier = Modifier, showModes: Boolean = false) {
    val c = LocalAppColors.current
    val context = LocalContext.current
    val readOnly = vm.chatState == ChatState.OPEN_IN_CLI && vm.currentChatId != null
    state.bind(vm)
    val text = vm.draft
    val running = vm.isRunning
    val canSubmit = vm.canSend && !readOnly && vm.isChatAvailable
    val margin by animateDpAsState(
        (if (state.focused || canSubmit) Metrics.COMPOSER_MARGIN_FOCUSED else Metrics.COMPOSER_MARGIN_IDLE).dp, Motion.inputMorph(), label = "margin",
    )
    val quota = vm.quotaNoticeLimit
    val dictation = remember { Dictation() }

    // Typing by hand, a turn starting or another chat ends dictation.
    LaunchedEffect(text) { if (dictation.listening && dictation.dictated != null && text != dictation.dictated) dictation.cancel() }
    LaunchedEffect(running) { if (running) dictation.cancel() }
    LaunchedEffect(vm.currentChatId) { dictation.cancel() }

    // @ file search: debounced, cancelled as the query changes.
    val query = atQuery(text)
    var files by remember { mutableStateOf<List<FileHit>>(emptyList()) }
    LaunchedEffect(query) {
        if (query == null) { files = emptyList(); return@LaunchedEffect }
        delay(150)
        files = vm.searchFiles(query).take(6)
    }
    val commands = vm.matchingSlashCommands
    val modesVisible = showModes && !running && commands.isEmpty() && (vm.isEmptyChat || state.focused)

    Column(
        modifier.fillMaxWidth().padding(top = 4.dp, bottom = Metrics.COMPOSER_BOTTOM.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Melt(vm.versionProblem != null) {
            VersionNotice(Icons.Outlined.SystemUpdate, stringResource(if (vm.versionProblem == VersionProblem.UPDATE_APP) R.string.protocol_update_app else R.string.protocol_update_connector))
        }
        val livePlan = vm.messages.lastOrNull()?.takeIf { it.live }?.plan.orEmpty()
        Melt(livePlan.isNotEmpty()) { PlanNotice(livePlan) }
        Melt(readOnly) { OpenInCliNotice(vm.selectedDevice?.name.orEmpty()) }
        Melt(quota != null) {
            SwipeToDismiss(onDismiss = { quota?.let { vm.dismissQuotaNotice(it) } }) {
                VersionNotice(Icons.Outlined.Speed, stringResource(R.string.low_limit_x, quota?.remaining?.toInt() ?: 0))
            }
        }
        Melt(query != null && files.isNotEmpty()) {
            FileSuggestions(files, Modifier.padding(horizontal = margin)) { f ->
                val draft = text.dropLastWhile { !it.isWhitespace() } + "@" + f.path + " "
                state.value = TextFieldValue(draft, TextRange(draft.length))
                vm.addAttachment(Attachment("file", f.path.substringAfterLast('/'), path = f.path))
                files = emptyList()
            }
        }
        AnimatedVisibility(commands.isNotEmpty(), enter = fadeIn() + scaleIn(initialScale = 0.94f), exit = fadeOut() + scaleOut(targetScale = 0.94f)) {
            SlashBar(commands.map { it.name to it.description }) { name ->
                commands.firstOrNull { it.name == name }?.let { vm.insertSlashCommand(it) }
            }
        }
        AnimatedVisibility(modesVisible, enter = fadeIn() + scaleIn(initialScale = 0.94f), exit = fadeOut() + scaleOut(targetScale = 0.94f)) {
            ModePillFor(vm)
        }

        // The plate.
        Column(
            Modifier.padding(horizontal = margin).widthIn(max = Metrics.COMPOSER_MAX_WIDTH.dp).fillMaxWidth()
                .heightIn(min = Metrics.COMPOSER_HEIGHT.dp)
                .glass(RoundedCornerShape((Metrics.COMPOSER_HEIGHT / 2).dp))
                .animateContentSize(Motion.inputMorph()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.attachments.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.attachments.forEach { a -> androidx.compose.runtime.key(a.id) { DraftAttachment(a) { vm.removeAttachment(a) } } }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = if (state.attachments.isEmpty()) 9.dp else 2.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(34.dp, 38.dp).liquidSquish().semantics { contentDescription = context.getString(R.string.add) }
                        .plainClickable { Haptics.light(context); onPlus() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Add, null, Modifier.size(24.dp), tint = c.onSurface.copy(alpha = 0.92f)) }
                Box(Modifier.weight(1f).heightIn(min = 38.dp), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        val folder = vm.folderName
                        Text(
                            if (folder != null) stringResource(R.string.ask_in_x, folder) else stringResource(R.string.ask_agy),
                            style = AppType.body.copy(color = c.onSurfaceVariant.copy(alpha = 0.88f)), maxLines = 1, overflow = TO.Ellipsis,
                        )
                    }
                    BasicTextField(
                        state.value,
                        { v -> if (v.text != state.value.text) state.typingTick++; state.value = v },
                        Modifier.fillMaxWidth().padding(vertical = 6.dp).focusRequester(state.focusRequester).onFocusChanged { state.focused = it.isFocused },
                        enabled = !readOnly,
                        textStyle = AppType.body.copy(color = c.onSurface),
                        cursorBrush = SolidColor(c.primary),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                }
                // The mic stays while dictating so it can always be stopped.
                AnimatedVisibility(!running || dictation.listening, enter = fadeIn() + scaleIn(initialScale = 0.85f), exit = fadeOut() + scaleOut(targetScale = 0.85f)) {
                    MicButton(state, dictation)
                }
                SendButton(
                    running = running, enabled = canSubmit,
                    onSend = {
                        dictation.stop()
                        Haptics.light(context)
                        if (vm.send()) { vm.draft = ""; vm.draftAttachments.clear() }
                    },
                    onStop = { vm.stop() },
                )
            }
        }
    }
}

@Composable
private fun SendButton(running: Boolean, enabled: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val c = LocalAppColors.current
    val fill by androidx.compose.animation.animateColorAsState(
        when { running -> c.stopButtonFill; enabled -> c.accentFill; else -> c.accentFillMuted }, tween(220), label = "fill",
    )
    val desc = stringResource(if (running) R.string.stop else R.string.send)
    Box(
        Modifier.size(38.dp).liquidSquish().background(fill, CircleShape).semantics { contentDescription = desc }
            .plainClickable { if (running) onStop() else if (enabled) onSend() },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(running, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.7f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.7f)) }, label = "send") { r ->
            if (r) Box(Modifier.size(11.dp).background(c.onSurface, RoundedCornerShape(2.5.dp)))
            else Icon(Icons.Filled.ArrowUpward, null, Modifier.size(18.dp), tint = if (enabled) c.onAccentFill else c.onSurfaceVariant)
        }
    }
}

/** Speech-to-text into the draft. [dictated] is what dictation last wrote, to tell typing apart. */
private class Dictation {
    var listening by mutableStateOf(false)
    var level by mutableFloatStateOf(0f)
    var dictated: String? = null
    var recognizer: SpeechRecognizer? = null

    fun stop() { if (listening) recognizer?.stopListening(); listening = false; level = 0f; dictated = null }
    fun cancel() { if (listening) recognizer?.cancel(); listening = false; level = 0f; dictated = null }
}

@Composable
private fun MicButton(state: ComposerState, d: Dictation) {
    val c = LocalAppColors.current
    val context = LocalContext.current
    val recognizer = remember { if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null }
    DisposableEffect(recognizer) { d.recognizer = recognizer; onDispose { recognizer?.destroy(); d.recognizer = null } }

    fun start() {
        val r = recognizer ?: return
        val base = state.value.text
        d.dictated = null
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { d.listening = true; Haptics.light(context) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { d.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { d.listening = false; d.level = 0f }
            override fun onResults(results: Bundle?) { apply(results); d.listening = false; d.level = 0f; d.dictated = null }
            override fun onPartialResults(partial: Bundle?) { apply(partial) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            private fun apply(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return
                val sep = if (base.isEmpty() || base.last().isWhitespace()) "" else " "
                val nt = base + sep + t
                d.dictated = nt
                state.value = TextFieldValue(nt, TextRange(nt.length))
                state.typingTick++
            }
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) start() }
    if (recognizer == null) return
    val live = d.listening
    val ringAlpha by animateFloatAsState(0.22f + d.level * 0.35f, tween(100), label = "ring")
    Box(
        Modifier.size(34.dp, 38.dp).liquidSquish().semantics { contentDescription = context.getString(R.string.dictate) }.plainClickable {
            if (live) { d.stop(); Haptics.light(context); return@plainClickable }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        },
        contentAlignment = Alignment.Center,
    ) {
        if (live) Box(Modifier.size(34.dp).background(c.errorRed.copy(alpha = ringAlpha), CircleShape))
        Icon(if (live) Icons.Filled.Mic else Icons.Outlined.MicNone, null, Modifier.size(20.dp), tint = if (live) c.errorRed else c.onSurface.copy(alpha = 0.88f))
    }
}

/** One item waiting in the composer: an image thumbnail, an `@` capsule or a phone-file card. */
@Composable
private fun DraftAttachment(a: Attachment, onRemove: () -> Unit) {
    val c = LocalAppColors.current
    val context = LocalContext.current
    when {
        a.kind == "image" -> Box {
            val bmp = remember(a.id) { ThumbnailCache.get(a) }
            Box(Modifier.padding(top = 4.dp, end = 4.dp).size(58.dp).clip(RoundedCornerShape(13.dp)).background(c.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                if (bmp != null) Image(bmp.asImageBitmap(), a.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Outlined.Photo, null, Modifier.size(20.dp), tint = c.onSurfaceVariant)
            }
            RemoveButton(Modifier.align(Alignment.TopEnd), onRemove)
        }
        a.path != null -> Row(
            Modifier.background(c.surfaceContainerHigh, CircleShape).padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.AlternateEmail, null, Modifier.size(13.dp), tint = c.primary)
            Text(a.path, style = AppType.mono(12f, FontWeight.Medium, c.onSurface), maxLines = 1, overflow = TO.MiddleEllipsis, modifier = Modifier.widthIn(max = 200.dp))
            RemoveButton(Modifier, onRemove)
        }
        else -> Row(
            Modifier.background(c.surfaceContainerHigh, RoundedCornerShape(13.dp)).padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Description, null, Modifier.size(15.dp), tint = c.connectedGreen)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(a.name, style = AppType.sans(12.5f, FontWeight.Medium, c.onSurface), maxLines = 1, overflow = TO.MiddleEllipsis, modifier = Modifier.widthIn(max = 160.dp))
                val size = a.bytes?.size?.toLong() ?: a.data?.let { it.length * 3L / 4 } ?: 0L
                Text(Formatter.formatShortFileSize(context, size), style = AppType.sans(10.5f, color = c.onSurfaceMuted))
            }
            RemoveButton(Modifier, onRemove)
        }
    }
}

@Composable
private fun RemoveButton(modifier: Modifier, onRemove: () -> Unit) {
    val desc = stringResource(R.string.remove)
    Box(modifier.size(18.dp).background(Color.Black.copy(alpha = 0.75f), CircleShape).semantics { contentDescription = desc }.plainClickable(onClick = onRemove), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Close, null, Modifier.size(11.dp), tint = Color.White)
    }
}

/** Swipe sideways to dismiss (low-limit notice). */
@Composable
private fun SwipeToDismiss(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    var dx by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.offset { IntOffset(dx.roundToInt(), 0) }.alpha(1f - (abs(dx) / 400f).coerceIn(0f, 0.9f))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (abs(dx) > 120f) onDismiss() else dx = 0f },
                    onDragCancel = { dx = 0f },
                ) { _, d -> dx += d }
            },
    ) { content() }
}

/** Slash commands as a row of glass capsules above the plate. */
@Composable
private fun SlashBar(commands: List<Pair<String, String?>>, onPick: (String) -> Unit) {
    val c = LocalAppColors.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        commands.forEach { (name, description) ->
            Row(
                Modifier.height(30.dp).glass(CircleShape).plainClickable { onPick(name) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("/$name", style = AppType.mono(13f, FontWeight.SemiBold, c.primary), maxLines = 1)
                if (!description.isNullOrEmpty()) {
                    Text(description, style = AppType.sans(12f, color = c.onSurfaceVariant), maxLines = 1, overflow = TO.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                }
            }
        }
    }
}

/** Up to six matching files: name over its path (cut at the head). */
@Composable
private fun FileSuggestions(files: List<FileHit>, modifier: Modifier, onPick: (FileHit) -> Unit) {
    val c = LocalAppColors.current
    Column(
        modifier.widthIn(max = Metrics.COMPOSER_MAX_WIDTH.dp).fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(vertical = 6.dp),
    ) {
        files.forEach { f ->
            androidx.compose.runtime.key(f.path) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).plainClickable { onPick(f) }.padding(horizontal = 16.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                ) {
                    Text(f.path.substringAfterLast('/'), style = AppType.sans(14f, FontWeight.Medium, c.onSurface), maxLines = 1, overflow = TO.Ellipsis)
                    Text(f.path, style = AppType.mono(11.5f, color = c.onSurfaceVariant), maxLines = 1, overflow = TO.StartEllipsis)
                }
            }
        }
    }
}

/** Reads the mode inside its own scope, so a mode change recomposes only the pill, not the composer. */
@Composable
private fun ModePillFor(vm: WorkspaceViewModel) {
    val onSelect = remember(vm) { { id: String -> vm.selectMode(id) } }
    ModePill(vm.modes.ifEmpty { defaultModes() }, vm.currentModeId, onSelect)
}
