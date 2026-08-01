# ExecPlan 0001：建立多来源本地账本基础

- 状态：进行中
- 所有者：项目维护者
- 最后核验：2026-08-02
- 事实来源：MVP、架构、当前 Gradle 工程、构建与测试结果

## 目的与用户可见结果

建立一个不以支付宝为默认中心的 Android 基线：用户能手工创建账户/期初余额，并让支付宝、微信和首批银行的脱敏样本通过统一 `RawEvent -> Draft -> Confirm -> Transaction/Entries` 纵向切片。此计划先冻结文档和验证门，再进入实现。

## 范围

- 仓库知识系统、产品规格、ADR、质量基线和文档检查。
- Android/Gradle 模块骨架与 provider-neutral 领域模型。
- Material 3 交互语义、克制的构成主义/太空时代编辑视觉、竖屏总览页与草稿复核代码骨架。
- 手工录入纵向切片。
- 三类来源适配器契约和样本发现框架。
- 分录平衡、幂等、日志脱敏的基础测试。

不包含完整报表、云同步、全部银行、OCR、短信箱或无障碍自动化。

## 上下文与导航

- 产品边界：[../../product-specs/mvp.md](../../product-specs/mvp.md)
- 架构地图：[../../../ARCHITECTURE.md](../../../ARCHITECTURE.md)
- 领域模型：[../../design-docs/domain-model.md](../../design-docs/domain-model.md)
- 来源契约：[../../design-docs/ingestion-and-source-adapters.md](../../design-docs/ingestion-and-source-adapters.md)
- 实际缺口：[../../QUALITY_SCORE.md](../../QUALITY_SCORE.md)
- UI 设计系统：[../../design-docs/ui-design-system.md](../../design-docs/ui-design-system.md)
- Android 设备兼容：[../../design-docs/android-device-compatibility.md](../../design-docs/android-device-compatibility.md)
- Android 技术基线：[../../decisions/0004-android-compose-baseline.md](../../decisions/0004-android-compose-baseline.md)
- 手工账本首片决策：[../../decisions/0005-manual-ledger-first-slice.md](../../decisions/0005-manual-ledger-first-slice.md)
- Android 入门路径：[../../development/android-getting-started.md](../../development/android-getting-started.md)

当前仓库在本计划开始时没有 Android 工程；只有外部设计报告。现在已有可构建的代码骨架，但不要把合成演示或契约骨架误读为真实来源支持。

## 进度

