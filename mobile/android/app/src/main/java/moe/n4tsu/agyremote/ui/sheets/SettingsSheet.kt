package moe.n4tsu.agyremote.ui.sheets

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.ui.res.vectorResource
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import moe.n4tsu.agyremote.BuildConfig
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.widthIn
import moe.n4tsu.agyremote.R
import moe.n4tsu.agyremote.state.DeviceStatus
import moe.n4tsu.agyremote.state.IndicatorStyle
import moe.n4tsu.agyremote.state.WorkspaceViewModel
import moe.n4tsu.agyremote.ui.components.ComputerRing
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.PanTool
import moe.n4tsu.agyremote.ui.components.ThinkingIndicator
import moe.n4tsu.agyremote.ui.components.plainClickable
import moe.n4tsu.agyremote.ui.theme.AppType
import moe.n4tsu.agyremote.ui.theme.LocalAppColors
import moe.n4tsu.agyremote.ui.components.AppToggle
import moe.n4tsu.agyremote.ui.theme.ThemeSetting
import moe.n4tsu.agyremote.ui.workspace.defaultModes
import moe.n4tsu.agyremote.ui.workspace.modeIcon

/** Settings (spec 8): computer card, grouped rows with 29 dp icon tiles, and its sub-pages. */
@Composable
fun SettingsSheet(
    vm: WorkspaceViewModel,
    onDismiss: () -> Unit,
    onModel: () -> Unit,
    onComputers: () -> Unit,
    onNotificationsExplainer: () -> Unit,
) {
    var page by remember { mutableStateOf("main") }
    AppSheet(onDismiss, large = true) {
        // Opening another sheet from here slides Settings down first (iOS presentAfterSettings).
        val hideThen = LocalHideSheetThen.current
        val onModel = { hideThen?.invoke(onModel) ?: onModel() }
        val onComputers = { hideThen?.invoke(onComputers) ?: onComputers() }
        androidx.activity.compose.BackHandler(enabled = page != "main") { page = "main" }
        SheetPages(page, { it == "main" }) { shown -> when (shown) {
            "computer" -> ComputerPage(vm, onBack = { page = "main" }, onDone = onDismiss, onComputers = onComputers)
            "appearance" -> AppearancePage(vm, onBack = { page = "main" }, onDone = onDismiss)
            "reset" -> ResetPage(vm, onBack = { page = "main" }, onDone = onDismiss)
            "licenses" -> LicensesPage(onBack = { page = "main" })
            "notifications" -> NotificationsPage(vm, onBack = { page = "main" }, onDone = onDismiss, onExplainer = onNotificationsExplainer)
            else -> MainPage(vm, onDone = onDismiss, onModel = onModel, go = { page = it })
        } }
    }
    if (vm.showNotificationExplainer) {
        NotificationsExplainerSheet(onDismiss = { vm.showNotificationExplainer = false }, onAllowed = {}, alreadyOn = vm.notificationsEnabled, onConfirm = { vm.confirmNotificationsTapped() })
    }
}

