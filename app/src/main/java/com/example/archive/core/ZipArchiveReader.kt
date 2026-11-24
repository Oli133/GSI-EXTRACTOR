package com.example.archive.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.min

/**
 * Random-access ZIP / APK / APEX browser backed by java.util.zip.ZipFile.
 * Indexes all entries into an in-memory folder tree for fast navigation and selective extraction.
 */
class ZipArchiveReader private constructor(
  private val zipFile: ZipFile,
  private val backingFile: File,
  override val format: ContainerFormat,
  override val volumeLabel: String,
  private val directoryChildren: Map<String, List<ArchiveEntry>>,
  private val entryByPath: Map<String, ArchiveEntry>,
) : ArchiveBrowser {

  override val totalSizeBytes: Long = backingFile.length()
  override val entryCountEstimate: Int = entryByPath.size

  override fun listDirectory(path: String): List<ArchiveEntry> {
    val normalized = path.trim().trim('/')
    return directoryChildren[normalized].orEmpty()
  }

  override fun findEntry(path: String): ArchiveEntry? {
    val normalized = path.trim().trim('/')
    if (normalized.isEmpty()) {
      return ArchiveEntry(
        id = "",
        name = "/",
        parentPath = "",
        type = EntryType.DIRECTORY,
        sizeBytes = totalSizeBytes,
      )
    }
    return entryByPath[normalized]
  }

  override fun search(query: String, maxResults: Int): List<ArchiveEntry> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptyList()
    return entryByPath.values
      .asSequence()
      .filter { it.name.lowercase().contains(needle) || it.id.lowercase().contains(needle) }
      .take(maxResults)
      .sortedWith(compareBy<ArchiveEntry> { !it.isDirectory }.thenBy { it.id.lowercase() })
      .toList()
  }

  override fun readBytes(entry: ArchiveEntry, maxBytes: Int): ByteArray {
    if (entry.isDirectory) return ByteArray(0)
    val ze: ZipEntry = zipFile.getEntry(entry.id) ?: return ByteArray(0)
    val baos = ByteArrayOutputStream(min(maxBytes, 262144))
    zipFile.getInputStream(ze).use { input ->
      val buf = ByteArray(16384)
      var remaining = maxBytes
      while (remaining > 0) {
        val n = input.read(buf, 0, min(buf.size, remaining))
        if (n <= 0) break
        baos.write(buf, 0, n)
        remaining -= n
      }
    }
    return baos.toByteArray()
  }

  override fun extractEntry(
    entry: ArchiveEntry,
    output: OutputStream,
    onProgress: (Long, Long) -> Unit,
  ) {
    if (entry.isDirectory) return
    val ze: ZipEntry = zipFile.getEntry(entry.id) ?: return
    val total = if (ze.size >= 0L) ze.size else entry.sizeBytes
    zipFile.getInputStream(ze).use { input ->
      val buf = ByteArray(65536)
      var copied = 0L
      while (true) {
        val n = input.read(buf)
        if (n <= 0) break
        output.write(buf, 0, n)
        copied += n
        onProgress(copied, maxOf(total, copied))
      }
    }
  }

  override fun close() {
    zipFile.close()
  }

  companion object {
    fun open(file: File, displayName: String): ZipArchiveReader {
      val zip = ZipFile(file)
      val childrenMap = HashMap<String, MutableList<ArchiveEntry>>()
      val entryMap = HashMap<String, ArchiveEntry>()
      val seenDirs = HashSet<String>()

      fun ensureDirectory(dirPath: String) {
        if (dirPath.isEmpty() || !seenDirs.add(dirPath)) return
        val parent = dirPath.substringBeforeLast('/', "")
        val name = dirPath.substringAfterLast('/')
        ensureDirectory(parent)
        val dirEntry =
          ArchiveEntry(
            id = dirPath,
            name = name,
            parentPath = parent,
            type = EntryType.DIRECTORY,
            sizeBytes = 0L,
          )
        entryMap[dirPath] = dirEntry
        childrenMap.getOrPut(parent) { ArrayList() }.add(dirEntry)
      }

      val enumeration = zip.entries()
      while (enumeration.hasMoreElements()) {
        val ze = enumeration.nextElement()
        val rawName = ze.name.trim('/').replace("//", "/")
        if (rawName.isEmpty()) continue
        if (ze.isDirectory) {
          ensureDirectory(rawName)
        } else {
          val parent = rawName.substringBeforeLast('/', "")
          val name = rawName.substringAfterLast('/')
          ensureDirectory(parent)
          val fileEntry =
            ArchiveEntry(
              id = rawName,
              name = name,
              parentPath = parent,
              type = EntryType.FILE,
              sizeBytes = maxOf(0L, ze.size),
              compressedSizeBytes = if (ze.compressedSize >= 0L) ze.compressedSize else null,
              modifiedTimeSeconds = ze.time / 1000L,
            )
          entryMap[rawName] = fileEntry
          childrenMap.getOrPut(parent) { ArrayList() }.add(fileEntry)
        }
      }

      val sortedMap =
        childrenMap.mapValues { (_, list) ->
          list.sortedWith(
            compareBy<ArchiveEntry> { !it.isDirectory }.thenBy { it.name.lowercase() }
          )
        }

      val format =
        if (displayName.endsWith(".apk", ignoreCase = true) || entryMap.containsKey("AndroidManifest.xml")) {
          ContainerFormat.APK
        } else {
          ContainerFormat.ZIP
        }

      return ZipArchiveReader(
        zipFile = zip,
        backingFile = file,
        format = format,
        volumeLabel = displayName,
        directoryChildren = sortedMap,
        entryByPath = entryMap,
      )
    }
  }
}
