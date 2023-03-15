package com.lambda.client.module.modules.movement

import com.babbaj.pathfinder.PathFinder
import com.lambda.client.LambdaMod
import com.lambda.client.event.events.RenderWorldEvent
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.module.modules.client.Hud
import com.lambda.client.util.graphics.LambdaTessellator
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.safeListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
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

    private val color by setting("Color", Hud.primaryColor)
    private val throughBlocks by setting("Through Blocks", true)
    private val thickness by setting("Line Thickness", 2.0f, 0.25f..8.0f, 0.25f)

    private var pathJob: Job? = null
    private var scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    private var scheduledFuture: ScheduledFuture<*>? = null
    private var seed: Long = 146008555100680L // 2b2t nether seed
    private val pathLock: AtomicBoolean = AtomicBoolean(false)
    private var path: List<BlockPos>? = null

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
            // important: if this is called while there is no path pathing ongoing the next path will fail
            // thank you babbaj
            PathFinder.cancel()
        }
        pathJob?.cancel()
        pathJob = null
        scheduledFuture?.cancel(true)
        path = null
        pathLock.set(false)
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
                mc.addScheduledTask { resetAll() }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
    }

    private fun minPlayerDistanceToPath(path: MutableList<BlockPos>): Double {
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