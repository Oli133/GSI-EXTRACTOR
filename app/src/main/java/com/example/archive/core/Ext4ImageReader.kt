package com.example.archive.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.min

/**
 * Pure-Kotlin read-only EXT2 / EXT3 / EXT4 filesystem parser operating over any RandomAccessSource.
 * Supports 32-bit and 64-bit block group descriptors, extent trees, indirect blocks, symlinks, and htree/linear directories.
 */
class Ext4ImageReader private constructor(
  private val source: RandomAccessSource,
  override val format: ContainerFormat,
  override val volumeLabel: String,
  private val blockSize: Long,
  private val inodeSize: Int,
  private val inodesPerGroup: Long,
  private val blocksPerGroup: Long,
  private val totalInodes: Long,
  private val descSize: Int,
  private val gdtOffset: Long,
  private val featureIncompat: Long,
) : ArchiveBrowser {

  override val totalSizeBytes: Long = source.size
  override val entryCountEstimate: Int = totalInodes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

  private val dirCache = HashMap<String, List<ArchiveEntry>>()
  private val pathInodeCache = HashMap<String, Long>().apply { put("", ROOT_INODE) }

  private data class Ext4Inode(
    val inodeNum: Long,
    val mode: Int,
    val uid: Int,
    val gid: Int,
    val size: Long,
    val mtime: Long,
    val flags: Long,
    val blocksCount: Long,
    val iBlockBytes: ByteArray,
  ) {
    val fileType: EntryType
      get() =
        when (mode and S_IFMT) {
          S_IFDIR -> EntryType.DIRECTORY
          S_IFLNK -> EntryType.SYMLINK
          else -> EntryType.FILE
        }

    val usesExtents: Boolean
      get() = (flags and EXT4_EXTENTS_FL) != 0L

    val hasInlineData: Boolean
      get() = (flags and EXT4_INLINE_DATA_FL) != 0L
  }

  private data class DataExtent(
    val logicalBlock: Long,
    val physicalBlock: Long,
    val blockCount: Long,
    val isZero: Boolean = false,
  )

  override fun listDirectory(path: String): List<ArchiveEntry> {
    val normalized = normalizePath(path)
    dirCache[normalized]?.let { return it }

    val inodeNum = resolvePathToInode(normalized) ?: return emptyList()
    val dirInode = readInode(inodeNum) ?: return emptyList()
    if (dirInode.fileType != EntryType.DIRECTORY) return emptyList()

    val entries = parseDirectoryEntries(normalized, dirInode)
    val sorted =
      entries.sortedWith(
        compareBy<ArchiveEntry> { !it.isDirectory }.thenBy { it.name.lowercase() }
      )
    dirCache[normalized] = sorted
    for (e in sorted) {
      pathInodeCache[e.id] = e.internalHandle
    }
    return sorted
  }

  override fun findEntry(path: String): ArchiveEntry? {
    val normalized = normalizePath(path)
    if (normalized.isEmpty()) {
      val root = readInode(ROOT_INODE) ?: return null
      return ArchiveEntry(
        id = "",
        name = "/",
        parentPath = "",
        type = EntryType.DIRECTORY,
        sizeBytes = root.size,
        mode = root.mode and 0x0FFF,
        uid = root.uid,
        gid = root.gid,
        modifiedTimeSeconds = root.mtime,
        internalHandle = ROOT_INODE,
      )
    }
    val parent = normalized.substringBeforeLast('/', "")
    val name = normalized.substringAfterLast('/')
    return listDirectory(parent).firstOrNull { it.name == name }
  }

  override fun search(query: String, maxResults: Int): List<ArchiveEntry> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptyList()

    val results = ArrayList<ArchiveEntry>()
    val queue = ArrayDeque<String>()
    val visitedInodes = HashSet<Long>()
    queue.add("")
    visitedInodes.add(ROOT_INODE)

    while (queue.isNotEmpty() && results.size < maxResults) {
      val dirPath = queue.removeFirst()
      val children = listDirectory(dirPath)
      for (child in children) {
        if (child.name.lowercase().contains(needle) || child.id.lowercase().contains(needle)) {
          results.add(child)
          if (results.size >= maxResults) break
        }
        if (child.isDirectory && visitedInodes.add(child.internalHandle)) {
          queue.add(child.id)
        }
      }
    }
    return results
  }

  override fun readBytes(entry: ArchiveEntry, maxBytes: Int): ByteArray {
    val inode = readInode(entry.internalHandle) ?: return ByteArray(0)
    if (inode.fileType == EntryType.SYMLINK) {
      return (readSymlinkTarget(inode) ?: "").toByteArray(Charsets.UTF_8)
    }
    val toRead = min(inode.size, maxBytes.toLong()).coerceAtLeast(0L)
    val baos = ByteArrayOutputStream(toRead.toInt().coerceAtMost(1024 * 1024))
    streamInodeData(inode, baos, limitBytes = toRead)
    return baos.toByteArray()
  }

  override fun extractEntry(
    entry: ArchiveEntry,
    output: OutputStream,
    onProgress: (Long, Long) -> Unit,
  ) {
    val inode = readInode(entry.internalHandle) ?: return
    if (inode.fileType == EntryType.SYMLINK) {
      val target = (readSymlinkTarget(inode) ?: "").toByteArray(Charsets.UTF_8)
      output.write(target)
      onProgress(target.size.toLong(), target.size.toLong())
      return
    }
    streamInodeData(inode, output, limitBytes = inode.size, onProgress = onProgress)
  }

  private fun resolvePathToInode(normalizedPath: String): Long? {
    if (normalizedPath.isEmpty()) return ROOT_INODE
    pathInodeCache[normalizedPath]?.let { return it }

    val parts = normalizedPath.split('/').filter { it.isNotEmpty() }
    var currentPath = ""
    var currentInode = ROOT_INODE
    for (part in parts) {
      val nextPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
      val cached = pathInodeCache[nextPath]
      if (cached != null) {
        currentInode = cached
        currentPath = nextPath
        continue
      }
      val dirInode = readInode(currentInode) ?: return null
      if (dirInode.fileType != EntryType.DIRECTORY) return null
      val children = parseDirectoryEntries(currentPath, dirInode)
      dirCache[currentPath] =
        children.sortedWith(
          compareBy<ArchiveEntry> { !it.isDirectory }.thenBy { it.name.lowercase() }
        )
      var found: Long? = null
      for (c in children) {
        pathInodeCache[c.id] = c.internalHandle
        if (c.name == part) {
          found = c.internalHandle
        }
      }
      currentInode = found ?: return null
      currentPath = nextPath
    }
    return currentInode
  }

  private fun readInode(inodeNum: Long): Ext4Inode? {
    if (inodeNum <= 0 || inodeNum > totalInodes) return null
    val group = (inodeNum - 1L) / inodesPerGroup
    val index = (inodeNum - 1L) % inodesPerGroup

    val gdOffset = gdtOffset + group * descSize
    if (gdOffset + 32 > source.size) return null

    val inodeTableLo = source.readUInt32LE(gdOffset + 8)
    val inodeTableHi = if (descSize >= 64) source.readUInt32LE(gdOffset + 40) else 0L
    val inodeTableBlock = inodeTableLo or (inodeTableHi shl 32)
    val inodeOffset = inodeTableBlock * blockSize + index * inodeSize
    if (inodeOffset + min(128, inodeSize) > source.size) return null

    val mode = source.readUInt16LE(inodeOffset)
    val uidLo = source.readUInt16LE(inodeOffset + 2)
    val sizeLo = source.readUInt32LE(inodeOffset + 4)
    val mtime = source.readUInt32LE(inodeOffset + 16)
    val gidLo = source.readUInt16LE(inodeOffset + 24)
    val blocksLo = source.readUInt32LE(inodeOffset + 28)
    val flags = source.readUInt32LE(inodeOffset + 32)
    val iBlockBytes = source.readFully(inodeOffset + 40, 60)
    val sizeHi =
      if ((mode and S_IFMT) == S_IFREG) source.readUInt32LE(inodeOffset + 108) else 0L
    val uidHi = if (inodeSize >= 128) source.readUInt16LE(inodeOffset + 120) else 0
    val gidHi = if (inodeSize >= 128) source.readUInt16LE(inodeOffset + 122) else 0

    return Ext4Inode(
      inodeNum = inodeNum,
      mode = mode,
      uid = uidLo or (uidHi shl 16),
      gid = gidLo or (gidHi shl 16),
      size = sizeLo or (sizeHi shl 32),
      mtime = mtime,
      flags = flags,
      blocksCount = blocksLo,
      iBlockBytes = iBlockBytes,
    )
  }

  private fun parseDirectoryEntries(parentPath: String, dirInode: Ext4Inode): List<ArchiveEntry> {
    val rawBytes = readAllInodeBytes(dirInode, maxBytes = 8 * 1024 * 1024)
    val out = ArrayList<ArchiveEntry>()
    var offset = 0
    val hasFileType = (featureIncompat and INCOMPAT_FILETYPE) != 0L

    while (offset + 8 <= rawBytes.size) {
      val inode = readU32LE(rawBytes, offset)
      val recLen = readU16LE(rawBytes, offset + 4)
      if (recLen < 8 || offset + recLen > rawBytes.size) break
      val nameLen = rawBytes[offset + 6].toInt() and 0xFF
      val ft = rawBytes[offset + 7].toInt() and 0xFF

      if (inode != 0L && nameLen > 0 && ft != FT_DIR_CSUM && offset + 8 + nameLen <= rawBytes.size) {
        val name = String(rawBytes, offset + 8, nameLen, Charsets.UTF_8)
        if (name != "." && name != "..") {
          val childInode = readInode(inode)
          val type =
            when {
              childInode != null -> childInode.fileType
              hasFileType && ft == FT_DIR -> EntryType.DIRECTORY
              hasFileType && ft == FT_SYMLINK -> EntryType.SYMLINK
              else -> EntryType.FILE
            }
          val childPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
          val symlink =
            if (type == EntryType.SYMLINK && childInode != null) {
              readSymlinkTarget(childInode)
            } else {
              null
            }
          out.add(
            ArchiveEntry(
              id = childPath,
              name = name,
              parentPath = parentPath,
              type = type,
              sizeBytes = childInode?.size ?: 0L,
              mode = (childInode?.mode ?: 0) and 0x0FFF,
              uid = childInode?.uid ?: 0,
              gid = childInode?.gid ?: 0,
              modifiedTimeSeconds = childInode?.mtime ?: 0L,
              symlinkTarget = symlink,
              internalHandle = inode,
            )
          )
        }
      }
      offset += recLen
    }
    return out
  }

  private fun readSymlinkTarget(inode: Ext4Inode): String? {
    val size = inode.size.toInt().coerceIn(0, 4096)
    if (size == 0) return ""
    // Fast symlink: target stored directly in i_block[0..59] when blocksCount == 0 and size <= 60
    if (inode.blocksCount == 0L && size <= inode.iBlockBytes.size && !inode.usesExtents) {
      return String(inode.iBlockBytes, 0, size, Charsets.UTF_8)
    }
    val data = readAllInodeBytes(inode, maxBytes = size)
    return String(data, 0, min(size, data.size), Charsets.UTF_8)
  }

  private fun readAllInodeBytes(inode: Ext4Inode, maxBytes: Int): ByteArray {
    val targetSize = min(inode.size, maxBytes.toLong()).toInt().coerceAtLeast(0)
    val baos = ByteArrayOutputStream(targetSize)
    streamInodeData(inode, baos, limitBytes = targetSize.toLong())
    return baos.toByteArray()
  }

  private fun streamInodeData(
    inode: Ext4Inode,
    output: OutputStream,
    limitBytes: Long,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
  ) {
    if (limitBytes <= 0L) {
      onProgress(0L, 0L)
      return
    }
    if (inode.hasInlineData) {
      val toWrite = min(limitBytes, inode.iBlockBytes.size.toLong()).toInt()
      output.write(inode.iBlockBytes, 0, toWrite)
      onProgress(toWrite.toLong(), limitBytes)
      return
    }

    val extents = collectExtents(inode, limitBytes)
    val extentMap = HashMap<Long, DataExtent>()
    for (ext in extents) {
      for (b in 0 until ext.blockCount) {
        extentMap[ext.logicalBlock + b] =
          DataExtent(
            logicalBlock = ext.logicalBlock + b,
            physicalBlock = ext.physicalBlock + b,
            blockCount = 1L,
            isZero = ext.isZero,
          )
      }
    }

    val totalBlocks = (limitBytes + blockSize - 1L) / blockSize
    val buf = ByteArray(blockSize.toInt().coerceAtMost(65536))
    val zeroBuf = ByteArray(buf.size)
    var written = 0L

    // Walk contiguous runs of logical blocks for fast large-file streaming
    var logical = 0L
    while (logical < totalBlocks && written < limitBytes) {
      val run = findExtentForLogicalBlock(extents, logical)
      if (run != null) {
        val offsetIntoRun = logical - run.logicalBlock
        val runRemainingBlocks = run.blockCount - offsetIntoRun
        val bytesLeft = limitBytes - written
        val runBytes = min(bytesLeft, runRemainingBlocks * blockSize)
        if (run.isZero || run.physicalBlock == 0L) {
          var rem = runBytes
          while (rem > 0) {
            val step = min(rem, zeroBuf.size.toLong()).toInt()
            output.write(zeroBuf, 0, step)
            rem -= step
            written += step
          }
        } else {
          var physOffset = (run.physicalBlock + offsetIntoRun) * blockSize
          var rem = runBytes
          while (rem > 0) {
            val step = min(rem, buf.size.toLong()).toInt()
            val n = source.read(physOffset, buf, 0, step)
            if (n <= 0) {
              output.write(zeroBuf, 0, step)
            } else {
              output.write(buf, 0, n)
              if (n < step) output.write(zeroBuf, 0, step - n)
            }
            physOffset += step
            rem -= step
            written += step
          }
        }
        onProgress(written, limitBytes)
        logical += maxOf(1L, (runBytes + blockSize - 1L) / blockSize)
      } else {
        val step = min(blockSize, limitBytes - written).toInt()
        output.write(zeroBuf, 0, step)
        written += step
        onProgress(written, limitBytes)
        logical++
      }
    }
  }

  private fun findExtentForLogicalBlock(extents: List<DataExtent>, logicalBlock: Long): DataExtent? {
    var lo = 0
    var hi = extents.size - 1
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      val ext = extents[mid]
      val end = ext.logicalBlock + ext.blockCount
      if (logicalBlock < ext.logicalBlock) hi = mid - 1
      else if (logicalBlock >= end) lo = mid + 1
      else return ext
    }
    return null
  }

  private fun collectExtents(inode: Ext4Inode, limitBytes: Long): List<DataExtent> {
    if (inode.usesExtents && readU16LE(inode.iBlockBytes, 0) == EXT4_EXT_MAGIC) {
      val out = ArrayList<DataExtent>()
      parseExtentNode(inode.iBlockBytes, 0, out)
      return out.sortedBy { it.logicalBlock }
    }

    // Traditional direct + indirect block pointers (EXT2/EXT3/legacy EXT4)
    val totalBlocksNeeded = (limitBytes + blockSize - 1L) / blockSize
    val out = ArrayList<DataExtent>()
    var logical = 0L
    for (i in 0 until 12) {
      if (logical >= totalBlocksNeeded) break
      val blk = readU32LE(inode.iBlockBytes, i * 4)
      if (blk != 0L) {
        out.add(DataExtent(logical, blk, 1L))
      }
      logical++
    }
    val ptrsPerBlock = blockSize / 4L
    if (logical < totalBlocksNeeded) {
      val singleBlk = readU32LE(inode.iBlockBytes, 12 * 4)
      logical = collectIndirectBlocks(singleBlk, depth = 1, startLogical = logical, totalBlocksNeeded, ptrsPerBlock, out)
    }
    if (logical < totalBlocksNeeded) {
      val doubleBlk = readU32LE(inode.iBlockBytes, 13 * 4)
      logical = collectIndirectBlocks(doubleBlk, depth = 2, startLogical = logical, totalBlocksNeeded, ptrsPerBlock, out)
    }
    if (logical < totalBlocksNeeded) {
      val tripleBlk = readU32LE(inode.iBlockBytes, 14 * 4)
      collectIndirectBlocks(tripleBlk, depth = 3, startLogical = logical, totalBlocksNeeded, ptrsPerBlock, out)
    }
    return out
  }

  private fun collectIndirectBlocks(
    tableBlock: Long,
    depth: Int,
    startLogical: Long,
    totalBlocksNeeded: Long,
    ptrsPerBlock: Long,
    out: MutableList<DataExtent>,
  ): Long {
    var span = 1L
    repeat(depth) { span *= ptrsPerBlock }
    if (tableBlock == 0L || startLogical >= totalBlocksNeeded) {
      return startLogical + span
    }
    val bytes = source.readFully(tableBlock * blockSize, blockSize.toInt())
    var logical = startLogical
    val count = min(ptrsPerBlock.toInt(), bytes.size / 4)
    for (i in 0 until count) {
      if (logical >= totalBlocksNeeded) break
      val ptr = readU32LE(bytes, i * 4)
      if (depth == 1) {
        if (ptr != 0L) out.add(DataExtent(logical, ptr, 1L))
        logical++
      } else {
        logical = collectIndirectBlocks(ptr, depth - 1, logical, totalBlocksNeeded, ptrsPerBlock, out)
      }
    }
    return logical
  }

  private fun parseExtentNode(buf: ByteArray, offset: Int, out: MutableList<DataExtent>) {
    if (offset + 12 > buf.size) return
    val magic = readU16LE(buf, offset)
    if (magic != EXT4_EXT_MAGIC) return
    val entries = readU16LE(buf, offset + 2)
    val depth = readU16LE(buf, offset + 6)

    for (i in 0 until entries) {
      val entryOff = offset + 12 + i * 12
      if (entryOff + 12 > buf.size) break
      if (depth == 0) {
        val eeBlock = readU32LE(buf, entryOff)
        val eeLenRaw = readU16LE(buf, entryOff + 4)
        val eeStartHi = readU16LE(buf, entryOff + 6).toLong()
        val eeStartLo = readU32LE(buf, entryOff + 8)
        val isUnwritten = eeLenRaw > 32768
        val eeLen = (if (isUnwritten) eeLenRaw - 32768 else eeLenRaw).toLong()
        val phys = eeStartLo or (eeStartHi shl 32)
        if (eeLen > 0) {
          out.add(DataExtent(eeBlock, phys, eeLen, isZero = isUnwritten))
        }
      } else {
        val eiLeafLo = readU32LE(buf, entryOff + 4)
        val eiLeafHi = readU16LE(buf, entryOff + 8).toLong()
        val childBlock = eiLeafLo or (eiLeafHi shl 32)
        if (childBlock > 0) {
          val childBytes = source.readFully(childBlock * blockSize, blockSize.toInt())
          parseExtentNode(childBytes, 0, out)
        }
      }
    }
  }

  override fun close() {
    source.close()
  }

  companion object {
    private const val SUPERBLOCK_OFFSET = 1024L
    private const val EXT_MAGIC = 0xEF53
    private const val ROOT_INODE = 2L

    private const val S_IFMT = 0xF000
    private const val S_IFDIR = 0x4000
    private const val S_IFREG = 0x8000
    private const val S_IFLNK = 0xA000

    private const val EXT4_EXTENTS_FL = 0x80000L
    private const val EXT4_INLINE_DATA_FL = 0x10000000L
    private const val EXT4_EXT_MAGIC = 0xF30A

    private const val INCOMPAT_FILETYPE = 0x0002L
    private const val INCOMPAT_64BIT = 0x0080L

    private const val FT_DIR = 2
    private const val FT_SYMLINK = 7
    private const val FT_DIR_CSUM = 0xDE

    fun isExt4(source: RandomAccessSource): Boolean {
      if (source.size < SUPERBLOCK_OFFSET + 0x40) return false
      return source.readUInt16LE(SUPERBLOCK_OFFSET + 0x38) == EXT_MAGIC
    }

    fun open(
      source: RandomAccessSource,
      format: ContainerFormat = ContainerFormat.EXT4,
      fallbackLabel: String = "system.img",
    ): Ext4ImageReader {
      require(isExt4(source)) { "Invalid EXT2/3/4 superblock magic" }
      val totalInodes = source.readUInt32LE(SUPERBLOCK_OFFSET + 0x00)
      val logBlockSize = source.readUInt32LE(SUPERBLOCK_OFFSET + 0x18).toInt().coerceIn(0, 6)
      val blockSize = 1024L shl logBlockSize
      val blocksPerGroup = source.readUInt32LE(SUPERBLOCK_OFFSET + 0x20).coerceAtLeast(1L)
      val inodesPerGroup = source.readUInt32LE(SUPERBLOCK_OFFSET + 0x28).coerceAtLeast(1L)
      val revLevel = source.readUInt32LE(SUPERBLOCK_OFFSET + 0x4C)
      val inodeSize =
        if (revLevel >= 1L) source.readUInt16LE(SUPERBLOCK_OFFSET + 0x58).coerceAtLeast(128) else 128
      val featureIncompat = source.readUInt32LE(SUPERBLOCK_OFFSET + 0x60)
      val rawVolName = source.readNullTerminatedAscii(SUPERBLOCK_OFFSET + 0x78, 16)
      val rawDescSize = source.readUInt16LE(SUPERBLOCK_OFFSET + 0xFE)
      val descSize =
        if ((featureIncompat and INCOMPAT_64BIT) != 0L && rawDescSize >= 64) rawDescSize else 32
      val gdtBlock = if (blockSize == 1024L) 2L else 1L
      val label = rawVolName.ifBlank { fallbackLabel }

      return Ext4ImageReader(
        source = source,
        format = format,
        volumeLabel = label,
        blockSize = blockSize,
        inodeSize = inodeSize,
        inodesPerGroup = inodesPerGroup,
        blocksPerGroup = blocksPerGroup,
        totalInodes = totalInodes,
        descSize = descSize,
        gdtOffset = gdtBlock * blockSize,
        featureIncompat = featureIncompat,
      )
    }

    private fun normalizePath(path: String): String =
      path.trim().trim('/').replace("//", "/")

    private fun readU16LE(b: ByteArray, off: Int): Int =
      (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readU32LE(b: ByteArray, off: Int): Long =
      (b[off].toLong() and 0xFFL) or
        ((b[off + 1].toLong() and 0xFFL) shl 8) or
        ((b[off + 2].toLong() and 0xFFL) shl 16) or
        ((b[off + 3].toLong() and 0xFFL) shl 24)
  }
}
