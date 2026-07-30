# ExecPlan 0005：银行卡/信用卡美元账户与分币种账本

- 状态：进行中
- 所有者：项目维护者
- 最后核验：2026-07-30
- 事实来源：项目负责人 2026-07-26 的账户币种约束、ADR-0013、当前 CNY-first 账本实现

## 目的与用户可见结果

用户可以创建人民币或美元的银行卡、信用卡；现金、支付宝余额、微信零钱等电子钱包只能创建人民币账户。用户录入美元银行卡/信用卡收支和期初余额后，账本显示各币种各自的资产、负债和净值，不显示虚假的汇率合计。

## 范围与非目标

包含：

- `CNY`/`USD` 枚举化支持、账户类型与币种白名单、输入/过账/存储的共同校验。
- USD 银行卡与信用卡的期初余额、手工草稿、来源复核编辑、确认和分录平衡。
- CNY/USD 分币种总览、账户与最近交易显示；无跨币种相加。
- 对支付宝/微信候选和普通 `GENERIC` 证据强制 CNY；只有银行卡/信用卡手工路径、未来经验证的银行来源，或用户显式选择币种的严格 `GENERIC/STATEMENT_IMPORT` 映射行可进入 USD。
- 旧 CNY 数据无损兼容、单元/Room/Compose 回归、文档更新。

不包含：

- 汇率、换汇、跨币种转账、外币估值或跨币种总资产。
- 将支付宝、微信钱包或现金账户扩展到 USD。
- 以泛化规则宣称任何具体银行外币通知或账单已被支持。

## 进度

- [x] 2026-07-26 - 项目负责人确认：支付宝和微信只能 CNY，USD 只限银行卡和信用卡，且没有汇率换算。
- [x] 2026-07-26 - 接受 ADR-0013，固定“币种不可相加”和来源边界。
- [x] 2026-07-26 - 将 `CurrencyCode`、Money 输入解析和创建命令改为显式 CNY/USD，拒绝其他币种。
- [x] 2026-07-26 - 实现账户类型与币种约束，补 SQLite 写入和开账余额/隐藏系统账户策略。
- [x] 2026-07-26 - 放开 USD 草稿和过账工厂，确保同币种收入/支出/权益分录平衡。
- [x] 2026-07-26 - 改造 Snapshot/总览/UI，按币种展示资产、负债和净值，禁止合计。
- [x] 2026-07-26 - 将外部来源草稿币种与资金账户绑定；支付宝/微信和普通 `GENERIC` 路径固定 CNY，银行保留明确币种入口。
- [x] 2026-07-30 - 为严格的 `GenericDelimitedStatement` + `STATEMENT_IMPORT` 显式映射例外透传用户确认的 CNY/USD；其他 `GENERIC` 捕获方式仍固定 CNY，且 USD 最终只能选择银行卡或信用卡资金账户。
- [x] 2026-07-26 - 补 JVM、application、Room integrity 和 Android test APK 编译回归；混币分录、USD 钱包和来源 USD 绕过均失败关闭。
- [x] 2026-07-30 - 完整 JVM/Lint/Debug/Release 与 AndroidTest APK 构建通过；`code-review` 复核确认支付宝/微信仍为 CNY、USD 只经银行卡/信用卡或严格来源中立文件映射进入，未发现币种绕过。
- [ ] 设备验收轮 - 执行旧数据库升级、USD 开账/确认和混币篡改 instrumentation；APK 编译不能替代真机。

## 实施步骤

1. 审计所有 CNY 限制点：创建账户、手工草稿、外部草稿、PostingFactory、Room repository、系统账户模板、Snapshot、Overview 与 Compose 输入。
2. 在领域层定义允许账户币种函数：`ASSET_BANK`/`LIABILITY_CC` 为 CNY/USD，其余用户账户为 CNY；为每种已支持币种建立不可见、不可选的系统账户，来源候选按来源家族额外收紧。
3. 因为每币种必须平衡，为 USD 提供同币种系统收入、支出和期初权益账户，且不可在 UI 中显示或选择；持久化与迁移必须避免给旧 CNY 用户多记账。
4. 令创建账户、期初余额和草稿以所选/绑定账户币种解析金额；禁止手工输入与资金账户币种不一致。
5. 用 `Map<CurrencyCode, BalanceTotals>` 取代单一总额，将 UI 改为每币种独立卡片或行；没有汇率时不显示总体净值数字。
6. 为支付宝/微信和普通 `GENERIC` 候选保留 CNY-only 断言；严格 `GENERIC/STATEMENT_IMPORT` 映射行只采用用户显式确认的 CNY/USD，未来银行 USD 仍须“明确币种证据”门；未知格式只进入诊断/待复核。
7. 验证 CNY 旧路径、USD 银行/信用卡、钱包拒绝、币种不匹配、混币总览、审计幂等与旧数据库打开；最后运行完整测试、文档检查和 code-review。

## 验收与恢复

- 一切收入/支出/期初余额在每个币种内平衡，`LedgerValidator` 不接受混币抵消。
- CNY 钱包永远不能成为 USD 草稿的资金账户；支付宝/微信候选永远不能产生 USD。
- 总览不存在 CNY + USD 的数值加法，金额遮罩也不会泄露另一币种余额。
- 更新失败时旧 CNY 数据仍可打开；迁移不自动重写历史账。
- 若实现未能证明系统账户、总览或来源币种的完整性，则不暴露 USD 创建入口。

## 验证记录与意外发现

- 2026-07-26 - 静态审计发现旧校验只要求每一种币种各自归零，可能允许单笔交易同时带 CNY/USD。现已在 `LedgerValidator`、Room 写入校验和 SQLite 完整性查询三层拒绝 `distinct currency > 1`，并添加 JVM 与篡改数据库回归。
- 2026-07-26 - 来源仓储原本已在最终写入时拒绝支付宝/微信/普通通用 USD；本轮将同一策略放到来源契约、application 和复核 UI。2026-07-30 又加入严格例外：只有 `GenericDelimitedStatement` 解析器、严格 connector 与 `STATEMENT_IMPORT` 捕获方式同时匹配时，才采用显式映射的 CNY/USD；异常或其他通用候选不会显示美元输入框，也不会等到落库才返回笼统失败。
- 2026-07-26 - 旧 CNY 系统账户的规范化名称保持不变；USD 隐藏系统账户仅在后续写入时幂等补齐，未引入 schema 迁移或历史账重写。完整旧 v6 数据库→USD 开账/确认的 connected instrumentation 仍待设备执行。
- 2026-07-26 - `:core:ledger:test :application:test :data:local:test :feature:review:compileDebugKotlin :app:testDebugUnitTest :data:local:assembleDebugAndroidTest` 成功（202 个 actionable Gradle tasks）；最后一项仅编译 Android 测试 APK，未接触真机或支付 App。
