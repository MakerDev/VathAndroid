package com.eis.vathandroid

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.eis.vathandroid.databinding.ActivityResultBinding

class ResultActivity : AppCompatActivity() {
    private lateinit var binding: ActivityResultBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if(intent.extras != null) {
            val result = intent.extras!!.getString("result")
            binding.resultTextView.text = "시력은 ${result}입니다"
        }
    }
}