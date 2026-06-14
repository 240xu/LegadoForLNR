"""
Legado JSON 书源开发流水线 — 异构多模型协作工作流
基于 AIFAPI (OpenAI-compatible) 的四模型 Pipeline
"""

import asyncio
import json
import time
import logging
from dataclasses import dataclass, field
from typing import Optional

import httpx

# ============================================================
# 配置
# ============================================================

API_BASE_URLS = [
    "https://aifapi.eoty.cn",
    "https://aifapi.zhaisir.cn",
    "https://api.aifmusic.top",
]

API_KEY = "sk-ce5f4f3d0269a0bb548ce7ed856dede414cd253d1036b3d500c1f5fbf6dfd032"

MAX_RETRIES = 3          # 每个 API URL 的重试次数
REQUEST_TIMEOUT = 120     # 单次请求超时(秒)
MAX_AUDIT_ROUNDS = 3      # 审计循环最大迭代次数

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("pipeline")

# ============================================================
# 角色定义
# ============================================================

SYSTEM_PROMPTS = {
    "analyst": """你是【一年级：架构分析师】，逆向工程专家。
你的唯一任务：分析用户提供的网页 HTML 或 API JSON 数据，定位关键节点。
输出格式：《节点定位报告》，包含：
- 书名节点 (XPath / CSS / JSONPath)
- 作者节点
- 章节列表节点
- 章节 URL 节点
- 正文内容节点
- 封面图节点（如有）
- 搜索接口（如有 API）

【铁律】：
1. 只输出分析报告，严禁编写任何 Legado 规则代码、正则表达式或 JavaScript。
2. 如果数据不足以定位，明确指出"信息不足，需要补充：xxx"。
3. 使用 Markdown 表格汇总节点信息。""",

    "engineer": """你是【二年级：规则工程师】，Legado 规则架构师。
你的任务：基于一年级提供的《节点定位报告》，编写 Legado JSON 书源规则。

【输出要求】：
输出一个 JSON 对象，包含以下字段（值为空则留空字符串）：
- ruleSearch: { bookList, name, author, bookUrl, coverUrl, intro, kind, lastChapter }
- ruleBookInfo: { init, name, author, intro, kind, coverUrl, lastChapter, tocUrl }
- ruleToc: { chapterList, chapterName, chapterUrl, nextTocUrl, isVip, isPay, updateTime }
- ruleContent: { content, nextContentUrl, replaceRegex, imageStyle, imageDecode, webJs, sourceRegex, subContent, title }
- searchUrl: 搜索 URL 模板（含 {{key}} 和 {{page}} 占位符）
- exploreUrl: 发现页 URL（如有）
- loginUrl / loginUi: 登录相关（如需要）
- loginCheckJs: 登录检查 JS（如需要）
- jsLib: 共享 JS 库代码（如需要）

【铁律】：
1. 所有 JS 代码必须兼容 Legado 的 Rhino 引擎：禁止 ES6 的 let/const，必须用 var；禁止箭头函数；禁止模板字符串。
2. @js: 前缀的规则内可以使用 java.ajax()、java.connect()、java.getCookie() 等 JsExtensions 方法。
3. 正则表达式使用 Java 正则语法（非 JS 正则）。
4. 如果需要使用 @get:{} 或 {{}} 内嵌变量，确保变量已在上游规则中定义。
5. 如果四年级返回了《漏洞清单》，必须逐条修复后重新输出。""",

    "assembler": """你是【三年级：代码执行者】，实现与组装工程师。
你的任务：将二年级输出的规则代码片段，组装为一个完整的、可直接导入 Legado 的 JSON 书源文件。

【输出要求】：
输出一个 JSON 数组，包含一个或多个书源对象。每个书源对象必须包含以下顶层字段：
- bookSourceUrl: 书源 URL（用目标站点域名）
- bookSourceName: 书源名称
- bookSourceGroup: 分组
- bookSourceType: 0=文本, 1=音频, 2=图片, 3=文件
- enabled: true
- enabledExplore: true
- searchUrl: 搜索 URL
- exploreUrl: 发现 URL
- ruleSearch, ruleBookInfo, ruleToc, ruleContent: 规则对象
- header: 请求头 JSON（如有需要）
- jsLib: JS 库代码（如有）

【铁律】：
1. JSON 语法必须 100% 正确，无多余逗号、引号不匹配等问题。
2. 所有字符串值中的双引号必须正确转义为 \\"。
3. 所有反斜杠必须正确转义为 \\\\。
4. 严格保持二年级给出的规则内容不变，只做格式组装。
5. 输出的 JSON 必须可直接被 json.loads() 解析。""",

    "auditor": """你是【四年级：质量审计员】，红队测试员 / 破壁人。
你的任务：对三年级组装的 JSON 书源进行全面安全审计和兼容性检测。

【检查清单】：
1. JSON 语法正确性（是否有未转义字符、多余逗号等）
2. 正则表达式安全性（是否有灾难性回溯风险，如 (a+)+ 等）
3. JS 代码安全性（是否有死循环风险、未声明变量、ES6 语法混入）
4. URL 模板完整性（{{key}} 和 {{page}} 占位符是否正确使用）
5. Cookie / Header 处理（是否有跨域 Cookie 泄露风险）
6. 编码兼容性（是否有未处理的 Unicode 转义）
7. 规则链完整性（上游规则的输出是否能被下游正确消费）

【输出格式】：
如果所有检查通过，输出：
```
AUDIT_RESULT: PASS
```

如果发现漏洞，输出：
```
AUDIT_RESULT: REJECT

## 漏洞清单
1. [严重] ...描述... -> 建议修复：...
2. [中等] ...描述... -> 建议修复：...
3. [轻微] ...描述... -> 建议修复：...
```

【铁律】：
1. 只输出审计报告，绝不修改代码。
2. 如果发现任何可能导致 Legado 崩溃或书源无法使用的问题，必须标记为 REJECT。
3. 漏洞必须包含具体的修复建议。""",
}

