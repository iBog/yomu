package com.yomu.app.service
import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.StatFs
import com.yomu.app.db.ModelDao
import com.yomu.app.db.entities.ModelEntity
import com.yomu.app.db.entities.ModelStatus
import com.yomu.app.db.entities.ModelType
import com.yomu.app.translation.LlmModelCatalog
import com.yomu.core.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDao: ModelDao,
    private val okHttpClient: OkHttpClient
) {
    data class DownloadProgress(
        val modelId: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val percentage: Int
    )

    internal data class AdditionalFile(
        val fileName: String,
        val url: String,
        val checksum: String,
        val size: Long
    )

    internal data class DownloadTask(
        val file: File,
        val url: String,
        val checksum: String,
        val size: Long
    )

    fun getAllModels(): Flow<List<ModelEntity>> = modelDao.getAllModels()

    suspend fun getModel(id: String): ModelEntity? = modelDao.getModelById(id)

    suspend fun getModelsByType(type: ModelType): List<ModelEntity> = modelDao.getModelsByType(type.name)

    companion object {
        /** Every model Yomu ships. [refreshModelList] upserts these rows; plain data so JVM tests can read it. */
        internal val REGISTRY = listOf(
            ModelEntity(
                id = Constants.BUBBLE_DETECTION_MODEL_ID,
                name = "Bubble Detection (YOLO26 Nano Manga)",
                type = ModelType.DETECTION,
                fileName = Constants.BUBBLE_DETECTION_MODEL,
                fileSize = 6_070_000L,
                downloadUrl = "https://huggingface.co/Kiuyha/Manga-Bubble-YOLO/resolve/fb646500455e8a8a3a807fd27b855c8e4fc63766/onnx/yolo26n.onnx",
                checksum = "b45c2e12cf0c3c1d2abfbbb9123c9f96f040f2ac36a0842382ecd9d859c851c7",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = true
            ),
            // RT-DETR-v2 trained on manga, webtoons and Western comics: also finds text in rectangular captions.
            ModelEntity(
                id = Constants.COMIC_TEXT_DETECTOR_MODEL_ID,
                name = "Comic Text Detector (RT-DETR v4-s)",
                type = ModelType.DETECTION,
                fileName = Constants.COMIC_TEXT_DETECTOR_MODEL,
                fileSize = 11_120_765L,
                downloadUrl = "https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/16e8a622f91fabc6b5b65c96d32d1183f8843546/detector-v4-s_int8.onnx",
                checksum = "5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.MANGA_OCR_MODEL_ID,
                name = "MangaOCR Japanese (Encoder + Decoder)",
                type = ModelType.OCR,
                fileName = Constants.OCR_ENCODER_MODEL,
                fileSize = 22_279_960L,
                downloadUrl = "https://huggingface.co/artsaraiva/manga-ocr-2025-onnx-past/resolve/151898756903f03a2682fa6be4507dfeff71fb7e/encoder_model.onnx",
                checksum = "2398ca8799bcc30a6240f2f309b3f9a1b697b5192afa9231338d2f69c3f866ef",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = true
            ),
            // Official PaddlePaddle ONNX exports, Apache-2.0. Each recognizer's dictionary is in its inference.yml.
            ModelEntity(
                id = Constants.PPOCR_CHINESE_MODEL_ID,
                name = "PP-OCRv5 Chinese and English",
                type = ModelType.OCR,
                fileName = Constants.PPOCR_CHINESE_MODEL,
                fileSize = 16_534_782L,
                downloadUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/resolve/ed152b8b495f84de93cda5709d768548a9127622/inference.onnx",
                checksum = "da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.PPOCR_KOREAN_MODEL_ID,
                name = "PP-OCRv5 Korean",
                type = ModelType.OCR,
                fileName = Constants.PPOCR_KOREAN_MODEL,
                fileSize = 13_418_787L,
                downloadUrl = "https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx/resolve/5c6f574b8e2230adf4287b33e736d71b9fabd28e/inference.onnx",
                checksum = "92f0b7785e64fc9090106a241cf4c1eb97472824558272751b88a2a4476d3a08",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.PPOCR_LATIN_MODEL_ID,
                name = "PP-OCRv5 Latin script (English, Spanish, French, German…)",
                type = ModelType.OCR,
                fileName = Constants.PPOCR_LATIN_MODEL,
                fileSize = 8_042_023L,
                downloadUrl = "https://huggingface.co/PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx/resolve/89d3a50e2c27e2e7cceeab0e944c25c807d5db4f/inference.onnx",
                checksum = "7888113072263cb471b93f66dd5e2ad70548dc526fa1ace760d0d973dd121498",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.PPOCR_CYRILLIC_MODEL_ID,
                name = "PP-OCRv5 Cyrillic (Russian, Ukrainian, Serbian…)",
                type = ModelType.OCR,
                fileName = Constants.PPOCR_CYRILLIC_MODEL,
                fileSize = 8_048_799L,
                downloadUrl = "https://huggingface.co/PaddlePaddle/cyrillic_PP-OCRv5_mobile_rec_onnx/resolve/2cef88145434beb8afa9dd82d77d799eb1ad7b29/inference.onnx",
                checksum = "5371ee1ddaa7983cc62d0818d99e982b6804638c85e4f960d59a574094e172e5",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.CAT_TRANSLATION_MODEL_ID,
                name = "CAT-Translate 0.8B (Q4_K_M)",
                type = ModelType.LLM,
                fileName = Constants.TRANSLATION_MODEL_4BIT,
                fileSize = Constants.TRANSLATION_MODEL_4BIT_SIZE,
                downloadUrl = "https://huggingface.co/mradermacher/CAT-Translate-0.8b-GGUF/resolve/834d0624185e964856a1b3c43eb5e114c9c41df5/CAT-Translate-0.8b.Q4_K_M.gguf",
                checksum = "6de8e40b687eb2248727c8ad208c54af8c82ad52b415901859d5fcd7fd65bb4c",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            // URL, exact size, and SHA-256 are the pinned Q4_K_M revisions (checksum is the HuggingFace
            // LFS oid, which is the sha256 of the file content — the same digest computeChecksum verifies).
            ModelEntity(
                id = Constants.CAT_TRANSLATION_14B_MODEL_ID,
                name = "CAT-Translate 1.4B (Q4_K_M)",
                type = ModelType.LLM,
                fileName = Constants.CAT_TRANSLATION_14B_MODEL,
                fileSize = Constants.CAT_TRANSLATION_14B_SIZE,
                downloadUrl = "https://huggingface.co/mradermacher/CAT-Translate-1.4b-GGUF/resolve/2eb35647e57b5981c14611e67b9ad205329b498d/CAT-Translate-1.4b.Q4_K_M.gguf",
                checksum = "332371e7aa764c6dde6df70956062e839aed69ad3db28e1af118aa99b6f63467",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.QWEN25_15B_MODEL_ID,
                name = "Qwen2.5 1.5B Instruct (Q4_K_M)",
                type = ModelType.LLM,
                fileName = Constants.QWEN25_15B_MODEL,
                fileSize = Constants.QWEN25_15B_SIZE,
                downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/9eadc66189c7641e1ddd226b8267a9119b2ce2d4/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
                checksum = "1adf0b11065d8ad2e8123ea110d1ec956dab4ab038eab665614adba04b6c3370",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.QWEN35_08B_MODEL_ID,
                name = "Qwen3.5 0.8B (Q4_K_M)",
                type = ModelType.LLM,
                fileName = Constants.QWEN35_08B_MODEL,
                fileSize = Constants.QWEN35_08B_SIZE,
                downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/6ab461498e2023f6e3c1baea90a8f0fe38ab64d0/Qwen3.5-0.8B-Q4_K_M.gguf",
                checksum = "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.QWEN35_2B_MODEL_ID,
                name = "Qwen3.5 2B (Q4_K_M)",
                type = ModelType.LLM,
                fileName = Constants.QWEN35_2B_MODEL,
                fileSize = Constants.QWEN35_2B_SIZE,
                downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/f6d5376be1edb4d416d56da11e5397a961aca8ae/Qwen3.5-2B-Q4_K_M.gguf",
                checksum = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.QWEN35_4B_MODEL_ID,
                name = "Qwen3.5 4B (Q4_K_M)",
                type = ModelType.LLM,
                fileName = Constants.QWEN35_4B_MODEL,
                fileSize = Constants.QWEN35_4B_SIZE,
                downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/e87f176479d0855a907a41277aca2f8ee7a09523/Qwen3.5-4B-Q4_K_M.gguf",
                checksum = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.QWEN35_9B_MODEL_ID,
                name = "Qwen3.5 9B (Q4_K_M)",
                type = ModelType.LLM,
                fileName = Constants.QWEN35_9B_MODEL,
                fileSize = Constants.QWEN35_9B_SIZE,
                downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/resolve/3885219b6810b007914f3a7950a8d1b469d598a5/Qwen3.5-9B-Q4_K_M.gguf",
                checksum = "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.QWEN3_4B_2507_MODEL_ID,
                name = "Qwen3 4B Instruct 2507 (Q4_K_M)",
                type = ModelType.LLM,
                fileName = Constants.QWEN3_4B_2507_MODEL,
                fileSize = Constants.QWEN3_4B_2507_SIZE,
                downloadUrl = "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/a06e946bb6b655725eafa393f4a9745d460374c9/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                checksum = "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.MINISTRAL3_3B_MODEL_ID,
                name = "Ministral 3 3B Instruct 2512 (Q4_K_M)",
                type = ModelType.LLM,
                fileName = Constants.MINISTRAL3_3B_MODEL,
                fileSize = Constants.MINISTRAL3_3B_SIZE,
                downloadUrl = "https://huggingface.co/mistralai/Ministral-3-3B-Instruct-2512-GGUF/resolve/eb599d408350ea2bb60452cb86be7c7b2fc28227/Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
                checksum = "9ed150d4367e68df0ac8e1540f6ddc65b42d0ee26378329d1ecbca60f93fc5f8",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.MINISTRAL3_8B_MODEL_ID,
                name = "Ministral 3 8B Instruct 2512 (Q4_K_M)",
                type = ModelType.LLM,
                fileName = Constants.MINISTRAL3_8B_MODEL,
                fileSize = Constants.MINISTRAL3_8B_SIZE,
                downloadUrl = "https://huggingface.co/mistralai/Ministral-3-8B-Instruct-2512-GGUF/resolve/0102285ad796bd99af90f58de616092e5630e970/Ministral-3-8B-Instruct-2512-Q4_K_M.gguf",
                checksum = "33e7a72cf5e6e2cfc2f2847075acc013d68bba023e35310cef86b5cf8fdca761",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            // Google publishes its own QAT GGUFs for Gemma 4, so both pins are first-party.
            ModelEntity(
                id = Constants.GEMMA4_E2B_MODEL_ID,
                name = "Gemma 4 E2B (QAT Q4_0)",
                type = ModelType.LLM,
                fileName = Constants.GEMMA4_E2B_MODEL,
                fileSize = Constants.GEMMA4_E2B_SIZE,
                downloadUrl = "https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/675cff42a74c774d6cb76f76d8eacb49b48c9b93/gemma-4-E2B_q4_0-it.gguf",
                checksum = "fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.GEMMA4_E4B_MODEL_ID,
                name = "Gemma 4 E4B (QAT Q4_0)",
                type = ModelType.LLM,
                fileName = Constants.GEMMA4_E4B_MODEL,
                fileSize = Constants.GEMMA4_E4B_SIZE,
                downloadUrl = "https://huggingface.co/google/gemma-4-E4B-it-qat-q4_0-gguf/resolve/4b4a2c1d584be7264f87aac328a1bc739ce81b6c/gemma-4-E4B_q4_0-it.gguf",
                checksum = "676c35070db6dbe52f93e9c864ee0fba4eddea94b9c875d9cb10daff453fbaee",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.HY_MT2_18B_MODEL_ID,
                name = "Hy-MT2 1.8B (Q4_K_M)",
                type = ModelType.LLM,
                fileName = Constants.HY_MT2_18B_MODEL,
                fileSize = Constants.HY_MT2_18B_SIZE,
                downloadUrl = "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/a0c709d9fac510f2c807aa3af52872340dc37a4a/Hy-MT2-1.8B-Q4_K_M.gguf",
                checksum = "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            // Google publishes no TranslateGemma GGUF; mradermacher's static quant of google/translategemma-4b-it.
            ModelEntity(
                id = Constants.TRANSLATEGEMMA_4B_MODEL_ID,
                name = "TranslateGemma 4B (Q4_K_M)",
                type = ModelType.LLM,
                fileName = Constants.TRANSLATEGEMMA_4B_MODEL,
                fileSize = Constants.TRANSLATEGEMMA_4B_SIZE,
                downloadUrl = "https://huggingface.co/mradermacher/translategemma-4b-it-GGUF/resolve/d37868f759d2f817a43d28dd80cf9f982ffa62fd/translategemma-4b-it.Q4_K_M.gguf",
                checksum = "81200d03e843d2ec1ece6eeafe7d13cb6e5211e1fcd336ade55790b683a08330",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.TERNARY_BONSAI_4B_MODEL_ID,
                name = "Ternary-Bonsai 4B (Q2_0)",
                type = ModelType.LLM,
                fileName = Constants.TERNARY_BONSAI_4B_MODEL,
                fileSize = Constants.TERNARY_BONSAI_4B_SIZE,
                downloadUrl = "https://huggingface.co/prism-ml/Ternary-Bonsai-4B-gguf/resolve/a3eb42bafe873f9686bc97486c43b72ef7d75ec8/Ternary-Bonsai-4B-Q2_0_g64.gguf",
                checksum = "9d968b04a3c9a794897bcc744c8072fb6a061c0e42efd03c989401ddf8baef0c",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.TERNARY_BONSAI_8B_MODEL_ID,
                name = "Ternary-Bonsai 8B (Q2_0)",
                type = ModelType.LLM,
                fileName = Constants.TERNARY_BONSAI_8B_MODEL,
                fileSize = Constants.TERNARY_BONSAI_8B_SIZE,
                downloadUrl = "https://huggingface.co/prism-ml/Ternary-Bonsai-8B-gguf/resolve/c2aefbeb4b24469cd11579c3384b990404c17a30/Ternary-Bonsai-8B-Q2_0_g64.gguf",
                checksum = "e17b298d84ee78797916ae5c2ecc8211469cc65cccfe3080cd9a9bb503fbc55e",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            // huihui-ai abliterations: its own GGUFs for the Gemma 4 QAT pair, mradermacher's static
            // quants for Qwen3.5, where huihui-ai publishes none.
            ModelEntity(
                id = Constants.QWEN35_2B_UNCENSORED_MODEL_ID,
                name = "Qwen3.5 2B Uncensored (Q4_K_M)",
                type = ModelType.LLM,
                fileName = Constants.QWEN35_2B_UNCENSORED_MODEL,
                fileSize = Constants.QWEN35_2B_UNCENSORED_SIZE,
                downloadUrl = "https://huggingface.co/mradermacher/Huihui-Qwen3.5-2B-abliterated-GGUF/resolve/f36848fead3fdda244cf60195c46993d23183d4c/Huihui-Qwen3.5-2B-abliterated.Q4_K_M.gguf",
                checksum = "aa25eea787afe56a097268f7ed3460cb623e1901d2e89cd2b654cabb42f80636",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.QWEN35_4B_UNCENSORED_MODEL_ID,
                name = "Qwen3.5 4B Uncensored (Q4_K_M)",
                type = ModelType.LLM,
                fileName = Constants.QWEN35_4B_UNCENSORED_MODEL,
                fileSize = Constants.QWEN35_4B_UNCENSORED_SIZE,
                downloadUrl = "https://huggingface.co/mradermacher/Huihui-Qwen3.5-4B-abliterated-GGUF/resolve/4a5daa6fbefca5fe822dc65fcb95cc4576fa9720/Huihui-Qwen3.5-4B-abliterated.Q4_K_M.gguf",
                checksum = "423f10b6ec2d99c3378143d7cd3b80eb4887b3ed92103103ac59173b404f4f7c",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.QWEN35_9B_UNCENSORED_MODEL_ID,
                name = "Qwen3.5 9B Uncensored (Q4_K_M)",
                type = ModelType.LLM,
                fileName = Constants.QWEN35_9B_UNCENSORED_MODEL,
                fileSize = Constants.QWEN35_9B_UNCENSORED_SIZE,
                downloadUrl = "https://huggingface.co/mradermacher/Huihui-Qwen3.5-9B-abliterated-GGUF/resolve/9f646d7eda193ddf2348134f3bff3d49eed7a2c6/Huihui-Qwen3.5-9B-abliterated.Q4_K_M.gguf",
                checksum = "ea1858ef4dc4b648b8dbb44612962a0333e945060dd0545ac0f28d7c4416e4b3",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.GEMMA4_E2B_UNCENSORED_MODEL_ID,
                name = "Gemma 4 E2B Uncensored (QAT Q4_K_M)",
                type = ModelType.LLM,
                fileName = Constants.GEMMA4_E2B_UNCENSORED_MODEL,
                fileSize = Constants.GEMMA4_E2B_UNCENSORED_SIZE,
                downloadUrl = "https://huggingface.co/huihui-ai/Huihui-gemma-4-E2B-it-qat-q4_0-unquantized-abliterated-GGUF/resolve/e38a3cdcf55879424c971d0961ea70b82870b989/Huihui-gemma-4-E2B-it-qat-q4_0-unquantized-abliterated-Q4_K.gguf",
                checksum = "6bc1f421ba870b01a2efbb6904a28bda0ae3ccde57b18eb5e9203c3db05effe9",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            ),
            ModelEntity(
                id = Constants.GEMMA4_E4B_UNCENSORED_MODEL_ID,
                name = "Gemma 4 E4B Uncensored (QAT Q4_K_M)",
                type = ModelType.LLM,
                fileName = Constants.GEMMA4_E4B_UNCENSORED_MODEL,
                fileSize = Constants.GEMMA4_E4B_UNCENSORED_SIZE,
                downloadUrl = "https://huggingface.co/huihui-ai/Huihui-gemma-4-E4B-it-qat-q4_0-unquantized-abliterated-GGUF/resolve/bc37dec4db35ea0fcad97be7a8c6b3f6a499616b/Huihui-gemma-4-E4B-it-qat-q4_0-unquantized-abliterated-Q4_K.gguf",
                checksum = "64434f2da081f912729e5c4732def7303eb5244d3fee493b9675bc4e9af52d4c",
                status = ModelStatus.AVAILABLE,
                version = "1.0",
                isRequired = false
            )
        )

        /** The curated model each slot holds when nothing (or an unknown id) is selected. */
        val SLOT_DEFAULTS: Map<ModelType, String> = mapOf(
            ModelType.DETECTION to Constants.BUBBLE_DETECTION_MODEL_ID,
            ModelType.OCR to Constants.MANGA_OCR_MODEL_ID,
            ModelType.LLM to LlmModelCatalog.DEFAULT.id
        )

        /**
         * Extra files downloaded with a model, in the order its consumer reads them from
         * [modelFiles]: MangaOCR's decoder, then its vocabulary; a PP-OCR recognizer's inference.yml.
         */
        internal fun additionalFiles(modelId: String): List<AdditionalFile> = when (modelId) {
            Constants.MANGA_OCR_MODEL_ID -> listOf(
                AdditionalFile(
                    fileName = Constants.OCR_DECODER_MODEL,
                    url = "https://huggingface.co/artsaraiva/manga-ocr-2025-onnx-past/resolve/151898756903f03a2682fa6be4507dfeff71fb7e/decoder_model_merged.onnx",
                    checksum = "ac0ac6f48fa89bbfff1ab8d53ffcaa76839a72b145fe20a70923dc59b0b9349e",
                    size = 118_131_111L
                ),
                AdditionalFile(
                    fileName = Constants.OCR_VOCAB_FILE,
                    url = "https://huggingface.co/artsaraiva/manga-ocr-2025-onnx-past/resolve/151898756903f03a2682fa6be4507dfeff71fb7e/vocab.txt",
                    checksum = "344fbb6b8bf18c57839e924e2c9365434697e0227fac00b88bb4899b78aa594d",
                    size = 24_072L
                )
            )
            Constants.PPOCR_CHINESE_MODEL_ID -> ppOcrConfig(
                Constants.PPOCR_CHINESE_MODEL,
                "PP-OCRv5_mobile_rec_onnx/resolve/ed152b8b495f84de93cda5709d768548a9127622",
                "5dfeb2777f6d0db8177d8128a8acfcf6e6276dc4ac73ea3bf0dc06d6a5e85d8e",
                148_345L
            )
            Constants.PPOCR_KOREAN_MODEL_ID -> ppOcrConfig(
                Constants.PPOCR_KOREAN_MODEL,
                "korean_PP-OCRv5_mobile_rec_onnx/resolve/5c6f574b8e2230adf4287b33e736d71b9fabd28e",
                "f757fa1c40e99edcf27e9cce879b93eb2a51fa46f5ef39095689b8c37dd75998",
                96_039L
            )
            Constants.PPOCR_LATIN_MODEL_ID -> ppOcrConfig(
                Constants.PPOCR_LATIN_MODEL,
                "latin_PP-OCRv5_mobile_rec_onnx/resolve/89d3a50e2c27e2e7cceeab0e944c25c807d5db4f",
                "0bbe984570f597af3638e50bdf2e8276f3ab26a61966096538b3b0d1849f5c84",
                6_817L
            )
            Constants.PPOCR_CYRILLIC_MODEL_ID -> ppOcrConfig(
                Constants.PPOCR_CYRILLIC_MODEL,
                "cyrillic_PP-OCRv5_mobile_rec_onnx/resolve/2cef88145434beb8afa9dd82d77d799eb1ad7b29",
                "5c76cc91fa98410178a09f498db10050d0ec1634a660053d3005ab7be581f501",
                6_991L
            )
            else -> emptyList()
        }

        private fun ppOcrConfig(modelFile: String, revision: String, checksum: String, size: Long) = listOf(
            AdditionalFile(
                fileName = modelFile.removeSuffix(".onnx") + ".yml",
                url = "https://huggingface.co/PaddlePaddle/$revision/inference.yml",
                checksum = checksum,
                size = size
            )
        )
    }

    suspend fun refreshModelList() = withContext(Dispatchers.IO) {
        val registryIds = REGISTRY.map { it.id }.toSet()
        for (stale in modelDao.getAllModels().first().filterNot { it.id in registryIds }) {
            deleteFiles(modelFiles(stale))
            modelDao.deleteModel(stale)
        }
        for (model in REGISTRY) {
            val existing = modelDao.getModelById(model.id)
            if (existing == null) {
                modelDao.insertModel(model)
            } else {
                // A READY row whose files left without going through deleteModel — benchmark
                // staging, storage pressure, a restore that missed filesDir — otherwise keeps
                // being selected and translates nothing in silence (#308).
                val lostItsFiles = existing.status == ModelStatus.READY &&
                    modelFiles(model).any { !it.exists() }
                // A model's files share one pinned revision, so a new pin changes the main file's URL.
                // The files on disk are the old pin's and must not be mixed with the new ones (#393).
                val repinned = existing.status == ModelStatus.READY &&
                    (existing.downloadUrl != model.downloadUrl || existing.checksum != model.checksum)
                if (repinned) deleteFiles(modelFiles(model))
                val outdated = lostItsFiles || repinned
                modelDao.insertModel(
                    existing.copy(
                        name = model.name,
                        type = model.type,
                        fileName = model.fileName,
                        fileSize = model.fileSize,
                        downloadUrl = model.downloadUrl,
                        checksum = model.checksum,
                        version = model.version,
                        isRequired = model.isRequired,
                        status = if (outdated) ModelStatus.AVAILABLE else existing.status,
                        downloadProgress = if (outdated) 0 else existing.downloadProgress
                    )
                )
            }
        }
    }
    private fun getModelDir(type: ModelType): File = when (type) {
        ModelType.DETECTION, ModelType.OCR -> File(context.filesDir, "${Constants.MODELS_DIR}/${Constants.VISION_MODELS_DIR}")
        ModelType.LLM -> File(context.filesDir, "${Constants.MODELS_DIR}/${Constants.LLM_MODELS_DIR}")
    }
    fun deviceTotalMemBytes(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }

    fun isOnMeteredNetwork(): Boolean =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

    private fun hasEnoughSpace(requiredBytes: Long): Boolean {
        val dir = File(context.filesDir, Constants.MODELS_DIR)
        dir.mkdirs()
        val stat = StatFs(dir.absolutePath)
        return stat.availableBytes > requiredBytes * 1.1
    }
    suspend fun downloadModel(
        modelId: String,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val model = modelDao.getModelById(modelId) ?: return@withContext false
        val additionalFiles = additionalFiles(model.id)
        val totalRequiredBytes = model.fileSize + additionalFiles.sumOf { it.size }

        if (!hasEnoughSpace(totalRequiredBytes)) {
            return@withContext false
        }

        modelDao.updateModelStatus(modelId, ModelStatus.DOWNLOADING)

        val outputDir = getModelDir(model.type)
        outputDir.mkdirs()

        val tasks = listOf(
            DownloadTask(
                file = File(outputDir, model.fileName),
                url = model.downloadUrl,
                checksum = model.checksum,
                size = model.fileSize
            )
        ) + additionalFiles.map {
            DownloadTask(
                file = File(outputDir, it.fileName),
                url = it.url,
                checksum = it.checksum,
                size = it.size
            )
        }

        downloadTasks(modelId, tasks, onProgress)
    }

    internal suspend fun downloadTasks(
        modelId: String,
        tasks: List<DownloadTask>,
        onProgress: (DownloadProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val totalBytes = tasks.sumOf { it.size }
            var bytesDownloaded = 0L

            for (task in tasks) {
                val success = downloadFile(task.url, task.file) { bytesRead ->
                    bytesDownloaded += bytesRead
                    if (totalBytes > 0) {
                        val progress = ((bytesDownloaded * 100) / totalBytes).toInt()
                        modelDao.updateDownloadProgress(modelId, progress)
                        onProgress(
                            DownloadProgress(
                                modelId = modelId,
                                bytesDownloaded = bytesDownloaded,
                                totalBytes = totalBytes,
                                percentage = progress
                            )
                        )
                    }
                }

                if (!success) {
                    deleteFiles(tasks.map { it.file })
                    modelDao.updateModelStatus(modelId, ModelStatus.ERROR)
                    return@withContext false
                }

                if (task.checksum.isNotEmpty()) {
                    val fileChecksum = computeChecksum(task.file)
                    if (fileChecksum != task.checksum) {
                        deleteFiles(tasks.map { it.file })
                        modelDao.updateModelStatus(modelId, ModelStatus.ERROR)
                        return@withContext false
                    }
                }
            }

            modelDao.updateModelStatus(modelId, ModelStatus.READY)
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                deleteFiles(tasks.map { it.file })
                modelDao.updateModelStatus(modelId, ModelStatus.AVAILABLE)
                modelDao.updateDownloadProgress(modelId, 0)
            }
            throw e
        } catch (e: Exception) {
            modelDao.updateModelStatus(modelId, ModelStatus.ERROR)
            false
        }
    }

    private suspend fun downloadFile(
        url: String,
        outputFile: File,
        onBytesRead: suspend (Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            return@withContext false
        }

        val body = response.body ?: return@withContext false

        body.byteStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    ensureActive()
                    output.write(buffer, 0, bytesRead)
                    onBytesRead(bytesRead.toLong())
                }
            }
        }

        true
    }

    private fun computeChecksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deleteFiles(files: List<File>) {
        for (file in files) {
            if (file.exists()) {
                file.delete()
            }
        }
    }

    suspend fun deleteModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        val model = modelDao.getModelById(modelId) ?: return@withContext false

        deleteFiles(modelFiles(model))

        modelDao.updateModelStatus(modelId, ModelStatus.AVAILABLE)
        modelDao.updateDownloadProgress(modelId, 0)
        true
    }

    /** The model's main file followed by its [additionalFiles], whether or not they are downloaded. */
    fun modelFiles(model: ModelEntity): List<File> {
        val modelDir = getModelDir(model.type)
        return listOf(File(modelDir, model.fileName)) + additionalFiles(model.id).map { File(modelDir, it.fileName) }
    }

    fun getModelFile(model: ModelEntity): File? {
        val modelFile = File(getModelDir(model.type), model.fileName)
        return if (modelFile.exists()) modelFile else null
    }

    fun getTotalModelSize(): Long {
        val modelsDir = File(context.filesDir, Constants.MODELS_DIR)
        if (!modelsDir.exists()) return 0
        return modelsDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }
}
