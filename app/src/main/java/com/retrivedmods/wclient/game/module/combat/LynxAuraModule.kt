package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.ListItem
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.entity.Entity
import com.retrivedmods.wclient.game.entity.EntityUnknown
import com.retrivedmods.wclient.game.entity.Item
import com.retrivedmods.wclient.game.entity.MobList
import com.retrivedmods.wclient.game.entity.Player
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Port of Gato Client's GatoAura module (game/module/combat/GatoAuraModule.kt),
 * ported by request into Kestrel/WClient's Module/entity/inventory API. Settings
 * and per-tick logic are unchanged from the Gato source, with one omission:
 * WClient's settings system has no "visibleIf" (conditional show/hide of one
 * setting based on another), so Critical Velocity/Strike Mode/Min Dist/Max
 * Dist/Max Angle are always visible here instead of only when their parent
 * toggle is on - purely cosmetic, doesn't change behavior.
 *
 * Mob detection uses WClient's own MobList.mobTypes (same as this codebase's
 * KillauraModule), same deviation as in the GatoAuraX port.
 */
class LynxAuraModule : Module("LynxAura", ModuleCategory.Combat) {

    // --- named selectors (horizontal chip list in the UI) ---
    private class Mode(override val name: String, val idx: Int) : ListItem

    private val rotModes = listOf(Mode("Unified", 3), Mode("None", 0), Mode("Normal", 1), Mode("Strafe", 2))
    private val hitTypes = listOf(Mode("Single", 0), Mode("Multi", 1))
    private val targetPriorities = listOf(Mode("Distance", 0), Mode("Health", 1))
    private val weaponModes = listOf(Mode("None", 0), Mode("Switch", 1), Mode("Spoof", 2))

    // --- Settings: same names/defaults/ranges as the Gato source ---
    private var range by floatValue("Range", 5f, 3f..40f)
    private var wallRange by floatValue("WallRange", 0f, 0f..40f) // sin LOS en relay
    private var interval by intValue("Interval", 1, 0..20)
    private var java by boolValue("Java Cooldown", true)
    private var rotModeItem by listValue("Rotations", rotModes[0], rotModes.toSet())
    private var rotationSpeed by intValue("Rot Speed", 10, 10..180)

    private var adaptiveRot by boolValue("Adaptive Rot", false)
    private var shouldCriticals by boolValue("Packet Criticals", false)
    private var criticalVelocity by floatValue("Critical Velocity", 1f, -5f..5f)
    private var strikeMode by boolValue("Strike Mode", false)
    private var minDist by floatValue("Min Dist", 3f, 0f..20f)
    private var maxDist by floatValue("Max Dist", 8f, 0f..20f)
    private var randomize by boolValue("Aim Randomize", true)
    private var facingCheck by boolValue("Facing Check", false)
    private var maxAngle by floatValue("Max Angle", 45f, 0f..90f)

    private var hitTypeItem by listValue("HitType", hitTypes[0], hitTypes.toSet())
    private var targetPriorityItem by listValue("Target Priority", targetPriorities[0], targetPriorities.toSet())
    private var packetAttack by boolValue("Packet", false)
    private var adaptivePackets by boolValue("Adaptive Packets", true)
    private var hitChance by intValue("HitChance", 100, 0..100)
    private var intervalJitter by floatValue("Interval Jitter", 15f, 0f..50f)
    private var hitAttempts by intValue("Hit Attempts", 1, 1..50)
    private var weaponItem by listValue("Weapon", weaponModes[0], weaponModes.toSet())
    private var includeMobs by boolValue("Mobs", false)
    private var hurtTimeCheck by boolValue("Hurt Check", true)
    private var eatStop by boolValue("EatStop", false)
    private var debug by boolValue("Debug", false)

    // int accessors over the named selectors
    private val rotMode get() = (rotModeItem as Mode).idx
    private val hitType get() = (hitTypeItem as Mode).idx
    private val targetPriority get() = (targetPriorityItem as Mode).idx
    private val autoWeaponMode get() = (weaponItem as Mode).idx

    // runtime state
    private val targetList = ArrayList<Entity>()
    private var shouldRot = false
    private var targetYaw = 0f
    private var currentYaw = 0f
    private var targetPitch = 0f
    private var currentPitch = 0f
    private var critPend = false
    private var critDip = 0f
    private var timePassed = 0L
    private var lastTickTime = 0L
    private var usingItemTicks = 0

