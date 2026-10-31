## 2026-10-31｜全栈安全加固、Web 聊天修复与 Sync V2 增量同步重构
(TechChangeSpec_2026-10-31-SyncV2-Security-WebFix)

### 方案总览与实现要点

本次技术变更围绕 RikkaHub 跨平台个人分支（Android 客户端 `personal-main` 与统一 Web & Sync 服务端 `/root/rikkahub-web`）执行端到端架构重构与缺陷闭环，涵盖以下五大工程主线：

1. **公网零信任鉴权隔离与安全加固**：
   - 将 Web 浏览器会话鉴权与 Android 客户端同步鉴权彻底分离：Web 端采用 PBKDF2-HMAC-SHA256 口令哈希校验 + `HttpOnly; SameSite=Strict` 签名会话 Cookie + `Origin`/`Referer` 与 `X-Rikka-Web: 1` 双重 CSRF 校验 + IP 速率限制；Android 同步接口采用独立随机密钥 `RIKKA_SYNC_TOKEN`（`Authorization: Bearer <token>`）常量时间比对，服务端配置缺失或使用弱默认口令时启动即拒绝（Fail-Closed）。
   - 关闭 FastAPI 默认暴露的 `/docs`、`/openapi.json`、`/redoc`，收敛 CORS 策略，重写 SPA 静态资源与附件读写路由的 `Path.resolve().relative_to(...)` 严格边界校验，彻底封堵 `%2e%2e%2f` 编码路径穿越。
   - `/api/settings` 响应对所有 Provider API Key、Vertex AI 私钥、自定义鉴权请求头与搜索密钥执行脱敏（仅回传布尔标记如 `hasApiKey`），更新时未改动的脱敏占位自动合并保留原库密文；Android 端 `OkHttp` 日志拦截器同步开启 `Authorization`、`x-api-key`、`x-goog-api-key`、`Cookie` 脱敏。
2. **Sync V2 单调修订号增量同步与双副本冲突保护**：
   - 废除依赖设备本地毫秒时钟和整段覆盖的旧 V1 同步协议（旧 `/api/sync/push`、`/api/sync/pull` 统一返回 HTTP `426 Upgrade Required` 与中文升级提示）。
   - 引入服务端单调递增 `revision` 变更日志、`upper_bound_revision` 分页快照锁、`mutation_id` 幂等去重表、客户端离线 `sync_outbox` + `sync_entity_state(base_revision)` 乐观并发校验。
   - 跨语言（Python `backend/app/canonical.py` 与 Kotlin `CanonicalSyncHash.kt`）实现逐字节一致的确定性语义哈希（Unicode 码位键排序、整数浮点归一、毫秒级 ISO-8601 UTC 时间对齐、剔除顶层易变 `updateAt`/`updatedAt`、结构化附件路径相对化且绝不触碰聊天正文文本）。
   - 多端离线并发修改同一实体时，绝不静默丢弃任一端内容，双端均将本地与云端完整 JSON 副本落入 `sync_conflicts`，并在 Web 端与 Android 端（Material 3 Expressive 风格）提供可视化冲突中心（支持“另存为新会话”、“采用本地覆盖”、“保留云端”）。
3. **Android 端持久化修复、会话单一事实源接入与无损生图同步**：
   - 修复 `SyncServerConfig` 未注册进 `PreferencesStore` / `SettingsFields` 导致冷启动丢失同步令牌与开关状态的问题，并在 `SettingsNormalization` 中自动清除历史硬编码默认口令。
   - Room 数据库从 v32 显式 SQL 迁移升级至 v33（`Migration_32_33`），新增 6 张 Sync V2 状态与队列表，严禁使用破坏性重建。
   - 将 `MediaCreationDAO.insertSession` 由 `@Insert(onConflict = REPLACE)` 改为 `@Upsert`，避免 SQLite `DELETE + INSERT` 触发外键 `ON DELETE CASCADE` 清空 `media_creation_node` 与 `media_creation_record`，并将 `media_session`、`media_node`、`media_record` 完整纳入拓扑顺序同步。
   - 远端会话更新与删除统一经由 `ChatService`（`ConversationSession` 内存单一事实源）与 `RemoteSyncApplyContext` 落地；若目标会话正在流式生成，自动暂存至 `sync_inbox_pending` 待空闲后再合并。
