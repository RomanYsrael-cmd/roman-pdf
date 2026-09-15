package com.romanysrael.romanpdf.core

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.romanysrael.romanpdf.data.InkPoint
import com.romanysrael.romanpdf.data.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** On-demand recognition adapter. Missing models never prevent reading or writing. */
class HandwritingRecognitionManager(private val context: Context) {
    private val model: DigitalInkRecognitionModel? by lazy {
        runCatching {
            DigitalInkRecognitionModel.builder(DigitalInkRecognitionModelIdentifier.EN_US).build()
        }.getOrNull()
    }

    suspend fun isEnglishModelDownloaded(): Boolean = withContext(Dispatchers.IO) {
        val selectedModel = model ?: return@withContext false
        runCatching {
            Tasks.await(RemoteModelManager.getInstance().isModelDownloaded(selectedModel))
        }.getOrDefault(false)
    }

    suspend fun downloadEnglishModel(): Boolean = withContext(Dispatchers.IO) {
        val selectedModel = model ?: return@withContext false
        runCatching {
            Tasks.await(
                RemoteModelManager.getInstance().download(
                    selectedModel,
                    DownloadConditions.Builder().build()
                )
            )
            true
        }.getOrDefault(false)
    }

    suspend fun recognize(strokes: List<Stroke>): String? = withContext(Dispatchers.Default) {
        if (strokes.isEmpty() || !isEnglishModelDownloaded()) return@withContext null
        val selectedModel = model ?: return@withContext null
        val recognizer = DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(selectedModel).build()
        )
        try {
            val inkBuilder = Ink.builder()
            strokes.forEach { stroke ->
                val strokeBuilder = Ink.Stroke.builder()
                stroke.points.forEach { point ->
                    strokeBuilder.addPoint(
                        Ink.Point.create(point.x, point.y, point.time)
                    )
                }
                inkBuilder.addStroke(strokeBuilder.build())
            }
            val result = Tasks.await(recognizer.recognize(inkBuilder.build()))
            result.candidates.firstOrNull()?.text
        } finally {
            recognizer.close()
        }
    }
}
