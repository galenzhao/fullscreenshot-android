package com.example.scrollshot

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.scrollshot.databinding.ActivitySliceViewerBinding
import com.example.scrollshot.databinding.ItemSliceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 在应用内查看本次长截图的各张切片和合成结果。
 * 由 ResultActivity 传入调试目录路径（EXTRA_DEBUG_DIR）。
 */
class SliceViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEBUG_DIR = "extra_debug_dir"
    }

    private lateinit var binding: ActivitySliceViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySliceViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val dirPath = intent.getStringExtra(EXTRA_DEBUG_DIR) ?: run {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = "未传入切片目录"
            return
        }
        val dir = File(dirPath)
        if (!dir.isDirectory) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = "目录不存在"
            return
        }

        val files = dir.listFiles { _, name ->
            name.endsWith(".png") && (name.startsWith("slice_") || name == "result.png")
        }?.sortedBy { it.name.let { n -> if (n == "result.png") "zzz" else n } } ?: emptyList()

        if (files.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = "暂无切片数据"
            return
        }

        binding.tvEmpty.visibility = View.GONE
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = SliceAdapter(files)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

private class SliceAdapter(private val files: List<File>) : RecyclerView.Adapter<SliceAdapter.Holder>() {

    override fun getItemCount(): Int = files.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemSliceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(files[position])
    }

    class Holder(private val binding: ItemSliceBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(file: File) {
            binding.tvLabel.text = file.name
            binding.ivSlice.setImageDrawable(null)
            loadBitmap(file, binding.ivSlice)
        }

        private fun loadBitmap(file: File, imageView: ImageView) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                val bmp = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val opts = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                    val w = opts.outWidth
                    val h = opts.outHeight
                    if (w <= 0 || h <= 0) return@withContext null
                    val maxW = imageView.context.resources.displayMetrics.widthPixels
                    var sample = 1
                    while (w / sample > maxW) sample *= 2
                    BitmapFactory.Options().apply {
                        inSampleSize = sample.coerceAtLeast(1)
                        inJustDecodeBounds = false
                    }.let { BitmapFactory.decodeFile(file.absolutePath, it) }
                }
                if (imageView.tag == file.absolutePath) {
                    bmp?.let { imageView.setImageBitmap(it) }
                }
            }
            imageView.tag = file.absolutePath
        }
    }
}
