# ExecPlan 0002：本地资源预算下的三轨采集

- 状态：进行中
- 所有者：项目维护者
- 最后核验：2026-07-26
- 事实来源：项目负责人 2026-07-25 的自动草稿与本地负担要求、ADR-0001、ADR-0009、ADR-0010、ADR-0011、当前 Android/Room 工程

## 目的与用户可见结果

在不增加云端后端、Root、私有目录读取、全天截屏/录屏或周期性扫描的前提下，为通知、只读支付结果页和用户触发单次凭证建立独立采集路径。用户能看见每条路径的权限、覆盖范围、最后一次回调与资源边界；任何自动识别最多形成可编辑的待复核项，绝不直接正式入账。

第一交付实现低功耗通知基础、显著本地声明和单次 PNG 收据回退：默认没有来源模板，因此不会读取任何支付 App 的正文，也不会声称支付宝、微信或银行已接入。它建立以后可由脱敏样本驱动的指定 App 模板安全接入。用户可用 Android 正常截图后经系统 Sharesheet 分享一张 PNG 给 Bill，形成仅本地、无 OCR、无字段猜测的手工复核项。后续磁贴/Photo Picker OCR 原型属于独立的 [ExecPlan 0004](0004-quick-tile-local-ocr-capture.md)，当前未通过隐私发布门；它不改变本计划 Sharesheet PNG 路径的无 OCR 边界。

## 范围与非目标

包含：

- 把设备端电量、CPU、内存、存储、权限和更新负担写成可测预算。
- 一个很薄的 Android `NotificationListenerService`、纯 Kotlin 元数据门禁、通知证据信封、受控入库和来源复核展示。
- 只在包名/渠道/类别选中候选模板后读取最小通知字段，再在内存确认正文模板；未知模板不持久化，必要时仅以不含原文的诊断计数呈现。
- 合成通知、更新通知、重启幂等、容量、敏感日志和 Manifest 的自动化验证。
- 用户显式单张 PNG 收据的临时 URI 读取、结构校验、私有证据暂存、内存擦除与手工复核；不解码像素、不预览、不运行 OCR。
- 三台目标真机上的资源对照测试设计；真实支付 App 内容仅在项目负责人明确授权后测试。

不包含：

- 真实支付宝、微信或银行模板、万能金额正则、自动确认/过账、账户余额推断或通知历史补齐。
- 直接读取短信箱、读取其他 App 私有目录、Root、抓包、任何云端解析/遥测/热更新。
- 常驻截图、MediaProjection、自动 UI 操作、轮询 OCR 或默认电池优化豁免。
- 未完成独立隐私、商店政策、图像证据生命周期和真机峰值测试前，把任何 Accessibility/OCR 实现标为正式支付来源能力。后续开发原型的状态与发布门由 ExecPlan 0004 维护。

## 上下文与仓库导航

- 本地优先边界：[../../../README.md](../../../README.md)、[../../../ARCHITECTURE.md](../../../ARCHITECTURE.md)
- 采集契约：[../../design-docs/ingestion-and-source-adapters.md](../../design-docs/ingestion-and-source-adapters.md)
- 覆盖与来源状态：[../../product-specs/source-coverage.md](../../product-specs/source-coverage.md)
- 安全与删除：[../../SECURITY.md](../../SECURITY.md)
- 可靠性与设备矩阵：[../../RELIABILITY.md](../../RELIABILITY.md)、[../../design-docs/android-device-compatibility.md](../../design-docs/android-device-compatibility.md)
- 既有通知/凭证决定：[../../decisions/0010-notification-first-capture-and-single-receipt-fallback.md](../../decisions/0010-notification-first-capture-and-single-receipt-fallback.md)
- 本计划的资源约束：[../../decisions/0011-local-resource-budget-first-capture.md](../../decisions/0011-local-resource-budget-first-capture.md)
- 当前实现主干：`source:contract`、`source:pipeline`、`source:review-contract`、`source:generic-share-text`、`source:generic-receipt-image`、`application`、`data:local` 和 `app`。

