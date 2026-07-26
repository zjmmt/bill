# 可靠性、测试与可诊断性

- 状态：部分实现；账本、分享文本、空模板通知边界、持久观察去重与证据生命周期已有自动化，另有受限 S24U-HK 应用级冒烟；完整真机与发布门待完成
- 所有者：项目维护者
- 最后核验：2026-07-26
- 事实来源：当前领域/Application/Room/来源实现、自动化与 MuMu 结果、领域设计与本地优先产品承诺、ADR-0010、ADR-0011

## 正确性不变量

- 金额使用整数最小货币单位与显式币种，禁止以二进制浮点保存账务金额。
- 同币种交易的正式分录必须平衡；不平衡数据只能停留在草稿/诊断状态。
- `RawEvent` 不可变，并带内容摘要、来源、采集时间和适配器版本。
- 导入、重试和重放必须幂等；相同原始证据不会重复生成正式经济事件。
- 用户已确认字段优先于后续低可信自动推断；覆盖必须说明原因并可撤销。
- 数据库迁移要么完整成功，要么回滚到可打开的旧状态。
- 相同 command ID 只有在操作、目标和规范请求指纹一致时才视为安全重试；不同请求复用同一 ID 必须失败为冲突。
- 撤销保留交易、Entries 和审计，把交易标记为 `VOIDED`；余额只计算 `ACTIVE` 交易，来源 Draft 恢复待复核。
- RawEvent、ParseAttempt、来源建议、Draft evidence 和 Draft 的 ID/语义链必须一致；损坏链失败关闭，不能发布部分快照。
- RawEvent 与载荷生命周期必须一一对应；新分享文件写入前必须有活动 staging 租约，RawEvent 事务原子消费租约。载荷只有在文件删除成功后才能从 `CLEAR_PENDING` 进入 `CLEARED`，且清除不得删除已完成的结构化账本来源链。
- 相同证据哈希只能产生重复提示，未经过完整关联与用户判断不得自动合并或静默丢弃。

## 当前可靠性断面

当前受限 CNY/USD 手工账本切片已有以下防线代码：现金和电子钱包余额只接受 CNY，银行卡和信用卡才可接受 USD；领域过账前执行金额、币种、账户角色与逐币种平衡校验；Room 仓储以事务提交账户/期初分录、Draft 状态、正式交易、审计和 command receipt；数据库 Flow 驱动总览、账户、草稿和流水，且总览绝不跨币种合计，避免 UI 维护另一份合成余额。

通用分享文本切片又加入 64 KiB/严格 UTF-8 输入门、应用私有证据与哈希校验、不可变 RawEvent、安全 ParseAttempt、待补全来源建议、重复提示、忽略审计和 Intent command 生命周期。Room schema v5 的跨表查询验证 accepted/rejected 解析结果、建议数量/状态、证据链接、载荷生命周期和 Draft 语义；写路径检查目标链，观察状态对全局损坏失败关闭。

证据生命周期用例提供 7/30/90 天或永久保留、已提交与暂存共用的 16 MiB/512 份预算、未知大小测量、缺失文件诊断、20 项 keyset 分页和两阶段清除。删除失败保持 `CLEAR_PENDING` 并可在启动维护或设置页重试；自动保留/容量只选择无待复核建议的最旧证据。Room v5 以 5 分钟租约登记预提交文件，RawEvent 事务原子消费登记；启动维护接管到期租约并有界扫描 10 分钟以上的旧孤儿。

空模板通知路径增加 `:source:generic-notification`、通知专用 evidence ingress、Room v6 持久观察租约和最薄 Android listener。测试证明生产空目录、未知 metadata，以及关闭 runtime 均不会调用正文读取器；合成通知的信封严格有界、重试幂等、改写证据安全冲突、容量超限和意外仓储错误拒绝。观察租约在两分钟过期后复用原 command，`CAPTURED` 后阻止同实例更新重复建草稿；已捕获摘要和超过 90 天的失效活动租约只在后续候选回调中小批量清理，不新增后台维护。listener 用一个容量 16 的 IO 队列，而非每 callback 启动协程。它的 parser 只能产生来源待复核项，不会在没有 provider 样本时猜测交易字段或自动过账。结果页 observer 当前没有屏幕内容能力，不能计入无障碍采集可靠性。

2026-07-25 的可重复结果：

