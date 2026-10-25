package com.yomu.app.translation

import com.yomu.app.db.entities.ModelType
import com.yomu.app.service.ModelManager
import com.yomu.core.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {

    private val registry = ModelManager.REGISTRY

    @Test
    fun `model ids are unique`() {
        val duplicates = registry.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue("duplicate ids: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `every http row carries a sha256 checksum`() {
        val sha256 = Regex("[0-9a-f]{64}")
        registry.filter { it.downloadUrl.startsWith("http") }.forEach {
            assertTrue("${it.id}: '${it.checksum}'", sha256.matches(it.checksum))
        }
    }

    @Test
    fun `every http url is pinned to a revision sha`() {
        val pinned = Regex("https://huggingface\\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/.+")
        val urls = registry.map { it.downloadUrl } +
            registry.flatMap { ModelManager.additionalFiles(it.id) }.map { it.url }
        urls.filter { it.startsWith("http") }.forEach {
            assertTrue(it, pinned.matches(it))
        }
    }

    @Test
    fun `llm rows are exactly the catalog entries`() {
        val llmRowIds = registry.filter { it.type == ModelType.LLM }.map { it.id }.toSet()
        assertEquals(LlmModelCatalog.ALL.map { it.id }.toSet(), llmRowIds)
    }

    @Test
    fun `every Qwen entry downloads the pinned unsloth Q4_K_M`() {
        // No first-party GGUF exists for either line (Qwen's own repos return 401), so the research
        // addendum's host rule lands on unsloth. Repo, commit, bytes and sha256 as pinned there.
        val pins = mapOf(
            Constants.QWEN35_08B_MODEL_ID to Triple(
                "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/6ab461498e2023f6e3c1baea90a8f0fe38ab64d0/Qwen3.5-0.8B-Q4_K_M.gguf",
                "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517",
                532_517_120L
            ),
            Constants.QWEN35_2B_MODEL_ID to Triple(
                "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/f6d5376be1edb4d416d56da11e5397a961aca8ae/Qwen3.5-2B-Q4_K_M.gguf",
                "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
                1_280_835_840L
            ),
            Constants.QWEN35_4B_MODEL_ID to Triple(
                "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/e87f176479d0855a907a41277aca2f8ee7a09523/Qwen3.5-4B-Q4_K_M.gguf",
                "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
                2_740_937_888L
            ),
            Constants.QWEN35_9B_MODEL_ID to Triple(
                "https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/resolve/3885219b6810b007914f3a7950a8d1b469d598a5/Qwen3.5-9B-Q4_K_M.gguf",
                "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8",
                5_680_522_464L
            ),
            Constants.QWEN3_4B_2507_MODEL_ID to Triple(
                "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/a06e946bb6b655725eafa393f4a9745d460374c9/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597",
                2_497_281_120L
            )
        )

        pins.forEach { (id, pin) ->
            val row = registry.single { it.id == id }
            val (url, checksum, size) = pin
            assertEquals(id, url, row.downloadUrl)
            assertEquals(id, checksum, row.checksum)
            assertEquals(id, size, row.fileSize)
        }
    }

    @Test
    fun `Ministral 3 downloads the pinned first-party Q4_K_M`() {
        val threeB = registry.single { it.id == Constants.MINISTRAL3_3B_MODEL_ID }
        assertEquals(
            "https://huggingface.co/mistralai/Ministral-3-3B-Instruct-2512-GGUF/resolve/" +
                "eb599d408350ea2bb60452cb86be7c7b2fc28227/Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
            threeB.downloadUrl
        )
        assertEquals("9ed150d4367e68df0ac8e1540f6ddc65b42d0ee26378329d1ecbca60f93fc5f8", threeB.checksum)
        assertEquals(2_147_023_008L, threeB.fileSize)

        val eightB = registry.single { it.id == Constants.MINISTRAL3_8B_MODEL_ID }
        assertEquals(
            "https://huggingface.co/mistralai/Ministral-3-8B-Instruct-2512-GGUF/resolve/" +
                "0102285ad796bd99af90f58de616092e5630e970/Ministral-3-8B-Instruct-2512-Q4_K_M.gguf",
            eightB.downloadUrl
        )
        assertEquals("33e7a72cf5e6e2cfc2f2847075acc013d68bba023e35310cef86b5cf8fdca761", eightB.checksum)
        assertEquals(5_198_911_904L, eightB.fileSize)
    }

    @Test
    fun `Gemma 4 downloads Google's own QAT Q4_0 GGUFs`() {
        val e2b = registry.single { it.id == Constants.GEMMA4_E2B_MODEL_ID }
        val e4b = registry.single { it.id == Constants.GEMMA4_E4B_MODEL_ID }

        assertEquals(
            "https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/675cff42a74c774d6cb76f76d8eacb49b48c9b93/gemma-4-E2B_q4_0-it.gguf",
            e2b.downloadUrl
        )
        assertEquals("fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634", e2b.checksum)
        assertEquals(3_349_516_256L, e2b.fileSize)

        assertEquals(
            "https://huggingface.co/google/gemma-4-E4B-it-qat-q4_0-gguf/resolve/4b4a2c1d584be7264f87aac328a1bc739ce81b6c/gemma-4-E4B_q4_0-it.gguf",
            e4b.downloadUrl
        )
        assertEquals("676c35070db6dbe52f93e9c864ee0fba4eddea94b9c875d9cb10daff453fbaee", e4b.checksum)
        assertEquals(5_154_941_280L, e4b.fileSize)
    }

    @Test
    fun `Hy-MT2 1_8B downloads the pinned first-party Q4_K_M`() {
        val row = registry.single { it.id == Constants.HY_MT2_18B_MODEL_ID }

        assertEquals(
            "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/" +
                "a0c709d9fac510f2c807aa3af52872340dc37a4a/Hy-MT2-1.8B-Q4_K_M.gguf",
            row.downloadUrl
        )
        assertEquals("dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699", row.checksum)
        assertEquals(1_133_080_448L, row.fileSize)
    }

    @Test
    fun `TranslateGemma 4B downloads the pinned mradermacher Q4_K_M`() {
        val row = registry.single { it.id == Constants.TRANSLATEGEMMA_4B_MODEL_ID }

        assertEquals(
            "https://huggingface.co/mradermacher/translategemma-4b-it-GGUF/resolve/" +
                "d37868f759d2f817a43d28dd80cf9f982ffa62fd/translategemma-4b-it.Q4_K_M.gguf",
            row.downloadUrl
        )
        assertEquals("81200d03e843d2ec1ece6eeafe7d13cb6e5211e1fcd336ade55790b683a08330", row.checksum)
        assertEquals(2_489_909_760L, row.fileSize)
        assertEquals(Constants.TRANSLATEGEMMA_4B_MODEL, row.fileName)
    }

    @Test
    fun `Ternary-Bonsai downloads the group-64 Q2_0 files the pinned llama_cpp loads`() {
        val fourB = registry.single { it.id == Constants.TERNARY_BONSAI_4B_MODEL_ID }
        val eightB = registry.single { it.id == Constants.TERNARY_BONSAI_8B_MODEL_ID }

        assertEquals(
            "https://huggingface.co/prism-ml/Ternary-Bonsai-4B-gguf/resolve/" +
                "a3eb42bafe873f9686bc97486c43b72ef7d75ec8/Ternary-Bonsai-4B-Q2_0_g64.gguf",
            fourB.downloadUrl
        )
        assertEquals("9d968b04a3c9a794897bcc744c8072fb6a061c0e42efd03c989401ddf8baef0c", fourB.checksum)
        assertEquals(1_137_806_656L, fourB.fileSize)

        assertEquals(
            "https://huggingface.co/prism-ml/Ternary-Bonsai-8B-gguf/resolve/" +
                "c2aefbeb4b24469cd11579c3384b990404c17a30/Ternary-Bonsai-8B-Q2_0_g64.gguf",
            eightB.downloadUrl
        )
        assertEquals("e17b298d84ee78797916ae5c2ecc8211469cc65cccfe3080cd9a9bb503fbc55e", eightB.checksum)
        assertEquals(2_310_125_920L, eightB.fileSize)
    }

    @Test
    fun `every uncensored entry downloads the pinned huihui-ai abliteration`() {
        // huihui-ai publishes its own GGUF only for the Gemma 4 QAT abliterations; the Qwen3.5 ones
        // come from mradermacher's static quants. Repo, commit, bytes and sha256 as the research
        // addendum pins them.
        val pins = mapOf(
            Constants.QWEN35_2B_UNCENSORED_MODEL_ID to Triple(
                "https://huggingface.co/mradermacher/Huihui-Qwen3.5-2B-abliterated-GGUF/resolve/" +
                    "f36848fead3fdda244cf60195c46993d23183d4c/Huihui-Qwen3.5-2B-abliterated.Q4_K_M.gguf",
                "aa25eea787afe56a097268f7ed3460cb623e1901d2e89cd2b654cabb42f80636",
                1_270_809_024L
            ),
            Constants.QWEN35_4B_UNCENSORED_MODEL_ID to Triple(
                "https://huggingface.co/mradermacher/Huihui-Qwen3.5-4B-abliterated-GGUF/resolve/" +
                    "4a5daa6fbefca5fe822dc65fcb95cc4576fa9720/Huihui-Qwen3.5-4B-abliterated.Q4_K_M.gguf",
                "423f10b6ec2d99c3378143d7cd3b80eb4887b3ed92103103ac59173b404f4f7c",
                2_707_514_688L
            ),
            Constants.QWEN35_9B_UNCENSORED_MODEL_ID to Triple(
                "https://huggingface.co/mradermacher/Huihui-Qwen3.5-9B-abliterated-GGUF/resolve/" +
                    "9f646d7eda193ddf2348134f3bff3d49eed7a2c6/Huihui-Qwen3.5-9B-abliterated.Q4_K_M.gguf",
                "ea1858ef4dc4b648b8dbb44612962a0333e945060dd0545ac0f28d7c4416e4b3",
                5_627_045_248L
            ),
            Constants.GEMMA4_E2B_UNCENSORED_MODEL_ID to Triple(
                "https://huggingface.co/huihui-ai/Huihui-gemma-4-E2B-it-qat-q4_0-unquantized-abliterated-GGUF/resolve/" +
                    "e38a3cdcf55879424c971d0961ea70b82870b989/Huihui-gemma-4-E2B-it-qat-q4_0-unquantized-abliterated-Q4_K.gguf",
                "6bc1f421ba870b01a2efbb6904a28bda0ae3ccde57b18eb5e9203c3db05effe9",
                3_416_118_240L
            ),
            Constants.GEMMA4_E4B_UNCENSORED_MODEL_ID to Triple(
                "https://huggingface.co/huihui-ai/Huihui-gemma-4-E4B-it-qat-q4_0-unquantized-abliterated-GGUF/resolve/" +
                    "bc37dec4db35ea0fcad97be7a8c6b3f6a499616b/Huihui-gemma-4-E4B-it-qat-q4_0-unquantized-abliterated-Q4_K.gguf",
                "64434f2da081f912729e5c4732def7303eb5244d3fee493b9675bc4e9af52d4c",
                5_302_272_352L
            )
        )

        pins.forEach { (id, pin) ->
            val row = registry.single { it.id == id }
            val (url, checksum, size) = pin
            assertEquals(id, url, row.downloadUrl)
            assertEquals(id, checksum, row.checksum)
            assertEquals(id, size, row.fileSize)
        }
    }

    @Test
    fun `every slot has exactly one curated default of its own type`() {
        assertEquals(ModelType.entries.toSet(), ModelManager.SLOT_DEFAULTS.keys)
        ModelManager.SLOT_DEFAULTS.forEach { (type, id) ->
            assertEquals(type, registry.single { it.id == id }.type)
        }
        assertEquals(LlmModelCatalog.DEFAULT.id, ModelManager.SLOT_DEFAULTS[ModelType.LLM])
    }

    @Test
    fun `every row downloads over http`() {
        val nonHttp = registry.map { it.downloadUrl }.filterNot { it.startsWith("http") }
        assertTrue("non-http urls: $nonHttp", nonHttp.isEmpty())
    }
}