现有 `CaptureMethod.NOTIFICATION`、通知字段 locator 与来源证据暂存已存在，但没有真实 provider 模板。`SourceIngestionService` 会先保存 RawEvent，因此 listener 不得在未知模板时调用它。Room v6 现在额外保存通知观察摘要：它只含安装范围 HMAC 摘要、opaque command/lease 和状态，不含 Android notification key、包名、频道或正文；过期租约会复用原 command，避免进程死亡后重复创建 RawEvent。

## 进度

- [x] 2026-07-25 - 记录通知、只读结果页和单次截图的产品边界；拒绝 Root、全天截屏、默认短信箱读取和手工查完整账单作为主路径。
- [x] 2026-07-25 - 根据项目负责人反馈把本地资源负担设为一等验收项，而非照抄具有服务端的竞品策略；接受 ADR-0011。
- [x] 2026-07-25 - 完成 Android/竞品公开资料的初步研究：通知是事件回调；无通知的前台结果页可能需独立、默认关闭的只读能力；截图只能是用户动作后的单次补录。
- [x] 2026-07-25 - 建立 `source:generic-notification` 的受控通知信封、按包名/具体 Android 通知渠道/可选类别门禁和 JVM 回放测试；生产目录为空，包级模板被拒绝。
- [x] 2026-07-25 - 在 `application` 接通通知证据的暂存、RawEvent、ParseAttempt 与来源待复核展示；通用 parser 不猜金额或来源，绝不自动过账。
- [x] 2026-07-25 - 在 `app` 增加最薄的 listener、Manifest 声明、启动时“纯本地/不走网络”大字声明和三轨权限教程；空目录与非匹配元数据的单测证明不读取 `extras`。结果页服务没有屏幕内容、截图或手势能力，当前不能启用采集或诱导授权。
- [x] 2026-07-25 - 修复既有 `STATEMENT_IMPORT` 仓储测试 fixture，使其不再绕过暂存租约前提；通知专用单测覆盖重试、证据碰撞和容量拒绝。
- [x] 2026-07-26 - 完成 Room v6 通知观察租约、迁移测试与有界 listener 队列：同一系统实例的更新/进程死亡恢复复用原 command；listener 只有一个容量 16 的 IO 消费者，满队列直接丢弃本次内存事件而不创建无限协程、重试任务或保活。已捕获摘要与失效超过 90 天的活动租约只在后续候选回调中每类至多清理 64 条，不设后台维护。当前生产模板仍为空，故这条路径不会读取正文或创建观察记录。
- [x] 2026-07-26 - 在设置教程中增加不含来源/正文的进程内通知健康状态：空目录、队列满跳过次数与失败次数均可见；单测证明只暴露安全聚合计数。
- [x] 2026-07-26 - 接通用户明确分享的一张 PNG 收据截图：`ACTION_SEND image/png` 只使用当次 `content://` 授权，有界读取最多 4 MiB，验证 PNG 签名/IHDR/尺寸/分块 CRC/IDAT/IEND 后进入现有私有证据、RawEvent、ParseAttempt 和手工复核链。临时字节在完成或失败后擦除；不保存 URI/文件名、不申请相册权限、不解码/预览像素、不运行 OCR 或推断财务字段。JVM parser、application 与 ViewModel 回归已覆盖，真实 Sharesheet/真机仍待验收。
- [x] 2026-07-26 - 将空目录重构为静态 `VerifiedNotificationRoute` 目录、默认关闭的本地 route enablement，以及 `route -> source identity -> RawEvent -> parser -> source review` 的完整安全链；类别（包括 null）精确匹配，ingress 拒绝同值 lookalike route、在写入前再次检查开关，复核只显示安全标签。生产目录继续为空，不能以此宣称任何 provider 已支持。已由 `:source:generic-notification:test :application:test :app:testDebugUnitTest :data:local:assembleDebugAndroidTest :feature:review:compileDebugKotlin` 验证。
- [ ] 首个非空 route catalog 前 - 在设置页接通只显示安全标签的 route 选择器与“目录存在但尚未开启”健康状态；不得把 app-private enablement 基础类误称为当前用户可启用的真实来源能力。
- [ ] 2026-07-26 - 补真实系统 callback 更新回放、容量和敏感日志回归；没有这组设备验证时不得开放真实 provider 模板或把 listener 声称为完整账单覆盖。
- [ ] 待项目负责人明确授权真实内容范围后 - 在 S24U-HK、国行 S24U、已建档小米执行通知资源基线；记录 OEM 回调存活与可选省电设置，不能用模拟器替代。
- [ ] 待通知基线、商店政策、显著告知和脱敏页面样本齐全后 - 决定是否实施独立只读结果页服务；已实现的 PNG 手工复核保持无 OCR。磁贴/Photo Picker OCR 已在 ExecPlan 0004 形成开发原型，但替换引擎、生命周期与真机峰值门未完成前仍不得发布。

