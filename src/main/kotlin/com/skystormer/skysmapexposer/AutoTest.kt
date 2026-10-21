package com.skystormer.skysmapexposer

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Style
import java.nio.file.Files
import java.nio.file.Path

/**
 * For testing in a development client only: once in a world, runs the steps in the file named by
 * the `skysmapexposer.autotest` system property, one a tick, and logs each ("AutoTest: …"), so a
 * test run can be driven and looked at without anyone at the keyboard. Without the property, which
 * only a development launch sets, it does nothing.
 *
 * One step a line; `#` starts a comment:
 * - `wait <seconds>`
 * - `cmd <command>` runs a command as if typed in chat, this mod's client commands included
 * - `chat <text>` says something
 * - `look <yaw> <pitch>` turns you to look that way
 * - `key <key>` presses a key once, by the game's name for it, e.g. `key.keyboard.m`
 * - `screenshot` takes a screenshot (F2's), into `screenshots/`
 * - `waypoint <x> <y> <z> <name>` adds a waypoint where you are, as an added shared one is
 * - `click <text>` runs the newest chat button reading that, e.g. `click [Add waypoints]`
 * - `fill <blocks>` starts a terrain fill that far around you, as the settings button does
 * - `fillwait` waits for a terrain fill to finish, then logs how it went
 * - `close` closes whatever screen is open
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
        val player = client.player ?: return
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
            when (verb) {
                "wait" -> waitUntil = now + (rest.toDouble() * 1000).toLong()
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
                    Log.info("AutoTest: {}", WaypointShare.add(dimension, "AutoTest", listOf(ShareFormat.SharedWaypoint(name, name.take(1), x, y, z, true, 0))))
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
                "close" -> client.gui.setScreen(null)
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