- [x] 2026-07-19 - 阅读 OpenAI Harness Engineering 文章并提取仓库内知识管理原则。
- [x] 2026-07-19 - 提取并视觉核验 14 页原设计报告。
- [x] 2026-07-19 - 将支付宝、微信支付、银行确认为三类一等来源并记录 ADR。
- [x] 2026-07-19 - 建立 AGENTS 地图、产品/设计规格、质量基线、来源研究与文档检查入口。
- [x] 2026-07-19 - 确定 Material 3 UI 系统、三类首批测试设备及总览/草稿复核页面规范。
- [x] 2026-07-19 - 选择 API 26–36、JDK 17、稳定 Gradle/AGP/Kotlin/Compose/Room 基线并记录 ADR-0004。
- [x] 2026-07-19 - 将地区/OEM 风险、三台设备清单字段和可执行真机门写入独立兼容文档；这只完成测试设计，不代表真机通过。
- [x] 2026-07-19 - 在工作区隔离目录安装 JDK 17、Android SDK 36 和 Gradle 8.13，并生成带 SHA-256 校验的 Gradle Wrapper。
- [x] 2026-07-19 - 初始化 Android 多模块骨架，落实 `Android/UI -> Application -> Domain <- Data/Source Adapters` 的依赖方向。
- [x] 2026-07-19 - 实现 Money、账本候选/分录、平衡与交易类型语义校验，并加入纯 Kotlin 单元测试。
- [x] 2026-07-19 - 实现不可变 RawEvent、时间精度、作用域外部引用和 Room schema v1 骨架；真实适配器与落库流水线仍未实现。
- [x] 2026-07-19 - 实现 Material 3 总览与草稿复核合成切片、金额遮罩、明暗主题、200% 字体 Preview，以及英文/简中/繁中资源基线。
- [x] 2026-07-19 - 完成代码审查与简化审查；修复账务语义绕过、证据丢失、金额遮罩泄露、复核流程、备份排除、可访问性和命名问题。
- [x] 2026-07-19 - 运行 `test lint assembleDebug`：构建、单测、Lint 和 Debug APK 打包通过；Lint 为 0 errors、10 个已知非阻断警告。
- [x] 2026-07-19 - 根据用户可用性反馈重新冻结 C 端视觉：主操作蓝 `#123B68`、红楔品牌信号 `#B92724`、纸张/宇宙深蓝/太阳金色板，以及竖屏单列主路径；馆藏只作形式研究，不复制图像或政治语义。
- [x] 2026-07-19 - 将 target 36 的 Android 16 `sw>=600dp` 方向限制、非竖屏可达性兜底、`ORIENT-01` 和 MuMu 证据边界写入唯一事实来源。
- [x] 2026-07-19 - 将新视觉令牌、竖屏 Manifest、总览/复核页面与底部导航落实到 Compose；在 MuMu 以约 411dp 逻辑宽度验证明暗主题、200% 字体、金额遮罩、账户选择与确认闭环，并完成仅限 API 32 模拟器的 `ORIENT-01` 冒烟。
- [x] 2026-07-19 - 对照产品、架构、来源、账务、可靠性文档盘点运行时代码，确认当前仍是“真实领域校验 + 来源/Room 骨架 + 合成 UI”，生产入口尚无数据库驱动的纵向流水线。
- [x] 2026-07-19 - 建立 `core:domain` 端口和 CNY-only 首片模型，明确手工输入不是 RawEvent、隐藏损益/权益账户只承担复式记账且不显示为用户资金账户。
- [x] 2026-07-19 - 将 Room 升级到 schema v2，加入 Account、Draft、Transaction、Entry、AuditEvent、command receipt、正式 v1→v2 migration 声明与原子仓储；禁止 destructive fallback。迁移/事务自动化仍由下一项验收。
- [x] 2026-07-19 - 接通创建账户/期初余额 → 手工 Draft → 选择真实账户 → 平衡确认 → 数据库驱动 Overview/账户/流水 → void 并恢复 Draft；生产状态不再依赖 synthetic 数据。完整构建和 MuMu 重启持久化仍由下一项验收。
- [x] 2026-07-19 - 为应用用例、幂等确认、状态转换和 UI 关键路径补测试；在 MuMu 完成账户 → 草稿 → 确认 → 强停冷启持久化 → void 恢复草稿的竖屏端到端验证；随后使用 `code-review` 技能审查并修复支出符号、后台隐私、陈旧快照、稳定命令 ID、坏行静默丢弃、硬阻塞计数、时间戳指纹、发生时间绑定和重名约束分类问题。
- [x] 2026-07-19 - 重跑审查修复后的全仓 `test lint assembleDebug`，506 个任务成功；随后在 MuMu API 32 上运行 `:data:local:connectedDebugAndroidTest`，4 个 Room migration/repository 测试全部通过。
- [x] 2026-07-25 - 建立 provider-neutral 的证据读取、RawEvent 冲突语义、解析器注册、版本化 ParseAttempt、待复核 Draft proposal 与 Draft evidence 管道；Room 升到 schema v3，并保留 v1→v2→v3 正式迁移。
- [x] 2026-07-25 - 接入 Android `ACTION_SEND text/plain` 通用入口：64 KiB/严格 UTF-8、app-private no-backup 证据、哈希校验、provider-unverified parser、用户补全、可能重复提示、二次确认后的已审计忽略和按 command 消费 Intent；不提升支付宝、微信或银行支持状态。
- [x] 2026-07-25 - 使用 `code-review` 复查来源切片，补齐 RawEvent/ParseAttempt/Proposal/Draft evidence 跨表失败关闭、目标 Draft 写路径校验、并发/过期分享结果隔离、恶意 extra 解包保护、空白/NUL/畸形 Unicode 落盘前拒绝、可操作错误、重复观察、忽略重放与二次确认。
- [x] 2026-07-25 - 完整 `test`、`lint`、`assembleDebug` 分别通过；MuMu API 32 上 18 个 Room/证据/来源 instrumentation 测试通过，并手工验证冷/热分享、竖屏复核、不回显原文、重复警告、忽略二次确认和 Intent 消费。
- [x] 2026-07-25 - 接受 ADR-0007 并实现来源载荷生命周期：Room v4、`AVAILABLE -> CLEAR_PENDING -> CLEARED` 两阶段清除、7/30/90 天或永久保留、16 MiB/512 份已跟踪预算、未知大小测量、20 项 keyset 分页和竖屏设置入口；待复核证据不被自动清理，已完成 Draft/provenance 保留。
- [x] 2026-07-25 - 为删除失败重试、缺失文件、保留/容量、待复核保护、v3→v4 migration、命令重放/碰撞、分页、文件测量/删除、损坏状态和已完成 provenance 保留补单元/Room instrumentation；最终 MuMu API 32 的 24 个设备测试通过。
- [x] 2026-07-25 - 使用 `code-review` 复核生命周期切片，修复超过批次上限时无法直接恢复目标清除工作、同步/异步完整性检查不一致、非可用状态仍可补写大小和新分享后设置页历史陈旧；最终组合 `test lint assembleDebug` 的 516 个任务成功。
- [x] 2026-07-25 - 在 MuMu 覆盖安装保留旧数据，手工验证 v3→v4 升级、竖屏设置、无原文回显、即时历史刷新、7/30 天策略切换、待复核二次确认清除和 `CLEARED` 结构化历史展示；测试后恢复默认 30 天。
- [x] 2026-07-25 - 接受 ADR-0008 并关闭 TD-013 的核心窗口：Room v5 在文件写入前登记 5 分钟租约，RawEvent/生命周期事务原子消费登记；启动维护 CAS 接管到期租约，并以 10 分钟年龄门、4096 项检查/512 项认领上限回收旧版无登记孤儿。陈旧租约、文件碰撞、`CLEARED` 残留和磁盘数据库关闭/重开均有回归。
- [x] 2026-07-25 - 在 `S24U-HK` 真机以 Debug APK 完成受限应用级冒烟：安装、两次冷启动、繁中竖屏静态 UI、合成 `ACTION_SEND text/plain` 的 provider-unverified 覆核与二次确认忽略；未读取支付 App 内容，未将此结果计为任何真实来源或完整真机用例通过。
- [x] 2026-07-26 - 实现 provider-neutral 的 SAF 文本文件入口：仅用户显式选择、立即有界复制到现有 app-private 证据链、以 `GENERIC/STATEMENT_IMPORT` 进入人工复核；不持久化 URI、不猜 provider/交易字段，也不提升任何来源支持状态。用户显式映射 CSV/TSV 的多行路径由 ExecPlan 0003 独立管理。
- [ ] 补大量/恶意 Intent、自动化 Compose 清除确认，以及真实 Android 系统强杀切点/新旧 command 乱序恢复测试。
- [ ] 确认国行小米具体型号和系统构建，为三台设备完成清单；若均为高端设备，再补一台低内存中端设备。
- [ ] 在港版 S24 Ultra、国行 S24 Ultra 和已建档国行小米上执行兼容文档的首批必测用例并保存脱敏证据。
- [x] 2026-08-01 - 以本机私有真实通知研究出支付宝、微信支付与招商银行的首批窄范围，并只把 5 条脱敏 fixture/route 提交到仓库；其他银行按项目负责人决定延期，原始样本继续留在 Git 外。
- [x] 2026-08-01 - 将通知 callback 后的共享 transport、不可变 RawEvent、ParseAttempt、proposal 与 Draft 纵向流水线接入生产注册表；5 条 route 默认关闭且仍为 `FALLBACK_REQUIRED`，因为真实系统 callback/OEM 存活与发布资源门未完成。
- [x] 2026-08-01 - 建立生产注册表回放测试，分别把支付宝 3 条、微信 1 条和招商银行 1 条脱敏通知从目录/元数据门送到待复核草稿；未知格式失败关闭，不把回放冒充真机 callback。
- [x] 2026-07-30 - 建立 GitHub Actions 文档 CI，在 push/PR 上运行 `scripts/check-docs.ps1`。
- [x] 2026-07-31 - 建立架构依赖/敏感边界 CI、本地 CMD 聚合门和正反例；生成器维护 Gradle 依赖、Room schema 版本、源 Manifest 与测试源码事实。真实 provider 支持矩阵仍须由注册表和脱敏回放证据另行生成，不得手工伪造。

