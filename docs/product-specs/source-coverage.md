# 数据源覆盖与支持状态

- 状态：部分实现；手工、通用分享文本、不透明文本证据、本地 CSV/TSV 显式映射、用户显式单次 PNG 收据、受控通知 route catalog/控制面、用户确认对账和持久观察去重已有代码；支付宝、微信与招商银行有 4 条默认关闭的实验性通知 route；Quick Settings/Photo Picker 本地 OCR 已有未签名 Release 静态审计，但两类路径的真机发布门均未完成
- 所有者：项目维护者
- 最后核验：2026-07-31
- 事实来源：当前 Android/Room/来源模块、项目负责人范围、Android/平台官方资料、财付通公开隐私政策、来源适配器设计、ADR-0006、ADR-0007、ADR-0008、ADR-0009、ADR-0010、ADR-0011、ADR-0012

本文件定义目标能力和“什么才算支持”，同时记录生成矩阵上线前的人工核验快照。当前已有受限 CNY/USD 手工账本闭环，以及 provider-unverified 的显式分享文本、不透明文本文件、用户逐列映射 CSV/TSV 和单次 PNG 收据截图证据/复核闭环；另有 Quick Settings 单次截图与 Photo Picker 最多 5 张串行 OCR。CSV/TSV 只提供来源中立映射，不识别银行或支付平台。PP-OCRv6 small + ONNX Runtime/OpenCV 已静态随包，并通过源码/AAR 与当前未签名 Release 分包的权限、组件、ABI、模型、体积和 16 KiB ZIP 对齐审计；实际中英/日英推理、ELF LOAD 段页兼容、目标真机资源和签名发行证据仍未完成。21 条本地真实通知样本经用户授权离线分析后，项目新增支付宝支出/余额收款、微信英文付款完成、招商银行快捷支付退款 4 条窄范围通知 route；它们默认关闭，只用脱敏夹具自动回放，尚未完成真机系统 callback、更新/重启与资源验收。专属文件格式与其他通知事件仍未实现。现金、支付宝余额和微信零钱只能是 CNY；只有银行卡和信用卡可为 USD，且没有汇率换算。电子钱包余额与银行卡是独立资金账户：银行卡流水不能补齐钱包余额内的红包、个人转账、余额消费或余额退款。未来生成器建立后，逐格式实际矩阵必须由代码和测试写入 `docs/generated/`，不得手工伪造。

## 支持标签

- `Supported / 正式支持`：存在官方用户路径、真实脱敏样本、自动回归测试、错误回退和已验证版本范围。
- `Best effort / 尽力支持`：可用但受通知文案、OEM、上游 App 或系统调度影响，不承诺完整性。
- `Experimental / 实验性`：涉及受限权限、平台审核、合作条件或尚未稳定的格式。
- `Research gate / 研究门`：公开资料或用户报告显示可能存在入口，但当前设备、账户、地区、版本或输出格式尚未验证；不能向用户宣称支持。
- `Unsupported / 不支持`：违反产品安全边界、不可验证或不是个人数据通道。
- `Not implemented / 未实现`：只有规格，没有可运行代码和证据。

`FALLBACK_REQUIRED` 是当前 UI 的来源健康状态，不是新的支持标签。它表示该来源没有可用连接或适配器，用户只能转到共同的手工录入路径；不能据此声称该来源是 Best effort 或 Supported。

## 当前实际状态