- 本轮组合 `test lint assembleDebug` 成功（516 个 Gradle 任务），覆盖来源契约、pipeline、generic parser、Application、Room 映射和 App/ViewModel；Lint 0 error，Debug APK 同轮生成。
- `:source:generic-notification:test :application:test :app:testDebugUnitTest :data:local:assembleDebugAndroidTest` 已在通知观察实现后成功；覆盖正文延迟读取边界、有限 UTF-8 信封、合成 parser、通知暂存/复核、同 command 重试、HMAC 摘要和 App hand-off，并编译 v5→v6 迁移与观察仓储 Android 测试。它不是系统 callback 回放、真实 provider 或真机测试。
- MuMu API 32 上最终 `:data:local:connectedDebugAndroidTest` 的 32 个测试全部通过，覆盖 v1→v2→v3→v4→v5 migration、证据文件测量/删除、RawEvent/来源仓储、两阶段清除、租约 CAS、磁盘数据库关闭/重开、旧孤儿与 `CLEARED` 残留回收、分页、已完成 provenance 保留、命令重放和跨表损坏失败关闭。
- MuMu 覆盖安装保留原账本后成功升级；手工验证了冷/热分享、竖屏复核、无原文回显、即时证据历史刷新、7/30 天策略切换、待复核二次确认清除以及清除后结构化历史保留。

2026-07-26 的可重复结果：

- `test lint assembleDebug :data:local:assembleDebugAndroidTest --console=plain` 成功（549 个任务）；包含通知失效活动租约的有界清理实现、JVM 回归、Lint、Debug APK 与 Android 测试 APK 编译。新的观察仓储 Android 用例仍未连接设备执行，因此不能替代真实 `NotificationListenerService` callback、OEM 存活或支付内容验证。
- 手工验收曾发现“新分享已入库但设置页列表未刷新”的陈旧分页问题；ViewModel 现以可取消的 generation 刷新取代旧请求，并有单元回归，复测时新证据立即出现在首项。
- 港版 `S24U-HK` 真机只完成了脱敏应用级冒烟：Debug 安装、两次冷启动、竖屏静态窗口以及一条明确标记为合成的 `ACTION_SEND text/plain`→覆核→忽略链路；无 `FATAL EXCEPTION`、Room 或 SQLite 错误。它不是 `INSTALL-01`/`ORIENT-01`/`SHARE-01` 的完整通过，也不包含支付 App 内容、通知或 provider 解析。唯一事实来源见 [Android 设备兼容与真机验收](design-docs/android-device-compatibility.md)。
- 受限 CNY/USD 账本的 `:core:ledger:test :application:test :data:local:test :feature:review:compileDebugKotlin :app:testDebugUnitTest :data:local:assembleDebugAndroidTest` 成功（202 个 actionable Gradle tasks）。覆盖 USD 银行/信用卡、USD 钱包拒绝、逐币种总览、混币分录三层拒绝，以及钱包来源 USD 的 application/仓储拒绝；Android instrumentation 只完成 APK 编译，尚未接触设备。

质量状态仍为“部分实现”：支付宝、微信支付和银行适配器仍不存在，来源健康固定为 `FALLBACK_REQUIRED`；大量/恶意 Intent、自动化 Compose、真实系统强杀切点和完整设备矩阵未完成。没有真实脱敏样本、解析回放和真机证据时，不执行或声称任何 provider 采集成功。

## 来源漂移

支付宝、微信和银行通知/文件格式都会变化。每个适配器必须：

1. 维护脱敏黄金样本与格式版本。
2. 对未知字段容忍，对关键字段缺失显式失败。
3. 区分“不匹配”“格式未知”“内容损坏”“权限不可用”。
4. 提供最后成功采集/导入时间和失败计数，但不泄露内容。
5. 新规则先离线回放旧样本，比较差异后才能发布。

## 测试金字塔

- 性质测试：分录平衡、金额守恒、导入幂等、关系对称/非对称约束。
- 领域单元测试：转账、还款、退款、充值、投资申赎和用户修正优先级。
- 适配器回放测试：三类来源的成功、格式漂移、缺字段、重复和乱码样本。
- Room 测试：schema、v1→v2→v3→v4→v5 迁移、事务回滚、命令重放/碰撞、void 恢复、来源/生命周期跨表完整性和大量数据查询。当前迁移、真实仓储、证据、重复、忽略、两阶段清除、租约暂存/恢复、磁盘数据库重开、keyset 分页和损坏链 instrumentation 已在 MuMu 执行；大量数据基准与真实系统强杀矩阵仍待补。
- Android 集成测试：权限撤销、进程重建、NotificationListener 系统回调/更新语义、SAF 文件访问失效。通知路径不使用 WorkManager 重试。
- UI 测试：账户/期初余额、手工/来源草稿确认、进程重建、重复解释、忽略、撤销、无障碍和敏感信息遮罩。首片与分享文本已完成 MuMu 手工端到端验收，ViewModel 覆盖后台整页保护、错误后清除陈旧快照和按 command 消费分享结果；自动化 Compose/进程死亡仍待完成。
- 端到端样例：同一绑卡消费同时出现支付渠道和银行证据，最终只产生一次支出。

