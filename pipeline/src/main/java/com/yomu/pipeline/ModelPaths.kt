package com.yomu.pipeline

import com.yomu.core.SourceLanguage
import com.yomu.core.TargetLanguage

/**
 * The reading models' files. For a Japanese [ocrSource] the OCR paths are manga-ocr's encoder,
 * decoder and vocabulary; for any other, [ocrEncoderPath] is the PP-OCR line recognizer,
 * [ocrVocabPath] its `inference.yml`, and [ocrDecoderPath] is unused.
 */
data class ModelPaths(
    val bubbleDetectionPath: String,
    val ocrEncoderPath: String,
    val ocrDecoderPath: String,
    val ocrVocabPath: String,
    val ocrSource: SourceLanguage = SourceLanguage.JAPANESE
)

/** What a page is read in and what it is translated into. */
internal data class PageLanguages(val source: SourceLanguage, val target: TargetLanguage)
