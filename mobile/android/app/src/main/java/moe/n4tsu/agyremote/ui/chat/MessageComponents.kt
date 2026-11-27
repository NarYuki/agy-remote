package moe.n4tsu.agyremote.ui.chat

import androidx.compose.animation.togetherWith
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.outlined.BackHand
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TripOrigin
import androidx.compose.material.icons.outlined.WebAsset
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import moe.n4tsu.agyremote.R
import moe.n4tsu.agyremote.net.Approval
import moe.n4tsu.agyremote.net.Message
import moe.n4tsu.agyremote.net.PlanEntry
import moe.n4tsu.agyremote.net.Step
import moe.n4tsu.agyremote.state.Haptics
import moe.n4tsu.agyremote.state.IndicatorStyle
import moe.n4tsu.agyremote.ui.components.ThinkingIndicator
import moe.n4tsu.agyremote.ui.components.deviceIcon
import moe.n4tsu.agyremote.ui.components.glass
import moe.n4tsu.agyremote.ui.components.plainClickable
import moe.n4tsu.agyremote.ui.theme.AppType
import moe.n4tsu.agyremote.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import kotlin.math.roundToInt

// Building blocks of a reply, ported from the iOS MessageComponents: quiet and typographic,
// hairline outlines instead of filled cards, monochrome ink.

@Composable
private fun hairline() = LocalAppColors.current.outlineVariant.copy(alpha = 0.55f)

@Composable
fun durationText(seconds: Double): String {
    val s = maxOf(1, seconds.roundToInt())
    return when {
        s < 60 -> stringResource(R.string.seconds_short, s)
        s < 3600 -> stringResource(R.string.minutes_seconds_short, s / 60, s % 60)
        else -> stringResource(R.string.hours_minutes_short, s / 3600, (s % 3600) / 60)
    }
}

fun stepIcon(kind: String): ImageVector = when (kind) {
    "read" -> Icons.Outlined.Description
    "edit" -> Icons.Outlined.Edit
    "delete" -> Icons.Outlined.Delete
    "move" -> Icons.AutoMirrored.Outlined.DriveFileMove
    "search" -> Icons.Outlined.Search
    "run", "execute" -> Icons.Outlined.Terminal
    "fetch" -> Icons.Outlined.Public
    "think" -> Icons.Outlined.Psychology
    else -> Icons.Outlined.TripOrigin
}

/** A reply cut into what came, in order: text, then the steps that ran before the next text, and so on. */
sealed interface ReplyPart {
    val key: String
    data class Words(val at: Int, val text: String) : ReplyPart { override val key get() = "t$at" }
    data class Steps(val at: Int, val steps: List<Step>) : ReplyPart { override val key get() = "s$at" }
}

fun replyParts(m: Message): List<ReplyPart> {
    val text = m.text
    val out = mutableListOf<ReplyPart>()
    var cursor = 0
    var group = mutableListOf<Step>()
    var groupAt = 0
    for (s in m.steps) {
        val at = (s.at ?: 0).coerceIn(0, text.length)
        val between = if (at > cursor) text.substring(cursor, at) else ""
        if (between.isNotBlank()) {
            if (group.isNotEmpty()) { out += ReplyPart.Steps(groupAt, group); group = mutableListOf() }
            out += ReplyPart.Words(cursor, between.trim('\n'))
        }
        if (at > cursor) cursor = at
        if (group.isEmpty()) groupAt = cursor
        group += s
    }
    if (group.isNotEmpty()) out += ReplyPart.Steps(groupAt, group)
    val rest = text.substring(cursor)
    if (rest.isNotBlank()) out += ReplyPart.Words(cursor, rest.trim('\n'))
    return out
}

