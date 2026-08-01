# ExecPlan 0004：下拉磁贴单次截图与离线 OCR 草稿

- 状态：进行中；本地 OCR 引擎、未签名 Release 静态审计、固定内部签名 APK 身份/对齐、港版 S24 Ultra 合成三语/简中状态回归与 11 张本机私有真实页面回放已完成，真实简中微信页面、签名包资源/安装与完整目标真机发布门未完成
- 所有者：项目维护者
- 最后核验：2026-08-02
- 事实来源：项目负责人 2026-07-26 的快捷截图、离线 OCR 与免相册权限要求，ADR-0012，当前 Android/来源流水线与 merged Manifest 审计

## 目的与用户可见结果

目标是让用户在支付宝、微信、银行或其他凭证页面下拉系统快捷设置，点击“截图记账”，Bill 只截取当前画面一次、在设备上完成 OCR，并生成一条可编辑的来源待复核项。首次设置教程解释并引导启用专用无障碍服务、添加磁贴和使用系统选图回退；拒绝权限不影响手工账本、通知或 Sharesheet。

当前代码已经实现磁贴、单次截图、最多 5 张 Photo Picker 串行导入、转录证据和 Draft 链。OCR 已从 Chinese ML Kit 替换为随包的 PP-OCRv6 small ONNX 检测/统一多语言识别模型，运行时为 ONNX Runtime Android 1.24.3 与 OpenCV Android 4.12.0；模型文件合计 31,190,469 字节，来源与 SHA-256 固定在 `ocr/paddle/MODEL_PROVENANCE.md`。引擎无后台入口、零排队、两条 CPU 线程、识别 batch 为 1、输入最长边 1600，且每次任务结束释放会话。依赖 AAR 与当前 arm64-v8a/x86_64 `release-unsigned` 分包的静态审计未发现网络权限、传输组件、Job 或 Alarm；代码在建会话前调用 `setTelemetry(false)`。分包权限、组件、ABI、模型、体积和 16 KiB ZIP 对齐已核验，固定内部测试签名的双 ABI 包也已完成证书身份/ZIP 对齐验证，arm64 正式签名包已在 S24U-HK 覆盖安装并冷启动。港版 S24 Ultra 的合成三语模型、合成多金额空间链、11 张本机私有真实繁中/英文过程页和简中四状态页 instrumentation 已通过；但真实简中微信页面、系统截图/选图 UI、ELF LOAD 段页兼容、签名 Release 资源和三台目标真机验收尚未完成，因此仍不能标为发布级支持。

## 范围与非目标

包含：

- API 30+ Quick Settings `TileService` 和专用、用户启用的单次截图 `AccessibilityService`。
- 严格单并发 command、超时/断连/安全窗口/频率限制的封闭错误与可见反馈。
- 随 APK 打包、无需首次下载且通过静态无网络边界审计的中文+英文、日文+英文 OCR；当前引擎、未签名 Release 分包、固定签名 APK 身份/对齐和 S24U-HK 合成三语推理已完成，真实简中/日文页面与完整目标真机资源证据仍待完成。
- 有界截图转换、版本化 UTF-8 OCR 转录、`GENERIC/PHOTO_OCR` RawEvent/ParseAttempt/待复核链。
- 合成 OCR 转录的金额、方向、对手方保守候选与冲突拒绝测试。
- 启动/设置教程中的用途、权限、添加磁贴、纯本地和回退说明。
- Photo Picker 免广泛相册权限的有界多选入口；当前最多 5 张，逐张读取、识别和入库，不扫描图库。

不包含：

- Root、MediaProjection 常驻录屏、后台定时截图、页面轮询、节点树读取、手势或自动点击。
- 绕过 `FLAG_SECURE`、截取锁屏/密码/验证码、读取图库全盘、申请 `READ_MEDIA_IMAGES`。
- 以合成规则声称支付宝、微信或银行正式支持，或自动确认、自动过账、余额推断。
- OCR 模型网络下载、云端推理、遥测、远程模板热更新。最终 APK 仍必须同时证明无网络权限与无传输/调度组件，不能只看源码声明。

## 上下文与仓库导航

