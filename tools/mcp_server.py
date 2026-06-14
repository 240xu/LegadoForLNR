"""
Legado 书源开发流水线 — MCP Server 封装

使用方法：
1. 安装依赖: pip install mcp httpx
2. 在 Claude Desktop 的 settings.json 中添加:
   {
     "mcpServers": {
       "legado-pipeline": {
         "command": "python",
         "args": ["C:/Users/ova44/Desktop/LegadoForLNR-master/tools/mcp_server.py"]
       }
     }
   }
3. 重启 Claude Desktop，即可在对话中调用 generate_book_source 工具。
"""

import asyncio
import json
import sys
import os

# 确保同目录的 legado_pipeline 可被导入
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from mcp.server import Server
from mcp.server.stdio import run_server
from mcp.types import Tool, TextContent

from legado_pipeline import run_pipeline

app = Server("legado-pipeline")


@app.tool()
async def generate_book_source(target_url: str, html_source: str) -> str:
    """
    分析网页并自动生成 Legado JSON 书源。

    Args:
        target_url: 目标网站 URL (如 https://www.example.com)
        html_source: 网页 HTML 源码或 API JSON 响应

    Returns:
        完整的 Legado JSON 书源字符串，或审计失败时的漏洞报告。
    """
    result = await run_pipeline(target_url, html_source)

    if result["success"]:
        return (
            f"✅ 审计通过 (迭代 {result['iterations']} 次)\n\n"
            f"```json\n{result['final_json']}\n```"
        )
    else:
        return (
            f"❌ 审计未通过 (已迭代 {result['iterations']} 次)\n\n"
            f"最新版本:\n```json\n{result['final_json'][:3000]}\n```\n\n"
            f"审计反馈:\n{result['audit_feedback'][:2000]}"
        )


@app.tool()
async def analyze_site(target_url: str, html_source: str) -> str:
    """
    仅分析网页结构，不生成书源。用于预览一年级的分析结果。

    Args:
        target_url: 目标网站 URL
        html_source: 网页 HTML 源码或 API JSON 响应

    Returns:
        一年级输出的《节点定位报告》。
    """
    from legado_pipeline import call_model

    analyst_input = f"目标 URL: {target_url}\n\nHTML/JSON 源码:\n{html_source}"
    report = await call_model("analyst", analyst_input)
    return report


async def main():
    await run_server(app)


if __name__ == "__main__":
    asyncio.run(main())
