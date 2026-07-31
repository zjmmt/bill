# 架构决策索引

- 状态：已确认
- 所有者：项目维护者
- 最后核验：2026-08-01
- 事实来源：已接受 ADR

| ADR | 决定 | 状态 |
| --- | --- | --- |
| [0001-local-first-no-owned-backend.md](0001-local-first-no-owned-backend.md) | 核心产品本地优先、无自有服务端依赖 | 已接受 |
| [0002-three-first-class-source-families.md](0002-three-first-class-source-families.md) | 支付宝、微信支付、银行均为 MVP 一等来源 | 已接受 |
| [0003-evidence-ledger-relations.md](0003-evidence-ledger-relations.md) | 使用不可变证据、草稿、平衡分录和显式关系 | 已接受 |
| [0004-android-compose-baseline.md](0004-android-compose-baseline.md) | Kotlin/Compose、API 26–36 与稳定构建工具基线 | 已接受，模拟器已验证，真机待验证 |
| [0005-manual-ledger-first-slice.md](0005-manual-ledger-first-slice.md) | CNY 手工账本首片、隐藏系统账户、幂等 Room 事务与 void 撤销 | 已接受，模拟器已验证，真机待验证 |
| [0006-provider-neutral-shared-text-evidence-spine.md](0006-provider-neutral-shared-text-evidence-spine.md) | 来源中立的显式分享文本证据、解析建议、复核与 Room v3 边界 | 已接受，通用切片已实现，发布门未完成 |
| [0007-source-evidence-lifecycle-and-bounded-storage.md](0007-source-evidence-lifecycle-and-bounded-storage.md) | 两阶段载荷清除、保留期限、容量治理、keyset 分页与 Room v4 | 已接受，生命周期切片已实现，staging 后续见 ADR-0008 |
| [0008-leased-source-evidence-staging-and-orphan-recovery.md](0008-leased-source-evidence-staging-and-orphan-recovery.md) | Room v5 租约暂存、事务消费、有界孤儿扫描与安全回滚 | 已接受，核心恢复切片已实现，压力与系统强杀矩阵未完成 |
| [0009-wallet-balance-not-inferred-from-bank.md](0009-wallet-balance-not-inferred-from-bank.md) | 电子钱包余额独立于银行卡，钱包内部收付不得由银行流水反推 | 已接受，provider 采集待验证 |
| [0010-notification-first-capture-and-single-receipt-fallback.md](0010-notification-first-capture-and-single-receipt-fallback.md) | 通知自动草稿为主路径，无通知收款用单次凭证补充 | 已接受，空模板基础已实现；provider 模板、持久去重与真机验证待完成 |
| [0011-local-resource-budget-first-capture.md](0011-local-resource-budget-first-capture.md) | 纯本地三轨采集以设备资源、权限与可测负担为先 | 已接受，4 条默认关闭通知 route 与本地 OCR 候选已实现，真机发布门未完成 |
| [0012-user-triggered-quick-tile-screenshot-and-bundled-ocr.md](0012-user-triggered-quick-tile-screenshot-and-bundled-ocr.md) | 下拉磁贴只触发一次专用无障碍截图，OCR 模型随包本地运行 | 已接受，实现与真机资源验收进行中 |
| [0013-bank-card-only-usd-without-fx.md](0013-bank-card-only-usd-without-fx.md) | USD 只允许银行卡/信用卡；钱包固定 CNY，分币种展示且不做汇率换算 | 已接受，实现进行中 |
| [0014-investment-position-snapshots-and-confirmed-events.md](0014-investment-position-snapshots-and-confirmed-events.md) | 基金持仓由手工/OCR 人工确认；通知只提出已确认投资事件，受理和行情提醒不入账 | 已接受，实现进行中 |

ADR 记录长期决定，不替代详细产品规格或设计文档。被新决定取代时保留原文件并互相链接。