@Composable
private fun MainPage(vm: WorkspaceViewModel, onDone: () -> Unit, onModel: () -> Unit, go: (String) -> Unit) {
    val c = LocalAppColors.current
    var confirmReset by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    SheetBar(stringResource(R.string.settings), onDone = onDone)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 28.dp).navigationBarsPadding()) {
        val d = vm.selectedDevice
        if (d != null) {
            Row(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth().background(SheetColors.card, RoundedCornerShape(24.dp))
                    .plainClickable { go("computer") }.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val ring = when (vm.status) { DeviceStatus.CONNECTED -> c.connectedGreen; DeviceStatus.STANDBY -> c.warningAmber; DeviceStatus.OFFLINE -> c.accentRing.copy(alpha = 0.85f) }
                Box(Modifier.size(52.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(moe.n4tsu.agyremote.ui.components.deviceIcon(d.hardwareModel), null, Modifier.size(22.dp), tint = c.onSurface)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(d.name, style = AppType.sans(18f, FontWeight.SemiBold, c.onSurface), maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        if (d.isDemo) DemoBadge()
                    }
                    ComputerStatusText(vm)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp), tint = c.onSurfaceMuted)
            }
            Spacer(Modifier.height(36.dp))
        }
        Group(listOf(
            { SheetRow(stringResource(R.string.notifications), Icons.Outlined.NotificationsNone, value = stringResource(if (vm.notificationsEnabled) R.string.on else R.string.off), chevron = true, onClick = { go("notifications") }) },
            { SheetRow(stringResource(R.string.appearance), Icons.Outlined.Contrast, value = themeName(vm.theme), chevron = true, onClick = { go("appearance") }) },
        ))
        val modes = vm.modes
        if (vm.models.isNotEmpty() || modes.isNotEmpty()) {
        SectionHeader(stringResource(R.string.chat))
        Group(listOfNotNull(
            if (vm.models.isEmpty()) null else ({ SheetRow(stringResource(R.string.model), Icons.Outlined.Memory, value = vm.selectedModel?.name, chevron = true, onClick = onModel) }),
            if (modes.isEmpty()) null else ({
                Box {
                    SheetRow(stringResource(R.string.mode), Icons.Outlined.Tune, value = vm.selectedMode?.name ?: modes.firstOrNull()?.name, onClick = { modeMenu = true }) {
                        Icon(Icons.Outlined.UnfoldMore, null, Modifier.padding(start = 4.dp).size(18.dp), tint = c.onSurfaceVariant)
                    }
                    Box(Modifier.matchParentSize().padding(end = 16.dp)) {
                        moe.n4tsu.agyremote.ui.components.AppMenu(modeMenu, { modeMenu = false }, alignChecks = true) {
                            modes.forEach { m -> item(m.name, checked = m.id == vm.currentModeId) { vm.selectMode(m.id) } }
                        }
                    }
                }
            }),
        ))
        vm.selectedMode?.description?.takeIf { it.isNotEmpty() }?.let { SectionFooter(it) }
        }
        SectionHeader(stringResource(R.string.about))
        Group(listOf(
            { SheetRow(stringResource(R.string.version), Icons.Outlined.Info, value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") },
            { SheetRow(stringResource(R.string.license), Icons.Outlined.Description, value = "AGPL-3.0", chevron = true, onClick = { go("licenses") }) },
            {
                val links = androidx.compose.ui.platform.LocalUriHandler.current
                SheetRow(stringResource(R.string.source_code), androidx.compose.ui.graphics.vector.ImageVector.vectorResource(R.drawable.ic_github_mark),
                    value = stringResource(R.string.github), onClick = { runCatching { links.openUri(REPO_URL) } })
            },
            {
                val links = androidx.compose.ui.platform.LocalUriHandler.current
                SheetRow(stringResource(R.string.privacy_policy), Icons.Outlined.PanTool, onClick = { runCatching { links.openUri(PRIVACY_URL) } })
            },
        ))
        Spacer(Modifier.height(36.dp))
        Group(listOf { SheetRow(stringResource(R.string.reset_app), Icons.Outlined.RestartAlt, titleColor = c.errorRed, chevron = true, onClick = { go("reset") }) })
    }

}

@Composable
private fun themeName(t: ThemeSetting) = stringResource(when (t) { ThemeSetting.SYSTEM -> R.string.system; ThemeSetting.DARK -> R.string.dark; ThemeSetting.LIGHT -> R.string.light })


/** "Online · 12 ms" in green, "Connecting…" in amber, or "Offline". */
@Composable
private fun ComputerStatusText(vm: WorkspaceViewModel) {
    val c = LocalAppColors.current
    when (vm.status) {
        DeviceStatus.CONNECTED -> Text(stringResource(R.string.online_x_ms, vm.latencyMs), style = AppType.sans(14f, color = c.connectedGreen))
        DeviceStatus.STANDBY -> Text(stringResource(R.string.connecting), style = AppType.sans(14f, color = c.warningAmber))
        DeviceStatus.OFFLINE -> Text(stringResource(R.string.offline), style = AppType.sans(14f, color = c.onSurfaceVariant))
    }
}

@Composable
private fun ComputerPage(vm: WorkspaceViewModel, onBack: () -> Unit, onDone: () -> Unit, onComputers: () -> Unit) {
    val c = LocalAppColors.current
    val d = vm.selectedDevice ?: return
    val i = vm.info
    SheetBar(d.name, onBack = onBack)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 28.dp).navigationBarsPadding()) {
        val agy = i?.agyVersion.orEmpty()
        Group(listOfNotNull(
            { SheetRow(stringResource(R.string.name), value = d.name) },
            if (d.hardwareModel.isEmpty()) null else ({ SheetRow(stringResource(R.string.hardware), value = d.hardwareModel) }),
            if (agy.isEmpty()) null else ({ SheetRow(stringResource(R.string.agy), value = agy) }),
            { SheetRow(stringResource(R.string.agy_connector), value = i?.connectorVersion?.ifEmpty { null } ?: stringResource(R.string.unknown)) },
        ), inset = 20.dp)
        when {
            vm.versionIssue == moe.n4tsu.agyremote.state.VersionProblem.UPDATE_CONNECTOR -> SectionFooter(stringResource(R.string.connector_too_old_footer, moe.n4tsu.agyremote.state.MINIMUM_CONNECTOR))
            !vm.connectorUpdateNote.isNullOrEmpty() -> SectionFooter(stringResource(R.string.update_available_x_agy_connector_installs_it_aut, vm.connectorUpdateNote.orEmpty()))
        }
        SectionHeader(stringResource(R.string.connection))
        Group(listOfNotNull(
            { SheetRow(stringResource(R.string.status), value = stringResource(when (vm.status) { DeviceStatus.CONNECTED -> R.string.online; DeviceStatus.STANDBY -> R.string.connecting; DeviceStatus.OFFLINE -> R.string.offline })) },
            if (vm.transportName.isEmpty()) null else ({ SheetRow(stringResource(R.string.transport), value = transportLabel(vm.transportName)) }),
            { SheetRow(stringResource(R.string.latency), value = "${vm.latencyMs} ms") },
        ), inset = 20.dp)
        SectionHeader(stringResource(R.string.relay))
        Group(listOf {
            SheetRow(stringResource(R.string.use_relay_when_needed)) {
                AppToggle(vm.relayAllowed, { vm.setRelayAllowed(it) })
            }
        })
        SectionFooter(stringResource(R.string.when_your_phone_can_t_reach_this_computer_direct))
        Spacer(Modifier.height(28.dp))
        Group(listOf { SheetRow(stringResource(R.string.switch_or_add_computer), titleColor = c.primary, onClick = onComputers) })
    }
}

