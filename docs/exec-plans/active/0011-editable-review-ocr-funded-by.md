# ExecPlan 0011：完整可编辑审核、单帧 OCR 回退与跨来源 FUNDED_BY

- 状态：进行中
- 所有者：项目维护者
- 最后核验：2026-08-01
- 事实来源：项目负责人 2026-08-01 的基础可用优先级、ADR-0012、ADR-0016、采集复核规格、对账设计、当前 Room v10 与 Compose 实现

## 目的与用户可见结果

任何可抓到的通知或用户主动触发的一帧截图，都先形成可完整修改的待审核项；抓不到或 OCR 失败的交易可手工补录。用户能纠正金额、时间、商户、经济类型、渠道、资金账户和备注，再确认入账。同一笔支付宝/微信银行卡支付与银行扣款可以被提出为跨来源候选，用户确认后只计一笔支出，撤销后两份 Draft 都恢复。

## 范围与非目标

包含：

- `ObservedChannel` 业务渠道及 Room v9→v10 显式迁移；来源证据继续不可变。
- `WAITING_USER`/`EDITED` Draft 的完整编辑命令、幂等 receipt、审计和竖屏滚动表单。
- 通知按来源预填渠道；通用截图 OCR 默认未知，并在创建/编辑审核中让用户选择。
- 简体中文、繁体中文和英文 OCR 状态/金额回归；金额与交易状态优先于应用展示名称。
- 保守 `FUNDED_BY` 候选、用户确认、单笔支出、两份证据链接、撤销恢复和完整性门。
- 定向测试、完整构建、仓库守卫和用户指定的 `code-review`。

不包含：

- 后台截图、页面轮询、网络 OCR、读取其他 App 私有目录或新增高风险权限。
- 保证平台一定产生通知；没有通知时由磁贴截图或手工录入兜底。
- 自动合并、低置信度自动确认、仅按金额去重、钱包余额与银行流水互相反推。
- 拆分交易、标签/分类体系重构、币种修改、余额快照、基金赎回或云同步。

## 上下文与仓库导航

- 决策：`docs/decisions/0016-user-reviewed-channel-and-explicit-funded-by.md`
- 捕获与审核：`docs/product-specs/capture-review-reconcile.md`
- 来源适配：`docs/design-docs/ingestion-and-source-adapters.md`
- 对账：`docs/design-docs/reconciliation.md`
- 领域/仓储：`core/domain/`、`core/ledger/`、`data/local/`
- 应用/UI：`application/`、`feature/review/`、`app/`

## 进度

- [x] 2026-08-01 - 核对当前缺口：OCR 可提出通用外部证据，但没有业务渠道；Draft 创建后除资金账户外不可编辑；对账只有转账、还款、退款，没有 `FUNDED_BY`。
- [x] 2026-08-01 - 项目负责人确认：平台无通知不作为阻断项，缺口由快捷设置磁贴单帧 OCR 和手工补录承担；优先完成审核、OCR 与跨来源去重。
- [x] 2026-08-01 - 接受 ADR-0016，分离不可变证据来源与可审核业务渠道，固定保守候选门和撤销语义。
- [x] 2026-08-01 - 实现领域、Room v10、application 编辑/候选/解析和 Compose 审核 UI；通用单帧 OCR 默认渠道未知，支付宝/微信/银行来源只负责预填，用户可在审核中纠正。
- [x] 2026-08-01 - 补齐并通过本轮定向回归：完整 Draft 编辑保留身份与来源模式、未来时间失败关闭、OCR 渠道预填/未知回退、`FUNDED_BY` 成功与拒绝矩阵、电子钱包余额排除；9→10 迁移 Android 测试源码编译通过。
- [x] 2026-08-01 - 补齐 Room 仓储级 `FUNDED_BY` 确认/撤销/双证据链回归，以及简体中文、繁体中文、英文状态和应用展示名不参与金额门控的 OCR 固定回放；三组 AndroidTest APK 均可构建。
- [x] 2026-08-01 - 完成完整测试、Lint、Debug/Release/AndroidTest APK、仓库守卫和 `code-review`；审查修复了编辑命令重试、显式渠道指纹兼容，以及实体未声明迁移默认值会导致 Room 真机 schema 校验失败的问题。
- [ ] 下次真机连接时实际运行 Room 9→10 迁移、仓储原子性及 OCR runtime 仪器化测试；当前只有源码/APK 编译证据，不冒充真机通过。

## 意外发现

- 快捷磁贴证据被正确标成 `GENERIC/PHOTO_OCR`；如果直接用来源族匹配，真实支付宝/微信截图永远无法参与 `FUNDED_BY`。因此必须增加独立、用户可纠正的业务渠道字段，而不能篡改来源证据。
- 现有审核底部页展示金额、时间和商户，但只能选择资金账户；“看得见”不等于“可审核修改”。
- 现有对账观察上限为有界 Draft 集合；新增匹配必须建立键索引，不能对全部 Draft 做无界 O(n²) 扫描。
- Room 实体的 Kotlin 属性默认值不是数据库 schema 默认值；迁移使用 `DEFAULT 'UNKNOWN'` 时，实体也必须声明同一 `ColumnInfo(defaultValue)`，否则 AndroidTest 虽可编译，真机迁移校验仍可能失败。

## 决策日志

