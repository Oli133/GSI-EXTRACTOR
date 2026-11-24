package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ai.configureAppCheck
import com.example.ui.ArchiveExplorerViewModel
import com.example.ui.ArchiveUiState
import com.example.ui.formatBytes
import com.example.ui.screens.AiInspectorSheet
import com.example.ui.screens.BrowserScreen
import com.example.ui.screens.FilePreviewSheet
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SuperPartitionScreen
import com.example.ui.theme.GsiExtractorTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Configure Firebase App Check before Compose UI or ViewModels are created
    configureAppCheck(this, intent)

    enableEdgeToEdge()
    val launchUri: Uri? = if (intent?.action == Intent.ACTION_VIEW) intent.data else null

    setContent {
      GsiExtractorTheme {
        GsiExtractorApp(initialUri = launchUri)
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GsiExtractorApp(
  initialUri: Uri? = null,
  viewModel: ArchiveExplorerViewModel = viewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val recentArchives by viewModel.recentArchives.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(initialUri) {
    if (initialUri != null) {
      viewModel.openUri(initialUri)
    }
  }

  LaunchedEffect(uiState.bannerMessage) {
    val msg = uiState.bannerMessage
    if (msg != null) {
      snackbarHostState.showSnackbar(msg)
      viewModel.dismissBanner()
    }
  }

  val openFileLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
      if (uri != null) {
        viewModel.openUri(uri)
      }
    }

  val saveSingleLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
      if (uri != null) {
        viewModel.savePendingEntryToUri(uri)
      }
    }

  val extractTreeLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
      if (treeUri != null) {
        viewModel.extractSelectionToTree(treeUri)
      }
    }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    topBar = {
      GsiTopBar(
        uiState = uiState,
        onBackClick = { viewModel.navigateBack() },
        onOpenFileClick = { openFileLauncher.launch(arrayOf("*/*")) },
        onExtractAllClick = { extractTreeLauncher.launch(null) },
        onCloseArchiveClick = { viewModel.closeWorkspaceToHome() },
      )
    },
  ) { innerPadding ->
    when {
      uiState.activeLayer != null -> {
        val layer = uiState.activeLayer!!
        val visibleEntries =
          remember(layer.entries, uiState.filter, uiState.sortMode) {
            viewModel.filteredAndSortedEntries(layer, uiState.filter, uiState.sortMode)
          }
        BrowserScreen(
          layer = layer,
          entries = visibleEntries,
          searchQuery = uiState.searchQuery,
          filter = uiState.filter,
          sortMode = uiState.sortMode,
          selectedIds = uiState.selectedIds,
          onSearchQueryChange = viewModel::updateSearchQuery,
          onFilterChange = viewModel::setFilter,
          onSortModeChange = viewModel::setSortMode,
          onEntryClick = { entry ->
            if (entry.isDirectory) {
              viewModel.navigateToDirectory(entry.id)
            } else {
              viewModel.openPreview(entry)
            }
          },
          onEntryLongClick = { entry -> viewModel.toggleSelection(entry.id) },
          onNavigateBreadcrumb = viewModel::navigateToDirectory,
          onSelectAll = { viewModel.selectAllVisible(visibleEntries) },
          onClearSelection = viewModel::clearSelection,
          onExtractSelected = { extractTreeLauncher.launch(null) },
          onAnalyzeGsi = viewModel::openAiInspectorForGsi,
          onBack = { viewModel.navigateBack() },
          contentPadding = innerPadding,
        )
      }

      uiState.superContainer != null -> {
        SuperPartitionScreen(
          superContainer = uiState.superContainer!!,
          onSelectPartition = viewModel::mountSuperPartition,
          onBack = { viewModel.navigateBack() },
          contentPadding = innerPadding,
        )
      }

      else -> {
        HomeScreen(
          recentArchives = recentArchives,
          onOpenFileClick = { openFileLauncher.launch(arrayOf("*/*")) },
          onOpenRecentClick = { rec -> viewModel.openUri(Uri.parse(rec.uriString)) },
          onRemoveRecentClick = { rec -> viewModel.removeRecent(rec.uriString) },
          contentPadding = innerPadding,
        )
      }
    }
  }

  // Loading dialog
  uiState.loading?.let { loading ->
    AlertDialog(
      onDismissRequest = {},
      title = { Text("Opening Archive / Image") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(
            text = loading.stage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (loading.totalBytes > 0L) {
            val fraction = (loading.bytesDone.toFloat() / loading.totalBytes.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
              text = "${formatBytes(loading.bytesDone)} / ${formatBytes(loading.totalBytes)}",
              style = MaterialTheme.typography.labelSmall,
            )
          } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (loading.bytesDone > 0L) {
              Text(
                text = "${formatBytes(loading.bytesDone)} processed",
                style = MaterialTheme.typography.labelSmall,
              )
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = viewModel::cancelOpen) {
          Text("Cancel")
        }
      },
    )
  }

  // Extraction progress dialog
  uiState.extraction?.let { ext ->
    AlertDialog(
      onDismissRequest = {},
      title = { Text("Extracting Files") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = ext.currentFile,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (ext.totalFiles > 0) {
            val fraction = (ext.filesDone.toFloat() / ext.totalFiles.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
              text = "${ext.filesDone} of ${ext.totalFiles} file(s)",
              style = MaterialTheme.typography.labelSmall,
            )
          } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          }
        }
      },
      confirmButton = {},
    )
  }

  // Preview loading indicator overlay
  if (uiState.isPreviewLoading) {
    AlertDialog(
      onDismissRequest = {},
      text = {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          CircularProgressIndicator(modifier = Modifier.size(24.dp))
          Text("Reading file contents...")
        }
      },
      confirmButton = {},
    )
  }

  // Error dialog
  uiState.errorMessage?.let { err ->
    AlertDialog(
      onDismissRequest = viewModel::dismissError,
      title = { Text("Unable to Process File") },
      text = { Text(err) },
      confirmButton = {
        TextButton(onClick = viewModel::dismissError) {
          Text("Dismiss")
        }
      },
    )
  }

  // File preview bottom sheet
  uiState.preview?.let { preview ->
    FilePreviewSheet(
      preview = preview,
      onDismiss = viewModel::closePreview,
      onSaveAs = { entry ->
        viewModel.markPendingSaveEntry(entry)
        saveSingleLauncher.launch(entry.name)
      },
      onShare = { entry ->
        viewModel.shareEntry(entry) { shareUri, mime ->
          val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
              type = mime
              putExtra(Intent.EXTRA_STREAM, shareUri)
              addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
          context.startActivity(Intent.createChooser(sendIntent, "Share ${entry.name}"))
        }
      },
      onMountNested = viewModel::mountNestedImage,
      onAiInspect = viewModel::openAiInspectorForPreview,
    )
  }

  // AI Inspector sheet
  if (uiState.showAiSheet) {
    AiInspectorSheet(
      title = uiState.aiContextTitle,
      aiState = uiState.aiState,
      onAskFollowUp = viewModel::askAiFollowUp,
      onDismiss = viewModel::closeAiSheet,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GsiTopBar(
  uiState: ArchiveUiState,
  onBackClick: () -> Unit,
  onOpenFileClick: () -> Unit,
  onExtractAllClick: () -> Unit,
  onCloseArchiveClick: () -> Unit,
) {
  val activeLayer = uiState.activeLayer
  val superContainer = uiState.superContainer

  TopAppBar(
    colors =
      TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
      ),
    navigationIcon = {
      if (!uiState.isHome) {
        IconButton(onClick = onBackClick, modifier = Modifier.size(48.dp).testTag("topbar_back_button")) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.cd_back),
          )
        }
      }
    },
    title = {
      when {
        activeLayer != null -> {
          Column {
            Text(
              text = activeLayer.container.displayName,
              style = MaterialTheme.typography.titleMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
              ) {
                Text(
                  text = activeLayer.container.format.badge,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
              }
              Text(
                text = formatBytes(activeLayer.container.browser.totalSizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
        superContainer != null -> {
          Column {
            Text(
              text = superContainer.displayName,
              style = MaterialTheme.typography.titleMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = "${superContainer.format.badge} • ${superContainer.partitions.size} partitions",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
        else -> {
          Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
          )
        }
      }
    },
    actions = {
      if (activeLayer != null) {
        IconButton(
          onClick = onExtractAllClick,
          modifier = Modifier.size(48.dp).testTag("topbar_extract_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.Download,
            contentDescription = stringResource(R.string.cd_extract_selected),
            tint = MaterialTheme.colorScheme.primary,
          )
        }
        IconButton(
          onClick = onCloseArchiveClick,
          modifier = Modifier.size(48.dp).testTag("topbar_close_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.cd_close),
          )
        }
      } else if (superContainer != null) {
        IconButton(onClick = onCloseArchiveClick, modifier = Modifier.size(48.dp)) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.cd_close),
          )
        }
      } else {
        IconButton(
          onClick = onOpenFileClick,
          modifier = Modifier.size(48.dp).testTag("topbar_open_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.FolderOpen,
            contentDescription = stringResource(R.string.cd_open_file),
            tint = MaterialTheme.colorScheme.primary,
          )
        }
      }
    },
  )
}
