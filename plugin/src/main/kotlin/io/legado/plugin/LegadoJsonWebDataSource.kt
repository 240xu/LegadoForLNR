package io.legado.plugin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.engine.webBook.WebBook
import io.legado.engine.js.SourceOpenCallback
import io.legado.engine.js.SourceLoginCallback
import io.legado.engine.js.SourceBrowserCallback
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject as GsonJsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import io.legado.engine.data.BookSource
import io.legado.engine.data.Book
import io.legado.engine.data.SearchBook
import io.legado.engine.data.BookChapter
import io.legado.engine.data.ExploreKind
import io.legado.engine.http.CookieStore
import io.legado.engine.rule.AnalyzeUrl
import io.legado.engine.rule.UrlOptionParser
import io.legado.engine.shim.AndroidContext
import io.legado.engine.shim.CacheManager
import io.legado.engine.shim.SourceConfig
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.explore.ExploreBooksRow
import io.nightfish.lightnovelreader.api.explore.ExploreDisplayBook
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import io.nightfish.lightnovelreader.api.util.LocalString
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.explore.ExploreTapPageDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExploreExpandedPageDataSource
import io.nightfish.lightnovelreader.api.web.explore.filter.Filter
import io.nightfish.lightnovelreader.api.web.explore.filter.SingleChoiceFilter
import io.nightfish.lightnovelreader.api.web.explore.filter.SwitchFilter
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Undefined
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.File
import java.io.StringReader
import java.net.URL
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentLinkedQueue

@WebDataSource(name = "Legado JSON", provider = "Legado")
class LegadoJsonWebDataSource(
    private val context: Context
) : WebBookDataSource {

    companion object {
        private val gson: Gson = GsonBuilder().create()
        private const val SEPARATOR = "::"
        private const val EXPLORE_PREVIEW_ROW_LIMIT = 12
        private const val EXPLORE_PREVIEW_BOOK_LIMIT = 12
    }

    private class LegadoExploreUiState {
        var selectedSourceUrl by mutableStateOf("")
        var selectedKindKey by mutableStateOf("")
        var reloadToken by mutableIntStateOf(0)
        var page by mutableIntStateOf(1)
        var loading by mutableStateOf(false)
        var ended by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        val books = mutableStateListOf<BookInformation>()
        val textInputs = mutableStateMapOf<String, String>()
    }

    private val bookSources = mutableListOf<BookSource>()
    private val exploreInfoMaps = java.util.concurrent.ConcurrentHashMap<String, ExploreInfoMap>()
    private val loginPromptTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val openEvents = ConcurrentLinkedQueue<OpenEvent>()

    private data class OpenEvent(val type: String, val url: String, val title: String)

    private data class AssignedArray(val text: String, val endIndex: Int)

    private class ExploreInfoMap(private val sourceUrl: String) : MutableMap<String, String> {
        private var actualMap: MutableMap<String, String> = load()
        var needSave: Boolean = false
            private set
        private var saveTime: Int = 0

        private fun load(): MutableMap<String, String> {
            val type = object : TypeToken<MutableMap<String, String>>() {}.type
            return runCatching {
                gson.fromJson<MutableMap<String, String>>(CacheManager.get(cacheKey), type)
            }.getOrNull() ?: linkedMapOf()
        }

        private val cacheKey: String get() = "infoMap_$sourceUrl"

        @JvmOverloads
        fun save(time: Int = 0, need: Boolean = true) {
            saveTime = time
            needSave = need
            if (need) saveNow()
        }

        fun saveNow() {
            CacheManager.put(cacheKey, gson.toJson(actualMap), saveTime)
            needSave = false
        }

        fun get(): MutableMap<String, String> = actualMap

        fun set(value: Any?) {
            actualMap = value.toInfoStringMap().toMutableMap()
            saveNow()
        }

        override val entries: MutableSet<MutableMap.MutableEntry<String, String>> get() = actualMap.entries
        override val keys: MutableSet<String> get() = actualMap.keys
        override val size: Int get() = actualMap.size
        override val values: MutableCollection<String> get() = actualMap.values
        override fun clear() { actualMap.clear(); saveNow() }
        override fun isEmpty(): Boolean = actualMap.isEmpty()
        override fun remove(key: String): String? = actualMap.remove(key).also { saveNow() }
        override fun putAll(from: Map<out String, String>) { actualMap.putAll(from); saveNow() }
        override fun put(key: String, value: String): String? = actualMap.put(key, value).also { saveNow() }
        override fun get(key: String): String? = actualMap[key]
        override fun containsValue(value: String): Boolean = actualMap.containsValue(value)
        override fun containsKey(key: String): Boolean = actualMap.containsKey(key)

        private fun Any?.toInfoStringMap(): Map<String, String> {
            return when (this) {
                null, is Undefined -> emptyMap()
                is Map<*, *> -> entries.mapNotNull { (key, value) ->
                    val k = key?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    k to value.toInfoString()
                }.toMap()
                is NativeObject -> ids.mapNotNull { id ->
                    val key = id.toString()
                    val value = when (id) {
                        is Number -> get(id.toInt(), this)
                        else -> get(key, this)
                    }
                    key.takeIf { it.isNotBlank() }?.let { it to value.toInfoString() }
                }.toMap()
                is String -> runCatching {
                    val type = object : TypeToken<Map<String, String>>() {}.type
                    gson.fromJson<Map<String, String>>(this, type)
                }.getOrNull().orEmpty()
                else -> emptyMap()
            }
        }

        private fun Any?.toInfoString(): String {
            return when (this) {
                null, is Undefined -> ""
                is CharSequence -> toString()
                is Number, is Boolean -> toString()
                else -> toString()
            }
        }
    }

    override val id: Int = "lnr.legado.json".hashCode()
    override val permits: Int = 32
    override suspend fun isOffLine(): Boolean = false
    override val offLine: Boolean = false
    override val isOffLineFlow: StateFlow<Boolean> = MutableStateFlow(false)
    override val imageHeader: Map<String, String>
        get() {
            // 返回第一个启用书源的header，用于图片防盗链
            val source = getEnabledSources().firstOrNull() ?: return emptyMap()
            return source.getHeaderMap(false).takeIf { it.isNotEmpty() }
                ?: mapOf("User-Agent" to io.legado.engine.constant.AppConst.USER_AGENT)
        }

    // ==================== SearchProvider ====================
    override val searchProvider: SearchProvider = object : SearchProvider {
        override val searchTypes: List<SearchType> = listOf(
            SearchType("default", LocalString("Legado"), LocalString("搜索Legado书源"))
        )

        override fun search(searchType: SearchType, keyword: String): Flow<SearchResult> = flow {
            for (source in getEnabledSources()) {
                try {
                    val books = withContext(Dispatchers.IO) { WebBook.searchBookAwait(source, keyword) }
                    for (book in books) {
                        val info = createBookInformation(source, book)
                        emit(SearchResult.MultipleBook(info))
                    }
                } catch (_: Exception) {}
            }
            emit(SearchResult.End())
        }
    }

    // ==================== ExplorePageProvider ====================
    override val explorePageProvider: ExplorePageProvider = object :
        ExplorePageProvider.DefaultExplorePageProvider,
        ExplorePageProvider.CustomExplorePageProvider<LegadoExploreUiState> {
        override val uiState = LegadoExploreUiState()

        override fun init(viewModelScope: CoroutineScope) = Unit

        @Composable
        override fun Content(nestedScrollConnection: NestedScrollConnection) {
            LegadoExploreContent(uiState, nestedScrollConnection)
        }
        private val sourceKinds: Map<BookSource, List<ExploreKind>>
            get() = getEnabledSources()
                .filter { it.enabledExplore && !it.exploreUrl.isNullOrBlank() }
                .associateWith { parseExploreKinds(it) }
                .filterValues { it.isNotEmpty() }

        override val explorePageIdList: List<String>
            get() = sourceKinds.keys.map { it.bookSourceUrl }

        override val exploreTapPageDataSourceMap: Map<String, ExploreTapPageDataSource>
            get() {
                val result = linkedMapOf<String, ExploreTapPageDataSource>()
                sourceKinds.forEach { (src, kinds) ->
                    result[src.bookSourceUrl] = object : ExploreTapPageDataSource {
                        override val title: String = src.bookSourceName
                        override fun getRowsFlow(): Flow<List<ExploreBooksRow>> = flow {
                            try {
                                val rows = mutableListOf<ExploreBooksRow>()
                                val effectiveKinds = kinds.filter { it.type.equals("url", true) && !it.url.isNullOrBlank() }
                                for ((index, kind) in effectiveKinds.withIndex()) {
                                    val books = if (index < EXPLORE_PREVIEW_ROW_LIMIT) {
                                        withContext(Dispatchers.IO) {
                                            val infoMap = exploreInfoMaps.getOrPut(src.bookSourceUrl) { ExploreInfoMap(src.bookSourceUrl) }
                                            WebBook.exploreBookAwait(src, kind.url, 1, infoMap).take(EXPLORE_PREVIEW_BOOK_LIMIT)
                                        }.also { if (it.isEmpty()) promptLoginIfNoAuth(src) }
                                    } else {
                                        emptyList()
                                    }
                                    rows.add(
                                        ExploreBooksRow(
                                            title = kind.displayTitle(),
                                            bookList = books.map { book -> createExploreDisplayBook(src, book) },
                                            expandable = true,
                                            expandedPageDataSourceId = exploreKindId(src.bookSourceUrl, kind.url, kind.displayTitle())
                                        )
                                    )
                                }
                                emit(rows)
                            } catch (_: Exception) {
                                promptLoginIfNoAuth(src)
                                emit(emptyList())
                            }
                        }
                    }
                }
                return result
            }

        override val exploreExpandedPageDataSourceMap: Map<String, ExploreExpandedPageDataSource>
            get() {
                val result = linkedMapOf<String, ExploreExpandedPageDataSource>()
                sourceKinds.forEach { (src, kinds) ->
                    kinds.filter { it.type.equals("url", true) && !it.url.isNullOrBlank() }.forEach { kind ->
                        val id = exploreKindId(src.bookSourceUrl, kind.url, kind.displayTitle())
                        result[id] = object : ExploreExpandedPageDataSource {
                            override val title: String = kind.displayTitle()
                            override val filters: List<Filter<*>> = createExploreFilters(src, kinds)
                            private var page = 1
                            @Volatile private var loading = false
                            @Volatile private var ended = false
                            private val pageRequests = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 1)

                            init {
                                pageRequests.tryEmit(page)
                                filters.forEach { filter ->
                                    filter.addOnChangeListener {
                                        page = 1
                                        ended = false
                                        pageRequests.tryEmit(page)
                                    }
                                }
                            }

                            override fun loadMore() {
                                if (loading || ended) return
                                page++
                                pageRequests.tryEmit(page)
                            }

                            override fun getResultFlow(): Flow<SearchResult> = channelFlow {
                                pageRequests.collect { requestedPage ->
                                    try {
                                        loading = true
                                        val books = withContext(Dispatchers.IO) {
                                            val infoMap = exploreInfoMaps.getOrPut(src.bookSourceUrl) { ExploreInfoMap(src.bookSourceUrl) }
                                            WebBook.exploreBookAwait(src, kind.url, requestedPage, infoMap)
                                        }
                                        if (books.isEmpty() && requestedPage == 1) promptLoginIfNoAuth(src)
                                        for (book in books) send(SearchResult.MultipleBook(createBookInformation(src, book)))
                                        if (books.isEmpty()) {
                                            ended = true
                                            send(SearchResult.Empty())
                                        }
                                    } catch (_: Exception) {
                                        promptLoginIfNoAuth(src)
                                        ended = requestedPage > 1
                                        send(SearchResult.Empty())
                                    } finally {
                                        loading = false
                                    }
                                }
                            }
                        }
                    }
                }
                return result
            }
    }

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Composable
    private fun LegadoExploreContent(
        state: LegadoExploreUiState,
        nestedScrollConnection: NestedScrollConnection
    ) {
        val navController = LocalNavController.current
        val scope = rememberCoroutineScope()
        val sources = getEnabledSources()
            .filter { it.enabledExplore && !it.exploreUrl.isNullOrBlank() }
        if (sources.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("暂无启用的 Legado 发现书源", style = MaterialTheme.typography.titleMedium)
                Text("导入并启用带发现规则的书源后会显示在这里。", style = MaterialTheme.typography.bodyMedium)
            }
            return
        }

        LaunchedEffect(sources.map { it.bookSourceUrl }) {
            if (state.selectedSourceUrl !in sources.map { it.bookSourceUrl }) {
                state.selectedSourceUrl = sources.first().bookSourceUrl
                state.selectedKindKey = ""
                state.reloadToken++
            }
        }

        val source = sources.firstOrNull { it.bookSourceUrl == state.selectedSourceUrl } ?: sources.first()
        val kinds = remember(source.bookSourceUrl, state.reloadToken) { parseExploreKinds(source) }
        val urlKinds = kinds.filter { it.type.equals("url", true) && it.url.isNotBlank() }
        val controlKinds = kinds.filter { it.type.lowercase() in setOf("toggle", "select", "text", "button") }
        val selectedKind = urlKinds.firstOrNull { it.exploreStateKey() == state.selectedKindKey }
            ?: urlKinds.firstOrNull()

        LaunchedEffect(source.bookSourceUrl, urlKinds.map { it.exploreStateKey() }) {
            val selectedKey = selectedKind?.exploreStateKey().orEmpty()
            if (selectedKey.isNotBlank() && state.selectedKindKey != selectedKey) {
                state.selectedKindKey = selectedKey
            }
        }

        fun loadPage(page: Int) {
            val kind = selectedKind ?: return
            if (state.loading || (page > 1 && state.ended)) return
            scope.launch {
                state.loading = true
                state.error = null
                runCatching {
                    withContext(Dispatchers.IO) {
                        val infoMap = exploreInfoMaps.getOrPut(source.bookSourceUrl) { ExploreInfoMap(source.bookSourceUrl) }
                        WebBook.exploreBookAwait(source, kind.url, page, infoMap)
                    }
                }.onSuccess { books ->
                    if (page == 1) state.books.clear()
                    state.books.addAll(books.map { createBookInformation(source, it) })
                    state.page = page
                    state.ended = books.isEmpty()
                    if (books.isEmpty() && page == 1) promptLoginIfNoAuth(source)
                }.onFailure { error ->
                    if (page == 1) state.books.clear()
                    state.error = error.message ?: error::class.java.simpleName
                    promptLoginIfNoAuth(source)
                }
                state.loading = false
            }
        }

        LaunchedEffect(source.bookSourceUrl, selectedKind?.exploreStateKey(), state.reloadToken) {
            state.page = 1
            state.ended = false
            loadPage(1)
        }

        var selectDialogKind by remember { mutableStateOf<ExploreKind?>(null) }
        val listState = rememberLazyListState()
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sources.forEach { item ->
                        FilterChip(
                            selected = item.bookSourceUrl == source.bookSourceUrl,
                            onClick = {
                                state.selectedSourceUrl = item.bookSourceUrl
                                state.selectedKindKey = ""
                                state.reloadToken++
                            },
                            label = {
                                Text(
                                    item.bookSourceName.ifBlank { item.bookSourceUrl },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }
            if (controlKinds.isNotEmpty()) {
                item {
                    ExploreControls(
                        source = source,
                        kinds = controlKinds,
                        state = state,
                        onSelectDialog = { selectDialogKind = it }
                    )
                }
            }
            if (urlKinds.isNotEmpty()) {
                item {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        urlKinds.forEach { kind ->
                            val key = kind.exploreStateKey()
                            FilterChip(
                                selected = key == selectedKind?.exploreStateKey(),
                                onClick = {
                                    state.selectedKindKey = key
                                    state.reloadToken++
                                },
                                label = { Text(kind.displayTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
            }
            if (state.loading && state.books.isEmpty()) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }
            state.error?.let { message ->
                item {
                    Text(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            items(state.books, key = { it.id }) { book ->
                ListItem(
                    modifier = Modifier.clickable { navController.navigate(Route.Book.Detail(book.id)) },
                    headlineContent = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val subtitle = listOf(book.author, book.subtitle).filter { it.isNotBlank() }.joinToString("  ")
                        if (subtitle.isNotBlank()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                )
                Divider()
            }
            if (selectedKind != null && state.books.isNotEmpty() && !state.ended) {
                item {
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        enabled = !state.loading,
                        onClick = { loadPage(state.page + 1) }
                    ) {
                        Text(if (state.loading) "加载中" else "加载更多")
                    }
                }
            }
        }

        selectDialogKind?.let { kind ->
            val choices = kind.chars?.filterNotNull()?.filter { it.isNotBlank() }.orEmpty()
            AlertDialog(
                onDismissRequest = { selectDialogKind = null },
                title = { Text(kind.displayTitle()) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        choices.forEach { choice ->
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    applyExploreControl(source, kind, choice)
                                    runExploreAction(source, kind)
                                    state.reloadToken++
                                    selectDialogKind = null
                                }
                            ) { Text(choice) }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectDialogKind = null }) { Text("关闭") }
                }
            )
        }
    }

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Composable
    private fun ExploreControls(
        source: BookSource,
        kinds: List<ExploreKind>,
        state: LegadoExploreUiState,
        onSelectDialog: (ExploreKind) -> Unit
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            kinds.forEach { kind ->
                val key = kind.title.ifBlank { kind.displayTitle() }
                when (kind.type.lowercase()) {
                    "toggle" -> {
                        val onValue = kind.chars?.getOrNull(1) ?: "true"
                        val offValue = kind.chars?.getOrNull(0) ?: "false"
                        val selected = currentExploreControlValue(source, kind) == onValue
                        FilterChip(
                            selected = selected,
                            onClick = {
                                applyExploreControl(source, kind, if (selected) offValue else onValue)
                                runExploreAction(source, kind)
                                state.reloadToken++
                            },
                            label = { Text(kind.displayTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                    "select" -> {
                        AssistChip(
                            onClick = { onSelectDialog(kind) },
                            label = {
                                val value = currentExploreControlValue(source, kind).ifBlank { kind.default.orEmpty() }
                                Text("${kind.displayTitle()}: $value", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        )
                    }
                    "text" -> {
                        var textValue by remember(source.bookSourceUrl, key, state.reloadToken) {
                            mutableStateOf(state.textInputs[key] ?: currentExploreControlValue(source, kind))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                modifier = Modifier.weight(1f),
                                value = textValue,
                                onValueChange = {
                                    textValue = it
                                    state.textInputs[key] = it
                                },
                                label = { Text(kind.displayTitle()) },
                                singleLine = true
                            )
                            Button(
                                modifier = Modifier.padding(top = 8.dp),
                                onClick = {
                                    applyExploreControl(source, kind, textValue)
                                    runExploreAction(source, kind)
                                    state.reloadToken++
                                }
                            ) {
                                Text("应用")
                            }
                        }
                    }
                    "button" -> {
                        Button(
                            onClick = {
                                runExploreAction(source, kind)
                                state.reloadToken++
                            }
                        ) {
                            Text(kind.displayTitle().ifBlank { "执行" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    private fun currentExploreControlValue(source: BookSource, kind: ExploreKind): String {
        val key = kind.title.ifBlank { kind.displayTitle() }
        return SourceConfig.get(source.bookSourceUrl, key)
            ?: exploreInfoMaps.getOrPut(source.bookSourceUrl) { ExploreInfoMap(source.bookSourceUrl) }[key]
            ?: kind.default
            ?: kind.chars?.firstOrNull { !it.isNullOrBlank() }
            ?: ""
    }

    private fun applyExploreControl(source: BookSource, kind: ExploreKind, value: String) {
        val key = kind.title.ifBlank { kind.displayTitle() }
        SourceConfig.put(source.bookSourceUrl, key, value)
        source.put(key, value)
        exploreInfoMaps.getOrPut(source.bookSourceUrl) { ExploreInfoMap(source.bookSourceUrl) }[key] = value
    }

    private fun runExploreAction(source: BookSource, kind: ExploreKind) {
        kind.action?.takeIf { it.isNotBlank() }?.let { action ->
            runCatching {
                source.evalJS(action) { bindings ->
                    bindings["infoMap"] = exploreInfoMaps.getOrPut(source.bookSourceUrl) { ExploreInfoMap(source.bookSourceUrl) }
                }
            }
        }
    }

    private fun ExploreKind.exploreStateKey(): String =
        listOf(displayTitle(), url, type).joinToString("\n")

    init {
        if (!AndroidContext.isInitialized()) {
            AndroidContext.init(context)
        }
        loadSources()
        // Register callback for java.open() / java.openExplore() / java.openSearch()
        SourceOpenCallback.setCallback { type, url, title ->
            android.util.Log.i("LegadoDS", "open: type=$type url=$url title=$title")
            // Store for potential use by explore page provider
            lastOpenType = type
            lastOpenUrl = url
            lastOpenTitle = title
            openEvents.add(OpenEvent(type, url, title))
        }
        SourceLoginCallback.setCallback { baseSource ->
            val src = (baseSource as? BookSource)
                ?: bookSources.firstOrNull { it.bookSourceUrl == baseSource.getKey() }
                ?: return@setCallback
            promptLogin(src, force = true)
        }
        // 注册浏览器Dialog回调
        SourceBrowserCallback.setShowCallback { url, html, preloadJs, _ ->
            io.legado.engine.webview.BrowserDialogHelper.open(
                context, url, "浏览器", html, preloadJs
            )
        }
        SourceBrowserCallback.setAwaitCallback { url, html, preloadJs ->
            io.legado.engine.webview.BrowserDialogHelper.openAwait(
                context, url, "浏览器", html, preloadJs
            )
        }
    }

    // Last opened URL from java.open() calls (for <useweb> interaction)
    @Volatile var lastOpenType: String = ""
    @Volatile var lastOpenUrl: String = ""
    @Volatile var lastOpenTitle: String = ""

    fun importSources(json: String): Int {
        val sources = parseImportedSources(json)
        var count = 0
        for (src in sources) {
            if (src.bookSourceUrl.isNotBlank()) {
                bookSources.removeAll { it.bookSourceUrl == src.bookSourceUrl }
                bookSources.add(src); count++
            }
        }
        saveSources()
        return count
    }

    fun getEnabledSources(): List<BookSource> = bookSources.filter { it.enabled }

    private fun makeId(sourceUrl: String, bookUrl: String) = "$sourceUrl$SEPARATOR$bookUrl"

    private fun parseId(id: String): Pair<String, String> {
        val idx = id.indexOf(SEPARATOR)
        return if (idx > 0) Pair(id.substring(0, idx), id.substring(idx + SEPARATOR.length))
        else Pair("", id)
    }

    private fun getSource(sourceUrl: String) = bookSources.firstOrNull { it.bookSourceUrl == sourceUrl }

    fun getAllSources(): List<BookSource> = bookSources.toList()

    fun reloadSources() {
        loadSources()
    }

    fun setSourceEnabled(sourceUrl: String, enabled: Boolean): Boolean {
        val source = getSource(sourceUrl) ?: return false
        source.enabled = enabled
        saveSources()
        return true
    }

    fun deleteSource(sourceUrl: String): Boolean {
        val removed = bookSources.removeAll { it.bookSourceUrl == sourceUrl }
        if (removed) saveSources()
        return removed
    }

    private fun loadSources() {
        try {
            val file = File(context.filesDir, "legado_sources.json")
            if (file.exists()) {
                val json = file.readText()
                val type = object : TypeToken<List<BookSource>>() {}.type
                bookSources.clear()
                bookSources.addAll(gson.fromJson(json, type) ?: emptyList<BookSource>())
            }
        } catch (_: Exception) {}
    }

    private fun saveSources() {
        try { File(context.filesDir, "legado_sources.json").writeText(gson.toJson(bookSources)) }
        catch (_: Exception) {}
    }

    private fun parseImportedSources(json: String): List<BookSource> {
        val trimmed = json.trim()
        val root = runCatching { JsonParser.parseString(trimmed) }.getOrNull()
        if (root != null) {
            val sources = sourceElementsFromImportRoot(root)
                .mapNotNull { parseSourceElement(it) }
                .filter { it.bookSourceUrl.isNotBlank() }
            if (sources.isNotEmpty()) return sources
        }
        return runCatching {
            val listType = object : TypeToken<List<BookSource>>() {}.type
            gson.fromJson<List<BookSource>>(trimmed, listType)
        }.getOrNull()?.filter { it.bookSourceUrl.isNotBlank() } ?: emptyList()
    }

    private fun sourceElementsFromImportRoot(element: JsonElement): List<JsonElement> {
        return when {
            element.isJsonArray -> element.asJsonArray.flatMap { sourceElementsFromImportRoot(it) }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                if (obj.has("bookSourceUrl")) {
                    listOf(obj)
                } else {
                    obj.entrySet().flatMap { (_, value) ->
                        if (value.isJsonArray || value.isJsonObject) sourceElementsFromImportRoot(value) else emptyList()
                    }
                }
            }
            else -> emptyList()
        }
    }

    private fun parseSourceElement(element: JsonElement): BookSource? {
        return runCatching {
            gson.fromJson(migrateSourceJson(element), BookSource::class.java)?.also { src ->
                // Gson不会使用Kotlin默认值，缺失字段为null。补全关键默认值。
                if (src.enabledCookieJar == null) src.enabledCookieJar = true
                if (src.enabledExplore == null) src.enabledExplore = true  // 但enabledExplore是Boolean非空，不需要
            }
        }.getOrNull()
    }

    private fun migrateSourceJson(element: JsonElement): JsonElement {
        if (!element.isJsonObject) return element
        val obj = element.asJsonObject.deepCopy()
        copyMissing(obj, "enabled", "enable")
        copyMissing(obj, "customOrder", "serialNumber")
        copyMissing(obj, "bookUrlPattern", "ruleBookUrlPattern")
        copyMissing(obj, "header", "httpHeaders")
        if (!obj.hasNonBlank("header")) {
            obj.getStringOrNull("httpUserAgent")?.let { ua ->
                obj.addProperty("header", gson.toJson(mapOf("User-Agent" to ua)))
            }
        }
        copyMissingUrl(obj, "searchUrl", "ruleSearchUrl", single = true)
        copyMissingUrl(obj, "exploreUrl", "ruleFindUrl", single = false)
        putRuleObjectIfMissing(obj, "ruleSearch", mapOf(
            "bookList" to "ruleSearchList",
            "name" to "ruleSearchName",
            "author" to "ruleSearchAuthor",
            "intro" to "ruleSearchIntroduce",
            "kind" to "ruleSearchKind",
            "bookUrl" to "ruleSearchNoteUrl",
            "coverUrl" to "ruleSearchCoverUrl",
            "lastChapter" to "ruleSearchLastChapter",
            "wordCount" to "ruleSearchWordCount"
        ))
        putRuleObjectIfMissing(obj, "ruleExplore", mapOf(
            "bookList" to "ruleFindList",
            "name" to "ruleFindName",
            "author" to "ruleFindAuthor",
            "intro" to "ruleFindIntroduce",
            "kind" to "ruleFindKind",
            "bookUrl" to "ruleFindNoteUrl",
            "coverUrl" to "ruleFindCoverUrl",
            "lastChapter" to "ruleFindLastChapter",
            "wordCount" to "ruleFindWordCount"
        ))
        putRuleObjectIfMissing(obj, "ruleBookInfo", mapOf(
            "init" to "ruleBookInfoInit",
            "name" to "ruleBookName",
            "author" to "ruleBookAuthor",
            "intro" to "ruleIntroduce",
            "kind" to "ruleBookKind",
            "coverUrl" to "ruleCoverUrl",
            "lastChapter" to "ruleBookLastChapter",
            "tocUrl" to "ruleChapterUrl",
            "wordCount" to "ruleBookWordCount"
        ))
        putRuleObjectIfMissing(obj, "ruleToc", mapOf(
            "chapterList" to "ruleChapterList",
            "chapterName" to "ruleChapterName",
            "chapterUrl" to "ruleContentUrl",
            "nextTocUrl" to "ruleChapterUrlNext"
        ))
        putRuleObjectIfMissing(obj, "ruleContent", mapOf(
            "content" to "ruleBookContent",
            "replaceRegex" to "ruleBookContentReplace",
            "nextContentUrl" to "ruleContentUrlNext"
        ))
        return obj
    }

    private fun copyMissing(obj: GsonJsonObject, newKey: String, oldKey: String) {
        if (!obj.hasNonBlank(newKey)) {
            obj.get(oldKey)?.let { if (!it.isJsonNull) obj.add(newKey, it.deepCopy()) }
        }
    }

    private fun copyMissingUrl(obj: GsonJsonObject, newKey: String, oldKey: String, single: Boolean) {
        if (!obj.hasNonBlank(newKey)) {
            val oldValue = obj.getStringOrNull(oldKey) ?: return
            val migrated = if (single) migrateOldUrl(oldValue) else migrateOldUrls(oldValue)
            if (!migrated.isNullOrBlank()) obj.addProperty(newKey, migrated)
        }
    }

    private fun putRuleObjectIfMissing(obj: GsonJsonObject, targetKey: String, fields: Map<String, String>) {
        if (obj.has(targetKey) && obj.get(targetKey).isJsonObject) return
        val target = GsonJsonObject()
        fields.forEach { (newKey, oldKey) ->
            val value = obj.getStringOrNull(oldKey) ?: return@forEach
            var migrated = migrateOldRule(value)
            if (targetKey == "ruleContent" && newKey == "content" && migrated.startsWith("$") && !migrated.startsWith("$.")) {
                migrated = migrated.substring(1)
            }
            if (migrated.isNotBlank()) target.addProperty(newKey, migrated)
        }
        if (target.entrySet().isNotEmpty()) obj.add(targetKey, target)
    }

    private fun GsonJsonObject.hasNonBlank(key: String): Boolean = getStringOrNull(key)?.isNotBlank() == true

    private fun GsonJsonObject.getStringOrNull(key: String): String? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asString }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun migrateOldUrls(oldUrls: String?): String? {
        if (oldUrls.isNullOrBlank()) return null
        if (oldUrls.startsWith("@js:", true) || oldUrls.startsWith("<js>", true)) return oldUrls
        if (!oldUrls.contains("\n") && !oldUrls.contains("&&")) return migrateOldUrl(oldUrls)
        return oldUrls.split("(&&|\r?\n)+".toRegex())
            .mapNotNull { migrateOldUrl(it)?.replace("\n\\s*".toRegex(), "") }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    }

    private fun migrateOldUrl(oldUrl: String?): String? {
        if (oldUrl.isNullOrBlank()) return null
        if (oldUrl.startsWith("<js>", true)) {
            return oldUrl.replace("=searchKey", "={{key}}")
                .replace("=searchPage", "={{page}}")
        }
        val option = linkedMapOf<String, String>()
        var url = oldUrl.trim()
        Regex("@Header:\\{[^}]*}", RegexOption.IGNORE_CASE).find(url)?.let { match ->
            url = url.replace(match.value, "")
            option["headers"] = match.value.substringAfter("@Header:")
        }
        val charsetSplit = url.split("|", limit = 2)
        url = charsetSplit[0]
        if (charsetSplit.size > 1) {
            charsetSplit[1].substringAfter("=", "").takeIf { it.isNotBlank() }?.let { option["charset"] = it }
        }
        val jsPattern = Regex("""\{[^{}]*}""")
        val jsList = mutableListOf<String>()
        jsPattern.findAll(url).forEach { match ->
            jsList.add(match.value)
            url = url.replace(match.value, "$" + jsList.lastIndex)
        }
        url = url.replace("{", "<").replace("}", ">")
            .replace("searchKey", "{{key}}")
            .replace("<searchPage([-+]1)>".toRegex(), "{{page$1}}")
            .replace("searchPage([-+]1)".toRegex(), "{{page$1}}")
            .replace("searchPage", "{{page}}")
        jsList.forEachIndexed { index, js ->
            url = url.replace("$" + index, js.replace("searchKey", "key").replace("searchPage", "page"))
        }
        val postSplit = url.split("@", limit = 2)
        url = postSplit[0]
        if (postSplit.size > 1) {
            option["method"] = "POST"
            option["body"] = postSplit[1]
        }
        return if (option.isEmpty()) url else "$url,${gson.toJson(option)}"
    }

    private fun migrateOldRule(oldRule: String?): String {
        if (oldRule.isNullOrBlank()) return ""
        var newRule = oldRule
        var reverse = false
        var allInOne = false
        if (newRule.startsWith("-")) {
            reverse = true
            newRule = newRule.substring(1)
        }
        if (newRule.startsWith("+")) {
            allInOne = true
            newRule = newRule.substring(1)
        }
        if (!newRule.startsWith("@CSS:", true) &&
            !newRule.startsWith("@XPath:", true) &&
            !newRule.startsWith("//") &&
            !newRule.startsWith("##") &&
            !newRule.startsWith(":") &&
            !newRule.contains("@js:", true) &&
            !newRule.contains("<js>", true)
        ) {
            if (newRule.contains("#") && !newRule.contains("##")) newRule = oldRule.replace("#", "##")
            if (newRule.contains("|") && !newRule.contains("||")) {
                newRule = if (newRule.contains("##")) {
                    val list = newRule.split("##")
                    buildString {
                        append(list.first().replace("|", "||"))
                        list.drop(1).forEach { append("##").append(it) }
                    }
                } else {
                    newRule.replace("|", "||")
                }
            }
            if (newRule.contains("&") && !newRule.contains("&&") && !newRule.contains("http") && !newRule.startsWith("/")) {
                newRule = newRule.replace("&", "&&")
            }
        }
        if (allInOne) newRule = "+$newRule"
        if (reverse) newRule = "-$newRule"
        return newRule
    }

    private fun parseExploreKinds(source: BookSource): List<ExploreKind> {
        val raw = source.exploreUrl ?: return emptyList()
        val value = runCatching { evaluateExploreRule(source, raw) }
            .onFailure {
                android.util.Log.w("LegadoDS", "parse exploreUrl failed: ${source.bookSourceName}", it)
                promptLoginIfNoAuth(source)
            }
            .getOrNull()
            ?: return emptyList()
        val openedKinds = drainOpenExploreKinds(source)
        val parsedKinds = if (value is CharSequence && value.contains("<useweb", ignoreCase = true)) {
            parseUseWebExploreKinds(source, value.toString()).takeIf { it.isNotEmpty() }
                ?: normalizeExploreKinds(value)
        } else {
            normalizeExploreKinds(value)
        }
        val kinds = (openedKinds + parsedKinds).distinctBy { it.displayTitle() + "\n" + it.url + "\n" + it.type }
        if (kinds.isEmpty()) promptLoginIfNoAuth(source)
        return kinds.filter { kind ->
            !kind.title.startsWith("ERROR:", true) && kind.displayTitle().isNotBlank() &&
                (kind.url.isNotBlank() || kind.type.equals("toggle", true) || kind.type.equals("select", true))
        }
    }

    private fun evaluateExploreRule(source: BookSource, raw: String): Any? {
        val rule = raw.trim()
        return when {
            rule.startsWith("@js:", true) -> evalExploreJs(source, rule.substring(4))
            rule.startsWith("<js>", true) -> {
                val match = Regex("<js>([\\s\\S]*?)</js>", RegexOption.IGNORE_CASE).find(rule)
                val js = match?.groupValues?.getOrNull(1) ?: rule.substring(4)
                evalExploreJs(source, js)
            }
            else -> raw
        }
    }

    private fun evalExploreJs(source: BookSource, js: String): Any? {
        val infoMap = exploreInfoMaps.getOrPut(source.bookSourceUrl) { ExploreInfoMap(source.bookSourceUrl) }
        openEvents.clear()
        return source.evalJS(js) { bindings ->
            bindings["infoMap"] = infoMap
        }
    }

    private fun drainOpenExploreKinds(source: BookSource): List<ExploreKind> {
        val result = mutableListOf<ExploreKind>()
        while (true) {
            val event = openEvents.poll() ?: break
            if (event.type.equals("explore", true) && event.url.isNotBlank()) {
                result.add(
                    ExploreKind(
                        title = event.title.ifBlank { event.url },
                        url = normalizeUseWebUrl(source, "", event.url)
                    )
                )
            }
        }
        return result
    }

    private fun normalizeExploreKinds(value: Any?): List<ExploreKind> {
        return when (value) {
            null -> emptyList()
            is ExploreKind -> listOf(value)
            is JsonElement -> exploreKindsFromJson(value)
            is Map<*, *> -> exploreKindsFromMap(value)
            is Iterable<*> -> exploreKindsFromList(value.toList())
            is Array<*> -> exploreKindsFromList(value.toList())
            else -> parseExploreKindString(value.toString())
        }
    }

    private fun parseExploreKindString(raw: String): List<ExploreKind> {
        val rule = raw.trim()
        if (rule.isBlank()) return emptyList()
        val jsonKinds = runCatching { exploreKindsFromJson(JsonParser.parseString(rule)) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
        if (jsonKinds != null) return jsonKinds
        return rule.split("(&&|\\n)+".toRegex())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { item ->
                val parts = item.split("::", limit = 2)
                ExploreKind(
                    title = parts.firstOrNull()?.trim().orEmpty(),
                    url = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrBlank() } ?: parts.firstOrNull()?.trim().orEmpty()
                )
            }
    }

    private fun parseUseWebExploreKinds(source: BookSource, raw: String): List<ExploreKind> {
        val body = Regex("<useweb[^>]*>([\\s\\S]*?)</useweb>", RegexOption.IGNORE_CASE)
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?: raw
        val doc = Jsoup.parseBodyFragment(body, source.bookSourceUrl)
        val scripts = doc.select("script").joinToString("\n") { it.data().ifBlank { it.html() } }
        if (scripts.isBlank()) return emptyList()
        val kinds = linkedMapOf<String, ExploreKind>()
        extractDirectOpenKinds(source, scripts).forEach { kinds[it.title + "\n" + it.url] = it }
        extractItemsArrayKinds(source, scripts).forEach { kinds[it.title + "\n" + it.url] = it }
        return kinds.values.toList()
    }

    private fun extractDirectOpenKinds(source: BookSource, script: String): List<ExploreKind> {
        val regex = Regex("""java\.open\(\s*['"]explore['"]\s*,\s*(['"])(.*?)\1\s*,\s*(['"])(.*?)\3\s*\)""")
        return regex.findAll(script).mapNotNull { match ->
            val url = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = match.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() } ?: url
            ExploreKind(title = title, url = normalizeUseWebUrl(source, "", url))
        }.toList()
    }

    private fun extractItemsArrayKinds(source: BookSource, script: String): List<ExploreKind> {
        val base = Regex("""(?:var|let|const)?\s*base(?:Url)?\s*=\s*(['"])(.*?)\1""")
            .find(script)
            ?.groupValues
            ?.getOrNull(2)
            .orEmpty()
        val assigned = extractAssignedArray(script, "items") ?: return emptyList()
        val json = stringifyUseWebItems(source, script, assigned) ?: return emptyList()
        val element = parseJsonLenient(json) ?: return emptyList()
        if (!element.isJsonArray) return emptyList()
        return element.asJsonArray.mapNotNull { item ->
            if (!item.isJsonArray) return@mapNotNull null
            val arr = item.asJsonArray
            val title = arr.getOrNullJson(0)?.let(::jsonScalarString)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = arr.getOrNullJson(1)?.let(::jsonScalarString)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ExploreKind(title = title, url = normalizeUseWebUrl(source, base, url))
        }
    }

    private fun com.google.gson.JsonArray.getOrNullJson(index: Int): JsonElement? {
        return if (index in 0 until size()) get(index) else null
    }

    private fun stringifyUseWebItems(source: BookSource, script: String, assigned: AssignedArray): String? {
        val prefix = script.substring(0, assigned.endIndex)
        return runCatching { source.evalJS("$prefix;\nJSON.stringify(items)")?.toString() }.getOrNull()
            ?: runCatching { source.evalJS("JSON.stringify(${assigned.text})")?.toString() }.getOrNull()
    }

    private fun extractAssignedArray(script: String, name: String): AssignedArray? {
        val match = Regex("""(?:var|let|const)?\s*${Regex.escape(name)}\s*=""").find(script) ?: return null
        val start = script.indexOf('[', match.range.last + 1).takeIf { it >= 0 } ?: return null
        var quote: Char? = null
        var escape = false
        var depth = 0
        for (i in start until script.length) {
            val ch = script[i]
            if (escape) {
                escape = false
                continue
            }
            if (ch == '\\') {
                escape = true
                continue
            }
            if (quote != null) {
                if (ch == quote) quote = null
                continue
            }
            when (ch) {
                '\'', '"' -> quote = ch
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return AssignedArray(script.substring(start, i + 1), i + 1)
                }
            }
        }
        return null
    }

    private fun normalizeUseWebUrl(source: BookSource, base: String, url: String): String {
        val clean = url.replace("{{page}}", "{{page}}").trim()
        if (clean.startsWith("http://") || clean.startsWith("https://") || clean.startsWith("data:")) return clean
        val baseUrl = base.takeIf { it.isNotBlank() } ?: source.bookSourceUrl
        return AnalyzeUrl.getAbsoluteURL(baseUrl, clean)
    }

    private fun parseJsonLenient(json: String): JsonElement? {
        return runCatching {
            JsonParser.parseReader(JsonReader(StringReader(json)).apply { isLenient = true })
        }.getOrNull()
    }

    private fun exploreKindsFromJson(element: JsonElement): List<ExploreKind> {
        return when {
            element.isJsonArray -> {
                val array = element.asJsonArray
                if (looksLikeExploreKindTuple(array.map { jsonScalarString(it) })) {
                    listOfNotNull(exploreKindFromTuple(array.map { jsonToAny(it) }))
                } else {
                    array.flatMap { exploreKindsFromJson(it) }
                }
            }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                if (obj.entrySet().any { it.key.lowercase() in exploreKindKeys }) {
                    listOfNotNull(runCatching { gson.fromJson(element, ExploreKind::class.java) }.getOrNull())
                } else {
                    obj.entrySet().flatMap { (key, item) ->
                        if (item.isJsonArray || item.isJsonObject) {
                            exploreKindsFromJson(item)
                        } else {
                            listOf(ExploreKind(title = key, url = jsonScalarString(item).orEmpty()))
                        }
                    }
                }
            }
            element.isJsonPrimitive -> parseExploreKindString(element.asString)
            else -> emptyList()
        }
    }

    private fun exploreKindsFromList(list: List<*>): List<ExploreKind> {
        if (looksLikeExploreKindTuple(list.map { it.asExploreString() })) {
            return listOfNotNull(exploreKindFromTuple(list))
        }
        return list.flatMap { normalizeExploreKinds(it) }
    }

    private fun exploreKindsFromMap(map: Map<*, *>): List<ExploreKind> {
        if (map.keys.mapNotNull { it?.toString()?.lowercase() }.any { it in exploreKindKeys }) {
            return listOfNotNull(exploreKindFromMap(map))
        }
        return map.entries.flatMap { (key, value) ->
            when (value) {
                is Map<*, *>, is Iterable<*>, is Array<*>, is JsonElement -> normalizeExploreKinds(value)
                else -> listOf(
                    ExploreKind(
                        title = key?.toString().orEmpty(),
                        url = value.asExploreString().orEmpty()
                    )
                )
            }
        }
    }

    private fun exploreKindFromMap(map: Map<*, *>): ExploreKind? {
        fun pick(vararg names: String): Any? {
            return names.firstNotNullOfOrNull { name ->
                map.entries.firstOrNull { it.key?.toString().equals(name, ignoreCase = true) }?.value
            }
        }
        val title = pick("title", "name").asExploreString().orEmpty()
        val url = pick("url", "href", "value").asExploreString().orEmpty()
        val type = pick("type").asExploreString() ?: "url"
        val action = pick("action").asExploreString()
        val chars = pick("chars", "options").asStringArrayOrNull()
        val defaultValue = pick("default").asExploreString()
        val viewName = pick("viewName", "view").asExploreString()
        if (title.isBlank() && url.isBlank()) return null
        return ExploreKind(
            title = title.ifBlank { viewName ?: url },
            url = url,
            type = type,
            action = action,
            chars = chars,
            default = defaultValue,
            viewName = viewName,
            style = pick("style")
        )
    }

    private fun exploreKindFromTuple(tuple: List<*>): ExploreKind? {
        val title = tuple.getOrNull(0).asExploreString().orEmpty()
        val url = tuple.getOrNull(1).asExploreString().orEmpty()
        if (title.isBlank() && url.isBlank()) return null
        return ExploreKind(
            title = title,
            url = url.ifBlank { title },
            type = tuple.getOrNull(2).asExploreString() ?: "url",
            action = tuple.getOrNull(3).asExploreString(),
            default = tuple.getOrNull(4).asExploreString(),
            viewName = tuple.getOrNull(5).asExploreString()
        )
    }

    private fun looksLikeExploreKindTuple(values: List<String?>): Boolean {
        if (values.size !in 2..8) return false
        if (values.any { it == null }) return false
        val first = values[0].orEmpty()
        val second = values[1].orEmpty()
        if (first.contains("::") || second.contains("::")) return false
        return first.isNotBlank() && second.isNotBlank()
    }

    private fun jsonToAny(element: JsonElement): Any? {
        return when {
            element.isJsonNull -> null
            element.isJsonPrimitive -> jsonScalarString(element)
            element.isJsonArray -> element.asJsonArray.map { jsonToAny(it) }
            element.isJsonObject -> element.asJsonObject.entrySet().associate { it.key to jsonToAny(it.value) }
            else -> null
        }
    }

    private fun jsonScalarString(element: JsonElement): String? {
        return runCatching {
            when {
                element.isJsonNull -> null
                element.asJsonPrimitive.isString -> element.asString
                element.asJsonPrimitive.isBoolean -> element.asBoolean.toString()
                element.asJsonPrimitive.isNumber -> element.asNumber.toString()
                else -> element.toString()
            }
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun Any?.asExploreString(): String? {
        return when (this) {
            null -> null
            is JsonElement -> jsonScalarString(this)
            is CharSequence -> this.toString()
            is Number, is Boolean -> this.toString()
            else -> this.toString()
        }?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun Any?.asStringArrayOrNull(): Array<String?>? {
        return when (this) {
            null -> null
            is JsonElement -> if (this.isJsonArray) {
                this.asJsonArray.map { jsonScalarString(it) }.toTypedArray()
            } else {
                this.asExploreString()?.let { arrayOf(it) }
            }
            is Array<*> -> this.map { it.asExploreString() }.toTypedArray()
            is Iterable<*> -> this.map { it.asExploreString() }.toTypedArray()
            else -> this.asExploreString()?.let { arrayOf(it) }
        }
    }

    private val exploreKindKeys = setOf("title", "name", "url", "href", "value", "type", "action", "chars", "options", "default", "viewname", "view", "style")

    private fun createExploreFilters(source: BookSource, kinds: List<ExploreKind>): List<Filter<*>> {
        return kinds.mapNotNull { kind ->
            when (kind.type.lowercase()) {
                "toggle" -> object : SwitchFilter(LocalString(kind.displayTitle()), kind.default == kind.chars?.getOrNull(1)) {
                    init {
                        value = SourceConfig.get(source.bookSourceUrl, kind.title)
                            ?.let { it == (kind.chars?.getOrNull(1) ?: "true") }
                            ?: value
                        addOnChangeListener { enabled ->
                            val newValue = if (enabled) (kind.chars?.getOrNull(1) ?: "true") else (kind.chars?.getOrNull(0) ?: "false")
                            SourceConfig.put(source.bookSourceUrl, kind.title, newValue)
                            source.put(kind.title, newValue)
                            kind.action?.takeIf { it.isNotBlank() }?.let { runCatching { source.evalJS(it) } }
                        }
                    }
                } as Filter<*>
                "select" -> {
                    val choices = kind.chars?.filterNotNull()?.filter { it.isNotBlank() }.orEmpty()
                    if (choices.isEmpty()) {
                        null
                    } else {
                        SingleChoiceFilter(
                            title = LocalString(kind.displayTitle()),
                            dialogTitle = LocalString(kind.displayTitle()),
                            description = LocalString("Legado source option"),
                            choices = choices,
                            defaultChoice = kind.default ?: choices.first()
                        ).apply {
                            SourceConfig.get(source.bookSourceUrl, kind.title)?.let { value = it }
                            addOnChangeListener { selected ->
                                SourceConfig.put(source.bookSourceUrl, kind.title, selected)
                                source.put(kind.title, selected)
                                kind.action?.takeIf { it.isNotBlank() }?.let { runCatching { source.evalJS(it) } }
                            }
                        } as Filter<*>
                    }
                }
                else -> null
            }
        }
    }

    private fun ExploreKind.displayTitle(): String {
        val rawViewName = viewName
        return rawViewName
            ?.takeIf { it.length in 2..80 && it.first() == '\'' && it.last() == '\'' }
            ?.substring(1, rawViewName.length - 1)
            ?: title
    }

    private fun exploreKindId(sourceUrl: String, url: String, title: String): String {
        return listOf(sourceUrl, title, url).joinToString(SEPARATOR)
    }

    private fun createExploreDisplayBook(source: BookSource, book: SearchBook): ExploreDisplayBook {
        return ExploreDisplayBook(
            id = makeId(source.bookSourceUrl, book.bookUrl),
            title = book.name,
            author = book.author,
            coverUri = parseCoverUri(book.coverUrl, source.bookSourceUrl, source)
        )
    }

    private fun parseImageUri(raw: String?, baseUrl: String): Uri {
        val clean = cleanImageUrl(raw, baseUrl)
        return try { Uri.parse(clean) } catch (_: Exception) { Uri.EMPTY }
    }

    private fun parseCoverUri(raw: String?, baseUrl: String, source: BookSource?): Uri {
        val clean = cleanImageUrl(raw, baseUrl)
        if (clean.isBlank()) return Uri.EMPTY
        // 如果书源有coverDecodeJs，下载图片并通过JS解密后缓存
        val coverJs = source?.coverDecodeJs?.takeIf { it.isNotBlank() }
        if (coverJs != null) {
            try {
                val imageBytes = io.legado.engine.http.HttpClient.getByteArray(clean, source.getHeaderMap(false))
                val decoded = source.evalJS(coverJs) { bindings ->
                    bindings["result"] = imageBytes
                    bindings["src"] = clean
                }
                when (decoded) {
                    is ByteArray -> {
                        val cacheFile = java.io.File(context.cacheDir, "cover_${clean.hashCode()}.img")
                        cacheFile.writeBytes(decoded)
                        return Uri.fromFile(cacheFile)
                    }
                }
            } catch (_: Exception) {}
        }
        return try { Uri.parse(clean) } catch (_: Exception) { Uri.EMPTY }
    }

    private fun cleanImageUrl(raw: String?, baseUrl: String): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return ""
        // Legado 图片格式: url,{"click":"...","style":"TEXT","width":"50%"}
        // 剥离尾部 JSON 参数，只保留纯 URL
        val stripped = stripLegadoImageParams(value).let { UrlOptionParser.strip(it) }
        return if (stripped.startsWith("http://") || stripped.startsWith("https://") || stripped.startsWith("data:") || stripped.startsWith("content://")) {
            stripped
        } else {
            AnalyzeUrl.getAbsoluteURL(baseUrl, stripped)
        }
    }

    private fun sanitizeImageUrl(raw: String, baseUrl: String): String {
        return cleanImageUrl(raw, baseUrl)
    }

    /**
     * 剥离 Legado 图片 src 中的 JSON 参数部分。
     * 例如: "https://example.com/img.png,{\"click\":\"...\",\"style\":\"TEXT\"}"
     * 返回: "https://example.com/img.png"
     */
    private fun stripLegadoImageParams(raw: String): String {
        val value = raw.trim()
        val match = Regex(",\\s*\\{[\\s\\S]*}\$").find(value)
        if (match != null) {
            return value.substring(0, match.range.first).trim()
        }
        return value
    }

    /**
     * 从 img src 中提取 Legado 图片参数 JSON 字符串。返回 null 表示无参数。
     */
    private fun extractLegadoImageParams(raw: String): String? {
        val match = Regex(",\\s*(\\{[\\s\\S]*})\$").find(raw.trim())
        return match?.groupValues?.getOrNull(1)
    }


    private fun createBookInformation(source: BookSource, book: SearchBook): BookInformation {
        return MutableBookInformation(
            id = makeId(source.bookSourceUrl, book.bookUrl),
            title = book.name,
            subtitle = "",
            coverUrl = parseCoverUri(book.coverUrl, source.bookSourceUrl, source),
            author = book.author,
            description = book.intro ?: "",
            tags = book.kind?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
            publishingHouse = "",
            wordCount = WordCount(book.wordCount?.filter { it.isDigit() }?.toIntOrNull() ?: 0),
            lastUpdated = try {
                if (book.time > 0) LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(book.time),
                    java.time.ZoneId.systemDefault()
                ) else LocalDateTime.MIN
            } catch (_: Exception) { LocalDateTime.MIN },
            isComplete = book.latestChapterTitle?.contains("完结") == true ||
                book.latestChapterTitle?.contains("完結") == true ||
                book.latestChapterTitle?.contains("已完结") == true
        )
    }

    // ==================== 登录 ====================

    fun getSourcesNeedingLogin(): List<BookSource> = getEnabledSources()
        .filter { !it.loginUrl.isNullOrBlank() || !it.loginUi.isNullOrBlank() }

    fun startLogin(source: BookSource): Boolean {
        try { LoginActivity.start(context, source) }
        catch (e: Exception) { android.util.Log.e("LegadoDS", "启动登录失败", e); return false }
        return true
    }

    private fun promptLoginIfNoAuth(source: BookSource) {
        if (hasLoginState(source)) return
        promptLogin(source, force = false)
    }

    private fun promptLogin(source: BookSource, force: Boolean) {
        if (source.loginUi.isNullOrBlank() && source.loginUrl.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        val key = source.bookSourceUrl
        val last = loginPromptTimes[key] ?: 0L
        if (!force && now - last < 15_000L) return
        loginPromptTimes[key] = now
        Handler(Looper.getMainLooper()).post {
            try {
                LoginActivity.start(context.applicationContext ?: context, source)
            } catch (e: Exception) {
                // Activity 启动失败（宿主 plugin 模式下 DexClassLoader 未注册 Activity）
                // 降级提示用户手动进入插件管理页登录
                android.util.Log.w("LegadoDS", "LoginActivity 启动失败，降级提示", e)
                try {
                    android.widget.Toast.makeText(
                        context.applicationContext ?: context,
                        "书源「${source.bookSourceName}」需要登录，请进入插件设置页操作",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) {}
            }
        }
    }

    private fun hasLoginState(source: BookSource): Boolean {
        if (!source.getLoginInfo().isNullOrBlank()) return true
        if (!source.getLoginHeader().isNullOrBlank()) return true
        val hosts = listOfNotNull(
            runCatching { URL(source.bookSourceUrl).host }.getOrNull(),
            source.loginUrl?.let { UrlOptionParser.strip(it) }?.let { runCatching { URL(it).host }.getOrNull() }
        ).distinct()
        return hosts.any { CookieStore.getCookie(it).isNotBlank() }
    }

    fun isLoggedIn(source: BookSource): Boolean {
        return hasLoginState(source)
    }

    fun logout(source: BookSource) {
        val domain = try { URL(source.bookSourceUrl).host } catch (_: Exception) { return }
        io.legado.engine.http.CookieStore.clear(domain)
    }

    fun clearSourceRuntimeCache(source: BookSource) {
        val key = source.bookSourceUrl
        CacheManager.delete("infoMap_$key")
        CacheManager.delete("userInfo_$key")
        CacheManager.delete("loginHeader_$key")
        CacheManager.delete("sourceVariable_$key")
        CacheManager.delete("concurrentRate_$key")
        CacheManager.deleteByPrefix("v_${key}_")
        SourceConfig.clear(key)
        runCatching {
            URL(key).host
        }.getOrNull()?.let(CookieStore::clear)
    }

    // ==================== 书籍详情 ====================

    override suspend fun getBookInformation(id: String): BookInformation = withContext(Dispatchers.IO) {
        val (sourceUrl, bookUrl) = parseId(id)
        val source = getSource(sourceUrl) ?: return@withContext BookInformation.empty(id)
        try {
            val book = Book(bookUrl = bookUrl, origin = sourceUrl, originName = source.bookSourceName)
            WebBook.getBookInfoAwait(source, book)
            MutableBookInformation(
                id = id,
                title = book.name,
                subtitle = "",
                coverUrl = parseCoverUri(book.coverUrl, source.bookSourceUrl, source),
                author = book.author,
                description = book.intro ?: "",
                tags = book.kind?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
                publishingHouse = "",
                wordCount = WordCount(book.wordCount?.filter { it.isDigit() }?.toIntOrNull() ?: 0),
                lastUpdated = try {
                    if (book.lastCheckTime > 0) LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(book.lastCheckTime),
                        java.time.ZoneId.systemDefault()
                    ) else LocalDateTime.MIN
                } catch (_: Exception) { LocalDateTime.MIN },
                isComplete = book.latestChapterTitle?.contains("完结") == true ||
                    book.latestChapterTitle?.contains("完結") == true ||
                    book.latestChapterTitle?.contains("已完结") == true
            )
        } catch (_: Exception) { BookInformation.empty(id) }
    }

    override suspend fun getBookVolumes(id: String): BookVolumes = withContext(Dispatchers.IO) {
        val (sourceUrl, bookUrl) = parseId(id)
        val source = getSource(sourceUrl) ?: return@withContext BookVolumes.empty(id)
        try {
            val book = Book(bookUrl = bookUrl, origin = sourceUrl, originName = source.bookSourceName)
            WebBook.getBookInfoAwait(source, book)
            val chapters = WebBook.getChapterListAwait(source, book)
            val chapterInfos = chapters.map { ch -> ChapterInformation(makeId(sourceUrl, ch.url), ch.title) }
            BookVolumes(id, listOf(Volume("::vol0", "目录", chapterInfos)))
        } catch (_: Exception) { BookVolumes.empty(id) }
    }

    // ==================== 正文 ====================

    override suspend fun getChapterContent(chapterId: String, bookId: String): ChapterContent =
        withContext(Dispatchers.IO) {
            val (sourceUrl, chapterUrl) = parseId(chapterId)
            val source = getSource(sourceUrl) ?: return@withContext ChapterContent.empty(chapterId)
            try {
                val (_, bookUrl) = parseId(bookId)
                val book = Book(bookUrl = bookUrl, origin = sourceUrl, originName = source.bookSourceName)
                val bookChapter = BookChapter(bookUrl = bookUrl, url = chapterUrl, title = "", index = 0)
                val rawContent = WebBook.getContentAwait(source, book, bookChapter)
                val chTitle = bookChapter.title.ifBlank { "" }
                val imageDecodeJs = source.getContentRule().imageDecode?.takeIf { it.isNotBlank() }
                val contentJson = buildLnrContentJson(rawContent, chapterUrl, source, imageDecodeJs)
                val (lastChapter, nextChapter) = findChapterNeighbors(source, book, sourceUrl, chapterUrl)
                MutableChapterContent(chapterId, chTitle, contentJson, lastChapter, nextChapter)
            } catch (_: Exception) { ChapterContent.empty(chapterId) }
        }

    private fun findChapterNeighbors(
        source: BookSource,
        book: Book,
        sourceUrl: String,
        chapterUrl: String
    ): Pair<String, String> {
        return runCatching {
            val chapters = WebBook.getChapterListAwait(source, book)
                .filter { !it.isVolume && it.url.isNotBlank() }
            val index = chapters.indexOfFirst { it.url == chapterUrl }
            if (index < 0) return@runCatching "" to ""
            val previous = chapters.getOrNull(index - 1)?.url?.let { makeId(sourceUrl, it) }.orEmpty()
            val next = chapters.getOrNull(index + 1)?.url?.let { makeId(sourceUrl, it) }.orEmpty()
            previous to next
        }.getOrDefault("" to "")
    }

    /**
     * 将 Legado 正文文本转换为 LNR 的 ChapterContent JSON 格式
     * 组件格式: {"id": "simple_text", "data": {"text": "..."}} 或 {"id": "image", "data": {"uri": "..."}}
     */
    private fun buildLnrContentJson(text: String, baseUrl: String, source: BookSource? = null, imageDecodeJs: String? = null): JsonObject {
        val components = buildJsonArray {
            addMarkedContent(text, baseUrl, source, imageDecodeJs)
        }
        return buildJsonObject { put("components", components) }
    }

    private fun JsonArrayBuilder.addMarkedContent(text: String, baseUrl: String, source: BookSource? = null, imageDecodeJs: String? = null) {
        val markerPattern = Regex("<(usehtml|useweb|md)>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)
        var lastEnd = 0
        for (match in markerPattern.findAll(text)) {
            addPlainOrHtmlContent(text.substring(lastEnd, match.range.first), baseUrl)
            val tag = match.groupValues[1].lowercase()
            val body = match.groupValues[2]
            when (tag) {
                "usehtml", "useweb" -> addHtmlContent(body, baseUrl, source, imageDecodeJs)
                "md" -> addMarkdownContent(body, baseUrl, source, imageDecodeJs)
            }
            lastEnd = match.range.last + 1
        }
        if (lastEnd < text.length) addPlainOrHtmlContent(text.substring(lastEnd), baseUrl, source, imageDecodeJs)
    }

    private fun JsonArrayBuilder.addPlainOrHtmlContent(text: String, baseUrl: String, source: BookSource? = null, imageDecodeJs: String? = null) {
        if (text.contains("<img", ignoreCase = true)) {
            addHtmlContent(text, baseUrl, source, imageDecodeJs)
        } else {
            addTextComponents(text)
        }
    }

    private fun JsonArrayBuilder.addHtmlContent(html: String, baseUrl: String, source: BookSource? = null, imageDecodeJs: String? = null) {
        val doc = Jsoup.parseBodyFragment(html, baseUrl)
        val body = doc.body()
        val buffer = StringBuilder()

        fun flushText() {
            val text = buffer.toString()
            buffer.setLength(0)
            addTextComponents(text)
        }

        fun visit(element: Element) {
            when (element.normalName()) {
                "img" -> {
                    flushText()
                    addImageComponent(sanitizeImageUrl(extractImageUrl(element), baseUrl), source, imageDecodeJs)
                    return
                }
                "br" -> {
                    buffer.append('\n')
                    return
                }
                "p", "div", "section", "article", "blockquote", "li", "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    if (buffer.isNotEmpty() && !buffer.endsWith('\n')) buffer.append('\n')
                }
            }
            element.childNodes().forEach { node ->
                when (node) {
                    is TextNode -> buffer.append(node.text())
                    is Element -> visit(node)
                    else -> {
                        val text = Jsoup.parseBodyFragment(node.outerHtml(), baseUrl).text()
                        if (text.isNotBlank()) buffer.append(text)
                    }
                }
            }
            when (element.normalName()) {
                "p", "div", "section", "article", "blockquote", "li", "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    if (buffer.isNotEmpty() && !buffer.endsWith('\n')) buffer.append('\n')
                }
            }
        }
        body.children().forEach(::visit)
        body.ownText().takeIf { it.isNotBlank() }?.let { buffer.insert(0, "$it\n") }
        flushText()
    }

    private fun JsonArrayBuilder.addTextComponents(text: String) {
        val normalized = normalizeText(text)
        for (para in normalized.split("\n")) {
            val p = para.trim()
            if (p.isNotBlank()) {
                add(buildJsonObject {
                    put("id", "simple_text")
                    put("data", buildJsonObject { put("text", p) })
                })
            }
        }
    }

    private fun JsonArrayBuilder.addMarkdownContent(markdown: String, baseUrl: String, source: BookSource? = null, imageDecodeJs: String? = null) {
        val imgPattern = Regex("!\\[[^]]*]\\(([^)]+)\\)")
        var lastEnd = 0
        for (match in imgPattern.findAll(markdown)) {
            addTextComponents(cleanMarkdownText(markdown.substring(lastEnd, match.range.first)))
            addImageComponent(sanitizeImageUrl(match.groupValues[1].trim(), baseUrl), source, imageDecodeJs)
            lastEnd = match.range.last + 1
        }
        if (lastEnd < markdown.length) {
            addTextComponents(cleanMarkdownText(markdown.substring(lastEnd)))
        }
    }

    private fun JsonArrayBuilder.addImageComponent(uri: String, source: BookSource? = null, imageDecodeJs: String? = null) {
        if (uri.isBlank()) return
        val finalUri = if (imageDecodeJs != null && source != null) {
            try {
                val decoded = source.evalJS(imageDecodeJs) { bindings ->
                    bindings["src"] = uri
                }
                decoded?.toString()?.takeIf { it.isNotBlank() } ?: uri
            } catch (_: Exception) { uri }
        } else uri
        add(buildJsonObject {
            put("id", "image")
            put("data", buildJsonObject { put("uri", finalUri) })
        })
    }

    private fun extractImageUrl(element: Element): String {
        val direct = listOf(
            "src",
            "data-src",
            "data-original",
            "data-url",
            "data-lazy-src",
            "data-actualsrc",
            "file"
        ).firstNotNullOfOrNull { name ->
            element.attr(name).trim().takeIf { it.isNotBlank() }
        }
        if (!direct.isNullOrBlank()) return direct
        return element.attr("srcset")
            .split(",")
            .firstOrNull()
            ?.trim()
            ?.substringBefore(" ")
            .orEmpty()
    }

    private fun normalizeText(text: String): String {
        if (text.isBlank()) return ""
        val withBreaks = text
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p\\s*>"), "\n")
            .replace(Regex("(?i)</div\\s*>"), "\n")
            .replace(Regex("(?i)</h[1-6]\\s*>"), "\n")
            .replace(Regex("<button[^>]*>[\\s\\S]*?</button>", RegexOption.IGNORE_CASE), "")
        return Jsoup.parseBodyFragment(withBreaks).body().wholeText()
            .replace('\u00A0', ' ')
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n")
            .trim()
    }

    private fun cleanMarkdownText(markdown: String): String {
        return markdown
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)")) { match -> match.groupValues[1] }
            .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("[*_`~]"), "")
    }

}