## 意外发现

- 项目负责人报告：其当前支付宝 App 内扫码支付可出现成功页面却没有系统通知；银行也未必产生通知。支付宝“信息助手”可显示动账但不是可后台读取的公开数据接口。该观察尚未作为跨版本事实。
- Notification Listener 没有可作为隐私边界的“只订阅这些包正文”模式；应用必须在自己的代码中先按包名、渠道、类别和模板门禁，且默认空目录最安全。
- 同一个系统通知可更新并再次触发回调；不能按金额或正文哈希把不同通知合并，也不能把 `onNotificationRemoved` 解释为退款或删账。
- 当前 `RoomRawEventRepository` 已要求 `NOTIFICATION` 有暂存租约；既有 `STATEMENT_IMPORT` 仓储测试 fixture 曾绕过该前提，现已改成非暂存 `SHARE_FILE` 观察测试；暂存行为由专门 staging 测试覆盖。

## 决策日志

- 2026-07-25 - 把市场产品只作为机制研究，不作为架构蓝图；Bill 的默认设计以纯本地资源与权限成本为准。
- 2026-07-25 - 将通知、结果页节点读取和截图拆成三个最小权限服务；禁止合并为一个“万能无障碍服务”。
- 2026-07-25 - 通知先做空模板/合成来源基础，不以没有样本的支付宝、微信或银行文字伪造支持。
- 2026-07-25 - 不给资源消耗预设虚假电量百分比；以目标设备的关闭/开启对照、无交易基线和固定回调序列决定开放范围。
- 2026-07-25 - 代码审查发现 UUID 命令不能充当系统通知更新/重启去重，且每条回调直接启动协程会在真实模板下失去背压；因此先以代码门关闭 runtime，直到持久观察迁移、有限队列和安全失败健康状态一起落地。
- 2026-07-25 - 代码审查发现空目录版本的系统授权 CTA 没有用户收益；教程保留权限路径说明，但不再直接跳转到通知或无障碍授权页。启动声明改为进程内会话状态，确保新进程重新展示而旋转不反复打断。
- 2026-07-26 - 通知观察以安装私有 HMAC(`StatusBarNotification.key`、post time) 生成 64 个十六进制字符的摘要，数据库只保存摘要、opaque command/lease、状态和时间；活动租约为两分钟，恢复必须复用原 command。已完成观察和失效超过 90 天的活动观察均只在后续候选回调中每类至多清理 64 条，避免长期无限增长。
- 2026-07-26 - listener 改为回调线程上的元数据门、有限字段复制和容量 16 的非阻塞队列；只保留一个 IO consumer。队列满时不排队、不重试、不写入任何正文，等待未来系统更新或用户的其他回退路径。
- 2026-07-26 - 设置页只显示当前进程的安全健康聚合：没有模板、队列满跳过次数或失败次数；不显示来源、通知正文、金额、时间戳或交易状态。空目录状态明确告知用户没有读取通知正文。
- 2026-07-26 - 单次收款凭证先采用系统 Sharesheet 的精确 PNG 入口，而不是相册权限、MediaProjection、读屏或 OCR。首版只验证有界文件结构并建立手工复核，不把图像内容解析成账务事实；这样补足无通知例外时不新增常态耗电。
- 2026-07-26 - 将 route ID 固定为 parser identity 的不透明 token；它只用于本地启用状态和安全展示键，包名、通知渠道、类别和正文仍只留在瞬时元数据门。类别即使为 null 也必须精确匹配，不能把 null 当通配符。route 被关闭或从新版目录移除时停止新采集，不删除既有证据、解析尝试或草稿。
- 2026-07-26 - route 默认关闭同时应用在 metadata gate 与 evidence ingress；关闭发生在队列/prepare 之后也不会落证据。开关写入是隐私撤销，成功返回前必须使用同步 `SharedPreferences.commit()` 完成磁盘写入；未来设置 UI 必须在后台调用并显示失败重试。目录还必须拒绝与既有通用通知 parser 相同的 `(sourceFamily, connectorId)`，避免真实 route 因 parser 歧义静默失败。

