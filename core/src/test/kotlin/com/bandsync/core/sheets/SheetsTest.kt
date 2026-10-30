package com.bandsync.core.sheets

import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageRangesTest {
    @Test
    fun `parses ranges and single pages to zero-based indexes`() {
        assertEquals(listOf(0, 1, 2, 4), PageRanges.parse("1-3, 5", 10))
    }

    @Test
    fun `blank means every page`() {
        assertEquals(emptyList(), PageRanges.parse("  ", 10))
    }

    @Test
    fun `keeps the order that was typed`() {
        assertEquals(listOf(4, 0), PageRanges.parse("5,1", 10))
    }

    @Test
    fun `rejects anything that isn't a real page`() {
        listOf("0", "11", "3-1", "1-2-3", "a", "1,,2", "-").forEach {
            assertNull(PageRanges.parse(it, 10), "\"$it\" should be invalid")
        }
    }

    @Test
    fun `formats back to the same text`() {
        assertEquals("1-3, 5", PageRanges.format(listOf(0, 1, 2, 4)))
        assertEquals("", PageRanges.format(emptyList()))
    }
}

class RoleSheetMapTest {
    private val guitar = SheetFile("g", "guitar.pdf", 100, pageCount = 3)
    private val shared = SheetFile("s", "chords.pdf", 200, pageCount = 2)
    private val map = RoleSheetMap()
        .withFile(guitar)
        .withFile(shared)
        .assign(Role.GUITAR, SheetSegment("g", listOf(2, 0)))
        .assign(Role.GUITAR, SheetSegment("s"))
        .assign(Role.BASS, SheetSegment("s", listOf(1)))

    @Test
    fun `a manifest carries only that role's files`() {
        assertEquals(listOf(shared), map.manifestFor(Role.BASS, revision = 3).files)
        assertEquals(emptyList(), map.manifestFor(Role.DRUMS, revision = 3).files)
    }

    @Test
    fun `pages flatten across segments in order, empty meaning all`() {
        assertEquals(
            listOf(SheetPage("g", 2), SheetPage("g", 0), SheetPage("s", 0), SheetPage("s", 1)),
            map.manifestFor(Role.GUITAR, revision = 3).pages(),
        )
    }

    @Test
    fun `a file used twice is listed once`() {
        val twice = map.assign(Role.BASS, SheetSegment("s", listOf(0)))
        assertEquals(listOf(shared), twice.manifestFor(Role.BASS, revision = 3).files)
    }

    @Test
    fun `assignOnce only adds a file the role doesn't already use`() {
        val again = map.assignOnce(Role.BASS, SheetSegment("s"))
        assertEquals(map.segmentsFor(Role.BASS), again.segmentsFor(Role.BASS))

        val added = map.assignOnce(Role.DRUMS, SheetSegment("s"))
        assertEquals(listOf(SheetSegment("s")), added.segmentsFor(Role.DRUMS))
    }

    @Test
    fun `removing a file removes its segments everywhere`() {
        val without = map.withoutFile("s")
        assertEquals(listOf(SheetSegment("g", listOf(2, 0))), without.segmentsFor(Role.GUITAR))
        assertEquals(emptyList(), without.segmentsFor(Role.BASS))
    }

    @Test
    fun `segments can be edited and removed by position`() {
        val edited = map.setPages(Role.GUITAR, 1, listOf(1)).unassign(Role.GUITAR, 0)
        assertEquals(listOf(SheetSegment("s", listOf(1))), edited.segmentsFor(Role.GUITAR))
    }
}

class SheetStoreTest {
    private val store = SheetStore(Files.createTempDirectory("sheets").toFile())
    private val pdf = "%PDF-1.7 pretend".encodeToByteArray()

    @Test
    fun `import names the file after its content`() {
        val first = store.import(ByteArrayInputStream(pdf))
        val second = store.import(ByteArrayInputStream(pdf))

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(store.contains(first))
        assertTrue(store.fileFor(first).readBytes().contentEquals(pdf))
    }

    @Test
    fun `adopt accepts bytes matching the expected hash`() {
        val id = SheetStore(Files.createTempDirectory("other").toFile()).import(ByteArrayInputStream(pdf))

        assertTrue(store.adopt(ByteArrayInputStream(pdf), id))
        assertTrue(store.contains(id))
    }

    @Test
    fun `adopt throws away bytes that don't match`() {
        val id = store.import(ByteArrayInputStream(pdf))
        store.fileFor(id).delete()

        assertFalse(store.adopt(ByteArrayInputStream("tampered".encodeToByteArray()), id))
        assertFalse(store.contains(id))
    }
}
