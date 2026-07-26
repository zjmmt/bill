# Bill - 本地优先 Android 个人财务聚合器

- 状态：部分实现；受限 CNY/USD 手工账本、通用显式证据链、通知安全底座与单次截图/OCR 原型已有代码，真实 provider 适配和 OCR 隐私发布门未完成
- 所有者：项目维护者
- 最后核验：2026-07-26
- 事实来源：当前 Android/Room 工程、[质量评分](docs/QUALITY_SCORE.md)、[ADR-0005](docs/decisions/0005-manual-ledger-first-slice.md)、[ADR-0006](docs/decisions/0006-provider-neutral-shared-text-evidence-spine.md)、[ADR-0007](docs/decisions/0007-source-evidence-lifecycle-and-bounded-storage.md)、[ADR-0008](docs/decisions/0008-leased-source-evidence-staging-and-orphan-recovery.md)、[ADR-0009](docs/decisions/0009-wallet-balance-not-inferred-from-bank.md)、[ADR-0010](docs/decisions/0010-notification-first-capture-and-single-receipt-fallback.md)、[ADR-0011](docs/decisions/0011-local-resource-budget-first-capture.md)、[ADR-0012](docs/decisions/0012-user-triggered-quick-tile-screenshot-and-bundled-ocr.md)、[ADR-0013](docs/decisions/0013-bank-card-only-usd-without-fx.md)

Bill 是一款 Android 原生、离线可用、无自有服务端的个人财务聚合器。它把支付宝、微信支付和银行视为同等重要的数据来源，将通知、用户导入文件和手工录入统一为可追溯的本地总账。

当前仓库已经从“文档先行”进入多条纵向实现：受限 CNY/USD 手工账户/期初余额/收入支出账本、Android Sharesheet 与 SAF 的通用显式文本证据、系统 Sharesheet 单次 PNG 收据证据、默认空目录的通知安全边界，以及用户显式触发的截图/Photo Picker OCR 原型。账本、文本和安全边界已有自动化；CNY 路径还有 MuMu API 32 证据，港版 S24 Ultra 另有一次合成应用级冒烟。OCR 引擎的“无遥测”发布审计、图片/通知 listener 完整真机验收，以及真实支付宝、微信支付和银行适配器仍未完成。

## 不可退让的产品边界

- 本地优先：财务原始数据默认只进入应用私有存储，不上传到项目方服务器。
- 当前应用 Manifest 显式移除 `INTERNET` 与 `ACCESS_NETWORK_STATE` 权限；任何未来依赖都必须通过最终 APK 权限、组件和断网运行审计后，才能宣称“不走网络”。
- 不以持续屏幕扫描、录屏、轮询 OCR、Root 或云端原文处理换取自动化；通知、只读结果页与单次截图必须独立授权、独立预算。
- 三类一等数据源：支付宝、微信支付、银行必须都能进入相同的 `RawEvent -> Draft -> Transaction -> Entry` 流程。
- 覆盖同级，不承诺自动化同级：MVP 要为三类来源提供真实可用的采集路径，但具体路径可分别采用通知、文件导入或手工补录。
- 先确认再入账：识别结果必须可解释、可编辑、可撤销；低置信度结果不得静默写入正式总账。
- 渠道不等于资金账户：支付宝或微信可能只是支付通道，实际资金来自银行卡或信用卡；但支付宝余额、微信零钱是独立资产账户，其内部收付不能由银行卡流水反推。
- 资金移动不等于收支：转账、充值、信用卡还款和投资申赎必须使用独立语义。

## MVP 目标（不等于当前能力）

1. 采集经验证的支付宝、微信支付和受支持银行 App 交易证据；通知不承诺覆盖没有通知的余额动作。
2. 导入当前设备/账户上实际可取得的账单文件；格式按来源适配，不假设所有银行一致或假设每台设备都有个人导出入口。
3. 支持手工补录、期初余额和账户余额校验。
4. 让所有来源先生成不可变原始事件和待确认草稿。
5. 在具有真实来源证据后识别同一消费的“支付渠道通知 + 银行扣款”，避免双计支出。
6. 管理资产账户、负债、投资持仓和账户间转移。
7. 提供本地加密备份、恢复和完整导出。

完整范围见 [MVP 产品规格](docs/product-specs/mvp.md) 与 [数据源覆盖矩阵](docs/product-specs/source-coverage.md)。

## 文档入口

- 智能体与贡献者地图：[AGENTS.md](AGENTS.md)
- 系统边界与依赖方向：[ARCHITECTURE.md](ARCHITECTURE.md)
- 文档总索引：[docs/index.md](docs/index.md)
- 产品判断原则：[docs/PRODUCT_SENSE.md](docs/PRODUCT_SENSE.md)
- 安全与隐私：[docs/SECURITY.md](docs/SECURITY.md)
- 当前执行计划：[docs/exec-plans/active/0001-project-foundation.md](docs/exec-plans/active/0001-project-foundation.md)

## 当前状态