const val REPO_URL = "https://github.com/NarYuki/agy-remote"
const val PRIVACY_URL = "https://n4t.su/agy/privacy"

/** One entry of assets/licenses.json (tools/gen_licenses.py). */
private data class LicenseLib(
    val name: String, val version: String, val group: String, val licenseId: String,
    val licenseKeys: List<String>, val author: String?, val url: String?, val noticeKey: String?,
)

private class LicenseData(val app: LicenseLib, val libraries: List<LicenseLib>, val texts: Map<String, String>)

private fun loadLicenses(context: android.content.Context): LicenseData? = runCatching {
    val root = org.json.JSONObject(context.assets.open("licenses.json").bufferedReader().use { it.readText() })
    fun org.json.JSONObject.str(k: String) = if (isNull(k)) null else optString(k).ifEmpty { null }
    val libs = root.getJSONArray("libraries").let { a ->
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val keys = o.optJSONArray("licenseKeys")?.let { k -> (0 until k.length()).map { k.getString(it) } }.orEmpty()
            LicenseLib(o.getString("name"), o.optString("version"), o.optString("group"), o.optString("licenseId"),
                keys, o.str("author"), o.str("url"), o.str("noticeKey"))
        }
    }
    val texts = root.getJSONObject("texts").let { t -> t.keys().asSequence().associateWith { t.getString(it) } }
    val app = root.getJSONObject("app")
    val appLib = LicenseLib(app.getString("name"), moe.n4tsu.agyremote.BuildConfig.VERSION_NAME, "app", app.getString("licenseId"),
        listOf(app.getString("textKey")), null, null, null)
    LicenseData(appLib, libs, texts)
}.getOrNull()

/** Settings > License: the app, then the Android and native libraries; a row pushes its licence (iOS-style). */
@Composable
private fun LicensesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val data by androidx.compose.runtime.produceState<LicenseData?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadLicenses(context) }
    }
    var selected by remember { mutableStateOf<LicenseLib?>(null) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    androidx.activity.compose.BackHandler(enabled = selected != null) { selected = null }
    // A nested stack: the list is the root, a library's page is pushed over it.
    Column(Modifier.fillMaxWidth()) {
        SheetPages(selected, { it == null }) { lib ->
            if (lib == null) LicenseList(data, query, { query = it }, onBack) { selected = it }
            else LicenseDetail(lib, data?.texts.orEmpty(), onBack = { selected = null })
        }
    }
}

