# 采集、导入与来源适配器

- 状态：部分实现；来源中立显式文本与单次 PNG 收据证据、受控通知 route 边界、持久观察去重与证据生命周期已实现；单次截图/Photo Picker OCR 仅有未通过隐私发布门的开发原型，provider 格式待样本
- 所有者：项目维护者
- 最后核验：2026-07-26
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

当前可运行的 provider-neutral 纵向切片有三个已过代码验证的显式证据入口，以及一个未过隐私发布门的 OCR 开发原型：

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

Quick Settings tile / one-image Photo Picker
  -> one-shot screenshot or selected image
  -> bounded OCR transcript
  -> GenericPhotoOcrParser
  -> immutable RawEvent -> ParseAttempt -> SourceDraftProposal
  -> user-completed external Draft
```

- `source:contract` 定义 Evidence、RawEvent、parser identity、候选和封闭诊断；`source:pipeline` 负责注册、读取、校验和追加式解析；`source:review-contract` 定义建议、证据链接和载荷生命周期端口；`source:generic-share-text` 实现最小分享文本与用户选择文本文件解析，`source:generic-receipt-image` 实现单张 PNG 收据的结构校验与手工复核建议，`source:generic-photo-ocr` 实现有界转录和保守候选解析。
- `data:local` 的 Room schema v6 保存 `parse_attempts`、`source_draft_proposals`、`draft_source_evidence`、`source_evidence_payloads`、`source_evidence_staging`、`notification_observations` 与单例保留策略；正式迁移链为 v1→v2→v3→v4→v5→v6。通知观察表只保存安装私有 HMAC 摘要、opaque command/lease、状态和时间，绝不保存 Android notification key、包名、频道或正文。证据文件位于 `noBackupFilesDir/source-evidence`，使用 opaque 名称、原子写入、大小与 SHA-256 校验。
- 文本在创建完整字符串/字节副本前检查字符上限，严格 UTF-8 编码后再次检查 64 KiB 字节上限；Sharesheet PNG 只在用户明确分享的当次 `content://` 临时授权中读取，要求声明与解析后 MIME 都为 `image/png`，最多 4 MiB，并校验签名、IHDR、尺寸、分块 CRC、非空 IDAT 与终止 IEND。该 Sharesheet 路径不解码像素、不预览、不读取图中文字、不运行 OCR。独立的磁贴/Photo Picker 原型会在用户动作后将一帧像素作为瞬时 OCR 输入，只持久化有界转录；它目前是未通过隐私发布门的 Chinese ML Kit 原型。畸形 Unicode、损坏/缺失文件、无效图像、哈希不一致和 ID 冲突均安全失败。
- 通用解析器不从自由文本提取或猜测金额、方向、账户、商户或 provider；首版结果始终 `WAITING_USER`，必须由用户补全再进入普通 Draft。它没有自动确认路径，也不把 CSV/TSV 视为批量账单格式。
- 同 `(connectorId, contentHash, captureScope)` 的既有观察只产生 `isPossibleDuplicate`，UI 提醒检查已有草稿/流水但不会自动合并。
- 用户可将来源建议标记为 `DISMISSED`；该动作有幂等回执和审计，默认仍保留 RawEvent、ParseAttempt 与证据文件。
- 设置页提供 7/30/90 天或永久保留、20 项 keyset 分页和逐项清除。清除采用 `AVAILABLE -> CLEAR_PENDING -> CLEARED`，文件失败保持可重试；待复核建议会被 dismiss，已完成 Draft/provenance 和结构化审计保留。
- 已提交生命周期与暂存租约共用 16 MiB/512 份预算。入口先测量旧版未知大小并只清理最旧、无待复核项的载荷；没有安全候选时返回可操作的容量错误。
- 文件写入前先登记 5 分钟 `ACTIVE` 租约；RawEvent 与生命周期在同一事务消费登记。启动维护接管到期租约，并对至少 10 分钟以前的遗留文件执行有界扫描（单轮最多检查 4096 个目录项、认领 512 个 payload）。扫描先排除可用、待清除和活动/恢复载荷；截断时拒绝新分享并分批恢复。
- 账本状态流验证 RawEvent、ParseAttempt、Proposal、Draft evidence、载荷生命周期和 Draft 的跨表链；不一致时失败关闭，不能把损坏链静默当作部分正常数据。

