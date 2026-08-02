# 产品规格索引

- 状态：已确认
- 所有者：项目维护者
- 最后核验：2026-08-02
- 事实来源：产品规格目录

产品规格只定义用户可观察行为、范围和验收，不规定具体 Kotlin 类或 Room 表。

| 规格 | 规范主题 |
| --- | --- |
| [mvp.md](mvp.md) | 首期范围、旅程、非目标与整体验收 |
| [source-coverage.md](source-coverage.md) | 支付宝、微信支付、银行的目标能力和支持状态定义 |
| [capture-review-reconcile.md](capture-review-reconcile.md) | 采集、导入、草稿确认、关联与撤销体验 |
| [accounts-liabilities-investments.md](accounts-liabilities-investments.md) | 账户、负债、投资和资金移动的用户语义 |
| [analytics-charts.md](analytics-charts.md) | 后续独立收支图表页的数据口径、隐私和验收边界 |
| [next-version-account-fund-and-charts.md](next-version-account-fund-and-charts.md) | 后续版本的账户维护、账本调整、基金每日估值/精度与图表汇总需求 |

若产品行为与设计文档冲突，先确认用户期望，再同步两者；不要让实现细节静默改写产品语义。
