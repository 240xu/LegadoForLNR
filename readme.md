# LegadoForLNR

[LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader) 插件，支持直接导入 [lyc486 版 Legado](https://gitee.com/lyc486/legado) 的 JSON 书源文件，无需格式转换。

## 功能特性

- **搜索** — 跨所有已导入书源搜索小说
- **发现** — 浏览配置了发现页的书源
- **书籍详情** — 获取书名、作者、封面、简介、标签
- **目录** — 获取章节目录
- **正文** — 获取并显示章节内容
- **JavaScript 规则** — 内嵌 Rhino 引擎执行 JS 规则
- **loginUi 登录** — 完整支持 Legado 的 `loginUi` 表单登录系统
  - 支持 `text`、`password`、`select`（下拉）、`toggle`（开关）、`button` 五种表单类型
  - 支持 `@js:` / `<js>` 前缀的动态 UI 生成
  - 支持 `loginUrl` 中的 `login()` JS 函数调用
  - 支持 `loginCheckJs` 登录状态检查
  - 支持按钮 `action`（URL 跳转 / JS 执行）
  - 支持 `upLoginData` / `reLoginView` JS 回调动态更新表单
  - 支持 WebView 模式（无 loginUi 时回退到 WebView 加载 loginUrl）
  - 登录信息持久化存储
- **功能开关** — 可单独启用/禁用各项功能

## 构建

### 环境要求

- JDK 17+
- Android SDK (compileSdk 36)
- Gradle 9+（已包含 wrapper）

### 编译

```bash
cd plugin
./gradlew :plugin:assembleDebug
```

产物路径：`plugin/plugin/build/outputs/apk/debug/plugin-debug.apk.lnrp`

### 安装

将生成的 `.lnrp` 文件通过 LightNovelReader 的插件管理界面导入即可。

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

插件内嵌了剥离 Android 深度依赖的 Legado 解析引擎：

```
io.legado.engine/
├── model/       # 数据模型 (BookSource, RowUi, SearchRule 等)
├── rule/        # 规则解析 (AnalyzeRule, AnalyzeUrl)
├── http/        # HTTP 客户端 (OkHttp + CookieStore)
└── js/          # Rhino JS 运行时

io.legado.plugin/
├── LegadoJsonPlugin.kt           # 插件元数据
├── LegadoJsonWebDataSource.kt    # LNR WebBookDataSource 实现
├── LoginActivity.kt              # 登录 Activity (loginUi 表单 + WebView)
└── LoginJsBridge.kt              # 登录 JS 桥接 (java.xxx())
```

## 许可证

本项目采用 **GNU 通用公共许可证 v3.0** (GPL-3.0)。

包含来自 [Legado](https://gitee.com/lyc486/legado) 项目的代码，同样采用 GPL-3.0 许可。

## 致谢

- [Legado](https://gitee.com/lyc486/legado) by lyc486 — 核心解析引擎
- [LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader) — 宿主应用
- [LightNovelReader Plugin Template](https://github.com/dmzz-yyhyy/LightNovelReaderPlguin-Template) — 插件模板
