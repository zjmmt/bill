# ExecPlan 0004：下拉磁贴单次截图与离线 OCR 草稿

- 状态：进行中；当前 OCR 原型未通过隐私发布门
- 所有者：项目维护者
- 最后核验：2026-07-26
- 事实来源：项目负责人 2026-07-26 的快捷截图、离线 OCR 与免相册权限要求，ADR-0012，当前 Android/来源流水线与 merged Manifest 审计

## 目的与用户可见结果

目标是让用户在支付宝、微信、银行或其他凭证页面下拉系统快捷设置，点击“截图记账”，Bill 只截取当前画面一次、在设备上完成 OCR，并生成一条可编辑的来源待复核项。首次设置教程解释并引导启用专用无障碍服务、添加磁贴和使用系统选图回退；拒绝权限不影响手工账本、通知或 Sharesheet。

当前代码已经实现磁贴、单次截图、单张 Photo Picker、转录证据和 Draft 链，但 OCR 仍是 Chinese ML Kit 开发原型。它尚未通过无遥测/无传输组件发布门，也尚未满足中文+英文、日文+英文的最终语言验收，因此不能作为已支持能力或纯本地承诺发布。

## 范围与非目标

包含：

- API 30+ Quick Settings `TileService` 和专用、用户启用的单次截图 `AccessibilityService`。
- 严格单并发 command、超时/断连/安全窗口/频率限制的封闭错误与可见反馈。
- 发布版随 APK 打包、无需首次下载且通过无遥测审计的中文+英文、日文+英文 OCR；一次点击只运行一次。当前 Chinese ML Kit 原型不满足此项。
- 有界截图转换、版本化 UTF-8 OCR 转录、`GENERIC/PHOTO_OCR` RawEvent/ParseAttempt/待复核链。
- 合成 OCR 转录的金额、方向、对手方保守候选与冲突拒绝测试。
- 启动/设置教程中的用途、权限、添加磁贴、纯本地和回退说明。
- Photo Picker/SAF 免广泛相册权限多选入口的后续同管线接入；当前仅有单张 Photo Picker 原型。

不包含：

- Root、MediaProjection 常驻录屏、后台定时截图、页面轮询、节点树读取、手势或自动点击。
- 绕过 `FLAG_SECURE`、截取锁屏/密码/验证码、读取图库全盘、申请 `READ_MEDIA_IMAGES`。
- 以合成规则声称支付宝、微信或银行正式支持，或自动确认、自动过账、余额推断。
- OCR 模型网络下载、云端推理、遥测、远程模板热更新。当前 ML Kit 原型在遥测审计门失败，不能以“无 Internet 权限”替代这项检查。

## 上下文与仓库导航

- 长期决定：[../../decisions/0012-user-triggered-quick-tile-screenshot-and-bundled-ocr.md](../../decisions/0012-user-triggered-quick-tile-screenshot-and-bundled-ocr.md)
- 本地与资源约束：[../../SECURITY.md](../../SECURITY.md)、[../../decisions/0011-local-resource-budget-first-capture.md](../../decisions/0011-local-resource-budget-first-capture.md)
- 来源能力：[../../product-specs/source-coverage.md](../../product-specs/source-coverage.md)、[../../design-docs/ingestion-and-source-adapters.md](../../design-docs/ingestion-and-source-adapters.md)
- 现有图片回退：`source:generic-receipt-image`、`SharedReceiptImageIngestionService`、`SharedReceiptImageDocumentReader`
- Android 边界：`app/src/main/AndroidManifest.xml`、`BillApplication`、`BillPaymentResultObserverService`

系统 Sharesheet PNG 回退仍只保存一张用户分享的图片并打开空白复核表单，它与 Photo Picker/磁贴 OCR 原型是两条独立路径。当前 `BillScreenshotAccessibilityService` 已声明 `canTakeScreenshot=true`，但不读取节点、包名或窗口标题，也不执行手势；`CaptureMethod.PHOTO_OCR` 已有 `source:generic-photo-ocr` 生产链。这个代码事实不改变其未通过发布门的状态。

