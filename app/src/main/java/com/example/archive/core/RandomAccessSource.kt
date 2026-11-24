package com.example.archive.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.min

interface RandomAccessSource : Closeable {
  val size: Long

  fun read(offset: Long, buffer: ByteArray, dstOffset: Int = 0, length: Int = buffer.size - dstOffset): Int

  fun readFully(offset: Long, length: Int): ByteArray {
    if (length <= 0) return ByteArray(0)
    val safeLen = min(length.toLong(), maxOf(0L, size - offset)).toInt()
    if (safeLen <= 0) return ByteArray(0)
    val out = ByteArray(safeLen)
    var pos = 0
    while (pos < safeLen) {
      val n = read(offset + pos, out, pos, safeLen - pos)
      if (n <= 0) break
      pos += n
    }
    return if (pos == safeLen) out else out.copyOf(pos)
  }

  fun readUInt8(offset: Long): Int {
    val buf = ByteArray(1)
    return if (read(offset, buf, 0, 1) == 1) buf[0].toInt() and 0xFF else 0
  }

  fun readUInt16LE(offset: Long): Int {
    val b = readFully(offset, 2)
    if (b.size < 2) return 0
    return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
  }

  fun readUInt32LE(offset: Long): Long {
    val b = readFully(offset, 4)
    if (b.size < 4) return 0L
    return (b[0].toLong() and 0xFFL) or
      ((b[1].toLong() and 0xFFL) shl 8) or
      ((b[2].toLong() and 0xFFL) shl 16) or
      ((b[3].toLong() and 0xFFL) shl 24)
  }

  fun readInt32LE(offset: Long): Int {
    val b = readFully(offset, 4)
    if (b.size < 4) return 0
    return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
  }

  fun readUInt64LE(offset: Long): Long {
    val b = readFully(offset, 8)
    if (b.size < 8) return 0L
    return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
  }

  fun readNullTerminatedAscii(offset: Long, maxLength: Int): String {
    val bytes = readFully(offset, maxLength)
    var end = 0
    while (end < bytes.size && bytes[end] != 0.toByte()) {
      end++
    }
    return String(bytes, 0, end, Charsets.UTF_8)
  }
}

class FileRandomAccessSource(private val file: File) : RandomAccessSource {
  private val raf = RandomAccessFile(file, "r")
  private val channel: FileChannel = raf.channel
  override val size: Long = file.length()

  override fun read(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
    if (offset < 0 || offset >= size || length <= 0) return -1
    val toRead = min(length.toLong(), size - offset).toInt()
    val bb = ByteBuffer.wrap(buffer, dstOffset, toRead)
    var totalRead = 0
    while (totalRead < toRead) {
      val n = channel.read(bb, offset + totalRead)
      if (n <= 0) break
      totalRead += n
    }
    return if (totalRead == 0) -1 else totalRead
  }

  override fun close() {
    raf.close()
  }
}

class ByteArrayRandomAccessSource(private val data: ByteArray) : RandomAccessSource {
  override val size: Long = data.size.toLong()

  override fun read(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
    if (offset < 0 || offset >= size || length <= 0) return -1
    val toRead = min(length.toLong(), size - offset).toInt()
    System.arraycopy(data, offset.toInt(), buffer, dstOffset, toRead)
    return toRead
  }

  override fun close() = Unit
}

class SubRangeRandomAccessSource(
  private val parent: RandomAccessSource,
  private val baseOffset: Long,
  override val size: Long,
) : RandomAccessSource {
  override fun read(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
    if (offset < 0 || offset >= size || length <= 0) return -1
    val toRead = min(length.toLong(), size - offset).toInt()
    return parent.read(baseOffset + offset, buffer, dstOffset, toRead)
  }

  override fun close() = Unit
}
