package moe.n4tsu.agyremote.ui.sheets

import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.n4tsu.agyremote.R
import moe.n4tsu.agyremote.net.Attachment
import moe.n4tsu.agyremote.net.FileHit
import moe.n4tsu.agyremote.net.Limit
import moe.n4tsu.agyremote.state.DeviceStatus
import moe.n4tsu.agyremote.state.WorkspaceViewModel
import moe.n4tsu.agyremote.ui.components.ComputerRing
import moe.n4tsu.agyremote.ui.components.plainClickable
import moe.n4tsu.agyremote.ui.theme.AppType
import moe.n4tsu.agyremote.ui.theme.LocalAppColors
import moe.n4tsu.agyremote.ui.workspace.ComposerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.math.roundToInt

// MARK: - Model picker

/** "Select model": the first models with a checkmark, an Effort row, and "More models". */
@Composable
fun ModelPickerSheet(vm: WorkspaceViewModel, onDismiss: () -> Unit) {
    val c = LocalAppColors.current
    var page by remember { mutableStateOf("main") }
    val primary = vm.models.take(4).toMutableList().apply { vm.selectedModel?.let { if (it !in this) add(it) } }
    val more = vm.models.filterNot { it in primary }
    AppSheet(onDismiss) {
        val closeSheet = rememberCloseSheet(onDismiss)
        androidx.activity.compose.BackHandler(enabled = page != "main") { page = "main" }
        SheetPages(page, { it == "main" }) { shown -> when (shown) {
            "main" -> {
                SheetBar(stringResource(R.string.select_model), onClose = onDismiss)
                Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Group(primary.map { m -> { CheckRow(m.name, m.id == vm.selectedModel?.id) { vm.selectModel(m); closeSheet() } } }, inset = 20.dp)
                    if (!vm.selectedModel?.efforts.isNullOrEmpty()) {
                        Group(listOf { SheetRow(stringResource(R.string.effort), value = vm.selectedEffort?.name, chevron = true, onClick = { page = "effort" }) })
                    }
                    if (more.isNotEmpty()) Group(listOf { SheetRow(stringResource(R.string.more_models), chevron = true, onClick = { page = "more" }) })
                }
            }
            "effort" -> {
                SheetBar(stringResource(R.string.effort), onBack = { page = "main" })
                Column(Modifier.padding(top = 12.dp, bottom = 24.dp).navigationBarsPadding()) {
                    Group(vm.selectedModel?.efforts.orEmpty().map { e -> { CheckRow(e.name, e.id == vm.selectedEffort?.id) { vm.selectEffort(e.id) } } }, inset = 20.dp)
                    SectionFooter(stringResource(R.string.higher_effort_means_more_thorough_responses_but))
                }
            }
            else -> {
                SheetBar(stringResource(R.string.more_models), onBack = { page = "main" })
                Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 24.dp).navigationBarsPadding()) {
                    Group(more.map { m -> { CheckRow(m.name, m.id == vm.selectedModel?.id) { vm.selectModel(m); closeSheet() } } }, inset = 20.dp)
                }
            }
        } }
    }
}

@Composable
fun CheckRow(title: String, checked: Boolean, onClick: () -> Unit) {
    val c = LocalAppColors.current
    SheetRow(title, onClick = onClick) {
        if (checked) Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = c.primary)
    }
}

// MARK: - Usage

