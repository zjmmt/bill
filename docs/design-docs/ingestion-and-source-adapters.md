# 采集、导入与来源适配器

- 状态：部分实现；来源中立显式文本、本地 CSV/TSV 显式映射、单次 PNG 收据证据、受控通知 route 边界/控制面、持久观察去重与证据生命周期已实现；支付宝、微信和招商银行有 5 条默认关闭的实验通知 route，生产 catalog/registry 的五路整链内存回放已通过；CNY 持仓手填/单图 OCR 预填和基金申购待复核已有代码，单次截图/Photo Picker 本地 OCR 已有 11 张本机私有真实页面与简中合成真机回归，但新增 route 真机验收、通知 callback、真实简中页面和完整资源发布门均未完成
- 所有者：项目维护者
- 最后核验：2026-08-01
- 事实来源：多来源产品要求、Android 官方能力边界、当前来源/Room/Application 实现、ADR-0006、ADR-0007、ADR-0008、ADR-0009、ADR-0010、ADR-0011、ADR-0012

## 目的

用一个来源中立契约接入支付宝、微信支付和银行，同时让每个适配器独立演进、失败和回放。适配器只描述“观察到了什么”，不直接决定正式账务结果。

## 来源与采集方式分离

`sourceFamily` 表示支付宝、微信、银行、手工等来源；`captureMethod` 表示通知、文件、分享、图片或手工。相同来源可以有多种采集方式，相同文件格式也不能自动说明来源。

## 概念契约

```text
SourceConnector
  identity(): SourceIdentity
  capabilities(): Set<Capability>
  accepts(inputMetadata): Acceptance
  capture(input): RawCapture
  parse(rawEvent, parserVersion): ParseResult
  normalize(parsed): NormalizedCandidate
  diagnostics(error): SafeDiagnostic
  redactionPolicy(): RedactionPolicy
```

`Capability` 初始集合：`NOTIFICATION`、`STATEMENT_IMPORT`、`SHARE_TEXT`、`SHARE_FILE`、`PHOTO_OCR`、`MANUAL`、`HOLDING_IMPORT`。

`NormalizedCandidate` 只包含候选字段和证据定位：金额、币种、方向、时间精度、对手方、描述、付款方式提示、外部引用、账户提示、事件关键词和字段置信度。它不包含最终 `Transaction/Entry`。

## 当前实现：用户显式本地证据

当前可运行的 provider-neutral 纵向切片有四个已过代码验证的显式证据入口，以及一个已过未签名 Release 静态门和港版 S24 Ultra 合成模型推理、尚未完成真实页面/资源/签名发布门的本地 OCR 切片：

```text
ACTION_SEND text/plain       SAF OpenDocument(text/plain, CSV, TSV)       ACTION_SEND image/png
  -> SharedTextIngestion       -> SelectedTextFileIngestion                  -> temporary content URI read
                                  -> UserEvidenceIngestion                    -> SharedReceiptImageIngestion
                                       -> AppPrivateEvidenceStore
                                       -> immutable RawEvent
                                       -> SourceIngestionService
                                       -> GenericShareTextParser / GenericSelectedTextFileParser /
                                          GenericSharedReceiptImageParser
                                       -> ParseAttempt
                                       -> SourceDraftProposal
                                       -> user-completed external Draft
                                       -> balanced Transaction/Entries

Quick Settings tile / Photo Picker (up to 5, serial)
  -> one-shot screenshot or selected image
  -> bounded OCR transcript
  -> GenericPhotoOcrParser
  -> immutable RawEvent -> ParseAttempt -> SourceDraftProposal
  -> user-completed external Draft

SAF OpenDocument(CSV, TSV) + user-confirmed mapping
  -> bounded process-local document (2 MiB / 5000 rows)
  -> ImportBatch(file hash + mapping hash)
  -> versioned row evidence + stable row command
  -> GenericDelimitedStatementParser
  -> one SourceDraftProposal per valid row
  -> user-completed external Draft
```

