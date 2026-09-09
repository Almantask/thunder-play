package com.thunderplay

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import com.thunderplay.stats.FirestoreStats
import com.thunderplay.ui.ThunderPlayApp
import com.thunderplay.ui.theme.ThunderPlayTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var stats: FirestoreStats

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ThunderPlayTheme {
                // Without this the media notification is silently suppressed on Android 13+,
                // which also removes the lock-screen transport controls.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val request = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { }
                    LaunchedEffect(Unit) { request.launch(Manifest.permission.POST_NOTIFICATIONS) }
                }

                val signInLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    lifecycleScope.launch {
                        stats.handleSignInResult(result.data)
                    }
                }

                LaunchedEffect(Unit) {
                    if (stats.isAvailable && !stats.isAuthorized) {
                        stats.ensureSignedIn()
                        if (!stats.isAuthorized) {
                            signInLauncher.launch(stats.getGoogleSignInIntent())
                        }
                    }
                }

                ThunderPlayApp()
            }
        }
    }
}
