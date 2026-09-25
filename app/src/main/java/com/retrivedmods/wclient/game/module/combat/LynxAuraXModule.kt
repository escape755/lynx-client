package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.ListItem
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.entity.Entity
import com.retrivedmods.wclient.game.entity.EntityUnknown
import com.retrivedmods.wclient.game.entity.Item
import com.retrivedmods.wclient.game.entity.LocalPlayer
import com.retrivedmods.wclient.game.entity.MobList
import com.retrivedmods.wclient.game.entity.Player
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.LevelEvent
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Port of Gato Client's GatoAuraX module (game/module/combat/GatoAuraXModule.kt +
 * LynxAuraXRots.kt), ported by request into Kestrel's Module/entity/inventory API.
 * Settings, rotation modes (engine in LynxAuraXRots.kt, ported unchanged) and the
 * trident auto-switch behavior are unchanged from the Gato original.
 *
 * One deliberate deviation from the Gato source: mob detection uses Kestrel's own
 * MobList.mobTypes (same table Kestrel's own KillauraModule uses) instead of Gato's
 * "anything that isn't a player or dropped item" heuristic, which could otherwise
 * treat things like boats, minecarts or XP orbs as attackable mobs.
 */
class LynxAuraXModule : Module("LynxAuraX", ModuleCategory.Combat) {

    // --- named selectors (horizontal chip list in the UI) ---
    private class Mode(override val name: String, val idx: Int) : ListItem

    private val targetModes = listOf(Mode("Single", 0), Mode("Multi", 1))
    private val rotationModes = listOf(
        Mode("Unified", 13), Mode("None", 0), Mode("Smooth", 1), Mode("Nemesis", 2), Mode("Strafe", 3),
        Mode("Vortex", 4), Mode("FrontStrafe", 5), Mode("AirHvH Pro", 6), Mode("FrontsX", 7),
        Mode("Astral", 8), Mode("Atomic", 9), Mode("Syntax", 10), Mode("Cortex", 11), Mode("Alpha", 12)
    )
    private val switchModes = listOf(Mode("None", 0), Mode("Full", 1), Mode("Silent", 2))

    // --- Settings: same names, order, defaults and ranges as the Gato source ---
    private var targetModeItem by listValue("TargetMode", targetModes[0], targetModes.toSet())
    private var range by floatValue("Range", 2.0f, 2.0f..150.0f)
    private var interval by intValue("Interval", 0, 0..20)
    private var multiplier by intValue("Multiplier", 1, 1..10)
    private var rotationItem by listValue("Rotation", rotationModes[0], rotationModes.toSet())
    private var switchItem by listValue("Switch", switchModes[0], switchModes.toSet())
    private var hurttimeCheck by boolValue("Hurttime", false)
    private var attackMobs by boolValue("Attack Mobs", false)
    private var hitChance by intValue("Hit Chance", 100, 1..100)
    private var randomizeHit by boolValue("Randomize Hit", false)
    private var noSwing by boolValue("NoSwing", false)
    private var autoTrident by boolValue("Auto Trident", false)
    private var rainMode by boolValue("Rain Mode", false)
    private var vertOffset by floatValue("Vertical Offset", 0.3f, -1.0f..1.0f)
    private var debug by boolValue("Debug", false)

    // int accessors over the named selectors
    private val targetMode get() = (targetModeItem as Mode).idx
    private val rotationMode get() = (rotationItem as Mode).idx
    private val switchMode get() = (switchItem as Mode).idx

    // runtime state
    private val targets = ArrayList<Entity>()
    private var tickCtr = 0
    private val rotOut = floatArrayOf(0f, 0f) // {pitch, yaw}
    private var headYawOut = 0f
    private var raining = false

    private var lastDebugMsg: String? = null
    private fun dbg(msg: String) {
        if (!debug) return
        if (msg == lastDebugMsg) return
        lastDebugMsg = msg
        session.displayClientMessage("[LynxAuraX] $msg")
    }

    override fun onEnabled() {
        super.onEnabled()
        targets.clear()
        tickCtr = 0
    }

    override fun onDisabled() {
        super.onDisabled()
        targets.clear()
        tickCtr = 0
        LynxAuraXRots.resetState()
    }

    private fun targetDims(entity: Entity): Pair<Float, Float> {
        val w = entity.metadata[EntityDataTypes.WIDTH] as? Float ?: 0.6f
        val h = entity.metadata[EntityDataTypes.HEIGHT] as? Float ?: 1.8f
        return w to h
    }

    private fun Entity.isMob(): Boolean =
        this is EntityUnknown && this !is Item && this.identifier in MobList.mobTypes

    private fun updateTargets(localPlayer: LocalPlayer) {
        targets.clear()
        for (entity in session.level.entityMap.values) {
            if (entity === localPlayer) continue
            val isPlayer = entity is Player
            if (!isPlayer && !(attackMobs && entity.isMob())) continue
            val dist = entity.distance(localPlayer.posX, localPlayer.posY, localPlayer.posZ)
            if (range <= dist) continue
            targets.add(entity)
        }
        targets.sortBy { it.distance(localPlayer.posX, localPlayer.posY, localPlayer.posZ) }
    }

    private fun attackTarget(target: Entity) {
        if (hurttimeCheck && ((target.metadata[EntityDataTypes.HURT_TICKS] as? Int) ?: 0) > 8) return

        val inventory = session.localPlayer.inventory
        repeat(multiplier) {
            if (randomizeHit && (Math.random() * 100).toInt() + 1 > hitChance) return@repeat
            if (noSwing) {
                val transaction = InventoryTransactionPacket()
                transaction.transactionType = InventoryTransactionType.ITEM_USE_ON_ENTITY
                transaction.actionType = 1
                transaction.runtimeEntityId = target.runtimeEntityId
                transaction.hotbarSlot = inventory.heldItemSlot
                transaction.itemInHand = inventory.hand
                transaction.playerPosition = session.localPlayer.vec3Position
                transaction.clickPosition = Vector3f.ZERO
                session.serverBound(transaction)
            } else {
                session.localPlayer.attack(target)
            }
        }
    }

    private fun findTridentSlot(): Int {
        val inventory = session.localPlayer.inventory
        return inventory.searchForItemInHotbar {
            it.definition?.identifier == "minecraft:trident"
        } ?: -1
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isSessionCreated) return
        val packet = interceptablePacket.packet

        when (packet) {
            is LevelEventPacket -> {
                when (packet.type) {
                    LevelEvent.START_RAINING, LevelEvent.START_THUNDERSTORM -> raining = true
                    LevelEvent.STOP_RAINING, LevelEvent.STOP_THUNDERSTORM -> raining = false
                    else -> {}
                }
                return
            }

            is PlayerAuthInputPacket -> {
                if (isEnabled) tick(packet)
                spoofRotation(packet)
            }

            is MovePlayerPacket -> {
                if (isEnabled && targets.isNotEmpty() && rotationMode >= 1 && rotationMode != 3 &&
                    packet.runtimeEntityId == session.localPlayer.runtimeEntityId
                ) {
                    packet.rotation = Vector3f.from(rotOut[0], rotOut[1], headYawOut)
                }
            }

            else -> {}
        }
    }

    private fun spoofRotation(packet: PlayerAuthInputPacket) {
        if (targets.isEmpty()) return
        val m = rotationMode
        if (m < 1 || m == 3) return

        packet.rotation = Vector3f.from(rotOut[0], rotOut[1], headYawOut)
    }

    private fun tick(packet: PlayerAuthInputPacket) {
        val localPlayer = session.localPlayer

        val rainingOk = raining && rainMode
        val inWater = packet.inputData.contains(PlayerAuthInputData.AUTO_JUMPING_IN_WATER)
        var tridentSlot = -1
        if (rainingOk) tridentSlot = findTridentSlot()
        if (tridentSlot == -1 && autoTrident && inWater) tridentSlot = findTridentSlot()
        if (tridentSlot == -1 && switchMode > 0) tridentSlot = findTridentSlot()

        updateTargets(localPlayer)
        tickCtr++
        if (targets.isEmpty()) {
            dbg("sin objetivos en rango (${range})")
            return
        }
        if (tickCtr < interval) return

        val curSlot = localPlayer.inventory.heldItemSlot
        var didSwitch = false
        if (tridentSlot != -1 && tridentSlot != curSlot) {
            session.serverBound(MobEquipmentPacket().apply {
                runtimeEntityId = localPlayer.runtimeEntityId
                item = localPlayer.inventory.content[tridentSlot]
                inventorySlot = tridentSlot
                hotbarSlot = tridentSlot
            })
            didSwitch = true
        }

        val first = targets[0]
        val dx = first.posX - localPlayer.posX
        val dy = first.posY - localPlayer.posY
        val dz = first.posZ - localPlayer.posZ
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        var pitch = rotOut[0]
        var yaw = rotOut[1]
        if (dist > 0.001f) {
            pitch = (asin(dy / dist) * -57.295776f)
            yaw = (-atan2(dx, dz) * 57.295776f)
        }

        val aimY = first.posY + vertOffset
        val daY = aimY - localPlayer.posY
        val distA = sqrt(dx * dx + daY * daY + dz * dz)
        if (distA > 0.001f) {
            pitch = (asin(daY / distA) * -57.295776f)
            yaw = (-atan2(dx, dz) * 57.295776f)
        }
        var fpitch = pitch
        var fyaw = yaw
        while (fpitch > 90f) fpitch -= 180f
        while (fpitch < -90f) fpitch += 180f
        while (fyaw > 180f) fyaw -= 360f
        while (fyaw < -180f) fyaw += 360f

        val m = rotationMode
        if (m != 3 && m > 0 && m <= 13) {
            rotOut[0] = fpitch
            rotOut[1] = fyaw
            headYawOut = fyaw
        }

        val ctx = LynxAuraXRots.Ctx(
            50f, 0.9f, 8f, 1f, 3f, 0.7f, 1.5f, 0.1f, 0.1f, 8, 20,
            randomizeHit, vertOffset
        )
        ctx.rotPitch = rotOut[0]
        ctx.rotYaw = rotOut[1]
        ctx.headYaw = headYawOut

        val env = LynxAuraXRots.Env(
            localPlayer.posX, localPlayer.posY, localPlayer.posZ,
            localPlayer.motionX, localPlayer.motionY, localPlayer.motionZ,
            packet.inputData.contains(PlayerAuthInputData.UP),
            packet.inputData.contains(PlayerAuthInputData.LEFT),
            packet.inputData.contains(PlayerAuthInputData.DOWN),
            packet.inputData.contains(PlayerAuthInputData.RIGHT),
            packet.inputData.contains(PlayerAuthInputData.JUMPING),
            System.nanoTime() / 1e9f
        )

        fun targetSnapshot(e: Entity): LynxAuraXRots.Target {
            val (w, h) = targetDims(e)
            return LynxAuraXRots.Target(
                e.posX, e.posY, e.posZ, e.motionX, e.motionY, e.motionZ,
                e.rotationYaw, w, h
            )
        }

        when (m) {
            0 -> {}
            3 -> {
                val t = System.nanoTime() / 1e9f * 0.1f
                rotOut[0] = fpitch + kotlin.math.sin(t) * 10f
                rotOut[1] = fyaw + kotlin.math.cos(t) * 10f
            }
            else -> {
                val handler = targets[0]
                val snap = targetSnapshot(handler)
                when (m) {
                    1 -> LynxAuraXRots.smooth(ctx, snap, env)
                    2 -> LynxAuraXRots.nemesis(ctx, snap, env)
                    4 -> LynxAuraXRots.vortex(ctx, snap, env)
                    5 -> LynxAuraXRots.frontStrafe(ctx, snap, env, handler.runtimeEntityId)
                    6 -> LynxAuraXRots.airHvHPro(ctx, snap, env)
                    7 -> LynxAuraXRots.frontsX(ctx, snap, env)
                    8 -> LynxAuraXRots.astral(ctx, snap, env)
                    9 -> LynxAuraXRots.atomic(ctx, snap, env)
                    10 -> LynxAuraXRots.syntax(ctx, snap, env)
                    11 -> LynxAuraXRots.cortex(ctx, snap, env)
                    12 -> LynxAuraXRots.alpha(ctx, snap, env)
                    13 -> LynxAuraXRots.unified(ctx, snap, env)
                }
            }
        }

        rotOut[0] = ctx.rotPitch
        rotOut[1] = ctx.rotYaw
        headYawOut = ctx.headYaw

        dbg("atacando ${targets.size} objetivo(s), rot=${rotationModes[rotationMode].name}")

        if (targetMode == 0) {
            attackTarget(targets[0])
        } else {
            for (t in targets) attackTarget(t)
        }

        if (didSwitch && switchMode == 2) {
            session.serverBound(MobEquipmentPacket().apply {
                runtimeEntityId = localPlayer.runtimeEntityId
                item = localPlayer.inventory.content[curSlot]
                inventorySlot = curSlot
                hotbarSlot = curSlot
            })
        }
        tickCtr = 0
    }

    private fun asin(f: Float): Float = kotlin.math.asin(f.coerceIn(-1f, 1f))
}