- `source:contract` 定义 Evidence、RawEvent、parser identity、候选、导入批次端口和封闭诊断；`source:pipeline` 负责注册、读取、校验和追加式解析；`source:review-contract` 定义建议、证据链接和载荷生命周期端口；`source:generic-share-text` 实现最小分享文本与不透明用户文件解析，`source:generic-delimited-statement` 实现严格 CSV/TSV、显式映射与行证据 codec，`source:generic-receipt-image` 实现单张 PNG 收据的结构校验与手工复核建议，`source:generic-photo-ocr` 实现有界转录和保守候选解析。
- `data:local` 的 Room schema v11 保存 `parse_attempts`、`source_draft_proposals`、`draft_source_evidence`、`source_evidence_payloads`、`source_evidence_staging`、`notification_observations`、`statement_import_batches`、`statement_import_rows`、普通账户余额快照、投资持仓、Draft 投资目标与用户审核渠道、对账链接/关系与单例保留策略；正式迁移链为 v1→v2→v3→v4→v5→v6→v7→v8→v9→v10→v11。v10 给 Draft 增加非空 `observedChannel`，旧记录迁移为 `UNKNOWN`；v11 只新增不可变 `balance_snapshots` 表与索引，不改 RawEvent、证据链或已有账务。导入批次表只保存文件/映射摘要、计数、状态和时间，行表只保存行号、指纹、RawEvent ID 或安全错误码；不保存文件名、URI、表头或单元格。通知观察表只保存安装私有 HMAC 摘要、opaque command/lease、状态和时间，绝不保存 Android notification key、包名、频道或正文。证据文件位于 `noBackupFilesDir/source-evidence`，使用 opaque 名称、原子写入、大小与 SHA-256 校验。
- 自由文本在创建完整字符串/字节副本前检查字符上限，严格 UTF-8 编码后再次检查 64 KiB 字节上限。结构化 CSV/TSV 当次最多读取 2 MiB、5000 数据行、64 列、1024 字符/单元格和 16 Ki 字符/记录；用户必须显式选择必填列，预览计算以 250 ms 去抖在主线程外运行。确认后只把每个有效行的版本化证据接入私有链，停止后可用同文件+同映射续传缺失行。Sharesheet PNG 只在用户明确分享的当次 `content://` 临时授权中读取，要求声明与解析后 MIME 都为 `image/png`，最多 4 MiB，并校验签名、IHDR、尺寸、分块 CRC、非空 IDAT 与终止 IEND。该 Sharesheet 路径不解码像素、不预览、不读取图中文字、不运行 OCR。独立的磁贴/Photo Picker 路径会在用户动作后把一帧或每批前 1–5 张逐张像素作为瞬时输入，只持久化有界转录。转录 v2 为每行保存可选的 0..10000 归一化整数矩形；原始像素、颜色、logo 和应用身份不进入证据，既有 v1 文本转录仍可重放。完成状态先于金额：失败、拒绝、取消、未领取/未打开、处理中、预计到账或待完成时连金额和方向都不提议；其余多金额页面仍只在一个独立金额相对正文与其他独立金额的高度明确占优时预填。明确完成上下文允许恢复 OCR 拆开的显著纯小数主金额，正负号只作用于被选中且位于交易详情上下文的金额；真正的退款/转账/红包/充值/提现不推断普通收支，普通付款页促销红包文案不覆盖完成支出语义。截图 command 先持有 90 秒可取消租约；完成纯读取校验后，在任何 evidence admission、容量清理或写入之前以 `ACTIVE -> COMMITTING` CAS 线性化取消与提交。cancellation handle 注册中的超时只登记待取消，不能提前报告成功。CAS 胜出后，像素资源先释放，再在独立的 15 秒协作式截止内运行无网络的 admit/stage/parse/Room 路径；此后的超时、取消或非致命异常统一返回 `COMMIT_STATUS_UNKNOWN`。提交或既成结果不可取消但回调缺失时，controller 只给一次 15 秒收尾宽限，再显示“结果未确认”并释放，opaque request identity 隔离迟到回调。Photo Picker 单飞串行、显示已处理数量且每批只汇总一次。PP-OCRv6 small 静态随包，运行时零排队、两条 CPU 线程、batch 1、最长边 1600，并逐任务释放会话。畸形 Unicode、损坏/缺失文件、无效图像、哈希不一致和 ID 冲突均安全失败。
- 自由文本解析器不提取或猜测金额、方向、账户、商户或 provider；映射 CSV/TSV 只采用用户确认的列、格式、方向值和币种，不按未知表头猜列或 provider。OCR 也不根据版式推断 provider，只保守提出金额/方向候选。三类结果都始终 `WAITING_USER`，必须由用户核对并选择资金账户；没有自动确认路径。
- 同 `(connectorId, contentHash, captureScope)` 的既有观察只产生 `isPossibleDuplicate`，UI 提醒检查已有草稿/流水但不会自动合并。
- 用户可将来源建议标记为 `DISMISSED`；该动作有幂等回执和审计，默认仍保留 RawEvent、ParseAttempt 与证据文件。
- 设置页提供 7/30/90 天或永久保留、20 项 keyset 分页和逐项清除。清除采用 `AVAILABLE -> CLEAR_PENDING -> CLEARED`，文件失败保持可重试；待复核建议会被 dismiss，已完成 Draft/provenance 和结构化审计保留。
- 已提交生命周期与暂存租约共用 16 MiB/512 份预算。入口先测量旧版未知大小并只清理最旧、无待复核项的载荷；没有安全候选时返回可操作的容量错误。
- 文件写入前先登记 5 分钟 `ACTIVE` 租约；RawEvent 与生命周期在同一事务消费登记。启动维护接管到期租约，并对至少 10 分钟以前的遗留文件执行有界扫描（单轮最多检查 4096 个目录项、认领 512 个 payload）。扫描先排除可用、待清除和活动/恢复载荷；截断时拒绝新分享并分批恢复。
- 账本状态流验证 RawEvent、ParseAttempt、Proposal、Draft evidence、载荷生命周期和 Draft 的跨表链；不一致时失败关闭，不能把损坏链静默当作部分正常数据。

