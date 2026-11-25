package com.skystormer.skysmapexposer

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Style
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import java.nio.file.Files
import java.nio.file.Path

/**
 * For testing in a development client only: from the title screen or a world, runs the steps in the file named by
 * the `skysmapexposer.autotest` system property, one a tick, and logs each ("AutoTest: …"), so a
 * test run can be driven and looked at without anyone at the keyboard. Without the property, which
 * only a development launch sets, it does nothing.
 *
 * One step a line; `#` starts a comment:
 * - `wait <seconds>`
 * - `waitplayer <name>` waits until that player is on the server, to keep two test clients in step
 * - `cmd <command>` runs a command as if typed in chat, this mod's client commands included
 * - `chat <text>` says something
 * - `look <yaw> <pitch>` turns you to look that way
 * - `key <key>` presses a key once, by the game's name for it, e.g. `key.keyboard.m`
 * - `screenshot` takes a screenshot (F2's), into `screenshots/`
 * - `waypoint <x> <y> <z> <name>` adds a waypoint there, in the dimension you are in, as an added shared one is
 * - `waypointin <dimension> <x> <y> <z> <name>` adds one to that dimension's waypoints
 * - `mywaypoints` logs the waypoints the installed map would offer to share
 * - `shareeveryone [dimension]` shares two of your waypoints of a dimension with everyone, in chat
 * - `jmtype <Day|Night|Topo|Biome>` switches JourneyMap's full-screen map to that type
 * - `biomecheck` logs how often [BiomeZoom] picks the biome the game shows, around you
 * - `click <text>` runs the newest chat button reading that, e.g. `click [Add waypoints]`
 * - `fill <blocks>` starts a terrain fill that far around you, as the settings button does
 * - `fillwait` waits for a terrain fill to finish, then logs how it went
 * - `mapzoom <scale>` zooms the open world map to that many pixels a block (Xaero eases to it)
 * - `refresh` presses the map bar's Refresh button
 * - `goto <x> <z> [dimension]` sends the installed map's full-screen map there, as the player list's Go to does
 * - `mouse <x> <y>` puts the mouse there, in window pixels, so maps see it move
 * - `close` closes whatever screen is open
 * - `menurows` / `menupick <text>` logs the rows of Xaero's open right-click menu, or picks the first with that text
 * - `editmenu` opens the right-click menu editor
 * - `zoom in|out` zooms the open screen, as JourneyMap's full-screen map does
 * - `gotoplayer` centres the map on the first player BlueMap lists in your dimension
 * - `addshared blank|all` adds the map file opened last, as its chat button would
 * - `button left|right down|up` and `moveto <x> <y>`: real mouse input through Minecraft's own handler
 * - `rightdown|rightdrag|rightup [<x> <y>]` the right mouse button on the open screen
 * - `join <world>` opens that singleplayer world, first making it (creative, peaceful) if needed
 * - `server <address>` joins that server
 * - `guiscale <n>` sets the GUI scale (0 auto)
 * - `waitworld` waits until you are in a world with no screen open
 * - `leave` saves and quits to the title screen, as the pause menu does
 * - `waittitle` waits until you are back on the title screen
 * - `heap` collects garbage and logs the memory in use, for checking that leaving frees it
 * - `quit` closes the game
 *
 * Saving the file again once it has finished runs it again.
 */
object AutoTest {

    private val file: Path? = System.getProperty("skysmapexposer.autotest")?.let { Path.of(it) }
    private var steps: ArrayDeque<String>? = null
    private var started = false
    private var waitUntil = 0L
    private var loadedFrom = 0L

    // Loaded by name from MapExposerClient, only in jars built with the test driver.
    init {
        ClientTickEvents.END_CLIENT_TICK.register(::tick)
    }

