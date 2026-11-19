package com.skystormer.skysmapexposer

import org.slf4j.LoggerFactory

/**
 * What the mod writes to the log: only things that went wrong. Why nothing is drawn at a given
 * moment is shown by `/mapexposer` instead of being written every frame.
 */
object Log {

    private val LOGGER = LoggerFactory.getLogger("skysmapexposer")

    fun info(message: String, vararg arguments: Any?) = LOGGER.info(prefix(message), *arguments)

    fun warn(message: String, vararg arguments: Any?) = LOGGER.warn(prefix(message), *arguments)

    fun error(message: String, cause: Throwable) = LOGGER.error(prefix(message), cause)

    /** Runs [block], writing [failure] and the error to the log if it throws anything; nothing more is done about it. */
    inline fun guarded(failure: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            error(failure, e)
        }
    }

    private fun prefix(message: String) = "[Sky's Map Exposer] $message"
}