- 长期决定：[../../decisions/0012-user-triggered-quick-tile-screenshot-and-bundled-ocr.md](../../decisions/0012-user-triggered-quick-tile-screenshot-and-bundled-ocr.md)
- 本地与资源约束：[../../SECURITY.md](../../SECURITY.md)、[../../decisions/0011-local-resource-budget-first-capture.md](../../decisions/0011-local-resource-budget-first-capture.md)
- 来源能力：[../../product-specs/source-coverage.md](../../product-specs/source-coverage.md)、[../../design-docs/ingestion-and-source-adapters.md](../../design-docs/ingestion-and-source-adapters.md)
- 现有图片回退：`source:generic-receipt-image`、`SharedReceiptImageIngestionService`、`SharedReceiptImageDocumentReader`
- Android 边界：`app/src/main/AndroidManifest.xml`、`BillApplication`、`BillPaymentResultObserverService`

系统 Sharesheet PNG 回退仍只保存一张用户分享的图片并打开空白复核表单，它与 Photo Picker/磁贴 OCR 是两条独立路径。当前 `BillScreenshotAccessibilityService` 已声明 `canTakeScreenshot=true`，但不读取节点、包名或窗口标题，也不执行手势；`CaptureMethod.PHOTO_OCR` 已有 `source:generic-photo-ocr` 生产链。引擎替换和未签名 APK 静态审计不等于 provider 支持，也不取消真实推理、签名与真机发布门。

## 进度

