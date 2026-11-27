package moe.n4tsu.agyremote.ui.sheets

import kotlinx.coroutines.launch
import moe.n4tsu.agyremote.ui.components.plainClickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import moe.n4tsu.agyremote.R
import moe.n4tsu.agyremote.state.Haptics
import moe.n4tsu.agyremote.ui.components.GlassCircleButton
import moe.n4tsu.agyremote.ui.theme.AppType
import java.util.concurrent.Executors

/** Full-screen camera with a rounded square guide and the hint; unavailable state without a camera. */
@Composable
fun QrScannerSheet(onDismiss: () -> Unit, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val hasCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; denied = !ok }
    LaunchedEffect(Unit) { if (hasCamera && !granted) permission.launch(Manifest.permission.CAMERA) }

    // Presented like an iOS sheet: slides up on open, slides down before it is removed.
    val slide = remember { androidx.compose.animation.core.Animatable(1f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { slide.animateTo(0f, moe.n4tsu.agyremote.ui.theme.Motion.spring(0.42f, 0.92f)) }
    val close: () -> Unit = { scope.launch { slide.animateTo(1f, androidx.compose.animation.core.tween(260)); onDismiss() } }
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = slide.value * size.height }.background(Color.Black)) {
            if (hasCamera && granted) {
                CameraPreview(onCode)
                Guide()
                // The hint in a frosted capsule, `agy-connector pair` in monospace (iOS inline code).
                val hint = moe.n4tsu.agyremote.state.Texts.get(LocalContext.current, "point_at_the_qr_code_printed_by_agy_connector_pa", "Point at the QR code printed by `agy-connector pair`")
                val styled = androidx.compose.ui.text.buildAnnotatedString {
                    hint.split('`').forEachIndexed { i, part ->
                        if (i % 2 == 1) with(this) { pushStyle(androidx.compose.ui.text.SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)); append(part); pop() } else append(part)
                    }
                }
                Text(
                    styled, style = AppType.sans(13.5f, FontWeight.Medium, Color.White), textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 36.dp, start = 24.dp, end = 24.dp)
                        .background(Color.White.copy(alpha = 0.16f), CircleShape).padding(horizontal = 18.dp, vertical = 10.dp),
                )
            } else if (!hasCamera || denied) {
                Column(Modifier.align(Alignment.Center).widthIn(max = 300.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.NoPhotography, null, Modifier.size(36.dp), tint = Color.White.copy(alpha = 0.7f))
                    Text(stringResource(R.string.camera_unavailable), style = AppType.sans(17f, FontWeight.SemiBold, Color.White))
                    Text(stringResource(if (hasCamera) R.string.camera_access_is_turned_off_for_agy_remote_allow else R.string.no_camera_found_on_this_device_paste_the_agy_rem), style = AppType.sans(14.5f, color = Color.White.copy(alpha = 0.7f)), textAlign = TextAlign.Center)
                }
            }
            // Title bar: centred title, Cancel on the trailing side.
            Box(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.scan_pc_connector), style = AppType.sans(17f, FontWeight.SemiBold, Color.White), modifier = Modifier.align(Alignment.Center))
                Text(stringResource(R.string.cancel), style = AppType.sans(17f, color = Color.White),
                    modifier = Modifier.align(Alignment.CenterEnd).plainClickable(onClick = close).padding(8.dp))
            }
        }
    }
}

@Composable
private fun Guide() {
    val ring = moe.n4tsu.agyremote.ui.theme.LocalAppColors.current.accentRing
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(GUIDE_DP.dp).border(2.2.dp, ring, RoundedCornerShape(24.dp)))
    }
}

/** Side of the guide square in [Guide], in dp. */
private const val GUIDE_DP = 240f

/**
 * Offline QR decoder (ZXing core, no network): decodes the Y plane of a camera frame, cropped to
 * the guide square plus a margin, as a QR code, also trying the inverted image.
 */
/**
 * Offline QR decoding with zxing-cpp. It copes with what a phone camera sees of a terminal-drawn
 * code (line gaps between rows, light-on-dark, glare) far better than the Java ZXing port.
 */
private class QrDecoder {
    private val reader = zxingcpp.BarcodeReader(zxingcpp.BarcodeReader.Options(
        formats = setOf(zxingcpp.BarcodeReader.Format.QR_CODE),
        tryHarder = true,
        tryRotate = true,
        tryInvert = true,
        tryDownscale = true,
        tryDenoise = true,
    ))

    fun decode(proxy: androidx.camera.core.ImageProxy): String? =
        runCatching { reader.read(proxy).firstOrNull { !it.text.isNullOrEmpty() }?.text }.getOrNull()
}

@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val done = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val cameraRef = remember { java.util.concurrent.atomic.AtomicReference<androidx.camera.core.Camera?>(null) }
    val viewSize = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val focusPoint = remember { java.util.concurrent.atomic.AtomicReference<androidx.camera.core.MeteringPoint?>(null) }
    val guidePx = GUIDE_DP * context.resources.displayMetrics.density
    DisposableEffect(Unit) {
        onDispose {
            // Reset the zoom stepped up while scanning.
            cameraRef.getAndSet(null)?.cameraControl?.setZoomRatio(1f)
            executor.shutdown()
        }
    }
    AndroidView(factory = { ctx ->
        val view = PreviewView(ctx)
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> viewSize.set((v.width.toLong() shl 32) or v.height.toLong()) }
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // The default 640×480 is too coarse for the dense pairing QR; analyse at about 1080p.
                .setResolutionSelector(androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(
                        android.util.Size(1920, 1080),
                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build())
                .build()
            val decoder = QrDecoder()
            val started = android.os.SystemClock.elapsedRealtime()
            var lastRun = 0L
            var lastFocus = 0L
            analysis.setAnalyzer(executor) { proxy ->
                try {
                    val now = android.os.SystemClock.elapsedRealtime()
                    // Throttle to about 8 decodes a second.
                    if (done.get() || now - lastRun < 120) return@setAnalyzer
                    lastRun = now
                    val size = viewSize.get()
                    val raw = decoder.decode(proxy)
                    if (raw != null) {
                        if (done.compareAndSet(false, true)) {
                            ContextCompat.getMainExecutor(context).execute { Haptics.success(context); onCode(raw) }
                        }
                        return@setAnalyzer
                    }
                    // Nothing found: refocus on the centre now and then (screens fool continuous AF).
                    if (now - lastFocus > 2500) {
                        lastFocus = now
                        val cam = cameraRef.get()
                        val pt = focusPoint.get()
                        if (cam != null && pt != null) {
                            cam.cameraControl.startFocusAndMetering(androidx.camera.core.FocusMeteringAction.Builder(pt).disableAutoCancel().build())
                        }
                    }
                } finally {
                    proxy.close()
                }
            }
            provider.unbindAll()
            val camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            cameraRef.set(camera)
            // Keep refocusing on the centre, where the guide square is.
            val centre = view.meteringPointFactory.createPoint(view.width / 2f, view.height / 2f)
            focusPoint.set(centre)
            camera.cameraControl.startFocusAndMetering(
                androidx.camera.core.FocusMeteringAction.Builder(centre).setAutoCancelDuration(2, java.util.concurrent.TimeUnit.SECONDS).build())
        }, ContextCompat.getMainExecutor(ctx))
        view
    }, modifier = Modifier.fillMaxSize())
}
