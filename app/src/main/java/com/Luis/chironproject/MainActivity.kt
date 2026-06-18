package com.Luis.chironproject

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.Luis.chironproject.services.OverlayService
import com.Luis.chironproject.ui.theme.ChironProjectTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val TAG = "ChironDebug"
    }

    private lateinit var projectionManager: MediaProjectionManager

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "projectionLauncher retornou. resultCode=${result.resultCode} (OK=${Activity.RESULT_OK}), data=${result.data}")
        toast("Captura: resultCode=${result.resultCode}")

        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.d(TAG, "Permissão de captura CONCEDIDA. Iniciando serviço com dados válidos.")
            toast("Permissão de captura concedida ✓")
            startOverlayService(result.resultCode, result.data!!)
        } else {
            Log.e(TAG, "Permissão de captura NEGADA ou cancelada. resultCode=${result.resultCode}")
            toast("Captura de tela foi negada ou cancelada")
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Log.d(TAG, "Voltou das configurações de overlay. canDrawOverlays=${Settings.canDrawOverlays(this)}")
        if (Settings.canDrawOverlays(this)) {
            toast("Permissão de overlay concedida ✓")
            requestScreenCapture()
        } else {
            toast("Permissão de overlay ainda não foi concedida")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")
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
        Log.d(TAG, "checkPermissionsAndStart. canDrawOverlays=${Settings.canDrawOverlays(this)}")

        if (!Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Sem permissão de overlay. Abrindo configurações.")
            toast("Ative 'Exibir sobre outros apps' e volte")
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao abrir configurações de overlay: ${e.message}", e)
                toast("Erro ao abrir permissão de overlay")
            }
            return
        }

        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        Log.d(TAG, "requestScreenCapture. Lançando popup de captura de tela.")
        toast("Confirme a captura de tela")
        try {
            val captureIntent = projectionManager.createScreenCaptureIntent()
            projectionLauncher.launch(captureIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao lançar popup de captura: ${e.message}", e)
            toast("Erro ao pedir captura de tela")
        }
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        Log.d(TAG, "startOverlayService chamado com resultCode=$resultCode")
        try {
            val intent = Intent(this, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                putExtra(OverlayService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(intent)
            Log.d(TAG, "startForegroundService disparado com sucesso.")
            toast("Monitoramento iniciado ✓")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar o serviço: ${e.message}", e)
            toast("Erro ao iniciar o serviço")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
        // Logo do escudo Chiron no topo
        Image(
            painter = painterResource(id = R.drawable.logo_escudo_chiron_laranja),
            contentDescription = "Chiron",
            modifier = Modifier.size(160.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "CHIRON",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Monitoramento de conteúdo para crianças",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = {
                monitoringStarted = true
                onStartMonitoring()
            }
        ) {
            Text(
                text = if (monitoringStarted) "Monitoramento ativo" else "Iniciar monitoramento"
            )
        }

        if (monitoringStarted) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "O app está analisando o conteúdo em segundo plano.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
        }
    }
}