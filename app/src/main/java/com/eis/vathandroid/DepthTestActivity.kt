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
import com.eis.vathandroid.rendering.BackgroundRenderer
import com.google.ar.core.ArCoreApk
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.io.IOException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10


class DepthTestActivity : AppCompatActivity(), GLSurfaceView.Renderer {
    private val messageSnackbarHelper = SnackbarHelper()
    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private lateinit var surfaceView: GLSurfaceView
    private var installRequested = false
    private var session: Session? = null
    private var displayRotationHelper: DisplayRotationHelper? = null
    private val backgroundRenderer = BackgroundRenderer()
    private lateinit var binding: ActivityDepthTestBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDepthTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        surfaceView = binding.surfaceview
        displayRotationHelper = DisplayRotationHelper( /*context=*/this)

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
            session!!.resume()
        } catch (e: CameraNotAvailableException) {
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.")
            session = null
            return
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        surfaceView!!.onResume()
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
            surfaceView!!.onPause()
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
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read an asset file", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        displayRotationHelper!!.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
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

            // If not tracking, show tracking failure reason instead.
            if (camera.trackingState == TrackingState.PAUSED) {
                messageSnackbarHelper.showMessage(
                    this, TrackingStateHelper.getTrackingFailureReasonString(camera)
                )
                return
            }
        } catch (t: Throwable) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }
    }

    companion object {
        private val TAG = DepthTestActivity::class.java.simpleName
    }
}