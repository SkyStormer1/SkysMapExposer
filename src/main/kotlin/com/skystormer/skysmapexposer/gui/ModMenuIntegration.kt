package com.skystormer.skysmapexposer.gui

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

/**
 * Puts the settings behind Mod Menu's cog.
 *
 * Mod Menu is a compile-time dependency only. Fabric loads a `modmenu` entrypoint solely when Mod
 * Menu asks for it, so this class is never touched on an install without it.
 */
class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> = ConfigScreenFactory(::ConfigScreen)
}
