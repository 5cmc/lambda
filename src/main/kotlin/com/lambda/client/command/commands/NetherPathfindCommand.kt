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
        val seed: Long = 146008555100680L
        resetAll()
        pathFuture = executor.submit {
            val t1 = System.currentTimeMillis()
            var longs: LongArray = LongArray(0)
            try {
                longs = PathFinder.pathFind(seed, false, true, mc.player.posX.toInt(), mc.player.posY.toInt(), mc.player.posZ.toInt(), x, 64, z)
            } catch (e: Throwable) {
                MessageSendHelper.sendChatMessage("path find failed")
            }

            // TODO: native code should check the interrupt flag and throw InterruptedException
            if (Thread.currentThread().isInterrupted) {
                return@submit
            }
            val t2 = System.currentTimeMillis()
            val path: MutableList<BlockPos> = Arrays.stream(longs).mapToObj { serialized: Long -> BlockPos.fromLong(serialized) }.collect(Collectors.toList())
            mc.addScheduledTask {
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
        resetAll()
        val theta: Float = Math.toRadians(mc.player.rotationYawHead.toDouble()).toFloat()
        val destX = (mc.player.posX - MathHelper.sin(theta) * dist).toInt()
        val destZ = (mc.player.posZ + MathHelper.cos(theta) * dist).toInt()

        val seed: Long = 146008555100680L
        pathFuture = executor.submit {
            val t1 = System.currentTimeMillis()
            var longs: LongArray = LongArray(0)
            try {
                longs = PathFinder.pathFind(seed, false, true, mc.player.posX.toInt(), mc.player.posY.toInt(), mc.player.posZ.toInt(), destX, 64, destZ)
            } catch (e: Throwable) {
                MessageSendHelper.sendChatMessage("path find failed")
            }

            // TODO: native code should check the interrupt flag and throw InterruptedException
            if (Thread.currentThread().isInterrupted) {
                return@submit
            }
            val t2 = System.currentTimeMillis()
            val path: MutableList<BlockPos> = Arrays.stream(longs).mapToObj { serialized: Long -> BlockPos.fromLong(serialized) }.collect(Collectors.toList())
            mc.addScheduledTask {
                registerRenderer(path)
                pathFuture = null
                scheduledFuture?.cancel(true)
                scheduledFuture = scheduledExecutor.scheduleAtFixedRate({ scheduledGotoRepathCheck(path, destX, destZ) }, 5000, 5000, TimeUnit.MILLISECONDS)
                MessageSendHelper.sendChatMessage(String.format("Found path in %.2f seconds", (t2 - t1) / 1000.0))
            }
        }
    }

    private fun setSeed(newSeed: Long) {
        this.seed = newSeed
    }

    private fun resetAll() {
        pathFuture?.cancel(true)
        scheduledFuture?.cancel(true)
        resetRenderer()
    }

    private fun scheduledGotoRepathCheck(path: MutableList<BlockPos>, destX: Int, destZ: Int) {
        val playerCurPos: BlockPos = mc.player.position
        val anyClose: Boolean = path.any { it.distanceTo(playerCurPos) < 100.0 }
        if (!anyClose) {
            MessageSendHelper.sendChatMessage("Moved too far from path, repathing...")
            MessageSendHelper.sendLambdaCommand("npath goto $destX $destZ")
            scheduledFuture?.cancel(true)
        }
        if (path.last().distanceTo(playerCurPos) < 25.0) {
            MessageSendHelper.sendChatMessage("Pathing goal completed, stopping..")
            mc.addScheduledTask { resetAll() }
        }
    }

    private fun resetRenderer() {
        if (renderer != null) {
            MinecraftForge.EVENT_BUS.unregister(renderer)
            renderer!!.deleteBuffer()
            renderer = null
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
}