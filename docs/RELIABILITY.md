# 可靠性、测试与可诊断性

- 状态：部分实现；账本、CNY 投资持仓、分享文本、显式 CSV/TSV 映射、用户确认对账、受控通知边界/控制面、5 条默认关闭的 provider 实验 route、Debug 模板采样隔离、持久观察去重与证据生命周期已有自动化；S24U-HK 另有受限应用级冒烟、最终工作树 16 个 app、50 个 Room、随包 OCR 合成/空间链、11 张本机私有真实页面与简中合成状态页证据，真实 callback、真实简中页面、完整设备矩阵与发布门待完成
- 所有者：项目维护者
- 最后核验：2026-08-01
- 事实来源：当前领域/Application/Room/来源实现、自动化与 MuMu/受限真机结果、领域设计与本地优先产品承诺、ADR-0010、ADR-0011、ADR-0012、ADR-0016、ExecPlan 0002、ExecPlan 0003、ExecPlan 0006、ExecPlan 0011、Android 设备兼容与真机验收

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
- 结构化文件导入必须由用户显式映射必填列；文件摘要与映射摘要共同确定批次身份，单行失败不能回滚或重复已完成行。
- 转账、信用卡还款和退款只生成候选；用户确认前不得合并 Draft，确认事务必须同时写入平衡交易、Draft 链接、关系、审计和幂等回执。

## 当前可靠性断面

当前受限 CNY/USD 手工账本切片已有以下防线代码：现金和电子钱包余额只接受 CNY，银行卡和信用卡才可接受 USD；领域过账前执行金额、币种、账户角色与逐币种平衡校验；Room 仓储以事务提交账户/期初分录、Draft 状态、正式交易、审计和 command receipt；数据库 Flow 驱动总览、账户、草稿和流水，且总览绝不跨币种合计，避免 UI 维护另一份合成余额。

通用分享文本切片又加入 64 KiB/严格 UTF-8 输入门、应用私有证据与哈希校验、不可变 RawEvent、安全 ParseAttempt、待补全来源建议、重复提示、忽略审计和 Intent command 生命周期。当前 Room schema v10 的跨表查询验证 accepted/rejected 解析结果、建议数量/状态、证据链接、载荷生命周期、Draft/投资目标/审核渠道和持仓语义；写路径检查目标链，观察状态对全局损坏失败关闭。

证据生命周期用例提供 7/30/90 天或永久保留、已提交与暂存共用的 16 MiB/512 份预算、未知大小测量、缺失文件诊断、20 项 keyset 分页和两阶段清除。删除失败保持 `CLEAR_PENDING` 并可在启动维护或设置页重试；自动保留/容量只选择无待复核建议的最旧证据。当前 Room v10 延续 v5 引入的 5 分钟租约，在写入前登记预提交文件，由 RawEvent 事务原子消费登记；启动维护接管到期租约并有界扫描 10 分钟以上的旧孤儿。

受控通知路径包含 `:source:generic-notification`、通知专用 evidence ingress、当前 Room v10 中由 v6 引入的持久观察租约，以及最薄 Android listener。生产 catalog 由 `:source:alipay`、`:source:wechat` 和 `:source:bank:cmb` 提供 5 条默认关闭 route。测试证明空目录、未知 metadata 和关闭 runtime 均不会调用正文读取器；provider 单测覆盖脱敏成功、缺字段、漂移、敏感反例、竞争金额、错误频道/类别和 transport 后复核，application 测试覆盖来源建议到草稿的纵向路径。信封严格有界，parser identity 与 route 强绑定，单一 CNY 金额解析拒绝符号金额、竞争金额和超限输入；支付宝基金 route 还要求唯一确认金额与零手续费，申请受理、收益提醒、多金额和非零手续费失败关闭。观察租约在两分钟过期后复用原 command，`CAPTURED` 后阻止同实例更新重复建草稿；listener 使用容量 16 的单消费者 IO 队列。route 控制面以安全标签展示 catalog，开启先持久化、关闭先收紧本进程门禁。provider parser 只生成可证明的金额、方向和有限经济事件提示，不猜商户、资金账户、基金标的或自动过账；投资草稿必须绑定真实持仓。结果页 observer 当前没有屏幕内容能力，不能计入无障碍采集可靠性。

