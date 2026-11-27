package moe.n4tsu.agyremote.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.n4tsu.agyremote.R
import moe.n4tsu.agyremote.net.Approval
import moe.n4tsu.agyremote.net.Attachment
import moe.n4tsu.agyremote.net.Message
import moe.n4tsu.agyremote.state.Haptics
import moe.n4tsu.agyremote.state.IndicatorStyle
import moe.n4tsu.agyremote.state.WorkspaceViewModel
import moe.n4tsu.agyremote.ui.components.GlassCircleButton
import moe.n4tsu.agyremote.ui.components.Melt
import moe.n4tsu.agyremote.ui.theme.AppType
import moe.n4tsu.agyremote.ui.theme.LocalAppColors
import moe.n4tsu.agyremote.ui.theme.Metrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/** Within this distance of the end still counts as reading the latest output. */
private val FOLLOW_SLACK = 48.dp

/**
 * The conversation (spec 5.2, 5.3). The list is reversed (index 0 is the newest message), so a
 * growing reply keeps the end in view while the reader is there, and once they scroll up nothing
 * moves under them until they come back (by scrolling, the jump button, sending, or switching chats).
 *
 * [onDistance] receives the distance from the end as a fraction of the viewport (for the header
 * glow). [jumpBottom] is where the jump button floats, measured from the bottom of this view.
 */
@Composable
fun ChatTranscript(
    vm: WorkspaceViewModel,
    list: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    jumpBottom: Dp = contentPadding.calculateBottomPadding(),
    showJumpButton: Boolean = true,
    onDistance: (Float) -> Unit = {},
) {
    val c = LocalAppColors.current
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = vm.messages
    var viewport by remember { mutableIntStateOf(1) }

    val distancePx by remember {
        derivedStateOf {
            if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat()
            else {
                val avg = list.layoutInfo.visibleItemsInfo.map { it.size }.average().takeIf { !it.isNaN() } ?: 400.0
                (avg * list.firstVisibleItemIndex + list.firstVisibleItemScrollOffset).toFloat()
            }
        }
    }
    val slackPx = with(density) { FOLLOW_SLACK.toPx() }
    var pinned by remember { mutableStateOf(true) }
    var unseen by remember { mutableStateOf(false) }
    var programmatic by remember { mutableStateOf(false) }
    // The caller measures the composer inside its IME / nav-bar padding, so add those insets here:
    // otherwise the newest lines sit under the composer while the keyboard is up.
    val imeBottom = androidx.compose.foundation.layout.WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBottom = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPad = contentPadding.calculateBottomPadding() + max(imeBottom.value, navBottom.value).dp
    val padding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = bottomPad)
    val showJump by remember { derivedStateOf { !pinned && distancePx > max(with(density) { 160.dp.toPx() }, viewport * 0.4f) } }

    LaunchedEffect(Unit) { snapshotFlow { distancePx / viewport }.collect { onDistance(it) } }
    // Only the reader's own scrolling decides whether we follow.
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to distancePx }.collect { (moving, d) ->
            // Our own scrolls never unpin; only the reader's drag or fling decides.
            if (programmatic) return@collect
            if (moving || d <= slackPx) { pinned = d <= slackPx; if (pinned) unseen = false }
        }
    }

    fun jumpToLatest(animated: Boolean) {
        pinned = true; unseen = false
        scope.launch {
            programmatic = true
            try {
                if (animated && list.firstVisibleItemIndex < 6) list.animateScrollToItem(0) else list.scrollToItem(0)
                // Content grew meanwhile (the reply started): finish at the very end, like iOS.
                if (list.firstVisibleItemIndex != 0 || list.firstVisibleItemScrollOffset != 0) list.scrollToItem(0)
            } finally { programmatic = false }
        }
    }
    // While following, keep the end in view when the bottom padding changes (keyboard, composer growth).
    LaunchedEffect(bottomPad) { if (pinned && list.firstVisibleItemScrollOffset != 0) list.scrollToItem(0) }

    // Sending (or a reply starting) brings the newest line into view; a new item while following too.
    val lastId = messages.lastOrNull()?.id
    LaunchedEffect(lastId) {
        val last = messages.lastOrNull() ?: return@LaunchedEffect
        val justSent = last.fromUser
        val replyStarted = !last.fromUser && last.live && messages.getOrNull(messages.size - 2)?.fromUser == true
        if (justSent || replyStarted) jumpToLatest(true) else if (pinned) list.scrollToItem(0)
    }
    LaunchedEffect(vm.outputTick) { if (!pinned && vm.outputTick > 0) unseen = true }
    LaunchedEffect(vm.currentChatId, vm.scrollToBottomRequest) { jumpToLatest(false) }
    // Centre the middle message (previews and screenshots).
    LaunchedEffect(vm.scrollToMiddleTick) {
        if (vm.scrollToMiddleTick == 0 || messages.size < 2) return@LaunchedEffect
        pinned = false
        val index = messages.size - 1 - messages.size / 2
        list.animateScrollToItem(index, -(viewport / 3))
    }

    val dismissOnDrag = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 8f) focus.clearFocus()
                return Offset.Zero
            }
        }
    }
    // Stable callbacks, so unchanged rows skip recomposition while another reply streams.
    val onAnswer = remember(vm) { { a: Approval, choice: String -> vm.answer(a, choice) } }
    val onQuestion = remember(vm) { { a: Approval, answers: org.json.JSONObject? -> vm.answerQuestion(a, answers) } }
    val onRetry = remember(vm) { { m: Message -> vm.rerun(m) } }
    val reversed = messages.asReversed()
    // LazyColumn throws on a repeated key, and a transcript can carry the same id twice (a reloaded
    // or merged turn). Give repeats a stable suffix so scrolling back to them never crashes.
    val keys = remember(reversed) {
        val seen = HashMap<String, Int>()
        reversed.map { m -> val n = seen.merge(m.id, 1, Int::plus)!!; if (n == 1) m.id else "${m.id}#$n" }
    }
    val liveIndicator = vm.turnIndicator
    val idleIndicator = vm.indicatorStyle

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().nestedScroll(dismissOnDrag)
                .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } }
                .onSizeChanged { viewport = max(1, it.height) },
            state = list,
            reverseLayout = true,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(Metrics.MESSAGE_SPACING.dp),
        ) {
            itemsIndexed(reversed, key = { i, _ -> keys[i] }, contentType = { _, m -> if (m.fromUser) 0 else 1 }) { _, m ->
                MessageRow(RowModel(m), if (m.live) liveIndicator else idleIndicator, onAnswer, onQuestion, onRetry)
            }
        }
        TranscriptScrollBar(list, distancePx, viewport, padding, Modifier.align(Alignment.CenterEnd))
        if (showJumpButton) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = jumpBottom + (bottomPad - contentPadding.calculateBottomPadding()) + 10.dp)) {
                Melt(showJump) {
                    Box {
                        GlassCircleButton(40.dp, { Haptics.light(context); jumpToLatest(true) }, contentDescription = stringResource(R.string.jump_to_bottom)) {
                            Icon(Icons.Filled.ArrowDownward, null, Modifier.size(18.dp), tint = c.onSurface)
                        }
                        val dot by animateFloatAsState(if (unseen) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 440f), label = "dot")
                        Box(Modifier.align(Alignment.TopEnd).offset(1.dp, (-1).dp).size(9.dp)
                            .graphicsLayer { scaleX = dot; scaleY = dot; alpha = dot }.background(c.primary, CircleShape))
                    }
                }
            }
        }
    }
}