/** Context-window use of the open chat plus the plan limits. */
@Composable
fun UsageSheet(vm: WorkspaceViewModel, onDismiss: () -> Unit) {
    val c = LocalAppColors.current
    LaunchedEffect(Unit) { vm.refreshLimits(true) }
    AppSheet(onDismiss) {
        SheetBar(stringResource(R.string.usage), onDone = onDismiss)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 28.dp).navigationBarsPadding()) {
            SectionHeader(stringResource(R.string.context))
            val ctx = vm.context
            if (ctx != null) {
                Group(listOf {
                    UsageBar(stringResource(R.string.used), percent(ctx.fraction * 100.0), ctx.fraction,
                        when { ctx.fraction > 0.9f -> c.errorRed; ctx.fraction > 0.75f -> c.warningAmber; else -> c.primary },
                        stringResource(R.string.x_of_x_tokens, compact(ctx.used), compact(ctx.size)))
                })
                SectionFooter(stringResource(R.string.agy_reports_the_total_only_for_a_breakdown_by_sy))
            } else {
                Group(listOf { Text(stringResource(R.string.open_a_chat_to_see_how_much_of_the_context_windo), style = AppType.sans(16f, color = c.onSurfaceVariant), modifier = Modifier.padding(20.dp)) })
            }
            SectionHeader(stringResource(R.string.plan_limits_remaining))
            val limits = vm.limits
            if (limits != null && limits.limits.isNotEmpty()) {
                Group(inset = 20.dp, rows = limits.limits.map { l -> @Composable { LimitRow(l) } } + listOfNotNull(limits.credits?.let { cr -> @Composable { SheetRow(stringResource(R.string.ai_credits), value = "%.2f".format(cr).trimEnd('0').trimEnd('.')) } }))
                SectionFooter(stringResource(R.string.from_agy_on_your_computer_updated_x, relative(limits.fetched)))
            } else {
                Group(listOf {
                    Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.onSurfaceVariant)
                        Text(stringResource(R.string.checking_your_limits), style = AppType.sans(16f, color = c.onSurfaceVariant))
                    }
                })
            }
        }
    }
}

@Composable
private fun LimitRow(l: Limit) {
    val c = LocalAppColors.current
    val f = (l.remaining / 100.0).toFloat().coerceIn(0f, 1f)
    val tint = when { l.remaining < 10 -> c.errorRed; l.remaining < 25 -> c.warningAmber; else -> c.primary }
    UsageBar(if (l.group.isEmpty()) l.label else "${l.group} · ${l.label}", percent(l.remaining), f, tint,
        l.resets?.let { stringResource(R.string.resets_x, resetsIn(it)) })
}

/** Locale-aware whole percent (iOS QuotaFormat.localePercent). */
private fun percent(v: Double): String = java.text.NumberFormat.getPercentInstance().apply { maximumFractionDigits = 0 }.format(v / 100.0)

/** "in 5d 3h", "in 2h 24m", "in 12m", or "now" (iOS QuotaFormat.resets). */
@Composable
private fun resetsIn(iso: String): String {
    val left = runCatching { Duration.between(java.time.Instant.now(), OffsetDateTime.parse(iso).toInstant()).seconds }.getOrNull() ?: return iso
    if (left <= 0) return stringResource(R.string.now)
    val m = maxOf(left, 60) / 60
    val d = m / 1440; val h = (m % 1440) / 60; val mm = m % 60
    val text = when { d > 0 -> if (h > 0) "${d}d ${h}h" else "${d}d"; h > 0 -> if (mm > 0) "${h}h ${mm}m" else "${h}h"; else -> "${mm}m" }
    return stringResource(R.string.in_x, text)
}

@Composable
private fun UsageBar(title: String, value: String, fraction: Float, tint: Color, caption: String?) {
    val c = LocalAppColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row {
            Text(title, style = AppType.sans(15f, color = c.onSurface), modifier = Modifier.weight(1f))
            Text(value, style = AppType.sans(15f, FontWeight.Medium, c.onSurface).copy(fontFeatureSettings = "tnum"))
        }
        Box(Modifier.fillMaxWidth().height(4.dp).background(c.onSurface.copy(alpha = 0.08f), CircleShape)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).widthIn(min = 4.dp).background(tint, CircleShape))
        }
        if (!caption.isNullOrEmpty()) Text(caption, style = AppType.sans(12.5f, color = c.onSurfaceMuted))
    }
}

private fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).replace(".0M", "M")
    n >= 1_000 -> "${(n / 1000.0).roundToInt()}K"
    else -> n.toString()
}

private fun relative(iso: String): String = runCatching {
    val d = Duration.between(OffsetDateTime.parse(iso).toInstant(), java.time.Instant.now())
    android.text.format.DateUtils.getRelativeTimeSpanString(System.currentTimeMillis() - d.toMillis()).toString()
}.getOrDefault(iso)