/** What a turn is doing right now, at the bottom of its reply. */
@Composable
fun LiveStatusLine(message: Message, indicator: IndicatorStyle) {
    val c = LocalAppColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { ThinkingIndicator(indicator, 14.dp) }
        Text(message.statusLabel.trim().ifEmpty { stringResource(R.string.thinking) },
            style = AppType.label.copy(color = c.onSurfaceVariant), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Lines added and removed, like `+12 −3`. */
@Composable
fun LineDelta(added: Int, removed: Int) {
    val c = LocalAppColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val style = AppType.sans(13f, FontWeight.Medium, c.onSurface).copy(fontFeatureSettings = "tnum")
        if (added > 0) Text("+$added", style = style.copy(color = androidx.compose.ui.graphics.Color(0xFF3FB950)))
        if (removed > 0) Text("\u2212$removed", style = style.copy(color = c.errorRed))
    }
}

private fun fileName(path: String?) = path?.takeIf { it.isNotEmpty() }?.substringAfterLast('/')

/** Steps between two pieces of reply text, folded into one line such as "Ran 2 commands, edited a.kt +12 −3 ›". */
@Composable
fun StepGroup(steps: List<Step>, thought: String?, live: Boolean, keyId: String) {
    val c = LocalAppColors.current
    var expanded by rememberSaveable(keyId) { mutableStateOf(false) }
    val kinds = listOf("read", "edit", "run", "search", "fetch", "delete")
    // Only a finished step gets a past-tense verb: one waiting for approval or denied edited nothing.
    val byKind = steps.groupBy { if (it.status == "completed" && it.kind in kinds) it.kind else "other" }
    val pieces = byKind.map { (k, list) ->
        val files = list.mapNotNull { it.path }.toSet().size
        val one = fileName(list.firstOrNull()?.path)
        val n = if (k == "read" || k == "edit" || k == "delete") maxOf(files, 1) else list.size
        when (k) {
            "read" -> if (files <= 1 && one != null) stringResource(R.string.read_x, one) else pluralStringResource(R.plurals.read_x_files, n, n)
            "edit" -> if (files <= 1 && one != null) stringResource(R.string.edited_x, one) else pluralStringResource(R.plurals.edited_x_files, n, n)
            "delete" -> pluralStringResource(R.plurals.deleted_x_files, n, n)
            "run" -> pluralStringResource(R.plurals.ran_x_commands, n, n)
            "search" -> pluralStringResource(R.plurals.searched_x_times, n, n)
            "fetch" -> pluralStringResource(R.plurals.fetched_x_pages, n, n)
            else -> pluralStringResource(R.plurals.x_steps, n, n)
        }
    }.ifEmpty { listOf(stringResource(R.string.thought)) }
    val done = steps.filter { it.status == "completed" }
    val added = done.sumOf { it.added ?: 0 }
    val removed = done.sumOf { it.removed ?: 0 }
    val rot by animateFloatAsState(if (expanded) 90f else 0f, label = "chev")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().plainClickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(pieces.joinToString(", "), style = AppType.label.copy(color = c.onSurfaceVariant), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (added + removed > 0) LineDelta(added, removed)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(14.dp).rotate(rot), tint = c.onSurfaceMuted)
        }
        AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(start = 2.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (!thought.isNullOrEmpty()) StepRow(Icons.Outlined.Psychology, stringResource(R.string.thought), thought, "done", isLast = steps.isEmpty())
                steps.forEachIndexed { i, s ->
                    val state = when { s.status == "failed" -> "failed"; s.status == "running" && live -> "running"; else -> "done" }
                    StepRow(stepIcon(s.kind), fileName(s.path) ?: s.title.ifEmpty { s.kind }, s.detail, state, isLast = i == steps.lastIndex,
                        added = s.added ?: 0, removed = s.removed ?: 0)
                }
            }
        }
    }
}