## 代码审查记录（2026-07-25）

- 已修复：通知字段编码按 field ordinal 规范化；意外仓储 `RuntimeException` 只返回封闭错误并保留租约恢复语义；无模板或无持久更新去重时不读取正文；未开放能力没有授权 CTA；ADR/索引不再误写“系统只把指定包回调给 listener”；Room v6 观察摘要、恢复 command、容量 16 单消费者队列和可见安全健康状态已补齐。
- 未开放而非已解决：真实系统 callback 更新回放、真机资源测试与真实 provider 样本。它们仍是首个 provider 模板的阻断项，不得以单元测试、通用 parser 或空目录表现替代。

## 第二轮代码审查记录（2026-07-26）

- 范围：`source:contract` 的观察租约契约、Room v6 实现/迁移、Android listener 的队列与 HMAC 边界、启动/设置教程中的健康状态。
- 结论：未发现可在当前空目录版本触发的高优先级重复入账、无界后台任务、原始 notification key/正文落盘或自动正式入账问题。审查后收紧了两个细节：观察实体和 hand-off 的 `toString()` 继续脱敏；listener 现在检查观察完成写入结果，不能把无效租约静默显示成成功；健康状态不会被之后一次成功回调抹去先前的队列满/失败计数。
- 保留风险：`StatusBarNotification` 的真实更新/repost 语义、OEM 回调存活和迁移的实际设备执行尚未得到用户授权的真机回放。Room Android 测试已编译，未在本轮连接或读取任何真实支付 App 内容。

## 第三轮代码审查记录（2026-07-26）

- 范围：有界队列、Room v6 观察租约的保留/恢复路径、失效观察清理、对应 Android 回归与文档承诺。
- 已修复：原设计只清理 `CAPTURED` 观察；若进程在成功 hand-off 前死亡且系统不再回调，失效 `ACTIVE` 观察会长期留在本地。现在候选回调中的同一小型维护事务会分别至多清理 64 条已捕获摘要和 64 条失效超过 90 天的活动租约；没有周期任务、闹钟、前台服务或通知正文扫描。新增回归证明较新的候选触发清理后，旧 observation ID 可以安全创建新的租约。
- 结论：未发现当前空模板版本可触发的 P0/P1 隐私泄漏、自动入账、无界队列或无界活动观察问题。`test lint assembleDebug :data:local:assembleDebugAndroidTest --console=plain` 成功（549 个任务）。
- 保留风险：观察标识采用 `StatusBarNotification.key` 与 `postTime` 的真实 update/repost 语义尚无真实 callback 回放；新的 Room Android 回归仅完成 APK 编译，尚未在设备上执行。首个真实模板前仍需项目负责人授权合成/真实内容范围、目标设备基线和 OEM 回调验收。

## 第四轮代码审查记录（2026-07-26）

