package com.skystormer.skysmapexposer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.LocalDateTime

/** Restoring goes back exactly where a copy came from, only when confirmed, and can be undone. */
class RestoreTest {

    private val server = "play.example.net"
    private val overworld = "minecraft:overworld"
    private val nether = "minecraft:the_nether"

    private class World(root: Path) {
        val backups: Path = Files.createDirectories(root.resolve("backups"))
        val overworld: Path = Files.createDirectories(root.resolve("map/overworld"))
        val nether: Path = Files.createDirectories(root.resolve("map/nether/caves/80"))
    }

    private fun file(folder: Path, name: String, text: String, date: Long? = null): Path =
        Files.writeString(folder.resolve(name), text).also { if (date != null) Files.setLastModifiedTime(it, FileTime.fromMillis(date)) }

    private fun batch(world: World, name: String, origin: MapBackup.Origin?, vararg regions: Pair<String, String?>): Path {
        val batch = Files.createDirectories(world.backups.resolve(name))
        origin?.write(batch)
        for ((region, text) in regions) {
            if (text == null) Files.writeString(batch.resolve("$region.absent"), "") else file(batch, region, text)
        }
        return batch
    }

    private fun contents(folder: Path): Map<String, String> = Files.list(folder).use { files ->
        files.toList().filter(Files::isRegularFile).associate { it.fileName.toString() to Files.readString(it) }
    }

    @Test
    fun `an Overworld copy restored while viewing the Nether goes into the Overworld`(@TempDir root: Path) {
        val world = World(root)
        file(world.overworld, "0_0.zip", "downloaded")
        file(world.nether, "0_0.zip", "nether")
        batch(world, "2026-10-20_14-03-11-Overworld", MapBackup.Origin(overworld, XaeroWriter.SURFACE, world.overworld, server), "0_0.zip" to "explored")
        val shown = MapBackup.Target(nether, 80, world.nether)

        val (preview, why) = MapBackup.preview(world.backups, "2026-10-20_14-03-11-Overworld", false, server, shown)
        assertNull(why)
        assertEquals(overworld, preview!!.target.dimensionId)
        MapBackup.putBack(world.backups, preview, server)

        assertEquals("explored", Files.readString(world.overworld.resolve("0_0.zip")))
        assertEquals(mapOf("0_0.zip" to "nether"), contents(world.nether))
    }

    @Test
    fun `a restore copies what it changes aside first, and undoing it puts that back`(@TempDir root: Path) {
        val world = World(root)
        file(world.overworld, "0_0.zip", "downloaded")
        file(world.overworld, "1_0.zip", "written where there was nothing")
        batch(
            world, "2026-10-20_14-03-11-Overworld", MapBackup.Origin(overworld, XaeroWriter.SURFACE, world.overworld, server),
            "0_0.zip" to "explored", "1_0.zip" to null,
        )
        val before = contents(world.overworld)

        val preview = MapBackup.preview(world.backups, "2026-10-20_14-03-11-Overworld", false, server, null).first!!
        val restored = MapBackup.putBack(world.backups, preview, server, LocalDateTime.of(2026, 10, 20, 15, 0, 0))
        assertEquals("2026-10-20_15-00-00-before-restore-Overworld", restored.before)
        assertEquals(mapOf("0_0.zip" to "explored"), contents(world.overworld))
        assertNotNull(MapBackup.Origin.read(world.backups.resolve(restored.before)))

        val undo = MapBackup.preview(world.backups, restored.before, false, server, null).first!!
        val again = MapBackup.putBack(world.backups, undo, server, LocalDateTime.of(2026, 10, 20, 15, 1, 0))
        assertEquals(before, contents(world.overworld))
        assertEquals("2026-10-20_15-01-00-before-restore-Overworld", again.before) // not before-restore twice
    }

    @Test
    fun `looking at a restore changes nothing`(@TempDir root: Path) {
        val world = World(root)
        file(world.overworld, "0_0.zip", "downloaded")
        batch(world, "2026-10-20_14-03-11-Overworld", MapBackup.Origin(overworld, XaeroWriter.SURFACE, world.overworld, server), "0_0.zip" to "explored")
        val (preview, _) = MapBackup.preview(world.backups, MapBackup.names(world.backups).first(), true, server, null)
        assertNotNull(preview)
        assertEquals(mapOf("0_0.zip" to "downloaded"), contents(world.overworld))
        assertEquals(listOf("2026-10-20_14-03-11-Overworld"), MapBackup.names(world.backups))
    }

    @Test
    fun `a copy from before 1_9_2 is only restored by name, into the map on screen`(@TempDir root: Path) {
        val world = World(root)
        file(world.nether, "0_0.zip", "nether now")
        batch(world, "2026-10-01_10-00-00-Nether", null, "0_0.zip" to "old nether")
        val shown = MapBackup.Target(nether, 80, world.nether)

        val (plain, why) = MapBackup.preview(world.backups, "2026-10-01_10-00-00-Nether", true, server, shown)
        assertNull(plain)
        assertTrue("before Sky's Map Exposer 1.9.2" in why!!, why)

        val named = MapBackup.preview(world.backups, "2026-10-01_10-00-00-Nether", false, server, shown).first!!
        assertNull(named.origin)
        assertEquals(world.nether, named.target.folder)
        assertEquals("2026-10-01 10:00", named.created)
    }

