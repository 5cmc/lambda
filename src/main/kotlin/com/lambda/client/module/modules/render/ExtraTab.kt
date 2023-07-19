package com.lambda.client.module.modules.render

import com.google.gson.JsonParser
import com.lambda.client.LambdaMod
import com.lambda.client.commons.utils.ConnectionUtils
import com.lambda.client.event.events.ConnectionEvent
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.listener.listener
import com.lambda.client.manager.managers.FriendManager
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.color.ColorConverter.rgbToHex
import com.lambda.client.util.color.EnumTextColor
import com.lambda.client.util.text.format
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.safeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.minecraft.client.gui.Gui
import net.minecraft.client.network.NetworkPlayerInfo
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.network.play.server.SPacketPlayerListItem
import net.minecraft.scoreboard.ScorePlayerTeam
import net.minecraftforge.fml.common.gameevent.TickEvent
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

object ExtraTab : Module(
    name = "ExtraTab",
    description = "Expands the player tab menu",
    category = Category.RENDER
) {
    private val tabSize by setting("Max Players", 265, 80..400, 5)
    val rowsPerColumn by setting("Players Per Column", 35, 20..50, 1)
    val highlightFriends by setting("Highlight Friends", true)
    private val color by setting("Color", EnumTextColor.GREEN, { highlightFriends })
    val ping by setting("Ping", Mode.SHOW)
    val displayBots by setting("Display bots", BotsMode.SHOW)
    val onlineTime by setting("2b2t Online Times", true)
    val onlineTimer by setting("Online timer", false, { onlineTime })
    val onlineBar by setting("Online Bar", true, { onlineTime })

    enum class Mode {
        SHOW, HIDDEN
    }
    enum class BotsMode {
        SHOW, HIDDEN, GREY
    }

    private const val maxOnlineSeconds = 21600L // 6 Hours, valid for 2b2t as of writing
    private const val apiUrl = "https://api.2b2t.vc/tablist"
    private const val botlistApiUrl = "https://api.2b2t.vc/bots/month"
    private val parser = JsonParser()
    private val dataUpdateTimer = TickTimer(TimeUnit.SECONDS)
    private var hasInitializedOnlineTimes = false
    private var botlistUpdateJob: Job? = null

    // playername -> joinedAt (epoch seconds)
    private var tablistData = ConcurrentHashMap<String, Long>()
    private var botlistData = HashSet<String>()

    init {

        onDisable {
            tablistData.clear()
            hasInitializedOnlineTimes = false
        }

        onEnable {
            hasInitializedOnlineTimes = false
        }

        safeListener<TickEvent.ClientTickEvent> {
            if (it.phase != TickEvent.Phase.END) return@safeListener
            if (onlineTime) {
                if (dataUpdateTimer.tick(300L) // API caches tablist data for 5 minutes
                    || !hasInitializedOnlineTimes) {
                    hasInitializedOnlineTimes = true
                    updateTabDataJoins()
                }
            }
            if (displayBots != BotsMode.SHOW && botlistData.isEmpty() && botlistUpdateJob?.isActive != true)
                getBotlistData()
        }

        listener<ConnectionEvent.Disconnect> {
            tablistData.clear()
            botlistData.clear()
            hasInitializedOnlineTimes = false
        }

        safeListener<PacketEvent.Receive> {
            if (it.packet !is SPacketPlayerListItem || !onlineTime) return@safeListener
            val now = Instant.now().epochSecond
            when(it.packet.action) {
                SPacketPlayerListItem.Action.ADD_PLAYER -> {
                    it.packet.entries.forEach { data ->
                        data.profile.name?.let { name ->
                            tablistData[name] = now
                        }
                    }
                }
                SPacketPlayerListItem.Action.REMOVE_PLAYER -> {
                    it.packet.entries.forEach { data ->
                        data.profile.name?.let { name ->
                            tablistData.remove(name)
                        }
                    }
                }
                else -> {}
            }
        }
    }

    private fun getBotlistData() {
        botlistUpdateJob = defaultScope.launch(Dispatchers.IO) {
            runCatching {
                ConnectionUtils.requestRawJsonFrom(botlistApiUrl) {
                    LambdaMod.LOG.error("Failed querying queue data", it)
                }?.let { data ->
                    botlistData = parser.parse(data).asJsonArray
                        .mapNotNull { it.asJsonObject.get("pname")?.asString }
                        .toHashSet()
                }
            }
        }
    }

    private fun updateTabDataJoins() {
        defaultScope.launch(Dispatchers.IO) {
            runCatching {
                ConnectionUtils.requestRawJsonFrom(apiUrl) {
                    LambdaMod.LOG.error("Failed querying queue data", it)
                }?.let { data ->
                    val json = parser.parse(data).asJsonArray
                    json.forEach { e ->
                        val jsonObject = e.asJsonObject
                        val playerName = jsonObject.get("playerName")?.asString
                        val joinTime = jsonObject.get("joinTime")?.asString
                        playerName?.let { name -> joinTime?.let{ time ->
                            mc.connection?.playerInfoMap?.find { it.gameProfile.name == name }?.let {
                                tablistData[name] = ZonedDateTime.parse(time).toEpochSecond()
                            }
                        }}
                    }
                }
            }
        }
    }

    @JvmStatic
    fun getPlayerName(info: NetworkPlayerInfo): String {
        val name = info.displayName?.formattedText
            ?: ScorePlayerTeam.formatPlayerName(info.playerTeam, info.gameProfile.name)
        var newName = name
        if (displayBots == BotsMode.GREY)
            if (botlistData.contains(name))
                newName = EnumTextColor.GRAY format newName
        if (highlightFriends)
            if (FriendManager.isFriend(name))
                newName = color format newName
        if (onlineTime && onlineTimer)
            tablistData[name]?.let {
                val duration = Instant.now().epochSecond - it
                newName += " [" +
                    (duration / 3600).toString() + ":" +
                    ((duration % 3600) / 60).toString().padStart(2, '0') +
                    "]"
            } ?: run { newName += " [0:00]" }
        return newName
    }

    @JvmStatic
    fun subList(list: List<NetworkPlayerInfo>, newList: List<NetworkPlayerInfo>): List<NetworkPlayerInfo> {
        if (isEnabled) {
            val modifiedList: List<NetworkPlayerInfo> =
                if (displayBots == BotsMode.HIDDEN) list.filterNot { it.gameProfile.name in botlistData }.toList()
                else list
            return modifiedList.subList(0, tabSize.coerceAtMost(modifiedList.size))
        } else {
            return newList
        }
    }

    @JvmStatic
    fun drawExpendRect(left: Int, top: Int, right: Int, bottom: Int, playerName: String) {
        tablistData[playerName]?.let {
            val barStart = left + 8 // head icon 8 wide
            val barPoint = (barStart + ((right - barStart) * ((Instant.now().epochSecond - it).toDouble() / maxOnlineSeconds)).toInt())
            Gui.drawRect(barPoint, top, right, bottom, 553648127)
            Gui.drawRect(barStart, top, barPoint, bottom, rgbToHex(0, 255, 0, 100))
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f)
            GlStateManager.enableBlend()
        }
    }
}





