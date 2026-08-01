# 文档总索引

- 状态：已确认
- 所有者：项目维护者
- 最后核验：2026-08-02
- 事实来源：本仓库文档体系

本目录是项目的记录系统。根目录 [AGENTS.md](../AGENTS.md) 只负责把贡献者带到这里；产品与工程事实应在下列唯一事实来源中维护。

## 快速导航

| 问题 | 首选文档 | 下一步 |
| --- | --- | --- |
| 为什么做、为谁做 | [PRODUCT_SENSE.md](PRODUCT_SENSE.md) | [MVP](product-specs/mvp.md) |
| 系统如何分层 | [ARCHITECTURE.md](../ARCHITECTURE.md) | [设计文档索引](design-docs/index.md) |
| 当前手工本地账本如何落地 | [ADR-0005](decisions/0005-manual-ledger-first-slice.md) | [领域模型](design-docs/domain-model.md) |
| 当前通用分享文本如何进入证据链 | [ADR-0006](decisions/0006-provider-neutral-shared-text-evidence-spine.md) | [来源适配器](design-docs/ingestion-and-source-adapters.md) |
| 来源原始证据怎样保留、清除和分页 | [ADR-0007](decisions/0007-source-evidence-lifecycle-and-bounded-storage.md) | [SECURITY.md](SECURITY.md) |
| 支付宝、微信、银行支持到什么程度 | [数据源覆盖矩阵](product-specs/source-coverage.md) | [来源适配器](design-docs/ingestion-and-source-adapters.md) |
| 交易为何没有重复入账 | [对账与关联](design-docs/reconciliation.md) | [领域模型](design-docs/domain-model.md) |
| 用户如何确认、修改、撤销 | [采集-确认-对账规格](product-specs/capture-review-reconcile.md) | [DESIGN.md](DESIGN.md) |
| UI 长什么样、如何适配窗口 | [UI 设计系统](design-docs/ui-design-system.md) | [FRONTEND.md](FRONTEND.md) |
| 港版/国行 Samsung 与小米怎样真机验收 | [Android 设备兼容](design-docs/android-device-compatibility.md) | [RELIABILITY.md](RELIABILITY.md) |
| 没学过 Java，怎样开始 Android 开发 | [Android 开发入门](development/android-getting-started.md) | [Android 技术基线](decisions/0004-android-compose-baseline.md) |
| 账户、负债、投资怎样表示 | [账户/负债/投资规格](product-specs/accounts-liabilities-investments.md) | [领域模型](design-docs/domain-model.md) |
| 后续收支图表页统计什么 | [收支图表页规格](product-specs/analytics-charts.md) | [对账与关联](design-docs/reconciliation.md) |
| 哪些权限和数据处理允许 | [SECURITY.md](SECURITY.md) | [来源覆盖矩阵](product-specs/source-coverage.md) |
| 失败后如何恢复、怎样测试 | [RELIABILITY.md](RELIABILITY.md) | [QUALITY_SCORE.md](QUALITY_SCORE.md) |
| 接下来做什么 | [执行计划索引](exec-plans/index.md) | [PLANS.md](PLANS.md) |
| 为什么做出某项长期决定 | [决策索引](decisions/index.md) | 对应 ADR |

## 文档分区

- [产品规格](product-specs/index.md)：用户可观察行为、范围和验收标准，回答“做什么”。
- [设计文档](design-docs/index.md)：系统方案、边界、权衡和验证方法，回答“怎样做以及为什么”。
- [架构决策](decisions/index.md)：已经接受的长期决定与被否决方案，保留设计历史。
- [执行计划](exec-plans/index.md)：一次具体复杂变更的可恢复实施记录。
- [参考资料](references/index.md)：外部资料与原始报告提取；只提供背景，不自动成为规范。
- [Android 开发入门](development/android-getting-started.md)：面向初学维护者的概念分层、阅读顺序与最小学习路径。
- [生成事实](generated/README.md)：未来从代码、Room schema、Manifest 和测试生成；禁止手改。
- [术语表](GLOSSARY.md)：跨产品、账务和来源适配器的稳定术语。

## 唯一事实来源

| 主题 | 唯一事实来源 |
| --- | --- |
| 产品首期边界 | [product-specs/mvp.md](product-specs/mvp.md) |
| 来源目标与实际状态定义 | [product-specs/source-coverage.md](product-specs/source-coverage.md) |
| 核心实体与状态 | [design-docs/domain-model.md](design-docs/domain-model.md) |
| 采集/解析契约 | [design-docs/ingestion-and-source-adapters.md](design-docs/ingestion-and-source-adapters.md) |
| 去重和资金流语义 | [design-docs/reconciliation.md](design-docs/reconciliation.md) |
| 权限、加密、日志与删除 | [SECURITY.md](SECURITY.md) |
| 地区/OEM 设备兼容与真机验收 | [design-docs/android-device-compatibility.md](design-docs/android-device-compatibility.md) |
| 文档与实现质量状态 | [QUALITY_SCORE.md](QUALITY_SCORE.md) |
| CNY 手工账本首片边界 | [decisions/0005-manual-ledger-first-slice.md](decisions/0005-manual-ledger-first-slice.md) |
| USD 只限银行卡/信用卡、无汇率换算 | [decisions/0013-bank-card-only-usd-without-fx.md](decisions/0013-bank-card-only-usd-without-fx.md) |
| 通用分享文本证据主干 | [decisions/0006-provider-neutral-shared-text-evidence-spine.md](decisions/0006-provider-neutral-shared-text-evidence-spine.md) |
| 来源证据生命周期与有界存储 | [decisions/0007-source-evidence-lifecycle-and-bounded-storage.md](decisions/0007-source-evidence-lifecycle-and-bounded-storage.md) |
| 三轨采集的本地资源预算 | [decisions/0011-local-resource-budget-first-capture.md](decisions/0011-local-resource-budget-first-capture.md) |
| 后续独立收支图表页 | [product-specs/analytics-charts.md](product-specs/analytics-charts.md) |

## 状态词

- `草案`：方向可讨论，尚不能作为实现约束。
- `已确认`：可作为实现与评审约束。
- `部分实现`：已有代码，但未覆盖全部验收证据。
- `已验证`：实现、测试和文档一致，并有可重复证据。
- `已取代`：保留历史，但顶部必须链接替代文档。

所有持久文档至少记录状态、所有者、最后核验日期和事实来源。超过 90 天未核验，或与代码行为冲突时，必须在 [技术债务清单](exec-plans/tech-debt-tracker.md) 中登记。
