================================================================================
    LegadoForLNR 源码级审查报告 — 补丁与验证
    日期: 2026-06-07 (续)
================================================================================

三、已应用补丁清单
================================================================================

补丁 1 [P0→已修复]: CookieStore.getCookie() 格式化 bug
  文件: plugin/.../http/CookieStore.kt:39
  修改: joinToString { "=" } → joinToString { "${it.key}=${it.value}" }
  状态: ✓ 已修复

补丁 2 [P0→已修复]: 添加 isTrue() 扩展函数
  文件: plugin/.../constant/StringExtensions.kt (新建)
  说明: 对齐 lyc486 String?.isTrue() 语义
  状态: ✓ 已创建

补丁 3 [P0→已修复]: BookChapterList 使用 isTrue() 替代硬编码比较
  文件: plugin/.../webBook/WebBook.kt:372-387
  修改: == "true" || == "1" → .isTrue()
  状态: ✓ 已修复

补丁 4 [P0→已修复]: SourceRule XPath 检测从 "//" 改为 "/"
  文件: plugin/.../rule/AnalyzeRule.kt:390
  修改: r.startsWith("//") → r.startsWith("/")
  状态: ✓ 已修复

补丁 5 [P0→已修复]: BookList 添加 bookUrlPattern 详情页回退
  文件: plugin/.../webBook/WebBook.kt (BookList object)
  新增: bookUrlPattern 匹配检测、getInfoItem 方法、isRedirect 参数、
       type/originOrder 设置
  状态: ✓ 已修复

补丁 6 [P1→已修复]: searchBookAwait 传入 ruleData
  文件: plugin/.../webBook/WebBook.kt:73
  修改: AnalyzeUrl 构造增加 ruleData 参数
  状态: ✓ 已修复

补丁 7 [P1→已修复]: evalJS 不再静默吞掉异常
  文件: plugin/.../rule/AnalyzeRule.kt:311
  修改: 移除 try-catch，异常向上传播
  状态: ✓ 已修复

补丁 8 [P1→已修复]: compileScriptCache 使用 getOrPutLimit(16)
  文件: plugin/.../rule/AnalyzeRule.kt:30-37, 315
  新增: getOrPutLimit 扩展函数，替换 getOrPut
  状态: ✓ 已修复

================================================================================

四、未修复的剩余风险 (Remaining Risks)
================================================================================

R1. [P0-残留] SourceRule 缺少 ruleParam/ruleType/splitRegex 完整机制
    风险: 使用 $1/$2 正则捕获组引用的书源规则将解析失败。
    建议: 后续版本需要将 SourceRule 重写为 lyc486 的 ruleParam/ruleType
    拆分模式。此为大改动，需单独排期。

R2. [P1-残留] getBookInfoAwait 的 AnalyzeUrl 未传 ruleData=book
    风险: 详情页 URL 中引用 book 变量的模板解析为空。
    注意: 代码中 getBookInfoAwait 的 AnalyzeUrl 已有 ruleData=book，
    此项实际已通过现有代码覆盖，无需额外修复。

R3. [P1-残留] getChapterListAwait 无条件调用 runPreUpdateJs
    风险: 每次获取目录都执行 preUpdateJs。
    建议: 后续添加 runPerJs 参数控制。

R4. [P1-残留] WebBook.exploreBookAwait 不支持 exploreInfoMap
    风险: 发现页筛选参数不持久化。
    建议: 后续在 LegadoJsonWebDataSource 的 ExploreInfoMap 中集成。

R5. [P1-残留] BookChapterList 缺少 upChapterInfo 持久化
    风险: 目录更新后丢失已保存的章节变量。
    建议: 后续在 LNR 无数据库场景下用 CacheManager 实现。

R6. [P1-残留] executeWithLoginCheck 的 StrResponse 不含原始 headers
    风险: loginCheckJs 访问 response.header() 返回空。
    建议: 后续将原始 OkHttp Response headers 传递到 StrResponse。

