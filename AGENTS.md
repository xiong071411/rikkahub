# Repository Guidelines

## Project Overview

RikkaHub is a native Android LLM chat client that supports switching between different AI providers
for conversations.
Built with Jetpack Compose, Kotlin, and follows Material 3 Expressive (MD3 Expressive) design language.

## Design Language

The UI follows Material 3 Expressive. New and reworked screens should match it rather than the older baseline
Material 3 look (cards with dividers, plain forms).

- The app theme is `MaterialExpressiveTheme` (app/src/main/java/me/rerere/rikkahub/ui/theme/Theme.kt); prefer the
  expressive variants of components, shapes (`MaterialShapes`), emphasized typography (e.g. `titleSmallEmphasized`)
  and motion from `androidx.compose.material3` over hand-rolled equivalents.
- Settings-style and form pages use segmented groups via `CardGroup` (
  app/src/main/java/me/rerere/rikkahub/ui/components/ui/CardGroup.kt) instead of a card with dividers.
- Favor larger corner radii, filled/tonal containers, and whole-row click targets.

## Build, Test, and Development Commands

```bash
./gradlew assembleGithubDebug    # 构建 GitHub 渠道的 Debug APK
./gradlew assembleGithubRelease  # 构建 GitHub Release / 每日构建用的 APK
./gradlew bundlePlayRelease      # 构建上架 Google Play 的 AAB
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew lint                   # 运行 Android Lint
./gradlew spotlessApply          # 用 ktlint 格式化相对 origin/master 改动过的 Kotlin 文件
./gradlew spotlessCheck          # 只检查格式不改文件
```

App 有一个 `distribution` 维度的 product flavor：`github`（默认）和 `play`，共用同一个 applicationId。
渠道差异用 `BuildConfig.IS_PLAY_STORE` 判断（如 Play 版不检查更新、不显示更新提醒）。

## Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, PPTX, and EPUB files
- **material3**: Material color utility extensions used by the app UI
- **ui**: Reusable Compose UI components that do not depend on app logic
- **search**: Search functionality SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, and others)
- **speech**: Speech module for TTS and ASR implementations
- **web**: Embedded web server module that provides Ktor server startup function and hosts static frontend build files (
  built from web-ui/ React project)
- **workspace**: Sandboxed per-workspace file system and shell execution environment exposed to the AI as tools.
- **terminal**: Terminal emulator core taken from termux-app's `terminal-emulator` (package `com.termux.terminal`, kept
  binary compatible with the `terminal-view` aar). `TerminalSession` runs over a `TerminalTransport`: local PRoot
  terminals use `LocalPtyTransport`, SSH workspaces use the app's `SshTerminalTransport`. Origin and local changes are
  listed in terminal/README.md.

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex
  transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific
  behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings.
  Once a conversation is persisted it also holds a `ConversationConfig` snapshot (chat model, reasoning level, search,
  MCP servers, workspace, skills) taken from the assistant; from then on chat-page changes to those settings stay on
  the conversation, and code should read them through `Settings.getAssistantOf(conversation)` /
  `Settings.getChatModelOf(conversation)` instead of the assistant directly. Once loaded, the in-memory
  `ConversationSession` is the single source of truth: change a conversation through `ChatService` (which applies
  `session.update { latest -> ... }` and then `flush()`), never by saving a whole `Conversation` snapshot or one read
  back from the database; deleting also goes through `ChatService` so a running generation cannot re-insert it. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt,
  app/src/main/java/me/rerere/rikkahub/data/model/ConversationConfig.kt,
  app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT,
  SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support
  streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers
  include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- Add, translate or clean up strings with the `tools/i18n` CLI (Bun); see the `i18n-localization` skill.

## Personal Branch and Local Development

以下是 `personal-main` 的补充约定，适用于 Windows 本机和 Linux 服务器。保留上文官方架构、设计语言和开发工具约定；本节补充个人版的构建变体与开发、发布边界。

### 日常开发与验证

