# ExecPlan 0007：受控通知模板采样与首批真实 route

- 状态：进行中
- 所有者：项目维护者
- 最后核验：2026-07-31
- 事实来源：用户 2026-07-31 明确授权的本轮新通知范围、当前空生产 route catalog、通知来源规格、来源适配器设计与 Android 16 港版 S24 Ultra

## 目的与用户可见结果

在不读取通知历史、不访问支付 App 私有目录、不把原文写入 Git/日志、也不把 Debug 研究工具带入 Release 的前提下，采集用户本轮主动制造的支付宝、微信和银行新通知。采样结果先保存在 Bill Debug 包私有目录，停止采样后导出到受控临时目录完成脱敏；只有具有稳定元数据、足够交易事实、反例和回放测试的格式，才能进入默认关闭的生产 route catalog。

本计划不能把“收到一条样本”直接升级为 provider 支持。首批 route 仍须满足包名、具体 Android channel、类别、正文模板、解析语义、失败回退和用户可见覆盖缺口；没有金额或没有确证资金变化的事件不得制造 Draft。

## 授权与隐私边界

- 授权窗口只覆盖用户确认之后新产生的支付宝、微信和银行通知；禁止调用 `getActiveNotifications()`、`dumpsys notification` 或其他历史补采接口。
- Debug 采样必须由用户在 Bill 的专用桌面入口显式开始和停止。采样窗口只持久化精确包名、最后一次开始时间和活动状态，以容忍 One UI 重建 Bill 进程；开始时间以前的 `postTime` 一律拒绝。窗口不设时间、条数或文件大小限制，也不根据日期自动关闭或清空；何时停止和清除完全由用户操作。
- 只接收用户选择的精确包名。不得用“捕获全部通知后再过滤”替代包级门禁；银行 App 或短信 App 的包名必须逐个明确加入。
- 只复制现有有界字段：title、text、subText、bigText、summaryText；不读取 notification key、actions、RemoteViews、消息列表、URI 或历史通知。
- 原始样本只在 Debug 包的应用私有文件中短期追加保留，每字段仍最多 1024 字符；本次一次性研究窗口不设人为条数或总文件阈值，真实磁盘 I/O 失败时立即停止。样本不得进入日志、崩溃报告、对话正文或 Git。
- 导出后先在 `C:\tmp` 的受控临时目录脱敏，再生成合成/脱敏固定夹具；确认夹具可回放后删除原始导出和设备端采样文件。
- Release 变体必须是无操作实现，且 Release manifest、dex/string/resource 扫描不得出现采样 Activity、原始样本文件名或研究用安全标签。

## 实施步骤

1. 在主源码定义窄的采样契约和纯 Kotlin 时间窗/包名策略；Debug 与 Release 提供同名变体实现，Release 始终拒绝。
2. 新增仅 Debug manifest 可达的采样页面，展示精确包名、显著原文风险说明、开始/停止/清除和通知使用权设置入口；设备内按 10 条分页列出来源、时间、channel/category、五个允许字段及缺失域，并显示可确认的漏采诊断。
3. listener 仍先复制包名/channel/category。只有生产 route 元数据候选或 Debug 采样窗口候选时才读取有界 `extras`；采样与生产工作共用容量 16 的单消费者队列。
4. 为前向开始时间、包过滤、无限期跨进程恢复、重复开始不覆盖、停止失败关闭、显式清除和 Release 无操作实现补自动化；完整 JVM/Lint/Debug/Release/AndroidTest APK 构建通过。
5. 在 `S24U-HK` 安装 Debug 包、由用户开始采样并授予通知使用权，再按单一事件逐条制造通知；停止后导出且立刻脱敏。
6. 对每个真实候选补成功、缺字段、重复、漂移和敏感反例。首批能证明金额、方向与完成状态的 route 才进入生产 catalog；默认关闭，只形成待复核项。
7. 使用 `code-review` 检查隐私边界、Release 隔离、队列背压、解析语义、去重和测试强度；修复后再真机回放、更新支持矩阵、提交并推送。

