package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.archive.core.ArchiveEntry
import com.example.archive.core.EntryType
import com.example.archive.core.GsiTelemetry
import com.example.ui.BrowserLayerState
import com.example.ui.EntryFilter
import com.example.ui.SortMode
import com.example.ui.formatBytes

@Composable
fun BrowserScreen(
  layer: BrowserLayerState,
  entries: List<ArchiveEntry>,
  searchQuery: String,
  filter: EntryFilter,
  sortMode: SortMode,
  selectedIds: Set<String>,
  onSearchQueryChange: (String) -> Unit,
  onFilterChange: (EntryFilter) -> Unit,
  onSortModeChange: (SortMode) -> Unit,
  onEntryClick: (ArchiveEntry) -> Unit,
  onEntryLongClick: (ArchiveEntry) -> Unit,
  onNavigateBreadcrumb: (String) -> Unit,
  onSelectAll: () -> Unit,
  onClearSelection: () -> Unit,
  onExtractSelected: () -> Unit,
  onAnalyzeGsi: (GsiTelemetry) -> Unit,
  onBack: () -> Unit,
  contentPadding: PaddingValues,
) {
  BackHandler { onBack() }

  val isMultiSelecting = selectedIds.isNotEmpty()

  Box(
    modifier = Modifier.fillMaxSize().padding(contentPadding),
    contentAlignment = Alignment.TopCenter,
  ) {
    Column(
      modifier = Modifier.fillMaxSize().widthIn(max = 820.dp),
    ) {
      // Search & Sort Row
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(
          value = searchQuery,
          onValueChange = onSearchQueryChange,
          placeholder = {
            Text(
              "Search paths or filenames...",
              style = MaterialTheme.typography.bodyMedium,
            )
          },
          leadingIcon = {
            Icon(
              imageVector = Icons.Filled.Search,
              contentDescription = stringResource(R.string.cd_search),
            )
          },
          trailingIcon = {
            if (searchQuery.isNotEmpty()) {
              IconButton(onClick = { onSearchQueryChange("") }) {
                Icon(
                  imageVector = Icons.Filled.Clear,
                  contentDescription = stringResource(R.string.cd_clear_search),
                )
              }
            }
          },
          singleLine = true,
          shape = MaterialTheme.shapes.medium,
          modifier = Modifier.weight(1f).testTag("browser_search_input"),
        )
      }

      // Breadcrumbs
      if (searchQuery.isBlank()) {
        BreadcrumbBar(
          currentPath = layer.currentPath,
          onNavigate = onNavigateBreadcrumb,
        )
      } else {
        Text(
          text = "Search results for \"$searchQuery\" (${entries.size})",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
      }

      // Filter & Sort Chips
      LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(EntryFilter.entries) { f ->
          FilterChip(
            selected = filter == f,
            onClick = { onFilterChange(f) },
            label = { Text(f.label) },
          )
        }
        item {
          Spacer(modifier = Modifier.width(4.dp))
          val nextSort =
            when (sortMode) {
              SortMode.NAME -> SortMode.SIZE
              SortMode.SIZE -> SortMode.TYPE
              SortMode.TYPE -> SortMode.NAME
            }
          FilterChip(
            selected = false,
            onClick = { onSortModeChange(nextSort) },
            label = { Text("Sort: ${sortMode.label}") },
          )
        }
      }

      // Multi-select action bar
      AnimatedVisibility(visible = isMultiSelecting) {
        Surface(
          color = MaterialTheme.colorScheme.primaryContainer,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
          shape = MaterialTheme.shapes.medium,
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "${selectedIds.size} selected",
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSelectAll, modifier = Modifier.size(48.dp)) {
              Icon(
                imageVector = Icons.Filled.SelectAll,
                contentDescription = "Select all",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
            TextButton(onClick = onClearSelection) {
              Text("Clear", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Button(
              onClick = onExtractSelected,
              modifier = Modifier.testTag("extract_selected_button"),
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.primary,
                  contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
              Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("Extract")
            }
          }
        }
      }

      // File list
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // Optional GSI Telemetry banner at root directory
        val telemetry = layer.container.telemetry
        if (telemetry != null && layer.currentPath.isEmpty() && searchQuery.isBlank()) {
          item(key = "gsi_telemetry_card") {
            GsiTelemetryCard(
              telemetry = telemetry,
              formatBadge = layer.container.format.badge,
              onAnalyzeGsi = { onAnalyzeGsi(telemetry) },
            )
          }
        }

        if (entries.isEmpty()) {
          item {
            Surface(
              shape = MaterialTheme.shapes.medium,
              color = MaterialTheme.colorScheme.surfaceContainerLow,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
              Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Text(
                  text = "No matching items",
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = "Try clearing your search or switching the category filter.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        } else {
          items(entries, key = { it.id }) { entry ->
            EntryRow(
              entry = entry,
              showFullPath = searchQuery.isNotBlank(),
              isSelected = entry.id in selectedIds,
              isMultiSelectMode = isMultiSelecting,
              onClick = { onEntryClick(entry) },
              onLongClick = { onEntryLongClick(entry) },
              onToggleSelect = { onEntryLongClick(entry) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun BreadcrumbBar(
  currentPath: String,
  onNavigate: (String) -> Unit,
) {
  val segments = if (currentPath.isEmpty()) emptyList() else currentPath.split('/')
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(
      shape = RoundedCornerShape(8.dp),
      color =
        if (currentPath.isEmpty()) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
      modifier =
        Modifier.clip(RoundedCornerShape(8.dp))
          .clickable { onNavigate("") },
    ) {
      Text(
        text = "/root",
        style = MaterialTheme.typography.labelMedium,
        color =
          if (currentPath.isEmpty()) MaterialTheme.colorScheme.onPrimaryContainer
          else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      )
    }

    var built = ""
    segments.forEachIndexed { idx, seg ->
      built = if (built.isEmpty()) seg else "$built/$seg"
      val targetPath = built
      val isLast = idx == segments.lastIndex
      Icon(
        imageVector = Icons.Filled.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
      )
      Surface(
        shape = RoundedCornerShape(8.dp),
        color =
          if (isLast) MaterialTheme.colorScheme.primaryContainer
          else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
          Modifier.clip(RoundedCornerShape(8.dp))
            .clickable { onNavigate(targetPath) },
      ) {
        Text(
          text = seg,
          style = MaterialTheme.typography.labelMedium,
          color =
            if (isLast) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
      }
    }
  }
}

@Composable
private fun GsiTelemetryCard(
  telemetry: GsiTelemetry,
  formatBadge: String,
  onAnalyzeGsi: () -> Unit,
) {
  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceContainer,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)),
    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.Android,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(20.dp),
          )
          Text(
            text = "Android GSI Telemetry",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
          Text(
            text = formatBadge,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        TelemetryChip("Android", telemetry.androidVersion ?: "?", Modifier.weight(1f))
        TelemetryChip("SDK", telemetry.sdkVersion ?: "?", Modifier.weight(1f))
        TelemetryChip("VNDK", telemetry.vndkVersion ?: "N/A", Modifier.weight(1f))
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        TelemetryChip("ABI", telemetry.abis ?: "Unknown", Modifier.weight(1.4f))
        TelemetryChip("Patch", telemetry.securityPatch ?: "Unknown", Modifier.weight(1f))
      }

      Button(
        onClick = onAnalyzeGsi,
        modifier = Modifier.fillMaxWidth().testTag("ai_gsi_report_button"),
        shape = MaterialTheme.shapes.small,
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
      ) {
        Icon(
          imageVector = Icons.Filled.AutoAwesome,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("AI Compatibility Report (Gemini High Thinking)")
      }
    }
  }
}

@Composable
private fun TelemetryChip(label: String, value: String, modifier: Modifier = Modifier) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier = modifier,
  ) {
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = value,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
  entry: ArchiveEntry,
  showFullPath: Boolean,
  isSelected: Boolean,
  isMultiSelectMode: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  onToggleSelect: () -> Unit,
) {
  val icon =
    when {
      entry.isDirectory -> Icons.Filled.Folder
      entry.type == EntryType.SYMLINK -> Icons.Filled.Link
      entry.isNestedImage -> Icons.Filled.Storage
      entry.extension == "apk" || entry.extension == "apex" -> Icons.Filled.Android
      entry.extension in setOf("zip", "xz", "gz") -> Icons.Filled.FolderZip
      entry.extension in setOf("png", "jpg", "jpeg", "webp") -> Icons.Filled.Image
      entry.extension in setOf("xml", "json", "sh", "rc", "te", "cil") -> Icons.Filled.Code
      entry.extension in setOf("prop", "txt", "conf", "fstab") -> Icons.Filled.Description
      else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

  val iconTint =
    when {
      entry.isDirectory -> MaterialTheme.colorScheme.primary
      entry.isNestedImage -> MaterialTheme.colorScheme.secondary
      entry.type == EntryType.SYMLINK -> MaterialTheme.colorScheme.tertiary
      entry.extension == "apk" -> MaterialTheme.colorScheme.secondary
      else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

  Surface(
    shape = MaterialTheme.shapes.medium,
    color =
      if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
      else MaterialTheme.colorScheme.surfaceContainer,
    border =
      BorderStroke(
        1.dp,
        if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
      ),
    modifier =
      Modifier.fillMaxWidth()
        .clip(MaterialTheme.shapes.medium)
        .combinedClickable(
          onClick = { if (isMultiSelectMode) onToggleSelect() else onClick() },
          onLongClick = onLongClick,
        )
        .testTag("entry_row_${entry.name}"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(40.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp),
          )
        }
      }

      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = if (showFullPath) entry.id else entry.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
          if (entry.isNestedImage) {
            Surface(
              shape = RoundedCornerShape(4.dp),
              color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
              Text(
                text = "MOUNTABLE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }
          }
        }

        val detailText =
          buildString {
            append(entry.permissionsString())
            if (!entry.isDirectory) {
              append(" • ")
              append(formatBytes(entry.sizeBytes))
            }
            if (entry.symlinkTarget != null) {
              append(" → ")
              append(entry.symlinkTarget)
            }
          }
        Text(
          text = detailText,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      if (isMultiSelectMode) {
        Checkbox(
          checked = isSelected,
          onCheckedChange = { onToggleSelect() },
        )
      } else {
        IconButton(
          onClick = onToggleSelect,
          modifier = Modifier.size(48.dp),
        ) {
          Icon(
            imageVector =
              if (isSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
            contentDescription = "Select ${entry.name}",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.size(20.dp),
          )
        }
      }
    }
  }
}