`MainActivity` 因接收 Sharesheet 而 exported，任意 App 都可能直接发送 Intent；SAF URI、PNG 分享和 Photo Picker 的 `content://` URI 都只在一次读取边界内使用，既不持久化 URI/原文件名，也不取得持久 URI 权限。所有入口都必须作为不可信外部输入处理；包名、正文、MIME、用户映射或 Intent 本身都不是 provider 证明。CSV/TSV 与 PNG 临时字节在解析/暂存完成或失败后擦除；Sharesheet 路径没有图像预览或 OCR，用户需以自己保留的原始截图填写复核事实。磁贴/Photo Picker 原型会生成待复核转录，但不是 provider 适配器；持仓单图 OCR 只瞬时读取识别行并预填空白表单字段，不保存 URI、图片或识别原文。Room v11 已关闭未登记文件的核心崩溃窗口，为通知实例恢复建立持久租约，并保存不含原文的导入/对账状态、普通账户余额快照、持仓快照、投资草稿目标和可纠正业务渠道；但当前仍缺大量/恶意输入压力、真实系统强杀切点矩阵、自动化 Compose、完整真机矩阵、真实脱敏结构化文件、OCR 发布审计和 OCR 目标设备资源证据，因此不能把这些切片标为发布级 `Supported`。

## 通知入口（受控 route 与首批 provider 候选已实现）