| 能力 | 代码状态 | 运行时/验收状态 | 证据与限制 |
| --- | --- | --- | --- |
| 支付宝通知与文件 | 通知部分实现；文件未实现 | `FALLBACK_REQUIRED`；2 条通知 route 为 `Experimental` 且默认关闭 | 支出通知和余额收款通知各有独立 metadata/content rule、provider parser 与脱敏回放；只提取单一 CNY 金额和明确方向。扫码成功页无通知、其他通知文案、余额内部事件、文件导入和真机 callback 均未覆盖 |
| 微信支付通知与文件 | 通知部分实现；文件未实现 | `FALLBACK_REQUIRED`；1 条通知 route 为 `Experimental` 且默认关闭 | 只覆盖当前英文环境 `Weixin Pay` + 单一 CNY 金额 + `paid` 的付款完成通知。红包、转账请求和提现状态拒绝；该通知与普通消息共用频道，启用时会在本机读取同频道有界正文后过滤。普通聊天通常不匹配，但同名联系人发送完全相同格式时无法由现有元数据证明来源，只能依靠待复核而非自动过账；仍缺真机 callback 与资源证据 |
| 银行通知与文件 | 招商银行通知部分实现；其他银行/文件未实现 | `FALLBACK_REQUIRED`；1 条招商银行 route 为 `Experimental` 且默认关闭 | 只覆盖招商银行 App 专用通道中的快捷支付退款，登录通道和 Samsung 短信通知明确排除。普通扣款、入账、基金、其他银行、文件和真机 callback 均未覆盖 |
| 通用受控通知 route 证据 | 已实现并承载 4 条实验 route | 不改变三类来源的 `FALLBACK_REQUIRED` | 静态 route catalog、默认关闭的 app-private route 开关、metadata gate、parser 注册和 RawEvent identity 由同一 route 提供；包名 + Android 通知渠道 + 类别（含 null）必须精确匹配。设置页只显示 catalog 安全标签；开启先持久化，关闭先收紧本进程门禁，失败会冻结其他 route 并要求重试或在重启前撤销系统通知使用权。关闭或未知 route 不读正文、不落库；ingress 会再次检查开关。候选使用容量 16 队列、Room v7 HMAC 观察租约和 command 恢复去重；provider parser 在运输后复核 template/version/content，只产出金额与方向的来源建议，复核 UI 不显示包名、频道或原文，也不直接过账。S24U-HK/API 36 的既有 3 个 instrumentation 只验证偏好控制面，不是新 route 的系统 callback 或 provider 证据 |
| Debug 通知模板采样器 | 已实现研究工具；不属于产品能力 | S24U-HK/API 36 上 10 个采样器合成用例与既有 3 个 route 用例共 13/13 通过；21 条真实 callback 已只读导出并本地保留 | 仅 Debug 桌面入口可由用户手动开始/停止/清除；只接受开始之后所选精确包名的新 callback，跨进程无限期恢复，不设自动时间、条数或文件大小限制。仅追加五个有界正文域到 app-private no-backup NDJSON，不读历史/key/actions/RemoteViews，也不生成账本证据。11 张过程截图、通知文件和配置有两份 Git 忽略的本机私有副本；设备端原始数据未删除，原文不进入仓库、日志或文档。Release 无页面、无原始文件名和研究包名 |
| 通用手工录入/期初余额 | 已实现，发布门未完成 | CNY 路径经自动化与 MuMu API 32 验证；USD 自动回归通过，相关 Android instrumentation 只编译、未在设备执行 | 现金、电子钱包余额只允许 CNY；银行卡和信用卡允许 CNY/USD。收入/支出先 Draft，选择同币种资金账户后平衡确认；无跨币种汇率或总额 |
| 用户显式分享 `text/plain` | 已实现，发布门未完成 | 生命周期单测、Room v5 staging 与 MuMu API 32 已验证；当前 schema 为 v7 | 始终为 `GENERIC/SHARE_TEXT`；64 KiB、严格 UTF-8、私有 no-backup 证据和哈希校验；不猜 provider/金额/类型/账户，用户补全；重复只提示；已有逐项两阶段清除、7/30/90/永久保留、已提交与暂存共用的 16 MiB/512 份预算、租约恢复、有界孤儿扫描和 keyset 分页；缺压力、真实系统强杀、自动化 Compose 和完整真机证据 |
| 用户显式选择不透明文本文件 | 已实现，发布门未完成 | 既有 JVM/ViewModel 回归通过；SAF 真机待验收 | `GENERIC/STATEMENT_IMPORT` 的证据回退；当次读取 `text/plain`、CSV 或 TSV，64 KiB 有界复制为一条证据，不持久化 URI/文件名，不解析行或猜 provider/金额/账户 |
| 用户显式映射 CSV/TSV | 已实现候选，发布门未完成 | 纯 Kotlin 解析/映射、application 批次、ViewModel 状态机与来源投影测试以及全量 JVM/Lint/Debug/Release 构建通过；Room v7 AndroidTest APK 已编译，设备 Room/SAF 仍待验收 | 当次 SAF 读取严格 UTF-8，文件最多 2 MiB、5000 数据行、64 列；用户必须映射日期、金额、方向、对方和可选参考号，选择 CNY/USD。有效行各自生成可清除 `GENERIC/STATEMENT_IMPORT` 证据与待复核建议；文件名/URI 不保存，未知表头不自动猜列，拒绝行只留安全错误码，停止后同文件+同映射可续传。行记录使用事务内 O(1) 增量汇总，不再逐行全表扫描。不是任何钱包或银行格式支持声明 |
| 用户显式分享单张 PNG 收据截图 | 已实现，发布门未完成 | JVM 结构/边界、application 与 ViewModel 回归及完整构建已覆盖；Sharesheet 真机和 Room instrumentation 待本轮验收 | 只接收系统 `ACTION_SEND` 的当次 `content://`、声明与解析后均为 `image/png` 的一张图片；4 MiB 有界复制，验证签名/IHDR/分块 CRC/终止分块后作为 `GENERIC/SHARE_FILE` 保存。无相册权限、无 URI/文件名持久化、无图像解码/预览/OCR、无金额/来源推断；只打开用户填写的手工复核表单，不是 provider 支持声明 |
| Quick Settings 单次截图与 Photo Picker 有界多选 OCR | 已实现候选，发布禁止 | 模型哈希/中英日字典、AAR 边界以及未签名 Release 分包的权限、组件、ABI、模型、体积与 16 KiB ZIP 对齐已有静态证据；实际语言回归、ELF 页兼容和目标真机资源证据缺失 | 磁贴只在用户点击后截取当前画面一次；command 先有 90 秒可取消阶段，在证据准入/容量清理/写入前用 CAS 原子交接取消权与本地提交。handle 注册中不提前宣称取消成功；提交前先释放像素，local commit 另有 15 秒协作式截止，闸门后的超时、取消或非致命异常统一提示“结果未确认”。提交/既成结果不可取消而回调缺失时最多再等一次 15 秒，随后释放并隔离迟到回调。Photo Picker 每批只处理前 1–5 张、逐张串行、显示已处理数量并只汇总一次，活动批次拒绝第二批。磁贴/无障碍设置入口深链到 Bill 教程；无节点读取、手势、后台捕获、相册广泛权限或自动过账。PP-OCRv6 small 静态随包，ONNX Runtime 建会话前关闭 telemetry；原始像素只作瞬时输入，`GENERIC/PHOTO_OCR` 转录只生成可编辑待复核项。不能据此声称任一 provider 支持 |

