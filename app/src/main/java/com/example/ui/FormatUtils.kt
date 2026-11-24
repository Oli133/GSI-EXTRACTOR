package com.example.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

fun formatBytes(bytes: Long): String {
  if (bytes < 0L) return "Unknown"
  if (bytes < 1024L) return "$bytes B"
  val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(5)
  val unit = "KMGTPE"[exp - 1]
  val value = bytes / 1024.0.pow(exp.toDouble())
  return String.format(Locale.US, "%.1f %ciB", value, unit)
}

fun formatHexDump(bytes: ByteArray, maxBytes: Int = 4096): String {
  val limit = minOf(bytes.size, maxBytes)
  val sb = StringBuilder()
  var offset = 0
  while (offset < limit) {
    sb.append(String.format(Locale.US, "%06X  ", offset))
    val rowEnd = minOf(offset + 16, limit)
    for (i in 0 until 16) {
      if (offset + i < rowEnd) {
        sb.append(String.format(Locale.US, "%02X ", bytes[offset + i].toInt() and 0xFF))
      } else {
        sb.append("   ")
      }
      if (i == 7) sb.append(' ')
    }
    sb.append(" |")
    for (i in offset until rowEnd) {
      val c = bytes[i].toInt() and 0xFF
      sb.append(if (c in 32..126) c.toChar() else '.')
    }
    sb.append("|\n")
    offset += 16
  }
  if (bytes.size > maxBytes) {
    sb.append("\n... truncated (${formatBytes(bytes.size.toLong())} total)")
  }
  return sb.toString()
}

fun formatTimestamp(epochSeconds: Long): String {
  if (epochSeconds <= 0L) return "—"
  val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
  return sdf.format(Date(epochSeconds * 1000L))
}

fun isLikelyTextFile(name: String, sample: ByteArray): Boolean {
  val ext = name.substringAfterLast('.', "").lowercase()
  val textExts =
    setOf(
      "prop", "rc", "sh", "txt", "xml", "json", "te", "cil", "conf", "cfg",
      "fstab", "map", "csv", "md", "ini", "yaml", "yml", "properties", "policy",
      "contexts", "list", "info", "version", "log",
    )
  if (ext in textExts || name.endsWith("_contexts") || name.endsWith("build.prop")) {
    return true
  }
  if (sample.isEmpty()) return true
  val checkLen = minOf(sample.size, 512)
  var nonPrintable = 0
  for (i in 0 until checkLen) {
    val b = sample[i].toInt() and 0xFF
    if (b == 0) return false
    if (b < 9 || (b in 14..31)) nonPrintable++
  }
  return nonPrintable <= checkLen / 20
}

fun isImageFile(name: String): Boolean {
  val ext = name.substringAfterLast('.', "").lowercase()
  return ext in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
}