## 意外发现

- 原报告的底层账本方向可复用，但全部具体解析、里程碑与样本策略以支付宝为中心，不能直接作为 MVP 范围。
- 支付/银行个人数据没有可假设的统一公共 API；商户账单 API 不适用。
- 通知监听是尽力入口，官方用户账单文件才是历史补齐和对账的主要证据之一。
- 原报告的 4–6 周估计只适合支付宝原型，不适合生产级三来源 MVP。
- 当前机器只有 Java 8 与 Unity 附带的 JDK 11/API 35 SDK；没有 Android Studio、独立 SDK、Gradle Wrapper 或依赖缓存，首次构建必须先建立可重复工具链。
- 设计检索的自动组合偏向玻璃拟态/暗色/超大标题，和本地可信账本不符；只采用其 Material 3、金融数据层级、无障碍和克制动效规则，并记录明确反模式。
- 两台 S24 Ultra 不能代表地区固件行为相同，也不能提供低内存覆盖；“国行小米”在型号未知前不能代表 HyperOS 或任何性能档。
- 预选的 Compose BOM `2026.04.00` 并不存在；核对 Google Maven 元数据后改为可解析且与本基线兼容的 `2026.03.01`，不得凭年月猜版本号。
- 手工复制 Room schema 与并行 KSP 任务存在竞态；改用 Room Gradle 插件的正式 schema directory 后，schema v1 可重复生成。
- Android Lint 当前剩余 18 个非阻断警告：稳定工具/依赖版本更新、明确选择的手机竖屏策略，以及 adaptive icon 的 `mipmap-anydpi-v26` 目录提示。尝试按目录提示移到 `mipmap-anydpi` 会让 AAPT 无法链接 adaptive icon；资源已恢复到 v26 目录并以成功构建为准，升级/抑制应作为独立验证任务。
- 初版 Material 3 + 通用财务卡片虽满足结构和无障碍基线，但视觉过于接近企业后台，且未请求手机竖屏；C 端产品需要在不牺牲语义的前提下建立更鲜明、克制的编辑构图。
- 项目 target 36 时，Android 16 在 `sw>=600dp` 环境默认忽略方向、可调整大小和宽高比限制；Manifest 的 `portrait` 只能作为手机主路径请求，不能写成跨设备强制承诺。
- 当前 MuMu 镜像是 API 32、`x86_64`，虽上报 `SM-S9280`，但不含可用来证明国行 S24 Ultra、One UI、ARM 或 Android 16 行为的证据。
- 200% 字体实测表明余额指标和交易需要纵排，来源状态徽标也必须移到说明下方；只依赖普通字号 Preview 无法发现横向徽标对正文的挤压。
- 本轮接线前，代码与文档成熟度存在三层断裂：`LedgerValidator` 是有测试的真实实现，来源契约和 RawEvent Room 表只是孤立骨架，总览/复核生产入口完全依赖 synthetic 与内存布尔值；这促成了当前手工闭环，但后续支持声明仍必须逐层给证据，不能因页面能点就升级。
- 首个真实切片不能先伪造支付宝/微信/银行适配器：缺少真实脱敏样本时，账户与手工录入是唯一既能端到端验证、又不虚构来源能力的入口。
- 手工闭环需要独立的全局 command receipt：只靠实体主键不能区分同命令安全重试与“同 ID、不同请求”的碰撞；回执只存规范请求指纹，不复制敏感原文。
- 撤销若删除交易会破坏审计和幂等连续性；Room v2 采用 `VOIDED` 状态、余额只汇总 `ACTIVE` Entries，并把原 Draft 恢复为 `WAITING_USER`。
- `code-review` 发现生产映射层用 `mapNotNull` 会把损坏账务行静默丢掉，币种不匹配还会发布部分余额；账务快照因此改为失败关闭，映射失败清空陈旧 UI 快照并显示错误状态。
- 服务生成的时间戳不能进入创建账户/草稿的幂等指纹，否则响应丢失后的同命令重试会被误报冲突；稳定业务字段留在指纹中，时间一致性由 Draft/Transaction 领域边界单独校验。
- 只在 `onStop` 隐藏金额不足以保护最近任务缩略图；当前使用 `FLAG_SECURE` 配合无敏感内容的整页隐私帘，并在离开前台时重新遮罩金额。
- RawEvent 现有骨架还不能驱动解析：解析器只有不透明字符串引用却没有受控 payload reader，DAO 的 `INSERT IGNORE` 也会把同 ID、不同内容的碰撞静默吞掉；来源接入必须先补不可变仓储、结构化证据定位和封闭诊断码。
- 在缺少真实脱敏样本与权限决策时，最小诚实的用户入口是 Android Sharesheet 的显式 `text/plain` 分享；它只能形成 `GENERIC/SHARE_TEXT` 待复核证据，支付宝、微信和银行仍保持 `FALLBACK_REQUIRED`。
- exported `ACTION_SEND` Activity 无法证明用户手势或发送 App；“用户主动分享”是产品路径，不是可信来源身份。载荷必须继续按任意第三方 App 可构造的不可信输入处理。
- 只检查外键存在不足以保护来源链：Proposal 可以被重接到另一个 ParseAttempt/RawEvent，Draft evidence 也可能字段一致但语义归属错误；状态流必须全局验证整条链，目标写路径也要在事务前失败关闭。
- 新建 Room 数据库不会执行 migration 中的默认策略插入；单例保留策略必须同时由 `RoomDatabase.Callback.onCreate` 初始化。若在 `onOpen` 无条件补行会掩盖运行期损坏，因此只在首次建库和正式 v3→v4 migration 写入。
- “文件删除成功”与“数据库声称已清除”不能是同一步假设；先持久化 `CLEAR_PENDING`，再删精确 payload，最后写 `CLEARED`，才能让崩溃和 I/O 失败保持可恢复。
- 预提交文件所有权不能只靠文件年龄推断。Room v5 先登记租约、RawEvent 事务消费、恢复者 CAS 接管后，16 MiB/512 份预算可同时覆盖已提交与暂存行；遗留目录扫描仍必须有界，截断时拒绝新输入，不能宣传为对任意文件系统状态的绝对配额证明。
- Activity 重建和 `singleTop` 新 Intent 会让“处理完成后直接清空当前 Intent”产生竞态；结果事件必须携带 command ID，只能消费匹配的 Intent，且过期结果不得抢回当前复核 UI，忙碌状态也不能静默丢掉新分享。
- 证据概览 Flow 只刷新聚合计数，不会自动替换 keyset 历史页；MuMu 手工验收因此发现新分享已入库但列表仍指向旧首项。捕获、完成/忽略来源复核和证据操作成功后必须显式重载第一页，并用 generation 丢弃被取消请求的迟到结果。
- 相同证据哈希不等于相同经济事件。普通重复仍只显示“可能重复”且不自动合并；转账、还款、退款和 `FUNDED_BY` 已有可撤销、幂等的用户确认关系，低置信度跨来源事件继续留给人工复核。
- 来源证据与建议目前会持续累积。单独加入硬配额会在没有删除入口时最终锁死用户，因此保留、删除、容量和分页必须作为同一发布门设计。
- MuMu 的手工回归发现首条合成分享在关闭工作表后仍是待办，第二条触发了重复提示；两条随后均通过正式“忽略”动作清理，证明关闭与忽略语义必须保持不同。