手工录入是 `ManualIntent -> Draft`，不会制造外部 `RawEvent`。分享文本、不透明文件、映射 CSV/TSV 行、单次 PNG 截图与实验性单次 OCR 分别进入同一 `RawEvent -> ParseAttempt -> source proposal -> user-completed Draft` 主干；映射 CSV/TSV 先以文件摘要+映射摘要建立 `ImportBatch`，再为每个有效行建立独立证据和稳定 command。Debug 通知模板采样器停在研究文件，不进入这条业务主干。Sharesheet/SAF 的接收方、MIME、用户映射或 Debug 目标包名都不能证明 provider；PNG 和 OCR 入口同样只证明用户提供了一张不可信的当前凭证。这些通用证据入口都可以作为三类来源不可用时的回退，却不能替任何来源满足格式漂移、授权、provider 归属或脱敏样本验收门。

## 一等数据源的完成定义

支付宝、微信支付和银行每一类上线都必须同时具备：

1. 连接/授权说明与暂停、删除入口。
2. 至少一条可用采集或导入路径，以及共同的手工回退。
3. 账户映射、原始证据保真和统一草稿体验。
4. 来源专属脱敏样本、解析/导入回归和幂等测试。
5. 与其他来源的去重/资金关联测试。
6. 权限、数据保留、失败模式和支持版本披露。