## 地区与 OEM 真机验证

[Android 设备兼容与真机验收](design-docs/android-device-compatibility.md) 是设备清单、地区/GMS 风险、One UI/HyperOS 差异和可执行用例的唯一事实来源。本文件只规定可靠性门：

- 港版 Samsung S24 Ultra 与国行 Samsung S24 Ultra 分别运行并留档，不能因型号系列相同而共享结论。
- 国行小米必须先确认具体型号、性能档和 HyperOS 构建；未建档时所有“小米已验证”“中端已覆盖”声明受阻。
- 通知访问、Bill 自身通知权限和来源 App 通知/渠道分别测试，不能合并成一个权限开关。
- 普通划掉、系统回收、强行停止、重启、App 更新、系统更新和 OEM 省电策略分别记录，不使用含糊的“杀后台已测”。
- 每项结果包含设备/系统/App 版本、前置状态、步骤、预期、实际结果和脱敏证据；没有这些字段只能算 `未执行`。
- 捕获失败不能阻塞文件导入或手工录入，也不能把来源健康误报为刚刚成功。

首批设备均未完成本轮真机用例前，可靠性状态保持“待实现验证”。两台 S24 Ultra 不能替代发布前的低内存/中端覆盖。

## 失败与恢复

- 捕获失败不阻塞手工录入和文件导入。
- 解析失败保留原始证据与安全诊断，不生成猜测交易。
- 分享文本过大、畸形 UTF-8、存储/读取/哈希失败或 ID 碰撞均停在安全错误，不回显原文；已完成/忽略的旧 Intent 不因 Activity 重建重新打开。
- 清除文件失败时保留可重试状态；容量不足且没有已处理候选时拒绝新证据，不自动牺牲待复核原文。进程在文件完成后、RawEvent 提交前退出时，由持久租约和有界扫描恢复；晚到提交不能越过恢复 CAS。扫描尚未收敛或恢复正在进行时明确要求用户稍后重试。
- 对账任务可中断、可继续；重复运行结果一致。
- 自动确认必须写审计记录，并提供批量撤销窗口。
- 备份恢复先验证版本、认证标签和空间，再替换当前数据；替换必须可回滚。

## 性能预算（初始目标）

- 冷启动不因历史账单全量解析而阻塞首屏。
- 单条通知只在系统回调后工作；未命中元数据时正文读取数、落盘数和解析数必须均为 0。禁止历史扫描、周期 Job/Alarm、前台服务、partial wakelock 与自动 OCR。命中候选最多进入容量 16 的内存队列，满队列必须丢弃而不是无界排队。
- 命中通知的信封上限为 8 KiB，通知入口由单个 IO consumer 串行提交；每条未来真实模板都要验证更新/重启不重复建待复核项，并记录队列丢弃/失败健康状态。当前空目录不产生任何正文证据。
- 结果页读取若以后实现，每个候选窗口必须有去抖、有限节点数/文本量和最大树读取次数；目标 App 不在前台时节点读取数必须为 0。
- 当前单次 PNG 收据分享只由用户动作触发：当次 `content://` 流最多读取 4 MiB，声明与解析 MIME 必须均为 `image/png`，只校验签名、IHDR、尺寸、分块 CRC、IDAT/IEND，不创建 Bitmap/HardwareBuffer、不预览、不运行 OCR；暂存完成或失败后擦除临时字节，不在后台重试或持续扫描。未来图像解码/OCR 仍只能由用户动作触发、一次只处理一帧，并须测量 Bitmap/HardwareBuffer 峰值后才可开放。
- 导入 10 万行时不一次载入全部文件，不阻塞 UI，并能取消/恢复。
- 账本分页、按月汇总和草稿箱查询需有索引与基准测试。

具体毫秒、电量、内存和热门槛在关闭/仅通知/读屏/单次截图的 Android 基线工程和代表设备建立后写入。国行小米具体型号未知，当前不能把它当作中端性能基线，也不伪造性能承诺。

## 可观测性但不泄密

- 本地结构化事件记录 `operationId/sourceKind/adapterVersion/outcome/errorCode/durationBucket`。
- 提供用户可见的来源健康页和脱敏诊断导出。
- 不默认远程上传遥测。若未来引入，必须由 [SECURITY.md](SECURITY.md) 的变更门审核。

质量证据与缺口由 [QUALITY_SCORE.md](QUALITY_SCORE.md) 追踪。