结构化 CSV/TSV 路径把当次 SAF 输入限制在 2 MiB、5000 数据行、64 列、1024 字符/单元格和 16 KiB/记录；严格 UTF-8 与语法校验发生在持久化前。未知表头不自动猜列或 provider，用户必须显式映射日期、金额、方向、对方与可选参考号。预览在后台 dispatcher 上以 250 ms 去抖执行；确认按行建立独立证据、来源建议和安全结果码，停止后以文件摘要和映射摘要继续尚未完成的行。Room v10 仍只保存批次/行身份、计数、RawEvent ID 或封闭错误码，不保存 URI、文件名、表头或单元格。

用户确认对账只在最多 500 条候选 Draft 与 50 笔近期退款来源交易的有界窗口中查找，并把每条 Draft 的建议限制为 3 条、全局限制为 50 条。金额、币种、方向、账户角色、时间窗和退款累计上限是硬门；相同金额本身不会触发合并。`FUNDED_BY` 另要求两条外部支出分别审核为支付宝/微信与银行渠道，并严格匹配同一银行卡/信用卡、规范化商户及 30 分钟窗口。确认与撤销均由 Room v10 事务提交，失败不留下半笔交易或孤立链接；撤销恢复两条 Draft 而不删除证据。该能力是用户显式确认的窄关系，不是一般跨来源自动去重。

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

2026-07-27 的阶段性可重复结果：

- `:source:contract:test :source:generic-delimited-statement:test :source:review-contract:test :application:test :feature:review:compileDebugKotlin :app:testDebugUnitTest --console=plain` 成功（163 个任务）。它覆盖严格 CSV/TSV 解析、显式映射、批次继续、来源时间/方向预填、ViewModel 取消/恢复和用户确认对账的应用投影；这只是针对性 JVM/编译证据。
- Room v6→v7 迁移、结构化导入批次/行仓储与对账事务的 Android 测试源码和 APK 已可编译，但尚未连接设备执行。针对性审查把导入行写入从每行完整性全扫与重新计数改为事务内 O(1) 增量汇总；全局完整性检查只在批次打开、恢复读取和最终刷新执行，避免 5000 行输入退化为 O(n²)。

2026-07-30 的可重复结果：

- 完整 `test lint assembleDebug assembleRelease :data:local:assembleDebugAndroidTest :ocr:paddle:assembleDebugAndroidTest --console=plain` 成功（850 个 actionable tasks：8 executed、842 up-to-date）。全部 JVM 测试、Lint、Debug、两个 ABI 的未签名 Release 分包，以及 Room/OCR AndroidTest APK 均完成构建；AndroidTest APK 没有连接设备执行。
- 该 850-task 结果已包含本轮设置深链、90 秒截图租约/迟到回调隔离、handle 注册竞态、15 秒本地提交截止/提交后未知态、Photo Picker 单飞批次汇总和启动声明布局加固，以及相关 JVM/路由/挂起端口/部分暂存后异常回归。
- 当前工作树的 `app-arm64-v8a-release-unsigned.apk` 为 90,540,713 bytes，SHA-256 为 `db3837758a3c700dfeb74b6f87a2d26f139e0adb2a105080b25e83dec123a9c5`；`app-x86_64-release-unsigned.apk` 为 128,362,402 bytes，SHA-256 为 `6d0488131fe1dd55b9348870584afc6f89f872a553297d5749414d04d4607583`。两包 Manifest 一致，只请求应用自身签名级动态接收器权限；没有网络、短信、媒体库或广泛存储权限，且均通过 `zipalign -c -P 16 4`。该检查证明 APK ZIP 内原生库对齐，不替代 ELF LOAD 段页兼容或真机加载验证。
- `code-review` 未发现 P0/P1；资源生命周期、币种边界、对账事务和依赖方向复核通过。发现的导入 O(n²) P2 已修复并复审关闭；对账 ViewModel 确认路由增加单元回归。仍开放的 P2 发布门是实际中英日 OCR、Room v6→v7/导入/对账 instrumentation、峰值内存/耗时/电量和三台目标设备。

