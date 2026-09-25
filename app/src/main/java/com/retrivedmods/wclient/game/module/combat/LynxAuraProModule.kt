package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.ListItem
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.entity.Entity
import com.retrivedmods.wclient.game.entity.EntityUnknown
import com.retrivedmods.wclient.game.entity.Item
import com.retrivedmods.wclient.game.entity.Player
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.LevelEvent
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataType
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Port of the GatoClient (PC) +999aura module — reconstruction of Rooster.dll's
 * Killaura (9 rotation modes; the 5 heavy ones RE'd instruction by instruction).
 * Exact settings, chunked rings, per-mode module state and math quirks.
 *
 * PC reference: Combat/Plus999Aura.cpp/.h
 *
 * Relay substitutions (same family as GatoAuraX, documented):
 * - Sky scan (voxels) -> skipped (skyBlocked = false); the rain gate uses the
 *   relayed LevelEventPacket weather instead of the PC's dimension-only gate —
 *   strictly closer to Rooster's rainStrength read.
 * - Water check -> AUTO_JUMPING_IN_WATER input flag.
 * - entityFlag (predicate) -> EntityFlag.ON_GROUND metadata flag.
 * - getHurtTime -> EntityDataTypes.HURT_TICKS.
 * - Weapon score -> static per-identifier damage table (same as GatoAura).
 * - Switch Full (local slot change) -> MobEquipmentPacket without restore;
 *   Silent -> MobEquipmentPacket + restore (PC mode 2 behavior).
 * - onUpdateRotation (local camera) -> cannot exist on mobile; the PAIP spoof
 *   keeps the PC onSendPacket gate: targets non-empty and mode in {1, 3..8}.
 * - Target Visualize render: not ported (pending render overlay integration).
 */
class LynxAuraProModule : Module("LynxAuraPro", ModuleCategory.Combat) {

    // --- named selectors (horizontal chip list in the UI) ---
    private class Mode(override val name: String, val idx: Int) : ListItem

    private val targetModes = listOf(Mode("Single", 0), Mode("Multi", 1))
    private val rotationModes = listOf(
        Mode("Unified", 9), Mode("None", 0), Mode("Silent", 1), Mode("Strafe", 2), Mode("FrontStrafe", 3),
        Mode("AirHvH Pro", 4), Mode("Adaptive", 5), Mode("Apex", 6), Mode("Spectre", 7), Mode("Aegis", 8)
    )
    private val switchModes = listOf(Mode("None", 0), Mode("Full", 1), Mode("Silent", 2))

    // ===== vec3 holder (mirrors Vec3<float> usage in the handlers) =====
    private class V3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {
        constructor(a: FloatArray) : this(a[0], a[1], a[2])
        fun set(o: V3) { x = o.x; y = o.y; z = o.z }
        fun arr() = FloatArray(3) { floatArrayOf(x, y, z)[it] }
    }

    // ===== chunked rings (exact arithmetic of the binary: cap power of two,
    // start/count flat indices, grow max(8, 2*cap), trim via popFront) =====
    private class RingVec3 {
        var rows = ArrayList<V3>()
        var cap = 0; var start = 0; var count = 0

        private fun grow() {
            val nc = if (cap == 0) 8 else cap * 2
            val nrows = ArrayList<V3>(nc)
            for (i in 0 until nc) nrows.add(V3())
            for (i in rows.indices) if (i < nc) nrows[i].set(rows[i])
            rows = nrows
            cap = nc
        }

        fun push(v: V3) {
            if (count + 1 >= cap) grow()
            start = start and (cap - 1)
            rows[(start + count) and (cap - 1)].set(v)
            count++
        }

        fun at(k: Int): V3 = rows[(start + k) and (cap - 1)] // k flat from the oldest
        fun popFront() { if (count > 0) { count--; if (count != 0) start++ else start = 0 } }
        fun clear() { rows.clear(); cap = 0; start = 0; count = 0 }
    }

    private class RingFloat {
        var rows = FloatArray(0)
        var cap = 0; var start = 0; var count = 0

        private fun grow() {
            val nc = if (cap == 0) 8 else cap * 2
            val nrows = FloatArray(nc * 4)
            for (i in rows.indices) if (i < nrows.size) nrows[i] = rows[i]
            rows = nrows
            cap = nc
        }

        fun push(v: Float) {
            if (((start + count) and 3) == 0 && cap <= ((start + count) + 4) shr 2) grow()
            start = start and (cap * 4 - 1)
            val f = start + count
            rows[((f shr 2) and (cap - 1)) * 4 + (f and 3)] = v
            count++
        }

        fun at(k: Int): Float {
            val f = start + k
            return rows[((f shr 2) and (cap - 1)) * 4 + (f and 3)]
        }

        fun popFront() { if (count > 0) { count--; if (count != 0) start++ else start = 0 } }
        fun clear() { rows = FloatArray(0); cap = 0; start = 0; count = 0 }
    }

    private class RingU8 {
        var rows = ByteArray(0)
        var cap = 0; var start = 0; var count = 0

        private fun grow() {
            val nc = if (cap == 0) 8 else cap * 2
            val nrows = ByteArray(nc * 16)
            for (i in rows.indices) if (i < nrows.size) nrows[i] = rows[i]
            rows = nrows
            cap = nc
        }

        fun push(v: Int) {
            if (((start + count) and 0xF) == 0 && cap <= ((start + count) + 16) shr 4) grow()
            start = start and (cap * 16 - 1)
            val f = start + count
            rows[((f shr 4) and (cap - 1)) * 16 + (f and 0xF)] = v.toByte()
            count++
        }

        fun at(k: Int): Int {
            val f = start + k
            return rows[((f shr 4) and (cap - 1)) * 16 + (f and 0xF)].toInt()
        }

        fun popFront() { if (count > 0) { count--; if (count != 0) start++ else start = 0 } }
        fun clear() { rows = ByteArray(0); cap = 0; start = 0; count = 0 }
    }

    // ===== OscAxis de Aegis (oscilador amortiguado por eje) =====
    private class OscAxis {
        var pos = 0f; var f1 = 0f; var f2 = 0f
        var w0 = 100f; var w1 = 100f; var w2 = 100f
    }

    // ===== per-mode module states (flat, reset on target switch) =====
    private class AirHvHState {
        val posRing = RingVec3(); val yawRing = RingFloat()
        var lastTargetYaw = 0f
        var lastTgtPosX = 0f; var lastTgtPosY = 0f; var lastTgtPosZ = 0f
        var lastLocalPosX = 0f; var lastLocalPosY = 0f; var lastLocalPosZ = 0f
        var lastYawDelta = 0f
        var phase = 0; var detMult = 0.5f
        var extrapolate = true; var detect = true
        var limBonus = false; var speedMult = 1.0f; var limBonusAmt = 0f
        var scratchVelX = 0f
        fun reset() { posRing.clear(); yawRing.clear(); lastTargetYaw = 0f
            lastTgtPosX = 0f; lastTgtPosY = 0f; lastTgtPosZ = 0f
            lastLocalPosX = 0f; lastLocalPosY = 0f; lastLocalPosZ = 0f
            lastYawDelta = 0f; phase = 0; detMult = 0.5f; scratchVelX = 0f }
    }

    private class AdaptiveState {
        val posRing = RingVec3(); val yawRing = RingFloat(); val hitRing = RingU8()
        var dynMult = 1.0f
        var lastTarget: Entity? = null
        fun reset() { posRing.clear(); yawRing.clear(); hitRing.clear(); dynMult = 1.0f; lastTarget = null }
    }

    private class ApexState {
        val posHist = RingVec3(); val rotHist = RingFloat(); val hitRing = RingU8()
        var dynMult = 1.0f
        var lastTarget: Entity? = null
        val dirAcc = V3()
        var maxHistLen = 0
        var snapDone = false
        var cntTgtHurt = 0; var cntLpHurt = 0
        var prevTgtHurt = 0; var prevLpHurt = 0
        var hurtMult = 1.0f
        fun reset() { posHist.clear(); rotHist.clear(); hitRing.clear(); dynMult = 1.0f
            lastTarget = null; dirAcc.set(V3()); snapDone = false
            cntTgtHurt = 0; cntLpHurt = 0; prevTgtHurt = 0; prevLpHurt = 0; hurtMult = 1.0f }
    }

    private class SpectreState {
        val posRing = RingVec3(); val yawRing = RingFloat(); val hitRing = RingU8()
        val velEMA = V3()
        var spdEMA = 2.0f; var hitRatio = 1.0f
        var lastTarget: Entity? = null
        var firstTickDone = false
        var myHitStreak = 0; var targetHitStreak = 0
        var lastTargetHurtTime = 0; var lastMyHurtTime = 0
        var reachMult = 1.0f; var yawRateEMA = 0f; var yawAccelEMA = 0f
        fun reset() { posRing.clear(); yawRing.clear(); hitRing.clear(); velEMA.set(V3())
            spdEMA = 2.0f; hitRatio = 1.0f; lastTarget = null; firstTickDone = false
            myHitStreak = 0; targetHitStreak = 0; lastTargetHurtTime = 0; lastMyHurtTime = 0
            reachMult = 1.0f; yawRateEMA = 0f; yawAccelEMA = 0f }
    }

    private class AegisState {
        val rx = OscAxis(); val ry = OscAxis(); val rz = OscAxis()
        var lastTarget: Entity? = null
        val posRing = RingVec3()
        val latRing = FloatArray(32)
        var latHead = 0; var latCount = 0
        var lastTargetYaw = 0f
        var hurtCountA = 0; var hurtCountB = 0
        var prevHurtT = 0; var prevHurtLp = 0
        var mult = 1.0f
        val hitRing = RingU8()
        var hitFactor = 1.0f
        fun reset() { for (a in arrayOf(rx, ry, rz)) { a.pos = 0f; a.f1 = 0f; a.f2 = 0f; a.w0 = 100f; a.w1 = 100f; a.w2 = 100f }
            lastTarget = null; posRing.clear(); latRing.fill(0f); latHead = 0; latCount = 0
            lastTargetYaw = 0f; hurtCountA = 0; hurtCountB = 0; prevHurtT = 0; prevHurtLp = 0
            mult = 1.0f; hitRing.clear(); hitFactor = 1.0f }
    }

    // ===== settings (order/defaults of the Rooster ctor) =====
    private var targetModeItem by listValue("TargetMode", targetModes[0], targetModes.toSet())
    private var range by floatValue("Target range", 2.0f, 2.0f..150.0f)
    private var speedPredictHead by floatValue("speed predict head", 0.1f, 0.0f..250.0f) // muerto en el original
    private var interval by intValue("Interval", 0, 0..20)
    private var multiplier by intValue("Multiplier", 1, 1..10)
    private var rotationItem by listValue("Rotation", rotationModes[0], rotationModes.toSet())
    private var switchItem by listValue("Switch", switchModes[0], switchModes.toSet())
    private var hurttimeCheck by boolValue("Hurttime", false)
    private var targetVisualize by boolValue("Target Visualize", false)     // render no portado
    private var noSwing by boolValue("NoSwing", false)
    private var autoTrident by boolValue("Auto Trident", false)
    private var rainMode by boolValue("Rain Mode", false)
    private var vertOffset by floatValue("Vertical Offset", 0.3f, -1.0f..1.0f)

    // sin registrar en Rooster (fidelidad): fijos 100% / off
    private val hitChance = 100
    private val hitChanceGate = false
    private val filterFlag = false

    // int accessors over the named selectors
    private val targetMode get() = (targetModeItem as Mode).idx
    private val rotationMode get() = (rotationItem as Mode).idx
    private val switchMode get() = (switchItem as Mode).idx

    // ===== runtime state =====
    private val targets = ArrayList<Entity>()
    private var tickCtr = 0
    private var atkCount = 0
    private var gTick = 0
    private val rotOut = floatArrayOf(0f, 0f)  // +55C {pitch, yaw}
    private val rotCtx = floatArrayOf(0f, 0f)  // cc->rotCache (+2D8)

    private var raining = false                 // relayed weather (better than the PC dim gate)
    private val fsYawMap = HashMap<Long, Float>() // FrontStrafe per-target yaw (+0xD0)

    private val mAir = AirHvHState()
    private val mAdaptive = AdaptiveState()
    private val mApex = ApexState()
    private val mSpectre = SpectreState()
    private val mAegis = AegisState()

    private var lastInput: Set<PlayerAuthInputData> = emptySet()

    // ===== shared helpers (exact) =====
    private fun gameTimeSec(): Float = System.nanoTime() / 1e9f

    private fun wrapRot(pitch: FloatArray, yaw: FloatArray) {
        var p = pitch[0]; var y = yaw[0]
        while (p > 90f) p -= 180f
        while (p < -90f) p += 180f
        while (y > 180f) y -= 360f
        while (y < -180f) y += 360f
        pitch[0] = p; yaw[0] = y
    }

    private fun wrap180(a: Float): Float {
        var r = a
        while (r > 180f) r -= 360f
        while (r < -180f) r += 360f
        return r
    }

    private fun clamp3(v: Float, lo: Float, hi: Float): Float {
        val c1 = if (lo <= v) v else lo
        return if (c1 <= hi) c1 else hi
    }

    private fun medianSorted(v: MutableList<Float>): Float {
        v.sort()
        val n = v.size
        if (n == 0) return 0f
        return if (n and 1 != 0) v[n / 2] else (v[n / 2] + v[n / 2 - 1]) * 0.5f
    }

    private fun wrapA(a: Float): Float {
        var r = a
        if (r > 180.0f) do { r += -360.0f } while (r > 180.0f)
        if (-180.0f > r) do { r += 360.0f } while (-180.0f > r)
        return r
    }

    private fun wrapP(p: Float): Float {
        var r = p
        if (r > 90.0f) do { r += -180.0f } while (r > 90.0f)
        if (-90.0f > r) do { r += 180.0f } while (-90.0f > r)
        return r
    }

    private fun calcAngle(from: V3, to: V3, out: FloatArray): Boolean {
        val dx = to.x - from.x; val dy = to.y - from.y; val dz = to.z - from.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist <= 0.001f) return false
        out[0] = asin(dy / dist) * -57.295776f
        out[1] = -atan2(dx, dz) * 57.295776f
        return true
    }

    private fun asin(d: Double): Double = kotlin.math.asin(d.coerceIn(-1.0, 1.0))
    private fun asin(f: Float): Float = kotlin.math.asin(f) // raw: NaN propagates like the binary

    private fun hurtTimeOf(e: Entity): Int = (e.metadata[EntityDataTypes.HURT_TICKS] as? Int) ?: 0
    // Esta versión del protocolo no trae EntityFlag.ON_GROUND (no existe en este
    // WClient) — se aproxima por velocidad vertical, suficiente para el uso que le
    // da este módulo (una señal más entre varias, no una condición crítica).
    private fun onGroundOf(e: Entity): Boolean = kotlin.math.abs(e.motionY) < 0.01f

    // ===== target snapshot =====
    private class TData(
        val entity: Entity,
        val posX: Float, val posY: Float, val posZ: Float,
        val velX: Float, val velY: Float, val velZ: Float,
        val yaw: Float,
        val width: Float, val height: Float,
        val hurt: Int, val onGround: Boolean
    ) {
        val lowerX get() = posX - width * 0.5f
        val upperX get() = posX + width * 0.5f
        val upperY get() = posY + height
        val lowerZ get() = posZ - width * 0.5f
        val upperZ get() = posZ + width * 0.5f
    }

    // metadata is written from the server-channel thread and read from the
    // client-channel thread: tolerate the race instead of propagating it
    private fun metadataFloat(e: Entity, key: EntityDataType<Float>, fallback: Float): Float =
        runCatching { (e.metadata[key] as? Float) ?: fallback }.getOrDefault(fallback)

    private fun snapshot(e: Entity): TData {
        val w = metadataFloat(e, EntityDataTypes.WIDTH, 0.6f)
        val h = metadataFloat(e, EntityDataTypes.HEIGHT, 1.8f)
        val hurt = runCatching { hurtTimeOf(e) }.getOrDefault(0)
        val ground = runCatching { onGroundOf(e) }.getOrDefault(false)
        return runCatching {
            TData(e, e.posX, e.posY, e.posZ, e.motionX, e.motionY, e.motionZ,
                e.rotationYaw, w, h, hurt, ground)
        }.getOrElse {
            println("Plus999 snapshot error: ${it.stackTraceToString()}")
            TData(e, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.6f, 1.8f, 0, false)
        }
    }

    // =======================================================================
    // ctor-equivalent: settings above; enable/disable (spec §7)
    // =======================================================================

    private fun resetPerTargetState() {
        fsYawMap.clear()
        mAir.reset(); mAdaptive.reset(); mApex.reset(); mSpectre.reset(); mAegis.reset()
    }

    override fun onEnabled() {
        super.onEnabled()
        targets.clear()
        tickCtr = 0; atkCount = 0
        raining = false
        resetPerTargetState()
        // seed caches with the current rotation (only when a session exists —
        // the module can be toggled from the app UI before the relay starts)
        if (isSessionCreated) {
            rotOut[0] = session.localPlayer.rotationPitch
            rotOut[1] = session.localPlayer.rotationYaw
            rotCtx[0] = rotOut[0]; rotCtx[1] = rotOut[1]
        }
    }

    override fun onDisabled() {
        super.onDisabled()
        targets.clear()
        tickCtr = 0
        resetPerTargetState()
    }

    // =======================================================================
    // support phases
    // =======================================================================

    private fun findTridentSlot(): Int {
        val inventory = session.localPlayer.inventory
        return inventory.searchForItemInHotbar { it.definition?.identifier == "minecraft:trident" } ?: -1
    }

    private fun weaponScore(identifier: String?): Float = when (identifier) {
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

    private fun findBestWeaponSlot(tgt: TData): Int {
        val inventory = session.localPlayer.inventory
        var best = 0f
        var slot = -1
        val oldSlot = inventory.heldItemSlot
        for (i in 0..8) {
            val score = weaponScore(inventory.content[i].definition?.identifier)
            if (score > best) { best = score; slot = i }
        }
        if (slot == -1) slot = oldSlot
        return slot
    }

    private fun updateTargets(localPlayer: com.retrivedmods.wclient.game.entity.LocalPlayer) {
        targets.clear()
        for (entity in session.level.entityMap.values) {
            if (entity === localPlayer) continue
            val isPlayer = entity is Player
            val isMob = entity is EntityUnknown && entity !is Item &&
                entity.identifier in com.retrivedmods.wclient.game.entity.MobList.mobTypes && !isPlayer
            if (!isPlayer && !(filterFlag && isMob)) continue
            val dist = entity.distance(localPlayer.posX, localPlayer.posY, localPlayer.posZ)
            if (range <= dist) continue
            targets.add(entity)
        }
        targets.sortBy { it.distance(localPlayer.posX, localPlayer.posY, localPlayer.posZ) }
    }

    private fun attackTarget(tgt: TData) {
        val localPlayer = session.localPlayer
        if (hurttimeCheck && tgt.hurt > 8) return

        val entity = session.level.entityMap[tgt.entity.runtimeEntityId] ?: return
        repeat(multiplier) {
            if (hitChanceGate && ((Math.random() * 100).toInt() + 1) > hitChance) return@repeat
            if (!noSwing) localPlayer.swing()
            localPlayer.attack(entity)
            atkCount++
        }
    }

    // =======================================================================
    // onNormalTick — 5 phases
    // =======================================================================

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
                lastInput = packet.inputData
                if (isEnabled) tick(packet)
                spoofRotation(packet)
            }

            is MovePlayerPacket -> {
                if (isEnabled && targets.isNotEmpty() && rotationMode >= 1 && rotationMode != 2 &&
                    packet.runtimeEntityId == session.localPlayer.runtimeEntityId &&
                    rotOut[0].isFinite() && rotOut[1].isFinite()
                ) {
                    packet.rotation = Vector3f.from(rotOut[0], rotOut[1], rotOut[1])
                }
            }
        }
    }

    private fun tick(packet: PlayerAuthInputPacket) {
        // defensive: a handler bug must never take the client down — log and skip the tick
        runCatching { tickInner(packet) }.onFailure {
            println("Plus999 tick error: ${it.stackTraceToString()}")
        }
    }

    private fun tickInner(packet: PlayerAuthInputPacket) {
        val localPlayer = session.localPlayer
        gTick++

        // FASE sky: voxel scan -> skipped on relay (skyBlocked always false; documented)
        val skyIsBlocked = false

        // FASE wep: trident
        var newSlot = -1
        if (!skyIsBlocked && rainMode && raining) newSlot = findTridentSlot()
        if (newSlot == -1 && autoTrident &&
            packet.inputData.contains(PlayerAuthInputData.AUTO_JUMPING_IN_WATER)
        ) newSlot = findTridentSlot()

        // FASE find
        updateTargets(localPlayer)

        tickCtr++
        if (targets.isEmpty()) return
        if (tickCtr < interval) return

        // weapon fallback (Switch > 0)
        val tgtSnap = snapshot(targets[0])
        if (newSlot == -1 && switchMode > 0) newSlot = findBestWeaponSlot(tgtSnap)

        // weapon switch execution
        val curSlot = localPlayer.inventory.heldItemSlot
        var didSwitch = false
        if (newSlot != -1 && newSlot != curSlot) {
            // Full (1): report without restore; Silent (2): report + restore
            session.serverBound(MobEquipmentPacket().apply {
                runtimeEntityId = localPlayer.runtimeEntityId
                item = localPlayer.inventory.content[newSlot]
                inventorySlot = newSlot
                hotbarSlot = newSlot
            })
            didSwitch = true
        }

        // FASE rot: base aim (all modes)
        val tgt = targets[0]
        val dx0 = tgt.posX - localPlayer.posX
        val dy0 = tgt.posY - localPlayer.posY
        val dz0 = tgt.posZ - localPlayer.posZ
        val dist0 = sqrt(dx0 * dx0 + dy0 * dy0 + dz0 * dz0)
        var pitch = rotOut[0]
        var yaw = rotOut[1]
        if (dist0 > 0.001f) {
            pitch = asin(dy0 / dist0) * -57.295776f
            yaw = -atan2(dx0, dz0) * 57.295776f
            val wraps = floatArrayOf(pitch); val wrapy = floatArrayOf(yaw)
            wrapRot(wraps, wrapy)
            pitch = wraps[0]; yaw = wrapy[0]
        }
        rotOut[0] = pitch; rotOut[1] = yaw

        // dispatch (each heavy handler is isolated: a failure skips the rotation
        // update for this tick but never escapes the tick wrapper)
        val snap = snapshot(tgt)
        runCatching {
            when (rotationMode) {
                2 -> { // Strafe — inline: full temporal sway
                    val t = gameTimeSec() * 0.1f
                    rotOut[0] = pitch + sin(t) * 10f
                    rotOut[1] = yaw + cos(t) * 10f
                    rotCtx[0] = rotOut[0]; rotCtx[1] = rotOut[1]
                }
                3 -> rotFrontStrafe(snap, packet)
                4 -> rotAirHvHPro(snap)
                5 -> rotAdaptive(snap)
                6 -> rotApex(snap)
                7 -> rotSpectre(snap)
                8 -> rotAegis(snap)
                9 -> rotUnified(snap)
                else -> {} // None/Silent: base aim only
            }
        }.onFailure {
            println("Plus999 rotation mode $rotationMode error: ${it.stackTraceToString()}")
        }

        // FASE atk
        runCatching {
            if (targetMode == 0) {
                attackTarget(snap)
            } else {
                for (t in targets) attackTarget(snapshot(t))
            }
        }.onFailure {
            println("Plus999 attack error: ${it.stackTraceToString()}")
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

    // =======================================================================
    // Handler FrontStrafe (modo 3) — 0x1800C0D30
    // =======================================================================

    private fun rotFrontStrafe(tgt: TData, packet: PlayerAuthInputPacket) {
        val myPos = V3(session.localPlayer.posX, session.localPlayer.posY, session.localPlayer.posZ)
        val tPos = V3(tgt.posX, tgt.posY, tgt.posZ)
        val tVel = V3(tgt.velX, tgt.velY, tgt.velZ)

        val dx = tPos.x - myPos.x; val dy = tPos.y - myPos.y; val dz = tPos.z - myPos.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        val speed = sqrt(tVel.x * tVel.x + tVel.z * tVel.z)
        val reach = 3.0f

        var pitch = rotOut[0]; var yaw = rotOut[1]

        if (dist <= reach) {
            val moving = packet.inputData.contains(PlayerAuthInputData.UP) ||
                packet.inputData.contains(PlayerAuthInputData.LEFT) ||
                packet.inputData.contains(PlayerAuthInputData.DOWN) ||
                packet.inputData.contains(PlayerAuthInputData.RIGHT)
            if (moving) {
                // MOVING: temporary orbit
                val t = gameTimeSec() * 3.0f
                val r = 0.9f
                val ox = cos(t) * r + (tPos.x - myPos.x)
                val oz = sin(t) * r + (tPos.z - myPos.z)
                val dist2 = sqrt(ox * ox + dy * dy + oz * oz)
                if (dist2 > 0.001f) {
                    pitch = asin(dy / dist2) * -57.295776f
                    yaw = -atan2(ox, oz) * 57.295776f
                }
            } else {
                // STATIONARY: per-target sway with phase = yawNow + deltaYaw
                val yawNow = rotCtx[1]
                val yawPrev = fsYawMap[tgt.entity.runtimeEntityId] ?: yawNow
                var phase = wrap180(yawNow - yawPrev)
                phase = (phase * 1.0f + yawNow) * 0.0174533f
                val px = tPos.x - sin(phase) - myPos.x
                val pz = tPos.z + cos(phase) - myPos.z
                val dist2 = sqrt(px * px + dy * dy + pz * pz)
                if (dist2 > 0.001f) {
                    pitch = asin(dy / dist2) * -57.295776f
                    yaw = -atan2(px, pz) * 57.295776f
                }
            }
        } else {
            // FAR: linear prediction with distance-proportional lead
            var done = false
            if (speed > 0.1f) {
                val dot = (dx / dist) * tVel.x + (dy / dist) * tVel.y + (dz / dist) * tVel.z
                if (dot > 0f) {
                    val k = 0.125f
                    val leadX = 2.5f
                    val px = tPos.x + tVel.x * (dist * k) + tVel.x * leadX
                    val pz = tPos.z + tVel.z * (dist * k)
                    val py = tPos.y
                    val out = floatArrayOf(pitch, yaw)
                    if (calcAngle(myPos, V3(px, py, pz), out)) {
                        val wrapp = floatArrayOf(out[0]); val wrapy = floatArrayOf(out[1])
                        wrapRot(wrapp, wrapy)
                        pitch = wrapp[0]; yaw = wrapy[0]
                    }
                    done = true
                }
            }
            if (!done) {
                val out = floatArrayOf(pitch, yaw)
                if (calcAngle(myPos, tPos, out)) {
                    val wrapp = floatArrayOf(out[0]); val wrapy = floatArrayOf(out[1])
                    wrapRot(wrapp, wrapy)
                    pitch = wrapp[0]; yaw = wrapy[0]
                }
            }
        }

        val wrapp = floatArrayOf(pitch); val wrapy = floatArrayOf(yaw)
        wrapRot(wrapp, wrapy)
        rotOut[0] = wrapp[0]; rotOut[1] = wrapy[0]
        rotCtx[0] = rotOut[0]; rotCtx[1] = rotOut[1]
        fsYawMap[tgt.entity.runtimeEntityId] = rotOut[1]
    }

    // =======================================================================
    // Handler AirHvH Pro (modo 4) — 0x1800C1F30
    // =======================================================================

    private fun airYawDeltaVariance(st: AirHvHState): Float {
        if (st.yawRing.count < 3) return 999.0f
        val d = ArrayList<Float>()
        for (i in 1 until st.yawRing.count) {
            var delta = st.yawRing.at(i) - st.yawRing.at(i - 1)
            if (delta > 180.0f) delta += -360.0f
            if (delta < -180.0f) delta += 360.0f
            d.add(delta)
        }
        val n = d.size.toFloat()
        var mean = 0f
        for (v in d) mean += v
        mean /= n
        var `var` = 0f
        for (v in d) { val e = v - mean; `var` += e * e }
        `var` /= n
        return `var`
    }

    private fun airCountReversals(st: AirHvHState): Int {
        if (st.posRing.count < 4) return 0
        var reversals = 0
        var state = 0.0f
        var i = 0
        while (i + 2 < st.posRing.count) {
            val p0 = st.posRing.at(i)
            val p1 = st.posRing.at(i + 1)
            val p2 = st.posRing.at(i + 2)
            val acc = ((p2.z - p1.z) - (p1.z - p0.z)) + ((p2.x - p1.x) - (p1.x - p0.x))
            if (state != 0.0f && acc * state < 0.0f) reversals++
            if (acc == 0.0f) {
                // acc==0 (incl. -0) does NOT change state
            } else {
                state = if (acc > 0.0f) 1.0f else -1.0f
            }
            i++
        }
        return reversals
    }

    private fun airAccelVariance(st: AirHvHState): Float {
        if (st.posRing.count < 4) return 0.0f
        val mags = ArrayList<Float>()
        for (i in 2 until st.posRing.count) {
            val p2 = st.posRing.at(i)
            val p1 = st.posRing.at(i - 1)
            val p0 = st.posRing.at(i - 2)
            val d2x = (p2.x - p1.x) - (p1.x - p0.x)
            val d2y = (p2.y - p1.y) - (p1.y - p0.y)
            val d2z = (p2.z - p1.z) - (p1.z - p0.z)
            val mag = sqrt(d2y.toDouble().pow(2.0) + d2x.toDouble().pow(2.0) + d2z.toDouble().pow(2.0)).toFloat()
            mags.add(mag)
        }
        val n = mags.size.toFloat()
        var mean = 0f
        for (v in mags) mean += v
        mean /= n
        var `var` = 0f
        for (v in mags) { val e = v - mean; `var` += e * e }
        `var` /= n
        return `var`
    }

    private fun rotAirHvHPro(tgt: TData) {
        val m = mAir
        val localPlayer = session.localPlayer

        // 0) initial loads
        val tPos = V3(tgt.posX, tgt.posY, tgt.posZ)
        val tVel = V3(tgt.velX, tgt.velY, tgt.velZ)
        val lPos = V3(localPlayer.posX, localPlayer.posY, localPlayer.posZ)
        val lVel = V3(localPlayer.motionX, localPlayer.motionY, localPlayer.motionZ)
        val tYaw = tgt.yaw

        // 1) push histories
        m.posRing.push(tPos)
        m.yawRing.push(tYaw)

        // 2) trim to 10
        if (m.posRing.count > 10) m.posRing.popFront()
        if (m.yawRing.count > 10) m.yawRing.popFront()

        // 3) default differences
        var vX = tVel.x; var vY = tVel.y; var vZ = tVel.z
        m.scratchVelX = vX
        var accelX = 0f; var accelY = 0f; var accelZ = 0f
        var d3X = 0f; var d3Y = 0f; var d3Z = 0f

        // 4) ring reading (extrapolate)
        if (m.extrapolate && m.posRing.count >= 3) {
            val p1 = m.posRing.at(m.posRing.count - 1)
            val p2 = m.posRing.at(m.posRing.count - 2)
            val p3 = m.posRing.at(m.posRing.count - 3)
            vX = p1.x - p2.x; vY = p1.y - p2.y; vZ = p1.z - p2.z
            m.scratchVelX = vX
            accelX = vX - (p2.x - p3.x)
            accelY = vY - (p2.y - p3.y)
            accelZ = vZ - (p2.z - p3.z)
            if (m.posRing.count >= 4) {
                val p4 = m.posRing.at(m.posRing.count - 4)
                d3X = accelX - ((p2.x - p3.x) - (p3.x - p4.x))
                d3Y = accelY - ((p2.y - p3.y) - (p3.y - p4.y))
                d3Z = accelZ - ((p2.z - p3.z) - (p3.z - p4.z))
            }
        }

        // 5) target yaw delta
        if (m.yawRing.count >= 2) {
            var d = m.yawRing.at(m.yawRing.count - 1) - m.yawRing.at(m.yawRing.count - 2)
            if (d > 180.0f) d += -360.0f
            if (d < -180.0f) d += 360.0f
            m.lastYawDelta = d
        }

        // 6) phase detection
        var phase = 0
        var detMult = 0.5f
        if (m.detect) {
            if (abs(m.lastYawDelta) > 5.0f) {
                val yv = airYawDeltaVariance(m)
                if (3.0f > yv) {
                    detMult = 0.9f
                    phase = 1 + (if (m.lastYawDelta <= 0.0f) 1 else 0)
                }
            }
            if (airCountReversals(m) >= 3 && phase == 0) {
                detMult = 0.8f
                phase = 3
            }
            val hspd = sqrt(vX * vX + vZ * vZ)
            if (abs(vY) > 0.7f * hspd) {
                detMult = 0.85f
                phase = 1 + (if (0.0f <= vY) 1 else 0)
            }
            if (airAccelVariance(m) > 2.0f) {
                detMult = 0.6f
                phase = 6
            }
        }
        m.phase = phase
        m.detMult = detMult

        // 7) target speed and phase factor
        var v2 = vY.toDouble().pow(2.0)
        v2 += vX.toDouble().pow(2.0)
        v2 += vZ.toDouble().pow(2.0)
        var speed = sqrt(v2).toFloat()
        var a2 = accelY.toDouble().pow(2.0)
        a2 += accelX.toDouble().pow(2.0)
        a2 += accelZ.toDouble().pow(2.0)
        val accelMag = sqrt(a2).toFloat()
        speed = (speed * 0.5f) + (speed * 0.5f) // exact binary no-op
        speed += 3.0f
        if (accelMag > 0.5f) speed *= 0.8f
        val fPhase = when (phase) {
            0 -> 1.2f
            1, 2 -> 1.0f
            3 -> 0.7f
            4, 5 -> 0.9f
            6 -> 0.5f
            else -> 1.0f
        }

        // 8) speedLimit and prediction multiplier
        var lim = detMult * m.speedMult * (speed * fPhase)
        if (m.limBonus) lim += m.limBonusAmt / 50.0f
        var mm = if (1.0f <= lim) lim else 1.0f
        mm = if (mm <= 10.0f) mm else 10.0f

        // 9) target position prediction (NaN-preserving *0 multiplies kept)
        var predY = (vY * 0.0f) + tPos.y
        var predZ = (vZ * 0.0f) + tPos.z
        var predX = (mm * m.scratchVelX) + tPos.x
        if (m.extrapolate) {
            val t1 = ((mm * (1.0f / 6.0f)) * mm) * mm
            val t2 = (mm * 0.5f) * mm
            predX = (predX + accelX * t2) + d3X * t1
            predY = ((predY + accelZ * 0.0f) + d3Z * 0.0f) + accelY * 0.0f
            predZ = (accelY * 0.0f + predZ) + d3Y * 0.0f
        }

        // 10) vertical adjustment / lateral orbit
        if (phase == 4) predY -= 0.5f
        else if (phase == 5) predY += 0.5f
        else if (phase == 1 || phase == 2) {
            var ang = atan2(predZ - m.lastTgtPosZ, predX - m.lastTgtPosX)
            ang = if (phase == 1) ang + 0.3f else ang - 0.3f
            predX += 0.5f * cos(ang)
            predZ += 0.5f * sin(ang)
        }
        predY += vertOffset

        // 11) subtract local position (y/z *0 quirks kept)
        predX -= lPos.x + ((mm * 0.3f) * lVel.x)
        predY -= lPos.y + (lVel.y * 0.0f)
        predZ -= lPos.z + (lVel.z * 0.0f)

        // 12) pitch/yaw
        var d2 = predY.toDouble().pow(2.0)
        d2 += predX.toDouble().pow(2.0)
        d2 += predZ.toDouble().pow(2.0)
        val dist = sqrt(d2).toFloat()
        var pitch = asin(predY / dist) * -57.29578f
        var yaw = -atan2(predX, predZ) * 57.29578f
        pitch = wrapP(pitch)
        yaw = wrapA(yaw)

        // 13) final writes
        rotOut[0] = pitch; rotOut[1] = yaw
        rotCtx[0] = rotOut[0]; rotCtx[1] = rotOut[1]
        m.lastTgtPosX = tPos.x; m.lastTgtPosY = tPos.y
        m.lastTgtPosZ = tPos.z
        m.lastLocalPosX = lPos.x; m.lastLocalPosY = lPos.y
        m.lastLocalPosZ = lPos.z
        m.lastTargetYaw = tYaw
    }

    // =======================================================================
    // Handler Adaptive (modo 5) — 0x1800C3840
    // =======================================================================

    private fun weightedAvgX(alpha: Float, v: List<V3>): V3 {
        val n = v.size
        val base = 1.0f - alpha
        var sumX = 0f; var wsum = 0f
        for (i in 0 until n) {
            val w = base.pow((n - 1 - i).toFloat())
            wsum += w
            sumX += w * v[i].x
        }
        return if (wsum > 0.0f) V3(sumX / wsum, 0f, 0f) else V3(sumX, 0f, 0f)
    }

    private fun adaptiveClassify(localPlayer: com.retrivedmods.wclient.game.entity.LocalPlayer, tgt: TData): Int {
        // (1) water under the LP -> 3 (relay substitute for the block query)
        if (lastInput.contains(PlayerAuthInputData.AUTO_JUMPING_IN_WATER)) return 3
        // (2) sdkCheck failing -> 2 (entityFlag = onGround predicate)
        if (!onGroundOf(localPlayer) || !tgt.onGround) return 2
        // (3) dist >= 4 -> 1 else 0
        val dy = (localPlayer.posY - tgt.posY).toDouble()
        val dx = (localPlayer.posX - tgt.posX).toDouble()
        val dz = (localPlayer.posZ - tgt.posZ).toDouble()
        val dist = sqrt(dy * dy + dx * dx + dz * dz).toFloat()
        return if (dist >= 4.0f) 1 else 0
    }

    private fun adaptiveOffsets(
        st: AdaptiveState,
        oVel: V3, oAcc: V3, oJerk: V3, oYawVel: FloatArray
    ) {
        oVel.set(V3()); oAcc.set(V3()); oJerk.set(V3())
        oYawVel[0] = 0.0f
        if (st.posRing.count >= 2) {
            val vels = ArrayList<V3>()
            for (i in 1 until st.posRing.count) {
                val cu = st.posRing.at(i)
                val pv = st.posRing.at(i - 1)
                vels.add(V3(cu.x - pv.x, cu.y - pv.y, cu.z - pv.z))
            }
            oVel.set(weightedAvgX(0.3f, vels))
            if (vels.size >= 2) {
                val accs = ArrayList<V3>()
                for (i in 1 until vels.size)
                    accs.add(V3(vels[i].x - vels[i - 1].x, vels[i].y - vels[i - 1].y, vels[i].z - vels[i - 1].z))
                oAcc.set(weightedAvgX(0.3f, accs))
                if (accs.size >= 2) {
                    val jerks = ArrayList<V3>()
                    for (i in 1 until accs.size)
                        jerks.add(V3(accs[i].x - accs[i - 1].x, accs[i].y - accs[i - 1].y, accs[i].z - accs[i - 1].z))
                    oJerk.set(weightedAvgX(0.3f, jerks))
                }
            }
            if (st.yawRing.count >= 2) {
                val dy = ArrayList<Float>()
                for (i in 1 until st.yawRing.count) {
                    var d = st.yawRing.at(i) - st.yawRing.at(i - 1)
                    if (d > 180.0f) d -= 360.0f
                    if (d < -180.0f) d += 360.0f
                    dy.add(d)
                }
                val n = dy.size
                if (n != 0) {
                    var acc = oYawVel[0]
                    var wsum = 0f
                    for (i in 0 until n) {
                        val w = 0.6f.pow((n - 1 - i).toFloat())
                        acc += w * dy[i]
                        wsum += w
                    }
                    oYawVel[0] = acc
                    if (wsum > 0.0f) oYawVel[0] = acc / wsum
                }
            }
        }
    }

    private fun predictTarget(
        st: AdaptiveState, out: V3, tp: V3,
        vel: V3, acc: V3, jerk: V3, l: Float, cls: Int, yawVel: Float, curYaw: Float
    ) {
        val t1 = (l * 0.5f) * l * acc.x
        out.x = vel.x * l + t1
        out.y = tp.y + vel.y * 0.0f + acc.y * 0.0f
        out.z = tp.z + vel.z * 0.0f + acc.z * 0.0f
        if (cls == 2) {
            val j = ((l * (1.0f / 6.0f)) * l) * l * jerk.x
            out.x += j
            out.z += jerk.z * 0.0f
            out.y += jerk.y * 0.0f
            if (abs(yawVel) > 6.0f) {
                val n = st.yawRing.count
                if (n >= 5) {
                    var sum = 0.0f
                    for (i in 1 until n) {
                        var d = st.yawRing.at(i) - st.yawRing.at(i - 1)
                        if (d > 180.0f) d += -360.0f
                        if (d < -180.0f) d += 360.0f
                        d -= yawVel
                        sum += d * d
                    }
                    val mean = sum / (n - 1)
                    if (mean < 5.0f) {
                        val rad = (curYaw + (l * yawVel) * 0.5f) * 0.0174532924f
                        val hs = sqrt(vel.z * vel.z + vel.x * vel.x) * 0.35f
                        out.x += cos(rad) * hs
                        out.z += sin(rad) * hs
                    }
                }
            }
            if (abs(vel.y) > 0.7f * sqrt(vel.x * vel.x + vel.z * vel.z))
                out.y += if (vel.y > 0.0f) 0.4f else -0.4f
        } else if (cls == 0) {
            if (abs(yawVel) > 5.0f) {
                val t = 1.0f / (abs(yawVel) / 15.0f + 1.0f)
                out.x = tp.x + (out.x - tp.x) * t
                out.z = tp.z + (out.z - tp.z) * t
            }
        } else if (cls == 3) {
            out.x = tp.x + 0.6f * (out.x - tp.x)
            out.y = tp.y + 0.0f * (out.y - tp.y)
            out.z = tp.z + 0.0f * (out.z - tp.z)
        }
    }

    private fun rotUnified(tgt: TData) {
        val localPlayer = session.localPlayer
        val ctx = LynxAuraXRots.Ctx(50f, 0.9f, 8f, 1f, 3f, 0.7f, 1.5f, 0.1f, 0.1f, 8, 20, true, 0f)
        ctx.rotPitch = rotCtx[0]
        ctx.rotYaw = rotCtx[1]
        val env = LynxAuraXRots.Env(
            localPlayer.posX, localPlayer.posY, localPlayer.posZ,
            localPlayer.motionX, localPlayer.motionY, localPlayer.motionZ,
            false, false, false, false, false, gameTimeSec()
        )
        LynxAuraXRots.unified(
            ctx,
            LynxAuraXRots.Target(tgt.posX, tgt.posY, tgt.posZ, tgt.velX, tgt.velY, tgt.velZ, tgt.yaw, tgt.width, tgt.height),
            env
        )
        rotOut[0] = ctx.rotPitch
        rotOut[1] = ctx.rotYaw
        rotCtx[0] = ctx.rotPitch
        rotCtx[1] = ctx.rotYaw
    }

    private fun rotAdaptive(tgt: TData) {
        val localPlayer = session.localPlayer
        val ka = mAdaptive

        val lmPos = V3(localPlayer.posX, localPlayer.posY, localPlayer.posZ)
        val lmVel = V3(localPlayer.motionX, localPlayer.motionY, localPlayer.motionZ)
        val tmPos = V3(tgt.posX, tgt.posY, tgt.posZ)
        val tmVel = V3(tgt.velX, tgt.velY, tgt.velZ)
        val tgtYaw = tgt.yaw

        // target switch
        if (tgt.entity !== ka.lastTarget) {
            ka.posRing.clear(); ka.yawRing.clear(); ka.hitRing.clear()
            ka.dynMult = 1.0f
            ka.lastTarget = tgt.entity
        }

        // push + trim to 16 (popFront when count > 15)
        ka.posRing.push(tmPos)
        ka.yawRing.push(tgtYaw)
        if (ka.posRing.count > 15) ka.posRing.popFront()
        if (ka.yawRing.count > 15) ka.yawRing.popFront()

        val cls = adaptiveClassify(localPlayer, tgt)

        val vel = V3(); val acc = V3(); val jerk = V3()
        val yawVelBox = floatArrayOf(0.0f)
        adaptiveOffsets(ka, vel, acc, jerk, yawVelBox)
        val yawVel = yawVelBox[0]

        val dMy = tmVel.y - lmVel.y; val dMx = tmVel.x - lmVel.x; val dMz = tmVel.z - lmVel.z
        val motDist = sqrt(dMy.toDouble().pow(2.0) + dMx.toDouble().pow(2.0) + dMz.toDouble().pow(2.0)).toFloat()
        val accMag = sqrt(acc.y.toDouble().pow(2.0) + acc.x.toDouble().pow(2.0) + acc.z.toDouble().pow(2.0)).toFloat()

        val clsFactor: Float = when {
            cls == 0 -> 1.5f
            cls == 1 -> {
                val dy = (lmPos.y - tmPos.y).toDouble()
                val dx = (lmPos.x - tmPos.x).toDouble()
                val dz = (lmPos.z - tmPos.z).toDouble()
                sqrt(dy.pow(2.0) + dx.pow(2.0) + dz.pow(2.0)).toFloat() * 0.1f + 2.5f
            }
            cls == 2 -> 3.5f
            cls == 3 -> 1.0f
            else -> 2.0f
        }

        var w = minOf(0.4f * motDist, 2.0f)
        w = (w + 1.0f) * clsFactor
        w *= 1.0f / (accMag * 1.5f + 1.0f)
        w *= ka.dynMult
        val l = if (w > 8.0f) 8.0f else (if (w >= 0.5f) w else 0.5f)

        val curX = lmPos.x + (l * 0.2f) * lmVel.x
        val curY = lmPos.y + 0.0f * lmVel.y
        val curZ = lmPos.z + 0.0f * lmVel.z

        val tp = tmPos
        val pred = V3()
        predictTarget(ka, pred, tp, vel, acc, jerk, l, cls, yawVel, tgtYaw)

        val dx = pred.x - curX; val dy = pred.y - curY; val dz = pred.z - curZ
        val dist = sqrt(dy.toDouble().pow(2.0) + dx.toDouble().pow(2.0) + dz.toDouble().pow(2.0)).toFloat()
        var pitch = asin(dy / dist) * -57.29578f
        pitch = wrapP(pitch)
        var yaw = -atan2(dx, dz) * 57.29578f
        yaw = wrapA(yaw)

        val lpPitch = rotCtx[0]; val lpYaw = rotCtx[1]
        var yawDelta = yaw - lpYaw
        if (yawDelta > 180.0f) yawDelta += -360.0f
        if (yawDelta < -180.0f) yawDelta += 360.0f
        val pitchDelta = pitch - lpPitch
        val angDist = sqrt(pitchDelta * pitchDelta + yawDelta * yawDelta)

        val turn = if (angDist > 30.0f) 0.95f else (if (angDist > 10.0f) 0.8f else 0.55f)
        val factor: Float = when {
            cls == 0 -> maxOf(0.85f, turn)
            cls == 2 -> maxOf(0.90f, turn)
            else -> turn
        }

        val newPitch = lpPitch + pitchDelta * factor
        val newYaw = lpYaw + yawDelta * factor
        rotOut[0] = newPitch; rotOut[1] = newYaw
        rotCtx[0] = rotOut[0]; rotCtx[1] = rotOut[1]

        // hit ring: hurtTime in {1,2}
        val hurtTime = tgt.hurt
        if (hurtTime == 1 || hurtTime == 2) ka.hitRing.push(1)
        if (ka.hitRing.count > 30) ka.hitRing.popFront()
        if (ka.hitRing.count >= 5) {
            var hits = 0
            for (k in 0 until ka.hitRing.count) if (ka.hitRing.at(k) != 0) hits++
            ka.dynMult = (hits.toFloat() / ka.hitRing.count) * 0.8f + 0.6f
        }
    }

    // =======================================================================
    // Handler Apex (modo 6) — 0x1800C4F80
    // =======================================================================

    private fun apexPredict(st: ApexState, outVel: V3, outAcc: V3, outJerk: V3, outAngVel: FloatArray) {
        outAngVel[0] = 0.0f
        outJerk.set(V3())
        outAcc.set(V3())
        outVel.set(V3())

        val npos = st.posHist.count
        if (npos >= 2) {
            val d1 = ArrayList<V3>()
            for (i in 1 until npos) {
                val a = st.posHist.at(i)
                val b = st.posHist.at(i - 1)
                d1.add(V3(a.x - b.x, a.y - b.y, a.z - b.z))
            }
            outVel.set(weightedAvgX(0.35f, d1))
            val n1 = d1.size
            if (n1 >= 2) {
                val d2 = ArrayList<V3>()
                for (i in 1 until n1)
                    d2.add(V3(d1[i].x - d1[i - 1].x, d1[i].y - d1[i - 1].y, d1[i].z - d1[i - 1].z))
                outAcc.set(weightedAvgX(0.35f, d2))
                val n2 = d2.size
                if (n2 >= 2) {
                    val d3 = ArrayList<V3>()
                    for (i in 1 until n2)
                        d3.add(V3(d2[i].x - d2[i - 1].x, d2[i].y - d2[i - 1].y, d2[i].z - d2[i - 1].z))
                    val n3 = d3.size
                    var sumX = 0f; var sumW = 0f
                    for (j in 0 until n3) {
                        val wj = 0.75f.pow((n3 - j - 1).toFloat())
                        sumW += wj
                        sumX += wj * d3[j].x
                    }
                    outJerk.set(if (sumW > 0.0f) V3((1.0f / sumW) * sumX, 0f, 0f) else V3(sumX, 0f, 0f))
                }
            }
        }
        val nrot = st.rotHist.count
        if (nrot >= 2) {
            val dr = ArrayList<Float>()
            for (i in 1 until nrot) {
                var d = st.rotHist.at(i) - st.rotHist.at(i - 1)
                if (d > 180.0f) d -= 360.0f
                if (d < -180.0f) d += 360.0f
                dr.add(d)
            }
            var accv = outAngVel[0]; var sumW = 0.0f
            for (j in dr.indices) {
                val wj = 0.65f.pow((dr.size - j - 1).toFloat())
                accv += wj * dr[j]
                sumW += wj
                outAngVel[0] = accv
            }
            if (sumW > 0.0f) outAngVel[0] = accv / sumW
        }
    }

    private fun apexBlend(a: V3, b: V3, c: V3): Float {
        val lenBd = sqrt(b.x.toDouble().pow(2.0) + b.y.toDouble().pow(2.0) + b.z.toDouble().pow(2.0))
        val lenAd = sqrt(a.x.toDouble().pow(2.0) + a.y.toDouble().pow(2.0) + a.z.toDouble().pow(2.0))
        val lenAf = lenAd.toFloat(); val lenBf = lenBd.toFloat()
        val ratio = if (lenBf > 0.5f) (lenAf / lenBf) else (lenAf + lenAf)
        var k = clamp3(ratio, 0.5f, 15.0f)
        for (it in 0 until 3) {
            k = clamp3(k, 0.5f, 15.0f)
            val bx = b.x + k * c.x
            val denom = bx * bx + b.y * b.y + b.z * b.z
            if (abs(denom) > 0.001f) {
                var g = ((0.5f * k * k) * c.x + (a.x + k * b.x)) * bx
                g = (g + a.y * b.y) + a.z * b.z
                k -= g / denom
            }
        }
        return clamp3(k, 0.5f, 15.0f)
    }

    private fun predictAimPoint(st: ApexState, out: V3, target: TData) {
        val vx = target.velX; val vy = target.velY; val vz = target.velZ
        val n = st.posHist.count
        if (n < 2) { out.set(V3(vx, vy, vz)); return }
        val last = st.posHist.at(n - 1)
        val prev = st.posHist.at(n - 2)
        val dx1 = last.x - prev.x; val dy1 = last.y - prev.y; val dz1 = last.z - prev.z
        val dist1 = sqrt(dy1.toDouble().pow(2.0) + dx1.toDouble().pow(2.0) + dz1.toDouble().pow(2.0)).toFloat()
        val dist2 = sqrt((vy - dy1).toDouble().pow(2.0) + (vx - dx1).toDouble().pow(2.0) + (vz - dz1).toDouble().pow(2.0)).toFloat()
        val distV = sqrt(vy.toDouble().pow(2.0) + vx.toDouble().pow(2.0) + vz.toDouble().pow(2.0)).toFloat()
        val den = maxOf(if (0.01f <= dist1) dist1 else 0.01f, distV)
        val t = minOf(1.0f, dist2 / den)
        val blendK = (1.0f - t) * 0.7f
        val nx = (dx1 + (vx - dx1) * blendK) * 0.8f + st.dirAcc.x * 0.2f
        val ny = (dy1 + (vy - dy1) * blendK) * 0.8f + st.dirAcc.y * 0.2f
        val nz = (dz1 + (vz - dz1) * blendK) * 0.8f + st.dirAcc.z * 0.2f
        st.dirAcc.set(V3(nx, ny, nz))
        out.set(st.dirAcc)
    }

    private fun rotApex(tgt: TData) {
        val localPlayer = session.localPlayer
        val m = mApex

        val tgtX = tgt.posX; val tgtY = tgt.posY; val tgtZ = tgt.posZ
        val lpX = localPlayer.posX; val lpY = localPlayer.posY; val lpZ = localPlayer.posZ
        val eyeY = lpY + 1.62f
        val lpVx = localPlayer.motionX; val lpVy = localPlayer.motionY; val lpVz = localPlayer.motionZ
        val tgtYaw = tgt.yaw
        val tVel = V3(tgt.velX, tgt.velY, tgt.velZ)

        // hurt rising-edge counters
        val tHurt = tgt.hurt
        val lHurt = hurtTimeOf(localPlayer)
        if (tHurt > 0 && m.prevTgtHurt == 0) { m.cntTgtHurt++; m.cntLpHurt = 0 }
        if (lHurt > 0 && m.prevLpHurt == 0) { m.cntLpHurt++; m.cntTgtHurt = 0 }
        m.prevTgtHurt = tHurt
        m.prevLpHurt = lHurt

        // hurt multiplier (written BEFORE the target switch, like the binary)
        val hurtMult: Float = when {
            m.cntTgtHurt >= 2 -> 1.0f + minOf(0.3f, m.cntTgtHurt * 0.08f)
            m.cntLpHurt >= 2 -> 1.0f - minOf(0.35f, m.cntLpHurt * 0.1f)
            else -> 1.0f
        }
        m.hurtMult = hurtMult

        // target switch
        if (tgt.entity !== m.lastTarget) {
            m.posHist.clear(); m.rotHist.clear(); m.hitRing.clear()
            m.dynMult = 1.0f
            m.dirAcc.set(V3())
            m.snapDone = false
            m.cntTgtHurt = 0; m.cntLpHurt = 0
            m.prevTgtHurt = 0; m.prevLpHurt = 0
            m.hurtMult = 1.0f // overwrites the value just written (binary quirk)
            m.lastTarget = tgt.entity
            // maxHistLen is NOT reset
        }

        // push sample + trim with the PREVIOUS tick's maxLen
        m.posHist.push(V3(tgtX, tgtY, tgtZ))
        m.rotHist.push(tgtYaw)
        while (m.posHist.count > m.maxHistLen) m.posHist.popFront()
        while (m.rotHist.count > m.maxHistLen) m.rotHist.popFront()

        // maxHistLen by relative speed
        val dvx = tVel.x - lpVx; val dvy = tVel.y - lpVy; val dvz = tVel.z - lpVz
        val relSpeed = sqrt(dvy.toDouble().pow(2.0) + dvx.toDouble().pow(2.0) + dvz.toDouble().pow(2.0)).toFloat()
        m.maxHistLen = if (relSpeed > 1.5f) 8 else (if (relSpeed > 0.5f) 12 else 18)

        // predicted aim point (dirAcc)
        val predHead = V3()
        predictAimPoint(m, predHead, tgt)

        // apex_predict
        val vel = V3(); val acc = V3(); val jerk = V3()
        val angVelBox = floatArrayOf(0.0f)
        apexPredict(m, vel, acc, jerk, angVelBox)
        val angVel = angVelBox[0]
        // vel discarded (binary copies acc over the vel slot)

        // apex_blend: interception time k
        val a = V3(tgtX - lpX, tgtY - lpY, tgtZ - lpZ)
        val b = V3(predHead.x - lpVx, predHead.y - lpVy, predHead.z - lpVz)
        val kRaw = apexBlend(a, b, acc)
        var k = kRaw * ((0.3f * m.dynMult) + 0.7f)
        k *= m.hurtMult
        val k2 = clamp3(k, 0.5f, 15.0f)

        // predicted point (Taylor)
        var px = tgtX + k2 * predHead.x + (0.5f * k2 * k2) * acc.x
        var py = tgtY + k2 * predHead.y
        var pz = tgtZ + k2 * predHead.z

        if (!tgt.onGround) {
            px += jerk.x * ((k2 * 0.16666667f) * k2 * k2)
            py -= (k2 * 0.04f) * k2

            if (abs(angVel) > 6.0f && m.rotHist.count >= 5) {
                var sum = 0.0f
                for (i in 1 until m.rotHist.count) {
                    var d = m.rotHist.at(i) - m.rotHist.at(i - 1)
                    if (d > 180.0f) d += -360.0f
                    if (d < -180.0f) d += 360.0f
                    d -= angVel
                    sum += d * d
                }
                val mse = sum / (m.rotHist.count - 1)
                if (mse < 5.0f) {
                    val ang = (tgtYaw + k2 * angVel * 0.5f) * 0.0174532924f
                    val r = 0.3f * sqrt(predHead.x * predHead.x + predHead.z * predHead.z)
                    px += cos(ang) * r
                    pz += sin(ang) * r
                }
            }
        }

        // predicted AABB and aim point
        val halfW = tgt.width * 0.5f
        val halfD = tgt.width * 0.5f
        val bbH = tgt.height
        val lx = lpX + k2 * 0.3f * lpVx
        val lly = eyeY
        val llz = lpZ
        val yMax = py + bbH - 0.15f

        val ax = clamp3(lx, px - halfW, px + halfW); val dx = ax - lx
        val ay = clamp3(lly, py, yMax); val dy = ay - lly
        val az = clamp3(llz, pz - halfD, pz + halfD); val dz = az - llz

        val dist = sqrt(dy.toDouble().pow(2.0) + dx.toDouble().pow(2.0) + dz.toDouble().pow(2.0)).toFloat()

        var pitch = asin(dy / dist) * -57.29578f
        var yaw = -atan2(dx, dz) * 57.29578f
        pitch = wrapP(pitch)
        yaw = wrapA(yaw)

        if (pitch.isNaN() || yaw.isNaN()) {
            // FALLBACK: recompute at the target's CURRENT position
            val fdx = tgtX - lpX
            val fdy = tgtY - eyeY
            val fdz = tgtZ - lpZ
            val fdist = sqrt(fdy.toDouble().pow(2.0) + fdx.toDouble().pow(2.0) + fdz.toDouble().pow(2.0)).toFloat()
            pitch = asin(fdy / fdist) * -57.29578f
            yaw = -atan2(fdx, fdz) * 57.29578f
            pitch = wrapP(pitch)
            yaw = wrapA(yaw)
            if (pitch.isNaN() || yaw.isNaN()) return
        }

        // blend toward the LP rotCache
        val curPitch = rotCtx[0]; val curYaw = rotCtx[1]
        val pitchDelta = pitch - curPitch
        var yawDelta = yaw - curYaw
        if (yawDelta > 180.0f) yawDelta += -360.0f
        if (yawDelta < -180.0f) yawDelta += 360.0f

        val angDist = sqrt(pitchDelta * pitchDelta + yawDelta * yawDelta)
        val base = when {
            !m.snapDone || angDist > 45.0f -> { m.snapDone = true; 1.0f }
            angDist > 15.0f -> 0.92f
            angDist > 5.0f -> 0.8f
            else -> 0.65f
        }

        var factor = base * ((m.dynMult * 0.15f) + 0.85f)
        if (m.cntLpHurt >= 3) factor = maxOf(0.93f, factor)
        factor = clamp3(factor, 0.5f, 1.0f)

        val newPitch = curPitch + factor * pitchDelta
        val newYaw = curYaw + factor * yawDelta

        rotOut[0] = newPitch; rotOut[1] = newYaw
        rotCtx[0] = rotOut[0]; rotCtx[1] = rotOut[1]

        // hit ring and dynamic multiplier
        val ht = tgt.hurt
        if (ht == 1 || ht == 2) m.hitRing.push(1)
        if (m.hitRing.count > 30) m.hitRing.popFront()
        if (m.hitRing.count >= 5) {
            var count = 0
            for (i in 0 until m.hitRing.count) if (m.hitRing.at(i) != 0) count++
            m.dynMult = (count.toFloat() / m.hitRing.count) * 0.8f + 0.6f
        }
    }

    // =======================================================================
    // Handler Spectre (modo 7) — 0x1800C5F70
    // =======================================================================

    private fun rotSpectre(tgt: TData) {
        val localPlayer = session.localPlayer
        val st = mSpectre

        // 1) positions
        val pmyX = localPlayer.posX; val pmyY = localPlayer.posY; val pmyZ = localPlayer.posZ
        val qmyX = localPlayer.motionX; val qmyY = localPlayer.motionY; val qmyZ = localPlayer.motionZ
        val ptX = tgt.posX; val ptY = tgt.posY; val ptZ = tgt.posZ
        val qtX = tgt.velX; val qtY = tgt.velY; val qtZ = tgt.velZ
        val eyeY = pmyY + 1.62f
        val tYaw = tgt.yaw
        val tHurt = tgt.hurt
        val mHurt = hurtTimeOf(localPlayer)

        // 2) hit streaks and reach multiplier
        if (tHurt > 0 && st.lastTargetHurtTime == 0) { st.myHitStreak++; st.targetHitStreak = 0 }
        if (mHurt > 0 && st.lastMyHurtTime == 0) { st.targetHitStreak++; st.myHitStreak = 0 }
        st.lastTargetHurtTime = tHurt; st.lastMyHurtTime = mHurt
        val reachMult: Float = when {
            st.myHitStreak >= 2 -> 1.0f + minOf(0.3f, st.myHitStreak * 0.08f)
            st.targetHitStreak >= 2 -> 1.0f - minOf(0.35f, st.targetHitStreak * 0.1f)
            else -> 1.0f
        }
        st.reachMult = reachMult

        // 3) target switch reset
        if (tgt.entity !== st.lastTarget) {
            st.posRing.clear(); st.yawRing.clear()
            st.velEMA.set(V3())
            st.spdEMA = 2.0f
            st.hitRatio = 1.0f
            st.hitRing.clear()
            st.firstTickDone = false
            st.myHitStreak = 0; st.targetHitStreak = 0
            st.lastTargetHurtTime = 0
            st.reachMult = 1.0f; st.yawRateEMA = 0.0f
            st.yawAccelEMA = 0.0f
            st.lastTarget = tgt.entity
        }

        // 4) push histories
        st.posRing.push(V3(ptX, ptY, ptZ))
        st.yawRing.push(tYaw)

        // trim to 16
        if (st.posRing.count > 16) st.posRing.popFront()
        if (st.yawRing.count > 16) st.yawRing.popFront()

        var avgX: Float; var avgY: Float; var avgZ: Float

        if (st.posRing.count < 2) {
            avgX = qtX; avgY = qtY; avgZ = qtZ
        } else {
            // 5a) position deltas
            val n = st.posRing.count
            val d = ArrayList<V3>(n - 1)
            for (i in 1 until n) {
                val a = st.posRing.at(i)
                val b = st.posRing.at(i - 1)
                d.add(V3(a.x - b.x, a.y - b.y, a.z - b.z))
            }
            val vX = ArrayList<Float>(); val vY = ArrayList<Float>(); val vZ = ArrayList<Float>()
            for (e in d) { vX.add(e.x); vY.add(e.y); vZ.add(e.z) }

            val medX = medianSorted(vX); val medY = medianSorted(vY); val medZ = medianSorted(vZ)

            val aX = ArrayList<Float>(); val aY = ArrayList<Float>(); val aZ = ArrayList<Float>()
            for (e in d) { aX.add(abs(e.x - medX)); aY.add(abs(e.y - medY)); aZ.add(abs(e.z - medZ)) }
            val madX = medianSorted(aX) * 1.4826f
            val madY = medianSorted(aY) * 1.4826f
            val madZ = medianSorted(aZ) * 1.4826f

            // 5b) robust mean (avgY/avgZ *0 quirks kept)
            var sumX = 0f; var sumY = 0f; var sumZ = 0f; var kept = 0
            for (e in d) {
                var outlier = false
                if (madX > 0.01f) outlier = outlier or (abs(e.x - medX) > madX * 3.0f)
                if (madZ > 0.01f) outlier = outlier or (abs(e.z - medZ) > madZ * 3.0f)
                if (madY > 0.01f && abs(e.y - medY) > madY * 4.5f) continue
                if (outlier) continue
                sumX += e.x; sumY += e.y; sumZ += e.z; kept++
            }
            if (kept > 0) {
                avgX = sumX * (1.0f / kept)
                avgY = sumY * 0.0f
                avgZ = sumZ * 0.0f
            } else {
                avgX = medX; avgY = medY; avgZ = medZ
            }

            // 5c) distance and spdEMA
            val distD = sqrt(avgY.toDouble().pow(2.0) + avgX.toDouble().pow(2.0) + avgZ.toDouble().pow(2.0))
            val distF = distD.toFloat()
            val ema = distF * 0.05f + st.spdEMA * 0.95f
            st.spdEMA = if (distF > ema) distF else ema
        }

        // 6) velocity EMA (all paths)
        st.velEMA.x = 0.9f * avgX + 0.15f * st.velEMA.x
        st.velEMA.y = 0.9f * avgY + 0.15f * st.velEMA.y
        st.velEMA.z = 0.9f * avgZ + 0.15f * st.velEMA.z
        val velX = st.velEMA.x; val velY = st.velEMA.y; val velZ = st.velEMA.z

        // 7) turn detection
        var predX = velX; var predZ = velZ
        if (st.yawRing.count >= 3) {
            var d1 = st.yawRing.at(st.yawRing.count - 1) - st.yawRing.at(st.yawRing.count - 2)
            if (d1 > 180.0f) d1 -= 360.0f
            if (d1 < -180.0f) d1 += 360.0f
            var d0 = st.yawRing.at(st.yawRing.count - 2) - st.yawRing.at(st.yawRing.count - 3)
            if (d0 > 180.0f) d0 -= 360.0f
            if (d0 < -180.0f) d0 += 360.0f
            st.yawRateEMA = 0.7f * d1 + 0.3f * st.yawRateEMA
            st.yawAccelEMA = 0.7f * (d1 - d0) + 0.3f * st.yawAccelEMA
            val rate = st.yawRateEMA; val accel = st.yawAccelEMA
            if (abs(rate) > 2.0f) {
                val ang = (tYaw + rate) * 0.0174532924f
                val scale = maxOf(0.5f, sqrt(velX * velX + velZ * velZ))
                val boostA = cos(ang) * scale
                val boostB = -sin(ang) * scale
                var t = clamp3(abs(rate) / 15.0f, 0.0f, 0.6f)
                if (abs(accel) > 3.0f) t = minOf(0.7f, t + 0.15f)
                predX = velX * (1.0f - t) + boostB * t
                predZ = velZ * (1.0f - t) + boostA * t
            }
        }

        // 8) second differences (y/z *0 quirks kept)
        var sdXh = 0f
        if (st.posRing.count >= 3) {
            val p1 = st.posRing.at(st.posRing.count - 1)
            val p2 = st.posRing.at(st.posRing.count - 2)
            val p3 = st.posRing.at(st.posRing.count - 3)
            sdXh = ((p1.x - p2.x) - (p2.x - p3.x)) * 0.5f
        }

        // 9) raw deltas and rel prediction
        val rawDx = ptX - pmyX
        val rawDy = ptY - pmyY
        val rawDz = ptZ - pmyZ
        val relX2 = predX - qmyX
        val relY2 = velY - qmyY
        val relZ2 = predZ - qmyZ

        // 10) reach
        var dist = sqrt(rawDx.toDouble().pow(2.0) + rawDy.toDouble().pow(2.0) + rawDz.toDouble().pow(2.0)).toFloat()
        var relY3 = 0.0f
        if (dist > 0.01f) {
            relY3 = -((rawDx * relX2) + (rawDy * 0.0f) * relY2 + (rawDz * 0.0f) * relZ2) / dist
        }
        if (dist > 0.01f && relY3 > 0.5f) {
            dist /= relY3
        } else {
            val horiz = sqrt(relY2.toDouble().pow(2.0) + relX2.toDouble().pow(2.0) + relZ2.toDouble().pow(2.0)).toFloat()
            if (horiz > 0.3f) dist /= horiz else dist *= 1.5f
        }
        dist *= st.reachMult
        dist *= (st.hitRatio * 0.3f + 0.7f)
        val r = clamp3(dist, 0.5f, 12.0f)

        // 11) aim point
        val tFlag = tgt.onGround
        var aimX = (0.5f * r * r) * sdXh + (ptX + r * predX)
        val aimYBase = ptY
        val aimZ = ptZ
        val aimYeff = if (tFlag) aimYBase else (aimYBase - 0.04f * r * r)
        val aimY2 = r * (st.spdEMA + 0.5f)
        val aimDX = aimX - ptX
        val aimDY = aimYeff - ptY
        val aimDZ = aimZ - ptZ
        val hReach = sqrt(aimDY.toDouble().pow(2.0) + aimDX.toDouble().pow(2.0) + aimDZ.toDouble().pow(2.0)).toFloat()
        var aimY = aimYBase
        if (hReach > aimY2 && hReach > 0.01f) {
            aimX = ptX + (aimY2 / hReach) * aimDX
            aimY = ptY
        }

        // 12) target box around the aim
        val extX = tgt.width * 0.5f
        val extY = tgt.height
        val extZ = tgt.width * 0.5f
        val loX = aimX - extX; val hiX = aimX + extX
        val loY = aimY; val hiY = aimY + extY - 0.15f
        val loZ = aimZ - extZ; val hiZ = aimZ + extZ
        val ptAx = pmyX + (0.25f * relY2) * qmyX
        val ptAy = eyeY
        val ptAz = pmyZ

        // 13) penetration -> angles
        val penX = clamp3(ptAx, loX, hiX) - ptAx
        val penY = clamp3(ptAy, loY, hiY) - ptAy
        val penZ = clamp3(ptAz, loZ, hiZ) - ptAz
        val penD = sqrt(penY.toDouble().pow(2.0) + penX.toDouble().pow(2.0) + penZ.toDouble().pow(2.0)).toFloat()
        var pitchPen = asin(penY / penD) * -57.29578f
        var yawPen = -atan2(penX, penZ) * 57.29578f
        while (pitchPen > 90.0f) pitchPen -= 180.0f
        while (pitchPen < -90.0f) pitchPen += 180.0f
        while (yawPen > 180.0f) yawPen -= 360.0f
        while (yawPen < -180.0f) yawPen += 360.0f

        // 14) NaN/Inf fallback
        var pitch: Float; var yaw: Float
        if (pitchPen.isNaN() || yawPen.isNaN()) {
            val fdx = ptX - pmyX
            val fdy = ptY - eyeY
            val fdz = ptZ - pmyZ
            val dRaw = sqrt(fdy.toDouble().pow(2.0) + fdx.toDouble().pow(2.0) + fdz.toDouble().pow(2.0)).toFloat()
            pitch = asin(fdy / dRaw) * -57.29578f
            yaw = -atan2(fdx, fdz) * 57.29578f
            while (pitch > 90.0f) pitch -= 180.0f
            while (pitch < -90.0f) pitch += 180.0f
            while (yaw > 180.0f) yaw -= 360.0f
            while (yaw < -180.0f) yaw += 360.0f
            if (pitch.isNaN() || yaw.isNaN()) return
        } else {
            pitch = pitchPen; yaw = yawPen
        }

        // 15) blend against the current LP rotation
        val curPitch = rotCtx[0]; val curYaw = rotCtx[1]
        val dPitch = pitch - curPitch
        var dYaw = yaw - curYaw
        if (dYaw > 180.0f) dYaw -= 360.0f
        if (dYaw < -180.0f) dYaw += 360.0f
        val turnMag = sqrt(dPitch * dPitch + dYaw * dYaw)
        val speedScale: Float
        if (!st.firstTickDone || turnMag > 50.0f) {
            st.firstTickDone = true
            speedScale = 1.0f
        } else {
            val tier = when {
                turnMag > 20.0f -> 0.95f
                turnMag > 8.0f -> 0.9f
                turnMag > 3.0f -> 0.72f
                else -> 0.55f
            }
            speedScale = when {
                relY3 > 3.0f -> maxOf(0.93f, tier)
                relY3 > 1.0f -> maxOf(0.86f, tier)
                else -> tier
            }
        }
        var blend = (0.9f + st.hitRatio * 0.15f) * speedScale
        if (st.targetHitStreak >= 3) blend = maxOf(0.93f, blend)
        val k = clamp3(blend, 0.4f, 1.0f)
        val newPitch = k * dPitch + curPitch
        val newYaw = k * dYaw + curYaw
        rotOut[0] = newPitch; rotOut[1] = newYaw
        rotCtx[0] = rotOut[0]; rotCtx[1] = rotOut[1]

        // 16) hit marker + ratio
        if (tHurt == 1 || tHurt == 2) st.hitRing.push(1)
        while (st.hitRing.count > 30) st.hitRing.popFront()
        if (st.hitRing.count >= 5) {
            var hits = 0
            for (i in 0 until st.hitRing.count) if (st.hitRing.at(i) != 0) hits++
            val ratio = hits.toFloat() / st.hitRing.count
            st.hitRatio = 0.6f + 0.8f * ratio
        }
    }

    // =======================================================================
    // Handler Aegis (modo 8) — 0x1800C8A60
    // =======================================================================

    private fun smoothAxis(s: OscAxis, target: Float, alpha: Float) {
        val delta = target - s.pos
        val k0 = s.w0 / (s.w0 + alpha)
        val k1 = s.w1 / (s.w1 + alpha + alpha)
        val k2 = s.w2 / (s.w2 + alpha * 4.0f)
        s.pos += k0 * delta
        s.f1 += k1 * delta
        s.f2 += k2 * delta
        s.w0 = maxOf(0.001f, s.w0 * (1.0f - k0))
        s.w1 = maxOf(0.001f, s.w1 * (1.0f - k1))
        s.w2 = maxOf(0.001f, s.w2 * (1.0f - k2))
    }

    private fun integratePath(pos: V3, off: V3, step: Float, noVertical: Boolean): V3 {
        val v1 = if (1.0f > step) 1.0f else step
        val v = if (step > 15.0f) 15.0f else v1
        val n = v.toInt()
        if (n <= 0) return V3(pos.x, pos.y, pos.z)
        if (!noVertical) {
            for (i in 0 until n) {
                off.y = (off.y - 0.08f) * 0.98f
                off.x *= 0.91f
                off.z *= 0.91f
                pos.x += off.x; pos.y += off.y; pos.z += off.z
            }
        } else {
            for (i in 0 until n) {
                off.y = 0.0f
                off.x *= 0.91f
                off.z *= 0.91f
                pos.x += off.x; pos.y += off.y; pos.z += off.z
            }
        }
        return V3(pos.x, pos.y, pos.z)
    }

    private fun pickBestAimPoint(origin: V3, pts: FloatArray): V3 {
        val curPitch = rotOut[0]
        val curYaw = rotOut[1]
        val centerX = (pts[0] + pts[3]) * 0.5f
        val topY = pts[4]
        val centerZ = (pts[2] + pts[5]) * 0.5f
        val midY = (topY + pts[1]) * 0.5f
        var best = V3(centerX, midY, centerZ)
        var bestErr = 999999.0f
        val cand = arrayOf(
            V3(pts[0], pts[1], pts[2]), V3(pts[3], pts[1], pts[2]),
            V3(pts[0], topY, pts[2]), V3(pts[3], topY, pts[2]),
            V3(pts[0], pts[1], pts[5]), V3(pts[3], pts[1], pts[5]),
            V3(pts[0], topY, pts[5]), V3(pts[3], topY, pts[5]),
            V3(centerX, midY, pts[2]), V3(centerX, midY, pts[5]),
            V3(pts[0], midY, centerZ), V3(pts[3], midY, centerZ),
            V3(centerX, pts[1], centerZ),
            V3(centerX, topY - 0.15f, centerZ),
            V3(centerX, midY, centerZ)
        )
        for (c in cand) {
            val dx = c.x - origin.x; val dy = c.y - origin.y; val dz = c.z - origin.z
            val sum = (dx.toDouble() * dx) + (dy.toDouble() * dy) + (dz.toDouble() * dz)
            val dist = sqrt(sum)
            val pitch = asin((dy / dist).toFloat()) * -57.29578f
            val yaw = -atan2(dx, dz) * 57.29578f
            var p = pitch; var y = yaw
            while (p > 90.0f) p -= 180.0f
            while (p < -90.0f) p += 180.0f
            while (y > 180.0f) y -= 360.0f
            while (y < -180.0f) y += 360.0f
            if (p.isNaN()) continue
            if (y.isNaN()) continue
            val dPitch = p - curPitch
            var dYaw = y - curYaw
            while (dYaw > 180.0f) dYaw -= 360.0f
            while (dYaw < -180.0f) dYaw += 360.0f
            val err = dYaw * dYaw + dPitch * dPitch
            if (err < bestErr) { bestErr = err; best = V3(c.x, c.y, c.z) }
        }
        return best
    }

    private fun rotAegis(tgt: TData) {
        val localPlayer = session.localPlayer
        val m = mAegis

        // inputs
        val cx = localPlayer.posX; val cy = localPlayer.posY; val cz = localPlayer.posZ
        val eyeY = cy + 1.62f
        val cf3 = localPlayer.motionX; val cf4 = localPlayer.motionY; val cf5 = localPlayer.motionZ
        val af0 = tgt.posX; val af1 = tgt.posY; val af2 = tgt.posZ
        val af3 = tgt.velX; val af4 = tgt.velY; val af5 = tgt.velZ
        val tYaw = tgt.yaw
        val hurtT = tgt.hurt

        // target switch
        if (tgt.entity !== m.lastTarget) {
            m.rx.pos = af0; m.rx.f1 = af3; m.rx.f2 = 0.0f; m.rx.w0 = 100.0f; m.rx.w1 = 100.0f; m.rx.w2 = 100.0f
            m.ry.pos = af1; m.ry.f1 = af4; m.ry.f2 = 0.0f; m.ry.w0 = 100.0f; m.ry.w1 = 100.0f; m.ry.w2 = 100.0f
            m.rz.pos = af2; m.rz.f1 = af5; m.rz.f2 = 0.0f; m.rz.w0 = 100.0f; m.rz.w1 = 100.0f; m.rz.w2 = 100.0f
            m.posRing.clear()
            m.latRing.fill(0f)
            m.latHead = 0; m.latCount = 0
            m.lastTargetYaw = tYaw
            m.hurtCountA = 0; m.hurtCountB = 0
            m.prevHurtT = 0; m.prevHurtLp = 0
            m.mult = 1.0f
            m.hitFactor = 1.0f
            m.hitRing.clear()
            m.lastTarget = tgt.entity
        }

        // hit counters + dynamic multiplier
        val lpHurtT = hurtTimeOf(localPlayer)
        if (hurtT > 0 && m.prevHurtT == 0) { m.hurtCountA++; m.hurtCountB = 0 }
        if (lpHurtT > 0 && m.prevHurtLp == 0) { m.hurtCountB++; m.hurtCountA = 0 }
        m.prevHurtT = hurtT; m.prevHurtLp = lpHurtT
        val mult: Float = if (m.hurtCountA >= 2) {
            1.0f + minOf(0.4f, m.hurtCountA * 0.1f)
        } else {
            var base = 1.0f
            if (m.hurtCountB >= 2) base = 1.0f - minOf(0.4f, m.hurtCountB * 0.12f)
            base
        }
        m.mult = mult

        // oscillator dead-reckoning + weight growth
        val delta = if (hurtT > 0) 0.5f else 0.05f
        for (s in arrayOf(m.rx, m.ry, m.rz)) {
            val f1o = s.f1; val f2o = s.f2
            s.pos = s.pos + f2o * 0.5f + f1o
            s.f1 = f2o + f1o
            s.f2 = f2o * 0.9f
            val w0o = s.w0; val w1o = s.w1; val w2o = s.w2
            s.w0 = w0o + w1o + delta
            s.w1 = w2o + w1o + delta * 0.5f
            s.w2 = w2o + delta * 0.25f
        }
        smoothAxis(m.rx, af0, 0.1f)
        smoothAxis(m.ry, af1, 0.1f)
        smoothAxis(m.rz, af2, 0.1f)

        // lateral sample + 32-float ring push
        val yawRad = tYaw * 0.0174532924f
        val cosY = cos(yawRad)
        val sinY = -sin(yawRad)
        val dot = cosY * m.rx.f1 + sinY * m.rz.f1
        m.latRing[m.latHead and 31] = dot
        m.latHead = (m.latHead + 1) and 31
        if (m.latCount < 32) m.latCount++

        // pattern analysis: mean / variance / autocorrelation
        var period = 0
        var bestCorr = 0.0f
        var phase = 0.0f
        var n = m.latCount
        if (n >= 8) {
            if (n > 32) n = 32
            var mean = 0.0f
            for (i in 0 until n) mean += m.latRing[(m.latHead - n + i) and 31]
            mean /= n
            var `var` = 0.0f
            for (i in 0 until n) { val d = m.latRing[(m.latHead - n + i) and 31] - mean; `var` += d * d }
            if (`var` >= 0.001f) {
                var lag = 4
                while (lag < n && lag <= 20) {
                    var acc = 0.0f
                    var i = 0
                    while (i + lag < n) {
                        acc += (m.latRing[(m.latHead - n + i + lag) and 31] - mean) *
                            (m.latRing[(m.latHead - n + i) and 31] - mean)
                        i++
                    }
                    val corr = acc / `var`
                    if (corr > bestCorr) { bestCorr = corr; period = lag }
                    lag++
                }
                if (bestCorr > 0.5f && period > 0) {
                    phase = (m.latCount.toFloat() % period.toFloat()) / period
                } else {
                    period = 0
                }
            }
        }

        // direction deltas
        val dx = m.rx.pos - cx
        val dy = m.ry.pos - cy
        val dz = m.rz.pos - cz
        val dx2 = m.rx.f1 - cf3
        val dy2 = m.ry.f1 - cf4
        val dz2 = m.rz.f1 - cf5

        // base step
        val sumD = (dx.toDouble() * dx) + (dy.toDouble() * dy) + (dz.toDouble() * dz)
        val dist = sqrt(sumD).toFloat()
        val base: Float
        var haveBase = false
        var baseVal = 0f
        if (dist > 0.01f) {
            var cosdot = (dx / dist) * dx2
            cosdot += (dy * 0.0f) * dy2
            cosdot += (dz * 0.0f) * dz2
            val neg = -cosdot
            if (neg > 0.5f) {
                baseVal = dist / neg
                haveBase = true
            }
        }
        if (!haveBase) {
            val sum2 = (dx2.toDouble() * dx2) + (dy2.toDouble() * dy2) + (dz2.toDouble() * dz2)
            val d2 = sqrt(sum2).toFloat()
            baseVal = if (d2 > 0.3f) dist / d2 else dist * 1.5f
        }
        baseVal *= m.mult
        baseVal *= (0.7f + m.hitFactor * 0.3f)
        val step = if (baseVal <= 15.0f) (if (1.0f <= baseVal) baseVal else 1.0f) else 15.0f

        // entity flag + oscillation angle
        val flag = tgt.onGround
        var offX = m.rx.f1; var offY = m.ry.f1; var offZ = m.rz.f1
        if (bestCorr > 0.5f && period > 0) {
            var v = (bestCorr - 0.5f) * 2.0f
            v = if (v <= 0.7f) maxOf(0.0f, v) else 0.7f
            val osc = sin(((step / period + phase) % 1.0f) * 2.0f * 3.1415927f)
            val offset = osc * abs(dot) * v + (1.0f - v) * dot
            val p = offZ * cosY + offX * sinY
            offX = offset * cosY + p * sinY
            offZ = p * cosY - offset * sinY
            // offY remains = m.ry.f1
        }

        // predicted point
        val posI = V3(m.rx.pos, m.ry.pos, m.rz.pos)
        val offI = V3(offX, offY, offZ)
        val pred = integratePath(posI, offI, step, flag)

        // aim box
        val halfWx = tgt.width * 0.5f
        val halfWz = tgt.width * 0.5f
        var amp = sqrt(m.rz.w0 + m.rx.w0) * 0.15f
        amp = if (amp <= 0.35f) maxOf(0.0f, amp) else 0.35f
        val pts = floatArrayOf(
            pred.x - halfWx - amp,
            pred.y,
            pred.z - halfWz - amp,
            halfWx + pred.x + amp,
            tgt.height + pred.y,
            halfWz + pred.z + amp
        )

        // ray origin
        val x0 = cx + step * 0.25f * cf3
        val y0 = eyeY
        val z0 = cz

        // best point
        val aim = pickBestAimPoint(V3(x0, y0, z0), pts)

        // direction -> pitch/yaw
        val adx = aim.x - x0; val ady = aim.y - y0; val adz = aim.z - z0
        val sumA = (adx.toDouble() * adx) + (ady.toDouble() * ady) + (adz.toDouble() * adz)
        val dA = sqrt(sumA)
        var pitch = asin((ady / dA).toFloat()) * -57.29578f
        var yaw = -atan2(adx, adz) * 57.29578f
        while (pitch > 90.0f) pitch -= 180.0f
        while (pitch < -90.0f) pitch += 180.0f
        while (yaw > 180.0f) yaw -= 360.0f
        while (yaw < -180.0f) yaw += 360.0f

        // NaN validation + fallback
        if (pitch.isNaN() || yaw.isNaN()) {
            val fdx = af0 - cx
            val fdy = af1 - eyeY
            val fdz = af2 - cz
            val sumF = (fdx.toDouble() * fdx) + (fdy.toDouble() * fdy) + (fdz.toDouble() * fdz)
            val dF = sqrt(sumF)
            pitch = asin((fdy / dF).toFloat()) * -57.29578f
            yaw = -atan2(fdx, fdz) * 57.29578f
            while (pitch > 90.0f) pitch -= 180.0f
            while (pitch < -90.0f) pitch += 180.0f
            while (yaw > 180.0f) yaw -= 360.0f
            while (yaw < -180.0f) yaw += 360.0f
            if (pitch.isNaN() || yaw.isNaN()) return
        }

        // rotation write
        rotOut[0] = pitch; rotOut[1] = yaw
        rotCtx[0] = rotOut[0]; rotCtx[1] = rotOut[1]

        // hit registration
        if (hurtT == 1 || hurtT == 2) m.hitRing.push(1)

        // hitrate factor
        var skipRate = false
        if (m.hitRing.count > 30) {
            m.hitRing.count--
            if (m.hitRing.count == 0) { m.hitRing.start = 0; skipRate = true }
            else m.hitRing.start++
        }
        if (!skipRate && m.hitRing.count >= 5) {
            var hits = 0
            for (i in 0 until m.hitRing.count) if (m.hitRing.at(i) != 0) hits++
            m.hitFactor = hits.toFloat() / m.hitRing.count * 0.8f + 0.6f
        }

        // target position ring push + decay
        m.posRing.push(V3(af0, af1, af2))
        while (m.posRing.count > 16) m.posRing.popFront()

        m.lastTargetYaw = tYaw
    }

    // =======================================================================
    // PAIP spoof — onSendPacket equivalent (spec §5 gate)
    // =======================================================================

    private fun spoofRotation(packet: PlayerAuthInputPacket) {
        if (targets.isEmpty()) return
        val mode = rotationMode
        if (mode < 1 || mode == 2) return // None y Strafe no spoofean
        if (!rotOut[0].isFinite() || !rotOut[1].isFinite()) return

        packet.rotation = Vector3f.from(rotOut[0], rotOut[1], rotOut[1])
    }
}
