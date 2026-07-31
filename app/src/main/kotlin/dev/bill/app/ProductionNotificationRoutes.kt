package dev.bill.app

import dev.bill.source.alipay.AlipayNotificationRoutes
import dev.bill.source.bank.cmb.CmbNotificationRoutes
import dev.bill.source.genericnotification.NotificationRouteCatalog
import dev.bill.source.wechat.WeChatNotificationRoutes

/**
 * Locally bundled, sample-backed routes. SharedPreferences enablement remains empty by default, so
 * installing or upgrading Bill does not read any matching notification body until the user turns
 * on an individual safe-labelled route.
 */
internal object ProductionNotificationRoutes {
    val catalog = NotificationRouteCatalog(
        AlipayNotificationRoutes.routes +
            WeChatNotificationRoutes.routes +
            CmbNotificationRoutes.routes,
    )
}