    private var lastDebugMsg: String? = null
    private fun dbg(msg: String) {
        if (!debug) return
        if (msg == lastDebugMsg) return
        lastDebugMsg = msg
        session.displayClientMessage("[LynxAura] $msg")
    }

    override fun onEnabled() {
        super.onEnabled()
        targetYaw = 0f; currentYaw = 0f; targetPitch = 0f; currentPitch = 0f
        critPend = false; critDip = 0f
    }

    override fun onDisabled() {
        super.onDisabled()
        targetList.clear()
        shouldRot = false
        targetYaw = 0f; currentYaw = 0f; targetPitch = 0f; currentPitch = 0f
        critPend = false; critDip = 0f
    }

    private fun Entity.isMob(): Boolean =
        this is EntityUnknown && this !is Item && this.identifier in MobList.mobTypes

    // ---- static per-weapon tables (mobile substitute for engine values) ----
    private fun weaponDamage(identifier: String?): Float = when (identifier) {
        "minecraft:wooden_sword" -> 4f
        "minecraft:stone_sword" -> 5f
        "minecraft:iron_sword" -> 6f
        "minecraft:diamond_sword" -> 7f
        "minecraft:netherite_sword" -> 8f
        "minecraft:golden_sword" -> 4f
        "minecraft:wooden_axe" -> 7f
        "minecraft:stone_axe" -> 9f
        "minecraft:iron_axe" -> 9f
        "minecraft:diamond_axe" -> 9f
        "minecraft:netherite_axe" -> 10f
        "minecraft:trident" -> 9f
        else -> 1f
    }

    private fun weaponCooldownMs(identifier: String?): Long = when {
        identifier == null -> 0L
        identifier.endsWith("_sword") -> 625L
        identifier.endsWith("_axe") -> 1000L
        identifier == "minecraft:trident" -> 500L
        else -> 0L
    }

    private fun getBestWeaponSlot(target: Entity): Int {
        val inventory = session.localPlayer.inventory
        var damage = weaponDamage(inventory.content[inventory.heldItemSlot].definition?.identifier)
        var slot = inventory.heldItemSlot
        for (i in 0..8) {
            val currentDamage = weaponDamage(inventory.content[i].definition?.identifier)
            if (currentDamage > damage) {
                damage = currentDamage
                slot = i
            }
        }
        return slot
    }

    private fun attack(target: Entity): Boolean {
        val randomNumber = (Math.random() * 100).toInt()
        val inventory = session.localPlayer.inventory

        var attempts = hitAttempts
        if (adaptivePackets) {
            val dist = target.distance(session.localPlayer.vec3Position)
            if (dist < 3.5f) attempts *= 2
            else if (dist > 10f) attempts = if (attempts > 1) attempts / 2 else 1
        }

        val attacked = randomNumber < hitChance
        if (attacked) {
            repeat(attempts) {
                if (packetAttack) {
                    val transaction = InventoryTransactionPacket()
                    transaction.transactionType = InventoryTransactionType.ITEM_USE_ON_ENTITY
                    transaction.actionType = 1
                    transaction.runtimeEntityId = target.runtimeEntityId
                    transaction.hotbarSlot = inventory.heldItemSlot
                    transaction.itemInHand = inventory.hand
                    transaction.playerPosition = Vector3f.from(
                        session.localPlayer.posX,
                        session.localPlayer.posY + criticalVelocity,
                        session.localPlayer.posZ
                    )
                    transaction.clickPosition = Vector3f.from(0.1f, 0.5f, 0.1f)
                    session.serverBound(transaction)
                } else {
                    session.localPlayer.attack(target)
                }
            }
        }

        session.localPlayer.swing()
        return attacked
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isSessionCreated) return
        val packet = interceptablePacket.packet

