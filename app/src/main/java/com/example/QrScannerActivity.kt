package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Built-in CameraX + ML Kit QR & Barcode Scanner launched directly by SmartDot's QR Scanner shortcut.
 * Supports Open URL, Copy Text, and Pay UPI (upi://) actions with zero vibration.
 */
class QrScannerActivity : ComponentActivity() {

    private var hasCameraPermission by mutableStateOf(false)
    private var scannedValue by mutableStateOf<String?>(null)
    private var scannerClosed = false

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val barcodeScanner by lazy { BarcodeScanning.getClient() }
    private var previewViewRef: PreviewView? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            previewViewRef?.let { startCamera(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            MyApplicationTheme {
                QrScannerScreen(
                    hasPermission = hasCameraPermission,
                    scannedValue = scannedValue,
                    onPreviewReady = { view ->
                        previewViewRef = view
                        if (hasCameraPermission) {
                            startCamera(view)
                        }
                    },
                    onRequestPermission = {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onCopy = { value ->
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("QR Scan", value))
                        Toast.makeText(this, getString(R.string.qr_copied_toast), Toast.LENGTH_SHORT).show()
                    },
                    onOpenOrPay = { value ->
                        launchScannedAction(value)
                    },
                    onScanAgain = {
                        scannedValue = null
                    },
                    onClose = {
                        finish()
                    }
                )
            }
        }
    }

    private fun startCamera(previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (scannerClosed || isDestroyed || isFinishing) return@addListener
            val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { imageAnalysis ->
                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            }.onFailure { e ->
                Log.e(TAG, "CameraX bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (scannedValue != null || scannerClosed) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val raw = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue?.trim()
                if (!raw.isNullOrEmpty() && scannedValue == null) {
                    runOnUiThread {
                        if (scannedValue == null) {
                            scannedValue = raw
                        }
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    internal fun onBarcodeDetectedForTest(value: String) {
        if (value.isNotBlank()) {
            scannedValue = value.trim()
        }
    }

    private fun launchScannedAction(value: String) {
        val intent = buildScannedActionIntent(value)
        if (intent != null) {
            val ok = runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
            if (!ok) {
                Toast.makeText(this, getString(R.string.qr_no_handler_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        scannerClosed = true
        super.onDestroy()
        runCatching { barcodeScanner.close() }
        runCatching { cameraExecutor.shutdownNow() }
    }

    companion object {
        private const val TAG = "QrScannerActivity"

        fun isUpiUri(raw: String): Boolean =
            raw.trim().startsWith("upi://", ignoreCase = true)

        fun isWebUrl(raw: String): Boolean {
            val lower = raw.trim().lowercase()
            return lower.startsWith("http://") || lower.startsWith("https://")
        }

        fun buildScannedActionIntent(raw: String): Intent? {
            val clean = raw.trim()
            if (clean.isEmpty()) return null
            return if (isUpiUri(clean) || isWebUrl(clean)) {
                Intent(Intent.ACTION_VIEW, Uri.parse(clean)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                null
            }
        }
    }
}

@Composable
private fun QrScannerScreen(
    hasPermission: Boolean,
    scannedValue: String?,
    onPreviewReady: (PreviewView) -> Unit,
    onRequestPermission: () -> Unit,
    onCopy: (String) -> Unit,
    onOpenOrPay: (String) -> Unit,
    onScanAgain: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF090E1A)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            if (hasPermission) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            onPreviewReady(this)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("qr_camera_preview")
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.qr_permission_required),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onRequestPermission,
                        modifier = Modifier.testTag("qr_grant_camera_btn")
                    ) {
                        Text(stringResource(R.string.qr_grant_camera))
                    }
                }
            }

            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.qr_scanner_title),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color(0xAA0F172A), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .background(Color(0xAA0F172A), RoundedCornerShape(24.dp))
                        .testTag("qr_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.screen_copy_close),
                        tint = Color.White
                    )
                }
            }

            // Result Bottom Card
            if (!scannedValue.isNullOrBlank()) {
                val isUpi = QrScannerActivity.isUpiUri(scannedValue)
                val isUrl = QrScannerActivity.isWebUrl(scannedValue)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .testTag("qr_result_card"),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (isUpi) "UPI Payment QR" else if (isUrl) "Website URL" else "Decoded QR / Barcode",
                            color = Color(0xFF00E5FF),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = scannedValue,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("qr_scanned_value_text")
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isUpi || isUrl) {
                                Button(
                                    onClick = { onOpenOrPay(scannedValue) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("qr_primary_action_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.OpenInNew,
                                        contentDescription = null,
                                        tint = Color.Black
                                    )
                                    Text(
                                        text = if (isUpi) stringResource(R.string.qr_pay_upi) else stringResource(R.string.qr_open_url),
                                        color = Color.Black,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 6.dp)
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { onCopy(scannedValue) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("qr_copy_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                                Text(
                                    text = stringResource(R.string.screen_copy_action),
                                    color = Color.White,
                                    modifier = Modifier.padding(start = 6.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onScanAgain,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("qr_scan_again_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF)
                            )
                            Text(
                                text = stringResource(R.string.qr_scan_again),
                                color = Color(0xFF00E5FF),
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
