package io.legado.plugin

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import io.nightfish.lightnovelreader.api.content.ContentComponentRepositoryApi
import io.nightfish.lightnovelreader.api.plugin.LightNovelReaderPlugin
import io.nightfish.lightnovelreader.api.plugin.Plugin
import io.nightfish.lightnovelreader.api.web.WebBookDataSourceManagerApi

@Plugin(
    name = "Legado JSON 书源",
    version = 16,
    versionName = "1.4.0",
    author = "LNR Legado Plugin",
    description = "支持导入 lyc486 版 Legado JSON 书源，增强 loginUi、发现页、旧字段迁移和宿主 PageContent 管理。",
    updateUrl = "",
    apiVersion = 2
)
class LegadoJsonPlugin(
    private val context: Context,
    private val webBookDataSourceManagerApi: WebBookDataSourceManagerApi,
    private val contentComponentRepositoryApi: ContentComponentRepositoryApi
) : LightNovelReaderPlugin {

    override fun onLoad() {
        runCatching {
            contentComponentRepositoryApi.registrar
                .id(LegadoHtmlComponentData.ID)
                .component(LegadoHtmlComponent::class)
                .data(LegadoHtmlComponentData::class)
                .serializer(LegadoHtmlComponentData.jsonSerializer)
                .register()
        }
        android.util.Log.i("LegadoJsonPlugin", "Legado JSON 书源插件已加载")
    }

    override fun onUnload() {
        android.util.Log.i("LegadoJsonPlugin", "Legado JSON 书源插件已卸载")
    }

    @Composable
    override fun PageContent(paddingValues: PaddingValues) {
        val activeDataSource = webBookDataSourceManagerApi.getWebDataSource()
        LegadoSourceManagerContent(
            hostContext = context,
            paddingValues = paddingValues,
            activeDataSourceId = activeDataSource.id,
            providedDataSource = activeDataSource as? LegadoJsonWebDataSource
        )
    }
}
