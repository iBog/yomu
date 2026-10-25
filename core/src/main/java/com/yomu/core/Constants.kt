package com.yomu.core

object Constants {
    const val APP_NAME = "Yomu"
    const val PACKAGE_NAME = "com.yomu.app"
    
    const val MODELS_DIR = "models"
    const val VISION_MODELS_DIR = "vision"
    const val LLM_MODELS_DIR = "llm"
    const val BUBBLE_DETECTION_MODEL_ID = "bubble_detection_v1"
    const val MANGA_OCR_MODEL_ID = "manga_ocr_v1"
    const val PPOCR_CHINESE_MODEL_ID = "ppocr_v5_chinese_v1"
    const val PPOCR_KOREAN_MODEL_ID = "ppocr_v5_korean_v1"
    const val PPOCR_LATIN_MODEL_ID = "ppocr_v5_latin_v1"
    const val PPOCR_CYRILLIC_MODEL_ID = "ppocr_v5_cyrillic_v1"
    const val CAT_TRANSLATION_MODEL_ID = "cat_translate_0.8b_v1"
    const val CAT_TRANSLATION_14B_MODEL_ID = "cat_translate_1.4b_v1"
    const val QWEN25_15B_MODEL_ID = "qwen25_1.5b_instruct_v1"
    const val QWEN35_08B_MODEL_ID = "qwen35_0.8b_v1"
    const val QWEN35_2B_MODEL_ID = "qwen35_2b_v1"
    const val QWEN35_4B_MODEL_ID = "qwen35_4b_v1"
    const val QWEN35_9B_MODEL_ID = "qwen35_9b_v1"
    const val QWEN3_4B_2507_MODEL_ID = "qwen3_4b_instruct_2507_v1"
    const val MINISTRAL3_3B_MODEL_ID = "ministral3_3b_v1"
    const val MINISTRAL3_8B_MODEL_ID = "ministral3_8b_v1"
    const val GEMMA4_E2B_MODEL_ID = "gemma4_e2b_v1"
    const val GEMMA4_E4B_MODEL_ID = "gemma4_e4b_v1"
    const val HY_MT2_18B_MODEL_ID = "hy_mt2_1.8b_v1"
    const val TERNARY_BONSAI_4B_MODEL_ID = "ternary_bonsai_4b_v1"
    const val TERNARY_BONSAI_8B_MODEL_ID = "ternary_bonsai_8b_v1"
    const val QWEN35_2B_UNCENSORED_MODEL_ID = "qwen35_2b_uncensored_v1"
    const val QWEN35_4B_UNCENSORED_MODEL_ID = "qwen35_4b_uncensored_v1"
    const val QWEN35_9B_UNCENSORED_MODEL_ID = "qwen35_9b_uncensored_v1"
    const val GEMMA4_E2B_UNCENSORED_MODEL_ID = "gemma4_e2b_uncensored_v1"
    const val GEMMA4_E4B_UNCENSORED_MODEL_ID = "gemma4_e4b_uncensored_v1"

    const val BUBBLE_DETECTION_MODEL = "bubble_detection.onnx"
    const val OCR_ENCODER_MODEL = "manga_ocr_encoder.onnx"
    const val OCR_DECODER_MODEL = "manga_ocr_decoder.onnx"
    const val OCR_VOCAB_FILE = "vocab.txt"
    const val PPOCR_CHINESE_MODEL = "ppocr_v5_chinese_rec.onnx"
    const val PPOCR_KOREAN_MODEL = "ppocr_v5_korean_rec.onnx"
    const val PPOCR_LATIN_MODEL = "ppocr_v5_latin_rec.onnx"
    const val PPOCR_CYRILLIC_MODEL = "ppocr_v5_cyrillic_rec.onnx"
    const val TRANSLATION_MODEL_4BIT = "cat_translate_0.8b_q4_k_m.gguf"
    const val TRANSLATION_MODEL_8BIT = "cat_translate_0.8b_q8_0.gguf"