- `:source:generic-notification` 定义了不含 Android 对象、包名、通知 key、actions 或 URI 的 `NotificationEnvelope`。它只保留经模板选中的有限字段、opaque 模板 ID/版本和事件时间；严格 UTF-8、NUL、未配对 surrogate、字段数与总编码大小均有硬门。证据最多 8 KiB，并由既有私有暂存/保留/清除链管理。
- `NotificationListenerService` 只先读取包名、具体 Android 通知渠道和类别。静态 `VerifiedNotificationRoute` 同时提供 metadata rule、route ID、SourceIdentity、parser 与安全显示标签；类别为 null 时也只匹配 null，不能当通配符。只有三者精确命中且 route 已在 app-private 本地设置中显式开启，才复制 title/text/subText/bigText/summaryText 的有界字段；未命中、关闭或 route 已移除时不碰 `extras`，不入库、不打日志、不创建草稿。模板不得只按包名匹配。候选工作进入容量 16 的非阻塞内存队列，由单个 IO consumer 串行处理；满队列时丢弃这次工作项，不启动额外协程或重试任务。
- 当前生产 catalog 由 `:source:alipay`、`:source:wechat` 与 `:source:bank:cmb` 提供 5 条本地随包 route：支付宝支出、支付宝余额收款、支付宝基金申购确认、微信付款完成、招商银行快捷支付退款。所有 route 默认关闭；Prepared capture 必须携带 catalog 原对象，ingress 拒绝同值 lookalike 并在持久化前再次检查开关。设置控制面只导出 opaque route ID 与安全标签，开关命令单飞串行；开启先用 `SharedPreferences.commit()` 成功写盘，关闭先收紧本进程门禁，失败时冻结其他 route 并保留精确重试。微信 route 保留原稳定 route ID，并把标题 `Weixin Pay` 与 `微信支付` 视为同一来源提示；标题不提供金额，只有当前真实样本验证过的英文正文模板可以进入 parser，未知简中/繁中正文失败关闭。支付宝基金 route 只接受唯一确认金额和零手续费形态，输出 `INVEST_BUY` 经济事件提示；简体来自真实样本，繁体只是字段兼容别名。它不输出标的，复核时必须绑定用户已经创建并确认的持仓。provider parser 在 transport 后重新校验 RawEvent identity、媒体类型、8 KiB 上限、template/version 与正文模板，只接受可证明的正 CNY 金额、方向和有限事件提示，生成来源建议而不猜商户、资金账户、基金标的或直接过账；parser factory 的 identity 必须与 route 完全一致。复核投影只由 opaque connector 解析安全标签，不显示包名、频道、类别或正文。观察 HMAC 摘要仍以 `StatusBarNotification.key` 与 post time 派生；两分钟过期租约复用原 command，成功 hand-off 后标记 `CAPTURED`，不设后台维护任务。
- 5 条 route 来自用户授权的 21 条本地真实 callback：离线安全盘点覆盖付款、余额收款、基金申请/确认/行情、微信付款与招商退款，代码与仓库只保留脱敏成功/缺字段/漂移/敏感反例。Alipay 三条 route 使用其默认通知频道；基金申请受理和收益/行情提醒不会创建草稿。微信 route 的支付通知与普通消息共用同一频道和类别，因此启用时会在设备本地读取同频道的有界正文再以标题别名和已验证完成词过滤，安全标签必须披露这一点。同名联系人若发送完全相同格式，现有元数据仍不能证明它是支付服务，所以该 route 永远只生成待复核建议。招商银行只使用 App 专用交易频道。Samsung 短信 route 被明确排除，因为读取正文前的元数据无法把银行短信与普通短信、OTP 或私人消息分开。
- `AppContainer` 与纵向回放共用 `ProductionNotificationRoutes.parsers`，避免生产注册和测试清单漂移。五条脱敏夹具已逐条经过 metadata/content gate、观察租约、证据 ingress、不可变 `RawEvent`、生产 parser registry 和来源 proposal，并只得到金额、方向、有限事件提示与 `WAITING_USER`；回放端口全部在内存中，不连接目标数据库、设备或本机私有样本。
- Debug 变体为模板研究提供独立、显式的采样控制器。用户从专用桌面入口选择精确包名并手动开始后，控制器保存 `active + startedAt + targetPackages`，只接收开始时间之后的新 callback；进程重建不改变活动状态，且不存在自动到期、条数或文件大小上限。每条候选仍共用容量 16 的单消费者队列，追加包名、channel/category、post time 与五个既有有界正文域到 app-private no-backup NDJSON；它不读取通知 key、历史、actions、RemoteViews 或消息数组，不进入 `RawEvent`/Draft。页面使用固定内存的反向逐行读取，每页最多返回 10 条，从新到旧显示实际字段并标出缺失域；预览不上传、不写日志，也不改变样本或账务状态。队列丢弃与正文不可读取只在当前进程计数，未收到系统 callback 的事件不可推断。重复开始保留旧样本并更新前向边界；停止保留文件，清除必须由用户另行点击。Release 是永久关闭的无操作控制器，且不打包采样 Activity、研究包名、文件名或研究文案。
- 不读取历史通知、不修改外部通知、不开前台服务、不设周期任务或唤醒锁。通知无法反映没有通知的领取、发送或后台余额变动，也不能补历史；来源健康页必须把这些显示为覆盖缺口，而不是显示“自动同步正常”。
- 当前仍缺这 5 条 route 的真实系统 callback、更新/重启回放和真机资源数据；设置页显示可逐条开启的安全标签、全暂停、系统权限缺失、listener 未连接、队列跳过与失败健康状态。S24U-HK/API 36 的 app instrumentation 已验证当前 5-route catalog 的独立测试偏好和默认关闭/opaque opt-in，但没有运行系统 callback。不得用已经通过的内存整链回放、正文 hash 或金额替代系统实例语义，也不得为此读取历史通知。