// MARK: - Add to Agy

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddSheet(vm: WorkspaceViewModel, composer: ComposerState, onDismiss: () -> Unit) {
    val c = LocalAppColors.current
    val context = LocalContext.current
    var page by remember { mutableStateOf("main") }

    fun attachImage(bytes: ByteArray, name: String, mime: String) {
        composer.attachments.add(Attachment("image", name, mime, Base64.encodeToString(bytes, Base64.NO_WRAP)))
        onDismiss()
    }
    fun attachUri(uri: Uri?) {
        uri ?: return
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        if (!mime.startsWith("image/")) { vm.toast(context.getString(R.string.error_generic)); return }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
        attachImage(bytes, uri.lastPathSegment?.substringAfterLast('/') ?: "image", mime)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp: Bitmap? ->
        bmp ?: return@rememberLauncherForActivityResult
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        attachImage(out.toByteArray(), "photo.jpg", "image/jpeg")
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { attachUri(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { attachUri(it) }

    AppSheet(onDismiss, large = false) {
        val closeSheet = rememberCloseSheet(onDismiss)
        androidx.activity.compose.BackHandler(enabled = page != "main") { page = "main" }
        SheetPages(page, { it == "main" }) { shown -> when (shown) {
            "files" -> FileListPage(vm, composer, onBack = { page = "main" }, onDone = onDismiss)
            "commands" -> CommandListPage(vm, composer, onBack = { page = "main" }, onDone = onDismiss)
            else -> {
                SheetBar(stringResource(R.string.add_to_agy), onDone = onDismiss)
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 10.dp, bottom = 24.dp).navigationBarsPadding()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile(Icons.Filled.CameraAlt, c.ramp[1], stringResource(R.string.camera), Modifier.weight(1f)) { camera.launch(null) }
                        Tile(Icons.Filled.PhotoLibrary, c.ramp[2], stringResource(R.string.photos), Modifier.weight(1f)) {
                            photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        Tile(Icons.Filled.Folder, c.connectedGreen, stringResource(R.string.files), Modifier.weight(1f)) { files.launch("*/*") }
                        Tile(Icons.Filled.ContentPaste, c.warningAmber, stringResource(R.string.paste), Modifier.weight(1f)) {
                            moe.n4tsu.agyremote.ui.workspace.pasteFromClipboard(vm, context)
                            closeSheet()
                        }
                    }
                    Text(stringResource(R.string.agy_context_tools), style = AppType.sans(14f, FontWeight.Medium, c.onSurfaceVariant), modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 10.dp))
                    ToolRow(Icons.Outlined.AlternateEmail, c.primary, stringResource(R.string.workspace_file_context), vm.folderName.takeUnless { it.isNullOrEmpty() }?.let { stringResource(R.string.attach_files_from_x, it) } ?: stringResource(R.string.attach_files_from_your_computer)) { page = "files" }
                    Spacer(Modifier.height(8.dp))
                    val n = vm.info?.commands?.size ?: 0
                    ToolRow(Icons.Outlined.Edit, Color(0xFF8F6BFF), stringResource(R.string.slash_commands_skills), stringResource(R.string.x_commands_from_agy, n)) { page = "commands" }
                    Text(stringResource(R.string.quick_commands), style = AppType.sans(14f, FontWeight.Medium, c.onSurfaceVariant), modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 10.dp))
                    val quick = (listOf("goal", "compact", "schedule", "browser") + vm.info?.commands.orEmpty().map { it.name }).distinct().take(10)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        quick.forEach { q ->
                            Text("/$q", style = AppType.mono(14f, FontWeight.Medium, c.onSurface),
                                modifier = Modifier.background(c.surfaceContainerHigh, CircleShape).plainClickable { composer.insert("/$q "); closeSheet() }.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                }
            }
        } }
    }
}

@Composable
private fun Tile(icon: ImageVector, tint: Color, label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Column(
        modifier.height(74.dp).background(SheetColors.card, RoundedCornerShape(18.dp)).plainClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Text(label, style = AppType.sans(12.5f, FontWeight.Medium, c.onSurface))
    }
}

@Composable
private fun ToolRow(icon: ImageVector, tint: Color, title: String, subtitle: String, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().background(SheetColors.card, RoundedCornerShape(16.dp)).plainClickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(36.dp).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(19.dp), tint = tint) }
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.sans(16.5f, FontWeight.Medium, c.onSurface))
            Text(subtitle, style = AppType.sans(13f, color = c.onSurfaceVariant), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp), tint = c.onSurfaceMuted)
    }
}

