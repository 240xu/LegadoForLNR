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
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
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
import io.legado.engine.http.HttpClient
import io.legado.engine.rule.AnalyzeUrl
import io.legado.engine.rule.UrlOptionParser
import io.legado.engine.shim.AndroidContext
import io.legado.engine.shim.CacheManager
import io.legado.engine.shim.GSON
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
import java.util.Base64
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentLinkedQueue

@WebDataSource(name = "Legado JSON", provider = "Legado")
class LegadoJsonWebDataSource(
    private val context: Context
) : WebBookDataSource {

    companion object {
        private val gson: Gson = GSON
        private const val SEPARATOR = "::"
        private const val EXPLORE_PREVIEW_ROW_LIMIT = 12
        private const val EXPLORE_PREVIEW_BOOK_LIMIT = 12
        private const val SEARCH_PAGE_LIMIT = 3
        private const val EVENT_START_READ = "startRead"
        private const val EVENT_END_READ = "endRead"
        private const val EVENT_CLICK_CUSTOM_BUTTON = "clickCustomButton"
        private const val EVENT_LONG_CLICK_CUSTOM_BUTTON = "longClickCustomButton"
    }

    private val bookSources = mutableListOf<BookSource>()
    private val exploreInfoMaps = java.util.concurrent.ConcurrentHashMap<String, ExploreInfoMap>()
    private val chapterListCache = java.util.concurrent.ConcurrentHashMap<String, List<BookChapter>>()
    private val loginPromptTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val openEvents = ConcurrentLinkedQueue<OpenEvent>()
    @Volatile
    private var currentImageHeader: Map<String, String> = emptyMap()

    private data class OpenEvent(val type: String, val url: String, val title: String)

    private data class AssignedArray(val text: String, val endIndex: Int)

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
    override val imageHeader: Map<String, String> get() = currentImageHeader

    // ==================== SearchProvider ====================
    override val searchProvider: SearchProvider = object : SearchProvider {
        override val searchTypes: List<SearchType> = listOf(
            SearchType("default", LocalString("Legado"), LocalString("搜索Legado书源"))
        )

        override fun search(searchType: SearchType, keyword: String): Flow<SearchResult> = flow {
            val emitted = linkedSetOf<String>()
            val errors = mutableListOf<String>()
            for (source in getEnabledSources()) {
                try {
                    val maxPage = if (source.searchUrl?.containsPagePlaceholder() == true) SEARCH_PAGE_LIMIT else 1
                    for (page in 1..maxPage) {
                        val books = withContext(Dispatchers.IO) { WebBook.searchBookAwait(source, keyword, page) }
                        if (books.isEmpty()) break
                        for (book in books) {
                            val info = createBookInformation(source, book)
                            if (emitted.add(info.id)) emit(SearchResult.MultipleBook(info))
                        }
                    }
                } catch (e: Exception) {
                    val sourceName = source.bookSourceName.ifBlank { source.bookSourceUrl }
                    errors.add("$sourceName: ${e.message ?: e::class.java.simpleName}")
                    android.util.Log.w("LegadoDS", "search failed: $sourceName", e)
                }
            }
            if (emitted.isEmpty() && errors.isNotEmpty()) {
                emit(SearchResult.Error(errors.joinToString("\n")))
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
                                            WebBook.exploreBookAwait(src, kind.url, 1, exploreInfoMap(src)).take(EXPLORE_PREVIEW_BOOK_LIMIT)
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
                                            WebBook.exploreBookAwait(src, kind.url, requestedPage, exploreInfoMap(src))
                                        }
                                        if (books.isEmpty() && requestedPage == 1) promptLoginIfNoAuth(src)
                                        for (book in books) send(SearchResult.MultipleBook(createBookInformation(src, book)))
                                        if (books.isEmpty()) {
                                            ended = true
                                            send(SearchResult.Empty())
                                        }
                                    } catch (e: Exception) {
                                        promptLoginIfNoAuth(src)
                                        ended = requestedPage > 1
                                        if (requestedPage == 1) {
                                            val sourceName = src.bookSourceName.ifBlank { src.bookSourceUrl }
                                            send(SearchResult.Error("$sourceName: ${e.message ?: e::class.java.simpleName}"))
                                        } else {
                                            send(SearchResult.Empty())
                                        }
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
                        WebBook.exploreBookAwait(source, kind.url, page, exploreInfoMap(source))
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
            ?: exploreInfoMap(source)[key]
            ?: kind.default
            ?: kind.chars?.firstOrNull { !it.isNullOrBlank() }
            ?: ""
    }

    private fun applyExploreControl(source: BookSource, kind: ExploreKind, value: String) {
        val key = kind.title.ifBlank { kind.displayTitle() }
        SourceConfig.put(source.bookSourceUrl, key, value)
        source.put(key, value)
        exploreInfoMap(source)[key] = value
    }

    private fun runExploreAction(source: BookSource, kind: ExploreKind) {
        kind.action?.takeIf { it.isNotBlank() }?.let { action ->
            runCatching {
                source.evalJS(action) { bindings ->
                    bindings["infoMap"] = exploreInfoMap(source)
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
                invalidateSourceRuntime(src.bookSourceUrl)
            }
        }
        saveSources()
        return count
    }

    fun getEnabledSources(): List<BookSource> = bookSources.filter { it.enabled }

    private fun makeId(sourceUrl: String, bookUrl: String) = "$sourceUrl$SEPARATOR$bookUrl"

    private fun String.containsPagePlaceholder(): Boolean =
        contains("{{page", ignoreCase = true) ||
            contains("searchPage", ignoreCase = true) ||
            Regex("<[^>]*,").containsMatchIn(this)

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
        invalidateSourceRuntime(sourceUrl)
        saveSources()
        return true
    }

    fun deleteSource(sourceUrl: String): Boolean {
        val removed = bookSources.removeAll { it.bookSourceUrl == sourceUrl }
        if (removed) {
            clearSourceRuntimeCache(sourceUrl)
            saveSources()
        }
        return removed
    }

    private fun invalidateSourceRuntime(sourceUrl: String) {
        chapterListCache.keys.removeIf { it.startsWith("$sourceUrl$SEPARATOR") }
        exploreInfoMaps.remove(sourceUrl)
        loginPromptTimes.remove(sourceUrl)
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
            gson.fromJson(migrateSourceJson(element), BookSource::class.java)
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
            "checkKeyWord" to "ruleSearchCheckKeyWord",
            "bookList" to "ruleSearchList",
            "name" to "ruleSearchName",
            "author" to "ruleSearchAuthor",
            "intro" to "ruleSearchIntroduce",
            "kind" to "ruleSearchKind",
            "bookUrl" to "ruleSearchNoteUrl",
            "coverUrl" to "ruleSearchCoverUrl",
            "lastChapter" to "ruleSearchLastChapter",
            "updateTime" to "ruleSearchUpdateTime",
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
            "updateTime" to "ruleFindUpdateTime",
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
            "updateTime" to "ruleBookUpdateTime",
            "tocUrl" to "ruleChapterUrl",
            "wordCount" to "ruleBookWordCount",
            "canReName" to "ruleBookCanReName",
            "downloadUrls" to "ruleDownloadUrls"
        ))
        putRuleObjectIfMissing(obj, "ruleToc", mapOf(
            "preUpdateJs" to "ruleTocPreUpdateJs",
            "chapterList" to "ruleChapterList",
            "chapterName" to "ruleChapterName",
            "chapterUrl" to "ruleContentUrl",
            "formatJs" to "ruleChapterFormat",
            "isVolume" to "ruleChapterIsVolume",
            "isVip" to "ruleChapterIsVip",
            "isPay" to "ruleChapterIsPay",
            "updateTime" to "ruleChapterUpdateTime",
            "nextTocUrl" to "ruleChapterUrlNext"
        ))
        putRuleObjectIfMissing(obj, "ruleContent", mapOf(
            "content" to "ruleBookContent",
            "subContent" to "ruleBookSubContent",
            "title" to "ruleBookContentTitle",
            "webJs" to "ruleBookContentWebJs",
            "sourceRegex" to "ruleBookContentSourceRegex",
            "replaceRegex" to "ruleBookContentReplace",
            "nextContentUrl" to "ruleContentUrlNext",
            "imageStyle" to "ruleBookContentImageStyle",
            "imageDecode" to "ruleBookContentImageDecode",
            "payAction" to "ruleBookContentPayAction",
            "callBackJs" to "ruleBookContentCallBackJs"
        ))
        copyRuleAlias(obj, "ruleBookInfo", "canReName", "ruleCanReName")
        copyRuleAlias(obj, "ruleBookInfo", "downloadUrls", "ruleBookDownloadUrls")
        copyRuleAlias(obj, "ruleToc", "formatJs", "ruleChapterFormatJs")
        copyRuleAlias(obj, "ruleToc", "isVolume", "ruleChapterVolume", "ruleIsVolume")
        copyRuleAlias(obj, "ruleToc", "isVip", "ruleChapterVip", "ruleIsVip")
        copyRuleAlias(obj, "ruleToc", "isPay", "ruleChapterPay", "ruleIsPay")
        copyRuleAlias(obj, "ruleContent", "subContent", "ruleSubContent")
        copyRuleAlias(obj, "ruleContent", "title", "ruleContentTitle")
        copyRuleAlias(obj, "ruleContent", "webJs", "ruleContentWebJs")
        copyRuleAlias(obj, "ruleContent", "sourceRegex", "ruleContentSourceRegex")
        copyRuleAlias(obj, "ruleContent", "nextContentUrl", "ruleBookContentUrlNext")
        copyRuleAlias(obj, "ruleContent", "imageStyle", "ruleContentImageStyle")
        copyRuleAlias(obj, "ruleContent", "imageDecode", "ruleImageDecode", "ruleContentImageDecode")
        copyRuleAlias(obj, "ruleContent", "payAction", "rulePayAction", "ruleContentPayAction")
        copyRuleAlias(obj, "ruleContent", "callBackJs", "ruleCallBackJs", "ruleContentCallBackJs")
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
        val existed = obj.has(targetKey) && obj.get(targetKey).isJsonObject
        val target = if (existed) obj.getAsJsonObject(targetKey) else GsonJsonObject()
        fields.forEach { (newKey, oldKey) ->
            if (target.hasNonBlank(newKey)) return@forEach
            val value = obj.getStringOrNull(oldKey) ?: return@forEach
            var migrated = migrateOldRule(value)
            if (targetKey == "ruleContent" && newKey == "content" && migrated.startsWith("$") && !migrated.startsWith("$.")) {
                migrated = migrated.substring(1)
            }
            if (migrated.isNotBlank()) target.addProperty(newKey, migrated)
        }
        if (!existed && target.entrySet().isNotEmpty()) obj.add(targetKey, target)
    }

    private fun copyRuleAlias(obj: GsonJsonObject, targetKey: String, newKey: String, vararg oldKeys: String) {
        val existed = obj.has(targetKey) && obj.get(targetKey).isJsonObject
        val target = if (existed) obj.getAsJsonObject(targetKey) else GsonJsonObject()
        if (target.hasNonBlank(newKey)) return
        val oldValue = oldKeys.firstNotNullOfOrNull { obj.getStringOrNull(it) } ?: return
        var migrated = migrateOldRule(oldValue)
        if (targetKey == "ruleContent" && newKey == "content" && migrated.startsWith("$") && !migrated.startsWith("$.")) {
            migrated = migrated.substring(1)
        }
        if (migrated.isNotBlank()) {
            target.addProperty(newKey, migrated)
            if (!existed) obj.add(targetKey, target)
        }
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
        val filtered = kinds.filter { kind ->
            !kind.title.startsWith("ERROR:", true) && kind.displayTitle().isNotBlank() &&
                (kind.url.isNotBlank() || kind.type.lowercase() in setOf("toggle", "select", "text", "button"))
        }
        seedExploreDefaults(source, filtered)
        return filtered
    }

    private fun evaluateExploreRule(source: BookSource, raw: String): Any? {
        val rule = raw.trim()
        return when {
            rule.startsWith("@js:", true) -> evalExploreJs(source, rule.substring(4))
            rule.startsWith("<js>", true) -> {
                val end = rule.lastIndexOf("<").takeIf { it > 4 } ?: rule.length
                evalExploreJs(source, rule.substring(4, end))
            }
            else -> raw
        }
    }

    private fun evalExploreJs(source: BookSource, js: String): Any? {
        openEvents.clear()
        return source.evalJS(js) { bindings ->
            bindings["infoMap"] = exploreInfoMap(source)
        }
    }

    private fun exploreInfoMap(source: BookSource): ExploreInfoMap {
        return exploreInfoMaps.getOrPut(source.bookSourceUrl) { ExploreInfoMap(source.bookSourceUrl) }
    }

    private fun seedExploreDefaults(source: BookSource, kinds: List<ExploreKind>) {
        val infoMap = exploreInfoMap(source)
        kinds.forEach { kind ->
            val key = kind.title.takeIf { it.isNotBlank() } ?: return@forEach
            val value = when (kind.type.lowercase()) {
                "toggle" -> SourceConfig.get(source.bookSourceUrl, key)
                    ?: kind.default
                    ?: kind.chars?.getOrNull(0)
                "select" -> SourceConfig.get(source.bookSourceUrl, key)
                    ?: kind.default
                    ?: kind.chars?.firstOrNull { !it.isNullOrBlank() }
                "text" -> SourceConfig.get(source.bookSourceUrl, key)
                    ?: kind.default
                else -> null
            }?.takeIf { it.isNotBlank() } ?: return@forEach
            source.put(key, value)
            if (!infoMap.containsKey(key)) infoMap[key] = value
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
                            exploreInfoMap(source)[kind.title] = newValue
                            kind.action?.takeIf { it.isNotBlank() }?.let { action ->
                                runCatching { source.evalJS(action) { bindings -> bindings["infoMap"] = exploreInfoMap(source) } }
                            }
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
                                exploreInfoMap(source)[kind.title] = selected
                                kind.action?.takeIf { it.isNotBlank() }?.let { action ->
                                    runCatching { source.evalJS(action) { bindings -> bindings["infoMap"] = exploreInfoMap(source) } }
                                }
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
            coverUri = parseImageUri(book.coverUrl, source.bookSourceUrl, source, book.toBook(), isCover = true)
        )
    }

    private fun parseImageUri(
        raw: String?,
        baseUrl: String,
        source: BookSource? = null,
        book: Book? = null,
        isCover: Boolean = false
    ): Uri {
        val value = raw?.trim().orEmpty()
        val clean = cleanImageUrl(value, baseUrl)
        val uri = source?.let { decodeImageUri(clean, it, book, isCover, extractImageHeaders(value)) } ?: clean
        return try { Uri.parse(uri) } catch (_: Exception) { Uri.EMPTY }
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

    private fun sanitizeImageUrl(
        raw: String,
        baseUrl: String,
        source: BookSource? = null,
        book: Book? = null,
        isCover: Boolean = false
    ): String {
        val clean = cleanImageUrl(raw, baseUrl)
        return source?.let { decodeImageUri(clean, it, book, isCover, extractImageHeaders(raw)) } ?: clean
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

    private data class ImageOption(
        val style: String? = null,
        val click: String? = null,
        val width: String? = null
    )

    private fun extractImageOption(raw: String): ImageOption {
        val obj = extractLegadoImageParams(raw)
            ?.let(::parseJsonLenient)
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return ImageOption()
        return ImageOption(
            style = obj.get("style")?.let(::jsonScalarString)?.takeIf { it.isNotBlank() },
            click = obj.get("click")?.let(::jsonScalarString)?.takeIf { it.isNotBlank() },
            width = obj.get("width")?.let(::jsonScalarString)?.takeIf { it.isNotBlank() }
        )
    }

    private fun extractImageHeaders(raw: String): Map<String, String> {
        val params = extractLegadoImageParams(raw) ?: return emptyMap()
        val headersElement = parseJsonLenient(params)
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("headers")
            ?: return emptyMap()
        return when {
            headersElement.isJsonObject -> headersElement.asJsonObject.entrySet().mapNotNull { (key, value) ->
                val headerValue = jsonScalarString(value)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                key to headerValue
            }.toMap()
            headersElement.isJsonPrimitive -> UrlOptionParser.parseHeaderLines(headersElement.asString)
            else -> emptyMap()
        }
    }

    private fun decodeImageUri(
        cleanUrl: String,
        source: BookSource,
        book: Book?,
        isCover: Boolean,
        extraHeaders: Map<String, String> = emptyMap()
    ): String? {
        if (cleanUrl.isBlank()) return null
        val ruleJs = if (isCover) source.coverDecodeJs else source.getContentRule().imageDecode
        if (ruleJs.isNullOrBlank() && extraHeaders.isEmpty()) return null
        val originalBytes = readImageBytes(cleanUrl, source, extraHeaders)?.takeIf { it.isNotEmpty() } ?: return null
        if (ruleJs.isNullOrBlank()) return imageDataUri(cleanUrl, originalBytes)
        val result = runCatching {
            source.evalJS(unwrapJsBlock(ruleJs)) { bindings ->
                bindings["book"] = book
                bindings["result"] = originalBytes
                bindings["src"] = cleanUrl
            }
        }.getOrNull()
        return decodedImageResultToUri(result, cleanUrl, originalBytes)
    }

    private fun readImageBytes(cleanUrl: String, source: BookSource, extraHeaders: Map<String, String>): ByteArray? {
        return when {
            cleanUrl.startsWith("data:", true) -> dataUriToBytes(cleanUrl)
            cleanUrl.startsWith("content://", true) -> runCatching {
                context.contentResolver.openInputStream(Uri.parse(cleanUrl))?.use { it.readBytes() }
            }.getOrNull()
            cleanUrl.startsWith("file://", true) -> runCatching {
                File(Uri.parse(cleanUrl).path.orEmpty()).readBytes()
            }.getOrNull()
            cleanUrl.startsWith("http://", true) || cleanUrl.startsWith("https://", true) -> runCatching {
                val headers = source.getHeaderMap(true).toMutableMap().apply { putAll(extraHeaders) }
                HttpClient.getByteArray(cleanUrl, headers = headers, timeoutMillis = source.respondTime)
            }.getOrNull()
            else -> null
        }
    }

    private fun decodedImageResultToUri(result: Any?, src: String, originalBytes: ByteArray): String? {
        val direct = (result as? CharSequence)?.toString()?.trim()?.takeIf { it.isNotBlank() }
        if (direct != null && (
                direct.startsWith("data:", true) ||
                    direct.startsWith("http://", true) ||
                    direct.startsWith("https://", true) ||
                    direct.startsWith("content://", true) ||
                    direct.startsWith("file://", true)
            )
        ) {
            return direct
        }
        val decodedBytes = result.toImageBytes() ?: direct?.decodeBase64OrNull()
        return decodedBytes
            ?.takeIf { it.isNotEmpty() }
            ?.let { bytes -> imageDataUri(src, bytes) }
            ?: imageDataUri(src, originalBytes)
    }

    private fun Any?.toImageBytes(): ByteArray? {
        return when (this) {
            null -> null
            is ByteArray -> this
            is Iterable<*> -> mapNumbersToBytes(toList())
            is Array<*> -> mapNumbersToBytes(toList())
            is IntArray -> map { it.toByte() }.toByteArray()
            is LongArray -> map { it.toByte() }.toByteArray()
            else -> null
        }
    }

    private fun mapNumbersToBytes(values: List<*>): ByteArray? {
        if (values.isEmpty()) return ByteArray(0)
        return runCatching {
            values.map { value ->
                when (value) {
                    is Number -> (value.toInt() and 0xff).toByte()
                    is Char -> value.code.toByte()
                    else -> return null
                }
            }.toByteArray()
        }.getOrNull()
    }

    private fun String.decodeBase64OrNull(): ByteArray? {
        val normalized = trim()
        if (normalized.isBlank()) return null
        val payload = if (normalized.startsWith("data:", true)) {
            normalized.substringAfter(",", missingDelimiterValue = "")
        } else {
            normalized
        }
        if (payload.isBlank()) return null
        return runCatching { Base64.getDecoder().decode(payload) }
            .getOrElse { runCatching { Base64.getMimeDecoder().decode(payload) }.getOrNull() }
    }

    private fun dataUriToBytes(uri: String): ByteArray? {
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val meta = uri.substring(0, comma)
        val payload = uri.substring(comma + 1)
        return if (meta.contains(";base64", ignoreCase = true)) {
            payload.decodeBase64OrNull()
        } else {
            runCatching { java.net.URLDecoder.decode(payload, "UTF-8").toByteArray() }.getOrNull()
        }
    }

    private fun imageDataUri(src: String, bytes: ByteArray): String {
        val mime = guessImageMime(src, bytes)
        return "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
    }

    private fun guessImageMime(src: String, bytes: ByteArray): String {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4e.toByte() &&
            bytes[3] == 0x47.toByte()
        ) return "image/png"
        if (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) return "image/jpeg"
        if (bytes.size >= 6 && bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII).startsWith("GIF")) return "image/gif"
        if (bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
        ) return "image/webp"
        val path = src.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".png") -> "image/png"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".avif") -> "image/avif"
            path.endsWith(".svg") -> "image/svg+xml"
            else -> "image/jpeg"
        }
    }

    private fun unwrapJsBlock(js: String): String {
        val trimmed = js.trim()
        return when {
            trimmed.startsWith("@js:", true) -> trimmed.substring(4)
            trimmed.startsWith("<js>", true) -> {
                val end = trimmed.lastIndexOf("<").takeIf { it > 4 } ?: trimmed.length
                trimmed.substring(4, end)
            }
            else -> js
        }
    }


    private fun createBookInformation(source: BookSource, book: SearchBook): BookInformation {
        return MutableBookInformation(
            id = makeId(source.bookSourceUrl, book.bookUrl),
            title = book.name,
            subtitle = "",
            coverUrl = parseImageUri(book.coverUrl, source.bookSourceUrl, source, book.toBook(), isCover = true),
            author = book.author,
            description = book.intro ?: "",
            tags = book.kind?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
            publishingHouse = "",
            wordCount = WordCount(book.wordCount?.filter { it.isDigit() }?.toIntOrNull() ?: 0),
            lastUpdated = LocalDateTime.MIN,
            isComplete = false
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
        runCatching { URL(source.bookSourceUrl).host }.getOrNull()?.let(CookieStore::clear)
        clearSourceRuntimeCache(source.bookSourceUrl)
    }

    fun clearSourceRuntimeCache(source: BookSource) {
        clearSourceRuntimeCache(source.bookSourceUrl)
    }

    fun clearSourceRuntimeCache(sourceUrl: String) {
        invalidateSourceRuntime(sourceUrl)
        val key = sourceUrl
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

    fun runCustomButton(source: BookSource, longClick: Boolean = false): Boolean {
        if (!source.customButton || !source.eventListener) return false
        val event = if (longClick) EVENT_LONG_CLICK_CUSTOM_BUTTON else EVENT_CLICK_CUSTOM_BUTTON
        val js = source.getContentRule().callBackJs?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            source.evalJS(unwrapJsBlock(js)) { bindings ->
                bindings["event"] = event
                bindings["result"] = null
                bindings["book"] = null
                bindings["chapter"] = null
                bindings["java"] = LoginJsBridge(null, source.bookSourceUrl, bookSource = source)
            }
        }.onFailure {
            android.util.Log.w("LegadoDS", "custom button failed: ${source.bookSourceName}", it)
        }.isSuccess
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
                coverUrl = parseImageUri(book.coverUrl, source.bookSourceUrl, source, book, isCover = true),
                author = book.author,
                description = book.intro ?: "",
                tags = book.kind?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
                publishingHouse = "",
                wordCount = WordCount(book.wordCount?.filter { it.isDigit() }?.toIntOrNull() ?: 0),
                lastUpdated = LocalDateTime.MIN,
                isComplete = false
            )
        } catch (_: Exception) { BookInformation.empty(id) }
    }

    override suspend fun getBookVolumes(id: String): BookVolumes = withContext(Dispatchers.IO) {
        val (sourceUrl, bookUrl) = parseId(id)
        val source = getSource(sourceUrl) ?: return@withContext BookVolumes.empty(id)
        try {
            val book = Book(bookUrl = bookUrl, origin = sourceUrl, originName = source.bookSourceName)
            val chapters = loadChapters(source, book, sourceUrl)
            BookVolumes(id, chaptersToVolumes(sourceUrl, chapters))
        } catch (_: Exception) { BookVolumes.empty(id) }
    }

    // ==================== 正文 ====================

    override suspend fun getChapterContent(chapterId: String, bookId: String): ChapterContent =
        withContext(Dispatchers.IO) {
            val (sourceUrl, chapterUrl) = parseId(chapterId)
            val source = getSource(sourceUrl) ?: return@withContext ChapterContent.empty(chapterId)
            try {
                currentImageHeader = source.getHeaderMap(true)
                val (_, bookUrl) = parseId(bookId)
                val book = Book(bookUrl = bookUrl, origin = sourceUrl, originName = source.bookSourceName)
                val chapters = loadChapters(source, book, sourceUrl).filter { !it.isVolume && it.url.isNotBlank() }
                val chapterIndex = chapters.indexOfFirst { it.url == chapterUrl }
                val bookChapter = chapters.getOrNull(chapterIndex)?.copy()
                    ?: BookChapter(bookUrl = bookUrl, url = chapterUrl, title = "", index = 0, baseUrl = book.tocUrl.ifBlank { book.bookUrl })
                book.durChapterIndex = bookChapter.index
                book.durChapterTitle = bookChapter.title
                val nextChapterUrl = if (chapterIndex >= 0) chapters.getOrNull(chapterIndex + 1)?.url else null
                val lastChapter = if (chapterIndex > 0) chapters.getOrNull(chapterIndex - 1)?.url?.let { makeId(sourceUrl, it) }.orEmpty() else ""
                val nextChapter = if (chapterIndex >= 0) nextChapterUrl?.let { makeId(sourceUrl, it) }.orEmpty() else ""
                runSourceCallback(source, EVENT_START_READ, book, bookChapter)
                val rawContent = WebBook.getContentAwait(source, book, bookChapter, nextChapterUrl)
                runSourceCallback(source, EVENT_END_READ, book, bookChapter, rawContent)
                val chTitle = bookChapter.title.ifBlank { "" }
                val contentJson = buildLnrContentJson(rawContent, chapterUrl, source, book, bookChapter)
                MutableChapterContent(chapterId, chTitle, contentJson, lastChapter, nextChapter)
            } catch (_: Exception) { ChapterContent.empty(chapterId) }
        }

    private fun loadChapters(
        source: BookSource,
        book: Book,
        sourceUrl: String
    ): List<BookChapter> {
        WebBook.getBookInfoAwait(source, book)
        val key = makeId(sourceUrl, book.bookUrl)
        return chapterListCache.getOrPut(key) { WebBook.getChapterListAwait(source, book) }
    }

    private fun chaptersToVolumes(sourceUrl: String, chapters: List<BookChapter>): List<Volume> {
        val volumes = mutableListOf<Volume>()
        var volumeIndex = 0
        var currentTitle = "目录"
        var currentChapters = mutableListOf<ChapterInformation>()

        fun flush() {
            if (currentChapters.isEmpty()) return
            volumes.add(Volume("::vol${volumeIndex++}", currentTitle, currentChapters))
            currentChapters = mutableListOf()
        }

        chapters.forEach { chapter ->
            if (chapter.isVolume) {
                flush()
                currentTitle = chapter.title.ifBlank { "目录" }
            } else if (chapter.url.isNotBlank()) {
                currentChapters.add(ChapterInformation(makeId(sourceUrl, chapter.url), chapter.title))
            }
        }
        flush()
        return volumes.ifEmpty {
            listOf(Volume("::vol0", "目录", chapters.filter { !it.isVolume }.map { ChapterInformation(makeId(sourceUrl, it.url), it.title) }))
        }
    }

    /**
     * 将 Legado 正文文本转换为 LNR 的 ChapterContent JSON 格式
     * 组件格式: {"id": "simple_text", "data": {"text": "..."}} 或 {"id": "image", "data": {"uri": "..."}}
     */
    private fun buildLnrContentJson(
        text: String,
        baseUrl: String,
        source: BookSource,
        book: Book,
        chapter: BookChapter? = null
    ): JsonObject {
        val components = buildJsonArray {
            addPayActionComponentIfNeeded(text, baseUrl, source, book, chapter)
            addCustomButtonComponentsIfNeeded(baseUrl, source, book, chapter)
            addMarkedContent(text, baseUrl, source, book, chapter)
        }
        return buildJsonObject { put("components", components) }
    }

    private fun JsonArrayBuilder.addPayActionComponentIfNeeded(
        text: String,
        baseUrl: String,
        source: BookSource,
        book: Book,
        chapter: BookChapter?
    ) {
        val payAction = source.getContentRule().payAction?.takeIf { it.isNotBlank() } ?: return
        val shouldShow = chapter?.let { it.isVip && !it.isPay } == true ||
            (chapter?.isPay == false && text.isBlank())
        if (!shouldShow) return
        add(buildJsonObject {
            put("id", LegadoActionComponentData.ID)
            put("data", buildJsonObject {
                put("label", "购买本章")
                put("action", payAction)
                put("sourceJson", gson.toJson(source))
                put("bookJson", gson.toJson(book))
                put("chapterJson", gson.toJson(chapter))
                put("title", chapter?.title.orEmpty())
                put("baseUrl", chapter?.url?.takeIf { it.isNotBlank() } ?: baseUrl)
            })
        })
    }

    private fun JsonArrayBuilder.addCustomButtonComponentsIfNeeded(
        baseUrl: String,
        source: BookSource,
        book: Book,
        chapter: BookChapter?
    ) {
        val callBackJs = source.getContentRule().callBackJs?.takeIf { it.isNotBlank() } ?: return
        if (!source.customButton || !source.eventListener) return
        addCallbackActionComponent(
            label = "自定义操作",
            event = EVENT_CLICK_CUSTOM_BUTTON,
            action = callBackJs,
            baseUrl = baseUrl,
            source = source,
            book = book,
            chapter = chapter
        )
        addCallbackActionComponent(
            label = "长按自定义操作",
            event = EVENT_LONG_CLICK_CUSTOM_BUTTON,
            action = callBackJs,
            baseUrl = baseUrl,
            source = source,
            book = book,
            chapter = chapter
        )
    }

    private fun JsonArrayBuilder.addCallbackActionComponent(
        label: String,
        event: String,
        action: String,
        baseUrl: String,
        source: BookSource,
        book: Book?,
        chapter: BookChapter?,
        result: String = ""
    ) {
        add(buildJsonObject {
            put("id", LegadoActionComponentData.ID)
            put("data", buildJsonObject {
                put("label", label)
                put("event", event)
                put("action", action)
                put("sourceJson", gson.toJson(source))
                put("bookJson", gson.toJson(book))
                put("chapterJson", gson.toJson(chapter))
                put("title", chapter?.title.orEmpty())
                put("baseUrl", chapter?.url?.takeIf { it.isNotBlank() } ?: baseUrl)
                put("result", result)
            })
        })
    }

    private fun JsonArrayBuilder.addMarkedContent(
        text: String,
        baseUrl: String,
        source: BookSource,
        book: Book,
        chapter: BookChapter?
    ) {
        val markerPattern = Regex("<(usehtml|useweb|md)>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)
        var lastEnd = 0
        for (match in markerPattern.findAll(text)) {
            addPlainOrHtmlContent(text.substring(lastEnd, match.range.first), baseUrl, source, book, chapter)
            val tag = match.groupValues[1].lowercase()
            val body = match.groupValues[2]
            when (tag) {
                "usehtml" -> addHtmlContent(body, baseUrl, source, book, chapter)
                "useweb" -> addLegadoHtmlComponent(body, baseUrl, source)
                "md" -> addMarkdownContent(body, baseUrl, source, book, chapter)
            }
            lastEnd = match.range.last + 1
        }
        if (lastEnd < text.length) addPlainOrHtmlContent(text.substring(lastEnd), baseUrl, source, book, chapter)
    }

    private fun JsonArrayBuilder.addPlainOrHtmlContent(
        text: String,
        baseUrl: String,
        source: BookSource,
        book: Book,
        chapter: BookChapter?
    ) {
        if (text.contains("<img", ignoreCase = true)) {
            addHtmlContent(text, baseUrl, source, book, chapter)
        } else {
            addTextComponents(text)
        }
    }

    private fun runSourceCallback(
        source: BookSource,
        event: String,
        book: Book? = null,
        chapter: BookChapter? = null,
        result: String? = null
    ) {
        if (!source.eventListener) return
        val js = source.getContentRule().callBackJs?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            source.evalJS(unwrapJsBlock(js)) { bindings ->
                bindings["event"] = event
                bindings["result"] = result
                bindings["book"] = book
                bindings["chapter"] = chapter
                bindings["java"] = LoginJsBridge(null, source.bookSourceUrl, bookSource = source)
            }
        }.onFailure {
            android.util.Log.w("LegadoDS", "callback $event failed: ${source.bookSourceName}", it)
        }
    }

    private fun JsonArrayBuilder.addHtmlContent(
        html: String,
        baseUrl: String,
        source: BookSource,
        book: Book,
        chapter: BookChapter?
    ) {
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
                    addImageComponent(extractImageUrl(element), baseUrl, source, book, chapter)
                    return
                }
                "button" -> {
                    flushText()
                    addButtonActionComponentIfNeeded(element, baseUrl, source, book, chapter)
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

    private fun JsonArrayBuilder.addButtonActionComponentIfNeeded(
        element: Element,
        baseUrl: String,
        source: BookSource,
        book: Book,
        chapter: BookChapter?
    ) {
        val text = element.text().trim()
        val onclickIndex = text.indexOf("@onclick:", ignoreCase = true)
        val attrAction = listOf("onclick", "data-onclick", "action")
            .firstNotNullOfOrNull { key -> element.attr(key).trim().takeIf { it.isNotBlank() } }
        val action = when {
            onclickIndex >= 0 -> text.substring(onclickIndex + "@onclick:".length).trim()
            !attrAction.isNullOrBlank() -> attrAction
            else -> ""
        }
        val label = if (onclickIndex >= 0) text.substring(0, onclickIndex).trim() else text
        if (action.isBlank()) {
            addTextComponents(label)
            return
        }
        addCallbackActionComponent(
            label = label.ifBlank { "执行" },
            event = "",
            action = action,
            baseUrl = baseUrl,
            source = source,
            book = book,
            chapter = chapter,
            result = ""
        )
    }

    private fun JsonArrayBuilder.addLegadoHtmlComponent(html: String, baseUrl: String, source: BookSource) {
        if (html.isBlank()) return
        add(buildJsonObject {
            put("id", LegadoHtmlComponentData.ID)
            put("data", buildJsonObject {
                put("html", html)
                put("baseUrl", baseUrl)
                put("sourceUrl", source.bookSourceUrl)
                put("sourceJson", gson.toJson(source))
                put("jsLib", resolveLegadoWebJsLib(source))
            })
        })
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

    private fun JsonArrayBuilder.addMarkdownContent(
        markdown: String,
        baseUrl: String,
        source: BookSource,
        book: Book,
        chapter: BookChapter?
    ) {
        val imgPattern = Regex("!\\[[^]]*]\\(([^)]+)\\)")
        var lastEnd = 0
        for (match in imgPattern.findAll(markdown)) {
            addTextComponents(cleanMarkdownText(markdown.substring(lastEnd, match.range.first)))
            addImageComponent(match.groupValues[1].trim(), baseUrl, source, book, chapter)
            lastEnd = match.range.last + 1
        }
        if (lastEnd < markdown.length) {
            addTextComponents(cleanMarkdownText(markdown.substring(lastEnd)))
        }
    }

    private fun JsonArrayBuilder.addImageComponent(
        rawUri: String,
        baseUrl: String,
        source: BookSource,
        book: Book,
        chapter: BookChapter?
    ) {
        val uri = sanitizeImageUrl(rawUri, baseUrl, source, book)
        if (uri.isBlank()) return
        val option = extractImageOption(rawUri)
        val style = option.style ?: source.getContentRule().imageStyle.orEmpty()
        val headers = source.getHeaderMap(true).toMutableMap().apply {
            putAll(extractImageHeaders(rawUri))
        }
        add(buildJsonObject {
            put("id", LegadoImageComponentData.ID)
            put("data", buildJsonObject {
                put("uri", uri)
                put("style", style)
                put("click", option.click.orEmpty())
                put("width", option.width.orEmpty())
                put("src", cleanImageUrl(rawUri, baseUrl))
                put("headers", buildJsonObject {
                    headers.forEach { (key, value) -> put(key, value) }
                })
                put("sourceJson", gson.toJson(source))
                put("bookJson", gson.toJson(book))
                put("chapterJson", gson.toJson(chapter))
            })
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
