package com.example.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GsiAiInspector
import com.example.archive.core.ArchiveEngine
import com.example.archive.core.ArchiveEntry
import com.example.archive.core.ContainerFormat
import com.example.archive.core.EntryType
import com.example.archive.core.GsiTelemetry
import com.example.archive.core.LogicalPartitionInfo
import com.example.archive.core.OpenedContainer
import com.example.archive.core.RecentArchiveRecord
import com.example.data.RecentArchivesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ArchiveExplorerVM"

enum class EntryFilter(val label: String) {
  ALL("All"),
  DIRS("Folders"),
  APKS("APKs"),
  CONFIGS("Configs"),
  IMAGES("Images"),
}

enum class SortMode(val label: String) {
  NAME("Name"),
  SIZE("Size"),
  TYPE("Type"),
}

sealed interface AiInspectorState {
  data object Idle : AiInspectorState
  data object Loading : AiInspectorState
  data class Streaming(val thoughts: String, val answer: String) : AiInspectorState
  data class Success(val thoughts: String, val answer: String) : AiInspectorState
  data class Error(val message: String) : AiInspectorState
}

data class FilePreviewData(
  val entry: ArchiveEntry,
  val rawBytes: ByteArray,
  val textContent: String?,
  val hexDump: String,
  val bitmap: Bitmap?,
  val isTextDefault: Boolean,
  val isImage: Boolean,
)

data class LoadingProgress(
  val stage: String,
  val bytesDone: Long,
  val totalBytes: Long,
)

data class ExtractionProgress(
  val currentFile: String,
  val filesDone: Int,
  val totalFiles: Int,
)

data class BrowserLayerState(
  val container: OpenedContainer.FilesystemBrowser,
  val currentPath: String = "",
  val entries: List<ArchiveEntry> = emptyList(),
)

data class ArchiveUiState(
  val loading: LoadingProgress? = null,
  val extraction: ExtractionProgress? = null,
  val superContainer: OpenedContainer.SuperImage? = null,
  val mountStack: List<BrowserLayerState> = emptyList(),
  val searchQuery: String = "",
  val filter: EntryFilter = EntryFilter.ALL,
  val sortMode: SortMode = SortMode.NAME,
  val selectedIds: Set<String> = emptySet(),
  val preview: FilePreviewData? = null,
  val isPreviewLoading: Boolean = false,
  val pendingSaveEntry: ArchiveEntry? = null,
  val showAiSheet: Boolean = false,
  val aiContextTitle: String = "",
  val aiContextPromptSeed: String = "",
  val aiState: AiInspectorState = AiInspectorState.Idle,
  val bannerMessage: String? = null,
  val errorMessage: String? = null,
) {
  val activeLayer: BrowserLayerState?
    get() = mountStack.lastOrNull()

  val isHome: Boolean
    get() = loading == null && superContainer == null && mountStack.isEmpty()
}

class ArchiveExplorerViewModel(application: Application) : AndroidViewModel(application) {
  private val engine = ArchiveEngine(application)
  private val recentStore = RecentArchivesStore(application)
  private val aiInspector = GsiAiInspector()