- [x] 2026-07-26 - 确认 Sharesheet 不是最终一键交互；确定 Quick Settings 磁贴 + 专用无障碍单次截图是主路径，Sharesheet 为安全窗口/API 回退。
- [x] 2026-07-26 - 接受 ADR-0012，固定无节点、无手势、无持续事件、bundled OCR 和 `GENERIC/PHOTO_OCR` 待复核边界。
- [x] 2026-07-26 - 实现纯 Kotlin OCR 转录格式、保守解析器与恶意/冲突输入测试。
- [x] 2026-07-26 - 接通 application 证据暂存、RawEvent、ParseAttempt 和方向/金额预填。
- [x] 2026-07-26 - 实现 Android 零排队单并发 OCR、单次截图 service、磁贴、反馈和教程 CTA。
- [x] 2026-07-26 - 实现免广泛相册权限、最多 5 张的系统 Photo Picker 串行回退。
- [x] 2026-07-26 - 用 PP-OCRv6 small + ONNX Runtime/OpenCV 替换 ML Kit；随包模型、许可、哈希、两线程/batch 1/1600 像素边界、逐任务释放与 AAR 无网络组件静态审计已落库。
- [x] 2026-07-30 - 完成全量单元、Lint、Debug/Release 和两组 AndroidTest APK 构建，以及当前未签名 Release 分包的权限、组件、ABI、模型、体积、哈希与 16 KiB ZIP 对齐审计。
- [x] 2026-07-30 - 完成指定 `code-review` 复审；未发现 P0/P1，资源所有权、取消释放、临时数据擦除、无网络边界和币种限制已复核。
- [x] 2026-07-30 - 为单次截图 command 增加 90 秒可取消租约，并以 `ACTIVE -> COMMITTING` CAS 在证据准入、容量清理或写入前原子交接取消权与本地提交权。取消胜出时停止 Job 且不进入有副作用的准入路径；cancellation handle 注册中的竞态不再把 `null` 当取消成功。提交前释放像素，local commit 另有 15 秒协作式截止；闸门后的超时、取消与非致命异常统一为 `COMMIT_STATUS_UNKNOWN`。提交或既成结果不可取消时只给一次 15 秒收尾宽限，仍无回调则显示“结果未确认”并释放单飞占用。opaque request identity 隔离迟到回调，旧回调不能完成后续新请求；对应超时、断连/替换、注册竞态、提交竞态、部分暂存后异常、调度失败、挂起端口、字节擦除和迟到回调回归已加入工作树。
- [x] 2026-07-30 - 将 Photo Picker 的 1–5 张图片改为显式批次：始终逐张串行，设置页显示进度并在批次期间禁用第二次选择，完成时只发一次成功/失败汇总并导航到首个可复核项，避免逐图弹窗或导航风暴。
- [x] 2026-07-30 - 磁贴不可用入口与无障碍服务详情页统一深链到 Bill 设置教程；启动“纯本地/不走网络”声明增加 safe drawing insets 与纵向滚动；简中/繁中 OCR 披露统一为“静态本地无网络边界已过，实际推理/签名/目标真机仍待验”。
- [x] 2026-07-31 - 在港版 S24 Ultra（Android 16、arm64-v8a）执行随包模型合成中/英/日支付文本 instrumentation，1/1 通过；OpenCV 与 ONNX 原生库、检测/识别模型均成功加载并释放。该结果不代表真实支付截图准确率或资源发布门。
- [x] 2026-07-31 - 将 OCR 每行四点框归一化为 0..10000 整数矩形并保留到空间转录 v2；解析器继续兼容 v1，只在一个独立金额相对正文中位高度和第二候选都明确占优时从多金额页面预填。等大、缺失布局、退款/转账/红包语义继续失败关闭，结果始终待复核。
- [x] 2026-07-31 - 指定 `code-review` 复核空间转录；修复空 OCR 框导致整次识别失败和值对象字符串可能泄露转录的问题，补英语大小写退款/红包阻断、失败/拒绝/取消页不提议金额及转录遮罩回归。强制重跑 `generic-photo-ocr`、application 与 app 单测 167 个任务全部通过；空间 OCR AndroidTest APK 已编译。
- [x] 2026-07-31 - “合成多金额图像 -> 随包 OCR -> 空间转录 v2 -> 主金额待复核建议”的定向 app instrumentation 在港版 S24 Ultra/API 36 上 1/1 通过。该用例不经过系统截图/Photo Picker UI、真实支付页面、证据入库或 provider 识别。
- [x] 2026-08-01 - 根据本机私有真实页面的脱敏结构复盘，补“未领取/未打开、处理中、预计到账、待完成”状态门：这些页面即使有清晰主金额也不得预填；已完成的退款、转账或红包仍可只给金额、不猜普通收支方向。原始截图不进入仓库、日志或测试夹具。
- [x] 2026-08-01 - 在港版 S24 Ultra/API 36 逐张回放 11 张 Git 忽略且双份本机持久化的真实繁中/英文过程截图：初始完整语义 7/11，规则修正后金额与预期语义均为 11/11；单张 OCR 1.297–2.026 秒、中位数 1.688 秒。另以程序绘制的简中“领取成功、红包尚未领取、提现处理中、转账成功”四页完成定向 app instrumentation 1/1。真实简中微信页面仍为研究门。
- [ ] 设备验收轮 - 执行真实简中/日英页面推理、ELF/原生库加载、签名 APK 安装，以及目标真机耗时/峰值/空闲释放；未完成前不允许发布该入口。固定证书身份与 ZIP 对齐已通过，不再把它们列作未实现功能。
- [ ] 用户明确进入真机验收时 - 在三台目标设备运行权限、磁贴、安全窗口、资源和支付宝/微信真实页面测试。

## 意外发现

- Android 14+ 的 `MediaProjection` 每次 capture session 都需要新的用户同意，不能用一次授权长期支撑磁贴。
- `AccessibilityService.takeScreenshot` 从 API 30 可用，并会对安全窗口、过密调用和无权限返回独立失败码；回退必须按失败原因解释，不能静默丢失。
- 当前应用已声明一个故意停用的未来结果页 service。新能力必须是第二个专用 service，不能给结果页 service 顺手加截图、节点或手势能力。
- 2026-07-26 merged debug Manifest 审计发现当前 ML Kit 原型引入 DataTransport/Firebase/Job/Alarm 等组件；官方 ML Kit 数据披露也列出诊断与使用分析数据。原型不可作为无遥测发布实现。
- 2026-07-26 - PaddleOCR 官方 Android SDK 已转向 ONNX Runtime；PP-OCRv6 small 的统一识别模型字典同时包含中、英、日字符。候选运行时 AAR 本身不声明权限或 Android 组件，但这仍不能代替最终 APK 审计和实际推理验收。
- 2026-08-01 - 21 条通知的脱敏结构复盘确认，剩余样本主要是无金额的红包/转账/提现状态、基金/活动消息、登录提醒或无法在读取正文前收窄的短信；继续扩通知正则会越过“确证金额与资金变化”门。相反，真实页面集合暴露了 OCR 语义缺口：未打开红包和银行处理中页面含有醒目金额，现有 parser 会错误提出金额。
- 2026-08-01 - OCR 可能把币种符号与小数主金额拆成不同文本框；只有页面含明确完成语义、纯小数框显著占优且不在底部时才可恢复，不能把这一例外扩成“页面最大数字就是金额”。普通付款成功页还可能包含促销红包文案，必须把促销提示与真正红包资金事件分开。
- 2026-08-01 - 用户的真实微信环境为繁中/英文，不能据此宣称简中真实页面已覆盖；同一随包模型与规则用简中程序绘制页补了字符和状态回归，但仍保留真实简中页面验收门。通知标题本地化与 OCR 无关：`Weixin Pay`/`微信支付` 只是 route 提示，金额始终来自已验证正文。