- 始终使用简体中文回答。个人功能在 `personal-main` 或从它创建的功能分支上开发；开始前检查当前分支和工作区状态，保留已有未提交改动，不覆盖个人功能来同步官方更新。
- 优先沿用现有实现，按任务需要使用 `.agents/skills/` 中已有的专项技能；复杂逻辑的注释说明原因、状态流转与性能取舍。
- 上文的 `./gradlew` 命令用于 Linux/macOS；Windows PowerShell 使用 `.\gradlew.bat`，例如 `.\gradlew.bat :app:assembleCoexistDebug`。JDK、Android SDK、签名及路径以当前开发环境配置为准，不套用另一台机器的绝对路径。
- 编译、Gradle 单元测试和 Lint 统一在 GitHub CI 执行，本机不运行 Gradle 构建；本机只进行代码检查、轻量脚本验证和 CI 产物校验，避免挤占部署服务的内存。纯文档改动检查差异即可。
- 日常开发、验证和生成测试 APK 不自动递增版本号、执行发布签名、上传安装包或更新发布门户。需要交付本地 APK 时，提供实际产物路径。
- Git 提交标题使用简短中文并概括核心变更，只包含本次任务相关文件。推送前留意 `.github/workflows/build-arm32.yml`：当前向 `personal-main` 推送会触发构建和 GitHub Release 上传，不为本地验证主动推送代码或触发发布工作流。

### 手表适配与个人构建变体

- 保留小屏、圆屏和低功耗适配，避免后台高频轮询、无意义的 WakeLock 和流式输出引起的过度重组，同时兼顾正常比例的手机界面。
- 上文列出官方 `github` / `play` 渠道；`personal-main` 在同一个 `distribution` 维度中另有两个共存变体，定义见 `app/build.gradle.kts`：

| 变体 | 显示比例 | Release applicationId | Windows 调试构建命令 |
| --- | --- | --- | --- |
| `coexist` | 50%，适合手表小屏 | `me.rerere.rikkahub.coexist` | `.\gradlew.bat :app:assembleCoexistDebug` |
| `coexistNormal` | 100%，正常比例 | `me.rerere.rikkahub.coexist.normal` | `.\gradlew.bat :app:assembleCoexistNormalDebug` |

- Debug 包还带有 `.debug` 后缀。当前个人分支的 `github` 也采用 50% 比例，`play` 为 100%；比例以各变体的 `BuildConfig.SCALE_FACTOR` 为准。
- 日常调试按目标设备选择一个变体。正式发布共存版时同时提供 `coexist` 与 `coexistNormal`，并保留包含 `armeabi-v7a` 和 `arm64-v8a` 的 Universal APK，检查所需原生库的架构完整性。
- 个人构建只支持 ARM 32 位、ARM 64 位及同时包含两者的 Universal APK，不构建 x86/x86_64 包或对应原生库。门户发布矩阵为 `github`、`coexist`、`coexistNormal` 三个变体各三包，共 9 包。

### 发布与服务器部署

- 仅在用户明确要求发布或部署时执行对应流程，再按需使用发布技能。生成 APK 本身不表示需要部署到服务器。
- 发布时根据当前任务和部署配置确定目标服务器、目录、版本及下载链接；本机开发不默认连接旧服务器，不操作 tclock 项目的分发目录，也不要求启动或更新 8088 门户。
- 只有实际发布成功后才提供对应下载链接；本地验证报告说明改动、验证结果及本地产物路径。

### 自建 Web 与 Sync V2 同步契约

- 统一服务端仓库位于 `/root/rikkahub-web`（同时承载 Web 聊天前端与 Sync V2 增量同步后端）；旧独立仓库 `/root/rikkahub-sync-server` 已归档为只读历史备份，禁止再次启动。
- Web 端登录鉴权（密码 + `HttpOnly; SameSite=Strict` 签名 Cookie + CSRF 校验）与 Android 客户端同步鉴权（`Authorization: Bearer <RIKKA_SYNC_TOKEN>`）严格分离；客户端严禁硬编码默认同步令牌。
- Android 端同步引擎（`IncrementalSyncEngine`）通过 `ChatService` / `ConversationSession` 单一事实源落地远端会话变更；跨语言语义哈希定义须保持 `CanonicalSyncHash.kt` 与服务端 `backend/app/canonical.py` 完全一致。
- Room 数据库升级必须编写显式增量 `Migration` 脚本并注册至 `AppDatabaseFactory.addMigrations()`，严禁依赖 `fallbackToDestructiveMigration()`。
