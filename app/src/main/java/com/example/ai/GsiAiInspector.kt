package com.example.ai

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

data class AiStreamChunk(
  val thoughtSummaryDelta: String,
  val answerDelta: String,
)

class GsiAiInspector {
  private val model by lazy {
    val config = generationConfig {
      thinkingConfig = thinkingConfig {
        thinkingLevel = ThinkingLevel.HIGH
        includeThoughts = true
      }
    }
    Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
      modelName = "gemini-4-carbon",
      generationConfig = config,
      systemInstruction =
        content {
          text(
            "You are an Android Systems & GSI Firmware Expert. Analyze Android Generic System Images (GSI), " +
              "EXT4/EROFS partitions, super.img dynamic partitions, build.prop properties, init.rc scripts, " +
              "fstab mounts, SELinux policies, and ZIP archive contents. Provide precise, actionable technical diagnostics."
          )
        },
    )
  }

  fun analyzeStream(prompt: String): Flow<AiStreamChunk> = flow {
    withTimeout(60_000L) {
      model.generateContentStream(prompt).collect { chunk ->
        val thought = chunk.thoughtSummary.orEmpty()
        val answer = chunk.text.orEmpty()
        if (thought.isNotEmpty() || answer.isNotEmpty()) {
          emit(AiStreamChunk(thoughtSummaryDelta = thought, answerDelta = answer))
        }
      }
    }
  }
}