/** A thin thumb on the trailing edge that shows while scrolling and fades out after. */
@Composable
private fun TranscriptScrollBar(list: LazyListState, distancePx: Float, viewport: Int, padding: PaddingValues, modifier: Modifier) {
    val c = LocalAppColors.current
    val density = LocalDensity.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collectLatest { moving ->
            if (moving) visible = true else { delay(900); visible = false }
        }
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 120 else 350), label = "bar")
    val info = list.layoutInfo
    val count = info.totalItemsCount
    if (count == 0) return
    val avg = info.visibleItemsInfo.map { it.size }.average().takeIf { !it.isNaN() } ?: return
    val total = (avg * count).toFloat()
    if (total <= viewport * 1.05f) return
    val top = with(density) { padding.calculateTopPadding().toPx() }
    val bottom = with(density) { padding.calculateBottomPadding().toPx() }
    val track = viewport - top - bottom
    val minThumb = with(density) { 36.dp.toPx() }
    // With the keyboard up the track can be shorter than the smallest thumb: draw nothing then.
    if (track <= minThumb) return
    val thumb = (track * viewport / total).coerceIn(minThumb, track)
    val fraction = (distancePx / (total - viewport).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val y = top + (track - thumb) * (1f - fraction)
    Box(
        modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
    ) {
        Box(
            Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y.roundToInt()) }.padding(end = 3.dp)
                .width(3.dp).height(with(density) { thumb.toDp() }).background(c.onSurface.copy(alpha = 0.32f), CircleShape),
        )
    }
}

/** Equality on the message alone, so unchanged rows skip recomposition. */
@Immutable
class RowModel(val message: Message) {
    override fun equals(other: Any?) = other is RowModel && other.message == message
    override fun hashCode() = message.hashCode()
}

@Composable
fun MessageRow(row: RowModel, indicator: IndicatorStyle, onAnswer: (Approval, String) -> Unit,
               onQuestion: (Approval, org.json.JSONObject?) -> Unit, onRetry: (Message) -> Unit) {
    val m = row.message
    if (m.fromUser) UserBubble(m) else AgentMessage(m, indicator, onAnswer, onQuestion, onRetry)
}