4. **Web 聊天全链路与多模态附件修复**：
   - 统一服务端存储目录与相对路径协议，修复历史对话图片 404，打通模型视觉识图（base64 data URL 注入）与多格式文档（`TXT`、`MD`、`PDF`、`DOCX`、`PPTX`、`EPUB`）服务端文本解析（注入 `<UploadFile>` 标签）。
   - 全面对齐助手与会话级 `ConversationConfig`（`systemPrompt`、`customSystemPrompt`、`modeInjections`、`lorebooks`、`truncateIndex`、自定义 Headers、消息编辑/重生成与消息树分支切换），原生补齐 `openai`（Chat Completions 与 Responses API）、`claude`（Anthropic Messages SSE）、`google`（Gemini `streamGenerateContent` SSE）适配器，严禁跨提供商随机回退。
   - 将浏览器当前选中助手隔离至标签页级 `sessionStorage`，不再污染全局同步 `assistantId`；对 Web 端暂未实现的本地工具（搜索、MCP、Workspace、工具审批）在 UI 明确置灰说明并在后端返回 HTTP `400` 清晰中文提示。
5. **多架构原生库补齐与 CI 发布守卫**：
   - 补齐 `app/src/main/jniLibs/armeabi-v7a/libsimple.so`，确保 `github`、`coexist`、`coexistNormal` 三个正式 Universal APK 同时具备 32 位与 64 位原生分词能力，兼容 32 位安卓手表与 64 位手机。
   - 修复 `.github/workflows/build-arm32.yml` 硬编码版本号与每次推送覆盖 GitHub Release 的风险。
6. **服务器构建联调阶段追加修复（单元测试暴露的真实缺陷）**：
   - `SyncMutationRecorder`：同一实体的 Outbox 条目在内容哈希与操作类型均未变化时保留原 `mutationId`，修复断网重推无法命中服务端幂等表的问题。
   - `IncrementalSyncEngine.parseHttpError`：401/403/426 错误文案始终保留 HTTP 状态码与中文操作引导，服务端 `detail` 仅作为括号补充。
   - `RemoteSyncApplyContext`：协程上下文键拆分为独立的 `RemoteSyncApplyContextKey` 对象，修复 Kotlin 2.4 下 `object` 内不能声明 `companion` 的编译错误。

### 问题回溯与根因分析

1. **公网未授权裸奔与目录穿越根因**：
   - 旧版 `/root/rikkahub-web` 的 Web API 未配置会话鉴权中间件，静态 SPA 兜底路由直接拼接 URL 路径而未做 `Path.resolve().relative_to(STATIC_DIR)` 校验，导致编码后的 `..` 可越界访问工作目录；`/api/settings` 直接原样回传包含第三方 API Key 的完整 JSON。
2. **Android 同步配置重启丢失根因**：
   - `Settings` 数据类新增 `syncServerConfig` 字段时，未同步在 `PreferencesStore.kt` 定义 `SYNC_SERVER_CONFIG` 键、亦未在 `SettingsFields.kt` 的 `SETTINGS_FIELDS` 注册序列化映射，导致配置仅驻留内存并在进程重启后回退默认值。
3. **跨端同步丢消息、删不掉、假冲突与生图记录被清空根因**：
   - 旧同步依赖客户端 `System.currentTimeMillis()`，存在双端时钟漂移漏拉与同毫秒边界漏拉；推送失败时不持久化离线队列却推进同步时间戳导致离线改动永久漏推；列表类扩展配置按 ID 只增不减导致删除复活；`MediaCreationSessionEntity` 使用 `OnConflictStrategy.REPLACE` 触发 SQLite 外键级联删除，导致每次拉取父会话都会清空子表全部节点与生图记录。
4. **跨包名与 Web 端历史图片裂图根因**：
   - Android 消息部件持久化了带具体安装包名的绝对 URI（如 `file:///data/user/0/me.rerere.rikkahub/files/upload/...`），同步到 `coexist`、`coexistNormal` 或 Web 端后无法匹配各自私有目录或 `/api/files/path/...` 接口；且旧服务端 `sync.py` 与 `files.py` 指向不同物理子目录。
5. **Web 端模型调用异常与助手串台根因**：
   - 旧版 `llm.py` 未实现 `claude`、`google` 与 OpenAI `responses` 协议且在找不到配置时回退到列表首个 Provider；前端切换助手直接改写全局同步字段 `settings.assistantId`。
6. **Outbox `mutationId` 幂等失效根因（单元测试 `invalid token 401 legacy 426 and mutation_id retry idempotency` 暴露）**：
   - 每轮同步开始前 `enqueueLocalDiffIntoOutbox` 都会重新扫描本地快照，对“与基线哈希不一致”的实体再次调用 `recordUpsert`；而旧实现只要 Outbox 已有条目就无条件 `mutationId = Uuid.random()`。
   - 当“服务端已落库但响应在途中丢失”时，下一轮重推的 `mutationId` 已被换新，服务端无法识别为重复提交，又因 `baseRevision` 落后于自己刚提交的修订而误判为冲突，产生假冲突副本。