- 产品与架构文档：已建立，并开始随真实实现校正。
- Android 工程：已初始化 Kotlin/Compose 多模块工程；手机主路径为竖屏单列界面。
- 本地账本首片：现金、支付宝余额和微信零钱等电子钱包只能是 CNY；银行卡和信用卡可以是 CNY 或 USD。期初余额、手工收入/支出先进入同币种 Draft，再确认成逐币种平衡 Entries；总览分开显示 CNY/USD，不提供汇率、换汇或虚假的总净值。撤销采用 `VOIDED` 并恢复原 Draft。
- 通用分享文本切片：显式分享的 `text/plain` 经 64 KiB 与严格 UTF-8 检查，保存为应用私有、不可变且可校验哈希的 `GENERIC/SHARE_TEXT` 证据；解析器不猜金额、方向、账户或 provider，用户补全后才创建 Draft。相同证据只提示“可能重复”，不会自动合并；用户可忽略待办，审计证据仍保留。
- 用户选择文本文件切片：`OpenDocument` 仅在本次选择中读取 `text/plain`、CSV 或 TSV 的 64 KiB 内文本，形成 `GENERIC/STATEMENT_IMPORT` 待复核证据；不持久化 URI/文件名，不解析 CSV 行或识别 provider。它是通用证据入口，不是微信、支付宝或银行账单导入支持。
- 单次 PNG 收据截图切片：用户用 Android 正常截图后，经系统 Sharesheet 明确分享一张 `image/png` 给 Bill。应用只在当次 `content://` 授权内有界复制最多 4 MiB，校验 PNG 签名、头信息、分块与 CRC 后保存为 `GENERIC/SHARE_FILE` 私有证据；不保留 URI/文件名、不申请相册权限、不解码或预览像素、不运行 OCR，也不猜金额、方向、商户、账户或 provider。它直接打开手工复核表单，用户应以原始截图为准填写事实；它不是支付宝、微信或银行适配器。
- 截图/Photo Picker OCR 原型：下拉快捷设置的“截图记账”与系统 Photo Picker 均只在用户明确点击后进行单次处理，不读取整本相册、不轮询屏幕、不执行手势或自动点击，识别结果只生成可编辑 Draft。现有 OCR 依赖尚未通过“无遥测/零网络组件”发布审计，且尚未满足中文/英文/日文的最终模型要求；因此它是开发中原型，不是已支持的支付来源，也不能作为“纯本地 OCR”发布声明。
- 来源证据生命周期：设置页显示占用、7/30/90 天或永久保留、keyset 分页历史和逐项永久清除。文件删除采用 `AVAILABLE -> CLEAR_PENDING -> CLEARED` 两阶段状态；16 MiB/512 份预算同时统计已提交与暂存载荷，只自动清理已无待复核项的最旧载荷，结构化账本和审计保留。
- 暂存恢复：文件写入前先登记 5 分钟 Room 租约，RawEvent 事务原子消费登记；启动维护接管到期租约，并有界回收旧版无登记孤儿。活动/待清除证据、陈旧租约和文件碰撞均有防误删测试。
- 受控通知路由基础：`NotificationListenerService` 先按包名、具体 Android 通知渠道和类别精确门禁；静态 route catalog、默认关闭的本地 route 开关、parser 注册和 RawEvent 来源身份共用同一条 route。生产目录为空时连通知正文都不读取；未来 route 即使进入目录，也须用户显式开启，且 ingress 会再次检查开关，避免已经排队的内容在关闭后落库。候选只会进入容量 16 的单消费者队列；Room v6 用安装私有 HMAC 摘要和租约复用同一 command，避免通知更新/进程恢复重复创建 RawEvent。合成信封最多 8 KiB，只进入来源待复核链，只显示安全标签，不猜金额、账户或 provider，也不自动入账。启动页已显示“纯本地 / 不走网络”大字声明与三轨权限教程；设置页会显示不含正文的空目录、队列跳过和失败健康状态。这不是支付宝、微信或银行支持声明。
- Room 与界面：schema v6 保存账本、RawEvent、ParseAttempt、来源建议、Draft 证据链接、载荷生命周期/策略、暂存租约、通知观察摘要、审计与幂等回执；跨表不一致失败关闭。总览、账户、草稿、流水、分享复核和证据设置均由本地状态驱动。
- 验证：此前组合 `test lint assembleDebug :data:local:assembleDebugAndroidTest` 成功，Lint 通过；PNG、证据生命周期、通知空目录与分享回归都有自动化。受限 CNY/USD 片又通过 `:core:ledger:test :application:test :data:local:test :feature:review:compileDebugKotlin :app:testDebugUnitTest :data:local:assembleDebugAndroidTest`，覆盖 USD 账户边界、逐币种总览、来源币种限制和混币篡改失败关闭；最后一项只编译 Android 测试 APK，不等于设备执行。MuMu API 32 上已有 32 个 v1→v5 Room/证据 instrumentation 测试通过；港版 S24 Ultra 只完成合成应用级冒烟。完整结果与未测项见 [Android 设备兼容与真机验收](docs/design-docs/android-device-compatibility.md)。
- 外部来源：支付宝、微信支付和银行真实通知/文件适配器仍未实现，也没有可用于发布声明的脱敏样本；三类来源当前状态统一为 `FALLBACK_REQUIRED`。通用文本入口只证明 Bill 能处理用户选择的不可信文本，不证明来源 App，也不能声称三类来源“已支持”。银行卡只覆盖银行卡/信用卡资金腿，不能替代微信零钱或支付宝余额内部流水。
- 发布缺口：尚无大量/恶意 Intent 压力、自动化 Compose 与真实系统强杀切点测试、完整跨来源对账，或完整港版/国行 Samsung 与国行小米真机证据。

文档中的“已设计”不等于“已实现”。真实支持状态只以代码、脱敏样本回放和验收测试共同证明。

## 本地构建

所有命令从仓库根目录经 CMD 执行：

```bat
cmd.exe /d /s /c "scripts\android.cmd test lint assembleDebug --console=plain"
cmd.exe /d /s /c "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-docs.ps1"
```

真实支付页面、真实通知或真机 ADB 验收需要数据所有者明确同意后单独进行；仓库不收录真实支付截图、通知正文、账号、金额或设备序列号。