@Composable
internal fun SearchField(query: String, placeholder: String, onChange: (String) -> Unit) {
    val c = LocalAppColors.current
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().height(44.dp).background(c.onSurface.copy(alpha = 0.07f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = c.onSurfaceVariant)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(placeholder, style = AppType.sans(16f, color = c.onSurfaceVariant))
            BasicTextField(query, onChange, singleLine = true, textStyle = AppType.sans(16f, color = c.onSurface), cursorBrush = SolidColor(c.primary), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FileListPage(vm: WorkspaceViewModel, composer: ComposerState, onBack: () -> Unit, onDone: () -> Unit) {
    val c = LocalAppColors.current
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<FileHit>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(query) {
        if (query.isNotEmpty()) delay(160)
        busy = true
        val found = vm.searchFiles(query)
        hits = found
        busy = false
    }
    val folder = vm.folderName
    SheetBar(if (folder.isNullOrEmpty()) stringResource(R.string.workspace_files) else "@ $folder", onBack = onBack, onDone = onDone)
    SearchField(query, stringResource(R.string.filter_files)) { query = it }
    Box(Modifier.fillMaxWidth().height(460.dp)) {
        when {
            hits.isEmpty() && busy -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), color = c.onSurfaceVariant, strokeWidth = 2.dp)
                val device = vm.selectedDevice?.name
                Text(
                    if (device.isNullOrEmpty()) stringResource(R.string.loading_workspace_files) else stringResource(R.string.loading_workspace_files_from_x, device),
                    style = AppType.sans(15f, color = c.onSurfaceVariant), textAlign = TextAlign.Center,
                )
            }
            hits.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Search, null, Modifier.size(34.dp), tint = c.onSurfaceMuted)
                Text(stringResource(R.string.no_matching_files), style = AppType.sans(17f, FontWeight.SemiBold, c.onSurface))
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(hits, key = { it.path }) { f ->
                    val attached = composer.attachments.any { it.path == f.path }
                    Row(
                        Modifier.fillMaxWidth().plainClickable {
                            if (attached) composer.attachments.removeAll { it.path == f.path }
                            else composer.attachments.add(Attachment("file", f.path.substringAfterLast('/'), path = f.path))
                        }.padding(horizontal = 24.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                            Icon(fileGlyph(f.path), null, Modifier.size(16.dp), tint = c.primary)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(f.path.substringAfterLast('/'), style = AppType.sans(15f, FontWeight.Medium, c.onSurface), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(f.path, style = AppType.mono(12f, color = c.onSurfaceVariant), maxLines = 1, overflow = TextOverflow.StartEllipsis)
                        }
                        if (attached) Icon(Icons.Filled.CheckCircle, null, Modifier.padding(start = 8.dp).size(19.dp), tint = c.connectedGreen)
                    }
                }
            }
        }
    }
}

/** Icon for a workspace file by its extension (code, image, config, text, other). */
private val pictureExts = "webp svg heic gif jpeg jpg png".split(' ').toSet()
private val configExts = "plist toml yaml yml json".split(' ').toSet()
private val proseExts = "rtf txt md".split(' ').toSet()

private fun fileGlyph(path: String): ImageVector {
    val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    return when {
        ext in pictureExts -> Icons.Outlined.Image
        ext in configExts -> Icons.Outlined.DataObject
        ext in proseExts -> Icons.AutoMirrored.Outlined.Article
        ext == "swift" -> Icons.Outlined.Code
        else -> Icons.Outlined.Description
    }
}

