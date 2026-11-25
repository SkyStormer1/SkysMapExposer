package com.skystormer.skysmapexposer

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Downloading BlueMap into your map from its right-click menu, the same on Xaero's and JourneyMap's:
 * "Download BlueMap…" closes the map and asks in chat ([offer]), where the buttons run the
 * `download` command ([command]), which hands the job to the map installed ([MapAdapter.download]).
 * Each choice comes as "only where your map has nothing" and "over your own map too".
 */
object DownloadOffer {

    /**
     * Asks in chat what to download, after a right-click at block ([x], [z]): [picked], the chunks
     * dragged over, when there are any, else the 512-block region there ([region] says which); or everything.
     */
    fun offer(x: Int, z: Int, picked: ShareFormat.Area?, region: String = "the 512-block region you clicked") {
        Ui.open(null)
        val here = if (picked != null) {
            val blocks = "${picked.left * 16} ${picked.top * 16} ${picked.right * 16 + 15} ${picked.bottom * 16 + 15}"
            val size = "the ${picked.width} × ${picked.height} chunks you picked"
            ChatButton.of("[Picked area, unexplored]", "/mapexposer download area $blocks", "Only where your map has nothing, in $size.")
                .copy().append(" ")
                .append(ChatButton.of("[Picked area]", "/mapexposer download area $blocks all", "All of $size, over your own map too."))
        } else {
            ChatButton.of("[This region, unexplored]", "/mapexposer download region $x $z", "Only where your map has nothing, in $region.")
                .copy().append(" ")
                .append(ChatButton.of("[This region]", "/mapexposer download region $x $z all", "All of $region, over your own map too."))
        }
        val message = Component.literal("Download BlueMap into your map, backing up each region first: ")
            .append(here)
            .append(" ")
            .append(ChatButton.of("[Everything, unexplored]", "/mapexposer download unexplored", "Only where your map has nothing, everywhere BlueMap has the map."))
            .append(" ")
            .append(ChatButton.of("[Everything]", "/mapexposer download all", "Everywhere BlueMap has the map, over your own map too."))
        Minecraft.getInstance().player?.sendSystemMessage(message)
    }

    /**
     * `download all|unexplored`, `download region <x> <z> [all]`, `download area <x1> <z1> <x2> <z2> [all]`
     * and `download cancel`; without `all`, only where your map has nothing.
     */
    fun command(): LiteralArgumentBuilder<FabricClientCommandSource> {
        fun start(context: CommandContext<FabricClientCommandSource>, unexploredOnly: Boolean, area: ShareFormat.Area?): Int =
            Ui.reply(context, MapMods.adapter?.download(unexploredOnly, area) ?: "No map mod to download into")
        return ClientCommands.literal("download")
            .then(ClientCommands.literal("all").executes { start(it, false, null) })
            .then(ClientCommands.literal("unexplored").executes { start(it, true, null) })
            .then(ClientCommands.literal("region").then(
                ClientCommands.argument("x", IntegerArgumentType.integer()).then(
                    ClientCommands.argument("z", IntegerArgumentType.integer())
                        .executes { start(it, true, regionOf(it)) }
                        .then(ClientCommands.literal("all").executes { start(it, false, regionOf(it)) })
                )
            ))
            // The chunks between two block positions: the area picked on the map.
            .then(ClientCommands.literal("area").then(ClientCommands.argument("x1", IntegerArgumentType.integer())
                .then(ClientCommands.argument("z1", IntegerArgumentType.integer())
                    .then(ClientCommands.argument("x2", IntegerArgumentType.integer())
                        .then(ClientCommands.argument("z2", IntegerArgumentType.integer())
                            .executes { start(it, true, SharedTerrain.areaOf(it)) }
                            .then(ClientCommands.literal("all").executes { start(it, false, SharedTerrain.areaOf(it)) }))))))
            .then(ClientCommands.literal("cancel").executes {
                Ui.reply(it, if (MapMods.adapter?.cancelDownload() == true) "Stopping after the region it is on" else "Nothing is being written into your map")
            })
    }

    /** The chunks of the 512-block region holding the block given as `x` and `z`. */
    private fun regionOf(context: CommandContext<FabricClientCommandSource>): ShareFormat.Area {
        val rx = Math.floorDiv(IntegerArgumentType.getInteger(context, "x"), BlueMapPicture.REGION)
        val rz = Math.floorDiv(IntegerArgumentType.getInteger(context, "z"), BlueMapPicture.REGION)
        return ShareFormat.Area(rx * 32, rz * 32, rx * 32 + 31, rz * 32 + 31)
    }
}