@Composable
private fun StepRow(icon: ImageVector, title: String, detail: String?, state: String, isLast: Boolean, added: Int = 0, removed: Int = 0) {
    val c = LocalAppColors.current
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(24.dp).background(c.surfaceContainer, CircleShape), contentAlignment = Alignment.Center) {
                when (state) {
                    "running" -> CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = c.onSurfaceVariant)
                    "failed" -> Icon(Icons.Filled.PriorityHigh, null, Modifier.size(12.dp), tint = c.errorRed)
                    else -> Icon(icon, null, Modifier.size(12.dp), tint = c.onSurfaceVariant)
                }
            }
            if (!isLast) Box(Modifier.width(1.dp).weight(1f).background(hairline()))
        }
        Column(Modifier.padding(top = 3.dp, bottom = if (isLast) 0.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = AppType.sans(13.5f, color = c.onSurface), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (added + removed > 0) LineDelta(added, removed)
            }
            if (!detail.isNullOrEmpty()) Text(detail, style = AppType.sans(13f, color = c.onSurfaceVariant), maxLines = 8, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The agent's task plan: a checklist that updates as steps finish. */
@Composable
fun PlanCard(entries: List<PlanEntry>, isStreaming: Boolean = false) {
    val pulse = if (isStreaming) androidx.compose.animation.core.rememberInfiniteTransition(label = "plan").animateFloat(
        1f, 0.35f, androidx.compose.animation.core.infiniteRepeatable<Float>(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse",
    ).value else 1f
    val c = LocalAppColors.current
    val done = entries.count { it.status == "done" }
    Column(
        Modifier.fillMaxWidth().border(1.dp, hairline(), RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row {
            Text(stringResource(R.string.plan), style = AppType.sans(13f, FontWeight.SemiBold, c.onSurface), modifier = Modifier.weight(1f))
            Text("$done/${entries.size}", style = AppType.sans(12f, FontWeight.Medium, c.onSurfaceMuted).copy(fontFeatureSettings = "tnum"))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            entries.forEach { e ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(16.dp).padding(top = 2.dp), contentAlignment = Alignment.Center) {
                        when (e.status) {
                            "done" -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(14.dp), tint = c.primary)
                            "active" -> Box(Modifier.size(13.dp).graphicsLayer { alpha = pulse }.border(1.6.dp, c.onSurface, CircleShape), contentAlignment = Alignment.Center) { Box(Modifier.size(5.dp).background(c.onSurface, CircleShape)) }
                            else -> Icon(Icons.Outlined.Circle, null, Modifier.size(14.dp), tint = c.onSurfaceMuted)
                        }
                    }
                    Text(
                        e.text,
                        style = AppType.sans(14f, color = if (e.status == "done") c.onSurfaceMuted else c.onSurface)
                            .copy(textDecoration = if (e.status == "done") TextDecoration.LineThrough else null),
                    )
                }
            }
        }
    }
}

private enum class Subject { COMMAND, WRITE, EDIT, OTHER }

private fun subjectOf(a: Approval) = when {
    !a.command.isNullOrEmpty() -> Subject.COMMAND
    a.path != null && a.before != null && a.after != null -> Subject.EDIT
    a.path != null && a.after != null -> Subject.WRITE
    else -> Subject.OTHER
}

/** A question from the agent before it edits a file or runs a command; collapses once answered. */
/**
 * A question from the agent (`ask_question`): pick options (one, or several where allowed), or
 * write an answer where the agent accepts one, then send. Skip lets the agent go on without one.
 */
@Composable
fun QuestionCard(approval: Approval, onSend: (org.json.JSONObject?) -> Unit) {
    val c = LocalAppColors.current
    val ctx = LocalContext.current
    fun t(key: String, fallback: String) = moe.n4tsu.agyremote.state.Texts.get(ctx, key, fallback)
    val done = approval.chosen
    if (approval.stale || done != null) {
        val label = when {
            approval.stale -> t("this_question_is_no_longer_waiting", "This question is no longer waiting.")
            done == "skipped" -> t("skipped", "Skipped")
            else -> t("answered", "Answered")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (done == "answered") Icons.Filled.Check else Icons.Outlined.BackHand, null, Modifier.size(13.dp), tint = c.onSurfaceVariant)
            Text(label, style = AppType.sans(13f, color = c.onSurfaceVariant))
            Text(approval.questions.firstOrNull()?.question.orEmpty(), style = AppType.sans(13f, color = c.onSurfaceMuted), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        return
    }
    val picked = remember(approval.id) { androidx.compose.runtime.mutableStateMapOf<String, Set<String>>() }
    val written = remember(approval.id) { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val complete = approval.questions.all { q -> !picked[q.key].isNullOrEmpty() || !written[q.key].isNullOrBlank() }
    Column(
        Modifier.fillMaxWidth().border(1.dp, c.outlineVariant, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.BackHand, null, Modifier.size(14.dp), tint = c.onSurfaceVariant)
            Text(t("the_agent_has_a_question", "The agent has a question"), style = AppType.sans(12.5f, FontWeight.SemiBold, c.onSurfaceVariant))
        }
        approval.questions.forEach { q ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(q.question, style = AppType.sans(15f, FontWeight.Medium, c.onSurface))
                if (q.multi) Text(t("select_all_that_apply", "Select all that apply"), style = AppType.sans(12f, color = c.onSurfaceMuted))
                q.options.forEach { o ->
                    val on = picked[q.key]?.contains(o.id) == true
                    Row(
                        Modifier.fillMaxWidth().plainClickable {
                            val now = picked[q.key].orEmpty()
                            picked[q.key] = if (q.multi) (if (on) now - o.id else now + o.id) else (if (on) emptySet() else setOf(o.id))
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier.size(18.dp).border(1.5.dp, if (on) c.primary else c.onSurfaceVariant, if (q.multi) RoundedCornerShape(4.dp) else CircleShape)
                                .padding(4.dp).background(if (on) c.primary else androidx.compose.ui.graphics.Color.Transparent, if (q.multi) RoundedCornerShape(2.dp) else CircleShape),
                        )
                        Text(o.label, style = AppType.sans(14.5f, color = c.onSurface))
                    }
                }
                if (q.otherKey != null) {
                    androidx.compose.foundation.text.BasicTextField(
                        written[q.key].orEmpty(), { written[q.key] = it },
                        textStyle = AppType.sans(14f, color = c.onSurface),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.primary),
                        modifier = Modifier.fillMaxWidth().background(c.surfaceContainer, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                        decorationBox = { inner ->
                            if (written[q.key].isNullOrEmpty()) Text(t("other_answer", "Other answer"), style = AppType.sans(14f, color = c.onSurfaceMuted))
                            inner()
                        },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PermissionButton(t("skip", "Skip"), prominent = false) { onSend(null) }
            Spacer(Modifier.weight(1f))
            Box(Modifier.alpha(if (complete) 1f else 0.4f)) {
                PermissionButton(t("send", "Send"), prominent = true) {
                    if (!complete) return@PermissionButton
                    val out = org.json.JSONObject()
                    approval.questions.forEach { q ->
                        val set = picked[q.key].orEmpty()
                        if (q.multi) out.put(q.key, org.json.JSONArray(q.options.map { it.id }.filter { it in set }))
                        else set.firstOrNull()?.let { out.put(q.key, it) }
                        val text = written[q.key]?.trim()
                        if (q.otherKey != null && !text.isNullOrEmpty()) out.put(q.otherKey, text)
                    }
                    onSend(out)
                }
            }
        }
    }
}

@Composable
fun PermissionCard(approval: Approval, onSelect: (String) -> Unit) {
    // Answering melts the card into its one-line summary (fade + scale + size), never a pop.
    androidx.compose.animation.AnimatedContent(
        approval, contentKey = { it.chosen to it.stale },
        transitionSpec = {
            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(300, 120)) +
                androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(300, 120), 0.96f)) togetherWith
                (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)) +
                    androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(200), 0.96f)) using
                androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.tween(320) }
        },
        contentAlignment = Alignment.TopStart, label = "approval",
    ) { a -> PermissionCardBody(a, onSelect) }
}