@Composable
private fun CommandListPage(vm: WorkspaceViewModel, composer: ComposerState, onBack: () -> Unit, onDone: () -> Unit) {
    val c = LocalAppColors.current
    var query by remember { mutableStateOf("") }
    val needle = query.trim()
    val cmds = vm.info?.commands.orEmpty().filter { needle.isEmpty() || it.name.contains(needle, true) || it.description?.contains(needle, true) == true }
    SheetBar(stringResource(R.string.slash_commands_skills), onBack = onBack, onDone = onDone)
    SearchField(query, stringResource(R.string.search_commands_skills)) { query = it }
    LazyColumn(Modifier.fillMaxWidth().height(460.dp)) {
        items(cmds, key = { it.name }) { cmd ->
            Column(Modifier.fillMaxWidth().plainClickable { composer.insert("/${cmd.name} "); onDone() }.padding(horizontal = 24.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("/" + cmd.name, style = AppType.mono(15.5f, FontWeight.SemiBold, c.primary))
                cmd.description?.takeIf { it.isNotEmpty() }?.let { Text(it, style = AppType.sans(13f, color = c.onSurfaceVariant), maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

// MARK: - Computers

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ComputerPickerSheet(vm: WorkspaceViewModel, onDismiss: () -> Unit, onScan: () -> Unit) {
    val c = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    AppSheet(onDismiss) {
        val closeSheet = rememberCloseSheet(onDismiss)
        Box(Modifier.fillMaxWidth()) {
            SheetBar(stringResource(R.string.connectors), onDone = onDismiss)
            moe.n4tsu.agyremote.ui.components.GlassCircleButton(40.dp, { vm.refreshActiveConnector() }, Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                contentDescription = stringResource(R.string.refresh)) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(19.dp), tint = c.onSurface)
            }
        }
        Box {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp).navigationBarsPadding()) {
            if (vm.devices.isNotEmpty()) {
                SectionHeader(stringResource(R.string.connected_hosts))
                Group(vm.devices.map { d ->
                    {
                        val state = androidx.compose.material3.rememberSwipeToDismissBoxState()
                        var confirming by remember { mutableStateOf(false) }
                        val scope = rememberCoroutineScope()
                        LaunchedEffect(state.currentValue) {
                            if (state.currentValue == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) confirming = true
                        }
                        if (confirming) {
                            val cancel = { confirming = false; scope.launch { state.reset() }; Unit }
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = cancel,
                                title = { Text(stringResource(R.string.unpair_confirm_title, d.name)) },
                                text = { Text(stringResource(R.string.unpair_confirm_message)) },
                                confirmButton = {
                                    androidx.compose.material3.TextButton({ confirming = false; vm.forgetDevice(d.id) }) {
                                        Text(stringResource(R.string.unpair), color = c.errorRed)
                                    }
                                },
                                dismissButton = { androidx.compose.material3.TextButton(cancel) { Text(stringResource(R.string.cancel)) } },
                            )
                        }
                        androidx.compose.material3.SwipeToDismissBox(
                            state, enableDismissFromStartToEnd = false,
                            backgroundContent = {
                                Box(Modifier.fillMaxSize().background(c.errorRed).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp), tint = Color.White)
                                        Text(stringResource(R.string.unpair), style = AppType.sans(15f, FontWeight.SemiBold, Color.White))
                                    }
                                }
                            },
                        ) {
                            Box(Modifier.background(SheetColors.card)) { HostRow(vm, d) { vm.selectDevice(d.id); closeSheet() } }
                        }
                    }
                })
            }
            SectionHeader(stringResource(R.string.pair_connector))
            Group(listOf(
                {
                    val hideThen = LocalHideSheetThen.current
                    SheetRow(stringResource(R.string.scan_qr_code), Icons.Outlined.QrCodeScanner, titleColor = c.primary, onClick = { hideThen?.invoke(onScan) ?: onScan() })
                },
                {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (code.isEmpty()) Text("agy-rem://pair?…", style = AppType.mono(13.5f, color = c.onSurfaceMuted), maxLines = 1)
                            BasicTextField(code, { code = it }, singleLine = true, textStyle = AppType.mono(13.5f, color = c.onSurface), cursorBrush = SolidColor(c.primary),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false, imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = {
                                    val v = code.trim()
                                    if (v.isNotEmpty()) { code = ""; scope.launchPair(vm, v, onDismiss) }
                                }))
                        }
                        Text(stringResource(if (code.isEmpty()) R.string.paste else R.string.pair), style = AppType.sans(13f, FontWeight.SemiBold, c.primary), modifier = Modifier.padding(start = 10.dp).plainClickable {
                            if (code.isEmpty()) code = context.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            else { val v = code.trim(); code = ""; scope.launchPair(vm, v, onDismiss) }
                        })
                    }
                },
            ))
        }
        }
    }
}

