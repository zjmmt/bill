package dev.bill.app.quickcapture

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import dev.bill.app.R

fun openQuickCaptureAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    if (context !is android.app.Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

fun requestQuickCaptureTile(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(
            context,
            R.string.quick_capture_tile_add_manually,
            Toast.LENGTH_LONG,
        ).show()
        return
    }
    val statusBarManager = context.getSystemService(StatusBarManager::class.java)
    if (statusBarManager == null) {
        Toast.makeText(
            context,
            R.string.quick_capture_tile_add_manually,
            Toast.LENGTH_LONG,
        ).show()
        return
    }
    statusBarManager.requestAddTileService(
        ComponentName(context, BillQuickCaptureTileService::class.java),
        context.getString(R.string.quick_capture_tile_label),
        Icon.createWithResource(context, R.drawable.ic_quick_capture),
        context.mainExecutor,
    ) {
        Toast.makeText(
            context,
            R.string.quick_capture_tile_request_finished,
            Toast.LENGTH_LONG,
        ).show()
    }
}