@Composable
private fun PermissionCardBody(approval: Approval, onSelect: (String) -> Unit) {
    val c = LocalAppColors.current
    val subject = subjectOf(approval)
    val fileName = approval.path?.substringAfterLast('/').orEmpty()
    val chosen = approval.chosen
    if (chosen == null && approval.stale) {
        // The turn moved on without an answer from here: show the request, no buttons.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.BackHand, null, Modifier.size(13.dp), tint = c.onSurfaceMuted)
            Text(approval.command ?: approval.title, style = AppType.sans(13f, color = c.onSurfaceMuted),
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
        }
        return
    }
    if (chosen != null) {
        val choice = approval.choices.firstOrNull { it.id == chosen }
        val allowed = choice?.effect?.startsWith("allow") ?: (chosen == "allow")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (allowed) Icons.Filled.Check else Icons.Filled.Close, null, Modifier.size(12.dp), tint = if (allowed) c.primary else c.errorRed)
            val summary = when (subject) {
                Subject.COMMAND -> approval.command.orEmpty()
                Subject.WRITE -> stringResource(R.string.create_x, fileName)
                Subject.EDIT -> stringResource(R.string.edit_x, fileName)
                Subject.OTHER -> approval.title
            }
            Text(summary, style = if (subject == Subject.COMMAND) AppType.mono(13f, color = c.onSurfaceVariant) else AppType.sans(13f, color = c.onSurfaceVariant),
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
            Text(choice?.let { choiceLabel(it) }.orEmpty(), style = AppType.sans(12f, FontWeight.Medium, c.onSurfaceMuted))
        }
        return
    }
    Column(
        Modifier.fillMaxWidth().border(1.dp, c.outlineVariant, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val icon = when (subject) { Subject.COMMAND -> Icons.Outlined.Terminal; Subject.WRITE -> Icons.Outlined.NoteAdd; Subject.EDIT -> Icons.Outlined.Edit; Subject.OTHER -> Icons.Outlined.BackHand }
            Icon(icon, null, Modifier.size(14.dp), tint = c.onSurfaceVariant)
            Text(stringResource(R.string.needs_your_approval), style = AppType.sans(12.5f, FontWeight.SemiBold, c.onSurfaceVariant))
        }
        val titleStyle = AppType.sans(15f, FontWeight.Medium, c.onSurface)
        when (subject) {
            Subject.COMMAND -> { Text(stringResource(R.string.run_a_command), style = titleStyle); CodeBox(approval.command.orEmpty(), 8) }
            Subject.WRITE -> {
                Text(stringResource(R.string.create_x, fileName), style = titleStyle)
                val lines = approval.after.orEmpty().split('\n').size
                Text(pluralStringResource(R.plurals.x_x_lines, lines, approval.path.orEmpty(), lines), style = AppType.sans(12.5f, color = c.onSurfaceMuted), maxLines = 2, overflow = TextOverflow.StartEllipsis)
            }
            Subject.EDIT -> { Text(stringResource(R.string.edit_x, fileName), style = titleStyle); DiffBox(approval.before.orEmpty(), approval.after.orEmpty()) }
            Subject.OTHER -> {
                Text(approval.title.ifEmpty { stringResource(R.string.use_a_tool) }, style = titleStyle)
            }
        }
        val allow = approval.choices.filter { it.effect.startsWith("allow") }
        val other = approval.choices.filterNot { it.effect.startsWith("allow") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            other.forEach { PermissionButton(choiceLabel(it), prominent = false) { onSelect(it.id) } }
            Spacer(Modifier.weight(1f))
            allow.forEach { PermissionButton(choiceLabel(it), prominent = it.effect == "allow_once" || allow.size == 1) { onSelect(it.id) } }
        }
    }
}

