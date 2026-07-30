package dev.bill.app.quickcapture

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import dev.bill.app.MainActivity
import dev.bill.app.R
import java.util.UUID

/** Quick Settings entry point. It never receives pixels and never performs OCR itself. */
class BillQuickCaptureTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            showToast(R.string.quick_capture_unlock_first)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showToast(R.string.quick_capture_unsupported)
            return
        }
        if (
            BillQuickCaptureRuntime.controller.connectionState.value !=
            QuickCaptureConnectionState.CONNECTED
        ) {
            showToast(R.string.quick_capture_enable_accessibility)
            openBillSetup()
            return
        }
        launchCaptureRelay()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val connected =
            BillQuickCaptureRuntime.controller.connectionState.value ==
                QuickCaptureConnectionState.CONNECTED
        tile.state = when {
            !supported -> Tile.STATE_UNAVAILABLE
            connected -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.quick_capture_tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (connected) {
                    R.string.quick_capture_tile_ready
                } else {
                    R.string.quick_capture_tile_setup
                },
            )
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchCaptureRelay() {
        val intent = Intent(this, QuickCaptureRelayActivity::class.java).apply {
            action = QuickCaptureRelayActivity.ACTION_CAPTURE
            putExtra(
                QuickCaptureRelayActivity.EXTRA_COMMAND_ID,
                UUID.randomUUID().toString(),
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openBillSetup() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_QUICK_CAPTURE_SETUP
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    1,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private fun showToast(messageRes: Int) {
        Toast.makeText(applicationContext, messageRes, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_OPEN_QUICK_CAPTURE_SETUP =
            "dev.bill.app.action.OPEN_QUICK_CAPTURE_SETUP"

        fun requestTileRefresh(context: android.content.Context) {
            requestListeningState(
                context,
                ComponentName(context, BillQuickCaptureTileService::class.java),
            )
        }
    }
}