/** Device glyph, name with a status dot, hardware · agy version, and transport · latency or a short id. */
@Composable
private fun HostRow(vm: WorkspaceViewModel, d: moe.n4tsu.agyremote.state.PairedDevice, onClick: () -> Unit) {
    val c = LocalAppColors.current
    val current = d.id == vm.selectedDevice?.id
    val rt = vm.runtime(d.id)
    val online = rt.status == DeviceStatus.CONNECTED
    val dot = when (rt.status) { DeviceStatus.CONNECTED -> c.connectedGreen; DeviceStatus.STANDBY -> c.warningAmber; DeviceStatus.OFFLINE -> c.onSurfaceMuted }
    Row(Modifier.fillMaxWidth().plainClickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(moe.n4tsu.agyremote.ui.components.deviceIcon(d.hardwareModel), null, Modifier.width(32.dp).size(22.dp), tint = if (current) c.primary else c.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(d.name, style = AppType.sans(16f, FontWeight.SemiBold, c.onSurface), maxLines = 1)
                if (d.isDemo) DemoBadge()
                Box(Modifier.size(7.dp).background(dot, CircleShape))
            }
            val details = listOf(d.hardwareModel, rt.agentVersion.takeIf { it.isNotEmpty() }?.let { "agy $it" }.orEmpty()).filter { it.isNotEmpty() }
            if (details.isNotEmpty()) Text(details.joinToString(" · "), style = AppType.sans(13f, color = c.onSurfaceVariant), maxLines = 1)
            val addr = if (d.id.length > 16) d.id.take(8) + "…" + d.id.takeLast(6) else d.id
            Text(if (online) "${transportLabel(rt.transport)} · ${rt.latencyMs}ms" else addr, style = AppType.mono(11.5f, color = c.onSurfaceMuted), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        if (current) Icon(Icons.Filled.CheckCircle, null, Modifier.size(22.dp), tint = c.primary)
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchPair(vm: WorkspaceViewModel, code: String, done: () -> Unit) {
    launch { if (vm.pair(code)) done() }
}

// MARK: - Search

/** Chats, folders and models matching the query (iOS globalSearchResults, without message text). */
@Composable
fun SearchSheet(vm: WorkspaceViewModel, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val c = LocalAppColors.current
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val chats = vm.chats.filter { q.isEmpty() || it.title.contains(q, true) }
    val folders = if (q.isEmpty()) emptyList() else vm.folders.filter { it.name.contains(q, true) || it.path.contains(q, true) }
    val models = if (q.isEmpty()) emptyList() else vm.models.filter { it.name.contains(q, true) || it.id.contains(q, true) }
    val untitled = stringResource(R.string.untitled_chat)
    AppSheet(onDismiss) {
        val closeSheet = rememberCloseSheet(onDismiss)
        SheetBar(stringResource(R.string.search), onDone = onDismiss)
        SearchField(query, stringResource(R.string.search)) { query = it }
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.85f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            if (chats.isEmpty() && folders.isEmpty() && models.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(40.dp), tint = c.onSurfaceMuted)
                    Text(stringResource(R.string.no_matches), style = AppType.sans(20f, FontWeight.Bold, c.onSurface))
                }
            }
            items(chats, key = { "c" + it.id }) { chat ->
                val folder = chat.folder?.let { p -> vm.folders.firstOrNull { it.path == p }?.name ?: p.substringAfterLast('/') }.orEmpty()
                SearchLine(Icons.Outlined.ChatBubbleOutline, chat.title.ifEmpty { untitled }, folder) { vm.hideSidebar(); onOpen(chat.id) }
            }
            items(folders, key = { "f" + it.host + it.path }) { f ->
                SearchLine(Icons.Outlined.Folder, f.name, f.path) { vm.hideSidebar(); vm.selectFolder(f); closeSheet() }
            }
            items(models, key = { "m" + it.id }) { m ->
                SearchLine(Icons.Outlined.AutoAwesome, m.name, m.id, mono = true) { vm.selectModel(m); closeSheet() }
            }
        }
    }
}