- 范围：系统 Sharesheet 单次 PNG 收据入口、`content://` 临时读取、PNG 结构校验、来源证据/草稿链、手工复核提示与本地资源边界。
- 已修复：首版 PNG 的 CRC、终止分块和图像数据分块未完整验证，现已在最多 4 MiB 的有界字节内验证签名、IHDR、尺寸、颜色/位深、每个分块 CRC、非空 IDAT 与最终 IEND；无效、截断、篡改或尾随字节均失败关闭。图片校验和哈希原先可能在 ViewModel 的界面协程中执行，现改为单一后台串行 intake，临时字节无论成功、失败或取消都擦除。
- 结论：未发现当前 PNG 回退路径可触发的自动入账、相册广泛访问、URI/文件名持久化、像素解码/OCR、无限输入或主线程大图 CPU 工作。`test lint assembleDebug :data:local:assembleDebugAndroidTest --console=plain` 成功（553 个任务），Lint 通过。
- 保留风险：还没有在用户许可的设备上运行真实 Android Sharesheet 临时授权回归，且尚未执行 Room instrumentation；这条路径仍是 provider-unverified 的手工复核回退，不能据此声称支付宝、微信或银行自动入账。

## 第五轮代码审查记录（2026-07-26）

- 范围：静态 `VerifiedNotificationRoute` catalog、默认关闭的 app-private enablement、gate/ingress 双重开关、route provenance、parser 注册、复核安全标签及对应文案。
- 已修复：gate 与 ingress 的默认回调原先可能在调用方遗漏时放行，现默认关闭；关闭发生在 prepare 后仍会被 ingress 忽略并释放观察租约；SharedPreferences 由异步 `apply()` 改成串行、可确认的 `commit()`；目录拒绝与 `GenericNotificationParser` 的来源 tuple 冲突；fatal `Throwable` 不再被安全标签解析器吞掉；Android 通知使用权文案不再误称能开启单条 route。
- 结论：生产 catalog 仍为空，未知/关闭 route 不读取正文或落证据；已验证 route 身份才能进入 RawEvent，parser 仍只创建待复核工作。`:source:generic-notification:test :application:test :app:testDebugUnitTest :data:local:assembleDebugAndroidTest :feature:review:compileDebugKotlin --console=plain` 成功；Android test APK 只完成编译打包，未连接设备。
- 保留风险：首个非空 catalog 前仍须补只显示安全标签的 Bill 内 route 选择器、“有目录但未开启”健康状态，以及 SharedPreferences 跨实例/旧 route ID 的 Android 回归。真实 callback/OEM 行为和资源基线仍需本轮明确真机授权。

## 实施步骤

1. 新增纯 Kotlin 通知模块，定义没有 Android 类、没有包名原文持久化的 `NotificationEnvelope`、字段上限、模板版本和安全诊断。元数据门禁先用包名/渠道/类别选择候选，再接受惰性正文读取器；测试必须证明未命中元数据时不会访问正文，正文模板不匹配时也不会持久化。
2. 为合成通知目录实现 parser。只有具备金额、方向、完成状态和来源资金映射的、经样本验证的未来模板才有资格创建完整 Draft；首个通用目录仅创建来源待复核或诊断，不能猜测财务语义。
3. 在 `application` 以独立通知用例调用现有 app-private staging/RawEvent/解析链；命令和通知实例只保留不可逆摘要。为需要自动 materialize 的未来模板预留持久观察状态，而非依赖 listener 进程内内存。
4. 在 `app` 实现只做 Android 映射的 `NotificationListenerService`。它不读历史、不取消/点击/修改通知、不启动外部 UI、不建立前台服务；生产模板目录为空。
5. 让来源复核 UI 能呈现 `NOTIFICATION` 证据来源而不回显原文，并展示“此模板尚未验证/需补全”的安全状态。
6. 将只读结果页与截图 OCR 作为后续独立计划步骤：前者限制来源包和事件；现有截图回退只接收用户用系统 Sharesheet 明确交付的一张 PNG，不订阅窗口事件、读取节点、解码像素或运行 OCR。任一新增服务或 OCR 都需重新过隐私、资源和真机门。
7. 有用户授权后执行设备对照；若 OEM 仅在关闭电池优化后回调，记录为可选设备配置而非默认要求。任何真实内容只产出脱敏测试结论，不写入 Git、日志或对话摘要。
8. 当前迭代：新增静态 route catalog 与 app-private enablement；gate 在读取 extras 前同时要求目录命中、route 已启用、包名/频道/类别精确匹配。Prepared capture 把 route identity 交给 ingress，后者以 route 的 `SourceFamily` 和 connector 建立 RawEvent；同一 route 的 parser 验证 template/version 后仅生成待复核项。复核 UI 只由 opaque connector 映射本地安全标签，绝不显示包名、频道或正文。测试使用虚构 `fixture.*` route，生产目录保持空。

