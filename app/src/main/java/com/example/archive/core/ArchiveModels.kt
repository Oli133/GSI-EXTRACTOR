package com.example.archive.core

import java.io.Closeable
import java.io.OutputStream

enum class ContainerFormat(val label: String, val badge: String) {
  ZIP("ZIP Archive", "ZIP"),
  APK("Android Package", "APK"),
  EXT4("EXT4 Filesystem", "EXT4"),
  EROFS("EROFS Filesystem", "EROFS"),
  SUPER_IMG("Dynamic Super Image", "SUPER"),
  SPARSE_EXT4("Sparse EXT4 Image", "SPARSE · EXT4"),
  SPARSE_EROFS("Sparse EROFS Image", "SPARSE · EROFS"),
  SPARSE_SUPER("Sparse Super Image", "SPARSE · SUPER"),
  UNKNOWN("Unknown Binary", "RAW"),
}

enum class EntryType {
  DIRECTORY,
  FILE,
  SYMLINK,
}

data class ArchiveEntry(
  val id: String,
  val name: String,
  val parentPath: String,
  val type: EntryType,
  val sizeBytes: Long,
  val compressedSizeBytes: Long? = null,
  val mode: Int = 0,
  val uid: Int = 0,
  val gid: Int = 0,
  val modifiedTimeSeconds: Long = 0L,
  val symlinkTarget: String? = null,
  val internalHandle: Long = 0L,
) {
  val isDirectory: Boolean
    get() = type == EntryType.DIRECTORY

  val extension: String
    get() = name.substringAfterLast('.', "").lowercase()

  val isNestedImage: Boolean
    get() =
      !isDirectory &&
        (name.endsWith(".img", ignoreCase = true) ||
          name.endsWith(".img.xz", ignoreCase = true) ||
          name.endsWith(".img.gz", ignoreCase = true) ||
          name.endsWith(".zip", ignoreCase = true))

  fun permissionsString(): String {
    if (mode == 0) {
      return when (type) {
        EntryType.DIRECTORY -> "drwxr-xr-x"
        EntryType.SYMLINK -> "lrwxrwxrwx"
        EntryType.FILE -> "-rw-r--r--"
      }
    }
    val prefix =
      when (type) {
        EntryType.DIRECTORY -> 'd'
        EntryType.SYMLINK -> 'l'
        EntryType.FILE -> '-'
      }
    val flags =
      charArrayOf(
        if ((mode and 0x100) != 0) 'r' else '-',
        if ((mode and 0x80) != 0) 'w' else '-',
        if ((mode and 0x40) != 0) 'x' else '-',
        if ((mode and 0x20) != 0) 'r' else '-',
        if ((mode and 0x10) != 0) 'w' else '-',
        if ((mode and 0x08) != 0) 'x' else '-',
        if ((mode and 0x04) != 0) 'r' else '-',
        if ((mode and 0x02) != 0) 'w' else '-',
        if ((mode and 0x01) != 0) 'x' else '-',
      )
    return "$prefix${String(flags)}"
  }
}

data class PartitionExtent(
  val virtualOffsetBytes: Long,
  val physicalOffsetBytes: Long,
  val lengthBytes: Long,
  val isZero: Boolean = false,
)

data class LogicalPartitionInfo(
  val name: String,
  val sizeBytes: Long,
  val attributes: Int,
  val extents: List<PartitionExtent>,
)

data class GsiTelemetry(
  val androidVersion: String? = null,
  val sdkVersion: String? = null,
  val securityPatch: String? = null,
  val buildId: String? = null,
  val abis: String? = null,
  val manufacturer: String? = null,
  val model: String? = null,
  val trebleEnabled: String? = null,
  val vndkVersion: String? = null,
  val systemAsRoot: Boolean = false,
  val rawBuildProp: String = "",
)

data class RecentArchiveRecord(
  val uriString: String,
  val displayName: String,
  val formatLabel: String,
  val sizeBytes: Long,
  val openedAtMillis: Long,
)

interface ArchiveBrowser : Closeable {
  val format: ContainerFormat
  val volumeLabel: String
  val totalSizeBytes: Long
  val entryCountEstimate: Int

  fun listDirectory(path: String): List<ArchiveEntry>

  fun findEntry(path: String): ArchiveEntry?

  fun search(query: String, maxResults: Int = 250): List<ArchiveEntry>

  fun readBytes(entry: ArchiveEntry, maxBytes: Int = Int.MAX_VALUE): ByteArray

  fun extractEntry(
    entry: ArchiveEntry,
    output: OutputStream,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
  )
}
