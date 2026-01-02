package com.yomidroid.service

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.yomidroid.R
import com.yomidroid.config.ColorConfigManager

/**
 * Quick Settings tile for toggling Yomidroid overlay visibility.
 * Users add this tile to their Quick Settings panel to quickly enable/disable the FAB.
 */
class YomidroidTileService : TileService() {

    private val configManager by lazy { ColorConfigManager(this) }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        // Toggle the FAB enabled state
        val currentState = configManager.isFabEnabled()
        val newState = !currentState
        configManager.setFabEnabled(newState)

        // Update the tile appearance
        updateTileState()

        // Notify the accessibility service to update FAB visibility
        YomidroidAccessibilityService.instance?.updateFabVisibility()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isEnabled = configManager.isFabEnabled()

        tile.state = if (isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Yomidroid"
        tile.subtitle = if (isEnabled) "On" else "Off"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.updateTile()
    }
}
