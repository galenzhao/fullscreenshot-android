package com.example.scrollshot

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.scrollshot.databinding.ActivityResultBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding
    private var resultBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        resultBitmap = CaptureRepository.resultBitmap
        if (resultBitmap == null) {
            Toast.makeText(this, "没有可用的截图结果", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.ivResult.setImageBitmap(resultBitmap)
        binding.tvImageInfo.text = "尺寸：${resultBitmap!!.width} × ${resultBitmap!!.height} px"

        binding.btnSave.setOnClickListener { saveBitmap() }
        binding.btnShare.setOnClickListener { shareBitmap() }
        binding.btnDiscard.setOnClickListener {
            CaptureRepository.clearResult()
            finish()
        }

        val debugDir = CaptureRepository.lastDebugDir
        binding.btnViewSlices.visibility = if (debugDir != null) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnViewSlices.setOnClickListener {
            startActivity(Intent(this, SliceViewerActivity::class.java)
                .putExtra(SliceViewerActivity.EXTRA_DEBUG_DIR, CaptureRepository.lastDebugDir))
        }
    }

    private fun saveBitmap() {
        val bmp = resultBitmap ?: return
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { saveToGallery(bmp) }
            if (uri != null) {
                Toast.makeText(this@ResultActivity, "已保存到相册 ✓", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ResultActivity, "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveToGallery(bitmap: Bitmap): Uri? {
        return try {
            val fileName = "ScrollShot_${System.currentTimeMillis()}.png"
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScrollShot")
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                val out: OutputStream = resolver.openOutputStream(it) ?: return null
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
                out.close()
            }
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun shareBitmap() {
        val bmp = resultBitmap ?: return
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { saveToGallery(bmp) }
            if (uri != null) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "分享长截图"))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 不 recycle，因为 CaptureRepository 仍持有引用
    }
}
