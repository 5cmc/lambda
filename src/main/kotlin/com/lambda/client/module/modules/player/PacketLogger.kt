package com.lambda.client.module.modules.player

import com.lambda.client.LambdaMod
import com.lambda.client.commons.interfaces.DisplayEnum
import com.lambda.client.event.events.ConnectionEvent
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.listener.listener
import com.lambda.client.gui.hudgui.elements.misc.PacketLogViewer
import com.lambda.client.mixin.extension.*
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.FolderUtils
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.runSafe
import com.lambda.client.util.threads.safeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.minecraft.block.Block
import net.minecraft.item.crafting.CraftingManager
import net.minecraft.network.Packet
import net.minecraft.network.play.client.*
import net.minecraft.network.play.server.*
import net.minecraft.potion.Potion
import net.minecraft.util.math.BlockPos
import net.minecraft.util.text.ITextComponent
import net.minecraft.util.text.TextFormatting
import net.minecraftforge.fml.common.gameevent.TickEvent
import java.io.File
import java.io.FileWriter
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object PacketLogger : Module(
    name = "PacketLogger",
    description = "Logs sent packets to a file or chat",
    category = Category.PLAYER
) {
    private val page by setting("Page", Page.GENERAL)
    private val categorySetting by setting("Category", CategorySlider.PLAYER, { page == Page.CLIENT || page == Page.SERVER })
    private val packetSide by setting("Packet Side", PacketSide.BOTH, description = "Log packets from the server, from the client, or both.", visibility = { page == Page.GENERAL })
    private val absoluteTime by setting("Absolute Time", true, description = "Show absolute time.", visibility = { page == Page.GENERAL })
    private val startDelta by setting("Start Time Delta", false, visibility = { page == Page.GENERAL })
    private val lastDelta by setting("Last Time Delta", false, visibility = { page == Page.GENERAL })
    private val showClientTicks by setting("Show Client Ticks", true, description = "Show timestamps of client ticks.", visibility = { page == Page.GENERAL })
    private val ignoreCancelled by setting("Ignore Cancelled", true, description = "Ignore cancelled packets.", visibility = { page == Page.GENERAL })
    val logMode by setting("Log Mode", LogMode.ALL, description = "Log to chat, to a file, HUD, or both.", visibility = { page == Page.GENERAL })
    private val captureTiming by setting("Capture Timing", CaptureTiming.POST, description = "Sets point of time for scan event.", visibility = { page == Page.GENERAL })
    private val openLogFolder by setting("Open Log Folder...", false, consumer = { _, _ ->
        FolderUtils.openFolder(FolderUtils.packetLogFolder)
        false
    }, visibility = { page == Page.GENERAL })

    /**
     * Client Packets
     */
    private val toggleAllClientPackets by setting("Toggle All Client Packets", false, visibility = { page == Page.CLIENT }, consumer = { _, _ ->
        val toggleValue = anyClientPacketDisabled()
        cPacketAnimation.value = toggleValue
        cPacketChatMessage.value = toggleValue
        cPacketClickWindow.value = toggleValue
        cPacketClientSettings.value = toggleValue
        cPacketClientStatus.value = toggleValue
        cPacketCloseWindow.value = toggleValue
        cPacketConfirmTeleport.value = toggleValue
        cPacketConfirmTransaction.value = toggleValue
        cPacketCreativeInventoryAction.value = toggleValue
        cPacketCustomPayload.value = toggleValue
        cPacketEnchantItem.value = toggleValue
        cPacketEntityAction.value = toggleValue
        cPacketHeldItemChange.value = toggleValue
        cPacketInput.value = toggleValue
        cPacketKeepAlive.value = toggleValue
        cPacketPlaceRecipe.value = toggleValue
        cPacketPlayerRotation.value = toggleValue
        cPacketPlayerPosition.value = toggleValue
        cPacketPlayerPositionRotation.value = toggleValue
        cPacketPlayer.value = toggleValue
        cPacketPlayerAbilities.value = toggleValue
        cPacketPlayerDigging.value = toggleValue
        cPacketPlayerTryUseItem.value = toggleValue
        cPacketPlayerTryUseItemOnBlock.value = toggleValue
        cPacketRecipeInfo.value = toggleValue
        cPacketResourcePackStatus.value = toggleValue
        cPacketSeenAdvancements.value = toggleValue
        cPacketSpectate.value = toggleValue
        cPacketSteerBoat.value = toggleValue
        cPacketTabComplete.value = toggleValue
        cPacketUpdateSign.value = toggleValue
        cPacketUseEntity.value = toggleValue
        cPacketVehicleMove.value = toggleValue
        false
    })

    private fun anyClientPacketDisabled(): Boolean {
        return !cPacketAnimation.value || !cPacketChatMessage.value || !cPacketClickWindow.value || !cPacketClientSettings.value || !cPacketClientStatus.value || !cPacketCloseWindow.value
            || !cPacketConfirmTeleport.value || !cPacketConfirmTransaction.value || !cPacketCreativeInventoryAction.value || !cPacketCustomPayload.value || !cPacketEnchantItem.value
            || !cPacketEntityAction.value || !cPacketHeldItemChange.value || !cPacketInput.value || !cPacketKeepAlive.value || !cPacketPlaceRecipe.value
            || !cPacketPlayerRotation.value || !cPacketPlayerPosition.value || !cPacketPlayerPositionRotation.value || !cPacketPlayer.value
            || !cPacketPlayerAbilities.value || !cPacketPlayerDigging.value || !cPacketPlayerTryUseItem.value || !cPacketPlayerTryUseItemOnBlock.value || !cPacketRecipeInfo.value
            || !cPacketResourcePackStatus.value || !cPacketSeenAdvancements.value || !cPacketSpectate.value || !cPacketSteerBoat.value || !cPacketTabComplete.value
            || !cPacketUpdateSign.value || !cPacketUseEntity.value || !cPacketVehicleMove.value
    }

    /** Player **/
    private val cPacketAnimation = setting("CPacketAnimation", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.PLAYER })
    private val cPacketConfirmTeleport = setting("CPacketConfirmTeleport", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.PLAYER })
    private val cPacketInput = setting("CPacketInput", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.PLAYER })
    private val cPacketPlayerRotation = setting("CPacketPlayer.Rotation", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.PLAYER })
    private val cPacketPlayerPosition = setting("CPacketPlayer.Position", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.PLAYER })
    private val cPacketPlayerPositionRotation = setting("CPacketPlayer.PositionRotation", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.PLAYER })
    private val cPacketPlayer = setting("CPacketPlayer", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.PLAYER })
    private val cPacketPlayerAbilities = setting("CPacketPlayerAbilities", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.PLAYER })
    private val cPacketPlayerDigging = setting("CPacketPlayerDigging", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.PLAYER })
    private val cPacketPlayerTryUseItem = setting("CPacketPlayerTryUseItem", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.PLAYER })
    private val cPacketPlayerTryUseItemOnBlock = setting("CPacketPlayerTryUseItemOnBlock", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.PLAYER })
    private val cPacketSpectate = setting("CPacketSpectate", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.PLAYER })

    /** Inventory **/
    private val cPacketClickWindow = setting("CPacketClickWindow", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.INVENTORY })
    private val cPacketCloseWindow = setting("CPacketCloseWindow", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.INVENTORY })
    private val cPacketConfirmTransaction = setting("CPacketConfirmTransaction", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.INVENTORY })
    private val cPacketCreativeInventoryAction = setting("CPacketCreativeInventoryAction", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.INVENTORY })
    private val cPacketEnchantItem = setting("CPacketEnchantItem", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.INVENTORY })
    private val cPacketHeldItemChange = setting("CPacketHeldItemChange", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.INVENTORY })
    private val cPacketPlaceRecipe = setting("CPacketPlaceRecipe", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.INVENTORY })
    private val cPacketRecipeInfo = setting("CPacketRecipeInfo", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.INVENTORY })

    /** System **/
    private val cPacketChatMessage = setting("CPacketChatMessage", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.SYSTEM })
    private val cPacketClientSettings = setting("CPacketClientSettings", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.SYSTEM })
    private val cPacketClientStatus = setting("CPacketClientStatus", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.SYSTEM })
    private val cPacketCustomPayload = setting("CPacketCustomPayload", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.SYSTEM })
    private val cPacketKeepAlive = setting("CPacketKeepAlive", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.SYSTEM })
    private val cPacketResourcePackStatus = setting("CPacketResourcePackStatus", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.SYSTEM })
    private val cPacketSeenAdvancements = setting("CPacketSeenAdvancements", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.SYSTEM })
    private val cPacketTabComplete = setting("CPacketTabComplete", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.SYSTEM })

    /** World **/
    private val cPacketUpdateSign = setting("CPacketUpdateSign", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.WORLD })

    /** Entity **/
    private val cPacketEntityAction = setting("CPacketEntityAction", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.ENTITY })
    private val cPacketSteerBoat = setting("CPacketSteerBoat", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.ENTITY })
    private val cPacketUseEntity = setting("CPacketUseEntity", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.ENTITY })
    private val cPacketVehicleMove = setting("CPacketVehicleMove", true, visibility = { page == Page.CLIENT && categorySetting == CategorySlider.ENTITY })


    /**
     * Server Packets
     */

    private val toggleAllServerPackets by setting("Toggle All Server Packets", false, visibility = { page == Page.SERVER }, consumer = { _, _ ->
        val toggleValue = anyServerPacketDisabled()
        sPacketAdvancementInfo.value = toggleValue
        sPacketAnimation.value = toggleValue
        sPacketBlockAction.value = toggleValue
        sPacketBlockBreakAnim.value = toggleValue
        sPacketBlockChange.value = toggleValue
        sPacketCamera.value = toggleValue
        sPacketChangeGameState.value = toggleValue
        sPacketChat.value = toggleValue
        sPacketChunkData.value = toggleValue
        sPacketCloseWindow.value = toggleValue
        sPacketCollectItem.value = toggleValue
        sPacketCombatEvent.value = toggleValue
        sPacketConfirmTransaction.value = toggleValue
        sPacketCooldown.value = toggleValue
        sPacketCustomPayload.value = toggleValue
        sPacketCustomSound.value = toggleValue
        sPacketDestroyEntities.value = toggleValue
        sPacketDisconnect.value = toggleValue
        sPacketDisplayObjective.value = toggleValue
        sPacketEffect.value = toggleValue
        s15PacketEntityRelMove.value = toggleValue
        s16PacketEntityLook.value = toggleValue
        s17PacketEntityLookMove.value = toggleValue
        sPacketEntity.value = toggleValue
        sPacketEntityAttach.value = toggleValue
        sPacketEntityEffect.value = toggleValue
        sPacketEntityEquipment.value = toggleValue
        sPacketEntityHeadLook.value = toggleValue
        sPacketEntityMetadata.value = toggleValue
        sPacketEntityProperties.value = toggleValue
        sPacketEntityStatus.value = toggleValue
        sPacketEntityTeleport.value = toggleValue
        sPacketEntityVelocity.value = toggleValue
        sPacketExplosion.value = toggleValue
        sPacketHeldItemChange.value = toggleValue
        sPacketJoinGame.value = toggleValue
        sPacketKeepAlive.value = toggleValue
        sPacketMaps.value = toggleValue
        sPacketMoveVehicle.value = toggleValue
        sPacketMultiBlockChange.value = toggleValue
        sPacketOpenWindow.value = toggleValue
        sPacketParticles.value = toggleValue
        sPacketPlaceGhostRecipe.value = toggleValue
        sPacketPlayerAbilities.value = toggleValue
        sPacketPlayerListHeaderFooter.value = toggleValue
        sPacketPlayerListItem.value = toggleValue
        sPacketPlayerPosLook.value = toggleValue
        sPacketRecipeBook.value = toggleValue
        sPacketRemoveEntityEffect.value = toggleValue
        sPacketResourcePackSend.value = toggleValue
        sPacketRespawn.value = toggleValue
        sPacketScoreboardObjective.value = toggleValue
        sPacketSelectAdvancementsTab.value = toggleValue
        sPacketServerDifficulty.value = toggleValue
        sPacketSetExperience.value = toggleValue
        sPacketSetPassengers.value = toggleValue
        sPacketSetSlot.value = toggleValue
        sPacketSignEditorOpen.value = toggleValue
        sPacketSoundEffect.value = toggleValue
        sPacketSpawnExperienceOrb.value = toggleValue
        sPacketSpawnGlobalEntity.value = toggleValue
        sPacketSpawnMob.value = toggleValue
        sPacketSpawnObject.value = toggleValue
        sPacketSpawnPainting.value = toggleValue
        sPacketSpawnPlayer.value = toggleValue
        sPacketSpawnPosition.value = toggleValue
        sPacketStatistics.value = toggleValue
        sPacketTabComplete.value = toggleValue
        sPacketTeams.value = toggleValue
        sPacketTimeUpdate.value = toggleValue
        sPacketTitle.value = toggleValue
        sPacketUnloadChunk.value = toggleValue
        sPacketUpdateBossInfo.value = toggleValue
        sPacketUpdateHealth.value = toggleValue
        sPacketUpdateScore.value = toggleValue
        sPacketUpdateTileEntity.value = toggleValue
        sPacketUseBed.value = toggleValue
        sPacketWindowItems.value = toggleValue
        sPacketWindowProperty.value = toggleValue
        sPacketWorldBorder.value = toggleValue
        false
    })

    private fun anyServerPacketDisabled() : Boolean {
        return !sPacketAdvancementInfo.value || !sPacketAnimation.value || !sPacketBlockAction.value || !sPacketBlockBreakAnim.value || !sPacketBlockChange.value
            || !sPacketCamera.value || !sPacketChangeGameState.value || !sPacketChat.value || !sPacketChunkData.value || !sPacketCloseWindow.value || !sPacketCollectItem.value
            || !sPacketCombatEvent.value || !sPacketConfirmTransaction.value || !sPacketCooldown.value || !sPacketCustomPayload.value || !sPacketCustomSound.value
            || !sPacketDestroyEntities.value || !sPacketDisconnect.value || !sPacketDisplayObjective.value || !sPacketEffect.value
            || !s15PacketEntityRelMove.value || !s16PacketEntityLook.value || !s17PacketEntityLookMove.value || !sPacketEntity.value || !sPacketEntityAttach.value
            || !sPacketEntityEffect.value || !sPacketEntityEquipment.value || !sPacketEntityHeadLook.value || !sPacketEntityMetadata.value || !sPacketEntityProperties.value
            || !sPacketEntityStatus.value || !sPacketEntityTeleport.value || !sPacketEntityVelocity.value || !sPacketExplosion.value || !sPacketHeldItemChange.value
            || !sPacketJoinGame.value || !sPacketKeepAlive.value || !sPacketMaps.value || !sPacketMoveVehicle.value || !sPacketMultiBlockChange.value || !sPacketOpenWindow.value
            || !sPacketParticles.value || !sPacketPlaceGhostRecipe.value || !sPacketPlayerAbilities.value || !sPacketPlayerListHeaderFooter.value || !sPacketPlayerListItem.value
            || !sPacketPlayerPosLook.value || !sPacketRecipeBook.value || !sPacketRemoveEntityEffect.value || !sPacketResourcePackSend.value || !sPacketRespawn.value
            || !sPacketScoreboardObjective.value || !sPacketSelectAdvancementsTab.value || !sPacketServerDifficulty.value || !sPacketSetExperience.value || !sPacketSetPassengers.value
            || !sPacketSetSlot.value || !sPacketSignEditorOpen.value || !sPacketSoundEffect.value || !sPacketSpawnExperienceOrb.value || !sPacketSpawnGlobalEntity.value
            || !sPacketSpawnMob.value || !sPacketSpawnObject.value || !sPacketSpawnPainting.value || !sPacketSpawnPlayer.value || !sPacketSpawnPosition.value || !sPacketStatistics.value
            || !sPacketTabComplete.value || !sPacketTeams.value || !sPacketTimeUpdate.value || !sPacketTitle.value || !sPacketUnloadChunk.value || !sPacketUpdateBossInfo.value
            || !sPacketUpdateHealth.value || !sPacketUpdateScore.value || !sPacketUpdateTileEntity.value || !sPacketUseBed.value || !sPacketWindowItems.value || !sPacketWindowProperty.value
            || !sPacketWorldBorder.value
    }

    /** Player **/
    private val sPacketAdvancementInfo = setting("SPacketAdvancementInfo", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.PLAYER })
    private val sPacketAnimation = setting("SPacketAnimation", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.PLAYER })
    private val sPacketBlockAction = setting("SPacketBlockAction", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.PLAYER })
    private val sPacketBlockBreakAnim = setting("SPacketBlockBreakAnim", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.PLAYER })
    private val sPacketCamera = setting("SPacketCamera", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.PLAYER })
    private val sPacketChangeGameState = setting("SPacketChangeGameState", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.PLAYER })
    private val sPacketCombatEvent = setting("SPacketCombatEvent", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.PLAYER })
    private val sPacketCooldown = setting("SPacketCooldown", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.PLAYER })
    private val sPacketPlayerAbilities = setting("SPacketPlayerAbilities", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.PLAYER })
    private val sPacketPlayerPosLook = setting("SPacketPlayerPosLook", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.PLAYER })
    private val sPacketRespawn = setting("SPacketRespawn", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.PLAYER })
    private val sPacketSetExperience = setting("SPacketSetExperience", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.PLAYER })
    private val sPacketUpdateHealth = setting("SPacketUpdateHealth", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.PLAYER })
    private val sPacketUpdateScore = setting("SPacketUpdateScore", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.PLAYER })
    private val sPacketUseBed = setting("SPacketUseBed", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.PLAYER })

    /** Inventory **/
    private val sPacketCloseWindow = setting("SPacketCloseWindow", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.INVENTORY })
    private val sPacketConfirmTransaction = setting("SPacketConfirmTransaction", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.INVENTORY })
    private val sPacketHeldItemChange = setting("SPacketHeldItemChange", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.INVENTORY })
    private val sPacketOpenWindow = setting("SPacketOpenWindow", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.INVENTORY })
    private val sPacketPlaceGhostRecipe = setting("SPacketPlaceGhostRecipe", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.INVENTORY })
    private val sPacketRecipeBook = setting("SPacketRecipeBook", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.INVENTORY })
    private val sPacketSetSlot = setting("SPacketSetSlot", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.INVENTORY })
    private val sPacketWindowItems = setting("SPacketWindowItems", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.INVENTORY })
    private val sPacketWindowProperty = setting("SPacketWindowProperty", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.INVENTORY })

    /** System **/
    private val sPacketChat = setting("SPacketChat", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.SYSTEM })
    private val sPacketCustomPayload = setting("SPacketCustomPayload", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.SYSTEM })
    private val sPacketCustomSound = setting("SPacketCustomSound", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.SYSTEM })
    private val sPacketDisconnect = setting("SPacketDisconnect", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.SYSTEM })
    private val sPacketDisplayObjective = setting("SPacketDisplayObjective", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.SYSTEM })
    private val sPacketJoinGame = setting("SPacketJoinGame", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.SYSTEM })
    private val sPacketKeepAlive = setting("SPacketKeepAlive", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.SYSTEM })
    private val sPacketPlayerListHeaderFooter = setting("SPacketPlayerListHeaderFooter", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.SYSTEM })
    private val sPacketPlayerListItem = setting("SPacketPlayerListItem", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.SYSTEM })
    private val sPacketResourcePackSend = setting("SPacketResourcePackSend", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.SYSTEM })
    private val sPacketScoreboardObjective = setting("SPacketScoreboardObjective", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.SYSTEM })
    private val sPacketSelectAdvancementsTab = setting("SPacketSelectAdvancementsTab", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.SYSTEM })
    private val sPacketStatistics = setting("SPacketStatistics", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.SYSTEM })
    private val sPacketTabComplete = setting("SPacketTabComplete", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.SYSTEM })
    private val sPacketTeams = setting("SPacketTeams", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.SYSTEM })
    private val sPacketTitle = setting("SPacketTitle", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.SYSTEM })

    /** World **/
    private val sPacketBlockChange = setting("SPacketBlockChange", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.WORLD })
    private val sPacketChunkData = setting("SPacketChunkData", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.WORLD })
    private val sPacketEffect = setting("SPacketEffect", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.WORLD })
    private val sPacketExplosion = setting("SPacketExplosion", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.WORLD })
    private val sPacketMaps = setting("SPacketMaps", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.WORLD })
    private val sPacketMultiBlockChange = setting("SPacketMultiBlockChange", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.WORLD })
    private val sPacketParticles = setting("SPacketParticles", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.WORLD })
    private val sPacketServerDifficulty = setting("SPacketServerDifficulty", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.WORLD })
    private val sPacketSignEditorOpen = setting("SPacketSignEditorOpen", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.WORLD })
    private val sPacketSoundEffect = setting("SPacketSoundEffect", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.WORLD })
    private val sPacketSpawnPosition = setting("SPacketSpawnPosition", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.WORLD })
    private val sPacketTimeUpdate = setting("SPacketTimeUpdate", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.WORLD })
    private val sPacketUnloadChunk = setting("SPacketUnloadChunk", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.WORLD })
    private val sPacketUpdateBossInfo = setting("SPacketUpdateBossInfo", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.WORLD })
    private val sPacketUpdateTileEntity = setting("SPacketUpdateTileEntity", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.WORLD })
    private val sPacketWorldBorder = setting("SPacketWorldBorder", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.WORLD })

    /** Entity **/
    private val sPacketCollectItem = setting("SPacketCollectItem", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketDestroyEntities = setting("SPacketDestroyEntities", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val s15PacketEntityRelMove = setting("S15PacketEntityRelMove", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val s16PacketEntityLook = setting("S16PacketEntityLook", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val s17PacketEntityLookMove = setting("S17PacketEntityLookMove", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketEntity = setting("SPacketEntity", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketEntityAttach = setting("SPacketEntityAttach", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketEntityEffect = setting("SPacketEntityEffect", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketEntityEquipment = setting("SPacketEntityEquipment", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketEntityHeadLook = setting("SPacketEntityHeadLook", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketEntityMetadata = setting("SPacketEntityMetadata", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketEntityProperties = setting("SPacketEntityProperties", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketEntityStatus = setting("SPacketEntityStatus", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketEntityTeleport = setting("SPacketEntityTeleport", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketEntityVelocity = setting("SPacketEntityVelocity", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketMoveVehicle = setting("SPacketMoveVehicle", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketRemoveEntityEffect = setting("SPacketRemoveEntityEffect", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketSetPassengers = setting("SPacketSetPassengers", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketSpawnExperienceOrb = setting("SPacketSpawnExperienceOrb", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketSpawnGlobalEntity = setting("SPacketSpawnGlobalEntity", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketSpawnMob = setting("SPacketSpawnMob", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketSpawnObject = setting("SPacketSpawnObject", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketSpawnPainting = setting("SPacketSpawnPainting", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })
    private val sPacketSpawnPlayer = setting("SPacketSpawnPlayer", true, visibility = { page == Page.SERVER && categorySetting == CategorySlider.ENTITY })













    private val fileTimeFormatter = DateTimeFormatter.ofPattern("HH-mm-ss_SSS")

    private var start = 0L
    private var last = 0L
    private var lastTick = 0L
    private val timer = TickTimer(TimeUnit.SECONDS)

    private var filename = ""
    private var lines = ArrayList<String>()

    private enum class Page {
        GENERAL, CLIENT, SERVER
    }
    enum class CategorySlider {
        PLAYER, INVENTORY, SYSTEM, WORLD, ENTITY
    }

    private enum class PacketSide(override val displayName: String) : DisplayEnum {
        CLIENT("Client"),
        SERVER("Server"),
        BOTH("Both")
    }

    enum class LogMode(override val displayName: String) : DisplayEnum {
        CHAT("Chat"),
        FILE("File"),
        CHAT_AND_FILE("Chat+File"),
        ONLY_HUD("Only HUD"),
        ALL("All")
    }

    private enum class CaptureTiming {
        PRE, POST
    }

    init {
        onEnable {
            PacketLogViewer.clear()
            start = System.currentTimeMillis()
            filename = "${fileTimeFormatter.format(LocalTime.now())}.csv"

            synchronized(this) {
                lines.add("From,Packet Name,Time Since Start (ms),Time Since Last (ms),Data\n")
            }
        }

        onDisable {
            write()
        }

        safeListener<TickEvent.ClientTickEvent> {
            if (it.phase != TickEvent.Phase.START) return@safeListener

            if (showClientTicks) {
                synchronized(this@PacketLogger) {
                    val current = System.currentTimeMillis()
                    lines.add("Tick Pulse,,${current - start},${current - lastTick}\n")
                    lastTick = current
                }
            }

            /* Don't let lines get too big, write periodically to the file */
            if (lines.size >= 500 || timer.tick(15L)) {
                write()
            }
        }

        listener<ConnectionEvent.Disconnect> {
            disable()
            PacketLogViewer.clear()
        }

        listener<PacketEvent.Receive>(Int.MAX_VALUE) {
            if (captureTiming != CaptureTiming.PRE || ignoreCancelled && it.cancelled) {
                return@listener
            }

            receivePacket(it.packet)
        }

        listener<PacketEvent.PostReceive>(Int.MIN_VALUE) {
            if (captureTiming != CaptureTiming.POST || ignoreCancelled && it.cancelled) {
                return@listener
            }

            receivePacket(it.packet)
        }

        listener<PacketEvent.Send>(Int.MAX_VALUE) {
            if (captureTiming != CaptureTiming.PRE || ignoreCancelled && it.cancelled) {
                return@listener
            }

            sendPacket(it.packet)
        }

        listener<PacketEvent.PostSend>(Int.MIN_VALUE) {
            if (captureTiming != CaptureTiming.POST || ignoreCancelled && it.cancelled) {
                return@listener
            }

            sendPacket(it.packet)
        }
    }

    private fun sendPacket(packet: Packet<*>) {
        if (packetSide == PacketSide.CLIENT || packetSide == PacketSide.BOTH) {
            when (packet) {
                is CPacketAnimation -> {
                    if (!cPacketAnimation.value) return
                    logClient(packet) {
                        "hand" to packet.hand
                    }
                }
                is CPacketChatMessage -> {
                    if (!cPacketChatMessage.value) return
                    logClient(packet) {
                        "message" to packet.message
                    }
                }
                is CPacketClickWindow -> {
                    if (!cPacketClickWindow.value) return
                    logClient(packet) {
                        "windowId" to packet.windowId
                        "slotID" to packet.slotId
                        "mouseButton" to packet.usedButton
                        "clickType" to packet.clickType
                        "transactionID" to packet.actionNumber
                        "clickedItem" to packet.clickedItem
                    }
                }
                is CPacketClientSettings -> {
                    if (!cPacketClientSettings.value) return
                    logClient(packet) {
                        "lang" to packet.lang
                        "view" to packet.view
                        "chatVisibility" to packet.chatVisibility
                        "enableColors" to packet.isColorsEnabled
                        "modelPartFlags" to packet.modelPartFlags
                        "mainHand" to packet.mainHand.name
                    }
                }
                is CPacketClientStatus -> {
                    if (!cPacketClientStatus.value) return
                    logClient(packet) {
                        "action" to packet.status.name
                    }
                }
                is CPacketCloseWindow -> {
                    logClient(packet) {
                        "windowID" to packet.windowID
                    }
                }
                is CPacketConfirmTeleport -> {
                    logClient(packet) {
                        "teleportID" to packet.teleportId
                    }
                }
                is CPacketConfirmTransaction -> {
                    if (!cPacketConfirmTransaction.value) return
                    logClient(packet) {
                        "windowID" to packet.windowId
                        "actionNumber" to packet.uid
                        "accepted" to packet.accepted
                    }
                }
                is CPacketCreativeInventoryAction -> {
                    if (!cPacketCreativeInventoryAction.value) return
                    logClient(packet) {
                        "slotID" to packet.slotId
                        "clickedItem" to packet.stack
                    }
                }
                is CPacketCustomPayload -> {
                    if (!cPacketCustomPayload.value) return
                    logClient(packet) {
                        "channel" to packet.channelName
                        "data" to packet.bufferData
                    }
                }
                is CPacketEnchantItem -> {
                    if (!cPacketEnchantItem.value) return
                    logClient(packet) {
                        "windowID" to packet.windowId
                        "button" to packet.button
                    }
                }
                is CPacketEntityAction -> {
                    if (!cPacketEntityAction.value) return
                    logClient(packet) {
                        "action" to packet.action.name
                        "auxData" to packet.auxData
                    }
                }
                is CPacketHeldItemChange -> {
                    if (!cPacketHeldItemChange.value) return
                    logClient(packet) {
                        "slotID" to packet.slotId
                    }
                }
                is CPacketInput -> {
                    if (!cPacketInput.value) return
                    logClient(packet) {
                        "forward" to packet.forwardSpeed
                        "strafe" to packet.strafeSpeed
                        "jump" to packet.isJumping
                        "sneak" to packet.isSneaking
                    }
                }
                is CPacketKeepAlive -> {
                    if (!cPacketKeepAlive.value) return
                    logClient(packet) {
                        "ket" to packet.key
                    }
                }
                is CPacketPlaceRecipe -> {
                    if (!cPacketPlaceRecipe.value) return
                    logClient(packet) {
                        "windowID" to packet.func_194318_a()
                        "recipe" to CraftingManager.getIDForRecipe(packet.func_194317_b())
                        "shift" to packet.func_194319_c()
                    }
                }

                is CPacketPlayer.Rotation -> {
                    if (!cPacketPlayerRotation.value) return
                    logClient(packet) {
                        "yaw" to packet.playerYaw
                        "pitch" to packet.playerPitch
                        "onGround" to packet.isOnGround
                    }
                }
                is CPacketPlayer.Position -> {
                    if (!cPacketPlayerPosition.value) return
                    logClient(packet) {
                        "x" to packet.playerX
                        "y" to packet.playerY
                        "z" to packet.playerZ
                        "onGround" to packet.isOnGround
                    }
                }
                is CPacketPlayer.PositionRotation -> {
                    if (!cPacketPlayerPositionRotation.value) return
                    logClient(packet) {
                        "x" to packet.playerX
                        "y" to packet.playerY
                        "z" to packet.playerZ
                        "yaw" to packet.playerYaw
                        "pitch" to packet.playerPitch
                        "onGround" to packet.isOnGround
                    }
                }
                is CPacketPlayer -> {
                    if (!cPacketPlayer.value) return
                    logClient(packet) {
                        "onGround" to packet.isOnGround
                    }
                }
                is CPacketPlayerAbilities -> {
                    if (!cPacketPlayerAbilities.value) return
                    logClient(packet) {
                        "invulnerable" to packet.isInvulnerable
                        "flying" to packet.isFlying
                        "allowFlying" to packet.isAllowFlying
                        "creativeMode" to packet.isCreativeMode
                        "flySpeed" to packet.flySpeed
                        "walkSpeed" to packet.walkSpeed
                    }
                }
                is CPacketPlayerDigging -> {
                    if (!cPacketPlayerDigging.value) return
                    logClient(packet) {
                        "pos" to packet.position
                        "facing" to packet.facing.name
                        "action" to packet.action.name
                    }
                }
                is CPacketPlayerTryUseItem -> {
                    if (!cPacketPlayerTryUseItem.value) return
                    logClient(packet) {
                        "hand" to packet.hand
                    }
                }
                is CPacketPlayerTryUseItemOnBlock -> {
                    if (!cPacketPlayerTryUseItemOnBlock.value) return
                    logClient(packet) {
                        "pos" to packet.pos
                        "side" to packet.direction.name
                        "hitVecX" to packet.facingX
                        "hitVecY" to packet.facingY
                        "hitVecZ" to packet.facingZ
                    }
                }
                is CPacketRecipeInfo -> {
                    if (!cPacketRecipeInfo.value) return
                    logClient(packet) {
                        "purpose" to packet.purpose.name
                        "recipe" to CraftingManager.getIDForRecipe(packet.recipe)
                        "guiOpen" to packet.isGuiOpen
                        "filteringCraftable" to packet.isFilteringCraftable
                    }
                }
                is CPacketResourcePackStatus -> {
                    if (!cPacketResourcePackStatus.value) return
                    logClient(packet) {
                        "action" to packet.action.name
                    }
                }
                is CPacketSeenAdvancements -> {
                    if (!cPacketSeenAdvancements.value) return
                    logClient(packet) {
                        "action" to packet.action.name
                        @Suppress("UNNECESSARY_SAFE_CALL")
                        packet.tab?.let {
                            "tabName" to it.namespace
                            "tabPath" to it.path
                        }
                    }
                }
                is CPacketSpectate -> {
                    if (!cPacketSpectate.value) return
                    logClient(packet) {
                        "uuid" to packet.uuid
                    }
                }
                is CPacketSteerBoat -> {
                    if (!cPacketSteerBoat.value) return
                    logClient(packet) {
                        "left" to packet.left
                        "right" to packet.right
                    }
                }
                is CPacketTabComplete -> {
                    if (!cPacketTabComplete.value) return
                    logClient(packet) {
                        "text" to packet.message
                        "hasTarget" to packet.hasTargetBlock()
                        packet.targetBlock?.let {
                            "targetBlockPos" to it
                        }
                    }
                }
                is CPacketUpdateSign -> {
                    if (!cPacketUpdateSign.value) return
                    logClient(packet) {
                        "x" to packet.position.x
                        "y" to packet.position.y
                        "z" to packet.position.z
                        "line1" to packet.lines[0]
                        "line2" to packet.lines[1]
                        "line3" to packet.lines[2]
                        "line4" to packet.lines[3]
                    }
                }
                is CPacketUseEntity -> {
                    if (!cPacketUseEntity.value) return
                    @Suppress("UNNECESSARY_SAFE_CALL")
                    logClient(packet) {
                        "entityId" to packet.useEntityId
                        "action" to packet.action.name
                        "hitVecX" to packet.hitVec?.x
                        "hitVecX" to packet.hitVec?.y
                        "hitVecX" to packet.hitVec?.z
                        "hand" to packet.hand?.name
                    }
                }
                is CPacketVehicleMove -> {
                    if (!cPacketVehicleMove.value) return
                    logClient(packet) {
                        "x" to packet.x
                        "y" to packet.y
                        "z" to packet.z
                        "yaw" to packet.yaw
                        "pitch" to packet.pitch
                    }
                }
                else -> {
                    logClient(packet) {
                        +"Not Registered in PacketLogger"
                    }
                }
            }
        }
    }

    private fun receivePacket(packet: Packet<*>) {
        if (packetSide == PacketSide.SERVER || packetSide == PacketSide.BOTH) {
            when (packet) {
                is SPacketAdvancementInfo -> {
                    if (!sPacketAdvancementInfo.value) return
                    logServer(packet) {
                        "isFirstSync" to packet.isFirstSync
                        "advancementsToAdd" to buildString {
                            for (entry in packet.advancementsToAdd) {
                                append("> ")

                                append(" key: ")
                                append(entry.key)

                                append(" value: ")
                                append(entry.value)

                                append(' ')
                            }
                        }
                        "advancementsToRemove" to buildString {
                            for (entry in packet.advancementsToRemove) {
                                append("> path: ")
                                append(entry.path)
                                append(", namespace:")
                                append(entry.namespace)
                                append(' ')
                            }
                        }
                        "progressUpdates" to buildString {
                            for (entry in packet.progressUpdates) {
                                append("> ")

                                append(" key: ")
                                append(entry.key)

                                append(" value: ")
                                append(entry.value)

                                append(' ')
                            }
                        }
                    }
                }
                is SPacketAnimation -> {
                    if (!sPacketAnimation.value) return
                    logServer(packet) {
                        "entityId" to packet.entityID
                        "animationType" to packet.animationType
                    }
                }
                is SPacketBlockAction -> {
                    if (!sPacketBlockAction.value) return
                    logServer(packet) {
                        "blockPosition" to packet.blockPosition
                        "instrument" to packet.data1
                        "pitch" to packet.data2
                        "blockType" to packet.blockType
                    }
                }
                is SPacketBlockBreakAnim -> {
                    if (!sPacketBlockBreakAnim.value) return
                    logServer(packet) {
                        "breakerId" to packet.breakerId
                        "position" to packet.position
                        "progress" to packet.progress
                    }
                }
                is SPacketBlockChange -> {
                    if (!sPacketBlockChange.value) return
                    logServer(packet) {
                        "blockPosition" to packet.blockPosition
                        "block" to packet.blockState.block.localizedName
                    }
                }
                is SPacketCamera -> {
                    if (!sPacketCamera.value) return
                    logServer(packet) {
                        "entityId" to packet.entityId
                    }
                }
                is SPacketChangeGameState -> {
                    if (!sPacketChangeGameState.value) return
                    logServer(packet) {
                        "value" to packet.value
                        "gameState" to packet.gameState
                    }
                }
                is SPacketChat -> {
                    if (!sPacketChat.value) return
                    logServer(packet) {
                        "unformattedText" to packet.chatComponent.unformattedText
                        "type" to packet.type
                        "itSystem" to packet.isSystem
                    }
                }
                is SPacketChunkData -> {
                    if (!sPacketChunkData.value) return
                    logServer(packet) {
                        "chunkX" to packet.chunkX
                        "chunkZ" to packet.chunkZ
                        "extractedSize" to packet.extractedSize
                    }
                }
                is SPacketCloseWindow -> {
                    if (!sPacketCloseWindow.value) return
                    logServer(packet) {
                        "windowId" to packet.windowId
                    }
                }
                is SPacketCollectItem -> {
                    if (!sPacketCollectItem.value) return
                    logServer(packet) {
                        "amount" to packet.amount
                        "collectedItemEntityID" to packet.collectedItemEntityID
                        "entityID" to packet.entityID
                    }
                }
                is SPacketCombatEvent -> {
                    if (!sPacketCombatEvent.value) return
                    logServer(packet) {
                        "eventType" to packet.eventType.name
                        "playerId" to packet.playerId
                        "entityId" to packet.entityId
                        "duration" to packet.duration
                        "deathMessage" to packet.deathMessage.unformattedText
                    }
                }
                is SPacketConfirmTransaction -> {
                    if (!sPacketConfirmTransaction.value) return
                    logServer(packet) {
                        "windowId" to packet.windowId
                        "transactionID" to packet.actionNumber
                        "accepted" to packet.wasAccepted()
                    }
                }
                is SPacketCooldown -> {
                    if (!sPacketCooldown.value) return
                    logServer(packet) {
                        "item" to packet.item.registryName
                        "ticks" to packet.ticks
                    }
                }
                is SPacketCustomPayload -> {
                    if (!sPacketCustomPayload.value) return
                    logServer(packet) {
                        "channelName" to packet.channelName
                        "bufferData" to packet.bufferData
                    }
                }
                is SPacketCustomSound -> {
                    if (!sPacketCustomSound.value) return
                    logServer(packet) {
                        "x" to packet.x
                        "y" to packet.y
                        "z" to packet.z
                        "pitch" to packet.pitch
                        "category" to packet.category.name
                        "soundName" to packet.soundName
                        "volume" to packet.volume
                    }
                }
                is SPacketDestroyEntities -> {
                    if (!sPacketDestroyEntities.value) return
                    logServer(packet) {
                        "entityIDs" to buildString {
                            for (entry in packet.entityIDs) {
                                append("> ")
                                append(entry)
                                append(' ')
                            }
                        }
                    }
                }
                is SPacketDisconnect -> {
                    if (!sPacketDisconnect.value) return
                    logServer(packet) {
                        "reason" to packet.reason.unformattedText
                    }
                }
                is SPacketDisplayObjective -> {
                    if (!sPacketDisplayObjective.value) return
                    logServer(packet) {
                        "position" to packet.position
                        "name" to packet.name
                    }
                }
                is SPacketEffect -> {
                    if (!sPacketEffect.value) return
                    logServer(packet) {
                        "soundData" to packet.soundData
                        "soundPos" to packet.soundPos
                        "soundType" to packet.soundType
                        "isSoundServerwide" to packet.isSoundServerwide
                    }
                }
                is SPacketEntity.S15PacketEntityRelMove -> {
                    if (!s15PacketEntityRelMove.value) return
                    logServer(packet) {
                        "entityId" to packet.entityId
                        "x" to packet.x
                        "y" to packet.y
                        "z" to packet.z
                        "onGround" to packet.onGround
                    }
                }
                is SPacketEntity.S16PacketEntityLook -> {
                    if (!s16PacketEntityLook.value) return
                    logServer(packet) {
                        "entityId" to packet.entityId
                        "yaw" to packet.yaw
                        "pitch" to packet.pitch
                        "isRotating" to packet.isRotating
                        "onGround" to packet.onGround
                    }
                }
                is SPacketEntity.S17PacketEntityLookMove -> {
                    if(!s17PacketEntityLookMove.value) return
                    logServer(packet) {
                        "entityId" to packet.entityId
                        "x" to packet.x
                        "y" to packet.y
                        "z" to packet.z
                        "yaw" to packet.yaw
                        "pitch" to packet.pitch
                        "isRotating" to packet.isRotating
                        "onGround" to packet.onGround
                    }
                }
                is SPacketEntity -> {
                    if (!sPacketEntity.value) return
                    logServer(packet) {
                        "entityId" to packet.entityId
                        "x" to packet.x
                        "y" to packet.y
                        "z" to packet.z
                        "yaw" to packet.yaw
                        "pitch" to packet.pitch
                        "isRotating" to packet.isRotating
                        "onGround" to packet.onGround
                    }
                }
                is SPacketEntityAttach -> {
                    if (!sPacketEntityAttach.value) return
                    logServer(packet) {
                        "entityId" to packet.entityId
                        "vehicleEntityId" to packet.vehicleEntityId
                    }
                }
                is SPacketEntityEffect -> {
                    if (!sPacketEntityEffect.value) return
                    logServer(packet) {
                        "entityId" to packet.entityId
                        "duration" to packet.duration
                        "amplifier" to packet.amplifier
                        "effectId" to packet.effectId
                        "isAmbient" to packet.isAmbient
                        "isMaxDuration" to packet.isMaxDuration
                    }
                }
                is SPacketEntityEquipment -> {
                    if (!sPacketEntityEquipment.value) return
                    logServer(packet) {
                        "entityId" to packet.entityID
                        "slot" to packet.equipmentSlot.name
                        "stack" to packet.itemStack.displayName
                    }
                }
                is SPacketEntityHeadLook -> {
                    if (!sPacketEntityHeadLook.value) return
                    logServer(packet) {
                        "entityId" to packet.entityHeadLookEntityId
                        "yaw" to packet.yaw
                    }
                }
                is SPacketEntityMetadata -> {
                    if (!sPacketEntityMetadata.value) return
                    logServer(packet) {
                        "dataEntries" to buildString {
                            for (entry in packet.dataManagerEntries) {
                                append("> isDirty: ")
                                append(entry.isDirty)

                                append(" key: ")
                                append(entry.key)

                                append(" value: ")
                                append(entry.value)

                                append(' ')
                            }
                        }
                    }
                }
                is SPacketEntityProperties -> {
                    if (!sPacketEntityProperties.value) return
                    logServer(packet) {
                        "entityId" to packet.entityId
                        "snapshots" to packet.snapshots
                    }
                }
                is SPacketEntityStatus -> {
                    if (!sPacketEntityStatus.value) return
                    logServer(packet) {
                        "opCode" to packet.opCode
                    }
                }
                is SPacketEntityTeleport -> {
                    if (!sPacketEntityTeleport.value) return
                    logServer(packet) {
                        "entityID" to packet.entityId
                        "x" to packet.x
                        "y" to packet.y
                        "z" to packet.z
                        "yaw" to packet.yaw
                        "pitch" to packet.pitch
                    }
                }
                is SPacketEntityVelocity -> {
                    if (!sPacketEntityVelocity.value) return
                    logServer(packet) {
                        "entityID" to packet.entityID
                        "motionX" to packet.motionX
                        "motionY" to packet.motionY
                        "motionZ" to packet.motionZ
                    }
                }
                is SPacketExplosion -> {
                    if (!sPacketExplosion.value) return
                    logServer(packet) {
                        "strength" to packet.strength
                        "x" to packet.x
                        "y" to packet.y
                        "z" to packet.z
                        "motionX" to packet.motionX
                        "motionY" to packet.motionY
                        "motionZ" to packet.motionZ
                        "affectedBlockPositions" to buildString {
                            for (block in packet.affectedBlockPositions) {
                                append("> x: ")
                                append(block.x)

                                append("y: ")
                                append(block.y)

                                append("z: ")
                                append(block.z)

                                append(' ')
                            }
                        }
                    }
                }
                is SPacketHeldItemChange -> {
                    if (!sPacketHeldItemChange.value) return
                    logServer(packet) {
                        "heldItemHotbarIndex" to packet.heldItemHotbarIndex
                    }
                }
                is SPacketJoinGame -> {
                    if (!sPacketJoinGame.value) return
                    logServer(packet) {
                        "playerId" to packet.playerId
                        "difficulty" to packet.difficulty.name
                        "dimension" to packet.dimension
                        "gameType" to packet.gameType.name
                        "isHardcoreMode" to packet.isHardcoreMode
                        "isReducedDebugInfo" to packet.isReducedDebugInfo
                        "maxPlayers" to packet.maxPlayers
                        "worldType" to packet.worldType
                    }
                }
                is SPacketKeepAlive -> {
                    if (!sPacketKeepAlive.value) return
                    logServer(packet) {
                        "id" to packet.id
                    }
                }
                is SPacketMaps -> {
                    if (!sPacketMaps.value) return
                    logServer(packet) {
                        "mapId" to packet.mapId
                        "mapScale" to packet.mapScale
                        "trackingPosition" to packet.trackingPosition
                        "icons" to packet.icons
                        "minX" to packet.minX
                        "minZ" to packet.minZ
                        "columns" to packet.columns
                        "rows" to packet.rows
                        "data" to packet.mapDataBytes
                    }
                }
                is SPacketMoveVehicle -> {
                    if (!sPacketMoveVehicle.value) return
                    logServer(packet) {
                        "x" to packet.x
                        "y" to packet.y
                        "z" to packet.z
                        "yaw" to packet.yaw
                        "pitch" to packet.pitch
                    }
                }
                is SPacketMultiBlockChange -> {
                    if (!sPacketMultiBlockChange.value) return
                    logServer(packet) {
                        "changedBlocks" to buildString {
                            for (changedBlock in packet.changedBlocks) {
                                append("> x: ")
                                append(changedBlock.pos.x)

                                append("y: ")
                                append(changedBlock.pos.y)

                                append("z: ")
                                append(changedBlock.pos.z)

                                append("offset: ")
                                append(changedBlock.offset)

                                append("blockState: ")
                                append(Block.BLOCK_STATE_IDS.get(changedBlock.blockState))

                                append(' ')
                            }
                        }
                    }
                }
                is SPacketOpenWindow -> {
                    if (!sPacketOpenWindow.value) return
                    logServer(packet) {
                        "entityId" to packet.entityId
                        "windowTitle" to packet.windowTitle
                        "guiId" to packet.guiId
                        "windowId" to packet.windowId
                        "slotCount" to packet.slotCount
                    }
                }
                is SPacketParticles -> {
                    if (!sPacketParticles.value) return
                    logServer(packet) {
                        "particleType" to packet.particleType.name
                        "isLongDistance" to packet.isLongDistance
                        "particleType" to packet.particleType.name
                        "particleCount" to packet.particleCount
                        "particleSpeed" to packet.particleSpeed
                        "xCoordinate" to packet.xCoordinate
                        "yCoordinate" to packet.yCoordinate
                        "zCoordinate" to packet.zCoordinate
                        "xOffset" to packet.xOffset
                        "yOffset" to packet.yOffset
                        "zOffset" to packet.zOffset
                        "particleArgs" to packet.particleArgs
                    }
                }
                is SPacketPlaceGhostRecipe -> {
                    if (!sPacketPlaceGhostRecipe.value) return
                    logServer(packet) {
                        "windowId" to packet.func_194313_b()
                        "recipeId" to CraftingManager.getIDForRecipe(packet.func_194311_a())
                    }
                }
                is SPacketPlayerAbilities -> {
                    if (!sPacketPlayerAbilities.value) return
                    logServer(packet) {
                        "isInvulnerable" to packet.isInvulnerable
                        "isFlying" to packet.isFlying
                        "allowFlying" to packet.isAllowFlying
                        "isCreativeMode" to packet.isCreativeMode
                        "flySpeed" to packet.flySpeed
                        "walkSpeed" to packet.walkSpeed
                    }
                }
                is SPacketPlayerListHeaderFooter -> {
                    if (!sPacketPlayerListHeaderFooter.value) return
                    logServer(packet) {
                        "header" to ITextComponent.Serializer.componentToJson(packet.header)
                        "footer" to ITextComponent.Serializer.componentToJson(packet.footer)
                    }
                }
                is SPacketPlayerListItem -> {
                    if (!sPacketPlayerListItem.value) return
                    logServer(packet) {
                        "action" to packet.action.name
                        "entries" to buildString {
                            for (entry in packet.entries) {
                                append("> displayName: ")
                                append(entry.displayName)
                                append(" gameMode: ")
                                append(entry.gameMode?.name)
                                append(" ping: ")
                                append(entry.ping)
                                append(" profile.id: ")
                                append(entry.profile?.id)
                                append(" profile.name: ")
                                append(entry.profile?.name)
                                append(" profile.properties: ")
                                append(entry.profile?.properties)
                                append(' ')
                            }
                        }
                    }
                }
                is SPacketPlayerPosLook -> {
                    if (!sPacketPlayerPosLook.value) return
                    logServer(packet) {
                        "x" to packet.x
                        "y" to packet.y
                        "z" to packet.z
                        "yaw" to packet.yaw
                        "pitch" to packet.pitch
                        "teleportID" to packet.teleportId
                        "flags" to buildString {
                            for (entry in packet.flags) {
                                append("> ")
                                append(entry.name)
                                append(' ')
                            }
                        }
                    }
                }
                is SPacketRecipeBook -> {
                    if (!sPacketRecipeBook.value) return
                    logServer(packet) {
                        "state" to packet.state.name
                        "recipes" to buildString {
                            for (recipe in packet.recipes) {
                                append("> ")
                                append(CraftingManager.getIDForRecipe(recipe))
                                append(' ')
                            }
                        }
                        "displayedRecipes" to buildString {
                            for (recipe in packet.displayedRecipes) {
                                append("> ")
                                append(CraftingManager.getIDForRecipe(recipe))
                                append(' ')
                            }
                        }
                        "guiOpen" to packet.isGuiOpen
                        "filteringCraftable" to packet.isFilteringCraftable
                    }
                }
                is SPacketRemoveEntityEffect -> {
                    if (!sPacketRemoveEntityEffect.value) return
                    logServer(packet) {
                        mc.world?.let { world ->
                            packet.getEntity(world)?.let {
                                "entityID" to it.entityId
                            }
                        }
                        packet.potion?.let { "effectID" to Potion.getIdFromPotion(it) }
                    }
                }
                is SPacketResourcePackSend -> {
                    if (!sPacketResourcePackSend.value) return
                    logServer(packet) {
                        "url" to packet.url
                        "hash" to packet.hash
                    }
                }
                is SPacketRespawn -> {
                    if (!sPacketRespawn.value) return
                    logServer(packet) {
                        "dimensionID" to packet.dimensionID
                        "difficulty" to packet.difficulty.name
                        "gameType" to packet.gameType.name
                        "worldType" to packet.worldType.name
                    }
                }
                is SPacketScoreboardObjective -> {
                    if (!sPacketScoreboardObjective.value) return
                    logServer(packet) {
                        "name" to packet.objectiveName
                        "value" to packet.objectiveValue
                        "type" to packet.renderType.renderType
                        "action" to packet.action
                    }
                }
                is SPacketSelectAdvancementsTab -> {
                    if (!sPacketSelectAdvancementsTab.value) return
                    logServer(packet) {
                        packet.tab?.let {
                            "name" to it.namespace
                            "path" to it.path
                        }?.run {
                            "name" to ""
                            "path" to ""
                        }
                    }
                }
                is SPacketServerDifficulty -> {
                    if (!sPacketServerDifficulty.value) return
                    logServer(packet) {
                        "difficulty" to packet.difficulty.name
                        "difficultyLocked" to packet.isDifficultyLocked
                    }
                }
                is SPacketSetExperience -> {
                    if (!sPacketSetExperience.value) return
                    logServer(packet) {
                        "experienceBar" to packet.experienceBar
                        "totalExperience" to packet.totalExperience
                        "level" to packet.level
                    }
                }
                is SPacketSetPassengers -> {
                    if (!sPacketSetPassengers.value) return
                    logServer(packet) {
                        "entityID" to packet.entityId
                        "passengers" to buildString {
                            for (passenger in packet.passengerIds) {
                                append("> ")
                                append(passenger)
                                append(' ')
                            }
                        }
                    }
                }
                is SPacketSetSlot -> {
                    if (!sPacketSetSlot.value) return
                    logServer(packet) {
                        "slot" to packet.slot
                        "stack" to packet.stack.displayName
                        "windowId" to packet.windowId
                    }
                }
                is SPacketSignEditorOpen -> {
                    if (!sPacketSignEditorOpen.value) return
                    logServer(packet) {
                        "posX" to packet.signPosition.x
                        "posY" to packet.signPosition.y
                        "posZ" to packet.signPosition.z
                    }
                }
                is SPacketSoundEffect -> {
                    if (!sPacketSoundEffect.value) return
                    logServer(packet) {
                        "sound" to packet.sound.soundName
                        "category" to packet.category
                        "posX" to packet.x
                        "posY" to packet.y
                        "posZ" to packet.z
                        "volume" to packet.volume
                        "pitch" to packet.pitch
                    }
                }
                is SPacketSpawnExperienceOrb -> {
                    if (!sPacketSpawnExperienceOrb.value) return
                    logServer(packet) {
                        "entityID" to packet.entityID
                        "x" to packet.x
                        "y" to packet.y
                        "z" to packet.z
                        "count" to packet.xpValue
                    }
                }
                is SPacketSpawnGlobalEntity -> {
                    if (!sPacketSpawnGlobalEntity.value) return
                    logServer(packet) {
                        "entityID" to packet.entityId
                        "type" to packet.type
                        "x" to packet.x
                        "y" to packet.y
                        "z" to packet.z
                    }
                }
                is SPacketSpawnMob -> {
                    if (!sPacketSpawnMob.value) return
                    logServer(packet) {
                        "entityID" to packet.entityID
                        "uuid" to packet.uniqueId
                        "type" to packet.entityType
                        "x" to packet.x
                        "y" to packet.y
                        "z" to packet.z
                        "yaw" to packet.yaw
                        "pitch" to packet.pitch
                        "headPitch" to packet.headPitch
                        "velocityX" to packet.velocityX
                        "velocityY" to packet.velocityY
                        "velocityZ" to packet.velocityZ
                        packet.dataManagerEntries?.let { metadata ->
                            "metadata" to buildString {
                                for (entry in metadata) {
                                    append("> ")
                                    append(entry.key.id)
                                    append(": ")
                                    append(entry.value)
                                    append(' ')
                                }
                            }
                        }
                    }
                }
                is SPacketSpawnObject -> {
                    if (!sPacketSpawnObject.value) return
                    logServer(packet) {
                        "entityID" to packet.entityID
                        "uuid" to packet.uniqueId
                        "x" to packet.x
                        "y" to packet.y
                        "z" to packet.z
                        "speedX" to packet.speedX
                        "speedY" to packet.speedY
                        "speedZ" to packet.speedZ
                        "pitch" to packet.pitch
                        "yaw" to packet.yaw
                        "type" to packet.type
                        "data" to packet.data
                    }
                }
                is SPacketSpawnPainting -> {
                    if (!sPacketSpawnPainting.value) return
                    logServer(packet) {
                        "entityID" to packet.entityID
                        "uuid" to packet.uniqueId
                        "title" to packet.title
                        "x" to packet.position.x
                        "y" to packet.position.y
                        "z" to packet.position.z
                        "facing" to packet.facing.name
                    }
                }
                is SPacketSpawnPlayer -> {
                    logServer(packet) {
                        "entityID" to packet.entityID
                        "uniqueID" to packet.uniqueId
                        "x" to packet.x
                        "y" to packet.y
                        "z" to packet.z
                        "yaw" to packet.yaw
                        "pitch" to packet.pitch
                        "dataManagerEntries" to buildString {
                            packet.dataManagerEntries?.forEach {
                                append("> ")
                                append(it.key)
                                append(": ")
                                append(it.value)
                                append(' ')
                            }
                        }
                    }
                }
                is SPacketSpawnPosition -> {
                    if (!sPacketSpawnPosition.value) return
                    logServer(packet) {
                        "pos" to packet.spawnPos
                    }
                }
                is SPacketStatistics -> {
                    if (!sPacketStatistics.value) return
                    logServer(packet) {
                        "statistics" to packet.statisticMap
                    }
                }
                is SPacketTabComplete -> {
                    if (!sPacketTabComplete.value) return
                    logServer(packet) {
                        "matches" to packet.matches
                    }
                }
                is SPacketTeams -> {
                    if (!sPacketTeams.value) return
                    logServer(packet) {
                        "action" to packet.action
                        "type" to packet.displayName
                        "itSystem" to packet.color
                    }
                }
                is SPacketTimeUpdate -> {
                    if (!sPacketTimeUpdate.value) return
                    logServer(packet) {
                        "totalWorldTime" to packet.totalWorldTime
                        "worldTime" to packet.worldTime
                    }
                }
                is SPacketTitle -> {
                    if (!sPacketTitle.value) return
                    logServer(packet) {
                        "action" to packet.type
                        "text" to packet.message
                        "fadeIn" to packet.fadeInTime
                        "stay" to packet.displayTime
                        "fadeOut" to packet.fadeOutTime
                    }
                }
                is SPacketUnloadChunk -> {
                    if (!sPacketUnloadChunk.value) return
                    logServer(packet) {
                        "x" to packet.x
                        "z" to packet.z
                    }
                }
                is SPacketUpdateBossInfo -> {
                    if (!sPacketUpdateBossInfo.value) return
                    logServer(packet) {
                        "uuid" to packet.uniqueId
                        "operation" to packet.operation.name
                        "name" to ITextComponent.Serializer.componentToJson(packet.name)
                        "percent" to packet.percent
                        "color" to packet.color
                        "overlay" to packet.overlay.name
                        "darkenSky" to packet.shouldDarkenSky()
                        "playEndBossMusic" to packet.shouldPlayEndBossMusic()
                        "createFog" to packet.shouldCreateFog()
                    }
                }
                is SPacketUpdateHealth -> {
                    if (!sPacketUpdateHealth.value) return
                    logServer(packet) {
                        "health" to packet.health
                        "foodLevel" to packet.foodLevel
                        "saturationLevel" to packet.saturationLevel
                    }
                }
                is SPacketUpdateScore -> {
                    if (!sPacketUpdateScore.value) return
                    logServer(packet) {
                        "playerName" to packet.playerName
                        "objective" to packet.objectiveName
                        "value" to packet.scoreValue
                        "action" to packet.scoreAction.name
                    }
                }
                is SPacketUpdateTileEntity -> {
                    if (!sPacketUpdateTileEntity.value) return
                    logServer(packet) {
                        "pos" to packet.pos
                        "type" to packet.tileEntityType
                        "nbt" to packet.nbtCompound.toString()
                    }
                }
                is SPacketUseBed -> {
                    if (!sPacketUseBed.value) return
                    logServer(packet) {
                        mc.world?.let { world ->
                            "player" to packet.getPlayer(world)?.name
                        }
                        "pos" to packet.bedPosition
                    }
                }
                is SPacketWindowItems -> {
                    if (!sPacketWindowItems.value) return
                    logServer(packet) {
                        "windowId" to packet.windowId
                        "itemStacks" to buildString {
                            for (entry in packet.itemStacks) {
                                append("> ")
                                append(entry.displayName)
                                append(' ')
                            }
                        }
                    }
                }
                is SPacketWindowProperty -> {
                    if (!sPacketWindowProperty.value) return
                    logServer(packet) {
                        "windowId" to packet.windowId
                        "property" to packet.property
                        "value" to packet.value
                    }
                }
                is SPacketWorldBorder -> {
                    if (!sPacketWorldBorder.value) return
                    logServer(packet) {
                        "action" to packet.action.name
                        "size" to packet.size
                        "centerX" to packet.centerX
                        "centerZ" to packet.centerZ
                        "targetSize" to packet.targetSize
                        "diameter" to packet.diameter
                        "timeUntilTarget" to packet.timeUntilTarget
                        "warningTime" to packet.warningTime
                        "warningDistance" to packet.warningDistance
                    }
                }
                else -> {
                    logServer(packet) {
                        +"Not Registered in PacketLogger"
                    }
                }
            }
        }
    }


    private fun write() {
        if (logMode != LogMode.FILE && logMode != LogMode.CHAT_AND_FILE && logMode != LogMode.ALL) return

        val lines = synchronized(this) {
            val cache = lines
            lines = ArrayList()
            cache
        }

        defaultScope.launch(Dispatchers.IO) {
            try {
                with(File(FolderUtils.packetLogFolder)) {
                    if (!exists()) mkdir()
                }

                FileWriter("${FolderUtils.packetLogFolder}${filename}", true).buffered().use {
                    for (line in lines) it.write(line)
                }
                runSafe {
                    MessageSendHelper.sendChatMessage("$chatName Log saved at ${TextFormatting.GREEN}${FolderUtils.packetLogFolder}${filename}")
                }
            } catch (e: Exception) {
                LambdaMod.LOG.warn("$chatName Failed saving packet log!", e)
            }
        }
    }

    private inline fun logClient(packet: Packet<*>, block: PacketLogBuilder.() -> Unit) {
        PacketLogBuilder(PacketSide.CLIENT, packet).apply(block).build()
    }

    private inline fun logServer(packet: Packet<*>, block: PacketLogBuilder.() -> Unit) {
        PacketLogBuilder(PacketSide.SERVER, packet).apply(block).build()
    }

    private class PacketLogBuilder(val side: PacketSide, val packet: Packet<*>) {
        private val stringBuilder = StringBuilder()

        init {
            stringBuilder.apply {
                append(side.displayName)
                append(',')

                append(packet.javaClass.simpleName)
                if (absoluteTime) {
                    append(',')
                    append(System.currentTimeMillis())
                }
                if (startDelta) {
                    append(',')
                    append(System.currentTimeMillis() - start)
                }
                if (lastDelta) {
                    append(',')
                    append(System.currentTimeMillis() - last)
                }
                append(": ")
            }
        }

        operator fun String.unaryPlus() {
            stringBuilder.append(this)
        }

        infix fun String.to(value: Any?) {
            if (value != null) {
                add(this, value.toString())
            }
        }

        infix fun String.to(value: String?) {
            if (value != null) {
                add(this, value)
            }
        }

        infix fun String.to(value: BlockPos?) {
            if (value != null) {
                add("x", value.x.toString())
                add("y", value.y.toString())
                add("z", value.z.toString())
            }
        }

        fun add(key: String, value: String) {
            stringBuilder.apply {
                append(key)
                append(": ")
                append(value)
                append(' ')
            }
        }

        fun build() {
            val string = stringBuilder.run {
                append('\n')
                toString()
            }

            if (logMode == LogMode.CHAT_AND_FILE || logMode == LogMode.FILE || logMode == LogMode.ALL) {
                synchronized(PacketLogger) {
                    lines.add(string)
                    last = System.currentTimeMillis()
                }
            }

            if (logMode == LogMode.CHAT_AND_FILE || logMode == LogMode.CHAT || logMode == LogMode.ALL) {
                MessageSendHelper.sendChatMessage(string)
            }

            if (logMode == LogMode.ONLY_HUD || logMode == LogMode.ALL) {
                if (PacketLogViewer.visible) {
                    PacketLogViewer.addPacketLog(string.replace("\n", ""))
                }
            }
        }
    }
}
