# Android UI 与状态架构

- 状态：账本、分享复核与证据设置切片已实现并回归，自动化 Compose/进程死亡与真机待完成
- 所有者：项目维护者
- 最后核验：2026-07-25
- 事实来源：ARCHITECTURE.md、DESIGN.md、当前 Compose/ViewModel 实现、UI 设计系统与 Android 官方窗口规则

## 技术方向

- Kotlin + Jetpack Compose。
- Material 3 提供交互状态、焦点、系统集成与无障碍语义；构成主义编辑视觉、颜色和页面规范以 [UI 设计系统](design-docs/ui-design-system.md) 为准。
- 单向数据流：不可变 `UiState`、显式 `UiAction`、可测试 reducer/use case。
- Navigation Compose 只传稳定 ID，不在路由参数中传原始财务内容。
- 屏幕只依赖 application use cases，不直接访问 Room、通知对象或文件解析器。

## 方向与布局约束

- 当前手机版本在 Manifest 为 `MainActivity` 请求 `portrait`，以竖屏、单列和底部导航作为唯一主设计路径。
- Compose 布局不得依赖 `Configuration.orientation` 假设固定宽高，也不得按 Samsung/Xiaomi 品牌分支。布局以实际窗口约束为准。
- 当前版本不启用 Navigation Rail 或双栏。系统暴露横屏、分屏或宽窗口时，使用最大宽度受控、居中、可滚动的单列兜底，所有操作仍须可达。
- 项目 target 36；Android 16 在 `sw>=600dp` 环境可能忽略 Manifest 方向请求。平台边界与测试步骤只在 [Android 设备兼容与真机验收](design-docs/android-device-compatibility.md) 维护。
- 不用 `configChanges` 掩盖 Activity 重建；遮罩、草稿编辑、导航和滚动状态按可恢复状态规则保存。项目不使用临时 restricted-resizability opt-out。

## 状态原则

- 加载、空、成功、部分成功和失败必须是显式状态。
- 导入和对账属于长任务；进度应可恢复，离开页面不等于取消。
- 敏感页面进入后台时立即重新遮罩金额，并以 `FLAG_SECURE` 配合不读取账本状态的整页隐私帘保护最近任务缩略图；回到前台后才揭开隐私帘。
- 数据库 Flow 在首次成功后若再失败，必须清空旧快照并进入显式错误状态，禁止继续展示看似新鲜的陈旧余额。
- 创建账户和手工 Draft 的 command ID 与表单同生命周期保存；提交失败或进程恢复后复用，只有成功或用户取消才清除。
- 分享捕获、补全与忽略同样绑定稳定 command ID。Activity 只在消费到匹配命令的完成/失败事件后清除对应 `ACTION_SEND` Intent，避免旋转重建重复打开，也避免旧事件清除后来到达的新分享。
- 正在处理其他操作时，新分享不得静默丢弃；要么进入处理流程，要么给出明确且可重试的失败状态。
- 进程重建后恢复草稿编辑，但不把明文敏感字段放入不受控的持久化状态。
- 错误文案说明“发生了什么、数据是否安全、下一步怎么做”，不回显完整原始通知或账号。
- 分享文本的空内容、过大、编码、存储、冲突、读取和提交失败使用不同的本地化修复提示；草稿卡片与复核工作表不显示原始正文。
- 证据设置状态只展示来源类别、采集方式、时间、载荷大小和生命周期，不读取原始正文。`CLEARED` 必须明确写出“原始内容已清除、结构化记录保留”，`CLEAR_PENDING` 提供重试而不能伪装成完成。

## 组件边界

- `MoneyText`：统一金额、币种、负数和隐私遮罩显示。
- `SourceBadge`：显示来源与采集方式，不暗示已验证程度。
- `AccountPicker`：区分渠道、资产账户、信用账户和外部对手方。
- `ConfidenceExplanation`：显示可理解的规则证据，不展示内部敏感载荷。
- `ReviewBottomSheet`：草稿确认的唯一主组件，多入口复用。
- `SourceReviewBottomSheet`：补全 provider-unverified 分享文本；展示来源未验证与可能重复提示，要求用户填写金额/类型/说明，并为已审计但当前不可恢复的忽略操作提供明确二次确认。
- `EvidenceSettingsScreen`：竖屏可滚动地展示已跟踪占用、7/30/90 天或永久保留、20 项 keyset 历史和逐项清除；永久清除前说明不可恢复、结构化历史保留以及待复核建议会被忽略。
- `ReconcileCard`：左右证据与建议关系并列，要求显式确认危险合并。

## 预览与测试

- 每个关键组件提供无真实个人数据的 Compose Preview。
- UI 测试覆盖：严格/混合/自动模式、疑似重复、来源未验证、分享输入失败/重放/忽略、未知账户、退款、还款、文件导入失败、权限撤销。
- 截图测试至少覆盖简体中文、系统大字体、深色模式和金额隐私遮罩。
- 竖屏小手机与 S24 Ultra 级手机 Preview 是发布基线；强制横向、分屏与 `sw>=600dp` Preview 只验证内容可达和状态保存。
- 测试夹具使用合成商户、掩码账号与随机金额，不复用用户真实账单。

当前 MuMu API 32 已手工验证冷启动与运行中分享、竖屏工作表、重复警告、忽略、Intent 消费和不回显原文；证据设置页的最终手工结果记录在活跃执行计划。这些都不替代自动化 Compose、进程死亡恢复或 Samsung/HyperOS 真机证据。
