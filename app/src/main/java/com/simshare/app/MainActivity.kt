package com.simshare.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var etPcUrl: EditText
    private lateinit var btnPing: Button
    private lateinit var btnFlash: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvDetectedPhone: TextView
    private lateinit var tvDetectedExtra: TextView
    private lateinit var switchAutoSend: Switch
    private lateinit var btnSendNow: Button
    private lateinit var btnCopy: Button

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var camera: Camera? = null
    private var flashOn = false

    private var currentRawPhone = ""
    private var currentFormattedPhone = ""
    private var currentIccid = ""
    private var currentPukPin = ""

    private var lastCandidatePhone = ""
    private var candidateHits = 0
    private var lastSentPhone = ""
    private var lastSentTimeMs = 0L

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else tvStatus.text = "المرجو منح صلاحية الكاميرا للتطبيق"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        etPcUrl = findViewById(R.id.etPcUrl)
        btnPing = findViewById(R.id.btnPing)
        btnFlash = findViewById(R.id.btnFlash)
        tvStatus = findViewById(R.id.tvStatus)
        tvDetectedPhone = findViewById(R.id.tvDetectedPhone)
        tvDetectedExtra = findViewById(R.id.tvDetectedExtra)
        switchAutoSend = findViewById(R.id.switchAutoSend)
        btnSendNow = findViewById(R.id.btnSendNow)
        btnCopy = findViewById(R.id.btnCopy)

        val prefs = getSharedPreferences("sim_share_prefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("pc_url", "http://10.16.132.88:8765")
        etPcUrl.setText(savedUrl)

        btnPing.setOnClickListener {
            val url = cleanBaseUrl(etPcUrl.text.toString())
            prefs.edit().putString("pc_url", url).apply()
            pingPcServer(url)
        }

        btnFlash.setOnClickListener {
            flashOn = !flashOn
            camera?.cameraControl?.enableTorch(flashOn)
            btnFlash.text = if (flashOn) "Flash ON" else "Flash"
        }

        btnSendNow.setOnClickListener {
            if (currentRawPhone.isEmpty()) {
                Toast.makeText(this, "وجه الكاميرا لبطاقة SIM أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val url = cleanBaseUrl(etPcUrl.text.toString())
            prefs.edit().putString("pc_url", url).apply()
            sendToPc(url, currentFormattedPhone, currentRawPhone, currentIccid, currentPukPin)
        }

        btnCopy.setOnClickListener {
            if (currentRawPhone.isNotEmpty()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("SIM Phone", currentRawPhone))
                Toast.makeText(this, "تم النسخ: $currentRawPhone", Toast.LENGTH_SHORT).show()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun cleanBaseUrl(input: String): String {
        var u = input.trim().trimEnd('/')
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://$u"
        }
        return u
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { proxy -> processFrame(proxy) }
                }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analyzer
                )
            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = "خطأ في تشغيل الكاميرا: ${e.message}" }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                parseAndHandleText(visionText.text)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun parseAndHandleText(rawText: String) {
        if (rawText.isBlank()) return
        val normalized = rawText
            .replace('O', '0').replace('o', '0')
            .replace('I', '1').replace('l', '1')

        var phone = ""
        val intl = Regex("""(?:\+|00)?212[\s.-]?([67]\d(?:[\s.-]?\d){7})""").find(normalized)
        if (intl != null) {
            val tail = intl.groupValues[1].filter { it.isDigit() }
            if (tail.length == 9) phone = "0$tail"
        }
        if (phone.isEmpty()) {
            val local = Regex("""0[67]\d(?:[\s.-]?\d){7}""").find(normalized)
            if (local != null) {
                val digits = local.value.filter { it.isDigit() }
                if (digits.length == 10) phone = digits
            }
        }
        if (phone.isEmpty()) return

        if (phone == lastCandidatePhone) {
            candidateHits++
        } else {
            lastCandidatePhone = phone
            candidateHits = 1
            return
        }
        if (candidateHits < 2) return

        val compact = normalized.replace(Regex("""[\s-]"""), "")
        val iccid = Regex("""89212\d{13,15}""").find(compact)?.value
            ?: Regex("""89\d{16,18}""").find(compact)?.value
            ?: ""
        val puk = Regex("""PUK\s*1?\s*[:=-]?\s*(\d{8})""", RegexOption.IGNORE_CASE)
            .find(rawText)?.groupValues?.getOrNull(1) ?: ""
        val pukPin = if (puk.isNotEmpty()) "PUK: $puk" else ""

        val formatted = phone.chunked(2).joinToString(" ")
        currentRawPhone = phone
        currentFormattedPhone = formatted
        currentIccid = iccid
        currentPukPin = pukPin

        runOnUiThread {
            tvDetectedPhone.text = formatted
            tvDetectedExtra.text = if (iccid.isNotEmpty()) "ICCID: $iccid  $pukPin" else "ICCID: —"
        }

        val now = System.currentTimeMillis()
        if (switchAutoSend.isChecked && (phone != lastSentPhone || now - lastSentTimeMs > 4000L)) {
            lastSentPhone = phone
            lastSentTimeMs = now
            vibrateOnce()
            val url = cleanBaseUrl(etPcUrl.text.toString())
            sendToPc(url, formatted, phone, iccid, pukPin)
        }
    }

    private fun vibrateOnce() {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(70)
            }
        } catch (_: Exception) {}
    }

    private fun pingPcServer(baseUrl: String) {
        runOnUiThread { tvStatus.text = "جاري فحص الاتصال بالبيسي..." }
        networkExecutor.execute {
            try {
                val conn = (URL("$baseUrl/api/ping").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2500
                    readTimeout = 2500
                    requestMethod = "GET"
                }
                val code = conn.responseCode
                conn.disconnect()
                runOnUiThread {
                    tvStatus.text = if (code == 200) {
                        "متصل بالبيسي عبر الويفي — جاهز للمسح الفوري!"
                    } else {
                        "تعذر الاتصال (كود $code) — تأكد من رابط البيسي"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "البيسي غير متصل — تأكد من نفس الويفي والرابط الصحيح"
                }
            }
        }
    }

    private fun sendToPc(baseUrl: String, formatted: String, raw: String, iccid: String, pukPin: String) {
        networkExecutor.execute {
            try {
                val payload = JSONObject().apply {
                    put("id", "sim_${System.currentTimeMillis()}")
                    put("phone", formatted)
                    put("rawPhone", raw)
                    put("iccid", iccid)
                    put("pukPin", pukPin)
                    put("senderCode", "android-apk")
                    put("timestamp", System.currentTimeMillis())
                }
                val conn = (URL("$baseUrl/api/send").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write(payload.toString())
                }
                val code = conn.responseCode
                conn.disconnect()
                runOnUiThread {
                    tvStatus.text = if (code == 200) {
                        "تم إرسال $formatted للبيسي (Ctrl+V جاهز)!"
                    } else {
                        "فشل الإرسال (كود $code)"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "تعذر الإرسال للبيسي — تأكد أن تطبيق البيسي مفتوح وعلى نفس الويفي"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        networkExecutor.shutdown()
        recognizer.close()
    }
}
