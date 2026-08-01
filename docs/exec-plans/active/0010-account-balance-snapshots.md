# ExecPlan 0010：账户余额快照与待解释差异

- 状态：已排队；让位于 0011 的完整审核、OCR 回退与跨来源去重
- 所有者：项目维护者
- 最后核验：2026-08-01
- 事实来源：MVP G-10、账户/负债/投资规格、对账设计、ADR-0009、ADR-0015、当前 Room v9 与账户页实现、2026-08-01 产品优先级调整

## 目的与用户可见结果

用户可在账户页为现金、银行卡、钱包余额或信用卡记录某一时点的实际余额。Bill 显示该快照、同一时点由活动分录推导的账本余额和带符号差异；差异为零时标记已对上，非零时明确标记待解释。保存快照不会自动创建收入、支出或调整。

## 范围与非目标

包含：

- 不可变余额快照领域模型、用户视角负债语义、输入上限和幂等命令。
- Room v10→v11 显式迁移、最新快照时点汇总、完整性门和迁移/仓储测试。
- application 命令、Snapshot 投影、账户页竖屏表单、加载/错误状态和简体/繁体/英文文案。
- 账户最新快照与差异展示；零值、CNY/USD 银行/信用卡、历史时点和补录/撤销语义测试。
- README、领域模型、对账、可靠性、质量评分与生成事实同步。

不包含：

- 自动调整、自动猜漏账、自动推断钱包红包/转账/消费。
- 完整快照历史页、批量删除、月结冻结或期间重开。
- 投资持仓估值；它继续由 `InvestmentPosition` 负责。
- 贷款账户、provider 余额导入、`FUNDED_BY`、备份或云同步。

## 上下文与仓库导航

- 语义决定：`docs/decisions/0015-immutable-balance-snapshots-and-explicit-differences.md`
- 账户规格：`docs/product-specs/accounts-liabilities-investments.md`
- 对账设计：`docs/design-docs/reconciliation.md`
- 领域/仓储：`core/domain/`、`data/local/`
- 应用投影：`application/BillService.kt`、`application/BillSnapshot.kt`
- Compose：`feature/accounts/`、`app/BillViewModel.kt`、`app/MainActivity.kt`

## 进度

- [x] 2026-08-01 - 对照 MVP、质量评分、技术债务、领域枚举与 UI，确认余额快照只有目标实体描述，没有代码或 schema。
- [x] 2026-08-01 - 接受 ADR-0015：快照不可变、按 `asOf` 动态比较、信用卡使用正数用户视角、差异不自动过账。
- [x] 2026-08-01 - 项目负责人将“完整手工审核、单帧 OCR 回退、跨来源去重”提升为基础可用的首要缺口；本计划排队，避免同时改同一 Draft/Room/UI 边界。
- [ ] 在 ExecPlan 0011 完成并稳定 Room v10 后，实现领域、Room v11、应用、账户页和测试。
- [ ] 运行完整构建、仓库守卫与用户指定的 `code-review`，修复后提交并推送。
- [ ] 真机验收轮只在项目负责人本轮明确连接设备后执行；APK 编译不冒充设备执行。

## 意外发现

- 根 README 与采集规格仍把生产 catalog 写成 4 条/空 provider、Room 写成 v7，和当前 5 条 route、Room v9、投资切片冲突；本计划收尾时同步修复，不把文档结构检查通过当作内容正确证明。
- 余额比较不能拿历史快照与当前余额直接相减；必须按交易 `occurredAt` 在快照时点汇总，否则快照之后的正常交易会产生假差异。

## 决策日志

- 2026-08-01 - 原计划先接余额快照；同日按项目负责人明确优先级，改为先完成完整审核、OCR 回退与 `FUNDED_BY`。余额快照仍保留为后续完整性能力，不作为基础记账可用性的阻断项。
- 2026-08-01 - 当前允许零或正数用户视角余额，不放宽资产透支；未来负数资产需独立定义透支/负债语义。
- 2026-08-01 - 当前只投影每账户最新快照，历史全部保留但不做历史管理 UI，避免把本切片扩成期间系统。

## 实施步骤

1. 在 `core:domain` 定义快照、来源模式、可快照账户白名单和 `LedgerState` 比较投影。
2. 在 `data:local` 新增 v11 实体、DAO 最新快照/时点余额查询、完整性检查、幂等事务、mapper、schema 和 10→11 迁移测试。
3. 在 `application` 校验账户、币种、时间、备注和金额，生成审计并投影差异状态。
4. 在 `feature:accounts` 增加账户级入口、可滚动底部表单、最新快照与差异卡；App/ViewModel 保留稳定 command ID 和异步反馈。
5. 补领域、application、Room、ViewModel/UI presenter 回归，更新唯一事实来源和自动生成事实。

## 具体命令

所有命令从仓库根目录经 CMD 执行：

```text
cmd.exe /d /s /c scripts\android.cmd :core:domain:test :application:test :data:local:test :feature:accounts:test :app:testDebugUnitTest
cmd.exe /d /s /c scripts\android.cmd test lint
cmd.exe /d /s /c scripts\android.cmd assembleDebug assembleRelease :data:local:assembleDebugAndroidTest :ocr:paddle:assembleDebugAndroidTest :app:assembleDebugAndroidTest
cmd.exe /d /s /c scripts\check-repository.cmd
cmd.exe /d /s /c git diff --check
```

## 验证与验收

- 快照账户必须存在、未归档、非系统且属于现金/银行/钱包/信用卡；投资和分类账户失败关闭。
- 金额与账户币种一致；CNY/USD 银行和信用卡均可记录，钱包仍只有 CNY；零余额可保存，负数或未来时间拒绝。
- 信用卡欠款用正数输入展示，比较前将账本内部负数转换为用户视角。
- 账本余额只汇总 `ACTIVE` 且 `occurredAt <= asOf` 的分录；快照后交易不改变历史差异，补录/撤销历史交易会更新差异。
- 同 command/同载荷重试不重复；同 command/不同载荷碰撞；Room 损坏关系使观察失败关闭。
- 表单有可见标签、数字键盘、字段内错误、加载反馈、禁用重复提交、48dp 触控和竖屏滚动；差异含文字/符号而非只靠颜色。
- Release 不增加网络、短信、相册、无障碍或存储权限；测试和预览不含真实财务数据。

## 幂等、回滚与恢复

- v11 迁移只新增快照表和索引，不改写账户、分录或交易；失败时不使用 destructive fallback。
- 创建快照、审计和 command receipt 同事务。插入失败不留下半条快照；重复命令按规范指纹恢复结果。
- UI 在成功前复用 command ID；错误允许修改后重试，但修改载荷前必须换新命令，避免把碰撞伪装成普通校验失败。
- 快照不可编辑或删除；未来更正通过新快照表达，旧证据保留。

## 结果与复盘

已排队，尚未开始代码实现。ExecPlan 0011 将占用 Room v10 和 Draft/UI 边界；本计划在其完成后从 Room v11 继续，不与当前优先切片并行制造迁移和交互冲突。