@Composable
private fun SearchLine(icon: ImageVector, title: String, detail: String, mono: Boolean = false, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Row(Modifier.fillMaxWidth().plainClickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.width(24.dp).size(20.dp), tint = c.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AppType.sans(15f, FontWeight.Medium, c.onSurface), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = if (mono) AppType.mono(12f, color = c.onSurfaceVariant) else AppType.sans(12f, color = c.onSurfaceVariant), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// MARK: - Folder picker for CLI chats

@Composable
fun FolderPickerSheet(vm: WorkspaceViewModel, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = LocalAppColors.current
    AppSheet(onDismiss) {
        SheetBar(stringResource(R.string.choose_a_folder), onClose = onDismiss)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 24.dp).navigationBarsPadding()) {
            Group(vm.folders.distinctBy { it.path }.map { f ->
                {
                    Column(Modifier.fillMaxWidth().plainClickable { onPick(f.path) }.padding(horizontal = 20.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(f.name, style = AppType.sans(17f, color = c.onSurface))
                        Text(f.path, style = AppType.sans(13f, color = c.onSurfaceVariant), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                }
            })
            SectionFooter(stringResource(R.string.this_chat_was_started_in_the_agy_cli_which_doesn))
        }
    }
}

// MARK: - Relay consent

@Composable
fun RelayConsentDialog(name: String, onAllow: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SheetColors.background,
        title = { Text(stringResource(R.string.relay_alert_title), color = c.onSurface) },
        text = { Text(stringResource(R.string.relay_alert_text, name), color = c.onSurfaceVariant) },
        confirmButton = { TextButton(onAllow) { Text(stringResource(R.string.relay_alert_allow), color = c.primary, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.not_now), color = c.onSurfaceVariant) } },
    )
}

// MARK: - Notifications explainer

/** Icon, large title, intro, the 4-node route, four points, the accent button and "Not Now" (iOS NotificationExplainerView). */
@Composable
fun NotificationsExplainerSheet(onDismiss: () -> Unit, onConfirm: (() -> Unit)? = null, alreadyOn: Boolean = false, onAllowed: () -> Unit = {}) {
    val c = LocalAppColors.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) onAllowed() else onDismiss() }
    // With [onConfirm] the view model owns the permission request; otherwise ask here.
    AppSheet(onDismiss, large = true) {
        val close = rememberCloseSheet(onDismiss)
        val onAllow = { if (alreadyOn) close() else if (onConfirm != null) onConfirm() else permission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        Column(Modifier.widthIn(max = 520.dp).align(Alignment.CenterHorizontally).padding(horizontal = 28.dp).padding(bottom = 12.dp).navigationBarsPadding()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
                Icon(Icons.Outlined.NotificationsActive, null, Modifier.padding(top = 44.dp, bottom = 18.dp).size(34.dp), tint = c.onSurface)
                Text(stringResource(R.string.notifications), style = AppType.sans(32f, FontWeight.Bold, c.onSurface).copy(letterSpacing = (-0.6).sp), modifier = Modifier.padding(bottom = 8.dp))
                Text(stringResource(R.string.find_out_when_the_agent_finishes_fails_or_needs), style = AppType.sans(16f, color = c.onSurfaceVariant), modifier = Modifier.padding(bottom = 30.dp))
                FlowDiagram()
                Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Point(Icons.Outlined.Lock, R.string.only_this_phone_can_read_them, R.string.your_computer_locks_each_notification_with_a_key)
                    Point(Icons.Outlined.VisibilityOff, R.string.what_the_relay_sees, R.string.this_phone_s_push_address_an_id_for_your_compute)
                    Point(Icons.Outlined.PowerSettingsNew, R.string.off_means_no_contact, R.string.until_you_turn_notifications_on_your_computer_ne)
                    Point(Icons.Outlined.Code, R.string.open_source, R.string.the_relay_s_code_is_public_agpl_3_0_and_you_can)
                }
            }
            moe.n4tsu.agyremote.ui.components.liquid.LiquidButton(onAllow, moe.n4tsu.agyremote.ui.components.rememberGlassBackdrop(), Modifier.fillMaxWidth(), surfaceColor = c.accentFill, height = 54.dp) {
                Text(stringResource(if (alreadyOn) R.string.done else R.string.turn_on_notifications), style = AppType.sans(17f, FontWeight.SemiBold, c.onAccentFill))
            }
            if (!alreadyOn) Text(stringResource(R.string.not_now), style = AppType.sans(16f, FontWeight.Medium, c.onSurfaceVariant), textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 44.dp).plainClickable(onClick = close).padding(vertical = 12.dp))
        }
    }
}

