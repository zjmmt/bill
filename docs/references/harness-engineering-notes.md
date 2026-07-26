# Harness Engineering 方法笔记

- 状态：已确认
- 所有者：项目维护者
- 最后核验：2026-07-19
- 事实来源：[OpenAI - 工程技术：在智能体优先的世界中利用 Codex](https://openai.com/zh-Hans-CN/index/harness-engineering/)

## 文章中采用的方法

- 人负责目标、优先级、验收和环境设计；智能体在明确反馈回路中执行。
- 深度优先拆解大目标，先构建缺失工具、抽象、测试和文档，再承担更复杂任务。
- 让 UI、日志、指标和测试结果可被智能体直接检查，减少人工转述。
- 把仓库作为记录系统；仓库外隐性知识必须转写为可版本化工件。
- `AGENTS.md` 是约 100 行的地图，不是庞大百科全书；详细事实进入结构化 `docs/`。
- 计划是一等工件，活跃计划、完成计划、决策和技术债务全部版本化。
- 用渐进式披露节省上下文：从稳定索引进入任务相关的最小文档集。
- 强制架构不变量、边界解析、命名/日志/文件限制，让速度不造成漂移。
- 用文档 lint、结构测试、CI 和定期 gardening 持续清理过期知识与重复模式。

## 本仓库的对应实现

| 文章原则 | 仓库工件 |
| --- | --- |
| 短地图 | 根 [AGENTS.md](../../AGENTS.md) |
| 稳定架构地图 | [ARCHITECTURE.md](../../ARCHITECTURE.md) |
| 结构化知识库 | [docs/index.md](../index.md) |
| 产品/设计分离 | `product-specs/` 与 `design-docs/` |
| Living ExecPlan | [exec-plans/](../exec-plans/index.md) |
| 设计历史 | [decisions/](../decisions/index.md) |
| 质量缺口 | [QUALITY_SCORE.md](../QUALITY_SCORE.md) |
| 生成事实 | [generated/README.md](../generated/README.md) |
| 机械检查 | `scripts/check-docs.ps1`，未来接 CI/生成器 |

## 没有机械照搬的部分

文章描述的是已有百万行代码和高吞吐智能体团队的实践。本项目目前是空仓库，因此：

- 不预先制造大量空的生成文档或 CI 仪表盘。
- 不把高吞吐下的快速合并策略当作当前默认；财务正确性和迁移风险仍需严格门禁。
- 先建立最小可执行文档检查，随 Android 工程出现再增加 schema、模块、权限和支持矩阵生成。

真正采用的是“仓库可导航、规则可执行、反馈可重复”的方法，而不是追求文档数量。
