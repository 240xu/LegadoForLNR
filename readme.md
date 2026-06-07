# LegadoForLNR

> 本项目是基于 GPL-3.0 许可证的 lyc486 版 Legado 的衍生作品。

[LightNovelReader (LNR)](https://github.com/dmzz-yyhyy/LightNovelReader) 插件，支持直接导入 [lyc486 版 Legado](https://gitee.com/lyc486/legado) 的 JSON 书源文件，无需任何格式转换。

**唯一成功标准**：lyc486 版 Legado 书源的全部功能在 LNR 中完整可用。

---

## 功能清单

| 功能 | 状态 | 说明 |
|------|------|------|
| 搜索 | ✅ | 跨所有已导入书源搜索小说 |
| 发现页 | ✅ | 浏览配置了发现页的书源，支持分类切换 |
| 书籍详情 | ✅ | 书名、作者、封面、简介、标签 |
| 章节目录 | ✅ | 获取章节列表 |
| 正文解析 | ✅ | CSS / XPath / JSONPath / 正则 / JS |
| loginUi 登录 | ✅ | 完整支持 Legado 多功能面板 |
| 功能开关 | ✅ | 按书源隔离存储，实时生效 |
| 书源管理 | ✅ | 导入 / 长按删除 / 启用 / 禁用 |
| JavaScript | ✅ | Rhino 1.8.1 + 安全沙箱 |
| 段评规则 | ✅ | ReviewRule 数据层就绪 |

### loginUi 支持的控件

- `text` / `password` — 输入框
- `select` — 下拉选择（Spinner + options）
- `toggle` — 开关（TextView + chars 循环）
- `button` — 按钮（action URL / JS 执行）
- `@js:` / `<js>` 前缀动态 UI
- `upLoginData` / `reLoginView` JS 回调
- WebView 模式回退

---

## 技术架构

### 核心原则：SPI 接口隔离

引擎核心解析逻辑与 Android 平台**零耦合**。平台依赖通过 SPI 接口注入，shim 层提供向后兼容的默认实现。

```
┌──────────────────────────────────────────────────┐
│                  LNR 宿主进程                      │
│                                                   │
│  LegadoJsonPlugin                                 │
│    │                                              │
│    ├── Engine.init(                               │
│    │     context,           <- 宿主 Context        │
│    │     cacheProvider,     <- 可选：自定义缓存     │
│    │     sourceConfigProvider,                     │
│    │     appConfigProvider,                        │
│    │     logProvider                               │
│    │   )                                          │
│    │                                              │
│  ┌─┴────────────────────────────────┐             │
│  │  engine 核心（零 android.* 依赖）   │             │
│  │                                   │             │
│  │  rule/    AnalyzeRule, AnalyzeUrl │             │
│  │           AnalyzeByJSoup/XPath/  │             │
│  │           JSonPath/Regex         │             │
│  │  js/      JsExtensions (50+方法) │             │
│  │           RhinoScriptEngine      │             │
│  │  http/    HttpClient, CookieStore│             │
│  │  data/    Book, BookChapter,     │             │
│  │           BookSource, SearchBook │             │
│  │  webBook/ WebBook                │             │
│  │                                   │             │
│  │  只依赖 spi/ 接口                  │             │
│  └───────────────────────────────────┘             │
│                                                   │
│  ┌───────────────────────────────────┐             │
│  │  shim 层（平台适配 + 回退实现）      │             │
│  │                                   │             │
│  │  CacheManager  delegate? -> 自定义 │             │
│  │                  null -> SharedPrefs             │
│  │  SourceConfig  同上                │             │
│  │  AppConfig     同上                │             │
│  │  Debug         同上                │             │
│  └───────────────────────────────────┘             │
│                                                   │
│  ┌───────────────────────────────────┐             │
│  │  plugin 层（LNR 插件 API 对接）     │             │
│  │                                   │             │
│  │  LegadoJsonPlugin    插件入口      │             │
│  │  LoginActivity       loginUi 面板  │             │
│  │  LoginJsBridge       WebView 桥接  │             │
│  │  BackstageWebView    无头 WebView  │             │
│  └───────────────────────────────────┘             │
└──────────────────────────────────────────────────┘
```

### 目录结构

```
plugin/src/main/kotlin/
├── com/script/                      # Rhino ScriptEngine 包装
│   ├── rhino/
│   │   ├── RhinoScriptEngine.kt     #   JS 执行引擎
│   │   └── RhinoClassShutter.kt     #   安全沙箱（白名单）
│   └── ScriptBindings.kt
│
├── io/legado/
│   ├── engine/                      # 引擎模块
│   │   ├── spi/                     # SPI 接口
│   │   │   ├── CacheProvider.kt     #   缓存
│   │   │   ├── SourceConfigProvider #   源配置
│   │   │   ├── AppConfigProvider    #   应用配置
│   │   │   ├── LogProvider          #   日志
│   │   │   └── Engine.kt           #   统一初始化
│   │   │
│   │   ├── shim/                    # 平台适配层
│   │   │   ├── CacheManager.kt      #   SP 回退
│   │   │   ├── SourceConfig.kt
│   │   │   ├── AppConfig.kt
│   │   │   ├── Debug.kt
│   │   │   └── AndroidContext.kt
│   │   │
│   │   ├── rule/                    # 规则解析
│   │   │   ├── AnalyzeRule.kt       #   核心（scriptCache + 递归保护）
│   │   │   ├── AnalyzeUrl.kt
│   │   │   ├── RuleAnalyzer.kt
│   │   │   ├── AnalyzeByJSoup.kt    #   CSS 选择器
│   │   │   ├── AnalyzeByXPath.kt    #   XPath
│   │   │   ├── AnalyzeByJSonPath.kt #   JSONPath
│   │   │   └── AnalyzeByRegex.kt    #   正则
│   │   │
│   │   ├── js/                      # JavaScript
│   │   │   ├── JsExtensions.kt      #   50+ JS 可调用方法
│   │   │   └── SharedJsScope.kt
│   │   │
│   │   ├── http/                    # HTTP
│   │   │   ├── HttpClient.kt        #   OkHttp 封装
│   │   │   ├── CookieStore.kt
│   │   │   └── StrResponse.kt
│   │   │
│   │   ├── data/                    # 数据模型
│   │   │   ├── Book.kt              #   35 字段
│   │   │   ├── BookChapter.kt       #   19 字段
│   │   │   ├── SearchBook.kt        #   25 字段
│   │   │   ├── BookSource.kt
│   │   │   └── rule/                #   子规则模型
│   │   │
│   │   ├── webBook/WebBook.kt       # 业务流程编排
│   │   └── webview/BackstageWebView # 无头 WebView
│   │
│   └── plugin/                      # 插件层
│       ├── LegadoJsonPlugin.kt      #   入口
│       ├── LoginActivity.kt         #   loginUi
│       ├── LoginJsBridge.kt         #   JS 桥接（30+ 方法）
│       └── LegadoSourceManagerContent.kt
```

---

## SPI 接口详解

替换某个平台实现只需实现对应接口并注入。

### CacheProvider（14 个方法）

```kotlin
interface CacheProvider {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun putString(key: String, value: String, saveTimeSeconds: Int)
    fun delete(key: String)
    fun deleteByPrefix(prefix: String)
    fun contains(key: String): Boolean
    fun getLong(key: String): Long
    fun putLong(key: String, value: Long)
    fun getInt(key: String): Int
    fun putInt(key: String, value: Int)
    fun getFloat(key: String): Float
    fun putFloat(key: String, value: Float)
    fun getBoolean(key: String): Boolean
    fun putBoolean(key: String, value: Boolean)
}
```

### SourceConfigProvider（5 个方法）

```kotlin
interface SourceConfigProvider {
    fun get(sourceKey: String, configKey: String): String?
    fun put(sourceKey: String, configKey: String, value: String)
    fun delete(sourceKey: String, configKey: String)
    fun clear(sourceKey: String)
    fun getAll(sourceKey: String): Map<String, String>
}
```

### AppConfigProvider

```kotlin
interface AppConfigProvider {
    val userAgent: String
    val threadCount: Int
}
```

### LogProvider

```kotlin
interface LogProvider {
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun e(tag: String, msg: String, throwable: Throwable? = null)
    fun putDebug(msg: String, throwable: Throwable? = null)
}
```

### 注入示例

```kotlin
// 方式 1：全部自定义
Engine.init(context, myCache, mySourceConfig, myAppConfig, myLog)

// 方式 2：只替换部分
CacheManager.delegate = MyCacheProvider()
// 其余使用默认 SharedPreferences 回退
```

---

## 宿主 API 变动时怎么改

| 场景 | 改动范围 |
|------|----------|
| 缓存 API 变了 | 只改宿主侧 CacheProvider 实现（1 个文件） |
| SP -> DataStore | `CacheManager.delegate = DataStoreProvider(dataStore)` |
| 支持非 Android | 只改 shim 层 5 个文件，引擎核心零改动 |
| LNR 插件 API 升级 | 只改 plugin/ 目录，engine 不动 |

---

## 依赖版本

与 lyc486 版 Legado **严格一致**：

| 依赖 | 版本 |
|------|------|
| Rhino | 1.8.1 |
| Jsoup | 1.16.2 |
| OkHttp | 5.3.2 |
| json-path | 2.10.0 |
| Gson | 2.13.2 |
| JsoupXpath | 2.5.3 |
| commons-text | 1.13.1 |
| Kotlin | 2.0.21 |
| compileSdk | 36 |
| minSdk | 24 |

---

## 构建

### 环境

- JDK 17+
- Android SDK（compileSdk 36）

### 编译

```bash
.\gradlew :plugin:assembleDebug
```

产物：`plugin/build/outputs/apk/debug/plugin-debug.apk.lnrp`

### 安装

将 `.lnrp` 文件通过 LightNovelReader 插件管理界面导入。

---

## 使用

1. 安装插件
2. 进入插件设置页 -> 「导入 Legado JSON 书源」
3. 启用插件
4. 搜索 / 发现找书阅读

### 登录书源

1. 书源列表找到目标 -> 点击「登录」
2. 有 loginUi -> 显示自定义表单
3. 无 loginUi -> WebView 加载 loginUrl
4. 提交后自动保存凭证
5. 也支持被动唤起（引擎检测到需要登录时自动触发）

---

## 许可证

**GNU 通用公共许可证 v3.0**（GPL-3.0）

本项目是基于 GPL-3.0 许可证的 [lyc486 版 Legado](https://gitee.com/lyc486/legado) 的衍生作品。

## 致谢

- [Legado](https://gitee.com/lyc486/legado) by lyc486 -- 核心解析引擎
- [LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader) -- 宿主应用
- [LightNovelReader Plugin Template](https://github.com/dmzz-yyhyy/LightNovelReaderPlguin-Template)

## 相关资源

- [lyc486 版 Legado 源码](https://gitee.com/lyc486/legado)
- [LNR API 文档](https://api-doc.lnr.nariko.org/)
- [LNR 开发指南](https://lnr.nariko.org/plugin-dev/)
