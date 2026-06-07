================================================================================
    LegadoForLNR 源码级审查报告 (Binary-Equivalent Review)
    日期: 2026-06-07
================================================================================

一、发现列表 (Findings)
================================================================================

[P0] 关键缺陷 — 会导致运行时功能缺失或规则解析失败
----------------------------------------------------------------------

F1. [P0] AnalyzeRule.SourceRule 缺少 ruleParam/ruleType/splitRegex/isRule 机制
    文件: LegadoForLNR/.../rule/AnalyzeRule.kt:241-302 (SourceRule class)
    对比: legado/.../analyzeRule/AnalyzeRule.kt:581-800 (SourceRule class)
    
    问题: lyc486 的 SourceRule 在 init 阶段通过 evalPattern/regexPattern 拆分
    规则字符串为 ruleParam[] + ruleType[] 列表，支持：
    - $1/$2 等正则捕获组引用 (splitRegex)
    - @get:{key} 变量替换 (getRuleType = -2)
    - {{js}} 嵌入式JS执行后嵌套规则解析 (isRule 检测, getOrCreateSingleSourceRule)
    
    LegadoForLNR 的 SourceRule 用简化版 replaceGetAndJs() 字符串扫描实现，
    缺少：
    (a) 正则捕获组引用 ($1, $2 等) 完全缺失
    (b) {{js}} 内嵌结果如果本身是规则表达式(@xxx, //, $.xxx)，不会被二次解析
    (c) 缺少 getOrCreateSingleSourceRule 的缓存复用
    
    影响: 使用 $1$2 捕获组拼接的书源规则（如某些 lyc486 源）会解析失败。
    使用 {{js}} 返回规则表达式的源也会行为不一致。

F2. [P0] AnalyzeRule 缺少 compileRegexCache / getOrPutLimit 缓存机制
    文件: LegadoForLNR/.../rule/AnalyzeRule.kt:43 (regexCache)
    对比: legado/.../analyzeRule/AnalyzeRule.kt:508-515
    
    问题: lyc486 使用 getOrPutLimit(regex, 16) 缓存正则编译结果，最大16条，
    LegadoForLNR 使用普通 hashMapOf<String, Regex?>() 无容量限制。
    另外 lyc486 的 stringRuleCache 和 scriptCache 也使用 getOrPutLimit。
    LegadoForLNR 的 compileScriptCache 使用 getOrPut 无限制。
    
    影响: 长期运行场景下内存可能膨胀。功能上不阻断但属于性能差异。

F3. [P0] BookList.analyzeBookList 缺少 bookUrlPattern 详情页回退逻辑
    文件: LegadoForLNR/.../webBook/WebBook.kt:252-296 (BookList.analyzeBookList)
    对比: legado/.../webBook/BookList.kt:38-170
    
    问题: lyc486 BookList 在搜索模式下检查 bookUrlPattern：
    如果 URL 匹配 bookUrlPattern 正则，直接将响应当作详情页解析 (getInfoItem)。
    同样在 collections 为空时也会回退到详情页解析。
    LegadoForLNR 完全缺失此逻辑。
    
    影响: 对于配置了 bookUrlPattern 的书源（搜索结果直接跳转详情页），搜索
    将返回空列表。

F4. [P0] BookList.analyzeBookList 缺少 isRedirect 传递和 URL 修正
    文件: LegadoForLNR/.../webBook/WebBook.kt:252-296
    对比: legado/.../webBook/BookList.kt:38, 161-165
    
    问题: lyc486 在 analyzeBookList 中接收 isRedirect 参数，
    并在 getInfoItem 中当 isRedirect=true 时使用 baseUrl 作为 bookUrl。
    LegadoForLNR 的 analyzeBookList 不接收 isRedirect，executeWithLoginCheck
    也不传递重定向信息。
    
    影响: 重定向后的书源，搜索结果中的 bookUrl 可能是错误的原始 URL 而非最终 URL。

F5. [P0] AnalyzeRule.SourceRule XPath 检测不一致："/" vs "//"
    文件: LegadoForLNR/.../rule/AnalyzeRule.kt:255 (SourceRule init)
    对比: legado/.../analyzeRule/AnalyzeRule.kt:620
    
    问题: lyc486 使用 ruleStr.startsWith("/") 检测 XPath（单斜杠即可），
    LegadoForLNR 使用 r.startsWith("//")（需要双斜杠）。
    
    影响: 使用 "/div" 等单斜杠 XPath 表达式的规则会被当作默认模式而非 XPath 解析。

F6. [P0] isTrue() 扩展函数缺失
    文件: LegadoForLNR/.../webBook/WebBook.kt:341-396 (BookChapterList)
    对比: legado/.../utils/StringExtensions.kt:76
    
    问题: lyc486 使用 String?.isTrue() 扩展函数检查 "true"/"1"/非"false" 等。
    LegadoForLNR 的 BookChapterList 使用 == "true" || == "1" 简化比较。
    
    影响: 当书源规则返回 "TRUE"/"True"/"yes"/"on" 等值时，isVolume/isVip/isPay
    判断会失败。

----------------------------------------------------------------------
[P1] 重要缺陷 — 影响部分书源功能或行为差异
----------------------------------------------------------------------

F7. [P1] WebBook.getBookInfoAwait 未携带 RuleData
    文件: LegadoForLNR/.../webBook/WebBook.kt:111-119
    对比: legado/.../webBook/WebBook.kt:192-256
    
    问题: lyc486 创建 AnalyzeUrl 时传入 ruleData=book，使 URL 模板中的 
    {{key}}/@get:{key} 能读取 book 上的变量。
    LegadoForLNR 的 getBookInfoAwait 没有传入 ruleData。
    
    影响: 详情页 URL 中引用 book 变量的规则会解析为空。

F8. [P1] WebBook.getChapterListAwait 缺少 runPerJs/isFromBookInfo 参数
    文件: LegadoForLNR/.../webBook/WebBook.kt:121
    对比: legado/.../webBook/WebBook.kt:284
    
    问题: lyc486 的 getChapterListAwait 有 runPerJs 和 isFromBookInfo 参数。
    当 runPerJs=true 时才调用 runPreUpdateJs。
    LegadoForLNR 无条件调用 runPreUpdateJs。
    
    影响: 每次获取目录都会执行 preUpdateJs，可能导致不必要的请求。
    但功能上不阻断（preUpdateJs 通常为空时无副作用）。

F9. [P1] WebBook.exploreBookAwait 不支持 exploreInfoMap 传入
    文件: LegadoForLNR/.../webBook/WebBook.kt:101-109
    对比: legado/.../webBook/WebBook.kt:124-178
    
    问题: lyc486 在 exploreBookAwait 中接受并使用 exploreInfoMap，
    用于存储发现页的中间状态（如筛选参数）。LegadoForLNR 缺失。
    
    影响: 发现页筛选参数在页面间无法持久化。

F10. [P1] CookieStore.getCookie 格式化 bug
     文件: LegadoForLNR/.../http/CookieStore.kt:60
     对比: N/A (自实现)
     
     问题: getCookie 方法中 joinToString 的模板是 "=" 而非 
     "${it.key}=${it.value}"，导致返回 "==" 而非 "name=value" 格式。
     
     代码: return map.entries.joinToString("; ") { "=" }
     应为: return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
     
     影响: 所有通过 CookieStore.getCookie() 获取的 cookie 字符串都是 "=="，
     导致请求不携带正确的 cookie。**这是严重的功能 bug**。

F11. [P1] AnalyzeRule.evalJS 在出错时静默返回 null 而非抛出异常
     文件: LegadoForLNR/.../rule/AnalyzeRule.kt:304
     对比: legado/.../analyzeRule/AnalyzeRule.kt:828
     
     问题: lyc486 的 evalJS 没有 try-catch 包裹，异常会向上抛出。
     LegadoForLNR 包裹了 try-catch 返回 null。
     
     影响: 需要 JS 异常来中断流程的书源逻辑会被吞掉，可能导致后续
     规则错误地继续执行。

F12. [P1] AnalyzeRule.evalJS 缺少 unwrapJs 处理
     文件: LegadoForLNR/.../rule/AnalyzeRule.kt:280 (evalJS 调用 unwrapJs)
     对比: lyc486 不调用 unwrapJs 在 evalJS 中，而是在 splitSourceRule 中处理
     
     问题: LegadoForLNR 在 evalJS 入口调用 unwrapJs 剥离 @js:/<js></js> 
     前后缀。lyc486 在 SourceRule.init 就已经处理了。
     这会导致双重剥离：如果规则是 "@js:xxx"，splitSourceRule 先识别为 Js 模式，
     然后 evalJS 又调用 unwrapJs 剥离 @js: 前缀——此时实际上已经是 "xxx" 了，
     不过 unwrapJs 对不匹配的字符串会原样返回，所以实际上影响较小。
     
     影响: 低风险，但可能导致某些边界情况行为不一致。

F13. [P1] BookChapterList 缺少 upChapterInfo 持久化逻辑
     文件: LegadoForLNR/.../webBook/WebBook.kt:341-396
     对比: legado/.../webBook/BookChapterList.kt:287-305
     
     问题: lyc486 有 upChapterInfo 方法，从数据库加载已有章节信息（wordCount、
     variable、imgUrl），合并到新解析的章节列表中。LegadoForLNR 缺失。
     
     影响: 目录更新后会丢失之前保存的章节变量和字数信息。

F14. [P1] preciseSearchAwait 使用 postSearch 过滤而非 filter 参数
     文件: LegadoForLNR/.../webBook/WebBook.kt:88-98
     对比: legado/.../webBook/WebBook.kt:480-502
     
     问题: lyc486 的 preciseSearchAwait 通过 filter 和 shouldBreak 参数
     在搜索过程中进行过滤和提前终止。
     LegadoForLNR 先搜索全部结果再 firstOrNull 过滤。
     
     影响: 对于搜索结果很多的源，会多请求不必要的页面。

F15. [P1] WebBook.executeWithLoginCheck 缺少 StrResponse 包装
     文件: LegadoForLNR/.../webBook/WebBook.kt:26-56
     对比: legado/.../webBook/WebBook.kt:65-108
     
     问题: lyc486 的 loginCheckJs 接收 StrResponse 对象（包含 raw Response）。
     LegadoForLNR 创建了一个简化的 StrResponse(HttpResponse(...))，
     丢失了原始 HTTP Response 的 headers 等信息。
     
     影响: loginCheckJs 脚本如果访问 response.header() 等会返回空。

----------------------------------------------------------------------
[P2] 低优先级 — 行为差异但不影响核心功能
----------------------------------------------------------------------

F16. [P2] HttpClient User-Agent 硬编码且未使用 AppConfig.userAgent
     文件: LegadoForLNR/.../http/HttpClient.kt:31
     
     问题: HttpClient 硬编码了 User-Agent，未使用 AppConfig.userAgent。
     
     影响: 宿主无法自定义 User-Agent。

F17. [P2] BookChapterList 的 wordCountRegex 与 lyc486 不一致
     文件: LegadoForLNR/.../webBook/WebBook.kt:341
     LegadoForLNR: (?:^|[字数【】，、，]|\s+)([0-9万千百十.]{1,6}字)
     lyc486 AppPattern: (?:^|字数[：:、]|\s+)([0-9万千百\.]{1,6}字)
     
     问题: LegadoForLNR 的正则包含了更多字符（【】，，）但缺少冒号（：:），
     并且包含了"十"。
     
     影响: "字数：100万字" 格式可能无法匹配。

F18. [P2] BookList 缺少搜索时的 type/originOrder 设置
     文件: LegadoForLNR/.../webBook/WebBook.kt:252-296
     
     问题: lyc486 在 getSearchItem 中设置 searchBook.type 和 originOrder，
     LegadoForLNR 只设置了 origin 和 originName。
     
     影响: 搜索结果排序和类型筛选可能不正确。

F19. [P2] BackstageWebView 缺少完整的 WebView 登录流程集成
     文件: LegadoForLNR/.../webview/BackstageWebView.kt
     
     问题: 虽然 LoginJsBridge 中实现了完整的 WebView 登录流程，
     但 BackstageWebView 的 cookie 同步和 WebView 生命周期管理
     与 lyc486 有差异。整体可用但边界情况（如多次重定向）可能不同。

F20. [P2] SourceRule.getParamSize() 始终返回 1
     文件: LegadoForLNR/.../rule/AnalyzeRule.kt:273
     
     问题: lyc486 返回 ruleParam.size，LegadoForLNR 硬编码返回 1。
     
     影响: getParamSize 目前在 LegadoForLNR 内部未使用，但未来扩展可能受影响。

F21. [P2] 缺少 checkRedirect 检测和日志
     文件: LegadoForLNR/.../webBook/WebBook.kt (所有 await 方法)
     
     问题: lyc486 在搜索/详情/目录请求后检查重定向并记录日志。
     LegadoForLNR 不检查重定向日志。
     
     影响: 调试困难，不影响功能。

F22. [P2] 缺少 AppConfig.tocCountWords 特性标志
     文件: LegadoForLNR/.../webBook/WebBook.kt:341 (BookChapterList)
     
     问题: lyc486 在解析目录时根据 tocCountWords 标志决定是否提取字数。
     LegadoForLNR 始终提取字数。
     
     影响: 默认行为一致（lyc486 默认开启），但用户无法关闭。

================================================================================
二、代码补丁 (Code Patches)
================================================================================

以下补丁按优先级排列，仅针对可原地修复的缺陷。
