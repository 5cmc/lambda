package com.lambda.client

import com.lambda.client.event.ForgeEventProcessor
import com.lambda.client.gui.clickgui.LambdaClickGui
import com.lambda.client.util.ConfigUtils
import com.lambda.client.util.KamiCheck
import com.lambda.client.util.threads.BackgroundScope
import com.babbaj.pathfinder.PathFinder
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.event.FMLInitializationEvent
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.*

@Suppress("UNUSED_PARAMETER")
@Mod(
    modid = LambdaMod.ID,
    name = LambdaMod.NAME,
    version = LambdaMod.VERSION,
    dependencies = LambdaMod.DEPENDENCIES
)
class LambdaMod {


    companion object {
        const val NAME = "Lambda"
        const val ID = "lambda"
        const val DIRECTORY = "lambda"

        const val VERSION = "3.2.1"

        const val APP_ID = 835368493150502923 // DiscordIPC
        const val DEPENDENCIES = "required-after:forge@[14.23.5.2860,);"

        const val GITHUB_API = "https://api.github.com/"
        const val PLUGIN_ORG = "lambda-plugins"

        const val LAMBDA = "λ"

        val LOG: Logger = LogManager.getLogger(NAME)

        var ready: Boolean = false; private set
    }

    @Mod.EventHandler
    fun preInit(event: FMLPreInitializationEvent) {
        val directory = File(DIRECTORY)
        if (!directory.exists()) directory.mkdir()

        LoaderWrapper.preLoadAll()
    }

    @Mod.EventHandler
    fun init(event: FMLInitializationEvent) {
        LOG.info("Initializing $NAME $VERSION")

        pathFinderInit()

        LoaderWrapper.loadAll()

        MinecraftForge.EVENT_BUS.register(ForgeEventProcessor)

        ConfigUtils.moveAllLegacyConfigs()
        ConfigUtils.loadAll()

        BackgroundScope.start()

        LambdaClickGui.populateRemotePlugins()

        KamiCheck.runCheck()

        LOG.info("$NAME initialized!")
    }

    @Mod.EventHandler
    fun postInit(event: FMLPostInitializationEvent) {
        ready = true
    }

    fun pathFinderInit() {
        try {
            val lol = System.mapLibraryName("uwu")
            val extension = lol.substring(lol.lastIndexOf('.'))
            val library = "libnether_pathfinder$extension"
            val libraryStream: InputStream? = LambdaMod::class.java.classLoader.getResourceAsStream(library)
            Objects.requireNonNull(libraryStream, "Failed to find pathfinder library ($library)")
            val tempName = System.mapLibraryName("nether_pathfinder_temp")
            val split = tempName.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val tempFile = Files.createTempFile(split[0], "." + split[1])
            println("Created temp file at " + tempFile.toAbsolutePath())
            try {
                Files.copy(libraryStream, tempFile, StandardCopyOption.REPLACE_EXISTING)
                System.load(tempFile.toAbsolutePath().toString())
            } finally {
                try {
                    Files.delete(tempFile)
                } catch (ex: IOException) {
                    println("trolled")
                }
                tempFile.toFile().delete()
                tempFile.toFile().deleteOnExit()
            }
            println("Loaded shared library")
        } catch (ex: Exception) {
            throw RuntimeException(ex)
        }
    }
}
