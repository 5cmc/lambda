package com.lambda.client.module.modules.render

import com.google.gson.JsonParser
import com.lambda.client.LambdaMod
import com.lambda.client.commons.utils.ConnectionUtils
import com.lambda.client.event.events.PacketEvent
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
import kotlinx.coroutines.launch
import net.minecraft.client.gui.Gui
import net.minecraft.client.network.NetworkPlayerInfo
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.network.play.server.SPacketPlayerListItem
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
    val ping by setting("Ping", Mode.SHOW)
    val onlineTime by setting("2b2t Online Times", true)
    val onlineTimer by setting("Online timer", false, { onlineTime })
    val onlineBar by setting("Online Bar", true, { onlineTime })

    enum class Mode {
        SHOW, HIDDEN
    }

    private const val maxOnlineSeconds = 21600L // 6 Hours, valid for 2b2t as of writing
    private const val apiUrl = "https://api.2b2t.vc/tablist"
    private val parser = JsonParser()
    private val dataUpdateTimer = TickTimer(TimeUnit.SECONDS)
    private val durationDataUpdateTimer = TickTimer(TimeUnit.SECONDS)
    private var hasInitialized = false

    var tablistData = HashMap<String, HashMap<String, Any>>()

    init {
        onDisable {
            tablistData.clear()
            hasInitialized = false
        }
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
        safeListener<PacketEvent.Receive> {
            if (onlineTime) {
                if (it.packet !is SPacketPlayerListItem) return@safeListener
                val now = Instant.now().epochSecond
                if (it.packet.action == SPacketPlayerListItem.Action.ADD_PLAYER) {
                    val name = it.packet.entries[0].profile.name
                    if (name == null) return@safeListener
                    tablistData[name] = HashMap()
                    tablistData[name]?.set("joinedAt", now)
                    tablistData[name]?.set("duration", 0)
                    tablistData[name]?.set("expend", 0.0F)
                } else if (it.packet.action == SPacketPlayerListItem.Action.REMOVE_PLAYER) {
                    val name = it.packet.entries[0].profile.name
                    if (name == null) return@safeListener
                    tablistData.remove(name)
                }
            }
        }
    }

    private fun updateTabDataJoins() {
        defaultScope.launch(Dispatchers.IO) {
            runCatching {
                ConnectionUtils.requestRawJsonFrom(apiUrl) {
                    LambdaMod.LOG.error("Failed querying queue data", it)
                }?.let {
                    tablistData.clear()
                    val json = parser.parse(it).asJsonArray

                    json.forEach {e -> tablistData[e.asJsonObject.get("playerName").asString] = HashMap()
                        tablistData[e.asJsonObject.get("playerName").asString]?.set("joinedAt", ZonedDateTime.parse(e.asJsonObject.get("joinTime").asString).toEpochSecond())
                    }
                    updateTabDataDurations()
                    return@runCatching
                }
            }
        }
    }
    private fun updateTabDataDurations() {
        val now = Instant.now().epochSecond
        for (key in tablistData.keys) {
            val joinedAt = (tablistData[key]?.get("joinedAt") as Long?)!!
            val duration = (now - joinedAt)
            tablistData[key]?.set("duration", duration)
            tablistData[key]?.set("expend", (duration.toDouble() / maxOnlineSeconds).toFloat())
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
                    val duration = tablistData[name]?.get("duration") as Long?
                    newName += " [" + (duration!! / 3600).toString() + ":" + ((duration % 3600) / 60).toString().padStart(2, '0') + "]"
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
    @JvmStatic
    fun drawExpendRect(left: Int, top: Int, right: Int, bottom: Int, playerName: String) {
        val width = right - left
        var barPoint = left
        try {
            if (tablistData.containsKey(playerName)) {
                val expend = tablistData[playerName]?.get("expend") as Float
                barPoint = (left + (width*expend).toInt())
            }
        } catch (ex: Exception) {
            println(ex)
        }

        Gui.drawRect(barPoint, top, right, bottom, 553648127)
        Gui.drawRect(left, top, barPoint, bottom, rgbToHex(0, 255, 0, 100))
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f)
        GlStateManager.enableAlpha()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO)
    }




}