    fun tick(client: Minecraft) {
        val file = file ?: return
        if (client.gui.overlay() != null) return // still loading the game or its resources
        val now = System.currentTimeMillis()
        // Saving the file again runs it again, so steps can be changed without restarting the game.
        val changed = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
        if (!started || (steps == null && changed != loadedFrom)) {
            started = true
            loadedFrom = changed
            steps = ArrayDeque(Files.readAllLines(file).map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() })
            waitUntil = now + START_DELAY
            Log.info("AutoTest: {} steps from {}", steps!!.size, file)
        }
        val queue = steps ?: return
        if (now < waitUntil) return
        val step = queue.removeFirstOrNull() ?: run {
            Log.info("AutoTest: done")
            steps = null
            return
        }
        Log.info("AutoTest: {}", step)
        val verb = step.substringBefore(' ')
        val rest = step.substringAfter(' ', "")
        try {
            val player by lazy { client.player ?: error("not in a world") }
            when (verb) {
                "wait" -> waitUntil = now + (rest.toDouble() * 1000).toLong()
                "waitplayer" -> {
                    if (ChatShare.others().none { it == rest }) {
                        queue.addFirst(step)
                        waitUntil = now + 1_000L
                    }
                }
                "cmd" -> player.connection.sendCommand(rest.removePrefix("/"))
                "chat" -> player.connection.sendChat(rest)
                "look" -> {
                    val (yaw, pitch) = rest.split(' ').map { it.toFloat() }
                    player.setYRot(yaw)
                    player.setXRot(pitch)
                    player.yRotO = yaw
                    player.xRotO = pitch
                }
                "key" -> KeyMapping.click(InputConstants.getKey(rest))
                "screenshot" -> Screenshot.grab(client, false)
                "waypoint" -> {
                    val parts = rest.split(' ', limit = 4)
                    val (x, y, z) = parts.take(3).map { it.toInt() }
                    val name = parts.getOrElse(3) { "Test" }
                    val dimension = client.level?.dimension()?.identifier()?.toString() ?: return
                    Log.info("AutoTest: {}", MapMods.adapter?.addWaypoints(dimension, "AutoTest", listOf(ShareFormat.SharedWaypoint(name, name.take(1), x, y, z, true, 0))))
                }
                "waypointin" -> {
                    val parts = rest.split(' ', limit = 5)
                    val (x, y, z) = parts.subList(1, 4).map { it.toInt() }
                    val name = parts.getOrElse(4) { "Test" }
                    Log.info("AutoTest: {}", MapMods.adapter?.addWaypoints(parts[0], "AutoTest", listOf(ShareFormat.SharedWaypoint(name, name.take(1), x, y, z, true, 0))))
                }
                "jmtype" -> {
                    // JourneyMap's full-screen map to Day, Night, Topo or Biome, through its API (found by name, so no JourneyMap is needed to build this).
                    val screen = client.gui.screen() ?: return
                    val types = Class.forName("journeymap.api.v2.common.Context\$MapType")
                    val type = types.enumConstants.first { (it as Enum<*>).name.equals(rest, ignoreCase = true) }
                    // In the dimension the map shows, which the dimension toggle may have changed from yours.
                    val shown = screen.javaClass.getMethod("getUiState").invoke(screen)?.let { it.javaClass.getField("dimension").get(it) } ?: client.level?.dimension()
                    screen.javaClass.getMethod("updateMapType", types, Integer::class.java, net.minecraft.resources.ResourceKey::class.java)
                        .invoke(screen, type, null, shown)
                }
                "biomecheck" -> {
                    // Our copy of the game's per-block biome rule against the game's own, at the surface around you.
                    val level = client.level ?: return
                    val seed = BiomeZoom.seed()
                    var same = 0
                    var total = 0
                    val random = java.util.Random(1)
                    repeat(2000) {
                        val x = player.blockX + random.nextInt(256) - 128
                        val z = player.blockZ + random.nextInt(256) - 128
                        val y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1
                        if (seed == null || !level.hasChunk(x shr 4, z shr 4)) return@repeat
                        val cell = BiomeZoom.cellOf(seed, x, y, z)
                        val ours = level.getNoiseBiome((cell shr 32).toInt(), y shr 2, cell.toInt())
                        total++
                        if (ours.unwrapKey() == level.getBiome(net.minecraft.core.BlockPos(x, y, z)).unwrapKey()) same++
                    }
                    Log.info("AutoTest: biome rule matches the game at {} of {} blocks (seed {})", same, total, if (seed == null) "unknown" else "known")
                }
                "shareeveryone" -> {
                    val mine = MapMods.adapter?.myWaypoints(rest.ifBlank { null })
                    Log.info("AutoTest: {}", mine?.let { ChatShare.shareWaypointsWithEveryone(it.first, it.second.take(2).map { w -> w.waypoint }) } ?: "no waypoints")
                }
                // Xaero's open right-click menu: `menurows` logs its rows, `menupick <text>` picks the first row with that text.
                "menurows", "menupick" -> {
                    val screen = client.gui.screen() ?: return
                    val menu = screen.javaClass.getDeclaredField("rightClickMenu").apply { isAccessible = true }.get(screen)
                    if (menu == null) {
                        Log.info("AutoTest: no right-click menu open")
                        return
                    }
                    val options = menu.javaClass.getDeclaredField("actionOptions").apply { isAccessible = true }.get(menu) as List<*>
                    val texts = options.map { (it!!.javaClass.getMethod("getDisplayName").invoke(it) as Component).string }
                    Log.info("AutoTest: menu rows: {}", texts.joinToString(" | "))
                    if (verb == "menupick") {
                        val i = texts.indexOfFirst { rest in it }
                        Log.info("AutoTest: picking row {}", i)
                        if (i >= 0) menu.javaClass.getMethod("selectId", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(menu, i, false)
                    }
                }
                "editmenu" -> Ui.open(com.skystormer.skysmapexposer.gui.MenuEditScreen(client.gui.screen(), if (MapMods.xaeroWorldMap) MenuLayout.XAERO else MenuLayout.JOURNEYMAP))
                "mywaypoints" -> Log.info("AutoTest: my waypoints: {}", MapMods.adapter?.myWaypoints()?.let { (d, w) -> "$d: " + w.joinToString { it.label + " [" + it.set + "]" } })
                "click" -> {
                    val command = buttonCommand(client, rest)
                    if (command == null) Log.warn("AutoTest: no chat button '{}'", rest)
                    else player.connection.sendCommand(command.removePrefix("/"))
                }
                "fill" -> {
                    com.skystormer.skysmapexposer.terrain.TerrainFill.radius = rest.toInt()
                    Log.info("AutoTest: {}", com.skystormer.skysmapexposer.terrain.TerrainFill.start())
                }
                "fillwait" -> {
                    if (com.skystormer.skysmapexposer.terrain.TerrainFill.running) {
                        queue.addFirst(step)
                        waitUntil = now + 2_000L
                    } else {
                        Log.info("AutoTest: fill: {}", com.skystormer.skysmapexposer.terrain.TerrainFill.status())
                    }
                }
                "mapzoom" -> (client.gui.screen() as? MapCamera)?.skysmapexposerSetZoom(rest.toDouble())
                "refresh" -> Log.info("AutoTest: refresh pressed: {}", MapRefresh.press())
                // `zoom out` / `zoom in`: the screen open's own zoomOut() / zoomIn(), as JourneyMap's full-screen map has.
                "zoom" -> client.gui.screen()?.let { it.javaClass.getMethod(if (rest == "in") "zoomIn" else "zoomOut").invoke(it) }
                // `gotoplayer`: the map centred on the first player BlueMap lists in your dimension.
                "gotoplayer" -> {
                    val dimension = client.level?.dimension()?.identifier()?.toString() ?: return
                    val session = Session.current ?: return
                    val player = session.server.mapFor(dimension)?.let(session.markers::players)?.firstOrNull() ?: return Log.info("AutoTest: no players listed")
                    Log.info("AutoTest: go to {}: {}", player.label, MapMods.adapter?.goTo(dimension, player.x.toInt(), player.z.toInt(), client.gui.screen(), player.uuid))
                }
                // `addshared blank|all`: the map file opened last, added to the map in use, as its chat button would.
                "addshared" -> {
                    val id = SharedTerrain.lastOpened() ?: return Log.info("AutoTest: no map file open")
                    val file = SharedTerrain.opened(id) ?: return
                    Log.info("AutoTest: add shared: {}", MapMods.adapter?.addSharedTerrain(file, id, rest != "all", true))
                }
                "goto" -> {
                    val parts = rest.split(' ')
                    val (x, z) = parts.take(2).map { it.toInt() }
                    val dimension = parts.getOrNull(2) ?: client.level?.dimension()?.identifier()?.toString() ?: return
                    Log.info("AutoTest: go to: {}", MapMods.adapter?.goTo(dimension, x, z, client.gui.screen(), null))
                }
                "mouse" -> {
                    val (x, y) = rest.split(' ').map { it.toDouble() }
                    //? if >=26.3 {
                    org.lwjgl.sdl.SDLMouse.SDL_WarpMouseInWindow(client.window.handle(), x.toFloat(), y.toFloat())
                    //?} else {
                    /*org.lwjgl.glfw.GLFW.glfwSetCursorPos(client.window.handle(), x, y)
                    *///?}
                }
                // Real mouse input, through Minecraft's own handler as a mouse would send it:
                // `button left|right down|up`, `moveto x y` (window pixels).
                "button", "moveto" -> {
                    val handler = client.mouseHandler
                    val window = client.window.handle()
                    if (verb == "moveto") {
                        val (x, y) = rest.split(' ').map { it.toDouble() }
                        val move = handler.javaClass.declaredMethods.first { it.name == "onMove" && it.parameterCount == 3 }.apply { isAccessible = true }
                        move.invoke(handler, window, x, y)
                    } else {
                        val (which, how) = rest.split(' ')
                        val press = handler.javaClass.declaredMethods.first { it.name == "onButton" && it.parameterCount == 3 }.apply { isAccessible = true }
                        press.invoke(handler, window, net.minecraft.client.input.MouseButtonInfo(if (which == "right") com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT else com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0), if (how == "down") 1 else 0)
                    }
                    Log.info("AutoTest: {} {}", verb, rest)
                }
                // The right mouse button on the screen open, where the mouse is: down, dragged to where
                // the mouse is now, up; through the same hooks as a real one.
                "rightdown", "rightdrag", "rightup" -> {
                    val screen = client.gui.screen() ?: return
                    // `rightdown 300 200`: the mouse put there first (window pixels), as the script's
                    // own mouse moves do not reach Minecraft's idea of where the mouse is.
                    rest.split(' ').filter { it.isNotBlank() }.map { it.toDouble() }.takeIf { it.size == 2 }?.let { (x, y) ->
                        for ((name, value) in listOf("xpos" to x, "ypos" to y)) {
                            client.mouseHandler.javaClass.getDeclaredField(name).apply { isAccessible = true }.setDouble(client.mouseHandler, value)
                        }
                    }
                    val scale = client.window.guiScale.toDouble()
                    val event = net.minecraft.client.input.MouseButtonEvent(
                        client.mouseHandler.xpos() / scale, client.mouseHandler.ypos() / scale, net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT, 0),
                    )
                    screen.mouseMoved(event.x(), event.y())
                    when (verb) {
                        "rightdown" -> screen.mouseClicked(event, false)
                        "rightdrag" -> if (net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseDrag(screen).invoker().allowMouseDrag(screen, event, 1.0, 1.0)) {
                            screen.mouseDragged(event, 1.0, 1.0)
                        }
                        else -> if (net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseRelease(screen).invoker().allowMouseRelease(screen, event)) {
                            screen.mouseReleased(event)
                        }
                    }
                    Log.info("AutoTest: {} at {}, {}", verb, event.x(), event.y())
                }
                "close" -> {
                    Log.info("AutoTest: closing {}", client.gui.screen()?.javaClass?.simpleName)
                    client.gui.setScreen(null)
                }
                "join" -> {
                    if (client.levelSource.levelExists(rest)) client.createWorldOpenFlows().openWorld(rest) {}
                    else client.createWorldOpenFlows().createFreshLevel(
                        rest,
                        LevelSettings(rest, GameType.CREATIVE, LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT),
                        WorldOptions.defaultWithRandomSeed(), WorldPresets::createNormalWorldDimensions, TitleScreen(),
                    )
                }
                // `guiscale <n>`: the GUI scale setting (0 auto), as the video settings set it.
                "guiscale" -> {
                    client.options.guiScale().set(rest.toInt())
                    client.options.save()
                    client.resizeGui()
                }
                // `server <address>`: joins that server, with its saved entry's settings where it has one.
                "server" -> {
                    val list = net.minecraft.client.multiplayer.ServerList(client).apply { load() }
                    val data = (0 until list.size()).map(list::get).firstOrNull { it.ip.equals(rest, ignoreCase = true) }
                        ?: net.minecraft.client.multiplayer.ServerData(rest, rest, net.minecraft.client.multiplayer.ServerData.Type.OTHER)
                    net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                        TitleScreen(), client, net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(data.ip), data, false, null,
                    )
                }
                "waitworld" -> if (client.player == null || client.gui.screen() != null) {
                    queue.addFirst(step)
                    waitUntil = now + 500L
                }
                "leave" -> client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE)
                "waittitle" -> if (client.level != null || client.gui.screen() !is TitleScreen) {
                    queue.addFirst(step)
                    waitUntil = now + 500L
                }
                "heap" -> {
                    repeat(2) { System.gc() }
                    val runtime = Runtime.getRuntime()
                    Log.info("AutoTest: heap {} MB used of {} MB", (runtime.totalMemory() - runtime.freeMemory()) shr 20, runtime.maxMemory() shr 20)
                }
                "quit" -> client.stop()
                else -> Log.warn("AutoTest: no such step '{}'", verb)
            }
        } catch (e: Exception) {
            Log.warn("AutoTest: step '{}' failed: {}", step, e.toString())
        }
    }

    /** The command behind the newest chat button reading [text], as clicking it would run it. */
    private fun buttonCommand(client: Minecraft, text: String): String? {
        val field = ChatComponent::class.java.getDeclaredField("allMessages").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val messages = field.get(client.gui.hud.chat) as List<GuiMessage>
        for (message in messages) {
            var found: String? = null
            message.content().visit({ style, part ->
                val click = style.clickEvent
                if (part == text && click is ClickEvent.RunCommand) found = click.command()
                java.util.Optional.empty<Unit>()
            }, Style.EMPTY)
            if (found != null) return found
        }
        return null
    }

    private const val START_DELAY = 8_000L
}