@Composable
private fun UserBubble(m: Message) {
    val c = LocalAppColors.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Metrics.USER_SIDE_PADDING.dp),
        horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (m.attachments.isNotEmpty()) {
            Row(Modifier.padding(start = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                m.attachments.forEach { a -> AttachmentThumb(a) }
            }
        }
        if (m.text.isNotEmpty()) {
            Text(
                m.text, style = AppType.body.copy(color = c.onSurface, lineHeight = 23.sp),
                modifier = Modifier.padding(start = 56.dp).widthIn(min = 58.dp).heightIn(min = 48.dp)
                    .background(c.userBubble, RoundedCornerShape(Metrics.BUBBLE_RADIUS.dp)).padding(horizontal = 20.dp, vertical = 13.dp),
            )
        }
    }
}

@Composable
private fun AgentMessage(m: Message, indicator: IndicatorStyle, onAnswer: (Approval, String) -> Unit,
                         onQuestion: (Approval, org.json.JSONObject?) -> Unit, onRetry: (Message) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Metrics.AGENT_SIDE_PADDING.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // While the turn runs its plan sits above the composer (PlanNotice); afterwards, here.
        if (m.plan.isNotEmpty() && !m.live) PlanCard(m.plan)
        val parts = replyParts(m).let { p ->
            if (!m.thinking.isNullOrEmpty() && p.none { it is ReplyPart.Steps }) listOf(ReplyPart.Steps(-1, emptyList())) + p else p
        }
        val firstGroup = parts.indexOfFirst { it is ReplyPart.Steps }
        parts.forEachIndexed { i, part ->
            androidx.compose.runtime.key(part.key) {
                val last = i == parts.lastIndex
                when (part) {
                    is ReplyPart.Words -> StreamingText(part.text, m.live && last)
                    is ReplyPart.Steps -> StepGroup(part.steps, if (i == firstGroup) m.thinking else null, m.live && last, m.id + part.key)
                }
            }
        }
        // Answered cards melt away (and collapse) instead of vanishing.
        val seen = androidx.compose.runtime.remember(m.id) { androidx.compose.runtime.mutableStateListOf<Approval>() }
        m.approvals.forEach { a -> if (seen.none { it.id == a.id }) seen.add(a) }
        seen.forEach { a ->
            androidx.compose.runtime.key(a.id) {
                val shown = m.approvals.any { it.id == a.id }
                androidx.compose.animation.AnimatedVisibility(
                    shown,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(260)) +
                        androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(260), 0.94f) +
                        androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(320)),
                ) {
                    val current = m.approvals.firstOrNull { it.id == a.id } ?: a
                    // Left unanswered when its turn ended (stopped, failed, connector restarted): no live buttons.
                    val shownApproval = if (!m.live && current.chosen == null) current.copy(stale = true) else current
                    if (shownApproval.isQuestion) QuestionCard(shownApproval) { answers -> onQuestion(a, answers) }
                    else PermissionCard(shownApproval) { choice -> onAnswer(a, choice) }
                }
            }
        }
        if (m.live) LiveStatusLine(m, indicator)
        else m.seconds?.let { Text(stringResource(R.string.worked_for_x, durationText(it)), style = AppType.sans(13f, color = LocalAppColors.current.onSurfaceMuted)) }
        if (!m.live && m.text.isNotEmpty()) MessageActions(m.text) { onRetry(m) }
    }
}

/** A sent attachment: an image, a phone file, or an `@` file from the computer's folder. */
@Composable
fun AttachmentThumb(a: Attachment) {
    val c = LocalAppColors.current
    if (a.kind == "image") {
        val bmp = remember(a.id) { ThumbnailCache.get(a) }
        Box(Modifier.size(124.dp).clip(RoundedCornerShape(18.dp)).background(c.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp.asImageBitmap(), a.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(a.name, style = AppType.smallLabel.copy(color = c.onSurfaceVariant), maxLines = 2, modifier = Modifier.padding(8.dp))
        }
    } else {
        Row(
            Modifier.background(c.surfaceContainerHigh, CircleShape).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (a.path != null) {
                Icon(Icons.Outlined.AlternateEmail, null, Modifier.size(13.dp), tint = c.primary)
                Text(a.path, style = AppType.mono(12f, FontWeight.Medium, c.onSurface), maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.widthIn(max = 220.dp))
            } else {
                Icon(Icons.Filled.Description, null, Modifier.size(14.dp), tint = c.connectedGreen)
                Text(a.name, style = AppType.sans(12.5f, FontWeight.Medium, c.onSurface), maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.widthIn(max = 220.dp))
            }
        }
    }
}

/** Decoded attachment previews, so rows don't decode bitmaps on every composition. */
object ThumbnailCache {
    private val cache = LruCache<String, Bitmap>(120)

    fun get(a: Attachment): Bitmap? {
        cache.get(a.id)?.let { return it }
        val bytes = a.preview ?: a.bytes ?: a.data?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() } ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= 384) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache.put(a.id, bmp)
        return bmp
    }
}