2026-07-31 的通知控制面结果：

- 完整 `test lint assembleDebug assembleRelease :data:local:assembleDebugAndroidTest :ocr:paddle:assembleDebugAndroidTest :app:assembleDebugAndroidTest --console=plain` 成功（879 个 actionable tasks：8 executed、871 up-to-date）。全部 JVM、Lint、Debug/Release 和三组 AndroidTest APK 编译通过。arm64-v8a Release 为 90,581,257 bytes，SHA-256 为 `9e3c33d7a1a5f127a1f165f99102dc6981fa82d9a1d57767d48579dee2fa0b3e`；x86_64 Release 为 128,402,946 bytes，SHA-256 为 `d924e1be714920ae6bbfd5fa8d00d67cf9d26fed6b6fda1317aa69c60d5f3cfe`；两包通过 `zipalign -c -P 16 4`。
- `:app:testDebugUnitTest` 在 route 控制面复审修复后成功，覆盖启停写盘顺序、失败重试冻结、同值无写盘、损坏/旧偏好失败关闭、安全健康状态和 listener 连接/断连。
- 港版 `S24U-HK`（`SM-S9280`、Android 16/API 36、arm64-v8a）上的 `:app:connectedDebugAndroidTest` 运行 3 个 SharedPreferences route 用例并全部通过：跨实例启停、移除 route 的旧 ID 不放行、损坏偏好类型默认全关。测试使用 target app 下独立的 `bill.notification-route-enablement.instrumentation-test` 文件并只清理该文件；此前尝试 test APK context 时 One UI 没有可写 data 目录，写入失败。
- 这组设备结果没有授予通知使用权、没有运行真实 `NotificationListenerService` callback、没有读取支付宝/微信/银行或任何真实通知，也没有验证 OEM 后台存活、通知更新语义或 provider parser。它不把 `NLS-01` 标为通过。
- 在 listener 授权刷新和测试夹具写盘断言修复后，对当前源码再次执行同一设备命令并 3/3 通过（237 个 actionable tasks：1 executed、236 up-to-date，15 秒）。这仍只证明 route 偏好源码边界，不扩大到通知 callback 或 provider。
- 独立 `code-review` 未发现当前空 catalog 切片的 P0–P2。审查修复了测试误清生产偏好的风险、损坏偏好冷启动崩溃、撤权入口消失、设置入口静默失败、TalkBack 无标签开关、写盘失败覆盖重试、重复同值写盘，以及把“已授权”误报为 listener 已连接的问题。
- Debug 模板采样及仓库守卫复核后的最新完整 `test lint assembleDebug assembleRelease :data:local:assembleDebugAndroidTest :ocr:paddle:assembleDebugAndroidTest :app:assembleDebugAndroidTest --console=plain` 成功（879 个 actionable tasks：13 executed、866 up-to-date）。此前 S24U-HK/API 36 的 `:app:connectedDebugAndroidTest` 运行过 13 个合成用例并全部通过，其中 10 个覆盖只接收开始后的精确包名、跨进程及一年后仍活动、重复开始不覆盖、停止拒绝已排队工作、显式清除、无效重启失败关闭、截断文件拒绝恢复、最新优先分页、完整预览投影和空 NDJSON 记录失败关闭；另外 3 个是既有 route 偏好隔离。最新构建没有连接设备；既有测试也没有打开来源 App、读取历史通知或导出真实样本。当前 arm64-v8a Release 为 90,581,257 bytes、SHA-256 `e6a7184ce35ad456261e8d812782da3e752a9ec551f5ab278f53f712664d2f20`；x86_64 Release 为 128,402,946 bytes、SHA-256 `0b64784d27ee05cccf0703a04d5130a5ee67f92191d24532ea62372afa23cb59`。两包通过 16 KiB ZIP 对齐，Manifest 二进制一致且只含应用自身签名级动态接收器权限；以 Debug 包命中作为正对照后，两份 Release 全量解包扫描均未发现采样 Activity、原始样本文件名或支付宝/微信/招商银行/三星研究包名。
- listener 断连以及用户点击 Debug“开始”时会向 Android 请求一次重新绑定，采样页分别显示系统授权与 listener 实际连接状态，活动采样状态则由 app-private 偏好跨进程恢复。设备内预览以固定 64 KiB 单行缓冲从文件末尾读取，每页最多解码 10 条；合成测试覆盖最新优先分页、完整元数据/正文投影以及空 NDJSON 记录失败关闭。页面显示跨进程写入数、本进程队列丢弃/正文不可读计数，并明确说明系统未交付的 callback 无法反推。它不使用轮询、前台服务、Alarm、WorkManager 或唤醒锁，也不承诺绕过用户强行停止、撤权、卸载或 OEM 平台拒绝重绑。Debug 原始文件没有自动时间、条数或文件大小限制；单条字段与队列仍有界，磁盘写入失败会关闭运行时门。
- `code-review` 随后发现并修复三个问题：重开/恢复不再全文件扫描计数，而是从有界尾部记录恢复最后序号；清除原始样本增加不可撤销确认并使过期预览请求失效；页面明确要求停止前先确认队列项目已经显示。修复后的离线 `:app:testDebugUnitTest :app:compileDebugKotlin :app:compileReleaseKotlin :app:assembleDebugAndroidTest :app:lintDebug` 成功（489 个 actionable tasks：17 executed、472 up-to-date）。该轮发生在用户断开 S24U-HK 之后，因此新的测试 APK只完成编译，新增尾部恢复断言和清除交互尚未在设备上执行；此前 13/13 设备结果不能冒充最终工作树的再次运行。

