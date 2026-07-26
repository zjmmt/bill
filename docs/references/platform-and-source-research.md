# 平台与数据入口研究

- 状态：参考资料；发布前需重新核验
- 所有者：项目维护者
- 最后核验：2026-07-19
- 事实来源：Android、Google Play、支付平台与监管官方页面

## 稳健入口排序

1. 用户主动取得的支付宝/微信个人账单和银行对账单。
2. 用户授权的 Android 通知监听（尽力即时，不保证历史/完整）。
3. 用户通过分享面板、文件选择器或 Photo Picker 明确选择的内容。
4. 手工录入、期初余额和通用列映射。
5. 受限权限、OCR、邮件整箱或合作方 API 只作为独立实验，不进入默认主线。

## Android 边界

- Notification Listener 需用户显式在设置中授权，且不是历史数据接口：[官方 API](https://developer.android.com/reference/android/service/notification/NotificationListenerService.html)。
- Android 15 对识别到的 OTP 进行遮罩，通知解析必须容忍字段删减：[行为变化](https://developer.android.com/about/versions/15/behavior-changes-all?hl=zh-CN)。
- SAF 只授予用户选择的文件，不需要广泛存储权限：[文件选择器](https://developer.android.com/training/data-storage/shared/documents-files)。
- Sharesheet 可接收用户主动分享的文本、图片和附件：[接收分享](https://developer.android.com/training/sharing/receive)。
- Photo Picker 只访问用户选中的媒体：[Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)。
- MediaProjection 每次会话需要同意，且必须尊重金融 App 的 `FLAG_SECURE`：[MediaProjection](https://developer.android.com/media/grow/media-projection)、[`FLAG_SECURE`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)。
- Android Keystore 与备份策略需共同设计：[Keystore](https://developer.android.com/privacy-and-security/keystore)、[备份安全](https://developer.android.com/privacy-and-security/risks/backup-best-practices)。

## 支付平台与银行

- 财付通提供微信支付个人交易记录及下载账单用户路径：[2026 公告](https://posts.tenpay.com/posts/021de3926a292d910000a7de5ea7e100.html)。
- 支付宝提供个人收支明细证明入口，具体文件细节仍需真机验证：[支付宝帮助](https://help.alipay.com/lab/help_detail.htm?help_id=553265)。
- 微信与支付宝公开账单 API 面向商户，需要商户号、签名/证书，不是读取任意个人账户历史的通道：[微信商户账单](https://pay.wechatpay.cn/doc/v3/merchant/4013071218)、[支付宝商户说明](https://help.alipay.com/enterprise/knowledgeDetail.htm?knowledgeId=201603401890)。
- 中国大陆没有可在本项目中预设为统一公共消费者接口的跨行交易 API；银行能力按机构和正式合作验证。工行个人电子账单可作为“逐行适配”的一个例子：[工行说明](https://www.icbc.com.cn/page/721857300590788616.html)。

## 受限能力

- Play 对 SMS/Call Log 权限设有严格核心功能与审核要求；即便资金管理可能属于例外，也不代表自动获批：[Play SMS 政策](https://support.google.com/googleplay/android-developer/answer/10208820?hl=en)。
- Accessibility 自动操作金融 App UI 脆弱且风险高，不作为产品路线。
- Gmail 读取属于受限 OAuth 范围，公开应用可能需要验证和额外安全评估，不适合 MVP 整箱同步：[Gmail scopes](https://developers.google.com/workspace/gmail/api/auth/scopes)。
- 本地 OCR 可作为用户选图后的辅助，但结果必须确认；若采用 ML Kit，发布前复核模型下载与隐私边界：[ML Kit OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)。

## 合规底线

金融账户信息属于敏感个人信息，处理必须目的特定、充分必要并采取严格保护：[《个人信息保护法》](https://www.cac.gov.cn/2021-08/20/c_1631050028355286.htm)。2026 年专项治理也关注超范围读取短信、存储和应用列表等行为：[网信办公告](https://www.cac.gov.cn/2026-04/02/c_1776867645836849.htm)。

这些资料用于设计边界，不构成法律意见。面向商店公开发布前需要按当时政策、目标市场和实际数据流重新审查。