## 进度

- [x] 2026-07-26 - 确认 Sharesheet 不是最终一键交互；确定 Quick Settings 磁贴 + 专用无障碍单次截图是主路径，Sharesheet 为安全窗口/API 回退。
- [x] 2026-07-26 - 接受 ADR-0012，固定无节点、无手势、无持续事件、bundled OCR 和 `GENERIC/PHOTO_OCR` 待复核边界。
- [x] 2026-07-26 - 实现纯 Kotlin OCR 转录格式、保守解析器与恶意/冲突输入测试。
- [x] 2026-07-26 - 接通 application 证据暂存、RawEvent、ParseAttempt 和方向/金额预填。
- [x] 2026-07-26 - 实现 Android 单并发 OCR、单次截图 service、磁贴、反馈和教程 CTA；当前引擎仅为不合格的 Chinese ML Kit 原型。
- [x] 2026-07-26 - 实现免广泛相册权限的单张系统 Photo Picker 回退；多张串行导入仍未实现。
- [ ] 2026-07-26 - 替换 OCR 引擎并证明中文+英文、日文+英文、无遥测/无网络组件和目标 ABI；未完成前不允许发布该入口。
- [ ] 2026-07-26 - 完成单元、Lint、assemble、最终 APK 审计、文档检查和指定 `code-review` 复审。
- [ ] 用户明确进入真机验收时 - 在三台目标设备运行权限、磁贴、安全窗口、资源和支付宝/微信真实页面测试。

## 意外发现

- Android 14+ 的 `MediaProjection` 每次 capture session 都需要新的用户同意，不能用一次授权长期支撑磁贴。
- `AccessibilityService.takeScreenshot` 从 API 30 可用，并会对安全窗口、过密调用和无权限返回独立失败码；回退必须按失败原因解释，不能静默丢失。
- 当前应用已声明一个故意停用的未来结果页 service。新能力必须是第二个专用 service，不能给结果页 service 顺手加截图、节点或手势能力。
- 2026-07-26 merged debug Manifest 审计发现当前 ML Kit 原型引入 DataTransport/Firebase/Job/Alarm 等组件；官方 ML Kit 数据披露也列出诊断与使用分析数据。原型不可作为无遥测发布实现。

## 决策日志

- 2026-07-26 - 下拉磁贴是主入口；桌面小组件可后续复用 command controller，但不作为首个 OEM 验收面。
- 2026-07-26 - 原始截图只作为本次 OCR 的瞬时输入；持久证据使用有界版本化转录，以降低全屏像素留存和测试面。
- 2026-07-26 - OCR 候选保持来源中立；多个金额或方向冲突时不猜，不因“一键”牺牲待复核和账务语义。
- 2026-07-26 - 将“交互路径已实现”和“OCR 发布资格通过”分开记录；不以无 `INTERNET` 声明掩盖第三方 SDK 的数据披露或组件审计。

## 实施步骤

1. 已完成：在来源层新增 `generic-photo-ocr`，定义转录 MIME、版本、严格 UTF-8/行列/长度限制和确定性 parser；金额仅接受带人民币标记的唯一候选，方向仅接受无冲突的高信号词，对手方只从显式标签提取。
2. 已完成：在 application 新增 PHOTO_OCR capture service，复用现有租约暂存、私有 evidence store、RawEvent 和 parser registry；输入转录临时字节在所有路径擦除。
3. 待完成：替换 `BundledLocalOcrEngine` 的 ML Kit 依赖。新引擎必须为经许可审查的本地运行时和静态模型资产，明确 `arm64-v8a`/目标 ABI 策略，固定单线程/串行工作，并在成功、失败和取消时释放会话、Bitmap 与临时字节。
4. 待完成：为替换引擎加入中文+英文、日文+英文的合成转录回归、空结果/异常/尺寸边界和一次点击单任务测试；不能仅靠“中文模型能读拉丁字符”的推断验收。
5. 已完成但需复验：进程内单并发 command controller、`TileService` 和专用 AccessibilityService；断连、忙碌、API 不支持、系统拒绝、OCR/入库失败均更新磁贴/Toast 安全状态。
6. 已完成单图、待完成多图：Photo Picker 单选接同一 OCR engine；若加入多选，必须串行且有数量/总字节上限，不能扩大为相册扫描。
7. 待完成：运行完整验证、release APK 依赖/Manifest/组件审计、三台目标真机资源测试和指定 `code-review`，修复高优先级问题，并把实际支持状态、资源和未验收项回写唯一事实来源。

