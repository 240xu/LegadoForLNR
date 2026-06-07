package io.legado.plugin

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import io.nightfish.lightnovelreader.api.plugin.LightNovelReaderPlugin
import io.nightfish.lightnovelreader.api.plugin.Plugin
import io.nightfish.lightnovelreader.api.web.WebBookDataSourceManagerApi

@Plugin(
    name = "Legado JSON 书源",
    version = 13,
    versionName = "1.3.7",
    author = "LNR Legado Plugin",
    description = "支持导入 lyc486 版 Legado JSON 书源，增强 loginUi、发现页、旧字段迁移和宿主 PageContent 管理。",
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