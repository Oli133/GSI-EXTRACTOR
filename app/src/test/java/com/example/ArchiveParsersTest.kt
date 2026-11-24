package com.example

import com.example.archive.core.ByteArrayRandomAccessSource
import com.example.archive.core.ContainerFormat
import com.example.archive.core.EntryType
import com.example.archive.core.ErofsImageReader
import com.example.archive.core.Ext4ImageReader
import com.example.archive.core.SparseImageSource
import com.example.archive.core.ZipArchiveReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveParsersTest {

  @Test
  fun `sparse image translates RAW FILL and DONT_CARE chunks accurately`() {
    val blkSize = 4096
    val totalBlks = 3
    val rawPayload = ByteArray(blkSize) { (it % 251).toByte() }

    val out = ByteArrayOutputStream()
    fun putU16(v: Int) {
      out.write(v and 0xFF)
      out.write((v ushr 8) and 0xFF)
    }
    fun putU32(v: Long) {
      out.write((v and 0xFF).toInt())
      out.write(((v ushr 8) and 0xFF).toInt())
      out.write(((v ushr 16) and 0xFF).toInt())
      out.write(((v ushr 24) and 0xFF).toInt())
    }

    // Sparse Header (28 bytes)
    putU32(0xED26FF3AL)
    putU16(1) // major
    putU16(0) // minor
    putU16(28) // file_hdr_sz
    putU16(12) // chunk_hdr_sz
    putU32(blkSize.toLong())
    putU32(totalBlks.toLong())
    putU32(3L) // total_chunks
    putU32(0L)

    // Chunk 0: RAW (1 blk)
    putU16(0xCAC1); putU16(0); putU32(1L); putU32((12 + blkSize).toLong())
    out.write(rawPayload)

    // Chunk 1: DONT_CARE (1 blk)
    putU16(0xCAC3); putU16(0); putU32(1L); putU32(12L)

    // Chunk 2: FILL 0x11223344 (1 blk)
    putU16(0xCAC2); putU16(0); putU32(1L); putU32(16L)
    out.write(byteArrayOf(0x44, 0x33, 0x22, 0x11))

    val sparseSrc = SparseImageSource.open(ByteArrayRandomAccessSource(out.toByteArray()))
    assertEquals((totalBlks * blkSize).toLong(), sparseSrc.size)

    val readBlk0 = sparseSrc.readFully(0, blkSize)
    assertArrayEquals(rawPayload, readBlk0)

    val readBlk1 = sparseSrc.readFully(blkSize.toLong(), 16)
    assertArrayEquals(ByteArray(16), readBlk1)

    val readBlk2 = sparseSrc.readFully((blkSize * 2).toLong(), 8)
    assertArrayEquals(byteArrayOf(0x44, 0x33, 0x22, 0x11, 0x44, 0x33, 0x22, 0x11), readBlk2)
  }

  @Test
  fun `ext4 reader parses directory entries and extracts file content`() {
    // Minimal 4096-byte-block EXT4 image in memory (32 KiB)
    val blockSize = 4096
    val img = ByteArray(blockSize * 8)
    val bb = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN)

    // Superblock at 1024
    val sb = 1024
    bb.putInt(sb + 0x00, 16) // s_inodes_count
    bb.putInt(sb + 0x04, 8) // s_blocks_count_lo
    bb.putInt(sb + 0x18, 2) // s_log_block_size -> 1024 << 2 = 4096
    bb.putInt(sb + 0x20, 8) // s_blocks_per_group
    bb.putInt(sb + 0x28, 16) // s_inodes_per_group
    bb.putShort(sb + 0x38, 0xEF53.toShort()) // s_magic
    bb.putInt(sb + 0x4C, 1) // s_rev_level
    bb.putShort(sb + 0x58, 128) // s_inode_size
    bb.putInt(sb + 0x60, 0x0042) // INCOMPAT_FILETYPE | INCOMPAT_EXTENTS

    // Block Group Descriptor Table at block 1 (4096) -> inode table at block 2 (8192)
    bb.putInt(blockSize + 8, 2)

    // Inode 2 (root dir) at block 2 + 1 * 128
    val rootOff = blockSize * 2 + 128
    bb.putShort(rootOff + 0, 0x41ED.toShort()) // S_IFDIR | 0755
    bb.putInt(rootOff + 4, blockSize) // i_size_lo
    bb.putInt(rootOff + 32, 0x80000) // EXT4_EXTENTS_FL
    // Extent header in i_block (rootOff + 40)
    bb.putShort(rootOff + 40, 0xF30A.toShort()) // eh_magic
    bb.putShort(rootOff + 42, 1) // eh_entries
    bb.putShort(rootOff + 44, 4) // eh_max
    bb.putShort(rootOff + 46, 0) // eh_depth
    // Leaf extent pointing to block 4
    bb.putInt(rootOff + 52, 0) // ee_block
    bb.putShort(rootOff + 56, 1) // ee_len
    bb.putShort(rootOff + 58, 0) // ee_start_hi
    bb.putInt(rootOff + 60, 4) // ee_start_lo

    // Directory data at block 4 (4096 * 4) -> entry for "build.prop" -> inode 3
    val dirOff = blockSize * 4
    val nameBytes = "build.prop".toByteArray(Charsets.UTF_8)
    bb.putInt(dirOff + 0, 3) // inode 3
    bb.putShort(dirOff + 4, blockSize.toShort()) // rec_len = rest of block
    img[dirOff + 6] = nameBytes.size.toByte()
    img[dirOff + 7] = 1 // regular file
    System.arraycopy(nameBytes, 0, img, dirOff + 8, nameBytes.size)

    // Inode 3 (build.prop) at block 2 + 2 * 128
    val propText = "ro.build.version.release=15\nro.treble.enabled=true\n"
    val propBytes = propText.toByteArray(Charsets.UTF_8)
    val fileOff = blockSize * 2 + 2 * 128
    bb.putShort(fileOff + 0, 0x81A4.toShort()) // S_IFREG | 0644
    bb.putInt(fileOff + 4, propBytes.size) // i_size_lo
    bb.putInt(fileOff + 32, 0x80000) // EXT4_EXTENTS_FL
    bb.putShort(fileOff + 40, 0xF30A.toShort())
    bb.putShort(fileOff + 42, 1)
    bb.putShort(fileOff + 44, 4)
    bb.putShort(fileOff + 46, 0)
    bb.putInt(fileOff + 52, 0)
    bb.putShort(fileOff + 56, 1)
    bb.putShort(fileOff + 58, 0)
    bb.putInt(fileOff + 60, 5) // block 5

    System.arraycopy(propBytes, 0, img, blockSize * 5, propBytes.size)

    val reader = Ext4ImageReader.open(ByteArrayRandomAccessSource(img))
    val entries = reader.listDirectory("")
    assertEquals(1, entries.size)
    assertEquals("build.prop", entries[0].name)
    assertEquals(EntryType.FILE, entries[0].type)
    assertEquals(propBytes.size.toLong(), entries[0].sizeBytes)

    val readContent = String(reader.readBytes(entries[0]), Charsets.UTF_8)
    assertEquals(propText, readContent)
  }

  @Test
  fun `erofs reader parses flat plain directory and inline file`() {
    val blockSize = 4096
    val img = ByteArray(blockSize * 4)
    val bb = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN)

    // Superblock at 1024
    val sb = 1024
    bb.putInt(sb + 0x00, 0xE0F5E1E2.toInt()) // magic
    img[sb + 0x0C] = 12 // blkszbits -> 4096
    bb.putShort(sb + 0x0E, 0) // root_nid = 0
    bb.putLong(sb + 0x10, 2L) // inos
    bb.putInt(sb + 0x24, 4) // blocks
    bb.putInt(sb + 0x28, 1) // meta_blkaddr = block 1 (offset 4096)

    // Root inode at nid 0 (offset 4096) - compact 32B, FLAT_PLAIN (layout 0) -> dir block 2
    val metaOff = blockSize
    bb.putShort(metaOff + 0, 0) // version=0, datalayout=0 (FLAT_PLAIN)
    bb.putShort(metaOff + 4, 0x41ED.toShort()) // S_IFDIR | 0755
    bb.putInt(metaOff + 8, 64) // size
    bb.putInt(metaOff + 16, 2) // raw_blkaddr = block 2

    // Child inode at nid 1 (offset 4096 + 32) - compact 32B, FLAT_INLINE (layout 2)
    val childText = "hello erofs"
    val childBytes = childText.toByteArray(Charsets.UTF_8)
    val childOff = metaOff + 32
    bb.putShort(childOff + 0, (2 shl 1).toShort()) // version=0, datalayout=2 (FLAT_INLINE)
    bb.putShort(childOff + 4, 0x81A4.toShort()) // S_IFREG | 0644
    bb.putInt(childOff + 8, childBytes.size) // size
    System.arraycopy(childBytes, 0, img, childOff + 32, childBytes.size)

    // Directory block at block 2 (offset 8192): 1 dirent -> nid=1, nameoff=12, name="info.txt"
    val dirOff = blockSize * 2
    bb.putLong(dirOff + 0, 1L) // nid
    bb.putShort(dirOff + 8, 12) // nameoff
    img[dirOff + 10] = 1 // ft
    val name = "info.txt".toByteArray(Charsets.UTF_8)
    System.arraycopy(name, 0, img, dirOff + 12, name.size)

    val reader = ErofsImageReader.open(ByteArrayRandomAccessSource(img))
    val entries = reader.listDirectory("")
    assertEquals(1, entries.size)
    assertEquals("info.txt", entries[0].name)
    assertEquals(childText, String(reader.readBytes(entries[0]), Charsets.UTF_8))
  }

  @Test
  fun `zip archive reader indexes nested folders and extracts content`() {
    val tmpZip = File.createTempFile("test_gsi", ".zip")
    try {
      ZipOutputStream(tmpZip.outputStream()).use { zos ->
        zos.putNextEntry(ZipEntry("system/build.prop"))
        zos.write("ro.build.id=AP3A\n".toByteArray())
        zos.closeEntry()
      }
      val reader = ZipArchiveReader.open(tmpZip, "test_gsi.zip")
      assertEquals(ContainerFormat.ZIP, reader.format)
      val root = reader.listDirectory("")
      assertTrue(root.any { it.name == "system" && it.isDirectory })
      val prop = reader.findEntry("system/build.prop")
      assertNotNull(prop)
      assertEquals("ro.build.id=AP3A\n", String(reader.readBytes(prop!!), Charsets.UTF_8))
      reader.close()
    } finally {
      tmpZip.delete()
    }
  }
}
