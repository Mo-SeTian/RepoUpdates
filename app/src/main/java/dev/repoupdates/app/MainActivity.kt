package dev.repoupdates.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val openRelease = MutableStateFlow<Long?>(null)
    private var notificationsAllowed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        if (savedInstanceState == null) route(intent)
        val tracker = (application as TrackerApplication).tracker
        setContent {
            TrackerUi(tracker, openRelease, notificationsAllowed, onThemeChanged = { dark ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }, onPermissionChanged = { notificationsAllowed = systemNotificationsEnabled(this) })
        }
    }

    override fun onResume() {
        super.onResume()
        notificationsAllowed = systemNotificationsEnabled(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    private fun route(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme == "repoupdates" && uri.host == "release") {
            openRelease.value = uri.lastPathSegment?.toLongOrNull()?.takeIf { it > 0 }
        }
    }
}

internal fun openWeb(context: Context, uri: Uri) {
    if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "未找到可打开链接的浏览器", Toast.LENGTH_SHORT).show()
    }
}
