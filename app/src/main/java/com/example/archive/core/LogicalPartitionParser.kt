package com.example.archive.core

import kotlin.math.min

/**
 * Parses Android liblp dynamic partition metadata (super.img) and exposes individual
 * logical partitions (system_a, product_a, vendor_a, etc.) as RandomAccessSource views.
 */
object LogicalPartitionParser {
  private const val LP_METADATA_GEOMETRY_MAGIC = 0x616C4467L
  private const val LP_METADATA_HEADER_MAGIC = 0x414C5030L
  private const val LP_TARGET_TYPE_LINEAR = 0
  private const val LP_TARGET_TYPE_ZERO = 1
  private const val SECTOR_SIZE = 512L

  fun isSuperImage(source: RandomAccessSource): Boolean {
    return findGeometryOffset(source) >= 0L
  }

  private fun findGeometryOffset(source: RandomAccessSource): Long {
    val candidates = longArrayOf(4096L, 0L, 8192L)
    for (offset in candidates) {
      if (offset + 52 <= source.size && source.readUInt32LE(offset) == LP_METADATA_GEOMETRY_MAGIC) {
        return offset
      }
    }
    return -1L
  }

  fun parsePartitions(source: RandomAccessSource): List<LogicalPartitionInfo> {
    val geoOffset = findGeometryOffset(source)
    if (geoOffset < 0L) return emptyList()

    val headerCandidates = longArrayOf(geoOffset + 8192L, geoOffset + 4096L, 12288L, 8192L)
    var headerOffset = -1L
    for (cand in headerCandidates) {
      if (cand + 128 <= source.size && source.readUInt32LE(cand) == LP_METADATA_HEADER_MAGIC) {
        headerOffset = cand
        break
      }
    }
    if (headerOffset < 0L) return emptyList()

    val headerSize = source.readUInt32LE(headerOffset + 8)
    val tablesBase = headerOffset + headerSize

    // Table descriptors start at headerOffset + 80:
    // partitions (80..91), extents (92..103), groups (104..115), block_devices (116..127)
    val partTblOffset = tablesBase + source.readUInt32LE(headerOffset + 80)
    val partCount = source.readUInt32LE(headerOffset + 84).toInt()
    val partEntrySize = source.readUInt32LE(headerOffset + 88).coerceAtLeast(52L)

    val extTblOffset = tablesBase + source.readUInt32LE(headerOffset + 92)
    val extCount = source.readUInt32LE(headerOffset + 96).toInt()
    val extEntrySize = source.readUInt32LE(headerOffset + 100).coerceAtLeast(24L)

    val rawExtents = ArrayList<RawExtent>(min(extCount, 4096))
    for (i in 0 until extCount) {
      val off = extTblOffset + i * extEntrySize
      if (off + 24 > source.size) break
      val numSectors = source.readUInt64LE(off)
      val targetType = source.readUInt32LE(off + 8).toInt()
      val targetData = source.readUInt64LE(off + 12)
      rawExtents.add(RawExtent(numSectors, targetType, targetData))
    }

    val partitions = ArrayList<LogicalPartitionInfo>(min(partCount, 256))
    for (i in 0 until partCount) {
      val off = partTblOffset + i * partEntrySize
      if (off + 52 > source.size) break
      val name = source.readNullTerminatedAscii(off, 36)
      val attrs = source.readUInt32LE(off + 36).toInt()
      val firstExt = source.readUInt32LE(off + 40).toInt()
      val numExt = source.readUInt32LE(off + 44).toInt()

      val mappedExtents = ArrayList<PartitionExtent>(numExt)
      var virtPos = 0L
      for (eIdx in firstExt until min(firstExt + numExt, rawExtents.size)) {
        val ext = rawExtents[eIdx]
        val lenBytes = ext.numSectors * SECTOR_SIZE
        mappedExtents.add(
          PartitionExtent(
            virtualOffsetBytes = virtPos,
            physicalOffsetBytes = ext.targetData * SECTOR_SIZE,
            lengthBytes = lenBytes,
            isZero = (ext.targetType == LP_TARGET_TYPE_ZERO) || (ext.targetType != LP_TARGET_TYPE_LINEAR),
          )
        )
        virtPos += lenBytes
      }
      if (name.isNotBlank() && virtPos > 0L) {
        partitions.add(
          LogicalPartitionInfo(
            name = name,
            sizeBytes = virtPos,
            attributes = attrs,
            extents = mappedExtents,
          )
        )
      }
    }
    return partitions
  }

  private data class RawExtent(
    val numSectors: Long,
    val targetType: Int,
    val targetData: Long,
  )
}

class ExtentRandomAccessSource(
  private val parent: RandomAccessSource,
  private val partition: LogicalPartitionInfo,
) : RandomAccessSource {
  override val size: Long = partition.sizeBytes
  private val extentStarts = LongArray(partition.extents.size) { partition.extents[it].virtualOffsetBytes }

  override fun read(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
    if (offset < 0 || offset >= size || length <= 0) return -1
    val maxToRead = min(length.toLong(), size - offset).toInt()
    var remaining = maxToRead
    var curVirt = offset
    var curDst = dstOffset

    while (remaining > 0) {
      val idx = findExtent(curVirt)
      if (idx < 0) {
        buffer.fill(0, curDst, curDst + remaining)
        break
      }
      val ext = partition.extents[idx]
      val extEnd = ext.virtualOffsetBytes + ext.lengthBytes
      val step = min(remaining, (extEnd - curVirt).toInt())
      val offInExt = curVirt - ext.virtualOffsetBytes

      if (ext.isZero) {
        buffer.fill(0, curDst, curDst + step)
      } else {
        val n = parent.read(ext.physicalOffsetBytes + offInExt, buffer, curDst, step)
        if (n < step) {
          buffer.fill(0, curDst + maxOf(0, n), curDst + step)
        }
      }
      curVirt += step
      curDst += step
      remaining -= step
    }
    return maxToRead
  }

  private fun findExtent(virt: Long): Int {
    var lo = 0
    var hi = extentStarts.size - 1
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      val ext = partition.extents[mid]
      val start = ext.virtualOffsetBytes
      val end = start + ext.lengthBytes
      if (virt < start) hi = mid - 1
      else if (virt >= end) lo = mid + 1
      else return mid
    }
    return -1
  }

  override fun close() = Unit
}