7. **鉴权错误文案丢失状态码根因**：
   - `parseHttpError` 中 `detail ?: 默认文案` 的写法使服务端只要返回 `detail`（如英文 `Invalid sync token`）就完全覆盖中文引导与 HTTP 状态码，手表端用户无法判断是令牌错误还是协议需升级。
8. **服务器 Gradle 守护进程被系统杀死根因**：
   - 项目 `gradle.properties` 配置 `-Xmx8g`，Kotlin 编译守护进程也继承 8G 上限；构建服务器仅 3.8G 物理内存，编译 `app` 模块时守护进程被 OOM 回收（`Gradle build daemon disappeared unexpectedly`）。

### 受影响文件与关键点

#### 一、Android 客户端仓库（`C:\Android\rikkahub`）
- `app/build.gradle.kts`：版本号递增至 `versionCode = 197`、`versionName = "3.1.3"`。
- `app/src/main/jniLibs/armeabi-v7a/libsimple.so`：补齐 32 位 ARMv7 原生 SQLite 全文分词库，确保 Universal APK 双架构完整。
- `app/src/main/java/me/rerere/rikkahub/data/datastore/Settings.kt`、`PreferencesStore.kt`、`SettingsFields.kt`、`SettingsNormalization.kt`：移除硬编码同步口令，补齐 `SYNC_SERVER_CONFIG` DataStore 持久化映射与遗留弱口令自动清洗。
- `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt`、`AppDatabaseFactory.kt`、`migrations/Migration_32_33.kt`、`entity/SyncV2Entities.kt`、`dao/SyncV2Dao.kt`：升级 Room 至 v33，通过显式 SQL 脚本创建 `sync_entity_state`、`sync_outbox`、`sync_inbox_pending`、`sync_conflicts`、`sync_file_queue`、`sync_meta` 六张表及索引，并无损承接旧版 `sync_tombstone`。
- `app/src/main/java/me/rerere/rikkahub/data/db/dao/MediaCreationDAO.kt`：将 `insertSession` 改为 `@Upsert`，新增 `upsertNode`、`upsertRecord`、`getAllNodes`、`getAllRecords`。
- `app/src/main/java/me/rerere/rikkahub/data/sync/v2/AttachmentPathNormalizer.kt`、`CanonicalSyncHash.kt`、`SyncMutationRecorder.kt`、`SyncV2Payloads.kt`：实现结构化附件路径双向转换、跨语言确定性语义哈希、保留初始 `baseRevision` 的离线变更记录器与 `RemoteSyncApplyContext`。
- `app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt`、`FolderRepository.kt`、`FavoriteRepository.kt`、`MediaCreationRepository.kt`：接入本地增删改 Outbox 自动记录。
- `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`：新增 `isConversationBusyForSync`、`applyRemoteSyncedConversation`、`applyRemoteDeletedConversation`，保证远端同步严格走 `ConversationSession` 单一事实源，且绝不往 `MainActivity` 堆叠业务代码。
- `app/src/main/java/me/rerere/rikkahub/data/sync/engine/IncrementalSyncEngine.kt`：重构为 Sync V2 全实体修订增量引擎，内置 `Mutex` 串行锁、分页快照拉取、拓扑推送、双副本冲突处理与 `.tmp` 原子附件队列。
- `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt`、`RepositoryModule.kt`：注册 Sync V2 组件并为 `HttpLoggingInterceptor` 增加敏感请求头脱敏。
- `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/BackupVM.kt`、`tabs/SyncTab.kt`：采用 Material 3 Expressive `CardGroup` 重构云端同步页与冲突恢复中心弹窗，兼顾手表小屏与常规手机排版。
- `app/src/test/java/me/rerere/rikkahub/data/datastore/SettingsFieldsTest.kt`、`data/sync/v2/CanonicalSyncHashTest.kt`、`data/sync/v2/SyncV2EngineTest.kt`：补齐 DataStore 映射、跨端黄金语义哈希与 Sync V2 引擎全场景单元测试。
  - 联调修正：测试改用真实 API（`me.rerere.ai.core.MessageRole`、`Conversation` 必填 `assistantId`、`SyncServerConfig` 无 `enabled` 字段、`MediaCreationRecordEntity` 必填 `taskId`/`error`）。
