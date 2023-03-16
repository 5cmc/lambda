package com.lambda.client.module.modules.movement

import com.babbaj.pathfinder.PathFinder
import com.lambda.client.LambdaMod
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.RenderWorldEvent
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.module.modules.client.GuiColors
import com.lambda.client.util.Bind
import com.lambda.client.util.graphics.LambdaTessellator
import com.lambda.client.util.math.RotationUtils.getRotationTo
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.math.VectorUtils.toVec3d
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.safeListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
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
    private val rotatePlayer by setting("Rotate Player", false, consumer = { _, new ->
        playerProgressIndex = Int.MIN_VALUE
        return@setting new
    })
    private val rotateYaw by setting("Rotate Yaw", true, visibility = { rotatePlayer })
    private val rotatePitch by setting("Rotate Pitch", true, visibility = { rotatePlayer })
    private val pauseRotateBind by setting("Pause Rotate Bind", Bind(), description = "Pauses rotate mode while this key is held", visibility = { rotatePlayer })
    private val pauseRotateMode by setting("Pause Rotate Mode", PauseRotateMode.HOLD, visibility = { rotatePlayer })
    private val rotateDist by setting("Segment Reached Distance", 3, 1..10, 1, description = "How near you have to get to the next point before rotating to the next point. Y is ignored.", visibility = { rotatePlayer })

    private var pathJob: Job? = null
    private var scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    private var scheduledFuture: ScheduledFuture<*>? = null
    private var seed: Long = 146008555100680L // 2b2t nether seed
    private val pathLock: AtomicBoolean = AtomicBoolean(false)
    private var path: List<BlockPos>? = null
    private var playerProgressIndex: Int = Int.MIN_VALUE
    private var rotatePaused: Boolean = false

    enum class PauseRotateMode {
        HOLD, TOGGLE
    }

    /**
     * todo: rework long goto paths into segments of shorter paths (2k or so)
     * calculating a large path takes a long time and doesn't really minimize the calculated path that much
     * it also causes additional strain on our distance and rotation calculations as well as rendering
     *
     * ideas for how this could be implemented:
     *  1. store actual path that the user requested along with vec2d rotation
     *  2. calculate path along vector with length = min(path_len, 2k)
     *  3. when we are within N blocks of segment end, calculate the next segment. starting at current segment point - M blocks
     *      (subtracting M blocks because sometimes our end point is not in an ideal location due to it not considering next movements)
     *  4. when we've reached the end of segment 1, remove it from our path list and continue on
     *
     */

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
                    if (rotateYaw) player.rotationYaw = rotationTo.x
                    if (rotatePitch) player.rotationPitch = rotationTo.y
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
            pathJob = defaultScope.launch {
                MessageSendHelper.sendChatMessage("Calculating path...")
                val t1 = System.currentTimeMillis()
                var longs: LongArray? = null
                try {
                    longs = PathFinder.pathFind(seed, false, true, mc.player.posX.toInt(), mc.player.posY.toInt(), mc.player.posZ.toInt(), x, 64, z)
                } catch (e: Throwable) {
                    LambdaMod.LOG.error(e)
                }
                if (longs != null) {
                    val t2 = System.currentTimeMillis()
                    val path: MutableList<BlockPos> = Arrays.stream(longs).mapToObj { serialized: Long -> BlockPos.fromLong(serialized) }.collect(Collectors.toList())
                    if (isActive) { // allow us to "cancel" pathfind
                        mc.addScheduledTask {
                            setPath(path)
                            scheduledFuture?.cancel(true)
                            scheduledFuture = scheduledExecutor.scheduleAtFixedRate({ scheduledGotoRepathCheck(path, x, z) }, 5000, 5000, TimeUnit.MILLISECONDS)
                            MessageSendHelper.sendChatMessage(String.format("Found path in %.2f seconds", (t2 - t1) / 1000.0))
                            pathJob = null
                            pathLock.set(false)
                        }
                    }
                } else {
                    pathJob = null
                    pathLock.set(false)
                }
            }
        } else {
            MessageSendHelper.sendChatMessage("Already pathing")
        }
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
    }

    fun scheduledGotoRepathCheck(path: MutableList<BlockPos>, destX: Int, destZ: Int) {
        try {
            if (!isInNether()) {
                return
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

    fun isInNether(): Boolean {
        return mc.world != null && mc.player.dimension == -1
    }
}