## 进度

- [x] 2026-07-31 - 用户明确授权“只读取本轮测试期间新产生的支付宝、微信和银行通知样本，不读取历史通知”。
- [x] 2026-07-31 - 通知 route 控制面、listener 连接健康和隔离偏好测试已作为 `dff4a96` 推送；生产 catalog 仍为空。
- [x] 2026-07-31 - 首轮微信红包、转账和提现操作期间 One UI 回收了 Bill 后台；原型的纯内存窗口停止，重建时又错误删除私有样本，未留下可用原文。修正方向为手动停止的最小持久窗口：重建恢复明确活动状态，重新开始不覆盖已采样文件，停止后继续保留样本到导出或显式清除；Debug manifest 提供用户无需电脑即可再次进入的桌面入口。
- [x] 2026-07-31 - 已实现 Debug 采样契约、显式页面、app-private 追加文件、跨进程无限期活动状态、连接诊断/重绑请求和 Release 无操作隔离；不存在自动时间、条数或文件大小限制。
- [x] 2026-07-31 - 最终完整 879-task 构建通过；S24U-HK/API 36 的 13 个合成通知控制/采样 instrumentation 全部通过；双 ABI Release 隔离扫描与 16 KiB ZIP 对齐通过。
- [ ] 在当前 S24U-HK 上完成新通知采样和原始数据清除。
- [ ] 固化首批真实模板、解析器、反例与回放测试。
- [x] 2026-07-31 - `code-review` 已检查隐私边界、跨进程状态、队列竞态、重绑诊断、导出路径和 Release 隔离；修复后无开放 P0–P3。真实 callback/模板回放仍由后续样本阶段完成。
- [x] 2026-07-31 - 最终 arm64 Debug 包已安装到 S24U-HK，并只打开采样页面；是否开始、停止和清除由用户自主操作，未读取或导出真实样本。
- [x] 2026-07-31 - 经用户授权只查询当前 Android 主用户后，确认“招商银行”安装包名为 `cmb.pb`；Debug 页面已将其加入默认勾选的固定预设。该预设只缩短采样配置，不代表招商银行模板已支持。
- [x] 2026-07-31 - Debug 页面已加入设备内倒序分页预览，每页最多 10 条，逐项显示来源、时间、channel/category、五个允许正文域与缺失域；同时区分跨进程写入计数、本进程队列/正文失败和当前 listener 断连。13/13 S24U-HK 合成 instrumentation 通过；真实通知仍未读取。
- [x] 2026-07-31 - `code-review` 追加修复无界启动计数扫描、清除无确认/过期预览竞态和停止前队列提示；修复后的 489-task 离线 JVM/编译/Lint 门通过。
- [ ] 用户断开设备前安装的是已通过 13/13 合成测试的分页预览版本；上述最后三项审查修复尚未覆盖安装或重跑设备测试，下一次连接时必须先完成。
- [x] 2026-07-31 - Debug 通知采样切片已作为 `400fc0f` 提交并推送到私有仓库 `zjmmt/bill` 的 `main`。

## 验收命令

所有命令在仓库根目录经 CMD 运行；长构建使用可轮询执行，每 20–30 秒向用户反馈状态。

```bat
cmd.exe /d /s /c scripts\android.cmd test lint assembleDebug assembleRelease :app:assembleDebugAndroidTest --console=plain
cmd.exe /d /s /c scripts\android.cmd :app:connectedDebugAndroidTest --console=plain
cmd.exe /d /s /c powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-docs.ps1
cmd.exe /d /s /c git diff --check
```

真机采样只在用户已连接设备且当前授权窗口有效时执行。原始样本的导出、脱敏与删除结果必须记录为数量和安全结论，不得把原文写入本计划。