只预留接口不算支持。覆盖同级不表示自动化程度或上游文件格式完全相同。

## 目标覆盖矩阵

| 来源 | 入口 | MVP 目标标签 | 产品说明 |
| --- | --- | --- | --- |
| 支付宝 | App 通知 | Best effort | 付款、收款、退款、转账等按真实样本建立版本化模板；不能补历史 |
| 支付宝 | 用户导出的收支明细/账单文件 | Research gate | 公开帮助提及个人收支明细证明；当前账户/地区/客户端入口、输出容器、跨度和密码流程均须真机脱敏样本确认 |
| 支付宝 | 商户账单 API | Unsupported | 面向商户，不是任意个人账户历史通道 |
| 微信支付 | App 通知 | Best effort | 只对已验证且含足够交易事实的模板有效；无金额的未领取红包、领取/发送红包或个人转账等无通知动作不在完整性承诺内 |
| 微信支付 | 用户下载的个人账单 | Research gate | 财付通公开隐私政策描述一般下载入口；港版设备、账户、地区、客户端是否显示入口及导出内容仍须用户明确同意下的脱敏实机确认 |
| 微信支付 | 商户账单 API | Unsupported | 需要商户号/证书，不是个人账单入口 |
| 银行 | 用户授权的银行 App 通知 | Best effort | 每家银行独立模板包；过滤营销、OTP 和非交易通知 |
| 银行 | 通用 CSV/TSV/XLS/XLSX 对账单映射 | Supported | 用户映射日期、金额、方向、描述、流水号、余额和账户；预设需逐行验证 |
| 银行 | OFX/QFX | Experimental | 可提供通用解析器，但不声称大陆主要银行普遍导出 |
| 银行 | PDF/OCR 对账单 | Experimental | 只有格式稳定且有样本时支持；所有识别结果先确认 |
| 银行 | 统一个人开放 API | Experimental | 仅在具体银行和正式合作协议下评估；当前无统一公共消费者接口假设 |
| 通用 | 手工录入/期初余额 | Supported | 目标标签；当前受限 CNY/USD 切片已有自动化，CNY 还通过 MuMu；现金/钱包仅 CNY、银行卡/信用卡可用 USD，真机和发布门仍未完成，是所有来源的共同回退路径 |
| 通用 | 用户从短信/邮件/文件 App 主动分享文本 | Supported | 目标标签；当前 `text/plain` 切片已实现但仍属发布前部分实现，不读取整箱，只处理显式分享内容，不证明发送 App |
| 通用 | 用户经 SAF 选择文本/CSV/TSV | Experimental | 当前同时保留 64 KiB 不透明文本证据和 2 MiB/5000 行的显式 CSV/TSV 映射；有效行逐条进入待复核，不含 XLS/XLSX、余额/账户列、provider 预设或 provider 识别 |
| 通用 | 用户从系统分享单张 PNG 收据截图 | Experimental | 当前只提供有界、私有、无 OCR 的 `GENERIC/SHARE_FILE` 手工复核入口；不读取相册、不保存 URI/文件名、不推断财务字段或 provider |
| 通用 | Quick Settings 单次截图 / Photo Picker 最多 5 张串行 OCR | Experimental，当前发布禁止 | 只在用户点磁贴或选择图片后处理；不绕过 `FLAG_SECURE`，结果必须确认。随包 PP-OCRv6/ONNX 候选已有未签名 Release 静态审计，但尚未完成实际中英/日英推理、ELF 页兼容、签名发行和三台目标真机验收；不能用作 provider 支持声明 |
| 通用 | 直接读取短信箱 | Experimental / 默认排除 | 受 Play 权限与合规审核约束；基础发行包不包含该权限 |
| 通用 | Accessibility 自动操作支付/银行 UI | Unsupported | 脆弱且风险高，不作为产品路线 |
| 通用 | 读取其他 App 私有目录、Root、抓包 | Unsupported | 违反系统/产品安全边界 |

## 来源专属最低要求

### 支付宝