## 决策日志

- 2026-07-19 - 采用短 `AGENTS.md` + 结构化 `docs/`，避免单一巨大说明文件。
- 2026-07-19 - 首期三类来源同级，但支持声明按来源/格式证据独立升级。
- 2026-07-19 - 不复制外部 PDF 到仓库；保存 SHA-256、提取结论和被推翻假设，避免二进制成为唯一事实来源。
- 2026-07-19 - 银行先做通用映射器和 provider 模板框架，具体银行由用户样本驱动。
- 2026-07-19 - 初版 UI 采用 Material 3 原生结构与克制财务密度；该视觉决定随后被同日的 C 端编辑视觉决定取代，Material 3 仅保留为交互与无障碍语义基线。
- 2026-07-19 - 初始工具链选择 AGP 8.13.2 + Gradle 8.13 + Kotlin 2.3.21，而不为追新采用 AGP 9/Room 3 预览组合。
- 2026-07-19 - 首轮使用手工依赖装配，等真实 Android 生命周期注入需求出现后再引入 Hilt。
- 2026-07-19 - UI 视觉规范与地区/OEM 兼容验收拆成两个唯一事实来源，避免用页面矩阵替代通知、后台和文件入口验证。
- 2026-07-19 - 默认资源使用英文，另提供 `zh-rCN` 与 `zh-rHK`；地区固件差异通过资源与真机矩阵验证，不用品牌条件分支硬编码 UI。
- 2026-07-19 - 代码简化以可读命名、合并等价分支和保留显式领域校验为准，不为表面行数引入 reducer 或过早抽象。
- 2026-07-19 - 视觉改为克制的构成主义与苏联太空时代编辑方法：红楔只作品牌信号，主操作保持蓝色，财务和错误语义不得依赖红楔；不复制政治标识或馆藏图像。
- 2026-07-19 - 当前手机版本请求 `portrait` 并保持单列底部导航；不使用 Android 16 临时兼容退出项，非竖屏/分屏/大屏只保证可达性和状态保存。
- 2026-07-19 - MuMu 只承担安装、布局和合成流程冒烟，任何上报型号都不能替代真实 Samsung/HyperOS 设备结论。
- 2026-07-19 - 首个真实账本切片只接受 CNY；总览不得跨币种 SQL 求和。多币种在汇率与换汇分录完成前保持显式未支持。
- 2026-07-19 - 手工录入建模为独立 `ManualIntent`/Draft 入口，不伪装成外部 RawEvent；未来通知和文件仍经不可变 RawEvent 汇入同一 Draft 状态机。
- 2026-07-19 - 增加隐藏的费用、收入与期初权益总账账户以满足复式记账外键和方向约束；它们不属于用户资金账户，不出现在账户选择或净资产列表。
- 2026-07-19 - 采用小型 `core:domain` 定义领域实体与仓储端口，保持 `UI -> Application -> Domain <- Data`；App 先手工装配 Room、仓储与 ViewModel，不引入 Hilt。
- 2026-07-19 - 接受 [ADR-0005](../../decisions/0005-manual-ledger-first-slice.md)：首片手工输入不伪造 RawEvent；只接受 CNY；使用三个隐藏系统账户、有符号负债、全局 command receipt、Room v1→v2 迁移和 void 撤销。
- 2026-07-19 - 来源接线先实现 provider-neutral evidence spine：解析器只接收受大小限制且已校验哈希的 `EvidenceInput`，RawEvent 同 ID 冲突必须失败关闭，ParseAttempt 按 parser/rule 版本追加，外部候选首版一律进入用户复核而不自动确认。
- 2026-07-25 - 接受 [ADR-0006](../../decisions/0006-provider-neutral-shared-text-evidence-spine.md)：通用分享文本只标记为 `GENERIC/SHARE_TEXT`，不猜 provider/金额/方向/账户；重复只提示，忽略保留审计证据，Room v3 保存来源证据链。
- 2026-07-25 - 接受 [ADR-0007](../../decisions/0007-source-evidence-lifecycle-and-bounded-storage.md)：RawEvent 继续不可变，载荷状态独立持久化；默认保留 30 天且可选 7/30/90/永久，自动清理只处理无待复核项；清除保留结构化事实链并采用两阶段审计。
- 2026-07-25 - 分享 Activity 作为不可信外部边界；不因产品上使用 Sharesheet 就声称能够证明发送 App 或用户手势。
- 2026-07-25 - 不用孤立硬配额暂时掩盖累积风险；先把用户删除、保留期限、容量、分页与恢复语义设计完整，再升级发布支持。