  val recentArchives: StateFlow<List<RecentArchiveRecord>> =
    recentStore.recentArchives.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000L),
      initialValue = emptyList(),
    )

  private val _uiState = MutableStateFlow(ArchiveUiState())
  val uiState: StateFlow<ArchiveUiState> = _uiState.asStateFlow()

  private var openJob: Job? = null
  private var aiJob: Job? = null

  fun openUri(uri: Uri) {
    openJob?.cancel()
    openJob =
      viewModelScope.launch {
        runCatching {
          getApplication<Application>().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
          )
        }
        closeAllContainers()
        _uiState.update {
          it.copy(
            loading = LoadingProgress("Opening file...", 0L, -1L),
            errorMessage = null,
            bannerMessage = null,
            superContainer = null,
            mountStack = emptyList(),
            selectedIds = emptySet(),
            searchQuery = "",
            preview = null,
          )
        }
        try {
          val opened =
            engine.openFromUri(uri) { stage, done, total ->
              _uiState.update { it.copy(loading = LoadingProgress(stage, done, total)) }
            }
          when (opened) {
            is OpenedContainer.SuperImage -> {
              _uiState.update {
                it.copy(
                  loading = null,
                  superContainer = opened,
                  mountStack = emptyList(),
                )
              }
              recentStore.recordOpened(
                RecentArchiveRecord(
                  uriString = uri.toString(),
                  displayName = opened.displayName,
                  formatLabel = opened.format.badge,
                  sizeBytes = opened.backingFile.length(),
                  openedAtMillis = System.currentTimeMillis(),
                )
              )
            }
            is OpenedContainer.FilesystemBrowser -> {
              val rootEntries = withContext(Dispatchers.IO) { opened.browser.listDirectory("") }
              _uiState.update {
                it.copy(
                  loading = null,
                  superContainer = null,
                  mountStack = listOf(BrowserLayerState(opened, "", rootEntries)),
                )
              }
              recentStore.recordOpened(
                RecentArchiveRecord(
                  uriString = uri.toString(),
                  displayName = opened.displayName,
                  formatLabel = opened.format.badge,
                  sizeBytes = opened.backingFile.length(),
                  openedAtMillis = System.currentTimeMillis(),
                )
              )
            }
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Failed to open archive/image", e)
          _uiState.update {
            it.copy(
              loading = null,
              errorMessage = e.localizedMessage ?: "Could not open file.",
            )
          }
        }
      }
  }

  fun cancelOpen() {
    openJob?.cancel()
    _uiState.update { it.copy(loading = null) }
  }

  fun mountSuperPartition(partition: LogicalPartitionInfo) {
    val superCont = _uiState.value.superContainer ?: return
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          loading = LoadingProgress("Mounting ${partition.name}...", 0L, partition.sizeBytes),
          errorMessage = null,
        )
      }
      try {
        val mounted =
          withContext(Dispatchers.IO) {
            val container = engine.mountSuperPartition(superCont, partition)
            val rootEntries = container.browser.listDirectory("")
            BrowserLayerState(container, "", rootEntries)
          }
        _uiState.update {
          it.copy(
            loading = null,
            mountStack = listOf(mounted),
            selectedIds = emptySet(),
            searchQuery = "",
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to mount partition ${partition.name}", e)
        _uiState.update {
          it.copy(
            loading = null,
            errorMessage = e.localizedMessage ?: "Unable to mount partition ${partition.name}.",
          )
        }
      }
    }
  }

  fun mountNestedImage(entry: ArchiveEntry) {
    val layer = _uiState.value.activeLayer ?: return
    openJob?.cancel()
    openJob =
      viewModelScope.launch {
        _uiState.update {
          it.copy(
            preview = null,
            loading = LoadingProgress("Extracting ${entry.name}...", 0L, entry.sizeBytes),
            errorMessage = null,
          )
        }
        try {
          val opened =
            engine.openNestedEntry(
              parent = layer.container.browser,
              entry = entry,
              depthIndex = _uiState.value.mountStack.size,
            ) { stage, done, total ->
              _uiState.update { it.copy(loading = LoadingProgress(stage, done, total)) }
            }
          when (opened) {
            is OpenedContainer.FilesystemBrowser -> {
              val rootEntries = withContext(Dispatchers.IO) { opened.browser.listDirectory("") }
              _uiState.update { state ->
                state.copy(
                  loading = null,
                  mountStack = state.mountStack + BrowserLayerState(opened, "", rootEntries),
                  selectedIds = emptySet(),
                  searchQuery = "",
                )
              }
            }
            is OpenedContainer.SuperImage -> {
              closeAllContainers()
              _uiState.update {
                it.copy(
                  loading = null,
                  superContainer = opened,
                  mountStack = emptyList(),
                  selectedIds = emptySet(),
                  searchQuery = "",
                )
              }
            }
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Failed to open nested image ${entry.name}", e)
          _uiState.update {
            it.copy(
              loading = null,
              errorMessage = e.localizedMessage ?: "Failed to mount ${entry.name}.",
            )
          }
        }
      }
  }

  fun navigateToDirectory(path: String) {
    val layer = _uiState.value.activeLayer ?: return
    viewModelScope.launch {
      val entries =
        withContext(Dispatchers.IO) {
          layer.container.browser.listDirectory(path)
        }
      _uiState.update { state ->
        val updatedStack = state.mountStack.dropLast(1) + layer.copy(currentPath = path, entries = entries)
        state.copy(
          mountStack = updatedStack,
          selectedIds = emptySet(),
          searchQuery = "",
        )
      }
    }
  }

  /**
   * Navigates up one directory, or pops a nested mounted image, or returns to home.
   */
  fun navigateBack(): Boolean {
    val state = _uiState.value
    if (state.preview != null) {
      closePreview()
      return true
    }
    if (state.showAiSheet) {
      closeAiSheet()
      return true
    }
    val layer = state.activeLayer
    if (layer != null) {
      if (state.searchQuery.isNotBlank()) {
        updateSearchQuery("")
        return true
      }
      if (layer.currentPath.isNotEmpty()) {
        val parent = layer.currentPath.substringBeforeLast('/', "")
        navigateToDirectory(parent)
        return true
      }
      if (state.mountStack.size > 1) {
        layer.container.close()
        _uiState.update {
          it.copy(
            mountStack = it.mountStack.dropLast(1),
            selectedIds = emptySet(),
            searchQuery = "",
          )
        }
        return true
      }
      if (state.superContainer != null) {
        layer.container.close()
        _uiState.update {
          it.copy(
            mountStack = emptyList(),
            selectedIds = emptySet(),
            searchQuery = "",
          )
        }
        return true
      }
      closeWorkspaceToHome()
      return true
    }
    if (state.superContainer != null) {
      closeWorkspaceToHome()
      return true
    }
    return false
  }

  fun closeWorkspaceToHome() {
    closeAllContainers()
    engine.clearWorkspace()
    _uiState.update {
      ArchiveUiState()
    }
  }

  fun updateSearchQuery(query: String) {
    val layer = _uiState.value.activeLayer
    _uiState.update { it.copy(searchQuery = query) }
    if (layer == null) return
    viewModelScope.launch {
      val entries =
        withContext(Dispatchers.IO) {
          if (query.isBlank()) {
            layer.container.browser.listDirectory(layer.currentPath)
          } else {
            layer.container.browser.search(query)
          }
        }
      _uiState.update { state ->
        val currentLayer = state.activeLayer ?: return@update state
        if (currentLayer.container !== layer.container) return@update state
        state.copy(
          mountStack = state.mountStack.dropLast(1) + currentLayer.copy(entries = entries)
        )
      }
    }
  }

  fun setFilter(filter: EntryFilter) {
    _uiState.update { it.copy(filter = filter) }
  }

  fun setSortMode(sortMode: SortMode) {
    _uiState.update { it.copy(sortMode = sortMode) }
  }

  fun toggleSelection(entryId: String) {
    _uiState.update { state ->
      val next = state.selectedIds.toMutableSet()
      if (!next.add(entryId)) next.remove(entryId)
      state.copy(selectedIds = next)
    }
  }

  fun selectAllVisible(entries: List<ArchiveEntry>) {
    _uiState.update { state ->
      state.copy(selectedIds = entries.map { it.id }.toSet())
    }
  }

  fun clearSelection() {
    _uiState.update { it.copy(selectedIds = emptySet()) }
  }

  fun filteredAndSortedEntries(layer: BrowserLayerState, filter: EntryFilter, sortMode: SortMode): List<ArchiveEntry> {
    val filtered =
      layer.entries.filter { e ->
        when (filter) {
          EntryFilter.ALL -> true
          EntryFilter.DIRS -> e.isDirectory
          EntryFilter.APKS -> e.extension == "apk" || e.extension == "apex"
          EntryFilter.CONFIGS ->
            e.extension in setOf("prop", "rc", "xml", "json", "te", "cil", "conf", "fstab", "sh")
          EntryFilter.IMAGES ->
            e.extension in setOf("img", "png", "jpg", "jpeg", "webp") || e.isNestedImage
        }
      }
    return when (sortMode) {
      SortMode.NAME ->
        filtered.sortedWith(compareBy<ArchiveEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
      SortMode.SIZE ->
        filtered.sortedWith(compareBy<ArchiveEntry> { !it.isDirectory }.thenByDescending { it.sizeBytes })
      SortMode.TYPE ->
        filtered.sortedWith(compareBy<ArchiveEntry> { !it.isDirectory }.thenBy { it.extension }.thenBy { it.name.lowercase() })
    }
  }

  fun openPreview(entry: ArchiveEntry) {
    val layer = _uiState.value.activeLayer ?: return
    if (entry.isDirectory) {
      navigateToDirectory(entry.id)
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isPreviewLoading = true, errorMessage = null) }
      try {
        val preview =
          withContext(Dispatchers.IO) {
            val isImg = isImageFile(entry.name)
            val maxRead = if (isImg) 4 * 1024 * 1024 else 256 * 1024
            val bytes = layer.container.browser.readBytes(entry, maxRead)
            val textOk = !isImg && isLikelyTextFile(entry.name, bytes)
            val text = if (textOk) String(bytes, Charsets.UTF_8) else null
            val bitmap =
              if (isImg && bytes.isNotEmpty()) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
              } else {
                null
              }
            FilePreviewData(
              entry = entry,
              rawBytes = bytes,
              textContent = text,
              hexDump = formatHexDump(bytes),
              bitmap = bitmap,
              isTextDefault = textOk,
              isImage = isImg && bitmap != null,
            )
          }
        _uiState.update { it.copy(isPreviewLoading = false, preview = preview) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to preview ${entry.name}", e)
        _uiState.update {
          it.copy(
            isPreviewLoading = false,
            errorMessage = e.localizedMessage ?: "Failed to read ${entry.name}",
          )
        }
      }
    }
  }

  fun closePreview() {
    _uiState.update { it.copy(preview = null) }
  }

  fun markPendingSaveEntry(entry: ArchiveEntry) {
    _uiState.update { it.copy(pendingSaveEntry = entry) }
  }

  fun savePendingEntryToUri(destUri: Uri) {
    val entry = _uiState.value.pendingSaveEntry ?: return
    val layer = _uiState.value.activeLayer ?: return
    _uiState.update { it.copy(pendingSaveEntry = null) }

    viewModelScope.launch {
      _uiState.update {
        it.copy(extraction = ExtractionProgress(entry.name, 0, 1))
      }
      try {
        engine.extractSingleToUri(layer.container.browser, entry, destUri)
        _uiState.update {
          it.copy(
            extraction = null,
            bannerMessage = "Extracted ${entry.name} (${formatBytes(entry.sizeBytes)})",
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Extraction failed for ${entry.name}", e)
        _uiState.update {
          it.copy(
            extraction = null,
            errorMessage = e.localizedMessage ?: "Failed to extract ${entry.name}",
          )
        }
      }
    }
  }

  fun extractSelectionToTree(treeUri: Uri) {
    val layer = _uiState.value.activeLayer ?: return
    val selected = _uiState.value.selectedIds
    val targets =
      if (selected.isNotEmpty()) {
        layer.entries.filter { it.id in selected }
      } else {
        layer.entries
      }
    if (targets.isEmpty()) return

    viewModelScope.launch {
      _uiState.update {
        it.copy(extraction = ExtractionProgress("Preparing extraction...", 0, targets.size))
      }
      try {
        val count =
          engine.extractEntriesToTree(layer.container.browser, targets, treeUri) { file, done, total ->
            _uiState.update {
              it.copy(extraction = ExtractionProgress(file, done, total))
            }
          }
        _uiState.update {
          it.copy(
            extraction = null,
            selectedIds = emptySet(),
            bannerMessage = "Extracted $count file(s) successfully.",
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Batch extraction failed", e)
        _uiState.update {
          it.copy(
            extraction = null,
            errorMessage = e.localizedMessage ?: "Batch extraction failed.",
          )
        }
      }
    }
  }

  fun shareEntry(entry: ArchiveEntry, onReady: (Uri, String) -> Unit) {
    val layer = _uiState.value.activeLayer ?: return
    viewModelScope.launch {
      try {
        val uri = engine.exportToShareUri(layer.container.browser, entry)
        onReady(uri, ArchiveEngine.guessMimeType(entry.name))
      } catch (e: Exception) {
        Log.e(TAG, "Share failed", e)
        _uiState.update { it.copy(errorMessage = e.localizedMessage ?: "Unable to share file.") }
      }
    }
  }

  fun removeRecent(uriString: String) {
    viewModelScope.launch { recentStore.removeRecord(uriString) }
  }

  fun openAiInspectorForGsi(telemetry: GsiTelemetry) {
    val layer = _uiState.value.activeLayer
    val label = layer?.container?.displayName ?: "Android GSI Image"
    val format = layer?.container?.format?.label ?: "Image"
    val seedPrompt =
      buildString {
        appendLine("Analyze this Android GSI / Archive container and assess device compatibility, partition structure, and flashing/boot considerations.")
        appendLine("Container: $label ($format)")
        appendLine("System-as-Root: ${telemetry.systemAsRoot}")
        telemetry.androidVersion?.let { appendLine("Android Version: $it (SDK ${telemetry.sdkVersion ?: "?"})") }
        telemetry.buildId?.let { appendLine("Build ID: $it") }
        telemetry.securityPatch?.let { appendLine("Security Patch: $it") }
        telemetry.abis?.let { appendLine("CPU ABIs: $it") }
        telemetry.trebleEnabled?.let { appendLine("Treble Enabled: $it") }
        telemetry.vndkVersion?.let { appendLine("VNDK Version: $it") }
        appendLine("\n--- build.prop excerpt ---")
        appendLine(telemetry.rawBuildProp.take(6000))
      }
    launchAiAnalysis(
      title = "GSI Compatibility Report",
      seedContext = seedPrompt,
      initialPrompt = seedPrompt,
    )
  }

  fun openAiInspectorForPreview(preview: FilePreviewData) {
    val layer = _uiState.value.activeLayer
    val containerLabel = layer?.container?.displayName ?: "Archive"
    val contentExcerpt =
      preview.textContent?.take(6500)
        ?: "Binary hex header (${preview.rawBytes.size} bytes sampled of ${formatBytes(preview.entry.sizeBytes)}):\n${preview.hexDump.take(3000)}"
    val seedContext =
      buildString {
        appendLine("Container: $containerLabel (${layer?.container?.format?.label ?: ""})")
        appendLine("File Path: /${preview.entry.id}")
        appendLine("Size: ${formatBytes(preview.entry.sizeBytes)} | Permissions: ${preview.entry.permissionsString()}")
        appendLine("\n--- File Content / Header ---")
        appendLine(contentExcerpt)
      }
    val initialPrompt =
      "Inspect '/${preview.entry.id}' from this Android GSI/archive. Explain its purpose, key settings or flags, any compatibility or security concerns, and anything notable for flashing or modding."
    launchAiAnalysis(
      title = "AI Inspect: ${preview.entry.name}",
      seedContext = seedContext,
      initialPrompt = "$seedContext\n\nTask: $initialPrompt",
    )
  }

  fun askAiFollowUp(userQuestion: String) {
    val trimmed = userQuestion.trim()
    if (trimmed.isEmpty()) return
    val seed = _uiState.value.aiContextPromptSeed
    val fullPrompt =
      if (seed.isNotBlank()) {
        "$seed\n\nUser question: $trimmed"
      } else {
        trimmed
      }
    runAiQuery(fullPrompt)
  }

  private fun launchAiAnalysis(title: String, seedContext: String, initialPrompt: String) {
    _uiState.update {
      it.copy(
        showAiSheet = true,
        aiContextTitle = title,
        aiContextPromptSeed = seedContext,
        aiState = AiInspectorState.Loading,
      )
    }
    runAiQuery(initialPrompt)
  }

  private fun runAiQuery(prompt: String) {
    aiJob?.cancel()
    aiJob =
      viewModelScope.launch {
        _uiState.update { it.copy(aiState = AiInspectorState.Loading) }
        val thoughts = StringBuilder()
        val answer = StringBuilder()
        try {
          aiInspector.analyzeStream(prompt).collect { chunk ->
            thoughts.append(chunk.thoughtSummaryDelta)
            answer.append(chunk.answerDelta)
            _uiState.update {
              it.copy(
                aiState =
                  AiInspectorState.Streaming(
                    thoughts = thoughts.toString(),
                    answer = answer.toString(),
                  )
              )
            }
          }
          if (answer.isNotBlank() || thoughts.isNotBlank()) {
            _uiState.update {
              it.copy(
                aiState =
                  AiInspectorState.Success(
                    thoughts = thoughts.toString(),
                    answer = answer.toString(),
                  )
              )
            }
          } else {
            _uiState.update {
              it.copy(aiState = AiInspectorState.Error("Gemini returned an empty response."))
            }
          }
        } catch (e: TimeoutCancellationException) {
          Log.e(TAG, "AI analysis timed out", e)
          _uiState.update {
            it.copy(
              aiState =
                AiInspectorState.Error(
                  "Deep thinking timed out after 60 seconds. Try asking a more focused question."
                )
            )
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Firebase AI analysis failed", e)
          _uiState.update {
            it.copy(
              aiState = AiInspectorState.Error(e.localizedMessage ?: "AI analysis failed.")
            )
          }
        }
      }
  }

  fun closeAiSheet() {
    aiJob?.cancel()
    _uiState.update { it.copy(showAiSheet = false) }
  }

  fun dismissBanner() {
    _uiState.update { it.copy(bannerMessage = null) }
  }

  fun dismissError() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  private fun closeAllContainers() {
    _uiState.value.mountStack.forEach { runCatching { it.container.close() } }
    _uiState.value.superContainer?.let { runCatching { it.close() } }
  }

  override fun onCleared() {
    super.onCleared()
    closeAllContainers()
    engine.clearWorkspace()
  }
}
