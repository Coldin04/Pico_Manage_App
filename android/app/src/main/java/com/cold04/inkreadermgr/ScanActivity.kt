package com.cold04.inkreadermgr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.cold04.inkreadermgr.ui.theme.PicoManageTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScanActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ADDRESS = "address"
    }

    private lateinit var controller: LifecycleCameraController
    private lateinit var scanner: BarcodeScanner
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val completed = AtomicBoolean(false)
    private var cameraAllowed by mutableStateOf(false)
    private var torchOn by mutableStateOf(false)
    private var torchAvailable by mutableStateOf(false)
    private var scanError by mutableStateOf<String?>(null)
    private fun showScanError(message: String) {
        scanError = message.ifBlank { "无法处理二维码，请重试" }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraAllowed = granted
        if (granted) startCamera()
    }

    private val chooseImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val image = InputImage.fromFilePath(this, uri)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val value = barcodes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
                    if (value == null) showScanError("图片中未找到二维码")
                    else finishWithAddress(value)
                }
                .addOnFailureListener { showScanError("图片识别失败，请重试") }
        } catch (_: Exception) {
            showScanError("无法读取所选图片")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = LifecycleCameraController(this).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        }
        scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
        cameraAllowed = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )

        setContent {
            PicoManageTheme { ScanPage(
                controller = controller,
                cameraAllowed = cameraAllowed,
                torchOn = torchOn,
                torchAvailable = torchAvailable,
                error = scanError,
                onDismissError = { scanError = null },
                onBack = ::finish,
                onRequestCamera = { cameraPermission.launch(Manifest.permission.CAMERA) },
                onTorch = {
                    val next = !torchOn
                    controller.enableTorch(next)
                    torchOn = next
                },
                onGallery = {
                    chooseImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            ) }
        }
        if (cameraAllowed) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        controller.setImageAnalysisAnalyzer(analysisExecutor) { frame ->
            val mediaImage = frame.image
            if (mediaImage == null || completed.get()) {
                frame.close()
                return@setImageAnalysisAnalyzer
            }
            val image = InputImage.fromMediaImage(mediaImage, frame.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val value = barcodes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
                    if (value != null) finishWithAddress(value)
                }
                .addOnCompleteListener { frame.close() }
        }
        controller.bindToLifecycle(this)
        torchAvailable = controller.cameraInfo?.hasFlashUnit() == true
    }

    private fun finishWithAddress(value: String) {
        if (!completed.compareAndSet(false, true)) return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ADDRESS, value))
        finish()
    }

    override fun onDestroy() {
        controller.clearImageAnalysisAnalyzer()
        scanner.close()
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun ScanPage(
    controller: LifecycleCameraController,
    cameraAllowed: Boolean,
    torchOn: Boolean,
    torchAvailable: Boolean,
    error: String?,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
    onRequestCamera: () -> Unit,
    onTorch: () -> Unit,
    onGallery: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraAllowed) {
            AndroidView(
                factory = { context -> PreviewView(context).apply { this.controller = controller } },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("需要相机权限才能扫码", color = Color.White)
                TextButton(onClick = onRequestCamera) { Text("允许使用相机") }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            IconButton(
                onClick = onTorch,
                enabled = cameraAllowed && torchAvailable,
            ) {
                Icon(
                    if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = if (torchOn) "关闭手电筒" else "打开手电筒",
                    tint = Color.White,
                )
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("对准设备二维码", color = Color.White)
            TextButton(
                onClick = onGallery,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                Text("从相册选择")
            }
        }
    }
    OperationErrorDialog(error, onDismissError, title = "二维码识别失败")
}