    // #84 challenger GGUF file names, staged under models/llm/ alongside the 0.8b (Q4_K_M tier).
    const val CAT_TRANSLATION_14B_MODEL = "cat_translate_1.4b_q4_k_m.gguf"
    // imatrix quant of the 1.4b winner: same size/RAM/latency, typically better quality per bit.
    const val CAT_TRANSLATION_14B_I1_MODEL = "cat_translate_1.4b_i1_q4_k_m.gguf"
    // 7B CAT sibling: too big for the 8GB reference device (like Hunyuan-7B); emulator/high-RAM only.
    const val CAT_TRANSLATION_7B_MODEL = "cat_translate_7b_q4_k_m.gguf"
    // Same-range alternatives to CAT-1.4b.
    const val TRANSLATEGEMMA_4B_MODEL = "translategemma_4b_q4_k_m.gguf"
    const val QWEN25_15B_MODEL = "qwen25_1.5b_instruct_q4_k_m.gguf"
    const val QWEN35_08B_MODEL = "qwen35_0.8b_q4_k_m.gguf"
    const val QWEN35_2B_MODEL = "qwen35_2b_q4_k_m.gguf"
    const val QWEN35_4B_MODEL = "qwen35_4b_q4_k_m.gguf"
    const val QWEN35_9B_MODEL = "qwen35_9b_q4_k_m.gguf"
    const val QWEN3_4B_2507_MODEL = "qwen3_4b_instruct_2507_q4_k_m.gguf"
    const val MINISTRAL3_3B_MODEL = "ministral3_3b_instruct_2512_q4_k_m.gguf"
    const val MINISTRAL3_8B_MODEL = "ministral3_8b_instruct_2512_q4_k_m.gguf"
    // Google's own QAT quants, so Q4_0 rather than the Q4_K_M tier the other entries use.
    const val GEMMA4_E2B_MODEL = "gemma4_e2b_it_q4_0.gguf"
    const val GEMMA4_E4B_MODEL = "gemma4_e4b_it_q4_0.gguf"
    const val HY_MT2_18B_MODEL = "hy_mt2_1.8b_q4_k_m.gguf"
    // Group-64 Q2_0: the cards' recommended group-128 file does not load in the pinned llama.cpp (#299).
    const val TERNARY_BONSAI_4B_MODEL = "ternary_bonsai_4b_q2_0_g64.gguf"
    const val TERNARY_BONSAI_8B_MODEL = "ternary_bonsai_8b_q2_0_g64.gguf"
    // huihui-ai abliterations. The Gemma 4 ones are Q4_K_M quants of the QAT weights, not Q4_0.
    const val QWEN35_2B_UNCENSORED_MODEL = "qwen35_2b_uncensored_q4_k_m.gguf"
    const val QWEN35_4B_UNCENSORED_MODEL = "qwen35_4b_uncensored_q4_k_m.gguf"
    const val QWEN35_9B_UNCENSORED_MODEL = "qwen35_9b_uncensored_q4_k_m.gguf"
    const val GEMMA4_E2B_UNCENSORED_MODEL = "gemma4_e2b_it_uncensored_q4_k_m.gguf"
    const val GEMMA4_E4B_UNCENSORED_MODEL = "gemma4_e4b_it_uncensored_q4_k_m.gguf"
    const val GEMMA2_2B_MODEL = "gemma2_2b_it_q4_k_m.gguf"
    const val QWEN3_MODEL = "qwen3_4b_q4_k_m.gguf"
    // Q3_K_M, not Q4: the 7B Q4 (4.6GB) OOM-kills on load on an 8GB device (#84 bake-off). Q3_K_M
    // (3.8GB) fits; the quant drop is the price of measuring a 7B on-device at all.
    const val HUNYUAN_MT_MODEL = "hunyuan_mt_7b_q3_k_m.gguf"
    
    // GGUF file sizes (bytes) = the HuggingFace LFS-reported size of each pinned Q4_K_M revision.
    // Single source of truth: both the ModelManager download registry (fileSize) and the LlmModelCatalog
    // device-fit gate (sizeBytes) read these, so the two can never drift out of sync (#90).
    const val QWEN25_15B_SIZE = 986_048_768L
    const val QWEN35_08B_SIZE = 532_517_120L
    const val QWEN35_2B_SIZE = 1_280_835_840L
    const val QWEN35_4B_SIZE = 2_740_937_888L
    const val QWEN35_9B_SIZE = 5_680_522_464L
    const val QWEN3_4B_2507_SIZE = 2_497_281_120L
    const val MINISTRAL3_3B_SIZE = 2_147_023_008L
    const val MINISTRAL3_8B_SIZE = 5_198_911_904L
    const val GEMMA4_E2B_SIZE = 3_349_516_256L
    const val GEMMA4_E4B_SIZE = 5_154_941_280L
    const val HY_MT2_18B_SIZE = 1_133_080_448L
    const val TERNARY_BONSAI_4B_SIZE = 1_137_806_656L
    const val TERNARY_BONSAI_8B_SIZE = 2_310_125_920L
    const val QWEN35_2B_UNCENSORED_SIZE = 1_270_809_024L
    const val QWEN35_4B_UNCENSORED_SIZE = 2_707_514_688L
    const val QWEN35_9B_UNCENSORED_SIZE = 5_627_045_248L
    const val GEMMA4_E2B_UNCENSORED_SIZE = 3_416_118_240L
    const val GEMMA4_E4B_UNCENSORED_SIZE = 5_302_272_352L
    const val TRANSLATION_MODEL_4BIT_SIZE = 528_205_184L
    const val CAT_TRANSLATION_14B_SIZE = 931_179_904L
    const val GEMMA2_2B_SIZE = 1_708_582_752L
    const val TRANSLATEGEMMA_4B_SIZE = 2_489_909_760L

    
    const val DATABASE_NAME = "yomu_db"
    const val DATABASE_VERSION = 2
    
    const val OVERLAY_CHANNEL_ID = "overlay_service"
    const val OVERLAY_NOTIFICATION_ID = 1
    
    const val OVERLAY_PERMISSION_REQUEST_CODE = 1001
    const val MEDIA_PROJECTION_PERMISSION_REQUEST_CODE = 1002
    
    const val PREF_TRANSLATION_MODE = "translation_mode"
    const val PREF_TARGET_LANGUAGE = "target_language"
    const val PREF_SOURCE_LANGUAGE = "source_language"
    const val PREF_THEME = "theme"
    const val PREF_AUTO_DETECT = "auto_detect"
    const val PREF_BUTTON_POSITION_X = "button_position_x"
    const val PREF_BUTTON_POSITION_Y = "button_position_y"
    // Which curated LLM occupies the translation slot (ADR-0009). Stores a
    // ModelEntity id; nothing picked keeps the default (Qwen2.5-1.5B, ADR-0010).
    const val PREF_LLM_MODEL = "llm_model"
    // Which curated model occupies the detection and OCR slots. Each stores a ModelEntity id;
    // nothing picked (or an unknown id) keeps the slot's default in ModelManager.SLOT_DEFAULTS.
    const val PREF_DETECTION_MODEL = "detection_model"
    const val PREF_OCR_MODEL = "ocr_model"
    const val PREF_FONT_SIZE_SCALE = "font_size_scale"
    
    const val DEFAULT_SOURCE_LANGUAGE = "ja"
    const val DEFAULT_AUTO_DETECT = true
    const val DEFAULT_FONT_SIZE_SCALE = 1.0f
}
