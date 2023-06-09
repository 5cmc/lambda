package com.lambda.client.module.modules.movement

import com.babbaj.pathfinder.PathFinder
import com.lambda.client.LambdaMod
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.events.PlayerTravelEvent
import com.lambda.client.event.events.RenderWorldEvent
import com.lambda.client.mixin.extension.boostedEntity
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.module.modules.client.GuiColors
import com.lambda.client.util.Bind
import com.lambda.client.util.graphics.LambdaTessellator
import com.lambda.client.util.math.RotationUtils.getRotationTo
import com.lambda.client.util.math.RotationUtils.normalizeAngle
import com.lambda.client.util.math.Vec2d
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.math.VectorUtils.magnitudeSquared
import com.lambda.client.util.math.VectorUtils.scalarMultiply
import com.lambda.client.util.math.VectorUtils.toVec3d
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.runSafe
import com.lambda.client.util.threads.safeListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.entity.item.EntityFireworkRocket
import net.minecraft.network.play.server.SPacketPlayerPosLook
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraftforge.fml.common.gameevent.InputEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11.GL_LINE_STRIP
import java.util.*
import java.util.Objects.isNull
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Collectors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min


/**
 * For user interface:
 * @see com.lambda.client.command.commands.NetherPathfindCommand
 */
object NetherPathfinder: Module(
    name = "NetherPathfinder",
    description = "Pathfind in the nether",
    category = Category.MOVEMENT
) {

    private val color by setting("Color", GuiColors.primary)
    private val throughBlocks by setting("Render Through Blocks", true)
    private val thickness by setting("Line Thickness", 1.0f, 0.25f..3.0f, 0.25f)
    private val segmentDistance by setting("Segment Distance", 5000, 1..10000, 1)
    private val segmentRepathDist by setting("Repath Distance", 300, 1..1000, 1,
        description = "Starts pathing for next segments if you are more than this distance away from the current end segment")
    private val rotatePlayer by setting("Rotate Player", false, consumer = { _, new ->
        playerProgressIndex = Int.MIN_VALUE
        return@setting new
    })
    private val rotateYaw by setting("Rotate Yaw", true, visibility = { rotatePlayer })
    private val rotatePitch by setting("Rotate Pitch", true, visibility = { rotatePlayer })
    private val rotatePitchAdjust by setting("Elytra Pitch Adjust", false, { rotatePlayer && rotatePitch},
        description = "Adjust pitch to optimize how closely the player is to the goal line. Intended to be used while elytra flying with vanilla physics")
    private val rotateYawAdjust by setting("Elytra Yaw Adjust", false, { rotatePlayer && rotateYaw},
        description = "Adjust yaw to optimize how closely the player is to the goal line. Intended to be used while elytra flying with vanilla physics")
    private val boostedYAdjust by setting("Boosted Efly Y Adjust", false, { rotatePlayer },
        description = "Uses ElytraFlight V Control while firework boosted to make adjustments closer to the goal line")
    private val adjustMultiplier by setting("Adjust Multiplier", 5.0, 1.0..10.0, 0.1, { rotatePlayer && (rotateYaw || rotatePitch) && (rotateYawAdjust || rotatePitchAdjust) })
    private val maxAdjustAngle by setting("Max Adjust Angle", 20.0, 1.0..90.0, 0.1, { rotatePlayer && (rotateYaw || rotatePitch) && (rotateYawAdjust || rotatePitchAdjust) })
    private val pauseRotateBind by setting("Pause Rotate Bind", Bind(), description = "Pauses rotate mode while this key is held", visibility = { rotatePlayer })
    private val pauseRotateMode by setting("Pause Rotate Mode", PauseRotateMode.HOLD, visibility = { rotatePlayer })
    private val rotateDist by setting("Rotate Point Reached Distance", 3, 1..10, 1,
        description = "How near you have to get to the next point before rotating to the next point. Y is ignored.", visibility = { rotatePlayer })
    private val tpRetargetDist by setting("Teleport Retarget Distance", 5, 1..20, 1,
        description = "Retargets the rotation if server tp's you more than this distance away from your last position", visibility = { rotatePlayer })
    private val repathSchedulerDelay by setting("Scheduler Delay ms", 1000, 100..10000, 100,
        description = "How often to check if we need to repath", visibility = { rotatePlayer })

    private var pathJob: Job? = null
    private var scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    private var scheduledFuture: ScheduledFuture<*>? = null
    private var seed: Long = 146008555100680L // 2b2t nether seed
    private val pathLock: AtomicBoolean = AtomicBoolean(false)
    private var path: List<BlockPos>? = null
    private var playerProgressIndex: Int = Int.MIN_VALUE
    private var rotatePaused: Boolean = false
    private var currentGoal: BlockPos? = null
    private var multiSegmentPath = false

    enum class PauseRotateMode {
        HOLD, TOGGLE
    }

    /**
     * todo: make pathfinder path around features/structures it missed due to incomplete calculations
     *
     * The pathfinder lib has no idea about fortresses, glowstone outcrops, etc in our loaded chunks
     * idea is we could have a very basic pathfinder integrated that checks if our current segment raytrace through a block in currently loaded chunks
     * if so, update the path using our own logic to add additional points to the path that don't raytrace through blocks
     *
     * ideally this would only need one or two additional points inserted
     *
     * i'd rather not reimplement a whole A* algo, and there might a simpler solution:
     *  1. progressively 3d spiral away from the raytraced block intersection point to test
     *     permutations like (1,0,0)(1,1,0)(0,1,1)(1,0,1)(-1,0,0)....(2,0,0) and so on
     *  2. check if a segment from this point and segment to next raytrace through a block
     *  3. if yes, try another block. If no, update the path list
     * this could run on a coroutine in the background. there's not that many path segments in loaded chunks
     * so this shouldn't be too cpu intensive
     */

    override fun getHudInfo(): String {
        return if (path?.isNotEmpty() == true && rotatePlayer && !rotatePaused) "ROTATE" else ""
    }

    init {
        onDisable {
            resetAll()
        }

        safeListener<RenderWorldEvent> {
            if (!isInNether()) return@safeListener
            path?.let {
                drawLine(it)
            }
        }

        safeListener<TickEvent.ClientTickEvent> { event ->
            if (event.phase != TickEvent.Phase.END) return@safeListener
            if (!rotatePlayer) return@safeListener
            path?.let { pathList ->
                nextPathPos(pathList)?.let {
                    if (!pauseRotateBind.isEmpty && pauseRotateMode == PauseRotateMode.HOLD && Keyboard.isKeyDown(pauseRotateBind.key)) {
                        playerProgressIndex = Int.MIN_VALUE
                        return@safeListener
                    }
                    if (pauseRotateMode == PauseRotateMode.TOGGLE && rotatePaused) return@safeListener
                    val rotationTo = getRotationTo(it.toVec3d())
                    if (rotateYaw) {
                        if (rotateYawAdjust) {
                            player.rotationYaw = getAdjustedYaw(pathList, rotationTo.x).toFloat()
                        } else {
                            player.rotationYaw = rotationTo.x
                        }
                    }
                    if (rotatePitch) {
                        if (rotatePitchAdjust) {
                            val adjustPitch = getAdjustedPitch(pathList).toFloat()
                            player.rotationPitch = (rotationTo.y + adjustPitch).coerceIn(-49.9f..49.9f)
                        } else {
                            player.rotationPitch = rotationTo.y.coerceIn(-49.9f..49.9f)
                        }
                    }
                }
            }
        }

        safeListener<PlayerTravelEvent> {
            if (!rotatePlayer || !boostedYAdjust || rotatePaused || !player.isElytraFlying) return@safeListener
            path?.let { pathList ->
                try {
                    val isBoosted = world.getLoadedEntityList().any { it is EntityFireworkRocket && it.boostedEntity == player }
                    if (isBoosted) {
                        val prevPathPos = pathList[playerProgressIndex - 1]
                        val nextPathPos = pathList[playerProgressIndex]
                        val playerEyes = player.getPositionEyes(1f)
                        val distanceVec = getDistanceVec(playerEyes.x, playerEyes.y, prevPathPos.x.toDouble(), prevPathPos.y.toDouble(), nextPathPos.x.toDouble(), nextPathPos.y.toDouble())
                        if (abs(distanceVec.y) > 0.1) {
                            val m = -distanceVec.y.coerceIn(-1.0, 1.0)
                            if (abs(player.motionY) < abs(m)) {
                                player.motionY = m
                            }
                        }
                    }
                } catch (ex: Exception) {
                    // just in case index op is out of bounds
                }
            }
        }

        safeListener<InputEvent.KeyInputEvent> {
            if (pauseRotateMode == PauseRotateMode.TOGGLE && !pauseRotateBind.isEmpty) {
                val key = Keyboard.getEventKey()
                if (pauseRotateBind.isDown(key)) {
                    rotatePaused = !rotatePaused
                    playerProgressIndex = Int.MIN_VALUE
                }
            }
        }

        safeListener<PacketEvent.Receive> {
            if (it.packet !is SPacketPlayerPosLook) return@safeListener
            if (playerProgressIndex == Int.MIN_VALUE) return@safeListener
            if (path == null) return@safeListener
            if (getDistance(player.posX, player.posY, player.posZ, it.packet.x, it.packet.y, it.packet.z) > tpRetargetDist) {
                playerProgressIndex = Int.MIN_VALUE
            }
        }
    }

    private fun SafeClientEvent.getAdjustedYaw(pathList: List<BlockPos>, yawToGoal: Float): Double {
        // i think these calculations can be simplified
        // no idea how tho lol
        // idea: compare line xz angle to yawToGoal. these should match up when we are on the line

        val distanceVec = playerLineDistanceVec(pathList) // vector for how far away from the goal line we are
        val pathVec = getPlayerVecToLine(pathList) // rotation vector from player directly to the goal line
        // convert rotation vector to yaw
        val yawToPath = normalizeAngle(Math.toDegrees(atan2(pathVec.z, pathVec.x)) - 90.0).toFloat()
        // determine which way we should turn the player, clockwise (+) or counterclockwise (-) yaw
        val clockwise = isClockwiseTargetAngle(yawToGoal, yawToPath)
        // farther distance from goal line = sharper angle adjusted towards it
        val adjustAngle = (if (clockwise) 1 else -1) * (distanceVec.length() * adjustMultiplier)
            // cap our max adjust angle
            .coerceIn(-maxAdjustAngle, maxAdjustAngle)
        return yawToGoal + adjustAngle
    }

    private fun SafeClientEvent.getAdjustedPitch(pathList: List<BlockPos>): Double {
        return try {
            val prevPathPos = pathList[playerProgressIndex - 1]
            val nextPathPos = pathList[playerProgressIndex]
            val playerEyes = player.getPositionEyes(1f)
            val distanceVec = getDistanceVec(playerEyes.x, playerEyes.y, prevPathPos.x.toDouble(), prevPathPos.y.toDouble(), nextPathPos.x.toDouble(), nextPathPos.y.toDouble())
            (distanceVec.y * adjustMultiplier).coerceIn(-maxAdjustAngle, maxAdjustAngle)
        } catch (ex: Exception) {
            0.0
        }
    }

    private fun SafeClientEvent.playerLineDistanceVec(pathList: List<BlockPos>): Vec2d {
        return try {
            val prevPathPos = pathList[playerProgressIndex - 1]
            val nextPathPos = pathList[playerProgressIndex]
            val playerEyes = player.getPositionEyes(1f)
            getDistanceVec(playerEyes.x, playerEyes.z, prevPathPos.x.toDouble(), prevPathPos.z.toDouble(), nextPathPos.x.toDouble(), nextPathPos.z.toDouble())
        } catch (ex: Exception) {
            Vec2d.ZERO
        }
    }

    private fun isClockwiseTargetAngle(yawTo: Float, yawToPath: Float): Boolean {
        // find shortest turn to get to yawToPath
        //  either clockwise (+) or counterclockwise (-)
        // yaw rolls over on 180/-180 so we're gonna normalize things to 360 degrees for simplicity
        val degreedYaw = yawTo + 180f
        val degreedYawToPath = yawToPath + 180f
        return ((degreedYawToPath - degreedYaw + 540f) % 360f - 180f) >= 0
    }

    private fun SafeClientEvent.getPlayerVecToLine(pathList: List<BlockPos>): Vec3d {
        return try {
            val prevPathPos = pathList[playerProgressIndex - 1]
            val nextPathPos = pathList[playerProgressIndex]
            val playerEyes = player.getPositionEyes(1f)
            val p = Vec3d(playerEyes.x, playerEyes.y, playerEyes.z)
            val p1 = Vec3d(prevPathPos.x.toDouble(), prevPathPos.y.toDouble(), prevPathPos.z.toDouble())
            val p2 = Vec3d(nextPathPos.x.toDouble(), nextPathPos.y.toDouble(), nextPathPos.z.toDouble())
            val v = p2.subtract(p1)
            val w = p.subtract(p1)
            val scalarProj = w.dotProduct(v) / v.magnitudeSquared()
            val c = p1.add(v.scalarMultiply(scalarProj))
            c.subtract(p)
        } catch (ex: Exception) {
            Vec3d.ZERO
        }
    }

    private fun drawLine(posList: List<BlockPos>) {
        if (posList.isEmpty()) return
        val buffer = LambdaTessellator.buffer
        if (throughBlocks) GlStateManager.disableDepth()
        GlStateManager.glLineWidth(thickness)
        LambdaTessellator.begin(GL_LINE_STRIP)
        for (pos in posList) {
            buffer.pos(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
                .color(color.r, color.g, color.b, color.a)
                .endVertex()
        }
        LambdaTessellator.render()
        GlStateManager.enableDepth()
    }

    fun goto(x: Int, z: Int) {
        if (pathLock.compareAndSet(false, true) && isNull(pathJob)) {
            pathJob = defaultScope.launch { runSafe {
                currentGoal = BlockPos(x.toDouble(), 64.0, z.toDouble())
                val t1 = System.currentTimeMillis()
                var longs: LongArray? = null
                val goalPos = calculateGoalPoint(player.position, currentGoal!!, segmentDistance.toDouble())
                multiSegmentPath = (abs(goalPos.x - currentGoal!!.x) > 5 || abs(goalPos.z - currentGoal!!.z) > 5)
                try {
                    MessageSendHelper.sendChatMessage("Calculating ${if (multiSegmentPath) "segment" else "" } path to ${goalPos.x}, ${goalPos.z}...")
                    longs = PathFinder.pathFind(seed, false, true, player.posX.toInt(), player.posY.toInt(), player.posZ.toInt(),
                        goalPos.x,
                        64,
                        goalPos.z
                    )
                } catch (e: Throwable) {
                    LambdaMod.LOG.error(e)
                }
                if (longs != null) {
                    val t2 = System.currentTimeMillis()
                    val path: MutableList<BlockPos> = Arrays.stream(longs).mapToObj { serialized: Long -> BlockPos.fromLong(serialized) }.collect(Collectors.toList())
                    if (isActive) { // allow us to "cancel" pathfind
                        mc.addScheduledTask {
                            playerProgressIndex = Int.MIN_VALUE
                            setPath(path)
                            scheduledFuture?.cancel(true)
                            scheduledFuture = scheduledExecutor.scheduleWithFixedDelay({ scheduledGotoRepathCheck(path, x, z) }, 5000, repathSchedulerDelay.toLong(), TimeUnit.MILLISECONDS)
                            MessageSendHelper.sendChatMessage(String.format("Found path in %.2f seconds", (t2 - t1) / 1000.0))
                            pathJob = null
                            pathLock.set(false)
                        }
                    }
                } else {
                    pathJob = null
                    pathLock.set(false)
                }
            } }
        } else {
            MessageSendHelper.sendChatMessage("Already pathing")
        }
    }

    private fun calculateGoalPoint(playerPos: BlockPos, targetPos: BlockPos, maxDistance: Double): BlockPos {
        val dx = targetPos.x - playerPos.x
        val dz = targetPos.z - playerPos.z
        val distance = playerPos.distanceTo(targetPos)

        val ratio = maxDistance / distance
        val goalX = playerPos.x + (dx * ratio).toInt()
        val goalZ = playerPos.z + (dz * ratio).toInt()
        val goalY = playerPos.y // Keep the player's current Y

        return BlockPos(goalX, goalY, goalZ)
    }


    private fun setPath(p: List<BlockPos>) {
        path = p
    }

    fun cancel() {
        resetAll()
        MessageSendHelper.sendChatMessage("Cancelled pathing")
    }

    fun thisWay(dist: Int) {
        val theta: Float = Math.toRadians(mc.player.rotationYawHead.toDouble()).toFloat()
        val destX = (mc.player.posX - MathHelper.sin(theta) * dist).toInt()
        val destZ = (mc.player.posZ + MathHelper.cos(theta) * dist).toInt()
        goto(destX, destZ)
    }

    fun setSeed(newSeed: Long) {
        this.seed = newSeed
        MessageSendHelper.sendChatMessage(String.format("Seed set: %s", newSeed))
    }

    fun resetAll() {
        if (pathJob != null) {
            // important: if this is called while there is no pathing ongoing the next path will fail
            // thank you babbaj
            PathFinder.cancel()
        }
        pathJob?.cancel()
        pathJob = null
        scheduledFuture?.cancel(true)
        path = null
        pathLock.set(false)
        playerProgressIndex = Int.MIN_VALUE
        rotatePaused = false
        currentGoal = null
        multiSegmentPath = false
    }

    private fun scheduledGotoRepathCheck(path: MutableList<BlockPos>, destX: Int, destZ: Int) {
        try {
            if (!isInNether() || pathLock.get()) {
                return
            }
            if (multiSegmentPath) {
                currentGoal?.let { goal ->
                    if (path.last() != goal
                        && path.last().getDistance(mc.player.posX.toInt(), mc.player.posY.toInt(), mc.player.posZ.toInt()) < segmentRepathDist) {
                        MessageSendHelper.sendChatMessage("Reached segment goal, pathing...")
                        MessageSendHelper.sendLambdaCommand("npath goto ${goal.x} ${goal.z}")
                        scheduledFuture?.cancel(true)
                        return
                    }
                }
            }

            val dist = minPlayerDistanceToPath(path)
            val playerFarFromPath: Boolean = dist > 100.0
            if (playerFarFromPath) {
                if (Thread.interrupted()) {
                    throw InterruptedException()
                }
                MessageSendHelper.sendChatMessage("Moved too far from path, repathing...")
                MessageSendHelper.sendLambdaCommand("npath goto $destX $destZ")
                scheduledFuture?.cancel(true)
            } else if (path.last().distanceTo(mc.player.position) < 50.0) {
                MessageSendHelper.sendChatMessage("Pathing goal completed, stopping..")
                mc.addScheduledTask { disable() }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
    }

    private fun minPlayerDistanceToPath(path: List<BlockPos>): Double {
        val playerCurPos: BlockPos = mc.player.position
        val pX = playerCurPos.x
        val pZ = playerCurPos.z
        var min: Double = Double.MAX_VALUE
        for (i in 1 until path.size) {
            val s1 = path[i - 1]
            val s2 = path[i]
            // skip extra maths on points where we're already really far away
            if (abs(abs(s1.x) - abs(pX)) > 1500.0 || abs(abs(s1.z) - abs(pZ)) > 1500.0) continue
            min = min(getDistance(pX.toDouble(), pZ.toDouble(), s1.x.toDouble(), s1.z.toDouble(), s2.x.toDouble(), s2.z.toDouble()), min)
        }
        return min
    }

    private fun SafeClientEvent.nextPathPos(path: List<BlockPos>): BlockPos? {
        if (path.isEmpty()) return null
        // we want to linearly along the path
        // once we get to a point, get the next point in the sequence

        /**
         * diagram of what we want:
         *
         *  player.(1).....(2).... = 1
         *  (1).player...(2)... = 2
         *  (1)....player.(2).... = 2
         *  (1)....(2)player...(3) = 3
         *
         *
         *  points are in 3d space
         */

        try {
            if (playerProgressIndex == Int.MIN_VALUE) {
                // resume or start
                val findNearestPathPosIndex = findNearestPathPosIndex(path)
                playerProgressIndex = findNearestPathPosIndex
                return path[playerProgressIndex]
            }
            val currentPathPos = path[playerProgressIndex]

            if (path.size < playerProgressIndex + 1) return currentPathPos
            val playerCurPos: BlockPos = mc.player.position
            val normalizedCurrentPathPos = BlockPos(currentPathPos.x, playerCurPos.y, currentPathPos.z) // ignore y
            if (playerCurPos.distanceTo(normalizedCurrentPathPos) < rotateDist) { // might need to adjust this distance...
                playerProgressIndex++
            }
            return currentPathPos
        } catch (ex: Exception) {
            // just in case of async race conditions so we don't crash
            // array index accesses are spooky
            return null
        }


    }

    private fun SafeClientEvent.findNearestPathPosIndex(path: List<BlockPos>): Int {
        val playerCurPos: BlockPos = mc.player.position
        val pX = playerCurPos.x
        val pZ = playerCurPos.z
        var min: Double = Double.MAX_VALUE
        var bPosMinIndex: Int = 0
        for ((index, pos) in path.withIndex()) {
            // skip extra maths on points where we're already really far away
            if (abs(abs(pos.x) - abs(pX)) > 1500.0 || abs(abs(pos.z) - abs(pZ)) > 1500.0) continue
            val playerDist = player.distanceTo(pos.toVec3d())
            if (playerDist < min) {
                min = playerDist
                bPosMinIndex = index
            }
        }
        val playerYawToPoint = MathHelper.wrapDegrees(getRotationTo(path[bPosMinIndex].toVec3d()).x)
        val yawDifference = MathHelper.abs(MathHelper.wrapDegrees(player.rotationYaw) - playerYawToPoint)
        if (yawDifference > 90.0) {
            // try next point
            val nextIndex = min(bPosMinIndex + 1, path.size-1)
            val nextPoint = path[nextIndex]
            val playerYawToPoint2 = MathHelper.wrapDegrees(getRotationTo(nextPoint.toVec3d()).x)
            val yawDifference2 = MathHelper.abs(MathHelper.wrapDegrees(player.rotationYaw) - playerYawToPoint2)
            if (yawDifference2 < 90.0) {
                bPosMinIndex = nextIndex
            }
        }

        return bPosMinIndex
    }

    // ty stackoverflow https://stackoverflow.com/questions/849211/shortest-distance-between-a-point-and-a-line-segment
    fun getDistance(x: Double, y: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val a = x - x1
        val b = y - y1
        val c = x2 - x1
        val d = y2 - y1

        val lenSq = c * c + d * d
        val param = if (lenSq != .0) { //in case of 0 length line
            val dot = a * c + b * d
            dot / lenSq
        } else {
            -1.0
        }

        val (xx, yy) = when {
            param < 0 -> x1 to y1
            param > 1 -> x2 to y2
            else -> x1 + param * c to y1 + param * d
        }

        val dx = x - xx
        val dy = y - yy
        return hypot(dx, dy)
    }

    private fun getDistanceVec(x: Double, y: Double, x1: Double, y1: Double, x2: Double, y2: Double): Vec2d {
        val a = x - x1
        val b = y - y1
        val c = x2 - x1
        val d = y2 - y1

        val lenSq = c * c + d * d
        val param = if (lenSq != .0) { //in case of 0 length line
            val dot = a * c + b * d
            dot / lenSq
        } else {
            -1.0
        }

        val (xx, yy) = when {
            param < 0 -> x1 to y1
            param > 1 -> x2 to y2
            else -> x1 + param * c to y1 + param * d
        }

        val dx = x - xx
        val dy = y - yy
        return Vec2d(dx, dy)
    }

    fun isInNether(): Boolean {
        return mc.world != null && mc.player.dimension == -1
    }
}