R7. [P2-残留] SourceRule.getParamSize() 硬编码返回 1
    风险: 低。当前未使用。
    建议: 后续实现 ruleParam 后同步修正。

R8. [P2-残留] HttpClient UA 硬编码
    风险: 低。宿主无法自定义 UA。
    建议: 使用 AppConfig.userAgent 替代。

R9. [P2-残留] wordCountRegex 与 lyc486 有微小差异
    风险: 极低。"字数：100万" 格式可能不匹配。
    建议: 统一为 lyc486 AppPattern.wordCountRegex。

================================================================================

五、验证检查清单 (Verification Checklist)
================================================================================

[✓] CookieStore.getCookie() 返回 "key=value" 格式
[✓] isTrue() 函数存在于 engine/constant/StringExtensions.kt
[✓] BookChapterList isVolume/isVip/isPay 使用 isTrue()
[✓] SourceRule XPath 检测支持单斜杠 "/"
[✓] BookList 支持 bookUrlPattern 详情页回退
[✓] BookList.searchBookAwait 传递 ruleData
[✓] evalJS 异常向上传播
[✓] compileScriptCache 使用容量限制缓存
[✓] 变量序列化 (variableMap lazy + putVariable 同步) 完整
[✓] BookChapter.variableMap lazy 反序列化完整
[✓] BaseSource loginInfo/cookie/headerMap 方法完整
[✓] LoginJsBridge WebView 登录流程完整
[✓] DecompressInterceptor gzip/deflate 解压完整
[✓] CookieStore.setCookie/replaceCookie 功能正确
[✓] SharedJsScope jsLib 注入和缓存完整
[✓] JsExtensions ajax/connect/crypto/queryTTF 方法完整
[✓] AnalyzeUrl getAbsoluteURL 静态方法完整
[✓] BackstageWebView WebView 执行器完整
[✓] loginUi 解析和 LoginActivity 完整
[✓] Engine SPI 注入机制完整

[!] SourceRule ruleParam/ruleType 机制 (需后续大版本)
[!] upChapterInfo 持久化 (需后续版本)
[!] exploreInfoMap 集成 (需后续版本)

================================================================================

六、中文总结
================================================================================

本次审查对 LegadoForLNR 项目进行了源码级对等检查，对比 lyc486 Legado 引擎
层与 LNR 插件合约，覆盖了数据模型、WebBook 流程、AnalyzeRule/AnalyzeUrl/
JsExtensions、BaseSource 登录信息、HttpClient 拦截器、Cookie 持久化、
缓存抽象、日志等全部关键路径。

主要发现:

1. CookieStore.getCookie() 存在严重格式化 bug（返回 "==" 而非 "key=value"），
   导致所有 cookie 请求失效——已修复。

2. BookChapterList 的 isVolume/isVip/isPay 判断使用简化比较（== "true"），
   不符合 lyc486 的 isTrue() 语义——已修复。

3. BookList 缺少 bookUrlPattern 详情页回退、isRedirect 传递、getInfoItem
   回退逻辑，导致部分书源搜索返回空——已修复。

4. AnalyzeRule.SourceRule 缺少 lyc486 的 ruleParam/ruleType/splitRegex 
   完整正则捕获组解析机制，这是最大的残留差距。使用 $1/$2 捕获组引用的
   少数书源规则会解析失败。此改动规模较大，建议作为下一版本重点。

5. 其他已修复问题包括: XPath 检测不一致、evalJS 异常静默吞掉、
   缓存无容量限制、searchBookAwait 未传 ruleData 等。

总体评估: LegadoForLNR 的核心架构与 lyc486 高度一致，数据模型序列化、
SPI 抽象、HttpClient 拦截链、登录流程等均已正确实现。本次修复消除了
影响大部分书源正常工作的关键缺陷。剩余风险主要集中在 SourceRule 
正则捕获组机制和少量边界行为差异，不影响主流书源的正常使用。
