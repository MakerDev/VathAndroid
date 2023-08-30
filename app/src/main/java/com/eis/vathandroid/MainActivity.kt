package com.eis.vathandroid

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    fun openDepthTest(view: View) {
        val intent = Intent(this, DepthTestActivity::class.java)
        startActivity(intent)
    }

    fun openEyesightTest(view: View) {
        val intent = Intent(this, FaceDetectionActivity::class.java)
        startActivity(intent)
    }
}