/** Computer → relay → push service → phone in four equal columns, one dashed line through the node centres. */
@Composable
private fun FlowDiagram() {
    val c = LocalAppColors.current
    val nodes = listOf(
        moe.n4tsu.agyremote.ui.components.deviceIcon("") to R.string.computer, Icons.Outlined.Dns to R.string.relay,
        Icons.Outlined.CellTower to R.string.flow_push, Icons.Outlined.PhoneAndroid to R.string.flow_phone,
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Canvas(Modifier.matchParentSize()) {
                val col = size.width / 4
                drawLine(c.onSurfaceVariant.copy(alpha = 0.35f), Offset(col / 2, 22.dp.toPx()), Offset(size.width - col / 2, 22.dp.toPx()), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
            }
            Row(Modifier.fillMaxWidth()) {
                nodes.forEachIndexed { i, (icon, label) ->
                    val last = i == nodes.lastIndex
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.size(44.dp).background(c.surface, CircleShape).border(if (last) 1.5.dp else 1.dp, if (last) c.primary else c.outlineVariant, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(icon, null, Modifier.size(19.dp), tint = c.onSurface) }
                        Text(stringResource(label), style = AppType.sans(12f, FontWeight.Medium, c.onSurfaceVariant), maxLines = 1)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Lock, null, Modifier.size(12.dp), tint = c.primary)
            Text(stringResource(R.string.locked_until_phone), style = AppType.sans(13f, FontWeight.Medium, c.primary))
        }
    }
}

@Composable
private fun Point(icon: ImageVector, title: Int, text: Int) {
    val c = LocalAppColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.TopCenter) { Icon(icon, null, Modifier.size(21.dp), tint = c.primary) }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(title), style = AppType.sans(16f, FontWeight.SemiBold, c.onSurface))
            Text(stringResource(text), style = AppType.sans(14.5f, color = c.onSurfaceVariant))
        }
    }
}

/** Marks the simulated computer from the demo pairing code. */
@Composable
fun DemoBadge() {
    val c = LocalAppColors.current
    Text(
        stringResource(R.string.demo_badge), style = AppType.sans(11f, FontWeight.SemiBold, c.primary),
        modifier = Modifier.background(c.primary.copy(alpha = 0.14f), androidx.compose.foundation.shape.RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** The path in use, from the P2P library's `direct` / `relay` / `p2p`. */
@Composable
fun transportLabel(raw: String): String = when (raw) {
    "direct" -> stringResource(R.string.direct)
    "relay" -> stringResource(R.string.relay)
    "" -> ""
    else -> "P2P"
}