2026-07-31 的首批 provider route 结果：

- 用户授权导出的 21 条真实 callback 与 11 张过程截图只保存在两份 Git 忽略的本机私有副本，设备端原始数据未删除。离线安全盘点只将 5/21 归入 4 条严格候选；仓库测试只使用脱敏成功/缺字段/漂移/敏感反例，不包含真实账号、商户或原文。
- 新增 `:source:alipay` 两条 route、`:source:wechat` 一条 route、`:source:bank:cmb` 一条 route；它们默认关闭，只产生整条通知内无冲突、无外币标记的单一 CNY 金额和方向建议。微信 route 的稳定 ID 不变，标题接受 `Weixin Pay` 与 `微信支付`，但只有真实样本验证过的英文正文模板可提取金额；未经样本验证的简中/繁中正文失败关闭。Samsung 短信、微信红包/转账/提现、支付宝其他事件及招商银行登录/普通交易保持拒绝。微信共享消息频道仍存在同名联系人完全仿照格式的误触发风险，因此只允许待复核。
- 定向 `:source:generic-notification:test :source:alipay:test :source:wechat:test :source:bank:cmb:test :application:test :app:testDebugUnitTest --rerun-tasks` 已实际执行并 173/173 通过。完整 `test lint assembleDebug assembleRelease :data:local:assembleDebugAndroidTest :ocr:paddle:assembleDebugAndroidTest :app:assembleDebugAndroidTest` 成功（891 个 actionable tasks：80 executed、811 up-to-date）。arm64-v8a Release 为 90,597,641 bytes、SHA-256 `d76a2c91013bc2991cf8e61d71e52a0083edf33a6bb8ac8b74c253918cfad74e`；x86_64 Release 为 128,419,330 bytes、SHA-256 `62ec182dfed546540d68536458de181abe55e99ff32cab6dd63da7f2b44c1e4e`，两包通过 16 KiB ZIP 对齐。
- 最终 `code-review` 未发现开放 P0/P1；已修复 parser 身份错配、超限载荷复制、致命错误被吞、带符号/多金额/跨字段冲突/混入外币的 CNY 误判，并以纵向测试确认 provider 建议保持 `WAITING_USER` 且不猜交易对手或资金账户。微信普通消息与支付通知共享频道是平台残余风险：同名联系人完全仿照正文仍可能产生待复核建议，因此该 route 保持默认关闭、实验性且不得自动入账。
- 本轮 connected 测试均只使用程序生成的合成数据；没有读取通知、打开支付 App 或使用用户的真实截图。因此 4 条新 route 的系统 callback、更新/重启、OEM 后台与资源证据仍全部开放。