@Composable
private fun PermissionButton(label: String, prominent: Boolean, onClick: () -> Unit) {
    val c = LocalAppColors.current
    val m = Modifier.height(34.dp)
    Box(
        (if (prominent) m.background(c.onSurface, CircleShape) else m.border(1.dp, hairline(), CircleShape))
            .plainClickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = AppType.sans(13.5f, FontWeight.SemiBold, if (prominent) c.surface else c.onSurface), maxLines = 1)
    }
}

/** The whole text, never cut: what is approved must be readable to the end. Past [maxLines] lines it scrolls. */
@Composable
private fun CodeBox(text: String, maxLines: Int) {
    val c = LocalAppColors.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(text, style = AppType.mono(12.5f, color = c.onSurface),
            modifier = Modifier.fillMaxWidth().background(c.surfaceContainer, RoundedCornerShape(8.dp))
                .heightIn(max = (maxLines * 17 + 20).dp).verticalScroll(rememberScrollState()).padding(10.dp))
    }
}

/** "- old" lines in red then "+ new" lines in primary; only the lines that change. */
@Composable
private fun DiffBox(before: String, after: String) {
    val c = LocalAppColors.current
    var old = before.split('\n')
    var new = after.split('\n')
    while (old.isNotEmpty() && new.isNotEmpty() && old.first() == new.first()) { old = old.drop(1); new = new.drop(1) }
    while (old.isNotEmpty() && new.isNotEmpty() && old.last() == new.last()) { old = old.dropLast(1); new = new.dropLast(1) }
    val more = if (old.size > 4 && new.isNotEmpty()) stringResource(R.string.x_more, old.size - 3) else null
    if (more != null) old = old.take(3) + more
    val text = buildAnnotatedString {
        val lines = old.map { "- $it" to c.errorRed } + new.map { "+ $it" to c.primary }
        lines.forEachIndexed { i, (s, col) -> if (i > 0) append('\n'); withStyle(SpanStyle(color = col)) { append(s) } }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        // Every added line is shown (scrolling past ten); only removed lines are summarized.
        Text(text, style = AppType.mono(12.5f),
            modifier = Modifier.fillMaxWidth().background(c.surfaceContainer, RoundedCornerShape(8.dp))
                .heightIn(max = 190.dp).verticalScroll(rememberScrollState()).padding(10.dp))
    }
}

