# 仓库生成事实

- 状态：自动生成
- 所有者：`scripts/generate-repository-facts.ps1`
- 最后核验：2026-07-31
- 事实来源：`settings.gradle.kts`、模块 `build.gradle.kts`、Room schema、源 Manifest 与测试源码
- 生成命令：`cmd.exe /d /s /c powershell -NoProfile -ExecutionPolicy Bypass -File scripts\generate-repository-facts.ps1`

> 自动生成，请勿手工编辑。测试数字只表示源码中发现的文件和 `@Test` 注解，不代表测试已经执行或通过。

## 输入摘要

| 项目 | 当前值 |
|---|---:|
| Gradle 模块 | 23 |
| 源 Manifest | 9 |
| 测试源码文件 | 64 |
| 生成输入文件 | 104 |
| 输入 SHA-256 | `05a845b319b169256e8e439cbd7abda3873b4adb2d8885b517e81c46f0599b30` |

输入摘要按 UTF-8/LF 规范化后包含生成器及共享解析库的 SHA-256；生成逻辑、源码事实或输入文件任一变化，都要求重新生成。

## Gradle 模块依赖

| 模块 | 构建文件 | 直接项目依赖 |
|---|---|---|
| `:app` | `app/build.gradle.kts` | `:application`<br>`:core:designsystem`<br>`:data:local`<br>`:feature:accounts`<br>`:feature:ledger`<br>`:feature:overview`<br>`:feature:review`<br>`:ocr:paddle`<br>`:source:alipay`<br>`:source:bank:cmb`<br>`:source:generic-delimited-statement`<br>`:source:generic-notification`<br>`:source:generic-photo-ocr`<br>`:source:generic-receipt-image`<br>`:source:generic-share-text`<br>`:source:pipeline`<br>`:source:wechat` |
| `:application` | `application/build.gradle.kts` | `:core:domain`<br>`:core:ledger`<br>`:core:model`<br>`:source:alipay`<br>`:source:contract`<br>`:source:generic-delimited-statement`<br>`:source:generic-notification`<br>`:source:generic-photo-ocr`<br>`:source:generic-receipt-image`<br>`:source:generic-share-text`<br>`:source:pipeline`<br>`:source:review-contract` |
| `:core:designsystem` | `core/designsystem/build.gradle.kts` | `:core:model` |
| `:core:domain` | `core/domain/build.gradle.kts` | `:core:model` |
| `:core:ledger` | `core/ledger/build.gradle.kts` | `:core:domain`<br>`:core:model` |
| `:core:model` | `core/model/build.gradle.kts` | — |
| `:data:local` | `data/local/build.gradle.kts` | `:core:domain`<br>`:core:ledger`<br>`:core:model`<br>`:source:contract`<br>`:source:pipeline`<br>`:source:review-contract` |
| `:feature:accounts` | `feature/accounts/build.gradle.kts` | `:application`<br>`:core:designsystem` |
| `:feature:ledger` | `feature/ledger/build.gradle.kts` | `:application`<br>`:core:designsystem` |
| `:feature:overview` | `feature/overview/build.gradle.kts` | `:application`<br>`:core:designsystem`<br>`:core:model` |
| `:feature:review` | `feature/review/build.gradle.kts` | `:application`<br>`:core:designsystem` |
| `:ocr:paddle` | `ocr/paddle/build.gradle.kts` | — |
| `:source:alipay` | `source/alipay/build.gradle.kts` | `:source:contract`<br>`:source:generic-notification` |
| `:source:bank:cmb` | `source/bank/cmb/build.gradle.kts` | `:source:contract`<br>`:source:generic-notification` |
| `:source:contract` | `source/contract/build.gradle.kts` | `:core:model` |
| `:source:generic-delimited-statement` | `source/generic-delimited-statement/build.gradle.kts` | `:source:contract` |
| `:source:generic-notification` | `source/generic-notification/build.gradle.kts` | `:source:contract` |
| `:source:generic-photo-ocr` | `source/generic-photo-ocr/build.gradle.kts` | `:source:contract` |
| `:source:generic-receipt-image` | `source/generic-receipt-image/build.gradle.kts` | `:source:contract` |
| `:source:generic-share-text` | `source/generic-share-text/build.gradle.kts` | `:source:contract` |
| `:source:pipeline` | `source/pipeline/build.gradle.kts` | `:source:contract` |
| `:source:review-contract` | `source/review-contract/build.gradle.kts` | `:core:domain`<br>`:source:contract` |
| `:source:wechat` | `source/wechat/build.gradle.kts` | `:source:contract`<br>`:source:generic-notification` |

