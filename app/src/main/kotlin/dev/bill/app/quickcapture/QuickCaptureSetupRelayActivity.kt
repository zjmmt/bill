package dev.bill.app.quickcapture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.bill.app.MainActivity

/**
 * Lets Android's accessibility-service details page open Bill's capture tutorial directly.
 *
 * The activity accepts no input and exposes no data. It only forwards to the existing singleTop
 * MainActivity with the same navigation action used by the Quick Settings tile.
 */
class QuickCaptureSetupRelayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = BillQuickCaptureTileService.ACTION_OPEN_QUICK_CAPTURE_SETUP
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            },
        )
        finish()
    }
}
