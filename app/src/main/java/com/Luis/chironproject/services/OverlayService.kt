package com.Luis.chironproject.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.Luis.chironproject.utils.Constants
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    // Lifecycle necessário pro ComposeView funcionar fora de uma Activity
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var windowManager: WindowManager
    private var overlayView: ViewGroup? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    private val gemini = GenerativeModel(
        modelName = "gemini-1.5-flash",
        apiKey = Constants.GEMINI_API_KEY
    )

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "chiron_overlay_channel"
        const val NOTIF_ID = 1
    }

    override fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        // ✅ Log AQUI, depois de declarar as variáveis
        android.util.Log.d("ChironDebug", "onStartCommand chamado. resultCode=$resultCode, resultData=$resultData")

        if (resultCode != -1 && resultData != null) {
            startForeground(NOTIF_ID, buildNotification())
            setupMediaProjection(resultCode, resultData)
            startPeriodicCheck()
        }

        return START_STICKY
    }

    private fun setupMediaProjection(resultCode: Int, resultData: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ChironCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private var checkCounter = 0

    private fun startPeriodicCheck() {
        serviceScope.launch {
            while (true) {
                delay(Constants.OVERLAY_CHECK_INTERVAL_MS)
                
                // A cada minuto (tendo intervalos de 10s: a 1ª requisição no minuto será o ACK, depois de 6 requisições fará outra vez)
                if (checkCounter % 6 == 0) {
                    checkWithGeminiAck()
                } else {
                    val bitmap = captureScreen()
                    if (bitmap != null) {
                        checkWithGeminiImage(bitmap)
                    }
                }
                checkCounter++
            }
        }
    }

    private fun captureScreen(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        } finally {
            image.close()
        }
    }

    private suspend fun checkWithGeminiAck() {
        try {
            val response = gemini.generateContent(
                content {
                    text("Por favor, responda apenas com a sigla ACK para verificarmos se a conexão está ativa.")
                }
            )
            val result = response.text?.trim()?.uppercase()
            android.util.Log.d("ChironDebug", "Comunicação Gemini (ACK) Recebida: $result")
        } catch (e: Exception) {
            android.util.Log.e("ChironDebug", "Erro na API Gemini no ACK: ${e.message}", e)
        }
    }

    private suspend fun checkWithGeminiImage(bitmap: Bitmap) {
        // Reduzir resolução para envio (evita gasto excessivo de memória/tráfego)
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
        
        try {
            val response = gemini.generateContent(
                content {
                    image(scaledBitmap)
                    text(
                        "Analise este frame de vídeo e responda APENAS com INADEQUADO se contiver " +
                                "qualquer um desses elementos: violência, sangue, armas, linguagem adulta, " +
                                "conteúdo sexual, terror, drogas ou álcool. " +
                                "Caso contrário, responda APENAS com ADEQUADO. " +
                                "Nenhuma explicação, apenas uma palavra."
                    )
                }
            )
            val result = response.text?.trim()?.uppercase() ?: return
            if (result.contains("INADEQUADO")) {
                showOverlay()
            } else {
                hideOverlay()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("ChironDebug", "Erro na API Gemini: ${e.message}", e)
        } finally {
            bitmap.recycle() // Libera memória do print original
            scaledBitmap.recycle()
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return
        handler.post {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // FLAG_NOT_TOUCHABLE = overlay não recebe toques (a criança não consegue fechar)
                // FLAG_NOT_FOCUSABLE = não interfere no foco do app por baixo
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@OverlayService)
                setViewTreeSavedStateRegistryOwner(this@OverlayService)
                setContent {
                    com.Luis.chironproject.ui.screens.OverlayScreen()
                }
            }

            overlayView = composeView
            windowManager.addView(composeView, params)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
    }

    private fun hideOverlay() {
        handler.post {
            overlayView?.let {
                windowManager.removeView(it)
                overlayView = null
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Chiron Monitor",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Chiron ativo")
            .setContentText("Monitorando conteúdo...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        serviceScope.cancel()
        hideOverlay()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        super.onDestroy()
    }
}