        when (packet) {
            is PlayerAuthInputPacket -> {
                if (isEnabled) tick(packet)
                spoofRotation(packet)
            }

            is MovePlayerPacket -> applyStrikeOffset(packet)
            is MoveEntityAbsolutePacket -> applyStrikeOffset(packet)
            else -> {}
        }
    }

    private fun applyStrikeOffset(packet: BedrockPacket) {
        if (!isEnabled || !shouldCriticals || critDip == 0f) return
        val yOffset: Float = if (strikeMode) {
            if (criticalVelocity < 0f) criticalVelocity * -2f + criticalVelocity
            else if (criticalVelocity > 0f) criticalVelocity * 2f - criticalVelocity
            else 0f
        } else {
            criticalVelocity
        }
        if (yOffset == 0f) return
        when (packet) {
            is MoveEntityAbsolutePacket -> {
                if (packet.runtimeEntityId == session.localPlayer.runtimeEntityId) {
                    packet.position = Vector3f.from(
                        packet.position.x, packet.position.y + yOffset, packet.position.z
                    )
                }
            }
            is MovePlayerPacket -> {
                if (packet.runtimeEntityId == session.localPlayer.runtimeEntityId) {
                    packet.position = Vector3f.from(
                        packet.position.x, packet.position.y + yOffset, packet.position.z
                    )
                }
            }
        }
    }

    private fun tick(packet: PlayerAuthInputPacket) {
        val localPlayer = session.localPlayer
        critPend = false

        targetList.clear()
        if (eatStop) {
            if (packet.inputData.contains(PlayerAuthInputData.START_USING_ITEM)) usingItemTicks = 20
            if (usingItemTicks > 0) {
                usingItemTicks--
                shouldRot = false
                critDip = 0f
                return
            }
        }

        for (entity in session.level.entityMap.values) {
            if (entity.distance(localPlayer.vec3Position) > range) continue
            val isPlayer = entity is Player
            val isMob = entity.isMob()
            if (!isPlayer && !isMob) continue
            if (!isPlayer && !includeMobs) continue
            if (hurtTimeCheck && ((entity.metadata[EntityDataTypes.HURT_TICKS] as? Int) ?: 0) > 0) continue
            targetList.add(entity)
        }

        if (targetList.isEmpty()) {
            dbg("sin objetivos en rango (${range})")
            shouldRot = false
            critDip = 0f
            return
        }

        if (targetPriority == 1) {
            targetList.sortBy { (it.attributes["minecraft:health"]?.value ?: 20f) }
        } else {
            targetList.sortBy { it.distance(localPlayer.vec3Position) }
        }

        val first = targetList[0]
        val (width, height) = targetDims(first)
        val boxCenterY = first.posY + height * 0.5f
        var aimX = first.posX
        var aimY = boxCenterY
        var aimZ = first.posZ
        if (randomize) {
            val yLow = first.posY + 0.1f
            val yHigh = first.posY + height - 0.1f
            val xLow = first.posX - width * 0.5f + 0.2f
            val xHigh = first.posX + width * 0.5f - 0.2f
            val zLow = first.posZ - width * 0.5f + 0.2f
            val zHigh = first.posZ + width * 0.5f - 0.2f
            aimX = randomFloat(xLow, xHigh)
            aimY = if (yHigh > yLow) randomFloat(yLow, yHigh) else boxCenterY
            aimZ = randomFloat(zLow, zHigh)
        }

        val eyeY = localPlayer.posY + 1.62f
        val dX = aimX - localPlayer.posX
        val dY = aimY - eyeY
        val dZ = aimZ - localPlayer.posZ
        val horiz = sqrt(dX * dX + dZ * dZ)
        targetPitch = (atan2(dY, horiz) * 57.295776f) * -1f
        targetYaw = -atan2(dX, dZ) * 57.295776f

        shouldRot = true

        val weaponSlot = if (autoWeaponMode != 0 || java) getBestWeaponSlot(first)
        else localPlayer.inventory.heldItemSlot

        var attackInterval = interval.toFloat()
        if (intervalJitter > 0f && !java) {
            attackInterval *= 1f + randomFloat(-intervalJitter, intervalJitter) / 100f
            if (attackInterval < 0f) attackInterval = 0f
        }

        var facingOk = true
        if (facingCheck) {
            var facingDiff = targetYaw - currentYaw
            while (facingDiff < -180f) facingDiff += 360f
            while (facingDiff > 180f) facingDiff -= 360f
            facingOk = abs(facingDiff) <= maxAngle
        }

        if (facingOk) {
            val cooldownItem = localPlayer.inventory.content[
                if (autoWeaponMode != 0) weaponSlot else localPlayer.inventory.heldItemSlot
            ]
            val cooldownMs = weaponCooldownMs(cooldownItem.definition?.identifier)
            val now = System.nanoTime() / 1_000_000L
            val reached: Boolean
            if (java && cooldownMs > 0L) {
                reached = now - timePassed >= cooldownMs
            } else {
                val tickIntervalMs = (attackInterval * 50f).toLong()
                reached = now - lastTickTime >= tickIntervalMs
                lastTickTime = now
            }
            if (java && cooldownMs > 0L && reached) timePassed = now

            if (reached) {
                if (autoWeaponMode != 0) {
                    if (localPlayer.inventory.heldItemSlot != weaponSlot) {
                        session.serverBound(mobEquipment(weaponSlot))
                    }
                }
                var attackedAny = false
                dbg("atacando ${targetList.size} objetivo(s)")
                for (target in targetList) {
                    if (attack(target)) attackedAny = true
                    if (hitType != 1) break
                }
                critPend = shouldCriticals && attackedAny
            }
        }
    }

    private fun mobEquipment(slot: Int) = MobEquipmentPacket().apply {
        runtimeEntityId = session.localPlayer.runtimeEntityId
        item = session.localPlayer.inventory.content[slot]
        inventorySlot = slot
        hotbarSlot = slot
    }

    private fun spoofRotation(packet: PlayerAuthInputPacket) {
        if (!shouldRot || targetList.isEmpty()) return

        if (rotMode == 3) {
            val localPlayer = session.localPlayer
            val target = targetList[0]
            val (w, h) = targetDims(target)
            val ctx = LynxAuraXRots.Ctx(50f, 0.9f, 8f, 1f, 3f, 0.7f, 1.5f, 0.1f, 0.1f, 8, 20, randomize, 0f)
            ctx.rotPitch = currentPitch
            ctx.rotYaw = currentYaw
            val env = LynxAuraXRots.Env(
                localPlayer.posX, localPlayer.posY, localPlayer.posZ,
                localPlayer.motionX, localPlayer.motionY, localPlayer.motionZ,
                false, false, false, false, false, System.nanoTime() / 1e9f
            )
            LynxAuraXRots.unified(
                ctx,
                LynxAuraXRots.Target(target.posX, target.posY, target.posZ, target.motionX, target.motionY, target.motionZ, target.rotationYaw, w, h),
                env
            )
            currentPitch = ctx.rotPitch
            currentYaw = ctx.rotYaw
            packet.rotation = Vector3f.from(currentPitch, packet.rotation.y, currentYaw)
            return
        }

        var angleDiff = targetYaw - currentYaw
        while (angleDiff < -180f) angleDiff += 360f
        while (angleDiff > 180f) angleDiff -= 360f

        var finalRotationSpeed = rotationSpeed.toFloat()
        if (adaptiveRot) {
            val first = targetList[0]
            val distance = first.distance(session.localPlayer.vec3Position)
            val denom = maxOf(maxDist - minDist, 1f)
            var ratio = (distance - minDist) / denom
            if (ratio < 0f) ratio = 0f
            if (ratio > 1f) ratio = 1f
            finalRotationSpeed = 10f + ratio * (rotationSpeed - 10f)
        }

        currentYaw += angleDiff * (0.01f * finalRotationSpeed)
        val pitchDiff = targetPitch - currentPitch
        currentPitch += pitchDiff * (0.01f * finalRotationSpeed)
        if (currentPitch > 90f) currentPitch = 90f
        if (currentPitch < -90f) currentPitch = -90f

        when (rotMode) {
            0 -> return
            1 -> {
                packet.rotation = Vector3f.from(currentPitch, packet.rotation.y, currentYaw)
            }
            2 -> {
                packet.rotation = Vector3f.from(currentPitch, currentYaw, currentYaw)
            }
        }

        val distance = targetList[0].distance(session.localPlayer.vec3Position)
        if (distance < 0.1f) {
            packet.rotation = Vector3f.from(90f, packet.rotation.y, currentYaw)
        }

        if (shouldCriticals && shouldRot) {
            packet.position = Vector3f.from(
                packet.position.x, packet.position.y - critDip, packet.position.z
            )
            critDip += 0.012f
            if (critDip >= 0.24f) critDip = 0f
        }
        critPend = false
    }

    private fun targetDims(entity: Entity): Pair<Float, Float> {
        val w = entity.metadata[EntityDataTypes.WIDTH] as? Float ?: 0.6f
        val h = entity.metadata[EntityDataTypes.HEIGHT] as? Float ?: 1.8f
        return w to h
    }

    private fun randomFloat(low: Float, high: Float): Float =
        if (high <= low) low else low + Math.random().toFloat() * (high - low)
}
