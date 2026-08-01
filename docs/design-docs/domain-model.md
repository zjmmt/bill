# 统一领域模型

- 状态：部分实现；受限 CNY/USD 账本、普通账户余额快照、CNY 投资持仓、来源中立证据链与用户确认对账已有自动化验证，真机与完整 provider 适配仍待完成
- 所有者：项目维护者
- 最后核验：2026-08-01
- 事实来源：当前 `core:model`、`core:domain`、`core:ledger`、来源模块与 Room v11 schema；[ADR-0005](../decisions/0005-manual-ledger-first-slice.md)、[ADR-0006](../decisions/0006-provider-neutral-shared-text-evidence-spine.md)、[ADR-0007](../decisions/0007-source-evidence-lifecycle-and-bounded-storage.md)、[ADR-0008](../decisions/0008-leased-source-evidence-staging-and-orphan-recovery.md)、[ADR-0010](../decisions/0010-notification-first-capture-and-single-receipt-fallback.md)、[ADR-0013](../decisions/0013-bank-card-only-usd-without-fx.md)、[ADR-0014](../decisions/0014-investment-position-snapshots-and-confirmed-events.md)、[ADR-0015](../decisions/0015-immutable-balance-snapshots-and-explicit-differences.md)、[ADR-0016](../decisions/0016-user-reviewed-channel-and-explicit-funded-by.md)

## 建模目标

- 一份外部证据可以被多次解析，但不能因重试重复记账。
- 一笔经济事件可以拥有支付宝、微信、银行和文件等多个外部引用。
- 账务语义与来源解析分开；账户余额由分录推导而不是直接改数。
- 用户修正、自动规则、合并和撤销均有审计历史。

## 当前已实现的首片

当前代码落地受限的 CNY/USD 账本、普通账户余额快照、CNY 投资持仓快照，以及手工录入、`GENERIC/SHARE_TEXT`、不透明文本文件、用户显式映射的 CSV/TSV 行、单张 PNG 收据、受控通知 route 和实验性 `GENERIC/PHOTO_OCR` 转录链。用户可创建现金、银行卡、电子钱包余额或信用卡账户；现金和电子钱包余额只允许 CNY，银行卡和信用卡允许 CNY/USD。现金、银行、钱包和信用卡可追加不可变 `BalanceSnapshot`；比较层按其 `asOf` 汇总活动分录，信用卡从内部负数转为正数欠款，差异只投影为已对上/待解释，不修改账户余额或生成交易。用户还可手填名称和当前金额，或用单张本地 OCR 预填后确认一个 `InvestmentPosition`；代码、份额、成本可空，每个持仓一对一对应独立 CNY `INVESTMENT_SECURITY` 账户。普通期初余额和持仓当前金额都以同币种平衡的 `ADJUSTMENT` 交易纳入账本；手工或来源 Draft 可在确认前编辑经济类型、金额、发生时间、商户、备注、资金账户、投资目标与业务渠道，来源证据身份保持不可变。支付宝/微信渠道只允许 CNY；银行卡/信用卡账户可承载 USD。基金申购 Draft 必须引用真实既有持仓，确认后以 `INVEST_BUY` 在资金账户和投资账户间平衡移动资产，不计普通支出。每个已支持币种有一套隐藏系统收入、支出和期初权益账户，它们不供用户创建、显示或选择。Room schema v11 保存 Account、BalanceSnapshot、InvestmentPosition、带投资目标与审核渠道的 Draft、Transaction、Entry、RawEvent、ParseAttempt、SourceDraftProposal、DraftSourceEvidence、证据载荷生命周期/保留策略、暂存租约、通知观察摘要、结构化账单导入批次、对账 Draft 链接、交易关系、AuditEvent 与全局 command receipt，Overview/账户/持仓/草稿/流水从仓储状态投影。