- `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/33.json`：由 KSP 在服务器构建时生成，已拷回 Windows 仓库保持两端一致。
- `app/google-services.json`：为三个变体补齐 `.debug` 包名客户端条目，使 Debug 构建与单元测试能通过 `process*DebugGoogleServices`。
- 构建服务器 `/root/.gradle/gradle.properties`（不在仓库内）：用户级覆盖为 `-Xmx2560m`、`kotlin.compiler.execution.strategy=in-process`、`org.gradle.parallel=false`，适配 3.8G 内存，不改动仓库内 `gradle.properties`。
- `.github/workflows/build-arm32.yml`、`AGENTS.md`：修复 CI 动态版本提取与 Release 触发守卫，更新仓库开发规范。

#### 二、统一 Web & Sync 服务端仓库（`/root/rikkahub-web`）
- `backend/main.py`、`backend/app/config.py`、`security.py`、`database.py`、`canonical.py`、`secret_redaction.py`、`document_parser.py`、`llm.py`：实现启动期密钥强校验、Web Cookie/CSRF 鉴权、Sync Bearer 鉴权、SQLite WAL 迁移与修订表、跨端语义哈希、密钥脱敏、多格式文档文本提取及四类原生流式模型协议适配。
- `backend/app/routes/auth.py`、`sync.py`、`conversations.py`、`settings.py`、`files.py`：实现登录登出、Sync V2 接口（`/v2/info`、`/v2/pull`、`/v2/push`、`/v2/conflicts`）、V1 `426` 拦截、服务端流式生成锁、结构化附件路径改写与统一文件防穿越读写。
- `scripts/manage_secrets.py`、`scripts/migrate_v2.py`、`scripts/backup_consistent.py`、`scripts/run.sh`：提供无回显密钥管理、幂等 V1->V2 迁移与热备份工具。
- `frontend/src/*`：新增登录页、同步冲突中心弹窗、当前标签页独立助手选择、完整附件渲染/文档上传与未实现工具置灰提示。

### 行为变更明细

1. **未登录/无令牌访问行为**：访问 Web 页面自动跳转登录页；直接请求 `/api/*` 返回 HTTP `401`；请求 `/docs`、`/openapi.json` 返回 `404`；缺少 `X-Rikka-Web: 1` 或跨站 `Origin` 的写操作返回 `403`。
2. **旧版客户端兼容拦截**：旧版 Android APK 调用 `/api/sync/push` 或 `/api/sync/pull` 时返回 HTTP `426 Upgrade Required` 及明确中文升级指引，防止旧客户端用残缺协议污染服务端状态。
3. **并发编辑与删除行为**：双端离线修改同一会话或一端修改另一端删除时，推送端收到 `conflict` 状态，云端与本地同时保留双方完整副本并在 UI 醒目展示待处理冲突数，支持一键另存为新会话，彻底杜绝静默覆盖丢消息。
4. **扩展设置删除同步**：标签、世界书、模式注入、快捷短语、自定义主题、MCP 配置等扩展设置由“只增不减合并”改为基于修订号的权威集合同步，任一端删除条目后跨端正常同步删除。
5. **附件开关关闭提示**：Android 同步页在关闭“同步图片与附件”时展示清晰横幅说明仅同步文本且远端新附件进入待下载队列，重新开启开关后自动断点补拉。
6. **断网重推幂等**：同一实体内容未变时，多轮同步之间 Outbox 的 `mutationId` 保持不变；服务端对重复 `mutation_id` 返回 `noop`，修订号不重复自增，不再产生假冲突。
7. **同步错误提示**：鉴权失败统一显示“同步令牌鉴权失败 (HTTP 401)，请核对 Sync Token 是否与服务端一致（服务端：…）”，协议不匹配显示含“升级”的 HTTP 426 引导。

### 验收用例与标准

1. **自动化测试标准**：
   - 服务端 `pytest -q` 全部通过（含鉴权、路径穿越、密钥脱敏、跨语言黄金哈希、Sync V2 并发冲突、V1 迁移、LLM 协议与文档提取）。
   - Web 前端 `pnpm run typecheck && pnpm run fmt:check && pnpm run build` 零错误通过。
   - Android 客户端 `.\gradlew.bat :app:testCoexistNormalDebugUnitTest` 全部通过（含 `SettingsFieldsTest`、`CanonicalSyncHashTest`、`SyncV2EngineTest`）。
   - 实测结果（2026-10-31，服务器 `/root/rikkahub` 执行 `./gradlew :app:testCoexistNormalDebugUnitTest`）：`500 tests completed, 0 failed`，`BUILD SUCCESSFUL`。