## 实施步骤

### 里程碑 A：Android 与领域基线

已创建 Kotlin/Compose 工程和 `core:model`、`core:domain`、`core:ledger`、`application`、`data:local`、`source:contract` 等模块，并实现手工账户/期初余额/草稿/确认/撤销闭环。当前 Room v11、S24U-HK 55/55 Room suite 与受限应用/OCR instrumentation 已通过；完整设备矩阵和真实系统强杀仍是独立发布门。

### 里程碑 A2：来源证据骨架

已完成 opaque payload ID、SHA-256 证据哈希、结构化 locator、封闭安全诊断和 parser identity；实现 RawEvent 不可变仓储的安全重放/碰撞/重复观察语义、`SourceIngestionService`、通用文本/文件/OCR入口和 5 条默认关闭的通知 route。Room v11 保存 ParseAttempt、来源建议、Draft evidence、通知观察摘要与关联关系，并通过跨表检查失败关闭。这些证据只证明本地编排和窄范围回放，不生成“已发布支持支付宝/微信/银行”的产品声明。

### 里程碑 B：样本与适配器试验

已建立不包含真实原文/账号的脱敏 fixture 规范，并以真实研究确定支付宝支出、支付宝余额收款、支付宝基金确认、微信付款和招商银行快捷退款 5 条窄 route；生产注册表回放验证身份门、解析版本、错误分类、持久观察去重和待复核草稿。文件格式与未列事件仍未知并安全失败。

