package moe.n4tsu.agyremote

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import moe.n4tsu.agyremote.state.Texts
import moe.n4tsu.agyremote.state.WorkspaceViewModel
import moe.n4tsu.agyremote.ui.setup.SetupScreen
import moe.n4tsu.agyremote.ui.theme.AppTheme
import moe.n4tsu.agyremote.ui.theme.ThemeSetting
import moe.n4tsu.agyremote.ui.workspace.WorkspaceScreen

class MainActivity : ComponentActivity() {
    private val vm: WorkspaceViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.confirmNotifications(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.localizeStatusWords(this)
        handlePairingLink(intent)
        moe.n4tsu.agyremote.ui.chat.ChatLinks.onLink = vm::handleChatLink
        setContent {
            val dark = when (vm.theme) { ThemeSetting.SYSTEM -> isSystemInDarkTheme(); ThemeSetting.DARK -> true; ThemeSetting.LIGHT -> false }
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
            }
            LaunchedEffect(vm.currentChatId) {
                moe.n4tsu.agyremote.push.PushState.visibleSessionId = vm.currentChatId
                // Opening a chat clears its notification.
                vm.currentChatId?.let { moe.n4tsu.agyremote.push.Notifications.cancel(this@MainActivity, it) }
            }
            LaunchedEffect(vm.notificationPermissionRequest) {
                if (vm.notificationPermissionRequest) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) else vm.confirmNotifications(true)
                }
            }
            AppTheme(vm.theme) {
                Crossfade(vm.showsSetup, label = "root") { setup ->
                    if (setup) SetupScreen(vm) else WorkspaceScreen(vm)
                }
                moe.n4tsu.agyremote.ui.sheets.SecurityDialogs(vm)
                // The relay question during setup (the workspace shows its own dialog).
                if (vm.showsSetup && vm.showRelayAlert) {
                    val c = this@MainActivity
                    AlertDialog(
                        onDismissRequest = { vm.answerRelayConsent(false) },
                        title = { Text(Texts.relayTitle(c)) },
                        text = { Text(Texts.relayMessage(c)) },
                        confirmButton = { TextButton({ vm.answerRelayConsent(true) }) { Text(Texts.relayAllow(c)) } },
                        dismissButton = { TextButton({ vm.answerRelayConsent(false) }) { Text(Texts.relayNotNow(c)) } },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        moe.n4tsu.agyremote.push.PushState.foreground = true
        vm.onForeground()
    }

    override fun onStop() {
        super.onStop()
        moe.n4tsu.agyremote.push.PushState.foreground = false
        vm.onBackground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingLink(intent)
    }

    /** agy-rem://pair?… pairs like a scanned code; agy-rem://session/<id> opens a chat. */
    private fun handlePairingLink(intent: Intent?) {
        // A notification tap carries the chat id (iOS onOpenSession).
        intent?.getStringExtra("sessionId")?.takeIf { it.isNotEmpty() }?.let { vm.handleLink(android.net.Uri.parse("agy-rem://session/$it")); return }
        val data = intent?.data ?: return
        vm.handleLink(data)
    }
}