/** Copy, share, retry — muted icons under a finished reply. */
@Composable
fun MessageActions(text: String, onRetry: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1400); copied = false } }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionIcon(if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy, stringResource(R.string.copy)) {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("reply", text))
            Haptics.success(context); copied = true
        }
        ActionIcon(Icons.Outlined.Share, stringResource(R.string.share)) {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
        }
        ActionIcon(Icons.Outlined.Refresh, stringResource(R.string.retry), onRetry)
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Box(Modifier.size(34.dp, 30.dp).semantics { contentDescription = label }.plainClickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(15.dp), tint = c.onSurfaceMuted)
    }
}

/** For chats the computer can't replay (desktop-app chats, or unsaved CLI chats). */
@Composable
fun UnavailableHistory(cliUnsaved: Boolean) {
    val c = LocalAppColors.current
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Icon(if (cliUnsaved) Icons.Outlined.Terminal else Icons.Outlined.WebAsset, null, Modifier.size(32.dp), tint = c.onSurfaceMuted)
        Spacer(Modifier.height(14.dp))
        Text(stringResource(if (cliUnsaved) R.string.can_t_show_this_chat_yet else R.string.open_this_chat_on_your_computer), style = AppType.sans(17f, FontWeight.SemiBold, c.onSurface))
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(if (cliUnsaved) R.string.it_s_open_in_the_agy_cli_but_agy_couldn_t_save_i else R.string.it_was_started_in_the_antigravity_desktop_app_on),
            style = AppType.secondaryBody.copy(color = c.onSurfaceVariant), textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(Modifier.weight(2f))
    }
}

/**
 * A reply that arrives in chunks shown as one steady flow. Whatever is already there when the row
 * appears shows at once; text that arrives later is revealed at 60 Hz at the pace it comes in
 * (measured from the chunks), catching up gently when it falls more than a third of a second
 * behind. A finished reply always renders in full.
 */
