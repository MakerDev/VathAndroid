package com.eis.vathandroid

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.eis.vathandroid.camerax.CameraManager
import com.eis.vathandroid.databinding.ActivityEyesightTestBinding
import com.eis.vathandroid.faceDetection.OnSuccessListener
import com.google.mlkit.vision.face.Face
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import kotlin.concurrent.thread


class EyesightTestActivity : AppCompatActivity() {
    private var ipAddress: String? = null
    private var isTargetingLeftEye = false
    private lateinit var binding: ActivityEyesightTestBinding
    private var clientSocket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStreamWriter? = null
    private var isDetectingEye = false

    private var textView: TextView? = null
    private lateinit var cameraManager: CameraManager
    private val detectionResults = mutableListOf<Int>()
    private var lastDetectionTimestamp = System.currentTimeMillis()
    private lateinit var soundPool: SoundPool
    private var soundIdMap = mutableMapOf<Int, Int>()
    private var canClickButton = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEyesightTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        textView = binding.textView

        setupCustomButton(binding.button2, R.drawable.button_idle2, R.drawable.button_pressed2)
        setupCustomButton(binding.button3, R.drawable.button_idle3, R.drawable.button_pressed3)
        setupCustomButton(binding.button5, R.drawable.button_idle5, R.drawable.button_pressed5)
        setupCustomButton(binding.button6, R.drawable.button_idle6, R.drawable.button_pressed6)
        setupCustomButton(binding.button9, R.drawable.button_idle9, R.drawable.button_pressed9)

        showIpAddressDialog()
        createCameraManager()
        checkForPermission()

        //binding.frameLayoutFinder.visibility = ImageView.GONE