## Room schema

| 数据库 | 已提交版本 | 最新版本 |
|---|---|---:|
| `dev.bill.data.local.BillDatabase` | `1`, `2`, `3`, `4`, `5`, `6`, `7` | 7 |

## 源 Manifest 权限声明

| Manifest | 节点 | 权限 | tools 操作 |
|---|---|---|---|
| `app/src/main/AndroidManifest.xml` | `uses-permission` | `android.permission.ACCESS_NETWORK_STATE` | `remove` |
| `app/src/main/AndroidManifest.xml` | `uses-permission` | `android.permission.INTERNET` | `remove` |

## 源 Manifest 组件声明

| Manifest | 类型 | 名称 | exported | permission | tools 操作 |
|---|---|---|---|---|---|
| `app/src/debug/AndroidManifest.xml` | `activity` | `.notification.NotificationTemplateSamplerActivity` | `true` | — | — |
| `app/src/main/AndroidManifest.xml` | `activity` | `.MainActivity` | `true` | — | — |
| `app/src/main/AndroidManifest.xml` | `activity` | `.quickcapture.QuickCaptureRelayActivity` | `false` | — | — |
| `app/src/main/AndroidManifest.xml` | `activity` | `.quickcapture.QuickCaptureSetupRelayActivity` | `true` | — | — |
| `app/src/main/AndroidManifest.xml` | `service` | `.notification.BillNotificationListenerService` | `false` | `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` | — |
| `app/src/main/AndroidManifest.xml` | `service` | `.quickcapture.BillQuickCaptureTileService` | `true` | `android.permission.BIND_QUICK_SETTINGS_TILE` | — |
| `app/src/main/AndroidManifest.xml` | `service` | `.quickcapture.BillScreenshotAccessibilityService` | `true` | `android.permission.BIND_ACCESSIBILITY_SERVICE` | — |

## 测试源码清单

| 模块 | 测试集 | 源码文件 | `@Test` 注解 |
|---|---|---:|---:|
| `:app` | Android | 4 | 15 |
| `:app` | JVM | 13 | 72 |
| `:application` | JVM | 5 | 75 |
| `:core:ledger` | JVM | 2 | 23 |
| `:core:model` | JVM | 2 | 4 |
| `:data:local` | Android | 9 | 47 |
| `:data:local` | JVM | 5 | 16 |
| `:feature:overview` | JVM | 1 | 4 |
| `:feature:review` | JVM | 1 | 2 |
| `:ocr:paddle` | Android | 1 | 1 |
| `:source:alipay` | JVM | 1 | 4 |
| `:source:bank:cmb` | JVM | 1 | 4 |
| `:source:contract` | JVM | 5 | 22 |
| `:source:generic-delimited-statement` | JVM | 3 | 18 |
| `:source:generic-notification` | JVM | 4 | 22 |
| `:source:generic-photo-ocr` | JVM | 1 | 19 |
| `:source:generic-receipt-image` | JVM | 1 | 4 |
| `:source:generic-share-text` | JVM | 2 | 9 |
| `:source:pipeline` | JVM | 2 | 22 |
| `:source:wechat` | JVM | 1 | 4 |
| 合计 | — | 64 | 387 |

计数口径：仅扫描各模块的 `src/test` 与 `src/androidTest` 下 `.kt`/`.java` 文件，并统计 `@Test` 或完全限定的 JUnit `@Test` 注解；参数化测试等其他注解不计入。