@Composable
fun StreamingText(text: String, streaming: Boolean) {
    // null = not animating, show everything.
    var revealed by remember { mutableStateOf<Int?>(if (streaming) text.length else null) }
    val latest by rememberUpdatedState(text)
    val live by rememberUpdatedState(streaming)
    // Characters per second, from the size of each chunk and the time since the one before.
    val pace = remember { doubleArrayOf(600.0) }
    val arrival = remember { longArrayOf(0L, text.length.toLong()) }
    LaunchedEffect(text) {
        val now = System.nanoTime()
        val grown = text.length - arrival[1]
        if (arrival[0] != 0L && grown > 0) {
            val gap = maxOf(0.05, (now - arrival[0]) / 1e9)
            pace[0] = pace[0] * 0.6 + minOf(20_000.0, grown / gap) * 0.4
        }
        arrival[0] = now; arrival[1] = text.length.toLong()
    }
    LaunchedEffect(streaming) { if (!streaming) revealed = null else if (revealed == null) revealed = latest.length }
    LaunchedEffect(revealed == null) {
        while (true) {
            val current = revealed ?: return@LaunchedEffect
            val backlog = latest.length - current
            if (backlog > 0) {
                var step = maxOf(1, (pace[0] / 60).toInt())
                val slack = (pace[0] * 0.35).toInt()
                if (backlog > slack) step += (backlog - slack) / 6
                revealed = minOf(latest.length, current + step)
            } else if (!live) { revealed = null; return@LaunchedEffect }
            delay(16)
        }
    }
    val count = revealed
    val visible = if (!streaming || count == null || count >= text.length) text else {
        var end = count
        // Never cut a surrogate pair.
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        text.substring(0, end)
    }
    MarkdownView(visible)
}

/** Above the composer while the chat is open in an agy CLI window. */
@Composable
fun OpenInCliNotice(hostName: String) {
    val c = LocalAppColors.current
    NoticeCapsule(Icons.Outlined.Terminal, if (hostName.isEmpty()) stringResource(R.string.open_in_the_agy_cli_updating_live_close_it_there) else stringResource(R.string.open_in_the_agy_cli_on_x_updating_live_close_it, hostName),
        c.onSurfaceVariant, c.surfaceContainerLow.copy(alpha = 0.9f), c.outlineVariant.copy(alpha = 0.6f))
}

/** The running turn's plan, shown like the other notices: the step under way and how far along; tapped, the list. */
@Composable
fun PlanNotice(entries: List<PlanEntry>) {
    val c = LocalAppColors.current
    var open by rememberSaveable { mutableStateOf(false) }
    val done = entries.count { it.status == "done" }
    val current = entries.firstOrNull { it.status == "active" } ?: entries.firstOrNull { it.status != "done" } ?: entries.lastOrNull()
    val shape = RoundedCornerShape(16.dp)
    val rot by animateFloatAsState(if (open) 180f else 0f, label = "plan")
    Column(
        Modifier.padding(horizontal = 20.dp).background(c.surfaceContainerLow.copy(alpha = 0.9f), shape)
            .border(1.dp, c.outlineVariant.copy(alpha = 0.6f), shape).plainClickable { open = !open }
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .animateContentSize(androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        @Composable fun count() = Text("$done/${entries.size}", style = AppType.sans(12f, FontWeight.Medium, c.onSurfaceMuted).copy(fontFeatureSettings = "tnum"))
        @Composable fun chevron() = Icon(Icons.Outlined.KeyboardArrowUp, null, Modifier.size(14.dp).rotate(rot), tint = c.onSurfaceMuted)
        if (open) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Checklist, null, Modifier.size(13.dp), tint = c.onSurfaceVariant)
                Text(stringResource(R.string.plan), style = AppType.sans(12.5f, FontWeight.SemiBold, c.onSurfaceVariant), modifier = Modifier.weight(1f))
                count(); chevron()
            }
            // Opened at the step under way, so where things stand shows at a glance.
            val scroll = rememberScrollState()
            val currentIndex = entries.indexOf(current).coerceAtLeast(0)
            var rowTops by remember { mutableStateOf(mapOf<Int, Int>()) }
            LaunchedEffect(currentIndex, rowTops[currentIndex]) {
                rowTops[currentIndex]?.let { scroll.animateScrollTo((it - 60).coerceAtLeast(0)) }
            }
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entries.forEachIndexed { i, e ->
                    Row(Modifier.onGloballyPositioned { c -> val y = c.positionInParent().y.toInt(); if (rowTops[i] != y) rowTops = rowTops + (i to y) },
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        PlanDot(e.status)
                        Text(e.text, style = AppType.sans(13f, color = if (e.status == "done") c.onSurfaceMuted else c.onSurface)
                            .copy(textDecoration = if (e.status == "done") TextDecoration.LineThrough else null))
                    }
                }
            }
        } else if (current != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanDot(current.status)
                Text(current.text, style = AppType.sans(12.5f, FontWeight.Medium, c.onSurface), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                count(); chevron()
            }
        }
    }
}

