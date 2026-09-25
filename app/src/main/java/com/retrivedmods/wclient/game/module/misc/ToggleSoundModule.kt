package com.retrivedmods.wclient.game.module.misc

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory

class ToggleSoundModule : Module(
    name = "toggle_sounds",
    category = ModuleCategory.Misc,
    defaultEnabled = false
) {
    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
    }
}
