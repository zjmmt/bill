# ADR-0004：Android 原生 Kotlin/Compose 技术基线

- 状态：已接受，待首轮构建与真机验证
- 所有者：项目维护者
- 最后核验：2026-07-19
- 事实来源：产品范围、Android 官方工具兼容矩阵、首批测试设备与 UI 设计系统

## 背景

项目需要通知监听、Storage Access Framework、WorkManager、Room、Keystore、无障碍和 Samsung/HyperOS 真机验证。开发环境当前只有 Java 8、Unity 附带的 JDK 11/API 35 SDK，仓库没有 Gradle Wrapper，无法支撑目标 API 36 的现代 Compose 工程。

## 给初次接触者的概念分层

这套技术名词不是四种互相替代的语言：

- **Kotlin** 是产品主体的编程语言。维护者不需要先学 Java；JDK 17 是 Gradle 和编译器的运行环境，不表示业务代码必须写成 Java。
- **Jetpack Compose** 是 Android UI 工具包。页面是 Kotlin 函数，根据不可变状态渲染按钮、列表、卡片和导航；它取代本项目主线中的 XML 布局，但不取代 Android 权限、生命周期或系统服务。
- **Material 3** 是组件与视觉交互规范。它提供语义颜色、状态、排版、点击区和自适应导航，具体取舍仍由 [UI 设计系统](../design-docs/ui-design-system.md) 约束。
- **Room、WorkManager、Keystore 和 SAF** 分别处理本地数据库、可恢复后台任务、密钥和用户选择文件；它们不是 Compose 的一部分。

Compose 与本项目匹配，不是因为“新”，而是因为草稿复核天然具有 `Loading/Empty/Content/Saving/Error` 等显式状态。不可变 `UiState` 可以直接映射为页面，并在不接真实财务数据时用 Preview 和 UI 测试覆盖。Compose 的语义树、自适应布局和 Material 3 组件也为 TalkBack、大字号、深色模式和不同窗口尺寸提供同一条实现路径。通知监听、OEM 省电与进程恢复仍必须单独做真机验证，不能靠 Compose 自动解决。

## 决定

- 产品主体使用 Kotlin/JVM；Android UI 使用 Jetpack Compose，并以 Material 3 作为组件与语义主题基线。Python 只用于样本研究、脱敏和开发工具。
- 首个稳定工具链固定为 JDK 17、Gradle 8.13、Android Gradle Plugin 8.13.2、Kotlin 2.3.21、KSP 2.3.9 和 Compose BOM 2026.03.01。该 BOM 保持 Compose 1.10.6；官方 Maven 元数据中不存在 `2026.04.00`，而 `2026.04.01` 已进入 Compose 1.11，因此后续升级必须单独验证 API/AGP 要求。
- `compileSdk = 36`、`targetSdk = 36`、`minSdk = 26`。Java/Kotlin 字节码目标为 17。
- 持久层使用 Room 2.8.4 稳定版；不采用仍在预览期的 Room 3。
- 初始包名使用 `dev.bill.app` 作为可替换的内部基线；公开发行前必须确认反向域名与签名身份。
- 领域模块保持纯 Kotlin，不依赖 Android、Compose、Room 或依赖注入框架。
- 首个骨架采用显式构造函数和手工装配；Hilt 在出现多个 Android 生命周期绑定或 WorkManager 注入需求时单独引入，避免空骨架先承担代码生成复杂度。
- 主题与页面结构以 [UI 设计系统](../design-docs/ui-design-system.md) 为准；地区/OEM 风险和真机门以 [Android 设备兼容与真机验收](../design-docs/android-device-compatibility.md) 为准。

版本只在 `gradle/libs.versions.toml` 维护。升级编译器、AGP、Room 或 schema 时必须重新运行构建、单元测试、lint 和迁移测试。

## 结果

- 正面：通知、SAF、WorkManager、Keystore 与无障碍直接使用 Android 平台能力；状态驱动 UI、Material 3、Room 编译期检查和官方测试工具走同一套 Kotlin 工程。核心功能设计为不依赖 GMS，并分别在实际有/无 GMS 的环境验证。
- 代价：需要学习 Kotlin 基础、Compose 状态/重组、Android 生命周期和 Gradle；构建环境需要独立 JDK 17、Android SDK 36 与首次联网下载依赖。最小学习顺序见 [Android 开发入门路径](../development/android-getting-started.md)。
- 风险：两台 S24 Ultra 都是大屏高端机，国行小米的具体型号尚未确认；在设备建档前不能声称已覆盖中端或低内存场景。发布前仍需明确代表性设备，包名也需发行身份确认。

## 方案比较

| 方案 | 对本项目的收益 | 成本与结论 |
| --- | --- | --- |
| Kotlin + Compose | 原生系统服务、状态驱动 UI、Preview/测试、Material 3 与无障碍语义在同一语言内 | **采用**；需要学习 Kotlin、Compose 和 Gradle，但没有跨语言桥接 |
| Kotlin + XML Views | Android 原生、生态成熟 | 可行，但新项目没有旧 View 资产可复用，还要同时维护 XML 布局与 Kotlin 状态绑定；没有足以抵消双体系成本的收益 |
| Java + XML Views | Android 原生、历史资料多 | 维护者同样需要新学 Java；相较 Kotlin 主线没有项目特有优势，样板和空安全负担更高 |
| Python + Kivy | 语言上手成本较低，适合快速界面原型 | 不作为手机主体；通知服务、后台任务、Room、Keystore、无障碍和 OEM 生命周期需要额外桥接。Python 保留为脱敏、fixture 和研究工具 |
| Flutter / React Native | 跨平台 UI 与热重载 | 当前只有 Android 目标且平台采集边界重；额外运行时与插件层不能换来实际跨平台收益 |

这里的选择不意味着 Kotlin/Compose 在所有应用中都优于其他方案；它只是在 Bill 当前 Android-only、平台服务密集、本地优先和强状态审计约束下，减少了边界数量。

## 其他被否决基线

- Room 3 预览版或 KMP：当前只有 Android 目标，承担预览 API 与多平台复杂度没有用户收益。
- 直接复用 Unity SDK：缺 API 36、工具偏旧且位于不可维护的共享安装目录。
