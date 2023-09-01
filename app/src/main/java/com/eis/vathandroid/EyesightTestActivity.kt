package com.eis.vathandroid

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
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

    private fun onDetectionSuccess(faces: List<Face>) {
        faces.forEach {
            var isRightEyeOpen = false
            var isLeftEyeOpen = false
            if (it.rightEyeOpenProbability != null) {
                isLeftEyeOpen = it.rightEyeOpenProbability!! > EYE_DETECTION_THRESHOLD
            }

            if (it.leftEyeOpenProbability != null) {
                isRightEyeOpen = it.leftEyeOpenProbability!! > EYE_DETECTION_THRESHOLD
            }

            runOnUiThread {
                textView?.text = "Right Eye: ${isRightEyeOpen} Left Eye: ${isLeftEyeOpen}"
            }
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
                clientSocket = Socket(ipAddress, 9099)
                Log.e("EyesightTestActivity", "Connected to server")
                reader = BufferedReader(InputStreamReader(clientSocket?.getInputStream()))
                writer = OutputStreamWriter(clientSocket?.getOutputStream())

                listenForData()
            } catch (e: Exception) {
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
        // Handle received data
        Log.e("EyesightTestActivity", "Received data: $data")
    }

    private fun sendData(data: String) {
        thread {
            try {
                writer?.write(data, 0, data.length)
                writer?.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clientSocket?.close()
        reader?.close()
        writer?.close()
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
            .setPositiveButton("왼눈") { _, _ ->
                // Handle left eye selection
                this.isTargetingLeftEye = true
                isDetectingEye = true
            }
            .setNegativeButton("오른눈") { _, _ ->
                // Handle right eye selection
                this.isTargetingLeftEye = false
                isDetectingEye = true
            }
            .create()


        dialog.show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCustomButton(button: ImageView, idleImage: Int, pressedImage: Int) {
        button.setImageResource(idleImage)

        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // User presses the button, change the image to the pressed state
                    button.setImageResource(pressedImage)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // User releases the button, change the image back to the idle state
                    button.setImageResource(idleImage)
                }
            }

            // Return false to ensure the event continues to propagate, allowing clicks to work
            return@setOnTouchListener false
        }

        button.setOnClickListener {
            val buttonView = it as ImageView
            val buttonNumber = buttonView.tag
            Log.e("EyesightTestActivity", "Button $buttonNumber pressed")
            sendData("Answer $buttonNumber\n")
        }
    }


    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val EYE_DETECTION_THRESHOLD = 0.96
        private val REQUIRED_PERMISSIONS = arrayOf(android.Manifest.permission.CAMERA)
    }
}