- 将支付通道与支付宝余额分开；绑卡提示映射到真实银行/信用卡账户。
- 支付宝余额内发生的收付不能由银行流水反推；只有明确的充值、提现或绑卡支付才可关联银行资金腿。
- 订单号、商家订单号、资金方式文本等作为可选外部证据，不强行塞入单一字段。
- 通知与后导入账单关联，后者补全外部 ID，不新增重复交易。
- 投资证明/手工持仓属于独立能力，不让支付账单适配器承担持仓抓取。

### 微信支付

- 微信支付通道与微信零钱分开；零钱通若纳入范围，使用独立投资适配器。
- 红包、个人转账、群收款和代收代付不能一律默认为收入/支出。
- 未领取红包、未接受转账、过期或原路退还而未进入零钱的事件不创建资金分录；只有确证入余额后才是待关联的资金流。
- 账单文件的实际入口、容器、编码、表头和导出限制必须由用户明确同意下的真机脱敏样本确认；公开隐私政策不是当前设备可用性的证明。
- 只有绑卡/信用卡支付才能与银行卡扣款建立 `FUNDED_BY`；零钱余额内收付不能由银行卡补账。充值、提现和明确的自有账户转入另走 `TRANSFER_PAIR`/`TOPUP`。

### 银行

- “银行”是适配器家族，不是一个万能正则。具体 App/格式有独立 provider ID 和版本范围。
- 借记账户与信用卡负债分开；银行也可直接产生工资、手续费、利息、现金存取和直接消费。
- 保存交易时间、入账时间、记账日等不同时间语义；未知时不伪造精度。
- 通用映射器与银行预设分开声明。没有样本和回归的银行只能称“可尝试映射”，不能称支持。

## 验收门

每个适配器提升支持标签前必须提供：

- 来源/机构 ID、能力、包名或格式版本。
- 至少成功、缺字段、重复、格式漂移和敏感内容五类脱敏样本。
- 解析结果与来源证据定位；未知格式不会误入账。
- 重复采集/导入幂等，且能参与跨来源关联。
- 用户可见授权、失败、暂停、删除和回退说明。
- 日志脱敏、资源上限和恶意输入测试；通知还须证明非命中正文读取数/持久化数为 0、更新/重启幂等和目标 OEM 的资源基线。

通用分享文本和单次 PNG 收据若要从“已实现切片”升级到 `Supported`，还必须提供大量或恶意 Intent 压力测试、自动化 Compose、真实系统强杀切点和目标真机矩阵；PNG 路线还须补系统 Sharesheet MIME/临时授权回归。Room v5 租约已关闭核心 staging 孤儿窗口，但不能只凭该实现、MuMu、设置页或 exported Intent 的存在升级。

## 官方依据与待验证边界

- Android 通知监听需要用户在系统设置中显式授权，且不是历史账单 API：[NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService.html)。
- Android 15 会向普通监听器隐去识别出的 OTP，解析器必须容忍删减：[Android 15 行为变化](https://developer.android.com/about/versions/15/behavior-changes-all?hl=zh-CN)。
- 文件应通过用户选择的 Storage Access Framework 导入：[Android 文档选择器](https://developer.android.com/training/data-storage/shared/documents-files)。
- 财付通公开隐私政策写有微信支付交易记录查询及下载路径，但它只证明一般产品说明；当前港版设备、账户、地区、版本及输出格式仍未验证：[财付通公告](https://posts.tenpay.com/posts/021de3926a292d910000a7de5ea7e100.html)。
- 支付宝提供个人收支明细证明路径，但实际导出格式仍需真机验证：[支付宝帮助](https://help.alipay.com/lab/help_detail.htm?help_id=553265)。
- 支付宝与微信公开的账单 API 是商户通道，不作为个人账单方案：[支付宝商户说明](https://help.alipay.com/enterprise/knowledgeDetail.htm?knowledgeId=201603401890)、[微信商户账单](https://pay.wechatpay.cn/doc/v3/merchant/4013071218)。

外部事实可能变化。适配器发布时必须重新核验官方路径和商店政策，并更新“最后核验”日期。