手工输入属于 `ManualIntent -> Draft`，不创建假的 `RawEvent`；持仓创建是另一条用户确认命令，也不冒充外部证据。上述外部入口都沿 `RawEvent -> ParseAttempt -> SourceDraftProposal -> DraftSourceEvidence -> Draft` 运行；通用解析器不推断 provider，一条来源 Draft 当前仍只链接该来源建议的单条证据。用户确认对账已能在金额、币种、方向、账户角色与时间窗满足硬门时，把一至两条 Draft 原子替换为平衡的 `TRANSFER`、`LIABILITY_REPAY` 或 `REFUND`，同时保存 Draft 链接、交易关系、审计和幂等回执；撤销会恢复相关 Draft。严格支付宝基金确认候选可提出 `INVEST_BUY`，但不能从通知推断或创建标的。`RawEvent` 结构化事实保持不可变；其文件载荷通过独立生命周期在 `AVAILABLE -> CLEAR_PENDING -> CLEARED` 间转换，清除后仍保留来源链、完成的 Draft provenance 与追加式审计。支付宝、微信支付和招商银行只有窄范围实验通知适配器；通用多证据自动合并、一般重复关系、投资赎回/价格/成本批次与期间实体仍是目标模型，不能从 schema 推断为已有功能。

## 核心关系

```mermaid
erDiagram
    SOURCE_CONNECTOR ||--o{ RAW_EVENT : captures
    IMPORT_BATCH ||--o{ RAW_EVENT : contains
    RAW_EVENT ||--|| SOURCE_EVIDENCE_PAYLOAD : owns
    RAW_EVENT ||--o{ PARSE_ATTEMPT : parsed_by
    PARSE_ATTEMPT ||--o| SOURCE_DRAFT_PROPOSAL : proposes
    SOURCE_DRAFT_PROPOSAL ||--o| DRAFT_SOURCE_EVIDENCE : completed_as
    DRAFT ||--o| DRAFT_SOURCE_EVIDENCE : evidenced_by
    DRAFT }o--o| TRANSACTION : confirms_or_links
    TRANSACTION ||--|{ ENTRY : contains
    ACCOUNT ||--o{ ENTRY : affected_by
    ACCOUNT ||--o{ BALANCE_SNAPSHOT : observed_as
    ACCOUNT ||--o| INVESTMENT_POSITION : describes
    TRANSACTION ||--o{ EXTERNAL_REF : evidenced_by
    TRANSACTION ||--o{ TX_RELATION : from
    TRANSACTION ||--o{ TX_RELATION : to
    ACCOUNT ||--o{ ACCOUNT_ALIAS : recognized_as
    RECONCILE_CASE }o--o{ DRAFT : considers
    TRANSACTION ||--o{ AUDIT_EVENT : audited_by
```

## 实体目录

