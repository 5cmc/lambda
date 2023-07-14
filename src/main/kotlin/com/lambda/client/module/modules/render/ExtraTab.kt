package com.lambda.client.module.modules.render

import com.google.gson.JsonParser
import com.lambda.client.LambdaMod
import com.lambda.client.commons.utils.ConnectionUtils
import com.lambda.client.manager.managers.FriendManager
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.color.EnumTextColor
import com.lambda.client.util.text.format
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.safeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.minecraft.client.network.NetworkPlayerInfo
import net.minecraft.scoreboard.ScorePlayerTeam
import net.minecraftforge.fml.common.gameevent.TickEvent
import java.time.Instant
import java.time.ZonedDateTime

object ExtraTab : Module(
    name = "ExtraTab",
    description = "Expands the player tab menu",
    category = Category.RENDER
) {
    private val tabSize by setting("Max Players", 265, 80..400, 5)
    val highlightFriends by setting("Highlight Friends", true)
    private val color by setting("Color", EnumTextColor.GREEN, { highlightFriends })
    private val onlineTime by setting("2b2t Online Times", true)


    private const val apiUrl = "https://api.2b2t.vc/tablist"
    private val parser = JsonParser()
    private val dataUpdateTimer = TickTimer(TimeUnit.SECONDS)
    private val durationDataUpdateTimer = TickTimer(TimeUnit.SECONDS)
    private var hasInitialized = false

    var tablistData = HashMap<String, HashMap<String, Long>>()

    init {
        safeListener<TickEvent.ClientTickEvent> {
            if (onlineTime) {
                if (dataUpdateTimer.tick(300L) // API caches tablist data for 5 minutes
                    || !hasInitialized) {
                    hasInitialized = true
                    updateTabDataJoins()
                } else if (durationDataUpdateTimer.tick(1L))
                    updateTabDataDurations()
            }
        }
    }

    private fun updateTabDataJoins() {
        defaultScope.launch(Dispatchers.IO) {
//            MessageSendHelper.sendChatMessage("updating")
            runCatching {
                ConnectionUtils.requestRawJsonFrom(ExtraTab.apiUrl) {
                    LambdaMod.LOG.error("Failed querying queue data", it)
                }?.let {
                    tablistData.clear()
                    val json = parser.parse(it).asJsonArray

                    json.forEach {e -> tablistData[e.asJsonObject.get("playerName").asString] = HashMap();
                        tablistData[e.asJsonObject.get("playerName").asString]?.set("joinedAt", ZonedDateTime.parse(e.asJsonObject.get("joinTime").asString).toEpochSecond())
//                        tablistData[e.asJsonObject.get("playerName").asString]?.set("duration", 0L)
                    }
                    updateTabDataDurations()
                    return@runCatching

//                    LambdaMod.LOG.error("No tablist data received. Is 2b2t down?")
                }
            }
        }
    }
    private fun updateTabDataDurations() {
        val now = Instant.now().epochSecond
        for (key in tablistData.keys) {
            tablistData[key]?.set("duration", now - tablistData[key]?.get("joinedAt")!!)
        }
    }


    @JvmStatic
    fun getPlayerName(info: NetworkPlayerInfo): String {
        val name = info.displayName?.formattedText
            ?: ScorePlayerTeam.formatPlayerName(info.playerTeam, info.gameProfile.name)
        var newName = name
        if (highlightFriends)
            if (FriendManager.isFriend(name))
                newName = color format newName
        if (onlineTime)
            try {
                if (tablistData.containsKey(name)) {
                    val duration = tablistData[name]?.get("duration")
                    newName += " [" + (duration!! / 3600).toString() + ":" + ((duration!! % 3600) / 60).toString().padStart(2, '0') + "]"
                }
            } catch (_: Exception) { }
//        var online = Duration.ofSeconds(Instant.now().epochSecond - tablistData[name]!!)
////        name += " [" + online.toHours().toString()
//        return name

        return newName
    }

    @JvmStatic
    fun subList(list: List<NetworkPlayerInfo>, newList: List<NetworkPlayerInfo>): List<NetworkPlayerInfo> {
        return if (isDisabled) newList else list.subList(0, tabSize.coerceAtMost(list.size))
    }




}





