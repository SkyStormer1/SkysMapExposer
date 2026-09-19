package com.skystormer.skysmapexposer

import org.slf4j.LoggerFactory

/**
 * What the mod writes to the log: only things that went wrong. Why nothing is drawn at a given
 * moment is shown by `/mapexposer` instead of being written every frame.
 */
object Log {

    private val LOGGER = LoggerFactory.getLogger("skysmapexposer")

    fun warn(message: String, vararg arguments: Any?) = LOGGER.warn(prefix(message), *arguments)

    fun error(message: String, cause: Throwable) = LOGGER.error(prefix(message), cause)

    private fun prefix(message: String) = "[Sky's Map Exposer] $message"
}
