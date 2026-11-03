package app.ihearyou

import android.Manifest
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.ihearyou.domain.SettingsStore
import app.ihearyou.service.RemoteService
import app.ihearyou.ui.IHearYouApp
import app.ihearyou.ui.theme.AppTheme
import app.ihearyou.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    companion object {
        /** On screen: the share sheet can open directly instead of through a notification. */
        @Volatile var visible = false
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(app.ihearyou.domain.Language.wrap(newBase))
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { startIfPermitted() }

    /** The connectedDevice service may only start once BLUETOOTH_CONNECT is granted (Android 14+). */
    private fun startIfPermitted() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) RemoteService.start(this)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        // while visible the service may take the microphone type, so voice notes then work in
        // the background too (Android 14 refuses it to a service started at boot)
        startIfPermitted()
        RemoteService.kickReconnect()
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stored = SettingsStore(this).theme()
        val darkAtLaunch = when (stored) {
            ThemeMode.SYSTEM -> (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(if (darkAtLaunch) 0xFF15180F.toInt() else 0xFFE4E7DB.toInt()),
        )
        enableEdgeToEdge()
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
        startIfPermitted()
        setContent {
            val settings = remember { SettingsStore(this) }
            var theme by remember { mutableStateOf(stored) }
            val dark = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                )
            }
            AppTheme(mode = theme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    IHearYouApp(
                        themeMode = theme,
                        onTheme = { settings.setTheme(it); theme = it },
                    )
                }
            }
        }
    }
}