@Composable
private fun PlanDot(status: String) {
    val c = LocalAppColors.current
    Box(Modifier.width(16.dp).padding(top = 1.dp), contentAlignment = Alignment.Center) {
        when (status) {
            "done" -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(14.dp), tint = c.primary)
            "active" -> Box(Modifier.size(13.dp).border(1.6.dp, c.onSurface, CircleShape), contentAlignment = Alignment.Center) { Box(Modifier.size(5.dp).background(c.onSurface, CircleShape)) }
            else -> Icon(Icons.Outlined.Circle, null, Modifier.size(14.dp), tint = c.onSurfaceMuted)
        }
    }
}

/** Above the composer when this app and the connector don't fit together. */
@Composable
fun VersionNotice(icon: ImageVector, text: String) {
    val c = LocalAppColors.current
    NoticeCapsule(icon, text, c.onSurface, c.surfaceContainerLow.copy(alpha = 0.95f), c.primary.copy(alpha = 0.5f))
}

@Composable
/** A notice above the composer: always the whole text, on as many lines as it needs. */
fun NoticeCapsule(icon: ImageVector, text: String, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color, border: androidx.compose.ui.graphics.Color) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.padding(horizontal = 20.dp).background(bg, shape).border(1.dp, border, shape).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(13.dp), tint = fg)
        Text(text, style = AppType.sans(12.5f, FontWeight.Medium, fg))
    }
}

/** "You can reply here now" — the CLI closed the chat on screen. */
@Composable
fun AvailableAgainBanner(chatTitle: String) {
    val c = LocalAppColors.current
    Row(
        Modifier.padding(horizontal = 20.dp).glass(RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.CheckCircle, null, Modifier.size(20.dp), tint = c.connectedGreen)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(stringResource(R.string.you_can_reply_here_now), style = AppType.sans(14.5f, FontWeight.SemiBold, c.onSurface))
            Text(if (chatTitle.isEmpty()) stringResource(R.string.the_agy_cli_session_was_released) else stringResource(R.string.x_agy_cli_session_released, chatTitle),
                style = AppType.smallLabel.copy(color = c.onSurfaceVariant))
        }
    }
}

/** Centre state (5.4): indicator 20 in a 28 box, 10 spacing, 14 secondary text. */
@Composable
fun CenterStatus(indicator: IndicatorStyle, text: String) {
    val c = LocalAppColors.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { ThinkingIndicator(indicator, 20.dp) }
        Text(text, style = AppType.sans(14f, color = c.onSurfaceVariant))
    }
}

@Composable
fun ConnectionStatus(name: String, hardwareModel: String, connecting: Boolean, indicator: IndicatorStyle) {
    val c = LocalAppColors.current
    if (connecting) {
        CenterStatus(indicator, stringResource(R.string.connecting_to_x, name))
    } else {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Icon(deviceIcon(hardwareModel), null, Modifier.size(24.dp), tint = c.onSurfaceVariant) }
            Text(stringResource(R.string.can_t_reach_x, name), style = AppType.sans(14f, FontWeight.Medium, c.onSurface))
            Text(stringResource(R.string.make_sure_it_s_on_and_agy_connector_is_running_r), style = AppType.sans(13f, color = c.onSurfaceVariant), textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 280.dp))
        }
    }
}

/** agy's plain English answers ("Allow", "Deny", "Always") in the UI language; any other label as sent. */
@Composable
private fun choiceLabel(c: moe.n4tsu.agyremote.net.Choice): String {
    val ctx = LocalContext.current
    return when (c.effect to c.label) {
        "allow_once" to "Allow" -> moe.n4tsu.agyremote.state.Texts.get(ctx, "allow", "Allow")
        "deny_once" to "Deny" -> moe.n4tsu.agyremote.state.Texts.get(ctx, "deny", "Deny")
        "allow_always" to "Always" -> moe.n4tsu.agyremote.state.Texts.get(ctx, "always", "Always")
        else -> c.label
    }
}
