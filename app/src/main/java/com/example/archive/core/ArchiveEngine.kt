package com.example.archive.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

sealed interface OpenedContainer : Closeable {
  val displayName: String
  val backingFile: File
  val format: ContainerFormat

  data class FilesystemBrowser(
    override val displayName: String,
    override val backingFile: File,
    val browser: ArchiveBrowser,
    val telemetry: GsiTelemetry?,
  ) : OpenedContainer {
    override val format: ContainerFormat = browser.format
    override fun close() = browser.close()
  }

  data class SuperImage(
    override val displayName: String,
    override val backingFile: File,
    override val format: ContainerFormat,
    val source: RandomAccessSource,
    val partitions: List<LogicalPartitionInfo>,
  ) : OpenedContainer {
    override fun close() = source.close()
  }
}

class ArchiveEngine(private val context: Context) {

  private val workspaceDir: File
    get() = File(context.cacheDir, "workspace").apply { mkdirs() }

  private val sharedDir: File
    get() = File(context.cacheDir, "shared").apply { mkdirs() }

  fun clearWorkspace() {
    workspaceDir.listFiles()?.forEach { it.deleteRecursively() }
  }

  fun queryUriMetadata(uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "archive"
    var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
      if (c.moveToFirst()) {
        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIdx >= 0) {
          c.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
        }
        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) {
          size = c.getLong(sizeIdx)
        }
      }
    }
    return name to size
  }

  suspend fun openFromUri(
    uri: Uri,
    onProgress: (stage: String, bytesDone: Long, totalBytes: Long) -> Unit,
  ): OpenedContainer = withContext(Dispatchers.IO) {
    val (displayName, expectedSize) = queryUriMetadata(uri)
    clearWorkspace()

    val rawInput =
      context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("Unable to open input stream for selected file.")

    val buffered = BufferedInputStream(rawInput, 65536)
    buffered.mark(16)
    val magic = ByteArray(8)
    val magicLen = buffered.read(magic)
    buffered.reset()

    val isXz =
      magicLen >= 6 &&
        magic[0] == 0xFD.toByte() &&
        magic[1] == 0x37.toByte() &&
        magic[2] == 0x7A.toByte() &&
        magic[3] == 0x58.toByte() &&
        magic[4] == 0x5A.toByte() &&
        magic[5] == 0x00.toByte()

    val isGz =
      magicLen >= 2 &&
        magic[0] == 0x1F.toByte() &&
        magic[1] == 0x8B.toByte()

    val targetFileName =
      when {
        isXz -> displayName.removeSuffix(".xz").ifBlank { "decompressed.img" }
        isGz -> displayName.removeSuffix(".gz").ifBlank { "decompressed.img" }
        else -> displayName
      }.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    val workFile = File(workspaceDir, targetFileName)

    val streamToCopy: InputStream =
      when {
        isXz -> {
          onProgress("Decompressing XZ image...", 0L, expectedSize)
          XZInputStream(buffered)
        }
        isGz -> {
          onProgress("Decompressing GZIP image...", 0L, expectedSize)
          GZIPInputStream(buffered, 65536)
        }
        else -> {
          onProgress("Staging file for inspection...", 0L, expectedSize)
          buffered
        }
      }

    streamToCopy.use { input ->
      FileOutputStream(workFile).use { out ->
        val buf = ByteArray(65536)
        var totalWritten = 0L
        var lastEmit = 0L
        while (true) {
          coroutineContext.ensureActive()
          val n = input.read(buf)
          if (n <= 0) break
          out.write(buf, 0, n)
          totalWritten += n
          if (totalWritten - lastEmit >= 512 * 1024) {
            val stage =
              when {
                isXz -> "Decompressing XZ image..."
                isGz -> "Decompressing GZIP image..."
                else -> "Staging file for inspection..."
              }
            onProgress(stage, totalWritten, if (isXz || isGz) -1L else expectedSize)
            lastEmit = totalWritten
          }
        }
      }
    }

    onProgress("Parsing container headers...", workFile.length(), workFile.length())
    openLocalFile(workFile, displayName)
  }

  suspend fun openNestedEntry(
    parent: ArchiveBrowser,
    entry: ArchiveEntry,
    depthIndex: Int,
    onProgress: (stage: String, bytesDone: Long, totalBytes: Long) -> Unit,
  ): OpenedContainer = withContext(Dispatchers.IO) {
    val cleanName = entry.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    val rawNested = File(workspaceDir, "nested_${depthIndex}_$cleanName")
    parent.extractEntry(entry, FileOutputStream(rawNested)) { done, total ->
      onProgress("Extracting ${entry.name}...", done, total)
    }

    // Check if nested entry itself is .xz or .gz
    val header = ByteArray(8)
    rawNested.inputStream().use { it.read(header) }
    val isXz =
      header[0] == 0xFD.toByte() &&
        header[1] == 0x37.toByte() &&
        header[2] == 0x7A.toByte() &&
        header[3] == 0x58.toByte() &&
        header[4] == 0x5A.toByte() &&
        header[5] == 0x00.toByte()
    val isGz = header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()

    val finalFile =
      if (isXz || isGz) {
        val decompressed =
          File(workspaceDir, "nested_${depthIndex}_decompressed_${cleanName.removeSuffix(".xz").removeSuffix(".gz")}")
        val inStream =
          if (isXz) XZInputStream(rawNested.inputStream().buffered())
          else GZIPInputStream(rawNested.inputStream().buffered(), 65536)
        inStream.use { inp ->
          FileOutputStream(decompressed).use { out ->
            val buf = ByteArray(65536)
            var total = 0L
            while (true) {
              coroutineContext.ensureActive()
              val n = inp.read(buf)
              if (n <= 0) break
              out.write(buf, 0, n)
              total += n
              onProgress("Decompressing ${entry.name}...", total, -1L)
            }
          }
        }
        rawNested.delete()
        decompressed
      } else {
        rawNested
      }

    onProgress("Mounting ${entry.name}...", finalFile.length(), finalFile.length())
    openLocalFile(finalFile, entry.name)
  }

  fun mountSuperPartition(
    superContainer: OpenedContainer.SuperImage,
    partition: LogicalPartitionInfo,
  ): OpenedContainer.FilesystemBrowser {
    val partSource = ExtentRandomAccessSource(superContainer.source, partition)
    val formatPrefix =
      if (superContainer.format == ContainerFormat.SPARSE_SUPER) "SPARSE" else "SUPER"

    val browser: ArchiveBrowser =
      when {
        Ext4ImageReader.isExt4(partSource) -> {
          Ext4ImageReader.open(
            partSource,
            format = if (formatPrefix == "SPARSE") ContainerFormat.SPARSE_EXT4 else ContainerFormat.EXT4,
            fallbackLabel = partition.name,
          )
        }
        ErofsImageReader.isErofs(partSource) -> {
          ErofsImageReader.open(
            partSource,
            format = if (formatPrefix == "SPARSE") ContainerFormat.SPARSE_EROFS else ContainerFormat.EROFS,
            fallbackLabel = partition.name,
          )
        }
        else -> throw IllegalStateException("Partition '${partition.name}' does not contain a readable EXT4 or EROFS filesystem.")
      }

    val telemetry = extractTelemetry(browser)
    return OpenedContainer.FilesystemBrowser(
      displayName = "${superContainer.displayName} › ${partition.name}",
      backingFile = superContainer.backingFile,
      browser = browser,
      telemetry = telemetry,
    )
  }

  fun openLocalFile(file: File, displayName: String): OpenedContainer {
    val fileSource = FileRandomAccessSource(file)
    try {
      // 1. Check ZIP magic (PK\x03\x04 or PK\x05\x06)
      val head4 = fileSource.readFully(0, 4)
      if (head4.size >= 4 && head4[0] == 0x50.toByte() && head4[1] == 0x4B.toByte() &&
        ((head4[2] == 0x03.toByte() && head4[3] == 0x04.toByte()) ||
          (head4[2] == 0x05.toByte() && head4[3] == 0x06.toByte()))
      ) {
        fileSource.close()
        val zipBrowser = ZipArchiveReader.open(file, displayName)
        val telemetry = extractTelemetry(zipBrowser)
        return OpenedContainer.FilesystemBrowser(displayName, file, zipBrowser, telemetry)
      }

      // 2. Check Android Sparse Image (0xED26FF3A)
      val isSparse = SparseImageSource.isSparse(fileSource)
      val activeSource: RandomAccessSource =
        if (isSparse) SparseImageSource.open(fileSource) else fileSource

      // 3. Check Super Image (liblp)
      if (LogicalPartitionParser.isSuperImage(activeSource)) {
        val partitions = LogicalPartitionParser.parsePartitions(activeSource)
        if (partitions.isNotEmpty()) {
          return OpenedContainer.SuperImage(
            displayName = displayName,
            backingFile = file,
            format = if (isSparse) ContainerFormat.SPARSE_SUPER else ContainerFormat.SUPER_IMG,
            source = activeSource,
            partitions = partitions,
          )
        }
      }

      // 4. Check EXT4 / EXT3 / EXT2
      if (Ext4ImageReader.isExt4(activeSource)) {
        val fmt = if (isSparse) ContainerFormat.SPARSE_EXT4 else ContainerFormat.EXT4
        val ext4 = Ext4ImageReader.open(activeSource, format = fmt, fallbackLabel = displayName)
        val telemetry = extractTelemetry(ext4)
        return OpenedContainer.FilesystemBrowser(displayName, file, ext4, telemetry)
      }

      // 5. Check EROFS
      if (ErofsImageReader.isErofs(activeSource)) {
        val fmt = if (isSparse) ContainerFormat.SPARSE_EROFS else ContainerFormat.EROFS
        val erofs = ErofsImageReader.open(activeSource, format = fmt, fallbackLabel = displayName)
        val telemetry = extractTelemetry(erofs)
        return OpenedContainer.FilesystemBrowser(displayName, file, erofs, telemetry)
      }

      activeSource.close()
      throw IllegalArgumentException(
        "Unsupported image or archive format for '$displayName'. Supported: ZIP, APK, EXT2/3/4 .img, EROFS .img, Android Sparse .img, Dynamic super.img, and .img.xz / .img.gz."
      )
    } catch (e: Exception) {
      runCatching { fileSource.close() }
      throw e
    }
  }

  private fun extractTelemetry(browser: ArchiveBrowser): GsiTelemetry? {
    val candidates =
      listOf(
        "system/build.prop",
        "build.prop",
        "system/system/build.prop",
        "system/etc/build.prop",
        "etc/build.prop",
      )
    for (path in candidates) {
      val entry = browser.findEntry(path)
      if (entry != null && !entry.isDirectory && entry.sizeBytes in 1L..512_000L) {
        val raw = String(browser.readBytes(entry, 256_000), Charsets.UTF_8)
        if (raw.contains("ro.")) {
          return parseBuildProp(raw, systemAsRoot = path.startsWith("system/"))
        }
      }
    }
    return null
  }

  private fun parseBuildProp(raw: String, systemAsRoot: Boolean): GsiTelemetry {
    val props = HashMap<String, String>()
    for (line in raw.lineSequence()) {
      val trimmed = line.trim()
      if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
      val eq = trimmed.indexOf('=')
      if (eq > 0) {
        props[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
      }
    }
    return GsiTelemetry(
      androidVersion = props["ro.system.build.version.release"] ?: props["ro.build.version.release"],
      sdkVersion = props["ro.system.build.version.sdk"] ?: props["ro.build.version.sdk"],
      securityPatch = props["ro.build.version.security_patch"],
      buildId = props["ro.system.build.id"] ?: props["ro.build.display.id"] ?: props["ro.build.id"],
      abis = props["ro.system.product.cpu.abilist"] ?: props["ro.product.cpu.abilist"] ?: props["ro.product.cpu.abi"],
      manufacturer = props["ro.product.system.manufacturer"] ?: props["ro.product.manufacturer"],
      model = props["ro.product.system.model"] ?: props["ro.product.model"],
      trebleEnabled = props["ro.treble.enabled"],
      vndkVersion = props["ro.vndk.version"],
      systemAsRoot = systemAsRoot,
      rawBuildProp = raw.take(12_000),
    )
  }

  suspend fun extractSingleToUri(
    browser: ArchiveBrowser,
    entry: ArchiveEntry,
    destUri: Uri,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
  ) = withContext(Dispatchers.IO) {
    context.contentResolver.openOutputStream(destUri)?.use { out ->
      browser.extractEntry(entry, out, onProgress)
    } ?: throw IllegalStateException("Cannot write to target document.")
  }

  suspend fun extractEntriesToTree(
    browser: ArchiveBrowser,
    entries: List<ArchiveEntry>,
    treeUri: Uri,
    onProgress: (currentFile: String, filesDone: Int, totalFiles: Int) -> Unit,
  ): Int = withContext(Dispatchers.IO) {
    val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
    val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)

    // Expand directories into flat list of (relativePath, ArchiveEntry)
    val filesToExtract = ArrayList<Pair<String, ArchiveEntry>>()
    val dirStack = ArrayDeque<ArchiveEntry>()
    for (e in entries) {
      if (e.isDirectory) dirStack.add(e)
      else if (e.type == EntryType.FILE) filesToExtract.add(e.name to e)
    }

    val seenDirs = HashSet<String>()
    while (dirStack.isNotEmpty()) {
      coroutineContext.ensureActive()
      val dir = dirStack.removeFirst()
      if (!seenDirs.add(dir.id)) continue
      for (child in browser.listDirectory(dir.id)) {
        if (child.isDirectory) {
          dirStack.add(child)
        } else if (child.type == EntryType.FILE) {
          filesToExtract.add(child.id to child)
        }
      }
    }

    val dirUriCache = HashMap<String, Uri>().apply { put("", rootDocUri) }

    fun getOrCreateDir(relDir: String): Uri {
      if (relDir.isEmpty()) return rootDocUri
      dirUriCache[relDir]?.let { return it }
      val parentRel = relDir.substringBeforeLast('/', "")
      val name = relDir.substringAfterLast('/')
      val parentUri = getOrCreateDir(parentRel)
      val created =
        findChildDocumentUri(treeUri, parentUri, name)
          ?: DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
          )
          ?: throw IllegalStateException("Failed to create folder '$name'")
      dirUriCache[relDir] = created
      return created
    }

    var extractedCount = 0
    val total = filesToExtract.size
    for ((idx, pair) in filesToExtract.withIndex()) {
      coroutineContext.ensureActive()
      val (relPath, entry) = pair
      onProgress(entry.name, idx, total)
      val parentRel = relPath.substringBeforeLast('/', "")
      val parentUri = getOrCreateDir(parentRel)
      val docUri =
        DocumentsContract.createDocument(
          context.contentResolver,
          parentUri,
          guessMimeType(entry.name),
          entry.name,
        ) ?: continue
      context.contentResolver.openOutputStream(docUri)?.use { out ->
        browser.extractEntry(entry, out)
      }
      extractedCount++
      onProgress(entry.name, idx + 1, total)
    }
    extractedCount
  }

  private fun findChildDocumentUri(treeUri: Uri, parentDocUri: Uri, targetName: String): Uri? {
    val parentId = DocumentsContract.getDocumentId(parentDocUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
    context.contentResolver.query(
      childrenUri,
      arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      ),
      null,
      null,
      null,
    )?.use { c ->
      val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
      val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      while (c.moveToNext()) {
        if (nameCol >= 0 && c.getString(nameCol) == targetName && idCol >= 0) {
          return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(idCol))
        }
      }
    }
    return null
  }

  suspend fun exportToShareUri(browser: ArchiveBrowser, entry: ArchiveEntry): Uri =
    withContext(Dispatchers.IO) {
      sharedDir.listFiles()?.forEach { it.delete() }
      val safeName = entry.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
      val tempFile = File(sharedDir, safeName)
      FileOutputStream(tempFile).use { out ->
        browser.extractEntry(entry, out)
      }
      FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
    }

  companion object {
    fun guessMimeType(name: String): String {
      return when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "prop", "rc", "sh", "te", "cil", "conf", "cfg" -> "text/plain"
        "xml" -> "text/xml"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
      }
    }
  }
}
