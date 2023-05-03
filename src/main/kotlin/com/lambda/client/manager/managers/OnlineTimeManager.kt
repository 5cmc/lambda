package com.lambda.client.manager.managers

import com.lambda.client.event.events.ConnectionEvent
import com.lambda.client.event.listener.listener
import com.lambda.client.manager.Manager
import java.time.Duration
import java.time.Instant

object OnlineTimeManager: Manager {

    var connectTime: Instant = Instant.EPOCH
        private set
    private var isOnline = false

    init {
        listener<ConnectionEvent.Connect> {
            connectTime = Instant.now()
            isOnline = true
        }

        listener<ConnectionEvent.Disconnect> {
            isOnline = false
        }
    }

    fun getOnlineTime(): Duration? {
        if (!isOnline) return null
        return Duration.between(connectTime, Instant.now())
    }
}