package com.bandsync.core.sheets

import kotlinx.serialization.Serializable

/** SHA-256 of the PDF's bytes. Identifying files by content means nothing is ever sent twice. */
typealias FileId = String

@Serializable
enum class Role(val label: String) {
    GUITAR("Guitar"),
    BASS("Bass"),
    DRUMS("Drums"),
    VOCALS("Vocals"),
    KEYS("Keys"),
    OTHER("Other"),
}

@Serializable
data class SheetFile(val id: FileId, val name: String, val sizeBytes: Long, val pageCount: Int)

/** Pages of one PDF in display order, zero-based. Empty means the whole file. */
@Serializable
data class SheetSegment(val fileId: FileId, val pages: List<Int> = emptyList())

data class SheetPage(val fileId: FileId, val pageIndex: Int)

/** Everything one role needs: the master resolves this per role, then sends it to each phone in that role. */
@Serializable
data class SheetManifest(
    val revision: Int,
    val role: Role,
    val files: List<SheetFile>,
    val segments: List<SheetSegment>,
) {
    fun pages(): List<SheetPage> {
        val pageCounts = files.associate { it.id to it.pageCount }
        return segments.flatMap { segment ->
            val indexes = segment.pages.ifEmpty { (0 until (pageCounts[segment.fileId] ?: 0)).toList() }
            indexes.map { SheetPage(segment.fileId, it) }
        }
    }
}

/** The master's plan. Keyed by role, not device, so two guitarists simply get the same sheets. */
data class RoleSheetMap(
    val files: Map<FileId, SheetFile> = emptyMap(),
    val assignments: Map<Role, List<SheetSegment>> = emptyMap(),
) {
    fun withFile(file: SheetFile): RoleSheetMap = copy(files = files + (file.id to file))

    fun withoutFile(id: FileId): RoleSheetMap = copy(
        files = files - id,
        assignments = assignments.mapValues { (_, segments) -> segments.filter { it.fileId != id } },
    )

    fun assign(role: Role, segment: SheetSegment): RoleSheetMap =
        copy(assignments = assignments + (role to (segmentsFor(role) + segment)))

    fun unassign(role: Role, index: Int): RoleSheetMap =
        copy(assignments = assignments + (role to segmentsFor(role).filterIndexed { i, _ -> i != index }))

    fun setPages(role: Role, index: Int, pages: List<Int>): RoleSheetMap = copy(
        assignments = assignments + (
            role to segmentsFor(role).mapIndexed { i, segment ->
                if (i == index) segment.copy(pages = pages) else segment
            }
        ),
    )

    fun segmentsFor(role: Role): List<SheetSegment> = assignments[role].orEmpty()

    fun manifestFor(role: Role, revision: Int): SheetManifest {
        val segments = segmentsFor(role)
        return SheetManifest(
            revision = revision,
            role = role,
            files = segments.map { it.fileId }.distinct().mapNotNull(files::get),
            segments = segments,
        )
    }
}