# ============================================================
# 模型路由
# ============================================================

MODEL_MAP = {
    "analyst":   "kimi-k2.6-thinking",
    "engineer":  "claude-opus-4-6",
    "assembler": "claude-sonnet-4-5",
    "auditor":   "deepseek-reasoner",
}

# ============================================================
# 数据结构
# ============================================================

@dataclass
class PipelineState:
    """流水线状态机"""
    target_url: str = ""
    html_source: str = ""
    node_report: str = ""
    rule_code: str = ""
    full_json: str = ""
    audit_result: str = ""
    audit_feedback: str = ""
    iteration: int = 0
    final_output: str = ""
    history: list = field(default_factory=list)

# ============================================================
# API 调用层 (带 Failover + 重试)
# ============================================================

async def call_model(
    role: str,
    user_message: str,
    *,
    temperature: float = 0.3,
    max_tokens: int = 8192,
) -> str:
    """
    调用指定角色的模型，自动在多个 API URL 间故障转移。
    """
    model = MODEL_MAP[role]
    system_prompt = SYSTEM_PROMPTS[role]
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json",
    }
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_message},
        ],
        "temperature": temperature,
        "max_tokens": max_tokens,
    }

    last_error = None
    for base_url in API_BASE_URLS:
        url = f"{base_url.rstrip('/')}/v1/chat/completions"
        for attempt in range(1, MAX_RETRIES + 1):
            try:
                async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT) as client:
                    resp = await client.post(url, headers=headers, json=payload)
                    resp.raise_for_status()
                    data = resp.json()
                    content = data["choices"][0]["message"]["content"]
                    log.info(f"[{role}] 调用成功 (model={model}, url={base_url}, attempt={attempt})")
                    return content
            except httpx.TimeoutException:
                last_error = f"Timeout at {base_url} (attempt {attempt})"
                log.warning(f"[{role}] {last_error}")
            except httpx.HTTPStatusError as e:
                last_error = f"HTTP {e.response.status_code} at {base_url} (attempt {attempt})"
                log.warning(f"[{role}] {last_error}")
                # 4xx 不重试，直接换下一个 URL
                if 400 <= e.response.status_code < 500:
                    break
            except (json.JSONDecodeError, KeyError) as e:
                last_error = f"Response parse error at {base_url}: {e}"
                log.warning(f"[{role}] {last_error}")
            except Exception as e:
                last_error = f"Unexpected error at {base_url}: {e}"
                log.warning(f"[{role}] {last_error}")
            await asyncio.sleep(2 ** attempt)  # 指数退避

    raise RuntimeError(f"[{role}] 所有 API 端点均失败。最后错误: {last_error}")

# ============================================================
# 流水线编排 (调度中枢)
# ============================================================