@Composable
private fun LicenseList(data: LicenseData?, query: String, onQuery: (String) -> Unit, onBack: () -> Unit, onOpen: (LicenseLib) -> Unit) {
    val c = LocalAppColors.current
    SheetBar(stringResource(R.string.license), onBack = onBack)
    SearchField(query, stringResource(R.string.licenses_search), onQuery)
    if (data == null) return
    val q = query.trim()
    fun match(l: LicenseLib) = q.isEmpty() || l.name.contains(q, true) || l.licenseId.contains(q, true) || l.version.contains(q, true)
    val sections = listOf(
        "" to listOf(data.app).filter(::match),
        stringResource(R.string.licenses_android_libraries) to data.libraries.filter { it.group == "android" && match(it) },
        stringResource(R.string.licenses_native_libraries) to data.libraries.filter { it.group == "rust" && match(it) },
    ).filter { it.second.isNotEmpty() }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 28.dp)) {
        if (sections.isEmpty()) item {
            Text(stringResource(R.string.licenses_no_results), style = AppType.sans(15f, color = c.onSurfaceVariant),
                modifier = Modifier.fillMaxWidth().padding(top = 40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        sections.forEachIndexed { si, (title, libs) ->
            item(key = "h$si") { if (title.isNotEmpty()) SectionHeader(title) else if (si > 0) Spacer(Modifier.height(18.dp)) }
            // Rows are separate items (the native list is long), drawn as one inset card.
            items(libs.size, key = { "$si/${libs[it].name}/${libs[it].version}" }) { i ->
                val first = i == 0; val last = i == libs.lastIndex
                val shape = androidx.compose.foundation.shape.RoundedCornerShape(
                    topStart = if (first) 24.dp else 0.dp, topEnd = if (first) 24.dp else 0.dp,
                    bottomStart = if (last) 24.dp else 0.dp, bottomEnd = if (last) 24.dp else 0.dp)
                Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(shape).background(SheetColors.card)) {
                    LicenseRow(libs[i]) { onOpen(libs[i]) }
                    if (!last) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 20.dp, end = 20.dp), color = c.outlineVariant, thickness = 0.5.dp)
                }
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun LicenseRow(lib: LicenseLib, onClick: () -> Unit) {
    val c = LocalAppColors.current
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).plainClickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(lib.name, style = AppType.sans(17f, color = c.onSurface), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (lib.version.isNotEmpty()) Text(lib.version, style = AppType.sans(13f, color = c.onSurfaceVariant), maxLines = 1)
        }
        Text(lib.licenseId, style = AppType.sans(15f, color = c.onSurfaceVariant), maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).widthIn(max = 170.dp))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.padding(start = 4.dp).size(20.dp), tint = c.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

@Composable
private fun LicenseDetail(lib: LicenseLib, texts: Map<String, String>, onBack: () -> Unit) {
    val c = LocalAppColors.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    SheetBar(lib.name, onBack = onBack)
    // The library's own notice first (copyright lines, NOTICE), then each shared licence text once.
    val blocks = buildList {
        lib.noticeKey?.let { k -> texts[k]?.let { add(null to it) } }
        lib.licenseKeys.forEach { k -> texts[k]?.let { add(k to it) } }
    }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 28.dp)) {
        item {
            Group(buildList {
                if (lib.version.isNotEmpty()) add { SheetRow(stringResource(R.string.version), value = lib.version) }
                add { SheetRow(stringResource(R.string.license), value = lib.licenseId) }
                lib.author?.let { a -> add { SheetRow(stringResource(R.string.licenses_author), value = a) } }
                lib.url?.let { u -> add { SheetRow(stringResource(R.string.licenses_source), value = u.removePrefix("https://"), titleColor = c.onSurface, onClick = { runCatching { uriHandler.openUri(u) } }) } }
            }, inset = 20.dp)
        }
        blocks.forEach { (key, text) ->
            item {
                SectionHeader(key ?: stringResource(R.string.licenses_notice))
                Text(text, style = AppType.mono(11.5f, color = c.onSurfaceVariant),
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                        .background(SheetColors.card).padding(16.dp))
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun ResetPage(vm: WorkspaceViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val c = LocalAppColors.current
    var asking by remember { mutableStateOf(false) }
    val context = LocalContext.current
    SheetBar(stringResource(R.string.reset), onBack = onBack)
    Column(Modifier.padding(top = 16.dp, bottom = 28.dp).navigationBarsPadding()) {
        Group(listOf { SheetRow(stringResource(R.string.reset_agy_remote), titleColor = c.errorRed, onClick = { asking = true }) })
        SectionFooter(stringResource(R.string.all_paired_computers_cached_chats_and_settings_a))
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false }, containerColor = SheetColors.background,
            title = { Text(stringResource(R.string.reset_agy_remote) + "?", color = c.onSurface, style = AppType.sans(17f, FontWeight.SemiBold)) },
            confirmButton = { TextButton({ asking = false; moe.n4tsu.agyremote.state.Haptics.attention(context); vm.resetApp(); onDone() }) { Text(stringResource(R.string.reset), color = c.errorRed, fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton({ asking = false }) { Text(stringResource(R.string.cancel), color = c.onSurface) } },
        )
    }
}

@Composable
private fun AppearancePage(vm: WorkspaceViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val c = LocalAppColors.current
    SheetBar(stringResource(R.string.appearance), onBack = onBack)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 28.dp).navigationBarsPadding()) {
        SectionHeader(stringResource(R.string.theme))
        Group(ThemeSetting.entries.map { t -> { CheckRow(themeName(t), vm.theme == t) { vm.updateTheme(t) } } }, inset = 20.dp)
        SectionHeader(stringResource(R.string.thinking_indicator))
        val labels = mapOf(
            IndicatorStyle.RING to stringResource(R.string.ring), IndicatorStyle.PROMPT to stringResource(R.string.prompt),
            IndicatorStyle.BRAILLE to stringResource(R.string.braille), IndicatorStyle.INFINITY to stringResource(R.string.infinity),
            IndicatorStyle.BRACKETS to stringResource(R.string.brackets),
            IndicatorStyle.MOBIUS to stringResource(R.string.m_bius_strip), IndicatorStyle.DOTS to stringResource(R.string.dots), IndicatorStyle.RANDOM to stringResource(R.string.random),
        )
        Group(IndicatorStyle.entries.map { s ->
            {
                val context = LocalContext.current
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).plainClickable { moe.n4tsu.agyremote.state.Haptics.selection(context); vm.updateIndicator(s) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { ThinkingIndicator(s, 16.dp) }
                    Text(labels[s].orEmpty(), style = AppType.sans(17f, color = c.onSurface), modifier = Modifier.padding(start = 14.dp).weight(1f))
                    if (vm.indicatorStyle == s) Icon(Icons.Filled.Check, null, Modifier.size(17.dp), tint = c.primary)
                }
            }
        })
        SectionFooter(stringResource(R.string.random_picks_a_different_shape_each_time_the_age))
    }
}

@Composable
private fun NotificationsPage(vm: WorkspaceViewModel, onBack: () -> Unit, onDone: () -> Unit, onExplainer: () -> Unit) {
    val c = LocalAppColors.current
    val names = listOf("finished" to R.string.finished, "failed" to R.string.failed, "approval" to R.string.needs_approval, "cli_released" to R.string.agy_cli_session_released)
    var kinds by remember { mutableStateOf(names.associate { (k, _) -> k to vm.notifyKind(k) }) }
    SheetBar(stringResource(R.string.notifications), onBack = onBack)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 28.dp).navigationBarsPadding()) {
        Group(listOf {
            SheetRow(stringResource(R.string.allow_notifications)) {
                AppToggle(vm.notificationsEnabled, { on -> vm.requestNotifications(on) })
            }
        })
        Text(stringResource(R.string.how_notifications_work), style = AppType.sans(13f, color = c.primary),
            modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 8.dp).plainClickable { vm.showNotificationExplainer = true })
        androidx.compose.animation.AnimatedVisibility(vm.notificationsEnabled,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(expandFrom = Alignment.Top),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top)) {
            Column {
                SectionHeader(stringResource(R.string.notify_me_when))
                Group(names.map { (k, title) ->
                    {
                        SheetRow(stringResource(title)) {
                            AppToggle(kinds.getValue(k), { on -> vm.setNotificationKind(k, on); kinds = kinds + (k to on) })
                        }
                    }
                }, inset = 20.dp)
                SectionFooter(stringResource(R.string.nothing_is_shown_for_the_chat_you_have_open_appr))
            }
        }
    }
}
