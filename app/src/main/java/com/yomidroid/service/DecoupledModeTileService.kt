package com.yomidroid.service

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.yomidroid.R
import com.yomidroid.config.ColorConfigManager

/**
 * Quick Settings tile that toggles decoupled mode. When on, dictionary lookups
 * appear in the Now → Lookup tab instead of the overlay popup.
 */
class DecoupledModeTileService : TileService() {

    private val configManager by lazy { ColorConfigManager(this) }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        configManager.setDecoupledMode(!configManager.isDecoupledMode())
        updateTileState()
        YomidroidAccessibilityService.instance?.loadColors()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val on = configManager.isDecoupledMode()
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Decoupled"
        tile.subtitle = if (on) "On" else "Off"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_decoupled)
        tile.updateTile()
    }
}