## 被动无障碍读取（研究门）

当前 Manifest 的 `BillScreenshotAccessibilityService` 是专用单次截图服务，不是支付结果观察器：它声明 `canTakeScreenshot=true`、`canRetrieveWindowContent=false`，只响应用户点击 Bill 磁贴后的一个 command，不读取节点、事件、目标包、窗口标题，不执行手势、点击或滚动。本地 OCR 的未签名 APK 静态门、港版 S24 Ultra 合成中/英/日模型加载推理、11 张本机私有真实繁中/英文过程页回放和简中四状态页合成回归已完成；真实简中微信页面、系统截图/选图 UI、Release 资源、签名和三台目标真机门尚未完成。用户不应把它理解为支付宝、微信支付或银行的被动监听能力。

当前没有可用的页面文字/节点观察 service。若项目负责人在有脱敏页面样本后明确选择被动读取路线，必须另建只读 `AccessibilityService`：系统只对微信/支付宝的明确目标包和最小窗口事件类型回调，服务只在疑似交易结果/账单详情窗口读取一次节点树并提交待复核证据。它不得取得截图、录屏、轮询、自动点击、打开页面、发起交易或保留非交易窗口内容；实现必须在事件回调外完成有界解析并清除临时节点/文本。

这不是远程钱包 API：没有通知且从未出现可读前台页面的后台余额变化仍不可观察。发布前还必须完成真实模板/可访问性树样本、耗电基线、显著告知、明确同意和相应发行渠道合规审核；没有这些证据不得把它称为微信或支付宝“全自动记账”。

## 用户可见本地声明与资源边界

每次新进程启动，App 先显示“纯本地 / 不走网络”的显著声明：当前 Manifest 不声明 Internet 权限，Bill 不向项目方服务器上传通知、截图、账户、余额或账本数据。声明使用 safe drawing insets 与纵向滚动，避免系统栏或大字体遮住确认操作。该声明不能替代第三方 SDK、签名发行与目标真机审计；当前截图/Photo Picker OCR 即使已过未签名 Release 静态门和一台设备的合成推理，也必须标为尚未完成发布门的 Experimental，不能被启动文案掩盖。简中/繁中披露统一区分“代码/AAR/未签名包的本地无网络静态检查及受限合成推理已通过”和“真实页面、签名、资源和完整目标真机仍待验”。设置页的三轨教程分别说明通知访问、未来只读结果页和单次截图/Photo Picker；磁贴不可用与无障碍服务详情的设置入口都回到该教程，教程明确图片 OCR 只创建本地待复核项、不证明 provider。

通知、结果页和截图是物理分开的能力：普通通知路径不承担读屏/OCR 成本；未来读屏路线不能截图或操作 UI；截图/Photo Picker 只在用户动作后处理一次有界像素输入，当前只形成待复核转录证据。没有真实设备资源基线前，不以某个虚构的电量百分比承诺低耗电，也不默认要求忽略电池优化。

