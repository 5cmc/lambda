package com.lambda.client.module.modules.render

import com.google.common.base.Strings
import com.google.common.collect.ImmutableMap
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.NetherPathFinderRenderer
import com.lambda.pathfinder.PathFinder
import joptsimple.OptionParser
import joptsimple.OptionSet
import joptsimple.OptionSpec
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.entity.Entity
import net.minecraft.util.Tuple
import net.minecraft.util.math.BlockPos
import net.minecraft.util.text.TextComponentString
import net.minecraftforge.client.event.ClientChatEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.function.BiConsumer
import java.util.function.Function
import java.util.stream.Collectors

object NetherPathfinder : Module(
    name = "NetherPathfinder",
    description = "Pathfind in the nether",
    category = Category.RENDER
) {


    private val executor = Executors.newCachedThreadPool()
    private val seeds: MutableMap<String, Long>? = null
    private var renderer: NetherPathFinderRenderer? = null
    private var pathFuture: Future<*>? = null

    private val commands: Map<String, Function<OptionParser, ICommand>> = ImmutableMap.builder<String, Function<OptionParser, ICommand>>()
        .put("help", Function { parser: OptionParser? -> Help(parser) })
        .put("pathfind", Function { parser: OptionParser -> PathFind(parser) })
        .put("addseed", Function { parser: OptionParser -> AddSeed(parser) })
        .put("cancel", Function { parser: OptionParser? -> Cancel(parser) })
        .put("reset", Function { parser: OptionParser? -> Reset(parser) })
        .build()

    // TODO: add description function
    internal interface ICommand : BiConsumer<List<String>, OptionSet> {
        override fun accept(args: List<String>, options: OptionSet)
        fun description(): String
        fun usage(): List<String>
        fun optionHelp(): List<String> {
            return emptyList()
        }
    }

    private fun getSeedFomOption(arg: String): Long {
        return try {
            arg.toLong()
        } catch (ex: NumberFormatException) {
            val seed = seeds!![arg]
            seed ?: throw IllegalArgumentException("No server with name $arg")
        }
    }

    // might want this to only return ip
    private fun getServerName(): String {
        return Optional.ofNullable(Minecraft.getMinecraft().currentServerData)
            .map { server: ServerData -> if (!Strings.isNullOrEmpty(server.serverIP)) server.serverIP else server.serverName }
            .filter { str: String -> !str.isEmpty() }
            .orElse("localhost")
        //.orElseThrow(() -> new IllegalStateException("Failed to get ip or server name"));
    }

    @Throws(NumberFormatException::class) private fun parseCoord(arg: String, player: Int): Int {
        return if (arg.startsWith("~")) {
            if (arg.length == 1) player else player + arg.substring(1).toInt()
        } else {
            arg.toInt()
        }
    }

    private fun parsePosition(x: String, y: String, z: String): BlockPos {
        val player: Entity = Minecraft.getMinecraft().player
        return BlockPos(parseCoord(x, player.posX.toInt()), parseCoord(y, player.posY.toInt()), parseCoord(z, player.posZ.toInt()))
    }

    @Throws(IllegalArgumentException::class) private fun parseCoords(args: List<String>): Tuple<BlockPos, BlockPos> {
        return if (args.size == 6) {
            Tuple(
                parsePosition(args[0], args[1], args[2]),
                parsePosition(args[3], args[4], args[5])
            )
        } else if (args.size == 3) {
            val player: Entity = Minecraft.getMinecraft().player
            Tuple(
                BlockPos(player.posX.toInt(), player.posY.toInt(), player.posZ.toInt()),
                parsePosition(args[0], args[1], args[2])
            )
        } else {
            throw IllegalArgumentException("Invalid number of arguments(" + args.size + "), expected 3 or 6")
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

    private fun resetRenderer() {
        if (renderer != null) {
            MinecraftForge.EVENT_BUS.unregister(renderer)
            renderer!!.deleteBuffer()
            renderer = null
        }
    }

    private fun sendMessage(str: String) {
        Minecraft.getMinecraft().player.sendMessage(TextComponentString(str))
    }

    private fun addToChatHistory(msg: String) {
        Minecraft.getMinecraft().ingameGUI.chatGUI.addToSentMessages(msg)
    }

    fun checkY(y: Int) {
        require(!(y <= 0 || y > 127)) { "Y level not in valid range" }
    }

    private class PathFind internal constructor(parser: OptionParser) : ICommand {
        private val seedOption: OptionSpec<String>

        init {
            seedOption = parser.accepts("seed").withRequiredArg().defaultsTo("146008555100680")
            parser.accepts("fine")
            parser.accepts("noraytrace")
        }

        override fun description(): String {
            return "Run the pathfinder (prepend '~' for relative coords)"
        }

        override fun usage(): List<String> {
            return Arrays.asList(
                "<x> <y> <z> <x> <y> <z>",
                "<x> <y> <z>"
            )
        }

        override fun optionHelp(): List<String> {
            return Arrays.asList(
                "--seed  (default: 146008555100680)",
                "--noraytrace  do not raytrace the result of the pathfinder",
                "--fine  high resolution but slower pathfinding"
            )
        }

        override fun accept(args: List<String>, options: OptionSet) {
            val mc = Minecraft.getMinecraft()
            val coords = parseCoords(args)
            val seed: Long
            if (options.has(seedOption)) {
                seed = getSeedFomOption(options.valueOf(seedOption))
                sendMessage("Pathing with seed: $seed")
            } else {
                val ip = getServerName()
                val seedObj = seeds!![ip]
                seed = seedObj ?: throw IllegalArgumentException("No seed for server \"$ip\"")
            }
            val a = coords.first
            val b = coords.second
            checkY(a.y)
            checkY(b.y)
            if (pathFuture != null) {
                pathFuture!!.cancel(true)
                pathFuture = null
                sendMessage("Canceled existing pathfinder")
            }
            resetRenderer()
            pathFuture = executor.submit {
                val t1 = System.currentTimeMillis()
                val longs: LongArray = PathFinder.pathFind(seed, options.has("fine"), !options.has("noraytrace"), a.x, a.y, a.z, b.x, b.y, b.z)
                // TODO: native code should check the interrupt flag and throw InterruptedException
                if (Thread.currentThread().isInterrupted) {
                    return@submit
                }
                val t2 = System.currentTimeMillis()
                val path = Arrays.stream(longs).mapToObj { serialized: Long -> BlockPos.fromLong(serialized) }.collect(Collectors.toList())
                mc.addScheduledTask {
                    registerRenderer(path)
                    pathFuture = null
                    sendMessage(String.format("Found path in %.2f seconds", (t2 - t1) / 1000.0))
                }
            }
        }
    }

    private class Help(parser: OptionParser?) : ICommand {
        override fun description(): String {
            return "Print this message"
        }

        override fun usage(): List<String> {
            return emptyList()
        }

        override fun accept(strings: List<String>, optionSet: OptionSet) {
            printHelp()
        }
    }

    private class AddSeed(parser: OptionParser) : ICommand {
        private val ipOption: OptionSpec<String>

        init {
            ipOption = parser.accepts("ip").withRequiredArg()
        }

        override fun description(): String {
            return "Set the seed for the current server"
        }

        override fun usage(): List<String> {
            return listOf("<seed>")
        }

        override fun optionHelp(): List<String> {
            return Arrays.asList(
                "--ip <String>"
            )
        }

        override fun accept(args: List<String>, options: OptionSet) {
            require(args.size == 1) { "Expected 1 argument" }
            val seed = args[0].toLong()
            val ip: String
            ip = if (options.has(ipOption)) {
                options.valueOf(ipOption)
            } else {
                getServerName()
            }
            seeds!![ip] = seed
            sendMessage("Set seed for $ip")
//            PathFinderMod.writeSeedsToDisk(seeds)
        }
    }

    private class Cancel(parser: OptionParser?) : ICommand {
        override fun description(): String {
            return "Stop the current pathfinding thread (will still run in the background)"
        }

        override fun usage(): List<String> {
            return emptyList()
        }

        override fun accept(args: List<String>, options: OptionSet) {
            if (pathFuture != null) {
                pathFuture!!.cancel(true)
                pathFuture = null
                sendMessage("Canceled pathfinder")
            } else {
                sendMessage("No pathfinder runing")
            }
        }
    }

    private class Reset(parser: OptionParser?) : ICommand {
        override fun description(): String {
            return "Stop rendering the path"
        }

        override fun usage(): List<String> {
            return emptyList()
        }

        override fun accept(args: List<String>, options: OptionSet) {
            resetRenderer()
        }
    }

    fun printHelp() {
        sendMessage("Commands:")
        commands.forEach { (cmd: String, fn: Function<OptionParser, ICommand>) ->
            val parser = OptionParser()
            val icmd = fn.apply(parser)
            sendMessage(cmd + ": " + icmd.description())
            for (usage in icmd.usage()) {
                sendMessage(";$cmd $usage")
            }
            for (line in icmd.optionHelp()) {
                sendMessage(line)
            }
            sendMessage("")
        }
    }

    @SubscribeEvent fun onChat(event: ClientChatEvent) {
        val msg = event.originalMessage
        if (msg.startsWith(":")) { // TODO customizable char
            event.isCanceled = true
            addToChatHistory(msg) // forge is dumb
            if (msg.length > 1) {
                val cmd = msg.substring(1)
                val args0 = cmd.split(" +".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                if (args0.size > 0) {
                    val command = commands[args0[0].lowercase(Locale.getDefault())]
                    if (command != null) {
                        val args = Arrays.copyOfRange(args0, 1, args0.size)
                        val parser = OptionParser()
                        parser.allowsUnrecognizedOptions()
                        try {
                            val consumer = command.apply(parser)
                            val opts = parser.parse(*args)
                            consumer.accept(opts.nonOptionArguments() as List<String>, opts)
                        } catch (ex: Exception) {
                            // input error
                            sendMessage(ex.toString())
                            // print stacktrace in case it's a bug
                            ex.printStackTrace()
                        }
                    } else {
                        sendMessage("Invalid command")
                    }
                }
            } else {
                printHelp()
            }
        }
    }
}