## 决策日志

- 2026-07-26 - 下拉磁贴是主入口；桌面小组件可后续复用 command controller，但不作为首个 OEM 验收面。
- 2026-07-26 - 原始截图只作为本次 OCR 的瞬时输入；持久证据使用有界版本化转录，以降低全屏像素留存和测试面。
- 2026-07-26 - OCR 候选保持来源中立；多个金额或方向冲突时不猜，不因“一键”牺牲待复核和账务语义。
- 2026-07-26 - 将“交互路径已实现”和“OCR 发布资格通过”分开记录；不以无 `INTERNET` 声明掩盖第三方 SDK 的数据披露或组件审计。
- 2026-07-26 - 采用 PP-OCRv6 small 官方 ONNX 模型、ONNX Runtime Android 1.24.3 与 OpenCV Android 4.12.0；不使用服务端、运行时模型下载或持久 OCR 会话。
- 2026-07-31 - OCR 结果页不能只保留字符串：模型已提供阅读顺序与四点框，空间转录 v2 以 0..10000 的整数坐标保存最小布局证据。金额选择只使用独立金额行的相对高度，不根据 App 配色、logo 或可伪造截图升级 provider 身份；v1 仍可重放。
- 2026-08-01 - 交易完成状态先于金额版面规则：出现失败、取消、拒绝、未领取/未打开、处理中、预计或待完成语义时，整页不提出金额；仅有退款、转账、红包、充值、提现等资金语义但状态没有被否定时，仍可保留金额证据，却不推断普通收入/支出方向。
- 2026-08-01 - 简体、繁体和英文是同一兼容目标。规则为关键状态词维护简繁体对，真实繁中/英文页面与简中合成真机页分开记证据；合成简中不得替代真实微信简中布局。通知 route 保留稳定 ID，只增加 `微信支付` 标题别名，未知中文正文在取得真实 callback 前失败关闭。

## 代码审查记录（2026-08-01，真实页面与多语言状态）

- 范围：完成状态优先级、空间纯小数金额恢复、正负号方向、促销红包与真实资金事件区分、简繁体状态词、微信通知标题别名、稳定 route ID、隐私边界和回归覆盖。
- 已修复：英语处理中状态从宽泛子串改为整行匹配，避免正常文案误触发；方向符号只作用于选中主金额且要求交易详情上下文，避免任意页面正负号决定方向；纯小数恢复要求明确完成上下文与显著版面；促销红包例外只允许普通已完成支出，不放宽真正红包/转账/退款阻断；微信 `微信支付` 标题复用既有 route ID，未经样本验证的中文正文反例保持拒绝。
- 证据：parser/application/WeChat/app 定向套件 169 个任务全部强制执行通过，最终 891-task 全量 JVM/Lint/Debug/Release 与三组 AndroidTest APK 构建通过；11 张本机私有真实页面修正后金额/预期语义 11/11；简中四状态页随包 OCR 定向真机 instrumentation 1/1。原始截图、OCR 原文、通知正文和设备序列号未进入 Git、文档或测试日志。
- 保留风险：真实简中微信页面和中文通知正文尚无授权样本；Debug instrumentation PSS 不是签名 Release 峰值/空闲内存；系统截图/Photo Picker UI、签名包安装/资源、ELF 页兼容和三台目标真机门仍开放。因此本轮不升级 provider 或 OCR 发布支持状态。

