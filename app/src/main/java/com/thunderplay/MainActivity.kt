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
import com.thunderplay.ui.ThunderPlayApp
import com.thunderplay.ui.theme.ThunderPlayTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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
                ThunderPlayApp()
            }
        }
    }
}
