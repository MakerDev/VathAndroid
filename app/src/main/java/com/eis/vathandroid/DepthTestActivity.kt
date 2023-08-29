package com.eis.vathandroid

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.eis.vathandroid.databinding.ActivityDepthTestBinding
import com.eis.vathandroid.helpers.CameraPermissionHelper.hasCameraPermission
import com.eis.vathandroid.helpers.CameraPermissionHelper.launchPermissionSettings
import com.eis.vathandroid.helpers.CameraPermissionHelper.requestCameraPermission
import com.eis.vathandroid.helpers.CameraPermissionHelper.shouldShowRequestPermissionRationale
import com.eis.vathandroid.helpers.DisplayRotationHelper
import com.eis.vathandroid.helpers.FullScreenHelper.setFullScreenOnWindowFocusChanged
import com.eis.vathandroid.helpers.SnackbarHelper
import com.eis.vathandroid.helpers.TrackingStateHelper
import com.eis.vathandroid.rawdepth.DepthData.create
import com.eis.vathandroid.rendering.BackgroundRenderer
import com.eis.vathandroid.rendering.DepthRenderer
import com.google.ar.core.ArCoreApk
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class CustomView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val paint = Paint()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        val rectWidth = 0.2f * height

        // Set paint properties for the red border lines
        paint.color = Color.RED
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f

        // Calculate the rectangle coordinates for center-middle position
        val left = (width - rectWidth) / 2
        val top = (height - rectWidth) / 2
        val right = (width + rectWidth) / 2
        val bottom = (height + rectWidth) / 2

        // Draw the rectangle on the canvas
        canvas.drawRect(left, top, right, bottom, paint)
    }
}

class DepthTestActivity : AppCompatActivity(), GLSurfaceView.Renderer {
    private val messageSnackbarHelper = SnackbarHelper()
    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private lateinit var surfaceView: GLSurfaceView
    private var installRequested = false
    private var session: Session? = null
    private var displayRotationHelper: DisplayRotationHelper? = null
    private val backgroundRenderer = BackgroundRenderer()
    private lateinit var binding: ActivityDepthTestBinding
    private val depthRenderer = DepthRenderer()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDepthTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        surfaceView = binding.surfaceview
        displayRotationHelper = DisplayRotationHelper( /*context=*/this)
        val rectView = binding.rectView
        val displayMetrics = resources.displayMetrics
        val screenHeightPixels = displayMetrics.heightPixels

        // Convert pixels to dp using display density
        val screenHeightDp = screenHeightPixels / displayMetrics.density
        val layoutParams = rectView.layoutParams
        val length = (screenHeightDp * AREA_HEIGHT_RATIO).toInt()
        layoutParams.width = length
        layoutParams.height = length
        rectView.layoutParams = layoutParams

