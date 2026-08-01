# ExecPlan 0012：可复现的本地签名发行

- 状态：进行中
- 所有者：项目维护者
- 最后核验：2026-08-01
- 事实来源：当前 Android 构建、README 发布承诺、SECURITY、RELIABILITY 与项目负责人当前发布范围

## 目的与用户可见结果

在不提交密钥、密码或成品 APK 的前提下，把当前只能产生 `release-unsigned` 的工程接成一条显式、可复现且失败关闭的本地签名发行路径。维护者必须明确提供递增版本、外部 keystore 和四项签名参数；缺少或部分提供时命令在发行前失败。普通 `assembleRelease` 继续保留为未签名静态审计入口。

## 范围与非目标

包含：外部版本输入、全有或全无的环境变量签名配置、CMD 发行入口、签名产物验证、秘密/产物忽略规则、离线失败与成功路径测试、发布文档。

不包含：替用户生成或托管生产密钥、把密码写入 `local.properties`/Gradle 属性、上传 GitHub Release 或应用商店、选择公开发行渠道、绕过当前通知/OCR 真机发布门。

## 上下文与仓库导航

- `app/build.gradle.kts` 当前固定 `versionCode = 1`、`versionName = "0.1.0"`，没有 release signing config。
- `scripts/android.cmd` 是仓库统一 JDK/Gradle 入口；新增命令仍从 CMD 调用它。
- 既有 Release 双 ABI APK 只用于本地静态审计，文档明确标记为未签名。
- 签名密钥是长期身份；创建、备份和最终渠道选择需要项目负责人单独决定，本计划不能自行代办。

## 进度

- [x] 2026-08-01 - 审计当前版本、签名、脚本、忽略规则和发布文档；确认只有固定版本与未签名 Release 构建。
- [ ] 2026-08-01 - 接入可覆盖版本和全有或全无的外部签名环境变量，保留默认未签名审计构建。
- [ ] 2026-08-01 - 增加不回显秘密的 `scripts/release.cmd`，要求显式版本与外部 keystore，并在构建后验证 APK 已签名。
- [ ] 2026-08-01 - 覆盖缺参、部分签名参数、无效版本、缺失 keystore 和成功的临时测试密钥路径；不使用或生成生产密钥。
- [ ] 2026-08-01 - 运行文档、差异、Release 构建和指定 `code-review`，分步提交并推送。
- [ ] 待项目负责人决定 - 创建/指定生产 keystore、离线备份、确定版本与发行渠道，再产出真实签名包。

## 意外发现

- 当前双 ABI `assembleRelease` 成功并不表示可安装发行：输出仍是 `release-unsigned`，且版本固定为首版值。

## 决策日志

- 2026-08-01 - 默认构建保持未签名，显式发行命令才要求签名；避免破坏既有离线静态审计与 CI。
- 2026-08-01 - 密钥路径和密码只从当前进程环境读取；不把秘密复制到仓库、Gradle 属性、日志或命令回显。
- 2026-08-01 - 生产密钥生成和远程发布均保留为人工授权门；签名基础设施不能被写成“已经发布”。

## 实施步骤

1. 在 App Gradle 配置中从环境变量读取版本；缺省值保持 `1/0.1.0`，显式值必须通过正整数、非空与长度校验。
2. 只有四项签名变量全部存在且 keystore 文件可读时才创建 release signing config；部分提供或显式要求签名但缺参时失败，并且错误只列变量名。
3. 新增 CMD 入口检查必填变量、调用 Android 构建，再使用当前 Android SDK 的 `apksigner` 验证两个 ABI APK；不输出密码或证书私钥材料。
4. 用仓库外临时测试 keystore 验证成功路径，随后删除临时文件；这不创建或替代生产密钥。
5. 更新 README、SECURITY、RELIABILITY 与发布计划结果；签名包、keystore 和私有配置保持 Git 忽略。

## 具体命令

所有命令在仓库根目录经 CMD 执行：

```bat
scripts\android.cmd :app:assembleRelease --console=plain
scripts\release.cmd
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-docs.ps1
git diff --check
```

`scripts\release.cmd` 运行前由维护者在当前 CMD 会话提供 `BILL_VERSION_CODE`、`BILL_VERSION_NAME`、`BILL_RELEASE_STORE_FILE`、`BILL_RELEASE_STORE_PASSWORD`、`BILL_RELEASE_KEY_ALIAS` 和 `BILL_RELEASE_KEY_PASSWORD`。脚本自行设置只对本次进程有效的签名必需门。

## 验证与验收

- 不设置签名变量时，普通 `assembleRelease` 仍产生明确的未签名审计包。
- 发行命令缺任一变量、版本非法或 keystore 不存在时，在构建前失败且不回显秘密。
- 只提供部分签名变量的直接 Gradle 调用失败关闭，不能静默回落为未签名包。
- 临时测试密钥路径能生成两个签名 ABI APK，并由 `apksigner verify` 成功验证；Git 状态不出现密钥、密码或 APK。
- 生产源码、Manifest、权限、数据模型和账务语义不随发行接线改变。

## 幂等、回滚与恢复

脚本只读取外部环境并写标准 Gradle `build/` 产物，可重复运行。中断后重新执行即可；没有数据库或用户数据迁移。回滚时移除显式签名入口即可恢复原未签名构建，外部 keystore 不受仓库操作影响。

## 结果与复盘

进行中。当前仅完成缺口审计与方案边界；未创建生产密钥、未生成正式签名包、未上传或发布任何产物。
