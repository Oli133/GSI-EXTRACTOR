package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.archive.core.ArchiveEntry
import com.example.ui.FilePreviewData
import com.example.ui.formatBytes
import com.example.ui.formatTimestamp
import com.example.ui.theme.MonoCodeStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewSheet(
  preview: FilePreviewData,
  onDismiss: () -> Unit,
  onSaveAs: (ArchiveEntry) -> Unit,
  onShare: (ArchiveEntry) -> Unit,
  onMountNested: (ArchiveEntry) -> Unit,
  onAiInspect: (FilePreviewData) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val context = LocalContext.current

  val tabs =
    remember(preview.entry.id, preview.isImage, preview.textContent) {
      buildList {
        if (preview.isImage) add("Image")
        if (preview.textContent != null) add("Text")
        add("Hex Dump")
      }
    }
  var selectedTab by remember(preview.entry.id) { mutableIntStateOf(0) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .navigationBarsPadding()
          .padding(horizontal = 16.dp)
          .padding(bottom = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Header
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = preview.entry.name,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "/${preview.entry.id}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.cd_close),
          )
        }
      }

      // Metadata pill row
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = formatBytes(preview.entry.sizeBytes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = "${preview.entry.permissionsString()} (${preview.entry.uid}:${preview.entry.gid})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = formatTimestamp(preview.entry.modifiedTimeSeconds),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      // Actions
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (preview.entry.isNestedImage) {
          Button(
            onClick = { onMountNested(preview.entry) },
            modifier = Modifier.testTag("preview_mount_button"),
            colors =
              ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
              ),
          ) {
            Icon(
              imageVector = Icons.Filled.Layers,
              contentDescription = stringResource(R.string.cd_mount_image),
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Mount Image")
          }
        }

        FilledTonalButton(
          onClick = { onSaveAs(preview.entry) },
          modifier = Modifier.testTag("preview_save_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.Download,
            contentDescription = stringResource(R.string.cd_save_as),
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text("Extract")
        }

        FilledTonalButton(
          onClick = { onShare(preview.entry) },
          modifier = Modifier.testTag("preview_share_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.Share,
            contentDescription = stringResource(R.string.cd_share),
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text("Share")
        }

        FilledTonalButton(
          onClick = { onAiInspect(preview) },
          modifier = Modifier.testTag("preview_ai_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = stringResource(R.string.cd_ai_inspector),
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text("AI Inspect")
        }
      }

      if (tabs.size > 1) {
        TabRow(selectedTabIndex = selectedTab.coerceIn(0, tabs.lastIndex)) {
          tabs.forEachIndexed { idx, title ->
            Tab(
              selected = selectedTab == idx,
              onClick = { selectedTab = idx },
              text = { Text(title) },
            )
          }
        }
      }

      val activeTab = tabs.getOrElse(selectedTab) { "Hex Dump" }
      when (activeTab) {
        "Image" -> {
          Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 380.dp),
          ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(16.dp)) {
              preview.bitmap?.let { bmp ->
                Image(
                  bitmap = bmp.asImageBitmap(),
                  contentDescription = preview.entry.name,
                  contentScale = ContentScale.Fit,
                  modifier = Modifier.fillMaxSize(),
                )
              }
            }
          }
        }

        "Text" -> {
          val text = preview.textContent.orEmpty()
          CodeViewerBox(
            content = text.ifEmpty { "(Empty file)" },
            onCopy = {
              val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              cm.setPrimaryClip(ClipData.newPlainText(preview.entry.name, text))
            },
          )
        }

        else -> {
          CodeViewerBox(
            content = preview.hexDump.ifEmpty { "(Empty file)" },
            onCopy = {
              val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              cm.setPrimaryClip(ClipData.newPlainText("${preview.entry.name}.hex", preview.hexDump))
            },
          )
        }
      }
    }
  }
}

@Composable
private fun CodeViewerBox(
  content: String,
  onCopy: () -> Unit,
) {
  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth().height(340.dp),
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      SelectionContainer {
        Text(
          text = content,
          style = MonoCodeStyle,
          color = MaterialTheme.colorScheme.onSurface,
          modifier =
            Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .horizontalScroll(rememberScrollState())
              .padding(14.dp),
        )
      }
      IconButton(
        onClick = onCopy,
        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(48.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.ContentCopy,
          contentDescription = "Copy content",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}