        soundPool = SoundPool.Builder().build()
        soundIdMap[R.raw.left_eye_voice] = soundPool.load(this, R.raw.left_eye_voice, 1)
        soundIdMap[R.raw.right_eye_voice] = soundPool.load(this, R.raw.right_eye_voice, 1)
        soundIdMap[R.raw.tada] = soundPool.load(this, R.raw.tada, 1)
    }

    private fun checkForPermission() {
        if (allPermissionsGranted()) {
            cameraManager.startCamera(object : OnSuccessListener {
                override fun onSuccess(faces: List<Face>) {
                    onDetectionSuccess(faces)
                }
            })
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun displayDialog(isSuccessDialog: Boolean) {
        canClickButton = false
        val dialog = Dialog(this)
        if (isSuccessDialog) {
            dialog.setContentView(R.layout.success_dialog)
        } else {
            dialog.setContentView(R.layout.fail_dialog)
        }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent) // Removes default dialog background

        val layoutParams = WindowManager.LayoutParams()
        layoutParams.copyFrom(dialog.window?.attributes)
        val displayMetrics = resources.displayMetrics
        val dialogWidth = displayMetrics.widthPixels
        layoutParams.width = dialogWidth

        dialog.show()
        dialog.window?.attributes = layoutParams

        val scope = CoroutineScope(Dispatchers.Main)
        val delayMillis = 1000L // 1 second

        scope.launch {
            delay(delayMillis)
            dialog.dismiss()
            canClickButton = true
        }
    }

    private fun onDetectionSuccess(faces: List<Face>) {
        if (!isDetectingEye) {
            return
        }
        val timestamp = System.currentTimeMillis()

        if (timestamp - lastDetectionTimestamp < EYE_DETECTION_INTERVAL_MS) {
            return
        }

        faces.forEach {
            var isRightEyeOpen = false
            var isLeftEyeOpen = false
            if (it.rightEyeOpenProbability != null) {
                isLeftEyeOpen = it.rightEyeOpenProbability!! > EYE_DETECTION_THRESHOLD
            }

            if (it.leftEyeOpenProbability != null) {
                isRightEyeOpen = it.leftEyeOpenProbability!! > EYE_DETECTION_THRESHOLD
            }

            if (isTargetingLeftEye) {
                detectionResults.add(if (isLeftEyeOpen) 1 else -1)
            } else {
                detectionResults.add(if (isRightEyeOpen) 1 else -1)
            }

            if (detectionResults.count() >= EYE_NOTIFICATION_INTERVAL_MS / EYE_DETECTION_INTERVAL_MS) {
                val sum = detectionResults.sum()
                if (sum > 0) {
                    runOnUiThread {
                        if (isTargetingLeftEye) {
                            soundPool.play(soundIdMap[R.raw.left_eye_voice]!!, 1f, 1f, 1, 0, 1f)
                        } else {
                            soundPool.play(soundIdMap[R.raw.right_eye_voice]!!, 1f, 1f, 1, 0, 1f)
                        }
                    }
                }
                detectionResults.clear()
                //Make sure that the next detection will be at least 2 seconds later
                lastDetectionTimestamp = timestamp + 2000
            } else {
                lastDetectionTimestamp = timestamp
            }


//            runOnUiThread {
//                textView?.text = "Right Eye: ${isRightEyeOpen} Left Eye: ${isLeftEyeOpen}"
//            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                cameraManager.startCamera(object : OnSuccessListener {
                    override fun onSuccess(faces: List<Face>) {
                        onDetectionSuccess(faces)
                    }
                })
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT)
                    .show()
                finish()
            }
        }
    }

    private fun createCameraManager() {
        cameraManager = CameraManager(
            this,
            binding.previewViewFinder,
            this,
            binding.graphicOverlayFinder
        )
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun connectToServer(ipAddress: String) {
        thread {
            try {
                clientSocket = Socket(ipAddress, 9699)
                Log.e("EyesightTestActivity", "Connected to server")
                reader = BufferedReader(InputStreamReader(clientSocket?.getInputStream()))
                writer = OutputStreamWriter(clientSocket?.getOutputStream())
                canClickButton = true
                runOnUiThread {
                    Toast.makeText(this, "성공적으로 연결되었습니다.", Toast.LENGTH_SHORT).show()
                }
                listenForData()
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "연결에 실패했습니다. 두 기기가 같은 네트워크에 연결되었는지 확인하세요", Toast.LENGTH_SHORT).show()
                }
                Log.e("EyesightTestActivity", "Failed to connect to server")
                e.printStackTrace()
            }
        }
    }

    private fun listenForData() {
        thread {
            try {
                while (true) {
                    val receivedData = reader?.readLine()
                    runOnUiThread {
                        onDataReceived(receivedData)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun onDataReceived(data: String?) {
        if (data.isNullOrEmpty() || data.contains("from")) {
            return
        }

        if (data.lowercase().startsWith("end")) {
            val testResult = data.split(" ")[1]
            val intent = Intent(this, ResultActivity::class.java)
            intent.putExtra("result", testResult)

            isDetectingEye = false

            startActivity(intent)
            return
        }
        Log.d("EyesightTestActivity", "Received data: $data")
        val isCorrect = data.lowercase().toBoolean()
        if (isCorrect) {
            displayDialog(true)
            soundPool.play(soundIdMap[R.raw.tada]!!, 1f, 1f, 1, 0, 1f)
        } else {
            displayDialog(false)
        }
    }

    private fun sendData(data: String) {
        thread {
            try {
                canClickButton = false
                writer?.write(data, 0, data.length)
                writer?.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showIpAddressDialog() {
        val ipAddressEditText = EditText(this)
        ipAddressEditText.hint = "Enter IP Address"

        val dialog = AlertDialog.Builder(this)
            .setTitle("Enter IP Address")
            .setView(ipAddressEditText)
            .setPositiveButton("OK") { _, _ ->
                this.ipAddress = ipAddressEditText.text.toString()
                //TODO: validate the ip address
                connectToServer(this.ipAddress!!)
                showEyeSelectionDialog()
            }
            .setNegativeButton("Cancel") { _, _ ->
                // Handle cancel
                showEyeSelectionDialog()
            }
            .create()

        dialog.show()
    }

    private fun showEyeSelectionDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("어느 쪽 눈을 검사하시나요?")
            .setPositiveButton("오른눈") { _, _ ->
                // Handle left eye selection
                this.isTargetingLeftEye = false
                isDetectingEye = true
                binding.frameLayoutFinder.visibility = ImageView.GONE
            }
            .setNegativeButton("왼눈") { _, _ ->
                // Handle right eye selection
                this.isTargetingLeftEye = true
                isDetectingEye = true
                binding.frameLayoutFinder.visibility = ImageView.GONE
            }
            .create()

        dialog.show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCustomButton(button: ImageView, idleImage: Int, pressedImage: Int) {
        button.setImageResource(idleImage)

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    button.setImageResource(pressedImage)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    button.setImageResource(idleImage)
                }
            }

            return@setOnTouchListener false
        }

        button.setOnClickListener {
            if (!canClickButton) {
                return@setOnClickListener
            }

            canClickButton = false
            val buttonView = it as ImageView
            val buttonNumber = buttonView.tag
            Log.e("EyesightTestActivity", "Button $buttonNumber pressed")
            sendData("Answer $buttonNumber\n")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clientSocket?.close()
        reader?.close()
        writer?.close()
        soundPool.release()
    }

    companion object {
        private const val EYE_DETECTION_INTERVAL_MS = 100
        private const val EYE_NOTIFICATION_INTERVAL_MS = 2000
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val EYE_DETECTION_THRESHOLD = 0.96
        private val REQUIRED_PERMISSIONS = arrayOf(android.Manifest.permission.CAMERA)
    }
}