2026-07-31 的 Room 与 OCR 真机合成结果：

- 在港版 `S24U-HK`（Android 16/API 36、arm64-v8a）上，最终 app instrumentation 14/14 通过：10 条 Debug 采样持久化/停止/分页、3 条隔离 route 偏好、1 条当时 4-route catalog 默认关闭和 opaque opt-in。新增第 5 条基金 route 后尚未重跑；这些用例也不读取真实通知或运行系统 callback。
- `:ocr:paddle:connectedDebugAndroidTest` 1/1 通过：随包 OpenCV、ONNX Runtime、PP-OCRv6 检测/统一识别模型成功加载，识别程序绘制的中/英/日支付文本并释放。它不代表真实支付截图准确率、主金额选择、峰值资源、电量或签名发行。
- `:data:local:connectedDebugAndroidTest` 首次运行 45/47；失败的 2 条旧导入夹具直接追加 `STATEMENT_IMPORT` RawEvent，绕过了生产所需 staging reservation，因此仓储按设计返回冲突。夹具改为先建立活动暂存租约后，完整 47/47 通过，覆盖 v1→v7 migration、导入批次/行、对账、通知观察、证据生命周期/暂存与账本仓储；生产不变量没有放宽。
- 空间 OCR v2 现在保留每行归一化整数边界、兼容 v1，并仅在独立金额相对正文中位高度及第二候选都明确占优时预填。指定 `code-review` 修复空 OCR 框令整次识别失败和值对象字符串泄露转录的风险，并阻止失败/拒绝/取消页提出金额，补英语退款/红包大小写阻断与遮罩回归；强制重跑 `generic-photo-ocr`、application 与 app 单测的 167 个任务全部通过。
- 用户重新接入设备后，定向 `:app:connectedDebugAndroidTest` 空间 OCR instrumentation 1/1 通过，验证“程序绘制多金额图像 -> 随包 OCR -> v2 转录 -> 主金额建议”。它没有经过系统截图/Photo Picker UI、真实支付页面、证据入库或 provider 识别，不能据此扩大发布结论。

2026-08-01 的本机私有真实页面与简繁体边界回归：