`MainActivity` 因接收 Sharesheet 而 exported，任意 App 都可能直接发送 Intent；SAF URI、PNG 分享和 Photo Picker 的 `content://` URI 都只在一次读取边界内使用，既不持久化 URI/原文件名，也不取得持久 URI 权限。所有入口都必须作为不可信外部输入处理；包名、正文、MIME 或 Intent 本身都不是 provider 证明。PNG 临时字节在暂存完成或失败后擦除；Sharesheet 路径没有图像预览或 OCR，用户需以自己保留的原始截图填写复核事实。磁贴/Photo Picker 原型会生成待复核转录，但不是 provider 适配器。Room v6 已关闭未登记文件的核心崩溃窗口，并为通知实例恢复建立了持久租约，但当前仍缺大量/恶意输入压力、真实系统强杀切点矩阵、自动化 Compose、完整真机矩阵、结构化文件样本、OCR 发布审计和 OCR 目标设备资源证据，因此不能把此切片标为发布级 `Supported`。

## 通知入口（受控 route 基础已实现，provider 未实现）

- `:source:generic-notification` 定义了不含 Android 对象、包名、通知 key、actions 或 URI 的 `NotificationEnvelope`。它只保留经模板选中的有限字段、opaque 模板 ID/版本和事件时间；严格 UTF-8、NUL、未配对 surrogate、字段数与总编码大小均有硬门。证据最多 8 KiB，并由既有私有暂存/保留/清除链管理。
- `NotificationListenerService` 只先读取包名、具体 Android 通知渠道和类别。静态 `VerifiedNotificationRoute` 同时提供 metadata rule、route ID、SourceIdentity、parser 与安全显示标签；类别为 null 时也只匹配 null，不能当通配符。只有三者精确命中且 route 已在 app-private 本地设置中显式开启，才复制 title/text/subText/bigText/summaryText 的有界字段；未命中、关闭或 route 已移除时不碰 `extras`，不入库、不打日志、不创建草稿。模板不得只按包名匹配。候选工作进入容量 16 的非阻塞内存队列，由单个 IO consumer 串行处理；满队列时丢弃这次工作项，不启动额外协程或重试任务。
- 当前生产 route catalog 为空；因此用户当前即使错误地授予通知访问，运行时也不会读取任何通知正文，也不会声称支付宝、微信或银行已接入。未来经过样本验证的 route 只有在元数据门、显式本地开关、持久观察租约和有界队列都存在时才能运行：Prepared capture 必须携带 catalog 的原 route 对象；ingress 拒绝调用者拼出的同值 lookalike，并在持久化前再次检查开关，因此关闭发生在排队/prepare 后也只释放观察租约而不落通知证据。开关变更串行并用 `SharedPreferences.commit()` 确认写盘，未来 UI 必须在后台执行并显示失败。ingress 从该 route 写入 `RawEvent.sourceFamily` 与 `connectorId`；catalog 同时拒绝与既有通用通知 parser 相同的来源 tuple。解析器复核 envelope template/version 后只形成待复核项；复核投影只由 opaque connector 解析安全标签，不显示包名、频道、类别或正文。当前 enablement 仅是 production-empty catalog 的安全基础；首个真实 route 合入前必须另外接通用户可见的安全标签选择器和“尚未开启”健康状态。观察 HMAC 摘要以 `StatusBarNotification.key` 与 post time 派生；两分钟过期租约复用原 command，成功 hand-off 后标记 `CAPTURED`。已捕获摘要与已失效超过 90 天的活动租约只在后续候选回调中有界清理，不设后台维护任务。通用 parser 不猜金额、方向、账户、provider 或直接过账。
- 不读取历史通知、不修改外部通知、不开前台服务、不设周期任务或唤醒锁。通知无法反映没有通知的领取、发送或后台余额变动，也不能补历史；来源健康页必须把这些显示为覆盖缺口，而不是显示“自动同步正常”。
- 首个真实模板前仍缺真实系统 callback 更新回放和真机资源数据；设置页当前已显示不含来源/正文的空目录、队列跳过与失败健康状态。不得用内存、正文 hash 或金额替代系统实例语义，也不得为此读取历史通知。

