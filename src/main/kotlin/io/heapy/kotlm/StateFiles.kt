package io.heapy.kotlm

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.writeText

/** Replaces a state file atomically when the filesystem supports it. */
fun replaceStateFile(
    file: Path,
    contents: String,
    prepareTemporary: (Path) -> Unit = {},
) {
    file.parent?.let(Files::createDirectories)
    val temporary = file.resolveSibling("${file.fileName}.tmp")
    temporary.writeText(contents)
    prepareTemporary(temporary)

    try {
        Files.move(
            temporary,
            file,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
    }
}