- 在同一 `S24U-HK` 上，经用户明确授权，把 11 张只保存在 Git 忽略目录和独立磁盘副本中的真实支付过程截图逐张送入随包 OCR；临时测试源码和设备暂存随后移除，仓库、日志和本文都不含原图、OCR 原文、金额、账号或设备序列号。初始解析 11/11 有 OCR 文本、9/11 有金额、9/11 有方向，完整预期语义为 7/11；完成状态门、纯小数主金额、选中金额符号和促销红包语义修正后，金额与预期语义均为 11/11。
- 11 张页面总 OCR 耗时 17.718 秒；单张最短 1.297 秒、中位数 1.688 秒、最长 2.026 秒。Debug instrumentation 进程在采样区间的 PSS 最低 174,332 KiB、最高 279,744 KiB，最大观测增量 98,841 KiB；该数包含测试进程、模型加载和分配器缓存，不是 Release 单次峰值或处理后空闲常驻结论，资源发布门仍开放。
- 当前真实页面来自用户的繁体中文/英文微信环境，可证明这些具体页面及规则。为避免把语言环境偶然性写死，又用程序绘制的简中“领取成功”“红包尚未领取”“提现处理中”“转账成功”四页执行定向 app instrumentation，1/1 通过。它证明随包 OCR 能识别简体字并让对应规则通过，不证明真实简中微信布局或中文通知正文；真实简中页面仍须后续脱敏样本验收。
- 本轮 `code-review` 先发现英语状态词采用宽泛子串会把正常文案误判为处理中，改为整行状态匹配；既有回归又捕获了“任意正负号决定方向”的过宽推断，现限制为选中主金额且必须存在交易详情上下文。真实回放暴露的币种符号拆行和促销红包假阻断也只在明确完成上下文、显著主金额和普通付款语义同时成立时放行。
- 简繁体对称复审又补齐 `入账/入賬`、`到账/到賬/到帳`、`提现/提現`、`还款/還款`、`零钱通/零錢通`、失败/处理中状态与商户/对方标签，并增加成对回归。微信通知保持既有 route ID，标题接受 `Weixin Pay`/`微信支付`，三个未经验证的中文付款正文反例继续拒绝。定向 `generic-photo-ocr`、WeChat、application 与 app 单元套件 169 个任务全部强制执行通过。
- 最终完整 `test lint assembleDebug assembleRelease :data:local:assembleDebugAndroidTest :ocr:paddle:assembleDebugAndroidTest :app:assembleDebugAndroidTest` 成功（891 个 actionable tasks：71 executed、820 up-to-date）。arm64-v8a Release 为 90,597,641 bytes、SHA-256 `6595051668d9d9fd2d9186a12b79cd6214c48b9ea001d176abf772c71a47ecab`；x86_64 Release 为 128,419,330 bytes、SHA-256 `b61385b015b36c395015c368ac63730715f7e75cdd356a8fd9e1650a67e62b6f`。两包通过 `zipalign -c -P 16 4`。
- 当前源码随后完成 891-task 全量 JVM/Lint/Debug/双 ABI Release/三组 AndroidTest APK 构建（71 executed、820 up-to-date）。arm64-v8a 未签名 Release 为 90,597,641 bytes、SHA-256 `5c6e58f02a38aef142130fc723983a7ce445020334a0aee04392e8b3d9a2bae1`；x86_64 为 128,419,330 bytes、SHA-256 `881ddc76dcebf7725d003a54fe3813ad2ff9a6d651dee302882327696b63ef0c`；两包通过 16 KiB ZIP 对齐。

2026-08-01 的投资持仓离线构建结果：

- `test lint` 成功（569 个 actionable tasks：30 executed、539 up-to-date）；生产 catalog 的陈旧 4-route 数量断言已改为 5，并额外固定支付宝 3 条 route。
- `assembleDebug assembleRelease :data:local:assembleDebugAndroidTest :ocr:paddle:assembleDebugAndroidTest :app:assembleDebugAndroidTest` 成功（711 个 actionable tasks：77 executed、1 from cache、633 up-to-date）。本轮只编译 AndroidTest APK，没有连接设备；Room v7→v9、持仓 OCR UI 和第 5 条通知 route 的真机验收仍开放。
- `code-review` 修复了投资草稿只校验账户类型、却未证明存在对应持仓的完整性缺口；application 与 Room 现在都要求目标账户能关联到真实 `InvestmentPosition`。文档结构检查和 `git diff --check` 同步通过。

2026-08-01 的可编辑审核、`FUNDED_BY` 与最终工作树真机结果：

