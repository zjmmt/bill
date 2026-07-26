# ExecPlan 0003：本地 CSV/TSV 账单映射与待复核导入

- 状态：进行中
- 所有者：项目维护者
- 最后核验：2026-07-26
- 事实来源：项目负责人“先自动草稿、后人工确认”的本地优先约束、[来源覆盖矩阵](../../product-specs/source-coverage.md)、[采集确认规格](../../product-specs/capture-review-reconcile.md)、[采集适配器设计](../../design-docs/ingestion-and-source-adapters.md)、[统一领域模型](../../design-docs/domain-model.md)

## 目的与用户可见结果

用户可从 Android 文档选择器选择一份本地 CSV 或 TSV，先看到有限行数的表头和预览，在本机把日期、金额、收支方向、对手方和可选参考号映射到列。确认后，每一条通过校验的记录进入不可变来源证据和来源待复核列表，预填金额、收支方向、日期和对手方；用户仍必须确认经济类型和资金账户，绝不批量自动正式入账。

这是一条来源中立的本地导入能力，不是某家银行、支付宝或微信支付的格式支持声明。未知编码、复杂容器、超限文件、缺失必填字段、冲突方向或无法解析的行必须在预览中失败关闭或标成异常，不能猜数据。

## 范围与非目标

包含：

- 用户逐次经 SAF 选择 UTF-8 CSV/TSV；不申请全盘存储或持久 URI 权限。
- 有界字节、行数、列数、单元格长度和 CSV 引号/换行解析；不使用网络、OCR 或第三方解析服务。
- 本地列映射与有限预览；最小字段为日期、金额、方向和对手方，外部参考号为可选。
- 文件哈希、映射版本、行结果和进度的 `ImportBatch` 结构化历史；每一条可采纳记录拥有独立、可清除的来源证据和稳定幂等键。
- 生成 `GENERIC/STATEMENT_IMPORT` 来源建议，预填可验证候选字段，但不自动确认、关联或合并。

不包含：

- 直接宣称任何银行/支付机构已支持，或为未知 CSV 自动选择列含义。
- XLS/XLSX、PDF、压缩包、图片、密码文件、公式计算、网络下载或云端解析。
- 批量正式入账、自动账户匹配、跨来源自动去重、余额反推、投资/还款/转账语义推断。
- 保存原始 SAF URI、文件名、外部存储路径或发送应用身份。

## 上下文与仓库导航

- 现有入口 `SelectedTextFileIngestionService` 只把整份小文本作为一条不透明 `GENERIC/STATEMENT_IMPORT` 证据，不能逐行导入；不要把它改造成隐式批量解析。
- 来源管线是 `Capture -> RawEvent -> ParseAttempt -> SourceDraftProposal -> user-completed Draft`。当前一条 `RawEvent` 对应一份可清除载荷；多行导入必须定义行级载荷、批次级幂等和中断恢复，不能复用一个 payload ID 伪造多条独立证据。
- 相关模块：`source:contract`、`source:pipeline`、`source:review-contract`、新增的纯 Kotlin delimited-statement 模块、`application`、`data:local`、`feature:review` 和 `app`。
- 当前来源复核 UI 已显示候选金额/对手方，但尚未将候选方向/时间完整预填；该缺口要在导入前一起补齐。

## 进度

- [x] 2026-07-26 - 审计现有通用文本入口、RawEvent/载荷一对一关系、来源建议 UI 和产品验收门；确认“整份 CSV 当一条证据”不能满足多行草稿导入。
- [ ] 2026-07-26 - 设计行级证据、`ImportBatch` 迁移、重复/恢复语义与用户可见错误，不改变现有分享文本路径。
- [ ] 2026-07-26 - 实现纯 Kotlin UTF-8 CSV/TSV 读取、映射、预览和恶意输入边界回归。
- [ ] 2026-07-26 - 实现 SAF 映射界面、批次确认、逐行来源建议和候选字段预填。
- [ ] 2026-07-26 - 补 Room 迁移、幂等/部分失败恢复、UI/输入安全回归与真机文档选择器验收说明。
- [ ] 2026-07-26 - 使用 `code-review` 复审并更新来源覆盖、可靠性、安全和质量评分。

## 意外发现

- 当前 `SourceEvidencePayloadEntity` 以 `rawEventId` 为主键且 `payloadId` 唯一，因此原始文件不能未经迁移就直接供多条 RawEvent 共用；简单循环调用现有服务会造成证据生命周期错误或无意义的整份文件复制。
- 当前来源复核 UI 只预填金额和对手方。若不先透传 `moneyDirection` 与 `occurredAt`，即使导入器正确解析，也仍会让用户逐条重填关键事实。

