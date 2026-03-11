package com.galenzhao.scrollshot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.galenzhao.scrollshot.databinding.ActivityMainBinding
import com.galenzhao.scrollshot.service.ScreenCaptureService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager

    companion object {
        private const val TAG = "MainActivity"
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "screenCaptureLauncher resultCode=${result.resultCode} data=${result.data}")
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, getString(R.string.toast_screen_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "notificationPermLauncher granted=$granted")
        if (granted) requestScreenCapture()
        else Toast.makeText(this, getString(R.string.toast_notification_required), Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate()")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)

        binding.btnStartCapture.setOnClickListener { checkPermissionAndStart() }
        binding.btnStopCapture.setOnClickListener { stopCaptureService() }

        observeState()
    }

    override fun onResume() {
        super.onResume()
        // 如果已完成则导航到结果页
        val current = CaptureRepository.state.value
        if (current is CaptureRepository.State.Completed) navigateToResult()
    }

    private fun observeState() {
        lifecycleScope.launch {
            CaptureRepository.state.collect { state ->
                updateUI(state)
                if (state is CaptureRepository.State.Completed) navigateToResult()
            }
        }
    }

    private fun updateUI(state: CaptureRepository.State) {
        when (state) {
            is CaptureRepository.State.Idle -> {
                binding.btnStartCapture.isVisible = true
                binding.btnStopCapture.isVisible = false
                binding.progressBar.isVisible = false
                binding.tvStatus.text = getString(R.string.status_ready)
                binding.tvFrameCount.text = getString(R.string.status_hint_switch_and_scroll)
            }
            is CaptureRepository.State.Capturing -> {
                binding.btnStartCapture.isVisible = false
                binding.btnStopCapture.isVisible = true
                binding.progressBar.isVisible = false
                binding.tvStatus.text = getString(R.string.status_capturing)
                binding.tvFrameCount.text = getString(R.string.status_capturing_count, state.frameCount)
            }
            is CaptureRepository.State.Processing -> {
                binding.btnStartCapture.isVisible = false
                binding.btnStopCapture.isVisible = false
                binding.progressBar.isVisible = true
                binding.tvStatus.text = getString(R.string.status_processing)
                binding.tvFrameCount.text = getString(R.string.status_processing_hint)
            }
            is CaptureRepository.State.Completed -> {
                binding.progressBar.isVisible = false
            }
            is CaptureRepository.State.Error -> {
                binding.btnStartCapture.isVisible = true
                binding.btnStopCapture.isVisible = false
                binding.progressBar.isVisible = false
                binding.tvStatus.text = getString(R.string.status_error)
                binding.tvFrameCount.text = state.message
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkPermissionAndStart() {
        Log.d(TAG, "checkPermissionAndStart()")
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "POST_NOTIFICATIONS not granted, requesting")
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Log.d(TAG, "POST_NOTIFICATIONS already granted, requestScreenCapture()")
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        Log.d(TAG, "requestScreenCapture() createScreenCaptureIntent")
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        Log.d(TAG, "startCaptureService() resultCode=$resultCode data=$data")
        val topCropStr = binding.etTopCropHeight.text?.toString()?.trim()
        val topCropPx = topCropStr?.toIntOrNull()

        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_TOP_CROP_HEIGHT_PX, topCropPx ?: -1)
        }
        Log.d(TAG, "Calling startForegroundService() with intent=$intent topCropPx=$topCropPx")
        startForegroundService(intent)
    }

    private fun stopCaptureService() {
        startService(Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        })
    }

    private fun navigateToResult() {
        startActivity(Intent(this, ResultActivity::class.java))
    }
}
