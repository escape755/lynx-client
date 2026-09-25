package com.retrivedmods.wclient.game.module.visual

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import org.cloudburstmc.protocol.bedrock.data.LevelEvent
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket
import org.cloudburstmc.protocol.bedrock.packet.SpawnParticleEffectPacket

/**
 * Client-side rendering filter. Drops purely cosmetic particle/effect
 * packets before they reach the game, to reduce GPU/CPU load in
 * particle-heavy fights or builds (crystal PvP, big explosions, custom
 * resource-pack effects, etc).
 *
 * This only removes decorative rendering on this device: it never changes
 * what is sent to the server, never touches movement/combat packets, and
 * has no effect on how the local player or anyone else is simulated.
 */
class AntiLagModule : Module("anti_lag", ModuleCategory.Visual) {

    private val explosions by boolValue("Explosions", true)
    private val combatHits by boolValue("Combat Hits", true)
    private val blockBreakChunks by boolValue("Block Break Chunks", true)
    private val customParticles by boolValue("Custom Particles", true)
    private val potionAndWater by boolValue("Potion And Water", false)
    private val ambient by boolValue("Ambient", false)
    private val weatherParticles by boolValue("Weather Particles", false)

    private val explosionEvents = setOf(
        LevelEvent.PARTICLE_EXPLOSION,
        LevelEvent.PARTICLE_BLOCK_EXPLOSION,
        LevelEvent.PARTICLE_WIND_EXPLOSION,
        LevelEvent.PARTICLE_BREEZE_WIND_EXPLOSION,
        LevelEvent.SONIC_EXPLOSION,
        LevelEvent.PARTICLE_KNOCKBACK_ROAR,
        LevelEvent.PARTICLE_SMASH_ATTACK_GROUND_DUST
    )

    private val combatEvents = setOf(
        LevelEvent.PARTICLE_CRIT,
        LevelEvent.PARTICLE_SHOOT,
        LevelEvent.PARTICLE_SHOOT_WHITE_SMOKE
    )

    private val blockBreakEvents = setOf(
        LevelEvent.PARTICLE_DESTROY_BLOCK,
        LevelEvent.PARTICLE_DESTROY_BLOCK_NO_SOUND,
        LevelEvent.PARTICLE_CRACK_BLOCK,
        LevelEvent.PARTICLE_BREAK_BLOCK_UP,
        LevelEvent.PARTICLE_BREAK_BLOCK_DOWN,
        LevelEvent.PARTICLE_BREAK_BLOCK_NORTH,
        LevelEvent.PARTICLE_BREAK_BLOCK_SOUTH,
        LevelEvent.PARTICLE_BREAK_BLOCK_EAST,
        LevelEvent.PARTICLE_BREAK_BLOCK_WEST
    )

    private val potionAndWaterEvents = setOf(
        LevelEvent.PARTICLE_POTION_SPLASH,
        LevelEvent.PARTICLE_EVAPORATE,
        LevelEvent.PARTICLE_EVAPORATE_WATER,
        LevelEvent.PARTICLE_FIZZ_EFFECT,
        LevelEvent.PARTICLE_BUBBLES
    )

    private val ambientEvents = setOf(
        LevelEvent.DUST_PLUME,
        LevelEvent.PARTICLE_DRIPSTONE_DRIP,
        LevelEvent.PARTICLE_ELECTRIC_SPARK,
        LevelEvent.PARTICLE_TELEPORT,
        LevelEvent.PARTICLE_TELEPORT_TRAIL,
        LevelEvent.PARTICLE_EYE_OF_ENDER_DEATH,
        LevelEvent.PARTICLE_MOB_BLOCK_SPAWN,
        LevelEvent.PARTICLE_GENERIC_SPAWN,
        LevelEvent.SCULK_CHARGE,
        LevelEvent.SCULK_CHARGE_POP,
        LevelEvent.SCULK_CATALYST_BLOOM,
        LevelEvent.PARTICLE_SCULK_SHRIEK,
        LevelEvent.PARTICLE_CROP_GROWTH,
        LevelEvent.PARTICLE_CROP_EATEN,
        LevelEvent.PARTICLE_DEATH_SMOKE
    )

    private val weatherEvents = setOf(
        LevelEvent.START_RAINING,
        LevelEvent.START_THUNDERSTORM
    )

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet

        if (packet is LevelEventPacket) {
            val type = packet.type
            val blocked =
                (explosions && type in explosionEvents) ||
                    (combatHits && type in combatEvents) ||
                    (blockBreakChunks && type in blockBreakEvents) ||
                    (potionAndWater && type in potionAndWaterEvents) ||
                    (ambient && type in ambientEvents) ||
                    (weatherParticles && type in weatherEvents)

            if (blocked) {
                interceptablePacket.intercept()
            }
            return
        }

        if (customParticles && packet is SpawnParticleEffectPacket) {
            interceptablePacket.intercept()
        }
    }
}