| 实体 | 责任 | 关键字段（概念级） |
| --- | --- | --- |
| `SourceConnector` | 声明来源/机构和能力 | sourceFamily、providerId、version、capabilities、package/format |
| `RawEvent` | 不可变原始证据 | source、payloadRef/encryptedPayload、contentHash、capturedAt、originalZone |
| `SourceEvidencePayload` | 原始文件载荷的独立生命周期，不改写 RawEvent | payloadId、payloadSize、state、clearCommand、reason、requestedAt、clearedAt |
| `SourceEvidenceStaging` | 文件写入到 RawEvent 提交之间的租约所有权与恢复工作 | payloadId、rawEventId、contentHash、payloadSize、state、leaseId、createdAt、expiresAt |
| `ImportBatch` | 一次文件/分享导入 | fileHash、formatVersion、status、counts、startedAt、committedAt |
| `ParseAttempt` | 某解析版本的结果 | rawEventId、parserVersion、ruleVersion、outcome、diagnostics、confidence |
| `SourceDraftProposal` | 某次解析产生的待补全来源建议 | rawEventId、parseAttemptId、candidate、state、completedDraftId |
| `DraftSourceEvidence` | 已创建 Draft 到来源建议/解析/原始事件的不可变链接 | draftId、proposalId、parseAttemptId、rawEventId、source/capture |
| `ManualIntent` | 用户主动发起的手工候选，不冒充外部证据 | commandId、type、amount、counterparty、note、occurredAt |
| `Draft` | 待决定候选 | state、candidateFields、hardBlocks、userEdits、occurredAt |
| `Transaction` | 经确认经济事件 | txType、occurredAt、postedAt、reviewStatus、sourceMode、note |
| `Entry` | 对账户的金额影响 | transactionId、accountId、signedMinorUnits、currency、entryRole |
| `Account` | 资产/负债/投资容器 | accountType、provider、parentId、isOwn、archived |
| `InvestmentPosition` | 用户确认的持仓估值快照；当前一对一绑定投资账户 | accountId、instrumentCode、name、currentValue、units?、costBasis?、asOf、sourceMode |
| `AccountAlias` | 来源文本到真实账户的映射 | providerId、maskedIdentifier、normalizedLabel、confidence |
| `ExternalRef` | 多来源外部引用 | transactionId、providerId、refType、refValueHash、displayMask |
| `TxRelation` | 交易/证据关系 | fromId、toId、relationType、score、evidence、decision |
| `ReconcileCase` | 未匹配/冲突工作项 | caseType、candidateIds、state、resolution、createdAt |
| `Rule` | 本地解析/映射/分类规则 | ruleType、scope、version、condition、action、priority、enabled |
| `AuditEvent` | 追加式变更审计 | actor、action、entityId、beforeRef、afterRef、reason、at |
| `OpeningBalance` | 指定日的期初事实；首片通过 `ADJUSTMENT + Entries` 表示，不维护可变余额列 | accountId、asOf、minorUnits、currency |
| `BalanceSnapshot` | 用户观察到的不可变余额/信用卡欠款证据；当前只支持手工来源 | accountId、observedBalance、asOf、recordedAt、note?、sourceMode、creationCommandId |
| `Period` | 月结/期间状态 | start、end、status、closedAt |
| `Liability` | 负债条件 | accountId、creditor、apr、statementDay、dueDay |
| `LiabilityStatement` | 某期应还信息 | period、statementBalance、minimumDue、dueDate、status |
| `Security` | 投资标的 | code、name、assetClass、currency |
| `HoldingLot` | 持仓成本批次 | securityId、accountId、units、costMinorUnits、acquiredAt |
| `SecurityPrice` | 可选市值证据 | securityId、price、currency、asOf、source |

## 稳定枚举

### DraftState

`NEW`、`WAITING_USER`、`EDITED`、`AUTO_CONFIRMED`、`CONFIRMED`、`LINKED`、`DISMISSED`、`ERROR`。

完整枚举是目标状态机。当前账本 Draft 实现 `WAITING_USER`、`EDITED`、`CONFIRMED`、`LINKED` 与 `DISMISSED`；`LINKED` 由用户确认的转账、还款或退款替代交易持有，撤销后恢复为 `WAITING_USER`。来源建议另有 `WAITING_USER`、`COMPLETED`、`DISMISSED` 状态。单证据来源链接与对账交易的一至两条 Draft 链接已实现；自动确认、通用多证据合并和 provider 错误重放仍须等待样本门。

### TxType

`EXPENSE`、`INCOME`、`TRANSFER`、`TOPUP`、`REFUND`、`LIABILITY_DRAW`、`LIABILITY_REPAY`、`INVEST_BUY`、`INVEST_SELL`、`FEE`、`ADJUSTMENT`。

### RelationType

`DUPLICATE_OF`、`PENDING_POSTED`、`FUNDED_BY`、`WRAPPED_BY`、`TRANSFER_PAIR`、`REFUNDS`、`REPAYS`、`SPLIT_FROM`、`REPLACES`、`IMPORT_SUPERSEDES`。

关系必须定义方向性：例如 `REFUNDS` 有方向，`DUPLICATE_OF` 的规范代表需要确定；不能仅靠字符串约定。

## 金额与时间

- 账务金额以 `minorUnits: Long` + ISO 币种保存；禁止 Double/Float。
- 原始文本金额另存为证据，不能作为计算字段。
- 保存 `capturedAt`、`occurredAt`、可选 `postedAt` 和原始时区/偏移；缺失时记录精度，不伪造秒级时间。
- 多币种交易必须显式包含换汇与费用分录；未设计完成前不允许以单币种平衡规则误过账。

### 首片有符号约定

