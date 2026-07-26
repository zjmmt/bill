# Android 开发入门路径

- 状态：已确认学习路径，项目命令已验证
- 所有者：项目维护者
- 最后核验：2026-07-19
- 事实来源：ADR-0004、当前 Gradle 模块和 Android UI 架构

这份文档面向没有 Java 或 Android 经验的维护者。目标不是先学完一整套课程，而是能看懂 Bill 的骨架、改一个小切片并用测试确认没有破坏账务边界。技术决定与版本以 [ADR-0004](../decisions/0004-android-compose-baseline.md) 为准。

## 先分清四件事

- **Kotlin** 是主要编程语言。写 Bill 不要求先学习 Java；JDK 17 是构建工具运行环境，不等于项目必须用 Java 写业务代码。
- **Jetpack Compose** 是 Android UI 工具包。页面由 Kotlin 函数根据 `UiState` 渲染，不使用 XML 布局作为主线。
- **Material 3** 是组件、颜色、排版、状态和交互规范。它决定按钮、卡片、导航等怎样保持一致，不是另一门编程语言。
- **Gradle/AGP** 负责模块、依赖、编译和测试；Room 负责本地数据库边界。它们与 Compose 各管一层。

## 最低学习顺序

1. Kotlin 基础：`val`、函数、空安全、`data class`、`enum`、`sealed interface`、集合和单元测试。
2. Bill 领域边界：先读 `core:model` 与 `core:ledger`，理解金额最小单位、不可变证据和平衡分录。
3. Compose 基础：Composable、`Modifier`、状态提升、列表、主题、Preview 和无障碍语义。
4. 单向数据流：不可变 `UiState` 向下、`UiAction` 向上；先做 `Loading/Empty/Content/Error`，再接真实 use case。
5. Android 生命周期与权限：通知访问、Bill 自身通知权限、SAF 和后台限制分别理解，不能混成一个“权限”。
6. 需要异步数据时再学协程与 Flow；需要多个生命周期注入点时再评估 Hilt，不为骨架提前叠框架。

## 仓库阅读顺序

| 目标 | 先读 |
| --- | --- |
| 产品为什么存在 | [`README.md`](../../README.md)、[MVP](../product-specs/mvp.md) |
| 依赖可以往哪里走 | [`ARCHITECTURE.md`](../../ARCHITECTURE.md) |
| 金额与账本规则 | `core:model`、`core:ledger`、[领域模型](../design-docs/domain-model.md) |
| 页面状态和组件 | `application`、`feature:overview`、`feature:review`、[FRONTEND.md](../FRONTEND.md) |
| 主题和视觉 | `core:designsystem`、[UI 设计系统](../design-docs/ui-design-system.md) |
| 通知/文件入口 | `source:contract`、[来源适配器设计](../design-docs/ingestion-and-source-adapters.md) |
| 数据库存储 | `data:local`、[SECURITY.md](../SECURITY.md) |

## 一个安全的小改动循环

1. 从一个用户可见状态开始，例如总览空状态文案或合成来源状态。
2. 先改纯 Kotlin 状态/映射并补测试，再改 Composable 渲染。
3. Preview 和测试只使用合成数据；不复制真实通知、账号或账单到代码。
4. 通过版本库记录的统一检查命令验证，不跳过失败任务。
5. 涉及通知、Room、账务语义或权限时，先回到对应设计文档与活跃 ExecPlan。

当前工作区已在项目隔离目录配置 JDK 17 与 Android SDK 36。所有命令从 CMD 进入；`scripts\android.cmd` 会为当前进程选择 `.tools\jdk17`，再调用 Gradle Wrapper，不修改系统级 Java：

```bat
cmd.exe /d /s /c scripts\android.cmd projects
cmd.exe /d /s /c scripts\android.cmd test lint assembleDebug
```

第二条命令已在 2026-07-19 以 JDK 17、Gradle 8.13 和 Android API 36 通过。`.tools/`、`.android-sdk/` 与 `local.properties` 都不入库；新工作区必须重新安装 JDK 17/API 36，并在 `local.properties` 写入自己的 `sdk.dir`。直接调用 `gradlew.bat` 时，则由调用者保证 `JAVA_HOME` 指向 JDK 17。

## Python 放在哪里

Python 仍然适合脱敏脚本、fixture 生成、样本统计和离线研究。损失不在“Python 算不了账”，而在手机主体若采用 Python UI，需要额外桥接通知服务、WorkManager、Room、Keystore、无障碍语义和 OEM 生命周期，测试与发布边界会更复杂。因此 Python 保留为开发工具，Android 产品主体使用 Kotlin。

## 常见误区

- 不要把 Compose 状态放进 Composable 的全局可变对象；页面重建不是异常路径。
- 不要让 UI 直接读 Room、通知对象或原始文件。
- 不要用颜色作为收支、风险或成功的唯一信号。
- 不要因为模拟器或一台 S24 Ultra 正常，就宣称 Samsung、小米或某个通知模板已支持；真机门见 [Android 设备兼容与真机验收](../design-docs/android-device-compatibility.md)。
