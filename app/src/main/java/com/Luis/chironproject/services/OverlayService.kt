package com.Luis.chironproject.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
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
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
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

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var windowManager: WindowManager
    private var overlayView: ViewGroup? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var checkCounter = 0

    @Volatile
    private var overlayVisible = false

    // Trocado para gemini-2.5-flash-lite: cota diária bem maior (1.000 req/dia vs 250),
    // ideal pra testar e gravar a demo sem estourar o limite gratuito.
    private val gemini = Firebase.ai(backend = GenerativeBackend.googleAI())
        .generativeModel("gemini-2.5-flash-lite")

    companion object {
        const val TAG = "ChironDebug"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "chiron_overlay_channel"
        const val NOTIF_ID = 1
        const val MAX_BITMAP_WIDTH = 720
        const val JPEG_QUALITY = 75

        // valor sentinela "inválido". NÃO usar -1, pois -1 é o RESULT_OK do Android!
        const val INVALID_RESULT_CODE = 0
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

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, INVALID_RESULT_CODE) ?: INVALID_RESULT_CODE
        val resultData = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        // O serviço PRECISA virar foreground ANTES de iniciar a captura.
        // No Android 14+ é obrigatório declarar o tipo MEDIA_PROJECTION aqui na chamada,
        // não só no manifesto — senão o sistema bloqueia a captura por segurança.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        if (resultCode != INVALID_RESULT_CODE && resultData != null) {
            try {
                android.util.Log.d(TAG, "Os responsáveis ativaram a proteção. O Chiron está iniciando a vigilância da tela da criança.")
                setupMediaProjection(resultCode, resultData)
                startPeriodicCheck()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Não foi possível iniciar a proteção da tela. Detalhe técnico: ${e.message}", e)
                stopSelf()
            }
        } else {
            android.util.Log.w(TAG, "A proteção ainda não pôde começar porque falta a autorização da criança/responsável para observar a tela.")
        }

        return START_STICKY
    }

    private fun setupMediaProjection(resultCode: Int, resultData: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                android.util.Log.d(TAG, "A vigilância da tela foi encerrada. O Chiron parou de observar.")
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
        android.util.Log.d(TAG, "Tudo pronto! O Chiron agora consegue enxergar o que aparece na tela da criança, em tempo real.")
    }

    private fun startPeriodicCheck() {
        android.util.Log.d(TAG, "A proteção está ativa. A cada poucos segundos, o Chiron vai olhar a tela e decidir se o conteúdo é seguro.")
        serviceScope.launch {
            // OVERLAY DE TESTE: aparece ao iniciar e some após 5s.
            android.util.Log.d(TAG, "Demonstração: veja como fica a tela de proteção quando algo impróprio é detectado.")
            showOverlay()
            delay(5000L)
            hideOverlay()
            android.util.Log.d(TAG, "Demonstração encerrada. Agora o Chiron entra em modo de vigilância real.")

            while (true) {
                delay(Constants.OVERLAY_CHECK_INTERVAL_MS)

                if (overlayVisible) {
                    android.util.Log.d(TAG, "A tela de proteção está no ar, bloqueando o conteúdo impróprio. O Chiron aguarda a criança trocar de vídeo.")
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
                    } else {
                        android.util.Log.d(TAG, "A tela não mudou desde a última checagem. O Chiron continua de olho, sem desperdiçar análises.")
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
            TAG,
            "Foto da tela preparada e otimizada para envio (apenas ${byteArray.size / 1024}KB), garantindo análise rápida e econômica."
        )
        return compressed
    }

    private suspend fun checkWithGeminiAck() {
        try {
            android.util.Log.d(TAG, "Verificando se a conexão com a inteligência artificial está ativa e saudável...")
            val response = gemini.generateContent(
                content {
                    text("Por favor, responda apenas com a sigla ACK para verificarmos se a conexão está ativa.")
                }
            )
            val result = response.text?.trim()?.uppercase()
            android.util.Log.d(TAG, "Conexão confirmada. A inteligência artificial está pronta para proteger a criança. (resposta: $result)")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "A conexão com a inteligência artificial falhou neste momento. Detalhe técnico: ${e.message}", e)
        }
    }

    private suspend fun checkWithGeminiImage(bitmap: Bitmap) {
        val compressed = compressBitmapForApi(bitmap) ?: run {
            android.util.Log.e(TAG, "Não foi possível preparar a foto da tela desta vez. O Chiron tentará novamente no próximo ciclo.")
            bitmap.recycle()
            return
        }

        try {
            android.util.Log.d(TAG, "Enviando a imagem da tela da criança para a inteligência artificial, junto com as regras definidas pelos responsáveis: nada de violência, luta, conteúdo adulto ou assustador.")
            val response = gemini.generateContent(
                content {
                    image(compressed)
                    text(
                        "Descreva em até 10 palavras o que você vê nesta imagem. " +
                                "Depois, em uma nova linha, responda: a imagem é apropriada para uma " +
                                "criança de 6 anos que não pode ver NENHUMA violência, luta, briga ou esporte de combate? " +
                                "Responda no formato: DESCRICAO: <o que vê> | VEREDITO: ADEQUADO ou INADEQUADO"
                    )
                }
            )
            val result = response.text?.trim()?.uppercase() ?: return
            android.util.Log.d(TAG, "A inteligência artificial analisou a tela e relatou o seguinte: $result")
            if (result.contains("INADEQUADO")) {
                android.util.Log.d(TAG, "ALERTA: conteúdo impróprio detectado! O Chiron está cobrindo a tela imediatamente para proteger a criança.")
                showOverlay()
            } else {
                android.util.Log.d(TAG, "Conteúdo seguro. A criança pode continuar assistindo tranquilamente. Nada a bloquear.")
                hideOverlay()
            }
        } catch (e: Exception) {
            if (e.message?.contains("quota", ignoreCase = true) == true) {
                android.util.Log.w(TAG, "Atingimos o limite de análises gratuitas da inteligência artificial por agora. O Chiron aguarda alguns segundos e volta a proteger — isso é normal no plano gratuito durante demonstrações.")
            } else {
                android.util.Log.e(TAG, "Ocorreu um imprevisto ao falar com a inteligência artificial. Detalhe técnico: ${e.message}", e)
            }
        } finally {
            bitmap.recycle()
            compressed.recycle()
        }
    }

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
            android.util.Log.d(TAG, "Tela de proteção exibida. A criança está protegida do conteúdo impróprio neste momento.")
        }
    }

    private suspend fun hideOverlay() {
        withContext(Dispatchers.Main) {
            overlayView?.let {
                windowManager.removeView(it)
                overlayView = null
                android.util.Log.d(TAG, "O conteúdo voltou a ser seguro. A tela de proteção foi retirada e a criança pode continuar.")
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
            .setContentText("Protegendo a criança...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        serviceScope.cancel()
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