- 港版 `S24U-HK` 上的 `:data:local:connectedDebugAndroidTest` 最终 50/50 通过，实际覆盖 v1→v10 迁移、Room v10 审核渠道默认值、Draft 编辑、`FUNDED_BY` 原子确认/幂等/撤销、两条来源证据保留，以及既有导入、通知观察、证据生命周期和账本仓储。
- `:app:connectedDebugAndroidTest` 最终 16/16 通过：10 条 Debug 采样、3 条 route 偏好隔离、固定全部 5 条生产 route 的默认关闭/opaque opt-in，以及 2 条空间 OCR 管线。`:ocr:paddle:connectedDebugAndroidTest` 1/1 再次通过，随包模型在 arm64/API 36 本地加载、推理并释放。
- 首轮分别出现 2 条旧迁移夹具未注册 v7→v10、1 条余额断言未按资金账户筛选、1 条生产目录仍期待 4-route 的失败；修复均只补全或收紧测试，生产代码与隐私边界未放宽。三个模块复跑共 67/67 通过。
- arm64 Debug APK 随后以 `adb install -r` 安装成功；`MainActivity` 冷启动 `Status: ok`、`TotalTime: 1078 ms`，启动后进程仍存在。没有执行 `pm clear`，也没有读取通知历史、来源 App、真实截图或本机私有样本。

质量状态仍为“部分实现”：支付宝、微信支付和招商银行已有 5 条窄范围实验通知适配器，但来源健康仍为 `FALLBACK_REQUIRED`；CSV/TSV 与对账只证明通用本地能力。大量/恶意 Intent、自动化 Compose、真实系统强杀切点、新 route 的 callback/更新/重启语义和完整设备矩阵未完成。没有真机回放和发布证据时，不声称任一 provider 已稳定支持。

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
- Room 测试：schema、v1→v2→v3→v4→v5→v6→v7→v8→v9→v10 迁移、事务回滚、命令重放/碰撞、void 恢复、来源/生命周期跨表完整性和大量数据查询。v1→v5 的真实仓储、证据、重复、忽略、两阶段清除、租约暂存/恢复、磁盘数据库重开、keyset 分页和损坏链 instrumentation 已在 MuMu 执行；S24U-HK 的完整 50-test suite 又覆盖到 v10、导入批次/行、通知观察，以及对账和 `FUNDED_BY` 确认/撤销。真实 SAF、5000 行基准、系统强杀和完整 UI 矩阵仍待补。
- Android 集成测试：权限撤销、进程重建、NotificationListener 系统回调/更新语义、SAF 文件访问失效。通知路径不使用 WorkManager 重试。
- UI 测试：账户/期初余额、手工/来源草稿确认、进程重建、重复解释、忽略、撤销、无障碍和敏感信息遮罩。首片与分享文本已完成 MuMu 手工端到端验收，ViewModel 覆盖后台整页保护、错误后清除陈旧快照和按 command 消费分享结果；自动化 Compose/进程死亡仍待完成。
- 端到端样例：同一绑卡消费同时出现支付渠道和银行证据，最终只产生一次支出。

