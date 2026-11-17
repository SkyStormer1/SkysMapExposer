package com.skystormer.skysmapexposer

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
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
 * - `waypoint <x> <y> <z> <name>` adds a waypoint where you are, as an added shared one is
 * - `click <text>` runs the newest chat button reading that, e.g. `click [Add waypoints]`
 * - `fill <blocks>` starts a terrain fill that far around you, as the settings button does
 * - `fillwait` waits for a terrain fill to finish, then logs how it went
 * - `mapzoom <scale>` zooms the open world map to that many pixels a block (Xaero eases to it)
 * - `refresh` presses the map bar's Refresh button
 * - `goto <x> <z>` sends the installed map's full-screen map there, as the player list's Go to does
 * - `mouse <x> <y>` puts the mouse there, in window pixels, so maps see it move
 * - `close` closes whatever screen is open
 * - `join <world>` opens that singleplayer world, first making it (creative, peaceful) if needed
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
                "goto" -> {
                    val (x, z) = rest.split(' ').map { it.toInt() }
                    val dimension = client.level?.dimension()?.identifier()?.toString() ?: return
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
