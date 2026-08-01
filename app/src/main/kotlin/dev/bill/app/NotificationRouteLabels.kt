package dev.bill.app

internal const val WECHAT_PAYMENT_NOTIFICATION_ROUTE_ID =
    "wechat.notification.paid-en-cny.v1"

internal fun notificationRouteLabelResourceId(routeId: String): Int? = when (routeId) {
    "alipay.notification.outbound-cny.v1" -> R.string.notification_type_alipay_expense
    "alipay.notification.balance-receipt-cny.v1" ->
        R.string.notification_type_alipay_balance_receipt
    "alipay.notification.fund-buy-confirmed-cny.v1" ->
        R.string.notification_type_alipay_fund_purchase
    WECHAT_PAYMENT_NOTIFICATION_ROUTE_ID -> R.string.notification_type_wechat_payment
    "bank.cmb.notification.quick-refund-cny.v1" -> R.string.notification_type_cmb_refund
    else -> null
}
