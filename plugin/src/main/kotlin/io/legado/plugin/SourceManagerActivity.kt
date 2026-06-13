package io.legado.plugin

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.legado.engine.data.BookSource
import io.legado.engine.shim.AndroidContext

class SourceManagerActivity : Activity() {

    companion object {
        private const val REQUEST_IMPORT_JSON = 1001

        fun newIntent(context: Context): Intent {
            return Intent().apply {
                setClassName("io.legado.plugin", SourceManagerActivity::class.java.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        fun start(context: Context) {
            context.startActivity(newIntent(context))
        }
    }

    private lateinit var dataSource: LegadoJsonWebDataSource
    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AndroidContext.isInitialized()) {
            AndroidContext.init(this)
        }
        dataSource = LegadoJsonWebDataSource(applicationContext)
        setContentView(buildContentView())
        refreshSources()
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(0xFFFFFFFF.toInt())
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(0xFF6200EE.toInt())
        }
        toolbar.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(0x00000000)
            contentDescription = "返回"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        toolbar.addView(TextView(this).apply {
            text = "Legado 书源"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        actions.addView(Button(this).apply {
            text = "导入 JSON 文件"
            setOnClickListener { openJsonPicker() }
        })
        actions.addView(Button(this).apply {
            text = "从剪贴板导入"
            setOnClickListener { importFromClipboard() }
        })
        actions.addView(Button(this).apply {
            text = "粘贴 JSON 导入"
            setOnClickListener { showPasteDialog() }
        })
        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(statusText)
        root.addView(actions)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(listContainer) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun refreshSources() {
        dataSource.reloadSources()
        val sources = dataSource.getAllSources()
        statusText.text = "已导入 ${sources.size} 个书源，启用 ${sources.count { it.enabled }} 个"
        listContainer.removeAllViews()
        if (sources.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "还没有书源。请导入 lyc486 版 Legado JSON 书源文件。"
                textSize = 15f
                setTextColor(0xFF555555.toInt())
                setPadding(0, dp(32), 0, 0)
            })
            return
        }
        sources.sortedWith(compareBy<BookSource> { !it.enabled }.thenBy { it.bookSourceName })
            .forEach { source -> listContainer.addView(sourceRow(source)) }
    }

    private fun sourceRow(source: BookSource): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(TextView(this).apply {
            text = source.bookSourceName.ifBlank { source.bookSourceUrl }
            textSize = 16f
            setTextColor(0xFF222222.toInt())
        })
        row.addView(TextView(this).apply {
            text = source.bookSourceUrl
            textSize = 12f
            setTextColor(0xFF777777.toInt())
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        actions.addView(CheckBox(this).apply {
            text = "启用"
            isChecked = source.enabled
            setOnCheckedChangeListener { _, checked ->
                dataSource.setSourceEnabled(source.bookSourceUrl, checked)
                refreshSources()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(Button(this).apply {
            text = "登录"
            isEnabled = !source.loginUrl.isNullOrBlank() || !source.loginUi.isNullOrBlank()
            setOnClickListener { dataSource.startLogin(source) }
        })
        actions.addView(Button(this).apply {
            text = "删除"
            setOnClickListener {
                dataSource.deleteSource(source.bookSourceUrl)
                refreshSources()
            }
        })
        row.addView(actions)
        row.addView(View(this).apply { setBackgroundColor(0xFFE0E0E0.toInt()) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        return row
    }

    private fun openJsonPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_IMPORT_JSON)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT_JSON && resultCode == RESULT_OK) {
            data?.data?.let(::importFromUri)
        }
    }

    private fun importFromUri(uri: Uri) {
        val json = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull()
        if (json.isNullOrBlank()) {
            toast("读取文件失败")
            return
        }
        importJson(json)
    }

    private fun importFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            toast("剪贴板为空")
            return
        }
        importJson(text)
    }

    private fun showPasteDialog() {
        val editText = EditText(this).apply {
            minLines = 8
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            hint = "粘贴 Legado JSON 书源"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("导入书源")
            .setView(editText)
            .setPositiveButton("导入") { _, _ -> importJson(editText.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importJson(json: String) {
        val count = dataSource.importSources(json)
        toast(if (count > 0) "成功导入 $count 个书源" else "未识别到有效书源")
        refreshSources()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
