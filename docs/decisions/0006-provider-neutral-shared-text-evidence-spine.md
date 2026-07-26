# ADR-0006：来源中立的分享文本证据主干

- 状态：已接受；通用分享文本切片已实现，发布门未完成
- 所有者：项目维护者
- 最后核验：2026-07-25
- 事实来源：当前 `source:contract`、`source:pipeline`、`source:generic-share-text`、`source:review-contract`、`application`、`data:local` 与 Android UI 实现；ADR-0001、ADR-0003、ADR-0005

## 背景

项目需要把外部证据接入 `RawEvent -> ParseAttempt -> Draft -> Transaction/Entries`，但当前没有可提交的支付宝、微信支付或银行脱敏样本。直接编写 provider 解析器会制造未经证据支持的“已接入”声明；继续只保留接口骨架，又无法验证载荷读取、不可变证据、解析版本、跨表完整性、复核和错误恢复。

Android Sharesheet 的显式 `ACTION_SEND text/plain` 可以提供一个最小真实入口，用来验证来源中立主干。该入口只能证明 Bill 能安全接收用户主动分享的一段文本，不能证明文本来自哪个 App，也不能替代任何 provider 的格式适配器。

## 决定

### 信任边界与入口

- `MainActivity` 接收 `ACTION_SEND` + `text/plain`，活动保持 `exported=true`，因此任意 App 都可能构造相同 Intent。代码和 UI 必须把载荷视为不可信外部输入，不能把“收到 Intent”解释为已经证明用户从某个支付 App 分享。
- 首版统一标记为 `GENERIC/SHARE_TEXT`，不根据正文、调用方或包名猜测支付宝、微信、银行或具体机构。
- 不新增网络、通知监听、短信、存储、无障碍等危险权限；核心路径保持离线。

### 有界、可校验证据

- 在创建字符串副本和 UTF-8 字节数组前先做字符数上限检查；严格 UTF-8 编码遇到畸形代理项时失败。
- 文本和最终字节数均受 64 KiB 上限约束。空内容、过大、编码失败、存储失败、ID 碰撞、读取失败和提交失败使用封闭诊断与可操作本地文案，不回显原文。
- 证据使用不含用户语义的 opaque 文件名写入 `noBackupFilesDir/source-evidence/`，原子替换后再提交元数据；读取时校验长度和 SHA-256。
- 该私有证据目前依赖 Android 应用沙盒和 `allowBackup=false`，不声称已经应用层加密。

### 解析、建议与复核

- `SourceIngestionService` 只向已注册、能力匹配的解析器提供经过大小与哈希校验的 `EvidenceInput`。
- `RawEvent` 不可变；相同 ID、相同内容是安全重放，相同 ID、不同内容失败为冲突。每次解析以 parser/rule identity 追加 `ParseAttempt`，不覆盖旧结果。
- `GenericShareTextParser` 只确认“存在一段可读取的分享文本”。它不从任意文本猜金额、交易方向、商户、账户或 provider，始终生成需要用户补全的来源建议。
- 用户补全金额、收入/支出和说明后，系统创建普通外部来源 Draft；选择实际资金账户并确认后才生成平衡 Transaction/Entries。
- `parse_attempts`、`source_draft_proposals`、`draft_source_evidence` 与账本状态必须保持同一 RawEvent/ParseAttempt/Proposal/Draft 证据链。写路径检查目标链，状态流对全局跨表不一致失败关闭，不发布部分或看似正常的快照。

### 重复、幂等与忽略

- `(connectorId, contentHash, captureScope)` 的重复观察只产生“可能重复”信号；Bill 不自动合并，也不因此直接入账。
- 捕获、补全和忽略使用稳定 command ID、请求指纹、原子事务与 command receipt 区分安全重放和命令碰撞。
- 忽略先明确告知待办目前不可恢复，经二次确认后把来源建议标记为 `DISMISSED` 并追加审计；忽略动作本身不删除 RawEvent、ParseAttempt 或证据文件。后续载荷保留与用户清除由 [ADR-0007](0007-source-evidence-lifecycle-and-bounded-storage.md) 独立治理。已完成/已忽略的分享 Intent 只有在匹配 command ID 的结果被消费后才从 Activity 清除；过期结果也不得抢占更新分享的复核 UI。

### Room 迁移

- 本决定把 Room schema 从 v2 升到 v3，新增 `parse_attempts`、`source_draft_proposals` 和 `draft_source_evidence`，并保留正式 `v2 -> v3` 迁移；运行时继续禁止 destructive fallback。当前数据库已由 ADR-0007 继续迁移到 v4，但不改变本决定的来源主干语义。
- 本决定改变数据库 schema、外部 Android 入口和敏感数据保留面，合并前需要高级工程师再次复核数据库迁移、API 契约与安全边界。

## 当前验证与限制

纯 Kotlin、Application、Room JVM 测试和完整 `test` 已通过；Room v1→v2→v3 迁移、证据存储、来源仓储、重复信号、跨表完整性、忽略/重放在 MuMu API 32 上由 18 个 connected instrumentation 测试通过。`lint` 与 `assembleDebug` 已分别通过。MuMu 手工验证了冷启动和运行中分享、竖屏复核、无原文回显、重复提示、忽略二次确认以及 Intent 消费。

这些证据不等于发布级来源支持：

- 支付宝、微信支付和银行仍无真实适配器、脱敏样本和回放测试，运行时继续显示 `FALLBACK_REQUIRED`。
- 导出的分享 Activity 无法密码学证明用户手势或来源 App；它只是产品设计上的显式分享路径。
- 用户可见删除、保留期限和分页已由 ADR-0007 实现；staging 租约、共同容量和有界孤儿回收已由 ADR-0008 实现；压力、真实系统强杀和真机门仍未完成。
- 尚无自动化 Compose、进程死亡/恢复、真实港版/国行 Samsung 与国行小米验证。
- 当前重复信号只提示用户复核，不是完整 `TxRelation` 去重或跨来源对账。

## 结果

- 正面：首次用真实 Android 入口验证了来源中立、不可变、可重放、可诊断且不会自动过账的证据主干；后续 provider 适配器无需直接依赖 Room 或 UI。
- 代价：为了不猜测交易事实，用户必须补全通用分享文本；被忽略的证据在保留策略到期或用户清除前仍占用私有存储。
- 风险：恶意或频繁外部 Intent 仍可能冲击 staging 与来源建议；即使已有有界生命周期，也不得在压力/进程死亡门完成前把该入口作为无限量、发布级导入能力。

## 被否决方案

- 从任意分享文本用“万能金额正则”直接生成交易：来源、语义和资金账户都无法可靠证明。
- 用发送方包名宣称 provider：外部 Intent 可伪造，且分享链路未提供可信来源证明。
- 相同哈希直接自动合并或丢弃：相同文本可能对应不同观察，必须保留证据并由用户判断经济事件。
- 忽略时连同 RawEvent 或证据一起删除：会混淆待办操作和数据生命周期；载荷清除必须是独立、用户可见且经过验证的语义。
- 只增加硬容量配额：在没有保留/删除 UI 时会把暂时资源保护变成永久锁死。