## 代码审查记录（2026-07-26，本地引擎替换）

- 范围：ML Kit 移除、PaddleOCR/ONNX/OpenCV 供应链与许可证、模型资产、截图/Photo Picker 生命周期、并发/资源上限、API 30/34 兼容、ABI 包体策略、用户声明和测试。
- 已修复：OCR 从可排队互斥改为零排队单任务，避免跨磁贴/选图入口积压 Bitmap；检测和识别模型改为顺序加载，模型字节、输入 tensor 与输出概率在所有路径擦除；创建与释放在取消状态下仍安全完成；HardwareBuffer、Bitmap、Mat 和裁剪中间量具有单一所有者并在异常/取消时释放；选图、分享和 OCR 的临时字节在 prompt cancellation 前后均擦除。输入由 6 MP/2048×4096 收紧为 2.56 MP/1600×1600；TileService 的旧 API Lint 抑制移到精确方法；截图 API 30 类型只在版本门内构造；arm64-v8a 与 x86_64 使用分包，删除 32 位 ABI，避免一个 APK 携带四套原生库。
- 静态结论：未发现截图/转录上传、运行时模型下载、常驻 OCR、节点/手势读取、自动过账或第三方传输组件。ONNX Runtime/OpenCV AAR Manifest 只有 `uses-sdk`，没有权限或组件；模型与 AAR SHA-256、上游修订和许可证已固定。
- 静态结果：850-task 全量构建通过，已包含设置深链、90 秒截图租约/迟到回调隔离、handle 注册竞态、15 秒本地提交截止/提交后未知态、Photo Picker 单飞批次汇总、启动声明布局及对应回归；arm64-v8a 未签名 Release 为 90,540,713 bytes，x86_64 为 128,362,402 bytes。两包 Manifest 一致且只请求应用自身签名级动态接收器权限，OCR 模型和目标 ABI 均在包内，`zipalign -P 16` 通过。
- 保留风险：instrumentation 尚未执行，未签名分包不是可分发发行物；`zipalign` 不证明每个 ELF LOAD 段兼容 16 KiB 页。三台设备的语言准确性、原生加载、耗时、峰值、空闲释放与电量仍待证明。因此审查没有把该入口升级为发布级支持。

## 实施步骤

1. 已完成：在来源层新增 `generic-photo-ocr`，定义转录 MIME、版本、严格 UTF-8/行列/长度限制和确定性 parser；金额仅接受带人民币标记的唯一候选，方向仅接受无冲突的高信号词，对手方只从显式标签提取。
2. 已完成：在 application 新增 PHOTO_OCR capture service，复用现有租约暂存、私有 evidence store、RawEvent 和 parser registry；输入转录临时字节在所有路径擦除。
3. 已完成代码与静态包接入：`ocr:paddle` 保存经 Apache-2.0 审查的 PaddleOCR Android 源码与静态模型；运行时显式关闭遥测，固定两条 CPU 线程、batch 1、零排队并在成功、失败和取消时释放会话、Bitmap 与临时字节。arm64-v8a/x86_64 目标 ABI 与未签名分包体积已构建测量，固定签名身份、对齐及 arm64 安装冷启动已验证，签名 Release 资源仍待验。
4. 部分完成：构建任务固定模型 SHA-256 并检查中/英/日字典；港版 S24 Ultra 已运行三语合成支付文本 1/1、合成多金额空间链 1/1、11 张本机私有真实繁中/英文过程页 11/11 与简中四状态页 1/1。真实简中微信页面、系统截图/选图 UI 与 Release 资源数据仍未完成。
5. 已完成但需复验：进程内单并发 command controller、`TileService` 和专用 AccessibilityService；断连、忙碌、API 不支持、系统拒绝、OCR/入库失败均更新磁贴/Toast 安全状态。每个活动 command 先有 90 秒可取消阶段；本地提交自身有 15 秒协作式截止，提交/既成结果不可取消时最多增加一次 15 秒收尾宽限，随后以“结果未确认”释放并用 opaque request identity 丢弃迟到回调。设置相关入口深链到 Bill 教程。
6. 已完成：Photo Picker 最多返回 5 张，ViewModel 以单飞批次串行逐张调用同一零排队 OCR engine；设置页显示处理进度，第二批在活动批次结束前被拒绝，结束时只汇总一次并打开首个可复核项。每张仍受 16 MiB 文件、32 MP 来源和 2.56 MP OCR 输入上限，不取得整库权限。
7. 已完成静态构建、release APK 依赖/Manifest/组件审计、真实页面规则回放和指定 `code-review`；待真实简中页面以及三台目标真机的 Release 资源、权限与失败矩阵，并把设备实测回写唯一事实来源。

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

