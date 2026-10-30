package com.bandsync.core.sheets

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Content-addressed PDF storage. The file's name is its hash, so a file in here is known to be intact. */
class SheetStore(private val dir: File) {
    init {
        dir.mkdirs()
    }

    fun fileFor(id: FileId): File = File(dir, "$id.pdf")

    fun contains(id: FileId): Boolean = fileFor(id).exists()

    /** Copies a PDF in and returns its id. */
    fun import(input: InputStream): FileId {
        val temp = File.createTempFile("incoming", ".tmp", dir)
        try {
            val id = copyHashing(input, temp)
            moveIntoPlace(temp, id)
            return id
        } finally {
            temp.delete()
        }
    }

    /** Stores a received file if its bytes hash to [expected]. A corrupt transfer is thrown away. */
    fun adopt(input: InputStream, expected: FileId): Boolean {
        val temp = File.createTempFile("incoming", ".tmp", dir)
        try {
            if (copyHashing(input, temp) != expected) return false
            moveIntoPlace(temp, expected)
            return true
        } finally {
            temp.delete()
        }
    }

    private fun copyHashing(input: InputStream, target: File): FileId {
        val digest = MessageDigest.getInstance("SHA-256")
        target.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun moveIntoPlace(temp: File, id: FileId) {
        val target = fileFor(id)
        if (!target.exists()) check(temp.renameTo(target)) { "Could not store $id" }
    }
}