    @Test
    fun `a copy of another server, or of a folder that is gone, is refused`(@TempDir root: Path) {
        val world = World(root)
        batch(world, "a", MapBackup.Origin(overworld, XaeroWriter.SURFACE, world.overworld, "other.server"), "0_0.zip" to "x")
        batch(world, "b", MapBackup.Origin(overworld, XaeroWriter.SURFACE, root.resolve("gone"), server), "0_0.zip" to "x")
        assertTrue("another server" in MapBackup.preview(world.backups, "a", false, server, null).second!!)
        assertTrue("is gone" in MapBackup.preview(world.backups, "b", false, server, null).second!!)
        assertNull(MapBackup.preview(world.backups, "../map", false, server, null).first)
    }

    @Test
    fun `restored regions keep their file dates`(@TempDir root: Path) {
        val world = World(root)
        val date = 1_700_000_000_000L
        file(world.overworld, "0_0.zip", "downloaded")
        val batch = batch(world, "2026-10-20_14-03-11-Overworld", MapBackup.Origin(overworld, XaeroWriter.SURFACE, world.overworld, server))
        file(batch, "0_0.zip", "explored", date)
        MapBackup.putBack(world.backups, MapBackup.preview(world.backups, batch.fileName.toString(), false, server, null).first!!, server)
        assertEquals(date, Files.getLastModifiedTime(world.overworld.resolve("0_0.zip")).toMillis())
    }

    @Test
    fun `where a copy came from reads back as written`(@TempDir root: Path) {
        val origin = MapBackup.Origin(nether, 80, root.resolve("caves/80"), server, 1234L, setOf("1_2.zip"))
        origin.write(root)
        val read = MapBackup.Origin.read(root)!!
        assertEquals(listOf(nether, 80, root.resolve("caves/80").toAbsolutePath(), server, 1234L, setOf("1_2.zip")),
            listOf(read.dimensionId, read.layer, read.folder, read.server, read.created, read.unsaved))
        Files.writeString(root.resolve(MapBackup.INFO), "{ broken")
        assertNull(MapBackup.Origin.read(root))
    }

    @Test
    fun `a clear previewed for the Overworld and clicked while viewing the Nether is refused`() {
        val names = mapOf(overworld to "Overworld", nether to "Nether")
        val why = MapClear.mismatch(overworld, XaeroWriter.SURFACE, nether, 80) { names.getValue(it) }
        assertEquals("This clear is for the Overworld; switch the world map there and click again.", why)
        assertTrue("cave layer 80" in MapClear.mismatch(nether, 80, nether, 40) { names.getValue(it) }!!)
        assertNull(MapClear.mismatch(overworld, XaeroWriter.SURFACE, overworld, XaeroWriter.SURFACE) { names.getValue(it) })
    }

    @Test
    fun `an error inside a write tick stops the job instead of escaping`() {
        val said = ArrayList<String>()
        TickGuard.run("Download from BlueMap", { said.add(it) }) { error("Xaero changed") }
        assertEquals(1, said.size)
        assertTrue("Xaero changed" in said.single() && "What was written before is kept" in said.single(), said.single())
        // Even a stop that fails is not thrown on.
        TickGuard.run("Download from BlueMap", { error("again") }) { error("first") }
    }

    @Test
    fun `a region that could not be backed up is named with its reason`() {
        val skipped = SkippedRegions()
        skipped.add(0, 1, SkippedRegions.notBackedUp("disk full"))
        assertEquals("1 region skipped: 0,1 at 0, 512 (couldn't back it up: disk full)", skipped.summary())
    }

    @Test
    fun `a region Xaero never loads is named once`() {
        val skipped = SkippedRegions()
        skipped.add(-3, 5, SkippedRegions.NOT_LOADED)
        skipped.add(-3, 5, SkippedRegions.NOT_LOADED)
        assertEquals(1, skipped.size)
        assertTrue(skipped.summary().startsWith("1 region skipped: -3,5 at -1536, 2560 (Xaero didn't load it)"))
        assertTrue(skipped.summary().endsWith(SkippedRegions.ADVICE))
    }

    @Test
    fun `twelve skipped regions name five and say how many more`() {
        val skipped = SkippedRegions()
        for (i in 0 until 12) skipped.add(i, 0, SkippedRegions.notBackedUp("no space"))
        val summary = skipped.summary()
        assertTrue(summary.startsWith("12 regions skipped: 0,0 at 0, 0"), summary)
        assertTrue("4,0 at 2048, 0" in summary)
        assertFalse("5,0 at" in summary)
        assertTrue(summary.endsWith(" and 7 more"), summary)
        assertEquals(12, skipped.all().size)
    }

    @Test
    fun `only regions your render distance reaches count as right around you`() {
        val reach = 13 * 16 // 12 chunks and one more
        assertTrue(MapBackup.reaches(5, 5, 0, 0, reach), "the region you stand in")
        // Found in game: standing at 5, 5, region 1, 1 starts 507 blocks away and was counted.
        assertFalse(MapBackup.reaches(5, 5, 1, 1, reach))
        assertTrue(MapBackup.reaches(500, 5, 1, 0, reach), "12 blocks from its edge")
        assertTrue(MapBackup.reaches(-100, -100, -1, -1, reach))
        assertFalse(MapBackup.reaches(1000, 1000, -1, -1, reach))
    }
}