仓库级离线回归由 `cmd.exe /d /s /c scripts\check-repository.cmd` 聚合：文档结构、架构依赖方向、生产日志/直接遥测/高风险源权限、生成事实新鲜度，以及守卫的正反例自测。本地与 [GitHub Actions run 30576542889](https://github.com/zjmmt/bill/actions/runs/30576542889) 已通过。生成事实中的测试文件和 `@Test` 数量只证明源码存在，不证明 Gradle 或设备执行；实际通过结果仍以上述测试分层和构建记录为准。

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
- 单条通知只在系统回调后工作；生产未命中元数据且 Debug 采样未由用户开启时，正文读取数、落盘数和解析数必须均为 0。禁止历史扫描、周期 Job/Alarm、前台服务、partial wakelock 与自动 OCR。命中候选最多进入容量 16 的内存队列，满队列必须丢弃而不是无界排队。Debug 研究文件允许用户自行决定保留时长和总量，但每条仍只有五个最多 1024 字符的字段；该例外不得进入 Release 或正式来源证据预算。
- 命中通知的信封上限为 8 KiB，通知入口由单个 IO consumer 串行提交；每条未来真实模板都要验证更新/重启不重复建待复核项，并记录队列丢弃/失败健康状态。当前空目录不产生任何正文证据。
- 结果页读取若以后实现，每个候选窗口必须有去抖、有限节点数/文本量和最大树读取次数；目标 App 不在前台时节点读取数必须为 0。
- 当前单次 PNG 收据分享只由用户动作触发：当次 `content://` 流最多读取 4 MiB，声明与解析 MIME 必须均为 `image/png`，只校验签名、IHDR、尺寸、分块 CRC、IDAT/IEND，不创建 Bitmap/HardwareBuffer、不预览、不运行 OCR；暂存完成或失败后擦除临时字节，不在后台重试或持续扫描。独立 Quick Settings/Photo Picker OCR 也只由用户动作触发：截图 command 先持有 90 秒可取消租约；在证据准入、容量清理或写入前以 CAS 线性化取消与本地提交，取消胜出时 Job 停止且不进入有副作用的准入路径。cancellation handle 注册期间的超时会先登记待取消；handle 返回后取消成功才报告超时，否则进入收尾未知态。提交胜出时先释放像素，再在独立 15 秒协作式截止内运行有界、无网络的 admit/stage/parse/Room 路径；挂起端口、内部取消或非致命异常都会返回“结果未确认”，由幂等与 staging recovery 处理可能的中间态。该截止依赖协程取消，不宣称能强杀不响应取消的底层阻塞 I/O；输入上限、像素预释放和暂存恢复共同限制其影响。断连、替换 service 或 90 秒到期若发现提交/既成结果不可取消，只启动一次 15 秒收尾宽限；仍无回调则显示“结果未确认”、释放单飞门并用 opaque request identity 隔离迟到回调。Photo Picker 每批只处理前 1–5 张，最大并发为 1，活动批次拒绝第二批，并只发布一次汇总。运行时最多两条 CPU 线程、batch 1、最长边 1600，并逐任务释放会话；v2 只额外持久化每行归一化整数边界，旧 v1 可重放。未签名 Release 分包体积、权限、组件、ABI、模型和 16 KiB ZIP 对齐已经测量，S24U-HK 合成三语、合成多金额空间链、11 张本机私有真实繁中/英文过程页和简中四状态页已通过；真实简中微信页面、系统截图/选图 UI、Release 峰值/空闲内存、耗电、ELF 页兼容与三台目标真机回归未通过前保持发布禁止。
- 结构化 CSV/TSV 当前硬拒绝超过 2 MiB、5000 数据行、64 列、1024 字符/单元格或 16 KiB/记录的输入；预览不阻塞主线程，导入可停止并按同一文件+映射继续。更大文件不进入当前版本，而不是无界加载。
- 账本分页、按月汇总和草稿箱查询需有索引与基准测试。

具体毫秒、电量、内存和热门槛在关闭/仅通知/读屏/单次截图的 Android 基线工程和代表设备建立后写入。国行小米具体型号未知，当前不能把它当作中端性能基线，也不伪造性能承诺。

## 可观测性但不泄密

- 本地结构化事件记录 `operationId/sourceKind/adapterVersion/outcome/errorCode/durationBucket`。
- 提供用户可见的来源健康页和脱敏诊断导出。
- 不默认远程上传遥测。若未来引入，必须由 [SECURITY.md](SECURITY.md) 的变更门审核。

质量证据与缺口由 [QUALITY_SCORE.md](QUALITY_SCORE.md) 追踪。
