# LegadoForLNR

[LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader) 插件，支持直接导入 [lyc486 版 Legado](https://gitee.com/lyc486/legado) 的 JSON 书源文件，无需格式转换。

## 功能特性

### 规则解析
- **6种解析模式** — CSS选择器 / XPath / JsonPath / Regex / JS / WebJS
- **规则组合** — `&&` 合并、`||` 回退、`%%` 交错
- **替换规则** — `##正则##替换##` 标准格式
- **内嵌规则** — `@get:{key}` / `{{JS表达式}}` / `@put:{key=value}`
- **jsLib** — 支持直接JS代码和JSON URL列表两种格式
- **并发率** — `1000`（间隔ms）和 `20/60000`（次数/时间窗口）

### 书源功能
- **搜索** — 跨所有已导入书源搜索，支持多页
- **发现页** — 支持 `<useweb>` 自定义UI、toggle/select 筛选器、分页加载
- **书籍详情** — 书名、作者、封面、简介、标签、字数格式化
- **目录** — 分页目录、nextTocUrl 翻页、isVolume/isVip/isPay 字段
- **正文** — 多页正文、webJs、sourceRegex、subContent、replaceRegex
- **图片** — imageDecode 解密、coverDecodeJs 封面解密、imageHeader 防盗链
- **字体反爬** — queryTTF + replaceFont
- **HTML标签** — `<usehtml>` / `<md>` / `<useweb>` 正文标签解析

### 登录系统
- **loginUi 表单** — text / password / select / toggle / button 五种控件
- **动态UI** — `@js:` / `<js>` 前缀动态生成表单
- **按钮action** — URL跳转 / JS函数调用 / 验证码获取
- **loginUrl** — `<js></js>` 标签内定义 login()/getLimit()/changeU() 等函数
- **loginCheckJs** — 登录状态自动检查
- **WebView登录** — 双指缩放、Cookie同步、JS注入
- **AES加密** — 登录信息 AES/ECB/PKCS5Padding 加密存储，与 Legado 一致

### JavaScript 扩展
- **HTTP** — ajax / ajaxAll(并行) / connect / get / head / post
- **编码** — base64 / URI / hex / MD5 / SHA1 / SHA256 / AES
- **文件** — cacheFile / downloadFile / importScript / readFile
- **字体** — queryTTF / replaceFont
- **繁简转换** — t2s / s2t（ICU + 内置映射表）
- **WebView** — webView / webViewGetSource / webViewGetOverrideUrl
- **浏览器** — showBrowser / startBrowser / startBrowserAwait

## 构建

### 环境要求

- JDK 17+
- Android SDK (compileSdk 36)
- Gradle 9+（已包含 wrapper）

### 编译

```bash
.\gradlew :plugin:assembleDebug
```

产物路径：`plugin/build/outputs/apk/debug/plugin-debug.apk.lnrp`

### 安装

将 `.lnrp` 文件通过 LightNovelReader 的插件管理界面导入即可。

## 使用方法

1. 在 LightNovelReader 中安装本插件
2. 进入插件设置页
3. 点击"导入 Legado JSON 书源"导入书源文件
4. 启用插件和所需功能
5. 使用搜索和发现功能找书阅读

### 登录书源

对于需要登录的书源：
1. 导入书源后，在书源列表中找到目标书源
2. 点击"登录"按钮
3. 如果书源配置了 `loginUi`，会显示自定义表单；否则打开 WebView 登录页
4. 填写信息并提交，登录成功后自动保存凭据

## 架构

```
plugin/src/main/kotlin/
├── com/script/                          # Rhino ScriptEngine
│   ├── ScriptBindings.kt                #   extends NativeObject（与Legado一致）
│   └── rhino/
│       ├── RhinoScriptEngine.kt         #   JS执行引擎
│       └── RhinoClassShutter.kt         #   安全沙箱
│
├── io/legado/engine/                    # 引擎核心（零深度Android依赖）
│   ├── constant/
│   │   ├── AppConst.kt                  #   UA、日期格式常量
│   │   ├── AppPattern.kt                #   JS_PATTERN、titleNumPattern等
│   │   └── BookType.kt                  #   位标志类型常量（与Legado一致）
│   │
│   ├── data/
│   │   ├── BaseSource.kt                #   源接口（evalJS、getHeaderMap、AES加密）
│   │   ├── BookSource.kt                #   书源数据模型
│   │   ├── Book.kt / SearchBook.kt      #   书籍数据模型
│   │   ├── BookChapter.kt               #   章节数据模型
│   │   ├── RuleDataInterface.kt         #   变量存取接口（含bigVariable）
│   │   └── rule/                        #   7个规则数据类
│   │
│   ├── http/
│   │   ├── HttpClient.kt                #   OkHttp封装（GET/POST/代理/DNS）
│   │   ├── CookieStore.kt               #   Cookie管理
│   │   ├── ConcurrentRateLimiter.kt     #   并发限速（线程安全）
│   │   ├── StrResponse.kt               #   响应包装（与Legado接口兼容）
│   │   └── SSLHelper.kt                 #   SSL工具
│   │
│   ├── js/
│   │   ├── JsExtensions.kt              #   50+ JS可调用方法
│   │   └── SharedJsScope.kt             #   jsLib共享作用域（LRU缓存）
│   │
│   ├── rule/
│   │   ├── AnalyzeRule.kt               #   规则解析核心
│   │   ├── AnalyzeUrl.kt                #   URL解析与执行
│   │   ├── AnalyzeByJSoup/XPath/JSonPath/Regex.kt
│   │   ├── RuleAnalyzer.kt              #   规则切分器
│   │   └── UrlOptionParser.kt           #   URL选项解析
│   │
│   ├── shim/                            # 平台适配层
│   │   ├── AndroidContext.kt
│   │   ├── CacheManager.kt              #   ConcurrentHashMap
│   │   ├── AppConfig.kt / Debug.kt / GSON.kt / SourceConfig.kt
│   │
│   ├── webBook/
│   │   └── WebBook.kt                   #   搜索/发现/详情/目录/正文流程
│   │
│   └── webview/
│       ├── BackstageWebView.kt          #   后台WebView
│       └── BrowserDialogHelper.kt       #   浏览器Dialog（缩放+JS注入）
│
├── io/legado/plugin/                    # LNR插件层
│   ├── LegadoJsonPlugin.kt              #   插件入口
│   ├── LegadoJsonWebDataSource.kt       #   WebBookDataSource实现
│   ├── LoginActivity.kt                 #   登录Activity
│   ├── LoginJsBridge.kt                 #   登录JS桥接
│   └── LegadoSourceManagerContent.kt    #   书源管理Compose UI
│
└── io/legado/engine/rule/
    └── QueryTTF.java                    #   TTF字体解析
```

## 与 Legado 的兼容性

| 特性 | 状态 | 说明 |
|------|------|------|
| 6种规则解析 | ✓ | CSS/XPath/JsonPath/Regex/JS/WebJS |
| jsLib 注入 | ✓ | 直接JS代码 + JSON URL列表 |
| 并发率 | ✓ | 间隔ms + 次数/时间窗口 |
| CookieJar | ✓ | 启用/禁用控制 |
| loginUi 5种控件 | ✓ | text/password/button/toggle/select |
| `<useweb>` 发现页 | ✓ | 自定义HTML+JS |
| HTTP/SOCKS代理 | ✓ | 支持代理服务器 |
| js/bodyJs/dnsIp | ✓ | URL参数支持 |
| 字体反爬 | ✓ | queryTTF + replaceFont |
| subContent | ✓ | 副文规则 |
| eventListener | ✓ | 回调事件 |
| imageDecode | ✓ | 正文图片解密 |
| usehtml/md/useweb | ✓ | 正文标签 |
| AES登录加密 | ✓ | 与Legado一致 |
| BookType位标志 | ✓ | 与Legado一致 |
| htmlFormat | ✓ | Jsoup解析保留img |
| toNumChapter | ✓ | titleNumPattern |

## 版本历史

| 版本 | 说明 |
|------|------|
| v1.5.1 | BookType位标志精确对齐、toNumChapter返回格式 |
| v1.5.0 | 接口对齐+线程安全+内部质量全面提升（16个文件） |
| v1.4.4 | AnalyzeRule/AnalyzeUrl evalJS作用域链 |
| v1.4.3 | `<js></js>`标签解析改为正则 |
| v1.4.2 | ScriptBindings重写为NativeObject |
| v1.4.1 | 消除所有手动Rhino scope |
| v1.4.0 | 初始30项修复 |

## 许可证

**GNU 通用公共许可证 v3.0** (GPL-3.0)

包含来自 [Legado](https://gitee.com/lyc486/legado) 项目的代码，同样采用 GPL-3.0 许可。

## 致谢

- [Legado](https://gitee.com/lyc486/legado) by lyc486 — 核心解析引擎
- [LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader) — 宿主应用
- [LightNovelReader Plugin Template](https://github.com/dmzz-yyhyy/LightNovelReaderPlguin-Template) — 插件模板