- 静态调用路径审计确认：没有磁贴点击或 Photo Picker 选择时，不存在截图、OCR、前台服务、网络或主动页面读取入口；Release APK 也没有因 OCR 依赖引入遥测传输、Job、Alarm 或模型下载组件。尚未执行 24 小时 soak。
- 每个点击最多对应一个 capture command、一个 OCR 转录 RawEvent 和一个来源建议；重复点击、进程重建和入库重试不会自动形成多笔正式账。
- 单次截图 command 在 90 秒内没有平台/原生回调且尚未进入提交时安全取消并释放；若本地提交已开始或结果已形成，则由 15 秒 local-commit deadline 与一次 15 秒 controller 收尾宽限保证资源和单飞门有界。状态仍不明时显示“结果未确认”；任何更迟的回调都不能完成下一条 command。
- Photo Picker 每批只处理前 5 张且最大并发为 1；批次只产生一次汇总事件，第二批不能与活动批次交错。
- 服务配置证明 `canRetrieveWindowContent=false`、`canPerformGestures=false`；代码不访问 event text、node、package、window title 或执行 global action。
- 图片像素、尺寸、转录字节、行数、行长、金额候选数和并发均有上限；每个失败路径释放 HardwareBuffer/Bitmap 并擦除临时字节。
- 当前未签名 Release APK merged manifest 没有 `INTERNET`、媒体库读取、悬浮窗、前台服务或 MediaProjection 权限；OCR 模型无需首次网络下载，且依赖树/组件审计没有遥测传输或其后台调度入口。AAR 与 APK 静态检查，以及一台设备的合成三语、合成多金额空间链、11 张本机私有真实繁中/英文过程页和简中四状态页 instrumentation 已通过；固定签名 arm64 包已安装冷启动，真实简中微信页面、系统截图/选图 UI 和完整真机 Release 资源矩阵仍待验。
- 合成付款、收款、退款/转账冲突、多金额、空 OCR、畸形 UTF-8、超限、重复 command 和安全失败都有回归；任何结果最多是待复核。
- API 30 以下和安全窗口显示明确回退；拒绝高风险权限后，手工录入、通知基础和分享 PNG 仍可用。

## 幂等、回滚与恢复

command ID 贯穿 capture、暂存与 RawEvent receipt；同一进程只允许一个活动任务，既有仓储 command receipt 处理重试。进程在截图后、OCR 后或暂存中死亡时，Bitmap/HardwareBuffer 由系统/进程回收，租约恢复沿用 v5 有界清理；未出现 RawEvent 不显示成功。

关闭或撤销无障碍权限立即停止新截图，不删除已有本地证据或草稿。若 bundled OCR 构建、资源或政策门失败，移除磁贴/service 注册并保留系统分享 PNG 回退；不得自动改用 MediaProjection、网络 OCR 或常驻节点读取。

## 结果与复盘

交互、来源链、有界多选、本地引擎替换、空间转录 v2、v1 重放、全量静态构建、未签名 Release 审计与固定签名双 ABI 身份/对齐已实现；设置深链、90 秒截图租约/迟到回调隔离、handle 注册竞态、15 秒本地提交截止/提交后未知态、Photo Picker 单飞批次与一次汇总，以及可滚动 safe-area 启动声明和统一中文披露均已纳入验证。港版 S24 Ultra 的合成三语模型与合成多金额空间链 instrumentation 已通过，但真实简中/日文页面、系统截图/选图 UI、签名包安装/资源、ELF 页兼容和目标真机证据缺失仍使其不具备发布资格。设备轮继续记录三台真机单次耗时/峰值/空闲释放、电量、失败矩阵和仍未达到 provider 支持的范围。