- 2026-08-01 - 通用截图和手工录入默认渠道未知；通知仅预填，最终渠道由用户在审核页确认。
- 2026-08-01 - 当前编辑不允许改币种；需要换币种时忽略旧 Draft 并按正确币种重建，避免突破来源允许币种。
- 2026-08-01 - `FUNDED_BY` 只匹配银行卡/信用卡资金账户，不匹配电子钱包余额；用户确认前不改变任何账务。
- 2026-08-01 - 候选要求 30 分钟、同金额币种、同规范化商户、同资金账户；保守漏报可由用户手工处理，误合并不可接受。
- 2026-08-01 - v9 创建命令指纹把缺失渠道等价为 `UNKNOWN`，保证升级后丢响应重试仍可命中；任何显式渠道都进入 v10 指纹，改变载荷必须碰撞。
- 2026-08-01 - 编辑表单只要输入发生变化就轮换 command ID；同一载荷的网络/进程重试复用原 ID，既保证幂等，也禁止旧 receipt 接受新内容。

## 实施步骤

1. 在 `core:domain` 增加业务渠道、Draft 编辑审计、`FUNDED_BY` kind/roles 与替代支出不变量。
2. 将 Room 升至 v10，添加非空默认渠道列、原子编辑 DAO、幂等 receipt、mapper、完整性查询和 9→10 迁移测试。
3. 在 application 增加编辑命令/校验/投影；来源提案预填渠道；以有界键索引生成候选并在确认前重做全部硬校验。
4. 在 review UI 增加渠道选择和完整 Draft 编辑表单，提供可见标签、字段错误、加载禁用、48dp 触控、竖屏滚动与放弃未保存修改确认。
5. 增加 OCR→渠道审核→Draft 编辑→`FUNDED_BY`→撤销的纵向回归，并验证来源链接没有被编辑或删除。

## 具体命令

所有命令从仓库根目录经 CMD 执行，先定向、后完整；长构建分段汇报：

```text
cmd.exe /d /s /c scripts\android.cmd :core:domain:test :core:ledger:test :application:test :data:local:test :feature:review:test :app:testDebugUnitTest
cmd.exe /d /s /c scripts\android.cmd test lint
cmd.exe /d /s /c scripts\android.cmd assembleDebug assembleRelease :data:local:assembleDebugAndroidTest :ocr:paddle:assembleDebugAndroidTest :app:assembleDebugAndroidTest
cmd.exe /d /s /c scripts\check-repository.cmd
cmd.exe /d /s /c git diff --check
```

## 验证与验收

- 任意外部/手工 Draft 在确认前可改规定字段；已确认、已关联、已忽略 Draft 失败关闭；同命令同载荷幂等，不同载荷碰撞。
- 编辑不改变 Draft ID、来源模式、创建命令、创建时间或来源证据链接；审计不记录敏感原文。
- 快捷磁贴只处理当前一帧，原图像素不持久化；OCR 失败可回到手工补录，无后台任务或网络权限。
- 简体、繁体、英文页面金额和成功/退款/红包状态回归通过；应用展示名不同不阻断金额审核。
- `FUNDED_BY` 成功后只有一笔平衡支出，银行/信用卡承担资金腿，两条 Draft 均链接；金额、币种、商户、时间、账户或渠道任一不匹配都不提出/不确认。
- 撤销替代交易后两条 Draft 恢复可审核且证据仍在；重复确认或撤销不产生双记。
- Release 不新增网络、短信、相册全盘、无障碍默认主线或存储权限；fixtures 不含真实财务信息。

## 幂等、回滚与恢复

- v10 只新增带默认值的 Draft 渠道列，不改写 RawEvent 或既有证据；旧 Draft 迁移为 `UNKNOWN`。失败时不使用 destructive fallback。
- 编辑与审计/receipt 同事务；成功前 UI 复用 command ID，修改载荷后换新命令。
- `FUNDED_BY` 确认原子写入替代交易、分录、两条 Draft link、Draft 状态、审计和 receipt；任一步失败不留下半个结果。
- 撤销原子 void 替代交易并恢复全部被吸收 Draft。没有自动删除或覆盖原始证据的恢复路径。

## 结果与复盘

本地实现与静态/构建验证完成；按项目负责人要求在此暂停。当前工作树在最后一次兼容性修复后已通过：

```text
cmd.exe /d /s /c scripts\android.cmd :core:domain:test :core:ledger:test :application:test :source:generic-photo-ocr:test :feature:review:test :app:compileDebugKotlin :data:local:test :data:local:compileDebugAndroidTestKotlin --console=plain
BUILD SUCCESSFUL；219 actionable tasks，16 executed，203 up-to-date

cmd.exe /d /s /c scripts\android.cmd test --console=plain
BUILD SUCCESSFUL；370 actionable tasks，18 executed，352 up-to-date

cmd.exe /d /s /c scripts\android.cmd lint --console=plain
BUILD SUCCESSFUL；356 actionable tasks，27 executed，329 up-to-date

cmd.exe /d /s /c scripts\android.cmd assembleDebug assembleRelease --console=plain
BUILD SUCCESSFUL；620 actionable tasks，19 executed，601 up-to-date

cmd.exe /d /s /c scripts\android.cmd :data:local:assembleDebugAndroidTest :ocr:paddle:assembleDebugAndroidTest :app:assembleDebugAndroidTest --console=plain
BUILD SUCCESSFUL；284 actionable tasks，7 executed，277 up-to-date

cmd.exe /d /s /c scripts\check-repository.cmd
Repository checks passed

cmd.exe /d /s /c git diff --check
通过；仅有现存 LF→CRLF 工作树提示
```

`code-review` 没有留下已知的高优先级代码问题；检查过运行时失败路径、性能边界、事务副作用、向后兼容、敏感数据边界、分层与测试。尚缺的唯一运行证据是真机执行 Room 9→10 迁移、仓储原子性与 OCR runtime 仪器化用例；提交/推送状态单独以 Git 结果为准。平台不产生通知不计为失败，磁贴截图和手工补录必须可用。
