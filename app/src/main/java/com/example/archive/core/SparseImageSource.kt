package com.example.archive.core

import kotlin.math.min

/**
 * Random-access virtual reader over an Android Sparse Image (libsparse magic 0xED26FF3A).
 * Translates virtual byte offsets directly to sparse file chunks without unsparsing to disk.
 */
class SparseImageSource private constructor(
  private val backing: RandomAccessSource,
  override val size: Long,
  private val chunks: List<ChunkEntry>,
  private val chunkStarts: LongArray,
) : RandomAccessSource {

  private enum class ChunkType {
    RAW,
    FILL,
    DONT_CARE,
  }

  private data class ChunkEntry(
    val virtualStart: Long,
    val virtualEnd: Long,
    val type: ChunkType,
    val fileDataOffset: Long = 0L,
    val fillBytes: ByteArray = ByteArray(4),
  )

  override fun read(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
    if (offset < 0 || offset >= size || length <= 0) return -1
    val maxToRead = min(length.toLong(), size - offset).toInt()
    var remaining = maxToRead
    var curVirtual = offset
    var curDst = dstOffset

    while (remaining > 0) {
      val idx = findChunkIndex(curVirtual)
      if (idx < 0 || idx >= chunks.size) {
        // Beyond mapped chunks: zero-fill
        buffer.fill(0, curDst, curDst + remaining)
        curDst += remaining
        remaining = 0
        break
      }
      val chunk = chunks[idx]
      val chunkAvail = (chunk.virtualEnd - curVirtual).toInt()
      val step = min(remaining, chunkAvail)
      val offsetInChunk = curVirtual - chunk.virtualStart

      when (chunk.type) {
        ChunkType.DONT_CARE -> {
          buffer.fill(0, curDst, curDst + step)
        }
        ChunkType.FILL -> {
          val pattern = chunk.fillBytes
          var pIdx = (offsetInChunk % 4L).toInt()
          for (i in 0 until step) {
            buffer[curDst + i] = pattern[pIdx]
            pIdx = (pIdx + 1) and 3
          }
        }
        ChunkType.RAW -> {
          val n = backing.read(chunk.fileDataOffset + offsetInChunk, buffer, curDst, step)
          if (n < step) {
            val readCount = maxOf(0, n)
            buffer.fill(0, curDst + readCount, curDst + step)
          }
        }
      }
      curVirtual += step
      curDst += step
      remaining -= step
    }
    return maxToRead
  }

  private fun findChunkIndex(virtualOffset: Long): Int {
    var lo = 0
    var hi = chunkStarts.size - 1
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      val start = chunkStarts[mid]
      val end = chunks[mid].virtualEnd
      if (virtualOffset < start) {
        hi = mid - 1
      } else if (virtualOffset >= end) {
        lo = mid + 1
      } else {
        return mid
      }
    }
    return -1
  }

  override fun close() {
    backing.close()
  }

  companion object {
    const val SPARSE_MAGIC: Long = 0xED26FF3AL
    private const val CHUNK_RAW = 0xCAC1
    private const val CHUNK_FILL = 0xCAC2
    private const val CHUNK_DONT_CARE = 0xCAC3
    private const val CHUNK_CRC32 = 0xCAC4

    fun isSparse(source: RandomAccessSource): Boolean {
      if (source.size < 28) return false
      return source.readUInt32LE(0) == SPARSE_MAGIC
    }

    fun open(source: RandomAccessSource): SparseImageSource {
      val magic = source.readUInt32LE(0)
      require(magic == SPARSE_MAGIC) { "Not an Android sparse image (magic=0x${magic.toString(16)})" }
      val fileHdrSize = source.readUInt16LE(8).toLong().coerceAtLeast(28L)
      val chunkHdrSize = source.readUInt16LE(10).toLong().coerceAtLeast(12L)
      val blkSize = source.readUInt32LE(12).coerceAtLeast(512L)
      val totalBlks = source.readUInt32LE(16)
      val totalChunks = source.readUInt32LE(20).toInt()
      val totalVirtualSize = totalBlks * blkSize

      val chunks = ArrayList<ChunkEntry>(min(totalChunks, 65536))
      var fileOffset = fileHdrSize
      var virtualOffset = 0L

      for (i in 0 until totalChunks) {
        if (fileOffset + chunkHdrSize > source.size) break
        val chunkType = source.readUInt16LE(fileOffset)
        val chunkBlks = source.readUInt32LE(fileOffset + 4)
        val totalSz = source.readUInt32LE(fileOffset + 8)
        val dataOffset = fileOffset + chunkHdrSize
        val virtualBytes = chunkBlks * blkSize

        when (chunkType) {
          CHUNK_RAW -> {
            if (virtualBytes > 0) {
              chunks.add(
                ChunkEntry(
                  virtualStart = virtualOffset,
                  virtualEnd = virtualOffset + virtualBytes,
                  type = ChunkType.RAW,
                  fileDataOffset = dataOffset,
                )
              )
            }
          }
          CHUNK_FILL -> {
            val fill = source.readFully(dataOffset, 4)
            if (virtualBytes > 0) {
              chunks.add(
                ChunkEntry(
                  virtualStart = virtualOffset,
                  virtualEnd = virtualOffset + virtualBytes,
                  type = ChunkType.FILL,
                  fillBytes = if (fill.size == 4) fill else ByteArray(4),
                )
              )
            }
          }
          CHUNK_DONT_CARE -> {
            if (virtualBytes > 0) {
              chunks.add(
                ChunkEntry(
                  virtualStart = virtualOffset,
                  virtualEnd = virtualOffset + virtualBytes,
                  type = ChunkType.DONT_CARE,
                )
              )
            }
          }
          CHUNK_CRC32 -> {
            // Skip CRC metadata chunk
          }
        }
        virtualOffset += virtualBytes
        fileOffset += maxOf(chunkHdrSize, totalSz)
      }

      val starts = LongArray(chunks.size) { chunks[it].virtualStart }
      val finalSize = maxOf(totalVirtualSize, virtualOffset)
      return SparseImageSource(source, finalSize, chunks, starts)
    }
  }
}
