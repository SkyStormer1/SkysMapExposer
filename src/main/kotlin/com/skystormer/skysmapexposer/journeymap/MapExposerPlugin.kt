package com.skystormer.skysmapexposer.journeymap

import journeymap.api.v2.client.IClientAPI
import journeymap.api.v2.client.IClientPlugin
import journeymap.api.v2.common.JourneyMapPlugin

/**
 * JourneyMap's way in (the `journeymap` entrypoint): it starts this once and hands over its API,
 * which is all this keeps. JourneyMap is the only thing that loads it, so it never loads without it.
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
class MapExposerPlugin : IClientPlugin {

    override fun getModId(): String = JourneyMapAdapter.MOD_ID

    override fun initialize(api: IClientAPI) {
        JourneyMapAdapter.api = api
    }
}