## 具体命令

所有命令从仓库根目录经 CMD 执行：

```bat
cmd.exe /d /s /c "scripts\android.cmd :source:generic-photo-ocr:test :application:test :app:testDebugUnitTest --console=plain"
cmd.exe /d /s /c "scripts\android.cmd test lint assembleDebug :data:local:assembleDebugAndroidTest --console=plain"
cmd.exe /d /s /c "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-docs.ps1"
cmd.exe /d /s /c "git diff --check"
```

真机命令只能在项目负责人明确开始该轮真实设备测试后执行；当测试确实需要真机时，Codex 必须先停止并弹出“请连接真机后回复已连接”的确认提示，收到本轮明确的已连接回复后才可执行任何 `adb`、安装或设备状态读取命令。历史授权或历史连接不构成本轮许可。不得把设备序列号、真实支付截图、OCR 转录或支付正文写入仓库/对话。

## 验证与验收

- 没有磁贴点击时，24 小时逻辑审计中不存在截图、OCR、前台服务、网络或主动页面读取入口；最终 release APK 也不得因 OCR 依赖引入遥测传输、Job、Alarm 或模型下载组件。
- 每个点击最多对应一个 capture command、一个 OCR 转录 RawEvent 和一个来源建议；重复点击、进程重建和入库重试不会自动形成多笔正式账。
- 服务配置证明 `canRetrieveWindowContent=false`、`canPerformGestures=false`；代码不访问 event text、node、package、window title 或执行 global action。
- 图片像素、尺寸、转录字节、行数、行长、金额候选数和并发均有上限；每个失败路径释放 HardwareBuffer/Bitmap 并擦除临时字节。
- APK merged manifest 没有 `INTERNET`、媒体库读取、悬浮窗、前台服务或 MediaProjection 权限；OCR 模型无需首次网络下载，且依赖树/组件审计没有遥测传输或其后台调度入口。当前 ML Kit 原型未满足后半项。
- 合成付款、收款、退款/转账冲突、多金额、空 OCR、畸形 UTF-8、超限、重复 command 和安全失败都有回归；任何结果最多是待复核。
- API 30 以下和安全窗口显示明确回退；拒绝高风险权限后，手工录入、通知基础和分享 PNG 仍可用。

## 幂等、回滚与恢复

command ID 贯穿 capture、暂存与 RawEvent receipt；同一进程只允许一个活动任务，既有仓储 command receipt 处理重试。进程在截图后、OCR 后或暂存中死亡时，Bitmap/HardwareBuffer 由系统/进程回收，租约恢复沿用 v5 有界清理；未出现 RawEvent 不显示成功。

关闭或撤销无障碍权限立即停止新截图，不删除已有本地证据或草稿。若 bundled OCR 构建、资源或政策门失败，移除磁贴/service 注册并保留系统分享 PNG 回退；不得自动改用 MediaProjection、网络 OCR 或常驻节点读取。

## 结果与复盘

交互、来源链和单图入口已实现；当前 OCR 运行时因隐私发布门和语言覆盖未达标而不具备发布资格。完成时记录替换引擎与模型许可、实际模块、APK 增量、ABI、自动化任务数、三台真机单次峰值、失败矩阵、code-review 修复和仍未达到 provider 支持的范围。
