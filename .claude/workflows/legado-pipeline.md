export const meta = {
  name: 'legado-book-source-pipeline',
  description: '四模型协作流水线：分析→编写→组装→审计，自动生成 Legado JSON 书源',
  phases: [
    { title: '分析', detail: '一年级分析目标站点HTML结构' },
    { title: '编写', detail: '二年级编写 Legado 规则' },
    { title: '组装', detail: '三年级组装完整 JSON 书源' },
    { title: '审计', detail: '四年级红队审计，不通过打回重修' },
  ],
}

// ── 流水线入口 ──────────────────────────────────────
const targetUrl = args?.url
const htmlSource = args?.html

if (!targetUrl || !htmlSource) {
  log('❌ 缺少参数。需要 url 和 html。')
  return { error: '缺少 url 或 html 参数' }
}

const MAX_ROUNDS = 3

// ── Phase 1: 一年级 分析 ────────────────────────────
phase('分析')
log(`[调度] 正在调用一年级分析 ${targetUrl} ...`)

const nodeReport = await agent(
  `你是逆向工程专家。分析以下网页 HTML 或 API JSON，定位关键节点。

目标 URL: ${targetUrl}

HTML/JSON 源码:
${htmlSource}

输出《节点定位报告》，包含：
- 书名节点 (XPath / CSS / JSONPath)
- 作者节点
- 章节列表节点 + 章节 URL 节点
- 正文内容节点
- 封面图节点
- 搜索接口（如有 API）

用 Markdown 表格汇总。只输出分析报告，严禁编写任何 Legado 规则代码或正则。
如果数据不足，明确指出需要补充什么。`,
  { label: '一年级·分析', phase: '分析' }
)

log('[调度] 一年级分析完成。')

// ── Phase 2: 二年级 编写 ────────────────────────────
phase('编写')
log('[调度] 正在调用二年级编写规则...')

let ruleCode = await agent(
  `你是 Legado 规则架构师。基于以下《节点定位报告》，编写 Legado JSON 书源规则。

《节点定位报告》:
${nodeReport}

输出一个 JSON 对象，包含（值为空留空字符串）：
- ruleSearch: { bookList, name, author, bookUrl, coverUrl, intro, kind, lastChapter }
- ruleBookInfo: { init, name, author, intro, kind, coverUrl, lastChapter, tocUrl }
- ruleToc: { chapterList, chapterName, chapterUrl, nextTocUrl, isVip, isPay, updateTime }
- ruleContent: { content, nextContentUrl, replaceRegex, imageStyle, imageDecode, webJs, sourceRegex, subContent, title }
- searchUrl: 搜索 URL 模板（含 {{key}} 和 {{page}}）
- exploreUrl, loginUrl, loginUi, loginCheckJs, jsLib（如需要）

铁律：
1. JS 必须兼容 Legado Rhino 引擎：禁用 let/const（用 var），禁用箭头函数，禁用模板字符串
2. @js: 前缀内可使用 java.ajax() / java.connect() / java.getCookie() 等
3. 正则用 Java 语法
4. 只输出规则 JSON 对象，不要包裹在 markdown 代码块中`,
  { label: '二年级·编写', phase: '编写' }
)

log('[调度] 二年级编写完成。')

// ── Phase 3+4: 组装 + 审计循环 ─────────────────────
let fullJson = ''
let auditFeedback = ''
let passed = false

for (let round = 1; round <= MAX_ROUNDS; round++) {
  // 三年级组装
  phase('组装')
  log(`[调度] 组装 JSON (迭代 ${round}/${MAX_ROUNDS})...`)

  fullJson = await agent(
    `你是实现工程师。将以下规则代码片段组装为完整的 Legado JSON 书源文件。

目标站点: ${targetUrl}

《规则代码片段》:
${ruleCode}

输出一个 JSON 数组，包含一个书源对象，必须有：
bookSourceUrl, bookSourceName, bookSourceGroup, bookSourceType,
enabled, enabledExplore, searchUrl, exploreUrl,
ruleSearch, ruleBookInfo, ruleToc, ruleContent,
header, jsLib, loginUrl, loginUi, loginCheckJs

铁律：
1. JSON 语法 100% 正确，无多余逗号、引号匹配
2. 字符串中双引号转义为 \\"，反斜杠转义为 \\\\
3. 严格保持规则内容不变，只做格式组装
4. 只输出纯 JSON，不要 markdown 代码块`,
    { label: '三年级·组装', phase: '组装' }
  )

  // 四年级审计
  phase('审计')
  log(`[调度] 审计 (迭代 ${round}/${MAX_ROUNDS})...`)

  auditFeedback = await agent(
    `你是红队审计员。对以下 JSON 书源进行全面审计。

待审计的 JSON 书源:
${fullJson}

检查清单：
1. JSON 语法（未转义字符、多余逗号）
2. 正则安全性（灾难性回溯如 (a+)+）
3. JS 安全性（死循环、未声明变量、ES6 语法）
4. URL 模板（{{key}} / {{page}} 占位符）
5. Cookie/Header（跨域泄露）
6. 编码兼容性（Unicode 转义）
7. 规则链完整性（上下游消费）

输出格式：
如果通过：第一行写 AUDIT_RESULT: PASS
如果失败：第一行写 AUDIT_RESULT: REJECT，然后列出漏洞清单

只输出审计报告，绝不修改代码。`,
    { label: '四年级·审计', phase: '审计' }
  )

  if (auditFeedback.includes('AUDIT_RESULT: PASS')) {
    passed = true
    log(`[调度] ✅ 审计通过！(第 ${round} 轮)`)
    break
  }

  log(`[调度] 审计循环 (${round}/${MAX_ROUNDS})：四年级发现漏洞，打回二年级重修...`)

  if (round >= MAX_ROUNDS) {
    log(`[调度] ⚠️ 已达最大重试次数，强制终止。`)
    break
  }

  // 打回二年级修订
  phase('编写')
  ruleCode = await agent(
    `你是 Legado 规则架构师。你上一次的规则未通过审计，请逐条修复。

《漏洞清单》:
${auditFeedback}

你上一次的规则代码（在此基础上修复）:
${ruleCode}

铁律同前：禁用 ES6、Java 正则、只输出规则 JSON 对象。`,
    { label: `二年级·重修(${round})`, phase: '编写' }
  )
}

// ── 输出结果 ────────────────────────────────────────
log(passed ? '✅ 流水线完成，书源已生成。' : '❌ 未通过审计，请人工介入。')

return {
  success: passed,
  finalJson: fullJson,
  auditFeedback: auditFeedback,
  iterations: Math.min(MAX_ROUNDS, MAX_ROUNDS),
  nodeReport: nodeReport,
}