## 决策日志

- 2026-07-26 - 先做用户映射的来源中立 CSV/TSV，而非猜测某家银行格式；原因是没有脱敏样本不能安全宣称 provider 支持，用户映射同时保留本地和可解释边界。
- 2026-07-26 - 每条采纳行必须有独立可清除证据和稳定行幂等键；原因是 `RawEvent` 不可变，清除/复核/审计不能依赖一个共享不透明文件的隐式行号。
- 2026-07-26 - 首版只生成待复核来源建议，不自动过账或批量确认；原因是资金账户、转账、退款、还款和重复关系仍需用户或后续规则明确。

## 实施步骤

1. 在 `source:contract` 定义不含原文泄漏的导入批次、映射和行定位契约；在 `data:local` 设计显式 Room 迁移，保持已有 v1→v6 数据可读。
2. 新增纯 Kotlin delimited-statement 模块：流式或有界读取 CSV/TSV，严格 UTF-8，支持 RFC 风格引号；设置大小、行数、列数、单元格和字段长度上限，输出不含敏感原文的诊断。
3. 让 application 层把已确认映射转换成每行规范化候选和独立证据，持久化批次进度；同一文件/映射/行的重试复用结果，冲突不覆盖旧证据。
4. 先扩展来源建议投影和 `SourceDraftSheet`，让 `moneyDirection`、日期与对手方以可编辑初值呈现；再接入批次导入 UI。
5. 在 app 中为 SAF 选择、映射、预览、确认、取消和恢复提供明确状态；不持久化 URI、文件名或预览全文。
6. 用合成 CSV/TSV 验证成功、引号换行、空字段、金额溢出、方向冲突、重复文件、重复行、部分失败、进程重启和清除边界；真实银行文件只在项目负责人明确同意、且先去识别后再测试。

## 具体命令

所有命令在仓库根目录经 CMD 运行：

```bat
cmd.exe /d /s /c "scripts\android.cmd :source:generic-delimited-statement:test :application:test :data:local:test :app:testDebugUnitTest --console=plain"
cmd.exe /d /s /c "scripts\android.cmd test lint assembleDebug :data:local:assembleDebugAndroidTest --console=plain"
cmd.exe /d /s /c "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-docs.ps1"
cmd.exe /d /s /c "git diff --check"
```

真机 SAF/银行样本验证仅在用户明确授权并限定内容范围后进行；真实文件、文件名、账户信息、路径和截图不进入仓库、日志或对话摘要。

## 验证与验收

- 未选择文件、非 `content://`、未知 MIME、畸形 UTF-8、超限大小/行列/单元格、未闭合引号和金额溢出均安全失败，不写 RawEvent、批次或来源建议。
- 用户必须显式完成每一个必填映射；方向、金额、日期和对手方冲突或缺失的行显示为异常，不生成猜测草稿。
- 成功行生成独立 `GENERIC/STATEMENT_IMPORT` 证据、RawEvent、ParseAttempt 和来源建议；候选只预填字段，正式交易仍需用户确认。
- 相同文件和相同映射重试不创建重复行；相同 command ID 不同文件/映射失败为冲突；中断后可从结构化批次状态恢复，不删除已提交的证据。
- 清除某行证据只影响该行待复核建议；批次历史和审计仍可解释，不泄漏原始文件正文。
- 无网络、无相册/广泛存储/通知/无障碍权限、无后台任务、无自动正式记账。

## 幂等、回滚与恢复

批次确认前只保留有界内存预览；确认后为每个行级载荷登记暂存租约，再原子消费为 RawEvent/生命周期/解析结果。行键由批次文件摘要、规范映射版本和稳定行定位派生；同一行成功重试返回已有结果，不同指纹使用同一 command 失败关闭。批次在部分行失败或进程死亡时记录安全进度，恢复只继续缺失行，绝不覆盖已有 RawEvent、用户编辑、已完成 Draft 或清除状态。

取消映射或预览不会留下任何原始文件载荷。导入后若发现资源、迁移或账务语义风险，回滚为“不透明文本证据 + 手工复核”入口；不以自动确认、共享 payload 或后台扫描作为替代。

## 结果与复盘

进行中。当前只完成架构审计和计划；尚未创建 `ImportBatch` 迁移、CSV/TSV 解析器、批次 UI 或 provider 支持声明。
