package com.Luis.chironproject

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Luis.chironproject.services.OverlayService
import com.Luis.chironproject.ui.theme.ChironProjectTheme

class MainActivity : ComponentActivity() {

    private lateinit var projectionManager: MediaProjectionManager

    // Recebe o resultado do popup "Permitir captura de tela?"
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startOverlayService(result.resultCode, result.data!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            ChironProjectTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onStartMonitoring = { checkPermissionsAndStart() }
                    )
                }
            }
        }
    }

    private fun checkPermissionsAndStart() {
        // Passo 1: verifica permissão de overlay (SYSTEM_ALERT_WINDOW)
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        // Passo 2: pede permissão de captura de tela
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(intent)
    }
}

@Composable
fun MainScreen(onStartMonitoring: () -> Unit) {
    var monitoringStarted by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Chiron Project",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Monitoramento de conteúdo para crianças",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = {
                monitoringStarted = true
                onStartMonitoring()
            }
        ) {
            Text(
                text = if (monitoringStarted) "Monitoramento ativo ✓" else "Iniciar monitoramento"
            )
        }

        if (monitoringStarted) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "O app está analisando o conteúdo em segundo plano.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}