2. **公网线上安全与功能验收标准**：
   - 未登录请求 `/api/settings`、`/api/conversations`、`/api/files/path/upload/...`、`/api/sync/v2/pull` 全部返回 `401`；编码穿越路径全部返回 `400`/`403`/`404`。
   - Web 登录后 `/api/settings` 不含任何明文密钥，历史图片消息返回 `200 image/jpeg`，上传文档成功提取 `<UploadFile>` 文本。
3. **安装包与真机 ADB 验收标准**：
   - `github`、`coexist`、`coexistNormal` 三个 Universal Release APK 均由同一 `release.jks` 签名，同时包含 `lib/armeabi-v7a/libsimple.so` 与 `lib/arm64-v8a/libsimple.so`。
   - 真机 `adb install -r` 覆盖安装保留原数据，配置 Sync Token 后强行停止再冷启动配置不丢失，连续执行 3 次同步不丢失任何 `media_creation` 记录，`logcat` 无崩溃且敏感请求头已脱敏。

### 性能与功耗评估

1. **面向安卓手表与低功耗场景优化**：
   - **零后台高频轮询与零 WakeLock**：移除无意义后台定时唤醒，增量同步仅在用户主动触发或会话页面明确需要时经单个 `Mutex` 串行执行，杜绝并发重复网络请求。
   - **增量游标替代全表扫描传输**：常规同步仅拉取 `revision > cursor` 的增量变更集，网络包体积与 JSON 解析 CPU 开销相比全量快照下降一个数量级以上。
   - **流式文件 I/O 与预缓存格式化器**：附件下载与 SHA-256 校验采用固定 `32KB/64KB` 缓冲区流式读写，严禁将大图或长文档整块读入堆内存引发手表小内存 OOM；`SyncTab.kt` 使用单例 `DateTimeFormatter`、稳定 `LazyColumn(key, contentType)` 与 `SharingStarted.WhileSubscribed(5_000)`，退后台 5 秒自动释放数据库监听游标，最小化 Compose 重组与待机功耗。
   - **减少无效推送**：`mutationId` 幂等修复后，内容未变的实体不再在每轮同步时改写 Outbox 行，减少一次数据库写入，并避免服务端因假冲突产生多余副本和流量。

### 风险与回滚策略

1. **已知边界风险**：
   - 当前按既有部署架构保持 `http://45.207.200.189:8765` 明文 HTTP 传输（不改动 `:443`/`:80` 既有服务），Cookie 与 Bearer Token 防护了未授权访问与跨站攻击，但在不可信公共 Wi-Fi 下仍建议配合加密隧道使用。
2. **服务端快速回滚策略**：
   - 切换前已在 `/root/backups/rikkahub-20261031-162433`（权限 `700`）完成 SQLite 原子热备、附件全量备份、源码归档与 `SHA256SUMS` 校验，并通过独立临时目录恢复演练。
   - 若线上出现异常，可在 1 分钟内停止新进程、将备份 `sync_pre_cutover.db` 与旧版代码归档拷回 `/root/rikkahub-web` 重启恢复。
3. **Android 客户端回滚策略**：
   - Room `Migration_32_33` 仅执行增量建表与数据导入，不破坏或删除原有 `ConversationEntity`、`MessageNodeEntity`、`MediaCreation*` 等核心业务表字段；三个变体均保留与线上一致的 `release.jks` 签名，且下载门户保留历史版本归档文件。

### 版本信息与变更摘要

- **客户端版本信息**（读取自 `app/build.gradle.kts`）：
  - `versionCode`：`197`（由 `196` 递增）
  - `versionName`：`"3.1.3"`（由 `"3.1.2"` 递增）
  - 构建变体：`github`（`me.rerere.rikkahub`，50% 缩放）、`coexist`（`me.rerere.rikkahub.coexist`，50% 手表微缩共存版）、`coexistNormal`（`me.rerere.rikkahub.coexist.normal`，100% 手机原比例共存版）
- **Room 数据库版本**：`32` -> `33`（显式注册 `Migration_32_33`）
- **统一服务端协议版本**：`2.0.0`（`protocol_version = 2`）
- **核心变更摘要**：完成统一 Web & Sync 服务端鉴权隔离、路径穿越封堵、密钥脱敏、Web 全功能聊天/识图/文档解析修复，落地跨端 Sync V2 修订增量同步与双副本冲突中心，修复 Android 同步配置持久化与生图级联误删缺陷，补齐 32/64 位双架构原生库并交付三变体正式安装包。
