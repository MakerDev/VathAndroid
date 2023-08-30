package com.eis.vathandroid

import android.Manifest
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.opencv.android.BaseLoaderCallback
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.LoaderCallbackInterface
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfRect
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.CascadeClassifier
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class FaceDetectionActivity : AppCompatActivity(), CameraBridgeViewBase.CvCameraViewListener2 {
    private var mRgba: Mat? = null
    private var mGray: Mat? = null
    private var mOpenCvCameraView: CameraBridgeViewBase? = null
    private var cascadeClassifier: CascadeClassifier? = null
    private var cascadeClassifier_eye: CascadeClassifier? = null

    private val mLoaderCallback: BaseLoaderCallback = object : BaseLoaderCallback(this) {
        override fun onManagerConnected(status: Int) {
            when (status) {
                SUCCESS -> {
                    run {
                        Log.i(TAG, "OpenCv Is loaded")
                        mOpenCvCameraView!!.enableView()
                    }
                    run { super.onManagerConnected(status) }
                }

                else -> {
                    super.onManagerConnected(status)
                }
            }
        }
    }

    init {
        Log.i(TAG, "Instantiated new " + this.javaClass)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenCVLoader.initDebug()
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val MY_PERMISSIONS_REQUEST_CAMERA = 0
        // if camera permission is not given it will ask for it on device
        if (ContextCompat.checkSelfPermission(this@FaceDetectionActivity, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_DENIED
        ) {
            ActivityCompat.requestPermissions(
                this@FaceDetectionActivity,
                arrayOf(Manifest.permission.CAMERA),
                MY_PERMISSIONS_REQUEST_CAMERA
            )
        }
        setContentView(R.layout.activity_face_detection)
        mOpenCvCameraView = findViewById<View>(R.id.frame_Surface) as CameraBridgeViewBase
        mOpenCvCameraView!!.setCameraPermissionGranted()
        mOpenCvCameraView!!.visibility = SurfaceView.VISIBLE
        mOpenCvCameraView!!.setCameraIndex(CameraBridgeViewBase.CAMERA_ID_FRONT)
        mOpenCvCameraView!!.setCvCameraViewListener(this)
        mOpenCvCameraView!!.enableView()

        // load the model
        try {
            val `is` = resources.openRawResource(R.raw.haarcascade_frontalface_alt)
            val cascadeDir = getDir("cascade", MODE_PRIVATE) // creating a folder
            val mCascadeFile =
                File(cascadeDir, "haarcascade_frontalface_alt.xml") // creating file on that folder
            val os = FileOutputStream(mCascadeFile)
            val buffer = ByteArray(4096)
            var byteRead: Int
            // writing that file from raw folder
            while (`is`.read(buffer).also { byteRead = it } != -1) {
                os.write(buffer, 0, byteRead)
            }
            `is`.close()
            os.close()

            // loading file from  cascade folder created above
            cascadeClassifier = CascadeClassifier(mCascadeFile.absolutePath)
            // model is loaded

            // load eye haarcascade classifier
            val is2 = resources.openRawResource(R.raw.haarcascade_eye)
            // created before
            val mCascadeFile_eye =
                File(cascadeDir, "haarcascade_eye.xml") // creating file on that folder
            val os2 = FileOutputStream(mCascadeFile_eye)
            val buffer1 = ByteArray(4096)
            var byteRead1: Int
            // writing that file from raw folder
            while (is2.read(buffer1).also { byteRead1 = it } != -1) {
                os2.write(buffer1, 0, byteRead1)
            }
            is2.close()
            os2.close()

            // loading file from  cascade folder created above
            cascadeClassifier_eye = CascadeClassifier(mCascadeFile_eye.absolutePath)
        } catch (e: IOException) {
            Log.i(TAG, "Cascade file not found")
        }
    }

    override fun onResume() {
        super.onResume()
        if (OpenCVLoader.initDebug()) {
            //if load success
            Log.d(TAG, "Opencv initialization is done")
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS)
        } else {
            //if not loaded
            Log.d(TAG, "Opencv is not loaded. try again")
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, this, mLoaderCallback)
        }
    }

    override fun onPause() {
        super.onPause()
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView!!.disableView()
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView!!.disableView()
        }
    }

    override fun onCameraViewStarted(width: Int, height: Int) {
        mRgba = Mat(height, width, CvType.CV_8UC4)
        mGray = Mat(height, width, CvType.CV_8UC1)
    }

    override fun onCameraViewStopped() {
        mRgba!!.release()
    }

    override fun onCameraFrame(inputFrame: CameraBridgeViewBase.CvCameraViewFrame): Mat {
        mRgba = inputFrame.rgba()
        mGray = inputFrame.gray()
        // in processing pass mRgba to cascaderec class
        mRgba = CascadeRec(mRgba)
        return mRgba!!
    }

    private fun CascadeRec(mRgba: Mat?): Mat? {
        // original frame is -90 degree so we have to rotate is to 90 to get proper face for detection
        Core.flip(mRgba!!.t(), mRgba, -1)
        // convert it into RGB
        val mRbg = Mat()
        Imgproc.cvtColor(mRgba, mRbg, Imgproc.COLOR_RGBA2RGB)
        val height = mRbg.height()
        // minimum size of face in frame
        val absoluteFaceSize = (height * 0.1).toInt()
        val faces = MatOfRect()
        if (cascadeClassifier != null) {
            cascadeClassifier!!.detectMultiScale(
                mRbg,
                faces,
                1.1,
                2,
                2,
                Size(absoluteFaceSize.toDouble(), absoluteFaceSize.toDouble()),
                Size()
            )
        }

        // loop through all faces
        val facesArray = faces.toArray()
        for (i in facesArray.indices) {
            // draw face on original frame mRgba
            Imgproc.rectangle(
                mRgba,
                facesArray[i].tl(),
                facesArray[i].br(),
                Scalar(0.0, 255.0, 0.0, 255.0),
                2
            )
            // crop face image and then pass it through eye classifier
            // starting point
            val roi = Rect(
                facesArray[i].tl().x.toInt(),
                facesArray[i].tl().y.toInt(),
                facesArray[i].br().x.toInt() - facesArray[i].tl().x.toInt(),
                facesArray[i].br().y.toInt() - facesArray[i].tl().y.toInt()
            )

            // cropped mat image
            val cropped = Mat(mRgba, roi)
            // create a array to store eyes coordinate but we have to pass MatOfRect to classifier
            val eyes = MatOfRect()
            if (cascadeClassifier_eye != null) {                                                      // find biggest size object
                cascadeClassifier_eye!!.detectMultiScale(
                    cropped,
                    eyes,
                    1.15,
                    2,
                    2,
                    Size(35.0, 35.0),
                    Size()
                )

                // now create an array
                val eyesarray = eyes.toArray()
                // loop through each eye
                for (j in eyesarray.indices) {
                    // find coordinate on original frame mRgba
                    // starting point
                    val x1 = (eyesarray[j].tl().x + facesArray[i].tl().x).toInt()
                    val y1 = (eyesarray[j].tl().y + facesArray[i].tl().y).toInt()
                    // width and height
                    val w1 = (eyesarray[j].br().x - eyesarray[j].tl().x).toInt()
                    val h1 = (eyesarray[j].br().y - eyesarray[j].tl().y).toInt()
                    // end point
                    // draw eye on original frame mRgba
                    //input    starting point   ending point   color                 thickness
                    Imgproc.rectangle(
                        mRgba, Point(x1.toDouble(), y1.toDouble()), Point(
                            (w1 + x1).toDouble(), (h1 + y1).toDouble()
                        ), Scalar(0.0, 255.0, 0.0, 255.0), 2
                    )
                }
            }
        }
        // rotate back original frame to -90 degree
        Core.flip(mRgba.t(), mRgba, 1)
        return mRgba
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}