### 里程碑 C：关联纵向切片

已用合成/脱敏证据实现转账、信用卡还款、退款和 `FUNDED_BY` 的候选、解释、用户确认、幂等与撤销。系统不自动合并低置信度重复；无法识别的事件继续由用户手工补录。

### 里程碑 D：工程反馈回路

加入单元/性质/Room/Android 测试、敏感日志检查、文档 lint、Room schema 和支持矩阵生成。每个失败信息给出可操作修复路径。

按兼容文档先完成三台设备建档，再分别执行安装、通知权限分层、生命周期、省电、SAF、无障碍、方向与隐私用例。MuMu 先跑竖屏布局冒烟，但不计入真机完成率。真机原始证据不进入 Git；仓库只记录脱敏结果摘要和失败编号。

## 具体命令

在仓库根目录通过 CMD 运行统一检查：

```bat
cmd.exe /d /s /c scripts\android.cmd --no-daemon --console=plain test lint assembleDebug
cmd.exe /d /s /c powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-docs.ps1
cmd.exe /d /s /c git diff --check
```

2026-07-25 最终结果：组合 `test lint assembleDebug` 成功（516 个任务，37 个实际执行）；MuMu API 32 上 `:data:local:connectedDebugAndroidTest` 的 24 个测试全部通过；文档结构、元数据、相对链接和三来源约束检查通过。覆盖安装保留旧数据后的竖屏手工回归验证了即时历史刷新、7/30 天策略切换、待复核清除确认和结构化历史保留。