async def run_pipeline(target_url: str, html_source: str) -> dict:
    """
    执行完整的四阶段流水线。

    Returns:
        {
            "success": bool,
            "final_json": str,      # 最终 JSON 书源 (如果成功)
            "audit_result": str,    # PASS / REJECT
            "audit_feedback": str,  # 审计详情
            "iterations": int,      # 实际迭代次数
            "history": list,        # 每轮历史记录
        }
    """
    state = PipelineState(target_url=target_url, html_source=html_source)

    # ── Step 1: 一年级分析 ─────────────────────────
    log.info("[调度] 正在调用一年级（架构分析师）分析目标站点...")
    analyst_input = f"目标 URL: {target_url}\n\nHTML/JSON 源码:\n{html_source}"
    state.node_report = await call_model("analyst", analyst_input)
    state.history.append({"stage": "analyst", "output_preview": state.node_report[:500]})
    log.info("[调度] 一年级分析完成，正在将节点报告透传给二年级...")

    # ── Step 2: 二年级编写规则 ─────────────────────
    log.info("[调度] 正在调用二年级（规则工程师）编写规则...")
    engineer_input = f"《节点定位报告》:\n\n{state.node_report}"
    state.rule_code = await call_model("engineer", engineer_input)
    state.history.append({"stage": "engineer", "output_preview": state.rule_code[:500]})
    log.info("[调度] 二年级规则编写完成，正在无损透传给三年级...")

    # ── Step 3+4: 组装 + 审计循环 ─────────────────
    for iteration in range(1, MAX_AUDIT_ROUNDS + 1):
        state.iteration = iteration

        # 三年级组装
        log.info(f"[调度] 正在调用三年级（代码执行者）组装 JSON (迭代 {iteration}/{MAX_AUDIT_ROUNDS})...")
        assembler_input = f"目标站点: {target_url}\n\n《规则代码片段》:\n\n{state.rule_code}"
        state.full_json = await call_model("assembler", assembler_input)
        state.history.append({"stage": "assembler", "iteration": iteration, "output_preview": state.full_json[:500]})

        # 四年级审计
        log.info(f"[调度] 正在调用四年级（质量审计员）进行红队审计...")
        auditor_input = f"待审计的 JSON 书源:\n\n{state.full_json}"
        state.audit_result = await call_model("auditor", auditor_input)
        state.history.append({"stage": "auditor", "iteration": iteration, "output": state.audit_result[:1000]})

        # 解析审计结果
        if "PASS" in state.audit_result.split("\n")[0] if state.audit_result else "":
            log.info(f"[调度] 审计通过！最终 JSON 书源如下：")
            state.final_output = state.full_json
            return {
                "success": True,
                "final_json": state.full_json,
                "audit_result": "PASS",
                "audit_feedback": state.audit_result,
                "iterations": iteration,
                "history": state.history,
            }

        # REJECT — 判断是否还有迭代机会
        state.audit_feedback = state.audit_result
        if iteration >= MAX_AUDIT_ROUNDS:
            log.warning(f"[调度] 已达最大重试次数 ({MAX_AUDIT_ROUNDS})，强制终止。请人工介入。")
            return {
                "success": False,
                "final_json": state.full_json,
                "audit_result": "REJECT (max iterations)",
                "audit_feedback": state.audit_feedback,
                "iterations": iteration,
                "history": state.history,
            }

        # 打回二年级修订
        log.info(f"[调度] 审计循环 ({iteration}/{MAX_AUDIT_ROUNDS})：四年级发现漏洞，正在打回二年级重修...")
        revise_input = (
            f"你上一次编写的规则未通过审计。以下是四年级的《漏洞清单》，请逐条修复：\n\n"
            f"{state.audit_feedback}\n\n"
            f"你上一次的规则代码（请在此基础上修复）:\n\n{state.rule_code}"
        )
        state.rule_code = await call_model("engineer", revise_input)
        state.history.append({"stage": "engineer_revised", "iteration": iteration, "output_preview": state.rule_code[:500]})

    # 理论上不会到这里
    return {
        "success": False,
        "final_json": state.full_json,
        "audit_result": "UNKNOWN",
        "audit_feedback": "",
        "iterations": state.iteration,
        "history": state.history,
    }

# ============================================================
# CLI 入口
# ============================================================

async def main():
    import sys

    # 示例：从命令行或文件读取输入
    if len(sys.argv) >= 3:
        target_url = sys.argv[1]
        source_file = sys.argv[2]
        with open(source_file, "r", encoding="utf-8") as f:
            html_source = f.read()
    else:
        # 默认演示
        target_url = "https://www.biquge.co"
        html_source = """<html>
<head><title>笔趣阁</title></head>
<body>
<div class="booklist">
  <div class="book-item">
    <a href="/book/12345/" class="bookname">示例小说</a>
    <span class="author">示例作者</span>
    <span class="intro">这是一本示例小说</span>
    <img src="/cover/12345.jpg" class="cover"/>
  </div>
</div>
</body>
</html>"""
        log.info("使用默认演示数据。用法: python legado_pipeline.py <target_url> <html_file>")

    result = await run_pipeline(target_url, html_source)

    print("\n" + "=" * 60)
    print(f"最终结果: {'✅ PASS' if result['success'] else '❌ REJECT'}")
    print(f"迭代次数: {result['iterations']}")
    print("=" * 60)

    if result["success"]:
        print("\n📋 最终 JSON 书源:\n")
        print(result["final_json"])
    else:
        print("\n⚠️ 未通过审计，最后一次输出:\n")
        print(result["final_json"][:2000])
        print("\n🔍 审计反馈:\n")
        print(result["audit_feedback"][:2000])

    # 保存结果
    output_path = "pipeline_result.json"
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    log.info(f"完整结果已保存到 {output_path}")


if __name__ == "__main__":
    asyncio.run(main())
