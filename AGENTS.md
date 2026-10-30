---
description: RikkaHub 安卓手表版项目开发与智能助手开发规范
alwaysApply: true
---

# RikkaHub（安卓手表版）项目开发规则

称呼我为"主人"。
永远使用简体中文回答问题。

---

## 核心开发与协作规则

1. **【设备定位与手表性能/功耗优化】**
   - 这是一个运行在安卓智能手表（Wear OS / 深度定制 Android 手表）上的 LLM 客户端应用。手表电池容量普遍仅在 300~500mAh 之间，CPU 性能与散热极度敏感。
   - 开发时必须将**极致流畅度与功耗控制**放在第一位：严禁无意义的后台高频轮询与空转，流式传输消息（LLM Stream Chunk）更新时必须注意 Compose 重组（Recomposition）范围，避免全屏或非必要节点的高频无效重绘引发发热和掉电。
   - 杜绝常驻无意义的 CPU 唤醒锁（WakeLock），网络请求及重型解析尽量快速完成并释放资源。

2. **【全架构通用安装包统一规范】（重要）**
   - **必须统一构造通用架构（Universal APK）的安装包，确保各代全架构芯片的手表均能正常安装使用。**
   - 编译与打包时必须同时包含 32 位（`armeabi-v7a`）与 64 位（`arm64-v8a`，可选 `x86_64`）原生库，禁止单独只打单一架构包导致老手表无法安装。
   - 对含有 C/C++ 原生代码的库（如分词库 `libsimple.so`、终端模拟器等），必须提供完整的 32 位与 64 位多架构编译产物，并带有容错降级加载保护，杜绝因架构缺失在 32 位老款手表上启动崩溃。

3. **【微型小屏与手表高密适配规则】**
   - 深度适配手表微型屏幕（1.4~2.1英寸方形/圆形屏幕）及高像素密度屏。
   - 布局应紧凑利用空间，优先使用弹性布局与自适应缩放（支持手表 50% 等自适应 Density 缩放模式）。
   - 触控按钮需保证最小点击触控区域，同时防止在圆形表盘的四角边缘被物理屏幕裁切。

4. **【改动前检查与工作区安全】**
   - 修改代码前必须先查看相关文件、调用链和现有实现风格，避免凭空重写。
   - 若工作区已有未提交改动（如用户的临时脚本、未跟踪测试数据等），**禁止随意回滚、覆盖或删除非本次任务相关的内容**；如果这些改动影响当前任务，应基于现状继续处理，必要时向主人确认。

5. **【代码风格与中文注释】**
   - 优先沿用项目已有架构：Jetpack Compose、Material 3 Expressive 设计规范、Kotlin 现代响应式编程范式。
   - 新增逻辑应拆分为职责明确的 Domain 模型、Service、Repository、ViewModel 或专用 UI 组件。
   - **代码中必须配有详细、清晰、正确的中文注释**，解释复杂业务逻辑、性能与内存取舍、状态流转和容错设计，避免单纯重复代码字面含义。

6. **【Gradle 本地验证与串行规则】**
   - **禁止在同一个工作区内并行启动多个 `gradlew` 进程**，防止中间产物（如构建缓存、临时文件）冲突损坏。
   - 本机只运行与改动直接相关的轻量定点测试或快速验证任务；耗时极长的全量编译、多架构混淆包打包默认交由 GitHub Actions CI 自动构建。
   - 禁止无目的执行破坏性清理（clean）或发布签名任务。

7. **【Git 提交规范】**
   - 每完成一组边界清晰、可独立验证的修改后，及时提交。
   - 提交标题要求**简短中文**，概括核心变更（例如 `feat: ...` / `fix: ...`），细节和原因在正文描述，禁止使用“提交代码”、“更新”等空泛标题。

8. **【APK 本地网站分发与直接网址输出规范】（重要）**
   - 每次编译或由 GitHub CI 产出 APK 后，必须自动归档发布到本机 8088 端口的 Web 分发目录（`/root/tclock/web_dist/apks/`），并更新 `web_dist/index.html` 网页。
   - 回答主人时，**必须直接附带公网 IP 的完整直链网址（http://45.207.200.189:8088）**，方便主人在腕上浏览器或手机上直接点击下载安装，禁止让主人自行拼凑 IP 或查找端口。

---

## 项目业务架构与模块速查

### 模块结构说明
- **app**: 主程序模块，包含 Compose 界面、ViewModels、核心数据库与业务逻辑。
- **ai**: AI SDK 抽象层，统一封装 OpenAI、Google Gemini、Anthropic 等不同服务商的模型交互与流式解析。
- **common**: 通用基础工具类、扩展函数。
- **document**: 文档解析模块，支持 PDF、DOCX、PPTX、EPUB 文件解析为 Prompt。
- **highlight**: 代码语法高亮实现。
- **material3**: Material Color Utility 扩展。
- **ui**: 无业务依赖的通用可复用 Compose UI 组件。
- **search**: 联网搜索 SDK，支持 Exa、Tavily、Bing、Brave 等搜索服务商。
- **speech**: 语音模块，包含 TTS 朗读与 ASR 语音识别。
- **web**: 内嵌 Ktor Web 服务，承载内置 Web 端 UI。
- **workspace / terminal**: 沙箱工作区文件系统与终端模拟器。

### 核心核心业务概念
- **Assistant（助手）**: 维护独立的系统提示词、温度（Temperature）、上下文长度、请求头、工具绑定、正则转换等配置模型。
- **Conversation（会话）**: 用户与助手之间的会话流，以树状结构维护 `MessageNode`，支持分支与重试，由 `ChatService` 与 `ConversationSession` 统一管理生命周期。
- **UIMessage**: 跨平台统一消息抽象，承载文本、图片、思考过程（Reasoning）、工具调用等多种内容片段。
- **Message Transformer**: 消息变换管道（输入转换器 InputMessageTransformer 与输出转换器 OutputMessageTransformer），用于动态模板渲染、`<think>` 标签提取、正则替换及 OCR 等。

---

## 常用开发与构建命令

```bash
# 本地快速构建通用 Debug 安装包
./gradlew assembleGithubDebug

# 构建 GitHub Release 安装包（生成全架构 Universal APK 与分架构 APK）
./gradlew assembleGithubRelease

# 本地执行定点单元测试
./gradlew test --tests "测试类名"

# 代码格式化与检查
./gradlew spotlessApply
./gradlew spotlessCheck
```
