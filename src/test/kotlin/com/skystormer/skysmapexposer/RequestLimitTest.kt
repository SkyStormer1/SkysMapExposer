package com.skystormer.skysmapexposer

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Every job's requests share one limit on how many reach BlueMap at once (alarm A2). */
class RequestLimitTest {

    private lateinit var server: HttpServer
    private val now = AtomicInteger()
    private val most = AtomicInteger()
    private val release = CountDownLatch(1)

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            val here = now.incrementAndGet()
            most.accumulateAndGet(here) { a, b -> maxOf(a, b) }
            release.await(5, TimeUnit.SECONDS)
            now.decrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun url() = "http://127.0.0.1:${server.address.port}/"

    @Test
    fun manyJobsNeverSendMoreThanTheLimitAtOnce() {
        // Separate BlueMap objects, as separate jobs (map, download, prepare, terrain fill) have.
        val jobs = List(4) { BlueMap(url()) }
        val pool = Executors.newFixedThreadPool(20)
        val done = (0 until 20).map { i -> pool.submit { jobs[i % 4].tile("world", 1, i, 0) } }
        Thread.sleep(500)
        release.countDown()
        done.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertTrue(most.get() in 1..6, "at most 6 at once, saw ${most.get()}")
        assertEquals(6, most.get())
    }

    @Test
    fun closingStopsWaitingRequests() {
        val blueMap = BlueMap(url())
        val failed = AtomicInteger()
        val thread = Thread { try { blueMap.tile("world", 1, 0, 0) } catch (e: IOException) { failed.incrementAndGet() } }
        thread.start()
        Thread.sleep(300)
        blueMap.close()
        thread.join(3_000)
        assertTrue(!thread.isAlive, "a closed BlueMap's request returns instead of waiting for the server")
        assertEquals(1, failed.get())
        release.countDown()
    }
}