## 具体命令

所有命令在仓库根目录经 CMD 运行：

```bat
cmd.exe /d /s /c "scripts\android.cmd :source:generic-notification:test :application:test :app:testDebugUnitTest --console=plain"
cmd.exe /d /s /c "scripts\android.cmd :data:local:connectedDebugAndroidTest :app:connectedDebugAndroidTest --console=plain"
cmd.exe /d /s /c "scripts\android.cmd test lint assembleDebug --console=plain"
cmd.exe /d /s /c "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-docs.ps1"
cmd.exe /d /s /c "git diff --check"
```

真机资源测量只在用户明确允许并已连接对应设备后执行。测试确实需要真机时，Codex 必须先停止并弹出“请连接真机后回复已连接”的确认提示；只在收到本轮明确的已连接回复后才可执行任何 `adb`、安装或设备状态读取命令，历史授权或历史连接不构成本轮许可。命令、设备序列号、原始 `dumpsys` 输出和真实通知正文不得进入仓库；计划只保留设备型号/构建、步骤、脱敏指标与结论。

## 验证与验收

- 默认空目录、未知包、未知渠道、暂停来源和未知模板均不读取正文、不创建文件/RawEvent/日志/草稿。
- 命中合成模板的通知以 `CaptureMethod.NOTIFICATION` 进入不可变证据链，超限、NUL、畸形 UTF-8、缺关键字段和未知版本安全失败。
- 同通知实例的重复/更新不会生成两笔候选；不同通知实例的相同内容也不会被金额/正文误合并。listener 重启后的语义由持久化状态验证。
- `onNotificationRemoved` 不影响既有草稿或账本。
- 自动路径最多创建待复核 Draft，所有入账仍要满足既有平衡和确认状态机。
- 单次 PNG 分享只在用户动作后读取一个临时 `content://` 流，类型/大小/结构不符即不落库；成功也只建立无财务字段的手工复核建议，临时字节必须擦除，不能持久化 URI、文件名或相册授权。
- 没有周期 Job/Alarm、前台服务、主动通知历史扫描或持续唤醒锁；所有证据都遵从容量、保留、删除和敏感日志检查。
- 三台目标真机分别记录关闭/开启能力的空闲基线、固定回调序列和用户同意范围内的真实来源结果；无基线或发现异常持续消耗时，不把该路线开放给普通用户。

## 幂等、回滚与恢复

命中合成模板后，listener 先取得独立的持久观察租约，再写现有证据暂存与 RawEvent/解析链。进程死亡或通知更新时，过期观察租约会复用同一 command；成功复核 hand-off 后才标为 `CAPTURED`。观察表只含摘要，旧库通过 v5→v6 迁移创建空表；回放仍必须覆盖系统 callback 语义而不是仅验证 Room。

关闭某来源立即停止新捕获，但不删除已有证据；用户可用既有逐项清除/保留策略处理已存数据。移除服务或模板时，保留历史 RawEvent、解析版本和安全诊断，禁止把未确认信息补写为正式账务。若资源验收失败，回滚到关闭开关和手工/显式分享回退，不启动替代保活策略。

## 结果与复盘

尚未完成。当前已交付空模板通知证据基础、v6 持久观察去重、有界回调队列、来源待复核展示、显著本地/权限说明，以及单次 PNG 收据的本地手工复核回退；没有真实 provider 模板、读屏或真机资源数据。另有磁贴/Photo Picker OCR 开发原型，但它由 ExecPlan 0004 管理且尚未达到发布资格。完成时在此记录实际模块、迁移、合成/真机证据、测得的资源结果、未开放能力和对来源支持标签的影响。
