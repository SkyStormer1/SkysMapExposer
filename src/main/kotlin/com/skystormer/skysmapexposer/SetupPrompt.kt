package com.skystormer.skysmapexposer

import com.skystormer.skysmapexposer.gui.ConfigScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.network.chat.Component

/**
 * On joining a server that is not in the settings, one line in chat, once a game for each, saying
 * Map Exposer has nothing to show there until its BlueMap is added: [Set up] opens the settings on
 * a new entry for it, [Not this server] stops the line for that server for good
 * ([Config.setupDeclined]). Not in single player, which rarely has a BlueMap.
 */
object SetupPrompt {

    private val told = HashSet<String>()

    fun joined(address: String?) {
        if (address == null || address == "singleplayer" || Config.serverFor(address) != null) return
        val key = Config.normalise(address)
        if (key in Config.setupDeclined || !told.add(key)) return
        Minecraft.getInstance().player?.sendSystemMessage(
            Component.literal("§7[Map Exposer]§r $address isn't set up. If it has a BlueMap, add its address and your map fills in from it.  ")
                .append(ChatButton.of("[Set up]", "/mapexposer setup", "Opens the settings on a new entry for $address: type its BlueMap address and the maps are found."))
                .append(Component.literal(" "))
                .append(ChatButton.of("[Not this server]", "/mapexposer setup off", "Stops this line for $address. The settings can still add it."))
        )
    }

    /** `/mapexposer setup`: the settings, which open on a new entry for a server not yet in them. */
    fun openSettings() {
        val minecraft = Minecraft.getInstance()
        // After the chat screen the command came from has closed, or it would close this too.
        minecraft.execute { Ui.open(ConfigScreen(PauseScreen(true))) }
    }

    /** `/mapexposer setup off`: no more "isn't set up" line for the server you are on. */
    fun decline(): String {
        val address = MapExposerClient.addressOf(Minecraft.getInstance()) ?: return "Not on a server"
        Config.setupDeclined.add(Config.normalise(address))
        Config.save()
        return "No more set-up line for $address"
    }
}
