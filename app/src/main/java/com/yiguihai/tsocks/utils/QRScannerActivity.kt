package com.yiguihai.tsocks.utils

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.yiguihai.tsocks.ui.theme.TSocksTheme

/**
 * 二维码扫描活动
 * 用于扫描Shadowsocks链接的二维码
 */
class QRScannerActivity : ComponentActivity() {
    private lateinit var barcodeView: DecoratedBarcodeView
    private var isScanning by mutableStateOf(false)
    private var isInitialized by mutableStateOf(false)

    // 处理扫描结果的回调
    private val callback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult) {
            if (!isScanning) return
            
            isScanning = false
            val text = result.text
            Log.d("QRScanner", "扫描结果: $text")

            if (text.startsWith("ss://")) {
                // 返回SS链接结果
                setResult(RESULT_OK, Intent().apply { 
                    putExtra("ssUri", text) 
                })
                finish()
            } else {
                // 继续扫描
                Toast.makeText(this@QRScannerActivity, "不是Shadowsocks链接", Toast.LENGTH_SHORT).show()
                isScanning = true
                barcodeView.resume()
            }
        }

        override fun possibleResultPoints(resultPoints: List<ResultPoint>) {}
    }

    // 权限请求
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            initializeCamera()
        } else {
            Toast.makeText(this, "需要相机权限来扫描二维码", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 检查相机权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            setContent {
                TSocksTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (!isInitialized) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            }
            initializeCamera()
        }
    }

    private fun initializeCamera() {
        barcodeView = DecoratedBarcodeView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            initializeFromIntent(intent)
            decodeContinuous(callback)
        }
        
        setContentView(barcodeView)
        isInitialized = true
        isScanning = true
    }

    override fun onResume() {
        super.onResume()
        if (::barcodeView.isInitialized) {
            barcodeView.resume()
            isScanning = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (::barcodeView.isInitialized) {
            barcodeView.pause()
            isScanning = false
        }
    }
} 