## 被动无障碍读取（研究门）

当前 Manifest 的 `BillScreenshotAccessibilityService` 是专用单次截图服务，不是支付结果观察器：它声明 `canTakeScreenshot=true`、`canRetrieveWindowContent=false`，只响应用户点击 Bill 磁贴后的一个 command，不读取节点、事件、目标包、窗口标题，不执行手势、点击或滚动。它的 OCR 运行时仍是未通过隐私发布门的开发原型；用户不应把它理解为支付宝、微信支付或银行的被动监听能力。

当前没有可用的页面文字/节点观察 service。若项目负责人在有脱敏页面样本后明确选择被动读取路线，必须另建只读 `AccessibilityService`：系统只对微信/支付宝的明确目标包和最小窗口事件类型回调，服务只在疑似交易结果/账单详情窗口读取一次节点树并提交待复核证据。它不得取得截图、录屏、轮询、自动点击、打开页面、发起交易或保留非交易窗口内容；实现必须在事件回调外完成有界解析并清除临时节点/文本。

这不是远程钱包 API：没有通知且从未出现可读前台页面的后台余额变化仍不可观察。发布前还必须完成真实模板/可访问性树样本、耗电基线、显著告知、明确同意和相应发行渠道合规审核；没有这些证据不得把它称为微信或支付宝“全自动记账”。

## 用户可见本地声明与资源边界

每次新进程启动，App 先显示“纯本地 / 不走网络”的显著声明：当前 Manifest 不声明 Internet 权限，Bill 不向项目方服务器上传通知、截图、账户、余额或账本数据。该声明不能替代第三方 SDK 的遥测/组件审计；当前截图/Photo Picker OCR 必须同时标为未通过隐私发布门的原型，不能被启动文案掩盖。设置页的三轨教程分别说明通知访问、未来只读结果页和实验性的单次截图/Photo Picker；教程明确图片 OCR 只创建本地待复核项、不证明 provider，替换引擎前不能作为正式能力。

通知、结果页和截图是物理分开的能力：普通通知路径不承担读屏/OCR 成本；未来读屏路线不能截图或操作 UI；截图/Photo Picker 只在用户动作后处理一次有界像素输入，当前只形成待复核转录证据。没有真实设备资源基线前，不以某个虚构的电量百分比承诺低耗电，也不默认要求忽略电池优化。

## 文件/分享入口

- 使用 Storage Access Framework 或 Android Sharesheet 接收用户明确选择的文件/文本；不申请全盘读取。当前 SAF 切片只接收 64 KiB 内的纯文本/CSV/TSV 证据；Sharesheet 另接收一张 4 MiB 内的 `image/png` 收据。实验性 Photo Picker OCR 只读取用户选择的一张图片，不申请广泛相册权限，但仍受 OCR 隐私发布门限制。不持久化 URI 或文件名。
- Sharesheet 接收组件属于外部信任边界；应用不能密码学证明用户手势或发送 App，必须对类型、大小、编码和内容重新验证。
- 文件视为不可信：验证真实内容而非只信扩展名/MIME，限制大小、行列数、嵌套压缩、公式和资源消耗。
- 探测容器 -> 编码 -> 来源/格式版本 -> 列映射；探测结果必须可由用户修正。
- 导入先 dry-run，展示新增、重复、冲突、异常和账户映射，再原子提交。
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
  alipay/             # 规划
    notification/
    statement/
  wechat/             # 规划
    notification/
    statement/
  bank/               # 规划
    common-mapping/
    notification/<provider-id>/
    statement/<provider-id>/
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
