package io.legado.plugin

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import io.nightfish.lightnovelreader.api.plugin.LightNovelReaderPlugin
import io.nightfish.lightnovelreader.api.plugin.Plugin
import io.nightfish.lightnovelreader.api.web.WebBookDataSourceManagerApi

@Plugin(
    name = "Legado JSON 书源",
    version = 20,
    versionName = "1.5.1",
    author = "LNR Legado Plugin",
    description = "支持导入 lyc486 版 Legado JSON 书源。完整实现6种规则解析、loginUi登录、发现页useweb、并发限速、Cookie管理、字体反爬、图片解密。",
    updateUrl = "",
    apiVersion = 2
)
class LegadoJsonPlugin(
    private val context: Context,
    private val webBookDataSourceManagerApi: WebBookDataSourceManagerApi
) : LightNovelReaderPlugin {

    override fun onLoad() {
        android.util.Log.i("LegadoJsonPlugin", "Legado JSON 书源插件已加载")
    }

    override fun onUnload() {
        android.util.Log.i("LegadoJsonPlugin", "Legado JSON 书源插件已卸载")
    }

    @Composable
    override fun PageContent(paddingValues: PaddingValues) {
        LegadoSourceManagerContent(
            hostContext = context,
            paddingValues = paddingValues,
            activeDataSourceId = webBookDataSourceManagerApi.getWebDataSource().id
        )
    }
}