## 文件/分享入口

- 使用 Storage Access Framework 或 Android Sharesheet 接收用户明确选择的文件/文本；不申请全盘读取。当前 SAF 同时提供 64 KiB 内的不透明纯文本/CSV/TSV 证据回退，以及 2 MiB/5000 行的 CSV/TSV 显式映射；Sharesheet 另接收一张 4 MiB 内的 `image/png` 收据。实验性 Photo Picker OCR 最多逐张处理用户选择的 5 张图片，不申请广泛相册权限，但仍受 OCR 隐私发布门限制。不持久化 URI 或文件名。
- Sharesheet 接收组件属于外部信任边界；应用不能密码学证明用户手势或发送 App，必须对类型、大小、编码和内容重新验证。
- 文件视为不可信：验证真实内容而非只信扩展名/MIME，限制大小、行列数、嵌套压缩、公式和资源消耗。
- 当前 CSV/TSV 不探测 provider 或自动猜列：用户先选分隔格式，再明确选择日期、金额、方向、对方、可选参考号、日期/数字格式和 CNY/USD。
- 导入先 dry-run，展示有效/拒绝计数与最多 5 个安全行错误；确认后逐行原子提交。中断不会回滚已完成行，也不会把任何行自动写入正式账本。
- `ImportBatch` 保存文件哈希、适配器版本、映射方案和统计；每行/记录有稳定幂等键。
- 原始附件可按用户策略删除；删除后保留解析/审计所需最小摘要。

## 三类适配器布局

```text
source/
  contract/
  pipeline/
  review-contract/
  generic-share-text/
  generic-receipt-image/
  generic-notification/
  alipay/             # 已实现：窄范围通知 route；statement 仍规划
  wechat/             # 已实现：窄范围通知 route；statement 仍规划
  bank/
    cmb/              # 已实现：招商银行窄范围通知 route
    common-mapping/   # 规划
    statement/<provider-id>/  # 规划
```

银行是 provider 集合。共享银行映射器可以帮助用户尝试导入，但只有带版本范围和回归证据的 provider preset 才能升级为正式支持。

## Parser 与规则版本

- 每次解析记录 connector/parser/rule 版本、结果、字段置信度和安全错误码。
- 新版本对全部旧黄金样本离线回放，生成新增、删除、字段变化和关联变化 diff。
- 默认不覆盖已确认用户编辑；需要迁移时显示原因并保留前后版本。
- 规则作用域最小化：provider + capture method + format/template version。

## 诊断分类

| 代码族 | 示例 | 用户动作 |
| --- | --- | --- |
| `AUTH_*` | 通知权限关闭、文件授权过期 | 重新授权或用其他入口 |
| `FORMAT_*` | 未知表头、未知通知模板、编码失败 | 选择映射/提交脱敏样本/手工录入 |
| `CONTENT_*` | 金额缺失、日期非法、文件损坏 | 修正或跳过异常记录 |
| `DUPLICATE_*` | 同文件、同通知、已有外部 ID | 查看已有记录 |
| `RESOURCE_*` | 文件过大、解析超限、空间不足 | 缩小文件或释放空间 |
| `POLICY_*` | 不支持的 UI 自动化、受限权限 | 使用正式替代路径 |

诊断日志不得包含原始正文、金额、商户或账号。用户可见错误可以在本地临时展示必要片段，但不得进入远程报告。

## 适配器验收模板

每个新适配器执行计划必须回答：

1. 官方用户路径和最后核验日期是什么？
2. 需要什么 Android 权限，拒绝/撤销后怎样退化？
3. 支持的机构、App/文件版本和不支持范围是什么？
4. 有哪些脱敏黄金样本和反例？
5. 幂等键、外部 ID 作用域和跨来源提示是什么？
6. 未知格式、恶意输入、日志和数据删除怎样处理？
7. 如何证明它达到 [来源覆盖验收门](../product-specs/source-coverage.md#验收门)？
