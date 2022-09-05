package com.lambda.client.command.commands

import com.lambda.client.command.ClientCommand
import com.lambda.client.util.NetherPathFinderRenderer
import com.lambda.client.util.text.MessageSendHelper
import com.babbaj.pathfinder.PathFinder
import net.minecraft.util.math.BlockPos
import net.minecraftforge.common.MinecraftForge
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.stream.Collectors

object NetherPathfindCommand : ClientCommand(
    name = "path",
    alias = arrayOf("p")
) {
    private var pathFuture: Future<*>? = null
    private val executor = Executors.newCachedThreadPool()
    private var renderer: NetherPathFinderRenderer? = null

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
    }

    private fun goto(x: Int, z: Int) {
        val seed: Long = 146008555100680L

        if (pathFuture != null) {
            pathFuture!!.cancel(true)
            pathFuture = null
            MessageSendHelper.sendChatMessage("Canceled existing pathfinder")
        }
        resetRenderer()
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
            val path = Arrays.stream(longs).mapToObj { serialized: Long -> BlockPos.fromLong(serialized) }.collect(Collectors.toList())
            mc.addScheduledTask {
                registerRenderer(path)
                pathFuture = null
                MessageSendHelper.sendChatMessage(String.format("Found path in %.2f seconds", (t2 - t1) / 1000.0))
            }
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