        // Set up renderer.
        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView.setWillNotDraw(false)
        installRequested = false
    }

    override fun onResume() {
        super.onResume()
        if (session == null) {
            var exception: Exception? = null
            var message: String? = null
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }

                    InstallStatus.INSTALLED -> {}
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!hasCameraPermission(this)) {
                    requestCameraPermission(this)
                    return
                }

                // Creates the ARCore session.
                session = Session(this)
                if (!session!!.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) {
                    message = "This device does not support the ARCore Raw Depth API. See" +
                                "https://developers.google.com/ar/devices for a list of devices that do."
                }
            } catch (e: UnavailableArcoreNotInstalledException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableUserDeclinedInstallationException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                message = "Please update ARCore"
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                message = "Please update this app"
                exception = e
            } catch (e: UnavailableDeviceNotCompatibleException) {
                message = "This device does not support AR"
                exception = e
            } catch (e: Exception) {
                message = "Failed to create AR session"
                exception = e
            }
            if (message != null) {
                messageSnackbarHelper.showError(this, message)
                Log.e(TAG, "Exception creating session", exception)
                return
            }
        }

        try {
            // Enable raw depth estimation and auto focus mode while ARCore is running.
            val config = session!!.config
            config.depthMode = Config.DepthMode.AUTOMATIC
            config.focusMode = Config.FocusMode.AUTO
            session!!.configure(config)
            session!!.resume()
        } catch (e: CameraNotAvailableException) {
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.")
            session = null
            return
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        surfaceView.onResume()
        displayRotationHelper!!.onResume()
        messageSnackbarHelper.showMessage(this, "Waiting for depth data...")
    }

    public override fun onPause() {
        super.onPause()
        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper!!.onPause()
            surfaceView.onPause()
            session!!.pause()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        results: IntArray
    ) {
        if (!hasCameraPermission(this)) {
            Toast.makeText(
                this, "Camera permission is needed to run this application",
                Toast.LENGTH_LONG
            ).show()
            if (!shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                launchPermissionSettings(this)
            }
            finish()
        }

        super.onRequestPermissionsResult(requestCode, permissions, results)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the texture and pass it to ARCore session to be filled during update().
            backgroundRenderer.createOnGlThread( /*context=*/this)
            depthRenderer.createOnGlThread(/*context=*/ this);
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read an asset file", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        displayRotationHelper!!.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    private fun calculateAverageDistanceOf(frame: Frame): Double {
        val depthImage = frame.acquireDepthImage16Bits() // Smoothed 16-bit depth image
        val width = depthImage.width
        val height = depthImage.height
        val areaLength = height * 0.15

        val depthImagePlane = depthImage.planes[0]
        val depthByteBufferOriginal = depthImagePlane.buffer
        val depthByteBuffer = ByteBuffer.allocate(depthByteBufferOriginal.capacity())
        depthByteBuffer.order(ByteOrder.nativeOrder())
        while (depthByteBufferOriginal.hasRemaining()) {
            depthByteBuffer.put(depthByteBufferOriginal.get())
        }
        depthByteBuffer.rewind()

        // Calculate the average depth value of the area in the middle of the depth buffer
        val middleStartX = (width - areaLength) / 2
        val middleEndX = middleStartX + areaLength
        val middleStartY = (height - areaLength) / 2
        val middleEndY = middleStartY + areaLength
        var totalDepth = 0
        var totalPixels = 0

        var y = middleStartY
        while (y < middleEndY) {
            var x = middleStartX
            while (x < middleEndX) {
                val byteIndex = x * depthImagePlane.pixelStride + y * depthImagePlane.rowStride
                val depthValue = depthByteBuffer.getShort(byteIndex.toInt()).toUShort().toInt()
                // Check for invalid depth values (usually represented as 0 or a very high value)
                if (depthValue in 100..65534) {
                    totalDepth += depthValue
                    totalPixels++
                }
                x++
            }
            y++
        }

        depthImage.close()

        return if (totalPixels > 0) {
            // return the average depth value in meters
            totalDepth / totalPixels / 1000.0
        } else {
            0.0 // Return 0.0 if there are no valid pixels in the specified area
        }
    }

    override fun onDrawFrame(gl: GL10) {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (session == null) {
            return
        }
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper!!.updateSessionIfNeeded(session!!)
        try {
            session!!.setCameraTextureName(backgroundRenderer.textureId)

            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            val frame = session!!.update()
            val camera = frame.camera

            // If frame is ready, render camera preview image to the GL surface.
            backgroundRenderer.draw(frame)
            val points = create(frame, session!!.createAnchor(camera.pose)) ?: return
            val avearageDepth = calculateAverageDistanceOf(frame)
            Log.d(TAG, "Average depth: $avearageDepth")
            binding.depthTextView.text = String.format("%.2f", avearageDepth)
            if (messageSnackbarHelper.isShowing) {
                messageSnackbarHelper.hide(this)
            }

            // If not tracking, show tracking failure reason instead.
            if (camera.trackingState == TrackingState.PAUSED) {
                messageSnackbarHelper.showMessage(
                    this, TrackingStateHelper.getTrackingFailureReasonString(camera)
                )
                return
            }

            // Visualize depth points.
            depthRenderer.update(points);
            depthRenderer.draw(camera);
        } catch (t: Throwable) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }
    }

    companion object {
        private val TAG = DepthTestActivity::class.java.simpleName
        private val AREA_HEIGHT_RATIO = 0.1f
    }
}