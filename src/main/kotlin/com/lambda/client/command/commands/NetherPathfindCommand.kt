package com.lambda.client.command.commands

import com.babbaj.pathfinder.PathFinder
import com.lambda.client.command.ClientCommand
import com.lambda.client.util.NetherPathFinderRenderer
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.text.MessageSendHelper
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import net.minecraftforge.common.MinecraftForge
import java.util.*
import java.util.concurrent.*
import java.util.stream.Collectors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

object NetherPathfindCommand : ClientCommand(
    name = "npath",
    alias = arrayOf("p")
) {
    private var pathFuture: Future<*>? = null
    private val executor = Executors.newCachedThreadPool()
    private var renderer: NetherPathFinderRenderer? = null
    private var scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    private var scheduledFuture: ScheduledFuture<*>? = null
    private var seed: Long = 146008555100680L // 2b2t nether seed

    init {
        literal("goto") {
            int("x") {x ->
                int("z") {z ->
                    executeSafe {
                        goto(x.value, z.value)
                    }
                }
            }
        }
        literal("cancel", "stop") {
            executeSafe {
                cancel()
            }
        }
        literal("thisway") {
            int("dist") { dist ->
                executeSafe {
                    thisWay(dist.value)
                }
            }
        }
        literal("seed") {
            long("s") { newSeed ->
                executeSafe { setSeed(newSeed.value) }
            }
        }
    }

    private fun goto(x: Int, z: Int) {
        pathFuture = executor.submit {
            val t1 = System.currentTimeMillis()
            var longs: LongArray
            try {
                longs = PathFinder.pathFind(this.seed, false, true, mc.player.posX.toInt(), mc.player.posY.toInt(), mc.player.posZ.toInt(), x, 64, z)
            } catch (e: Throwable) {
                MessageSendHelper.sendChatMessage("path find failed")
                return@submit
            }

            val t2 = System.currentTimeMillis()
            val path: MutableList<BlockPos> = Arrays.stream(longs).mapToObj { serialized: Long -> BlockPos.fromLong(serialized) }.collect(Collectors.toList())
            mc.addScheduledTask {
                resetRenderer()
                registerRenderer(path)
                pathFuture = null
                scheduledFuture?.cancel(true)
                scheduledFuture = scheduledExecutor.scheduleAtFixedRate({ scheduledGotoRepathCheck(path, x, z) }, 5000, 5000, TimeUnit.MILLISECONDS)
                MessageSendHelper.sendChatMessage(String.format("Found path in %.2f seconds", (t2 - t1) / 1000.0))
            }
        }
    }

    private fun cancel() {
        resetAll()
        MessageSendHelper.sendChatMessage("Cancelled pathing")
    }

    private fun thisWay(dist: Int) {
        val theta: Float = Math.toRadians(mc.player.rotationYawHead.toDouble()).toFloat()
        val destX = (mc.player.posX - MathHelper.sin(theta) * dist).toInt()
        val destZ = (mc.player.posZ + MathHelper.cos(theta) * dist).toInt()
        goto(destX, destZ)
    }

    private fun setSeed(newSeed: Long) {
        this.seed = newSeed
        MessageSendHelper.sendChatMessage(String.format("Seed set: %s", newSeed))
    }

    private fun resetAll() {
        pathFuture?.cancel(true)
        scheduledFuture?.cancel(true)
        mc.addScheduledTask { resetRenderer() }.get(1, TimeUnit.SECONDS)
    }

    private fun scheduledGotoRepathCheck(path: MutableList<BlockPos>, destX: Int, destZ: Int) {
        if (!isInNether()) {
            return
        }
        val dist = minPlayerDistanceToPath(path)
        val playerFarFromPath: Boolean = dist > 100.0
        if (playerFarFromPath) {
            MessageSendHelper.sendChatMessage("Moved too far from path, repathing...")
            MessageSendHelper.sendLambdaCommand("npath goto $destX $destZ")
            scheduledFuture?.cancel(true)
        } else if (path.last().distanceTo(mc.player.position) < 25.0) {
            MessageSendHelper.sendChatMessage("Pathing goal completed, stopping..")
            mc.addScheduledTask { resetAll() }
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

    private fun resetRenderer() {
        if (renderer != null) {
            try {
                MinecraftForge.EVENT_BUS.unregister(renderer)
                renderer!!.deleteBuffer() // throws if we're on the wrong thread or buffer does not exist for some reason
            } finally {
                renderer = null
            }
        }
    }

    private fun registerRenderer(path: List<BlockPos>) {
        if (renderer != null) {
            disableRenderer()
            renderer!!.deleteBuffer()
        }
        renderer = NetherPathFinderRenderer(path)
        MinecraftForge.EVENT_BUS.register(renderer)
    }

    private fun disableRenderer() {
        if (renderer != null) {
            MinecraftForge.EVENT_BUS.unregister(renderer)
        }
    }

    private fun isInNether(): Boolean {
        return mc.world != null && mc.player.dimension == -1
    }
}