// SPDX-License-Identifier: AGPL-3.0-only
package moe.n4tsu.agyremote.ui.sheets

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.n4tsu.agyremote.state.Texts
import moe.n4tsu.agyremote.state.WorkspaceViewModel
import moe.n4tsu.agyremote.ui.theme.AppType
import moe.n4tsu.agyremote.ui.theme.LocalAppColors

/** Pairing confirmation, web-link confirmation and the file viewer, shown over everything. */
@Composable
fun SecurityDialogs(vm: WorkspaceViewModel) {
    vm.pendingPairLink?.let { PairLinkDialog(vm) }
    vm.pendingPairing?.let { PairNumberDialog(vm, it) }
    vm.pendingWebLink?.let { WebLinkDialog(vm, it) }
    vm.fileView?.let { FileViewerSheet(vm, it) }
}

/**
 * An `agy-rem://pair` link opened from a web page or another app: asked about before the app
 * contacts the computer it names.
 */
@Composable
private fun PairLinkDialog(vm: WorkspaceViewModel) {
    val c = LocalContext.current
    AlertDialog(
        onDismissRequest = { vm.answerPairLink(false) },
        title = { Text(Texts.get(c, "pair_with_a_computer", "Pair with a computer?")) },
        text = {
            Text(Texts.get(c, "a_link_wants_to_pair_agy_remote_with_a_computer",
                "A link wants to pair AGY Remote with a computer. Continue only if you just ran agy-connector pair on your own computer."))
        },
        confirmButton = { TextButton({ vm.answerPairLink(true) }) { Text(Texts.get(c, "pair", "Pair")) } },
        dismissButton = { TextButton({ vm.answerPairLink(false) }) { Text(Texts.get(c, "cancel", "Cancel")) } },
    )
}

/**
 * The last step of pairing: the number to type into `agy-connector pair` on the computer, or a
 * plain confirmation for an older code. Every way a pairing starts (QR, pasted code, link) ends
 * here, so nothing pairs without the user.
 */
@Composable
private fun PairNumberDialog(vm: WorkspaceViewModel, p: WorkspaceViewModel.PendingPairing) {
    val c = LocalContext.current
    val colors = LocalAppColors.current
    val id = p.ticket.endpointId.let { if (it.length > 16) it.take(8) + "…" + it.takeLast(6) else it }
    AlertDialog(
        onDismissRequest = { },
        title = { Text(Texts.get(c, "pair_with_this_computer", "Pair with this computer?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(p.computer, style = AppType.sans(17f, FontWeight.SemiBold, colors.onSurface))
                Text(id, style = AppType.mono(12.5f, color = colors.onSurfaceMuted))
                val number = p.number
                Text(Texts.get(c, "type_this_number_into_agy_connector_pair_on_the",
                    "Type this number into agy-connector pair on the computer. Only someone at that computer's keyboard can finish pairing."),
                    style = AppType.sans(14f, color = colors.onSurfaceVariant))
                Text(number.take(3) + " " + number.drop(3), Modifier.fillMaxWidth(),
                    style = AppType.mono(32f, FontWeight.SemiBold, colors.onSurface).copy(textAlign = TextAlign.Center))
                if (p.error != null) {
                    Text(p.error, style = AppType.sans(13.5f, color = colors.errorRed))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(Texts.get(c, "waiting_for_the_number_to_be_typed_on_the_comput", "Waiting for the number to be typed on the computer…"),
                            style = AppType.sans(13.5f, color = colors.onSurfaceVariant))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { vm.cancelPairing() }) { Text(Texts.get(c, "cancel", "Cancel")) }
        },
    )
}

/** A web link from a reply: shows where it really goes before anything opens it. */
@Composable
private fun WebLinkDialog(vm: WorkspaceViewModel, url: String) {
    val c = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { vm.closeWebLink() },
        title = { Text(Texts.get(c, "open_this_link", "Open this link?")) },
        text = { SelectionContainer { Text(url, style = AppType.mono(13f)) } },
        confirmButton = {
            TextButton({
                vm.closeWebLink()
                // A browser may be missing or refuse the address: never let that crash the app.
                try {
                    c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) {
                    vm.toast(Texts.get(c, "agy_remote_doesn_t_open_this_kind_of_link", "AGY Remote doesn't open this kind of link."))
                } catch (_: SecurityException) {
                    vm.toast(Texts.get(c, "agy_remote_doesn_t_open_this_kind_of_link", "AGY Remote doesn't open this kind of link."))
                }
            }) { Text(Texts.get(c, "open_in_browser", "Open in Browser")) }
        },
        dismissButton = {
            Row {
                TextButton({
                    scope.launch { clipboard.setClipEntry(ClipData.newPlainText("link", url).toClipEntry()) }
                    vm.closeWebLink()
                }) { Text(Texts.get(c, "copy_link", "Copy Link")) }
                TextButton({ vm.closeWebLink() }) { Text(Texts.get(c, "cancel", "Cancel")) }
            }
        },
    )
}

/** A text file an agent reply linked to (an artifact, a source file), read from the computer. */
@Composable
private fun FileViewerSheet(vm: WorkspaceViewModel, f: WorkspaceViewModel.FileView) {
    val c = LocalContext.current
    val colors = LocalAppColors.current
    AppSheet(onDismiss = { vm.closeFileView() }, large = true) {
        Text(f.name, style = AppType.sans(17f, FontWeight.SemiBold, colors.onSurface),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        when {
            f.error != null -> Text(f.error, style = AppType.sans(15f, color = colors.onSurfaceVariant), modifier = Modifier.padding(20.dp))
            f.text == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val lines = remember(f.text) { f.text.split('\n') }
                val width = lines.size.toString().length
                val list = rememberLazyListState()
                LaunchedEffect(f.line) { f.line?.let { list.scrollToItem((it - 1 - 3).coerceIn(0, (lines.size - 1).coerceAtLeast(0))) } }
                SelectionContainer {
                    LazyColumn(state = list, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        itemsIndexed(lines) { n, line ->
                            val hit = n + 1 == f.line
                            Row(
                                Modifier.background(if (hit) colors.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                                    .padding(horizontal = 14.dp, vertical = 1.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text((n + 1).toString().padStart(width), style = AppType.mono(12.5f, color = colors.onSurfaceMuted))
                                Text(line.ifEmpty { " " }, style = AppType.mono(12.5f, color = colors.onSurface), softWrap = false)
                            }
                        }
                        if (f.truncated) item {
                            Text(Texts.get(c, "the_file_is_longer_only_the_first_1_mb_is_shown", "The file is longer; only the first 1 MB is shown."),
                                style = AppType.sans(12.5f, color = colors.onSurfaceVariant), modifier = Modifier.padding(14.dp))
                        }
                    }
                }
            }
        }
    }
}
