package com.lambda.client

import com.lambda.client.event.ForgeEventProcessor
import com.lambda.client.util.ConfigUtils
import com.lambda.client.util.KamiCheck
import com.lambda.client.util.threads.BackgroundScope
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.event.FMLInitializationEvent
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent
import org.apache.commons.lang3.SystemUtils
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

        val FULL_VERSION = LambdaMod::class.java.classLoader.getResourceAsStream("lambda_version.txt")?.let { stream ->
            Scanner(stream).use { scanner ->
                scanner.nextLine()
            }
        } ?: run {
            "DEV"
        }

        const val VERSION = "3.3.0"

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
        LOG.info("Initializing $NAME $FULL_VERSION")
        // load this class so that baritone doesn't crash
        // see https://github.com/cabaletta/baritone/issues/3859
        val baritoneTroll = baritone.api.utils.BetterBlockPos::class.java
        pathFinderInit()

        LoaderWrapper.loadAll()

        MinecraftForge.EVENT_BUS.register(ForgeEventProcessor)

        ConfigUtils.moveAllLegacyConfigs()
        ConfigUtils.loadAll()

//        LambdaClickGui.populateRemotePlugins()

        KamiCheck.runCheck()

        LOG.info("$NAME initialized!")
    }

    @Mod.EventHandler
    fun postInit(event: FMLPostInitializationEvent) {
        ready = true
        BackgroundScope.start()
    }

    private fun pathFinderInit() {
        try {
            val extension = if (SystemUtils.IS_OS_WINDOWS) "windows.dll"
                else if (SystemUtils.IS_OS_MAC) "osx.dylib"
                else if (SystemUtils.IS_OS_LINUX) "linux.os" // we don't have a compiled version for linux yet tho, sry nerds
                else return
            var library = "nether_pathfinder_$extension"
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
        } catch (ex: Throwable) {
            LOG.error("Failed linking nether pathfinder library, command will not function")
        }
    }
}
