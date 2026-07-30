# ExecPlan 0006：用户确认的转账、退款与还款对账

- 状态：进行中
- 所有者：项目维护者
- 最后核验：2026-07-30
- 事实来源：[去重、关联与资金流设计](../../design-docs/reconciliation.md)、[采集确认规格](../../product-specs/capture-review-reconcile.md)、[统一领域模型](../../design-docs/domain-model.md)、项目负责人“先做对账”的实现顺序

## 目的与用户可见结果

用户可在待复核 Draft 之间明确确认“自有账户转账”，或把一条支出 Draft 改作信用卡还款、把一条收入 Draft 关联到原支出作为退款。确认后只生成一笔平衡的 `TRANSFER`、`LIABILITY_REPAY` 或 `REFUND` 交易，普通收支统计不再把这些资金移动算成新增收入或支出；被吸收的 Draft 与其来源证据保留为 `LINKED`，撤销对账后可回到待复核状态。

首片不按金额相同自动合并。应用只在金额、币种、方向、账户角色和时间窗满足硬门时生成建议，所有建议都必须由用户显式确认。

## 范围与非目标

包含：

- 两条相反方向 Draft 的自有资产账户转账建议。
- 一条资产账户支出 Draft 到信用卡负债账户的还款建议。
- 一条收入 Draft 到已有活动支出交易的全额或部分退款建议。
- 多 Draft 到一个替代交易的持久证据链接、退款关系、幂等命令、追加审计和撤销恢复。
- 本地计算的候选列表和账务影响预览；不依赖服务器。

不包含：

- 未经用户确认的自动合并或自动正式记账。
- 仅凭银行卡充值/提现去推断钱包余额消费、红包或个人转账。
- 待入账/正式入账、支付渠道绑卡双计、余额快照差异和投资申赎；这些需要独立关系类型与样本校准。
- 跨币种对账、汇率换算、手续费拆分或把未知差额自动记为调整。

## 核心决策

- 对账发生在仍待确认的 Draft 上，而不是先把两条普通收支过账再删掉；这样不会产生临时双计，也保留来源证据。
- `DraftState.LINKED` 表示该 Draft 已被一个对账交易吸收；独立关联表支持一笔交易链接一或两份 Draft。
- 退款另外保存有方向的 `REFUNDS` 交易关系；累计活动退款不得超过原支出的费用金额。
- 还款目标只接受同币种信用卡负债账户；首片不开放尚不能由用户创建的贷款账户。
- 转账两腿必须是不同的同币种资产账户；银行到电子钱包余额属于资金移动，不反推后续钱包消费。
- 撤销对账只把替代交易标成 `VOIDED`、恢复链接 Draft；不删除交易、关系、来源证据或审计。

## 进度

- [x] 2026-07-26 - 审计底层 `LedgerValidator` 已支持三类分录，但 Draft、application、Room 和 UI 尚未连接。
- [x] 2026-07-26 - 增加领域关系、对账 PostingFactory 和硬门测试。
- [x] 2026-07-26 - 增加 Room 关系/多 Draft 链接、幂等确认、退款上限与撤销事务。
- [x] 2026-07-26 - 增加应用层候选投影、稳定确认命令与竖屏对账工作台。
- [x] 2026-07-26 - 针对性 `core:ledger`、`application`、App 单测通过，Room Android 测试源码与迁移查询编译通过。
- [x] 2026-07-30 - 使用 `code-review` 复审并完成全量 JVM、Lint、Debug/未签名 Release 和 AndroidTest APK 构建；ViewModel 确认路由新增回归。
- [ ] 设备验收轮 - 在 Room instrumentation 中执行 v6→v7、确认→撤销→再次确认、退款上限与数据库关闭重开；当前只完成测试源码/APK 编译。

## 意外发现

- 首次构建先暴露 OCR Gradle 脚本的 `java` DSL 命名冲突，以及通用 CSV 模块的 UTF-8 异常导包和成员扩展引用错误；均为本轮完整构建第一次覆盖到的既有未编译路径，已作局部修复。
- 初版候选生成对大量同金额 Draft 存在平方级扫描风险。复审后改为按金额索引，并把一次建议搜索限制在最近更新的 500 条 Draft、最近确认的 50 笔退款来源交易；这不删除或跳过正常人工复核。
- 初版 Draft 链接唯一索引错误地把历史 `VOIDED` 对账也算作永久占用，撤销后无法重新确认。复审后改为允许历史链接并存，完整性查询只要求每个 Draft 最多一个活动对账，同时兼容旧 VOIDED 链接与新 ACTIVE 链接。

## 决策日志

- 当前三类关系一律只生成建议，不设置分数或自动确认。缺标注样本时显示伪精确分数比不显示更误导。
- 转账和还款的关系语义由替代交易类型与 Draft 链接表达；退款另存方向明确的 `REFUNDS` 关系，以支持累计上限和阻止提前撤销原支出。
- Room 完整性观察同时校验命令回执、替代交易、Draft 链接和退款关系的形状；不完整或角色不匹配的本地状态失败关闭。
- 候选账户按创建时间和 ID 确定性排序；Room 测试源码覆盖 reconcile→void→reconcile，ViewModel 单元测试覆盖用户确认命令到 application/repository 的成功路由。

## 代码审查记录（2026-07-30）

- 未发现 P0/P1。金额、币种、方向、账户角色、时间窗和退款累计上限仍在确认事务前重新校验。
- 去除 Draft 链接的错误全历史唯一约束；撤销保留旧交易与链接审计，再次确认写入新的活动关系，完整性观察不会误把历史记录当作冲突。
- `code-review` 复核了事务原子性、命令重放/碰撞、撤销恢复和候选上限；真机 Room migration/关闭重开仍是开放发布门。

## 验收

- 相同金额与币种但同方向、同一账户或不兼容账户的 Draft 不生成转账建议。
- 转账确认只产生两条自有账户 Entries，总和为零，类型为 `TRANSFER`。
- 信用卡还款减少资产和负债，类型为 `LIABILITY_REPAY`，不进入普通支出。
- 退款冲减原支出费用；全额、部分和多次退款累计不能超过原支出。
- 同一命令重试返回原结果；命令、Draft、关系或交易内容冲突时失败关闭。
- 确认事务同时写交易、Entries、Draft 链接、关系、审计和命令回执；中途失败不留下半成品。
- 撤销后交易不影响余额，所有输入 Draft 恢复为待复核，来源链仍存在。
- 无网络、无后台匹配、无跨币种换算、无自动确认。

## 具体命令

所有命令经 CMD 运行：

```bat
cmd.exe /d /s /c "scripts\android.cmd :core:ledger:test :application:test :data:local:test :app:testDebugUnitTest --console=plain"
cmd.exe /d /s /c "scripts\android.cmd test lint assembleDebug :data:local:assembleDebugAndroidTest --console=plain"
cmd.exe /d /s /c "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-docs.ps1"
cmd.exe /d /s /c "git diff --check"
```

## 结果与复盘

进行中。领域、应用、Room、竖屏 UI、ViewModel 路由、全量 JVM/Lint/Debug/Release、AndroidTest APK 编译与指定 `code-review` 已完成；设备端 Room v6→v7、确认/撤销/重确认、关闭重开和真实来源 Draft 验收仍未执行。真机 instrumentation 保持独立授权门。
