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
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.Luis.chironproject.utils.Constants
// --- IMPORTS NOVOS: Firebase AI Logic (substituem com.google.ai.client.generativeai) ---
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
// ----------------------------------------------------------------------------------------
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    // PRIORIDADE 6: scope agora roda em Default (trabalho pesado fora da main thread)
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var windowManager: WindowManager
    private var overlayView: ViewGroup? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var checkCounter = 0

    // PRIORIDADE 5: flag pra pausar a captura enquanto o overlay está visível
    @Volatile
    private var overlayVisible = false

    // PRIORIDADE 2 e 3: inicialização via Firebase AI Logic + modelo vivo (gemini-2.5-flash)
    private val gemini = Firebase.ai(backend = GenerativeBackend.googleAI())
        .generativeModel("gemini-2.5-flash")

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "chiron_overlay_channel"
        const val NOTIF_ID = 1
        const val MAX_BITMAP_WIDTH = 720
        const val JPEG_QUALITY = 75
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
        val resultData = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        android.util.Log.d("ChironDebug", "onStartCommand chamado. resultCode=$resultCode")

        startForeground(NOTIF_ID, buildNotification())

        if (resultCode != -1 && resultData != null) {
            // PRIORIDADE 1: setup protegido por try/catch pra não derrubar o serviço
            try {
                setupMediaProjection(resultCode, resultData)
                startPeriodicCheck()
            } catch (e: Exception) {
                android.util.Log.e("ChironDebug", "Falha ao iniciar MediaProjection: ${e.message}", e)
                stopSelf()
            }
        } else {
            android.util.Log.w("ChironDebug", "Serviço iniciado sem MediaProjection — aguardando dados válidos.")
        }

        return START_STICKY
    }

    private fun setupMediaProjection(resultCode: Int, resultData: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        // PRIORIDADE 1: registerCallback é OBRIGATÓRIO no Android 14+ (targetSdk 36).
        // Sem isso, createVirtualDisplay lança IllegalStateException e o serviço quebra.
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                android.util.Log.d("ChironDebug", "MediaProjection parada pelo sistema.")
                stopSelf()
            }
        }, handler)

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

    private fun startPeriodicCheck() {
        serviceScope.launch {
            while (true) {
                delay(Constants.OVERLAY_CHECK_INTERVAL_MS)

                // PRIORIDADE 5: se o overlay está visível, não captura
                // (senão capturaria a própria tela preta do overlay → loop de piscar)
                if (overlayVisible) {
                    android.util.Log.d("ChironDebug", "Overlay visível — pulando captura neste ciclo.")
                    // ainda faz um ACK ocasional pra confirmar conexão
                    if (checkCounter % 6 == 0) checkWithGeminiAck()
                    checkCounter++
                    continue
                }

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

    // PRIORIDADE 4: retorna null em vez de quebrar se a compressão falhar
    private fun compressBitmapForApi(original: Bitmap): Bitmap? {
        val scale = MAX_BITMAP_WIDTH.toFloat() / original.width
        val scaledHeight = (original.height * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(original, MAX_BITMAP_WIDTH, scaledHeight, true)

        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        val byteArray = stream.toByteArray()
        val compressed = android.graphics.BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)

        if (scaled != original) scaled.recycle()

        android.util.Log.d(
            "ChironDebug",
            "Bitmap original: ${original.width}x${original.height} | " +
                    "Comprimido: ${compressed?.width}x${compressed?.height} | " +
                    "Tamanho JPEG: ${byteArray.size / 1024}KB"
        )
        return compressed
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
        val compressed = compressBitmapForApi(bitmap) ?: run {
            android.util.Log.e("ChironDebug", "Falha ao comprimir bitmap — pulando frame.")
            bitmap.recycle()
            return
        }

        try {
            val response = gemini.generateContent(
                content {
                    image(compressed)
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
            android.util.Log.d("ChironDebug", "Resposta Gemini: $result")
            if (result.contains("INADEQUADO")) {
                showOverlay()
            } else {
                hideOverlay()
            }
        } catch (e: Exception) {
            android.util.Log.e("ChironDebug", "Erro na API Gemini: ${e.message}", e)
        } finally {
            bitmap.recycle()
            compressed.recycle()
        }
    }

    // PRIORIDADE 6: addView precisa rodar na main thread; usamos withContext(Main)
    private suspend fun showOverlay() {
        if (overlayVisible) return
        withContext(Dispatchers.Main) {
            if (overlayView != null) return@withContext
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

            val composeView = ComposeView(this@OverlayService).apply {
                setViewTreeLifecycleOwner(this@OverlayService)
                setViewTreeSavedStateRegistryOwner(this@OverlayService)
                setContent {
                    com.Luis.chironproject.ui.screens.OverlayScreen()
                }
            }

            overlayView = composeView
            windowManager.addView(composeView, params)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            overlayVisible = true
        }
    }

    private suspend fun hideOverlay() {
        withContext(Dispatchers.Main) {
            overlayView?.let {
                windowManager.removeView(it)
                overlayView = null
            }
            overlayVisible = false
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
        // hideOverlay direto na main (onDestroy não é suspend)
        handler.post {
            overlayView?.let {
                windowManager.removeView(it)
                overlayView = null
            }
        }
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        super.onDestroy()
    }
}