## 验证与验收

- 文档检查通过，所有持久文档可从索引导航，无失效相对链接。
- 三类来源的目标范围不再使用“支付宝优先/微信银行以后再说”的措辞。
- 领域模块不依赖 Android；来源适配器不能直接写 Room/正式账本。
- 手工交易和合成跨来源消费均生成平衡、幂等且可撤销的结果。
- 每类来源至少一个脱敏样本安全到达 Draft；这只证明试验，不自动升级为正式支持。
- 测试与日志不包含真实财务信息。
- 港版/国行 S24 Ultra 结果分别留档；国行小米型号与系统构建尚未确认，首批真机用例有脱敏证据后才能计入。任何未执行项保持未通过。
- 手机全屏竖屏主路径通过 `ORIENT-01`；方向请求被忽略时单列兜底仍可滚动、主操作可达且状态不丢失，不把该结果包装成横屏主视觉支持。
- 若首批三台不能代表低内存中端设备，发布前覆盖项明确保持未完成，不以品牌或模拟器替代。

## 幂等、回滚与恢复

文档和工程初始化均使用增量提交。数据库/导入操作在模型确定后使用事务和稳定幂等键。任何迁移必须在复制数据库上验证，失败不替换用户当前数据。样本发现阶段不持久化真实原件到 Git。

## 结果与复盘

截至 2026-08-01，基础已扩展为 Room v11 的受限 CNY/USD 账本、普通账户余额快照、CNY 投资持仓、通用分享/SAF 文本证据、显式映射 CSV/TSV 行、单张 PNG 收据、用户触发 OCR、5 条默认关闭的生产通知 route，以及用户确认的转账/还款/退款/`FUNDED_BY` 对账。最终工作树已有完整 JVM/Lint/Debug/双 ABI 未签名 Release/三组 AndroidTest APK 构建证据；S24U-HK 的 app 16/16 与 Room 55/55 已覆盖 v10→v11、通知目录/仓储、余额快照、投资和关联路径，OCR 另有合成三语、空间链、11 张私有真实过程页与简中合成状态页证据。编译和定向回放仍不等于真实系统 callback 或完整设备发布验收。

计划仍保持进行中：5 条 provider route 的真实系统 callback/OEM 存活、真实简中微信页、自动化 Compose/真实系统强杀、资源门和三台完整真实设备证据尚未完成；其他银行与事件按当前范围延期。一般重复与低置信度跨 provider 关系有意保持用户确认或手工补录，不再把“自动合并”列为基础可用性的必需条件。现有脱敏回放和用户确认对账不能替代三类来源的发布支持。
