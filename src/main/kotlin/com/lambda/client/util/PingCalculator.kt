package com.lambda.client.util

import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.ConnectionEvent
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.listener.listener
import com.lambda.client.util.threads.onMainThreadSafe
import com.lambda.client.util.threads.safeListener
import kotlinx.coroutines.runBlocking
import net.minecraft.inventory.ClickType
import net.minecraft.network.play.client.CPacketClickWindow
import net.minecraft.network.play.server.SPacketConfirmTransaction
import net.minecraftforge.fml.common.gameevent.TickEvent

object PingCalculator {

    private val transactTimer: TickTimer = TickTimer(TimeUnit.MILLISECONDS)
    private val transactIntervalMs = 5000
    private var startTransactTime: Long = 0L
    private var lastActionNumber: Short = 1000
    var ping: Int = 0; // ms

    init {
        safeListener<PacketEvent.Receive> {
            if (it.packet is SPacketConfirmTransaction) {
                if (it.packet.actionNumber == lastActionNumber) {
                    val now = System.nanoTime()
                    ping = ((now - startTransactTime) / 1000000L).toInt()
                }
            }
        }

        safeListener<TickEvent.ClientTickEvent> {
            if (it.phase != TickEvent.Phase.END) {
                if (transactTimer.tick(transactIntervalMs, true)) {
                    lastActionNumber = sendTransaction()
                    startTransactTime = System.nanoTime()
                }
            }
        }

        listener<ConnectionEvent.Connect> {
            transactTimer.reset()
        }
    }

    private fun SafeClientEvent.sendTransaction(): Short {
        val container = player.inventoryContainer
        val playerInventory = player.inventory
        val transactionID = container.getNextTransactionID(playerInventory)
        // not sure if its necessary to actually have a valid itemstack here
        // if not can could just send air
        val itemStack = container.slotClick(0, 0, ClickType.CLONE, player)

        connection.sendPacket(CPacketClickWindow(0, 0, 0, ClickType.CLONE, itemStack, transactionID))
        runBlocking {
            onMainThreadSafe { playerController.updateController() }
        }
        return transactionID
    }
}