- 资产与费用增加为正；负债、收入和权益增加为负。
- 信用卡欠款在账本内部为负，UI 的“欠款”数值按用户视角显示其绝对值。
- 手工支出：未分类费用为正，资产资金腿或信用卡负债增加为负。
- 手工收入：资产资金腿为正，未分类收入为负；信用卡不能作为收入资金账户。
- 投资买入：现金/银行/电子钱包资金腿为负，既有 `INVESTMENT_SECURITY` 持仓账户为正；同币种内归零且不经过费用账户。
- 三个隐藏系统账户分别承载未分类费用、未分类收入与期初权益；它们不供用户选择，也不进入用户账户列表或净资产账户集合。
- 当前账本只接受 CNY/USD。总览在应用层按已确认分录逐币种聚合，不执行跨币种 SQL 求和，也不显示虚假的汇率合计。

## 不变量

1. `RawEvent` 内容与哈希一经提交不可修改。
2. `(connector, contentHash, captureScope)` 的重复采集可识别，但不能删除审计证据。
3. 正式交易至少包含两条有意义的分录或符合显式单边调整规则。
4. 同币种内所有分录的 signed minor units 合计为零。
5. `Draft` 只能通过合法状态转换；重复命令幂等。
6. 用户确认字段有来源与优先级，自动重放不能无理由覆盖。
7. 一个外部 ID 可作为强证据，但其作用域必须包含 provider/账户/类型，避免跨机构碰撞。
8. 删除原始载荷后保留最小审计摘要时，UI 必须明确“证据已清除”。
9. 同一 command ID 与同一规范请求可以安全重试；若操作、目标或指纹不同则为命令碰撞，不能复用旧结果。
10. 撤销已确认手工交易只把 Transaction 标为 `VOIDED`，余额仅汇总 `ACTIVE` Entries；保留交易/分录并把原 Draft 恢复为 `WAITING_USER`。
11. `ParseAttempt.rawEventId`、来源建议、Draft evidence 与 Draft 的外键和语义字段必须指向同一证据链；不一致时写入/观察失败关闭。
12. 相同 `(connectorId, contentHash, captureScope)` 只标记可能重复，不自动删除观察、合并 Draft 或生成正式交易。
13. 忽略来源建议只标记 `DISMISSED` 并追加审计，不等同于清除证据文件。
14. 文件载荷只允许 `AVAILABLE -> CLEAR_PENDING -> CLEARED`；删除文件成功前不得写成 `CLEARED`，失败保持 `CLEAR_PENDING` 并允许按同一工作项恢复。
15. 自动保留/容量清理只选择没有 `WAITING_USER` 来源建议的最旧载荷；用户主动清除会先原子 dismiss 待复核建议，并保留结构化链和审计。
16. 新分享文件写入前必须有 `ACTIVE` staging 租约；RawEvent/生命周期事务原子消费租约。恢复只能 CAS 接管到期租约，陈旧回滚不得删除新租约或已提交载荷。
17. `InvestmentPosition` 必须是一对一的非系统、未归档 CNY `INVESTMENT_SECURITY` 账户；当前金额为正，未知代码/份额/成本保持 null，不伪造成零。
18. `INVEST_BUY` Draft 必须引用真实既有持仓账户，资金账户与投资账户不同；普通收入/支出不得携带投资目标。通知只能提出事件，不能自动创建持仓。

## Room 落地门

Room schema v1/v2/v3/v4/v5/v6/v7/v8/v9/v10 与 `v1 -> v2 -> v3 -> v4 -> v5 -> v6 -> v7 -> v8 -> v9 -> v10` 正式迁移已经导出，且仓储把账户/持仓创建、来源建议提交/忽略、草稿编辑/确认、证据链接、生命周期请求、暂存消费、结构化账单批次、对账确认、交易关系、审计、命令回执和 void 放在 Room 事务内；运行时没有 destructive fallback。迁移、重放/碰撞、证据存储、两阶段清除、租约/孤儿恢复、磁盘数据库重开、keyset 分页和来源/生命周期跨表损坏有自动化测试；v9→v10 审核渠道迁移和新增 `FUNDED_BY` 仓储测试当前只完成源码编译，仍待真机执行。

复杂关系查询、10 万级导入、已清除历史增长下的完整性查询基准、多适配器并发压力、应用层加密、FTS/搜索、真实系统强杀矩阵和备份恢复仍是后续落地门。未来数据库事实生成器建立后，应由构建生成 `docs/generated/database-schema.md`；本文件不复制列级 schema。
