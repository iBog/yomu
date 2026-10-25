#include <cassert>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include "../../main/cpp/chat_prompt.h"
#include "llama.h"

// Built by ml/src/test/cpp/CMakeLists.txt, which points TEMPLATE_DIR at the fixtures: each is the
// chat_template a pinned catalog GGUF carries (read from the HF GGUF metadata, 2026-09-18; the
// Qwen3-4B-Instruct-2507 fixture 2026-09-20).

static std::string read_template(const char *name) {
    std::ifstream in(std::string(TEMPLATE_DIR) + "/" + name);
    assert(in);
    std::stringstream s;
    s << in.rdbuf();
    return s.str();
}

// What llama_jni.cpp produced before #281: llama.cpp's heuristic template guesser, falling back to CAT.
static std::string guesser_prompt(const std::string &tmpl, const std::string &user) {
    const llama_chat_message messages[] = {{"user", user.c_str()}};
    std::vector<char> buf(4096);
    int32_t n = llama_chat_apply_template(tmpl.c_str(), messages, 1, true, buf.data(), (int32_t)buf.size());
    if (n > (int32_t)buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl.c_str(), messages, 1, true, buf.data(), (int32_t)buf.size());
    }
    if (n > 0) return std::string(buf.data(), n);
    return cat_prompt(user);
}

static std::string jinja_prompt(const std::string &tmpl, const char *bos, const char *eos, const std::string &user,
                                const std::string &system = "") {
    auto templates = common_chat_templates_init(nullptr, tmpl, bos, eos);
    return format_chat_prompt(templates.get(), system, user);
}

int main() {
    const std::string user = "Translate the following Japanese into English.\n\n1: こんにちは\n2: 「行くぞ！」";

    // Neither shipped model is byte-identical to the guesser, and in both the guesser was the one off
    // the model's own template. Qwen2.5's template adds its default system turn when none is sent,
    // which the guesser's hardcoded ChatML skipped; otherwise identical.
    const std::string qwen25 = read_template("qwen2.5-1.5b-instruct.jinja");
    const std::string qwen_system = "<|im_start|>system\nYou are Qwen, created by Alibaba Cloud. You are a helpful assistant.<|im_end|>\n";
    assert(jinja_prompt(qwen25, "<|endoftext|>", "<|im_end|>", user) == qwen_system + guesser_prompt(qwen25, user));

    // CAT-Translate 0.8B and 1.4B ship the same template. The guesser took it for ChatGLM4 and sent
    // "<|user|>\n...<|assistant|>", dropping the </s> that closes CAT's user turn.
    const std::string cat = read_template("cat-translate.jinja");
    assert(guesser_prompt(cat, user) == "<|user|>\n" + user + "<|assistant|>");
    assert(jinja_prompt(cat, "<s>", "</s>", user) == cat_prompt(user));
    assert(cat_prompt(user) == "<|user|>" + user + "</s><|assistant|>");

    // Qwen3.5 thinks unless told not to; its template closes an empty think block when enable_thinking is false.
    // All four unsloth pins — 0.8B (6ab4614), 2B (f6d5376), 4B (e87f176), 9B (3885219) — carry this
    // template byte for byte, so one fixture covers the line.
    const std::string qwen35 = jinja_prompt(read_template("qwen3.5.jinja"), "", "<|im_end|>", user);
    const std::string thinking_off = "<|im_start|>assistant\n<think>\n\n</think>\n\n";
    assert(qwen35.size() > thinking_off.size());
    assert(qwen35.compare(qwen35.size() - thinking_off.size(), thinking_off.size(), thinking_off) == 0);
    assert(qwen35.find("<|im_start|>system") == std::string::npos);

    // Qwen3-4B-Instruct-2507 is non-thinking only: its template emits no think block at all.
    const std::string qwen3_2507 = jinja_prompt(read_template("qwen3-4b-instruct-2507.jinja"), "", "<|im_end|>", user);
    assert(qwen3_2507 == "<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n");

    // Ternary-Bonsai 4B and 8B (#301) ship one template (both Q2_0_g64 pins, read 2026-09-26). It
    // never reads enable_thinking and always closes an empty think block, so it renders Qwen3.5's
    // non-thinking prompt; the pre-#281 guesser took it for plain ChatML and dropped the block.
    const std::string bonsai_tmpl = read_template("ternary-bonsai.jinja");
    const std::string bonsai = jinja_prompt(bonsai_tmpl, "", "<|im_end|>", user);
    assert(bonsai == "<|im_start|>user\n" + user + "<|im_end|>\n" + thinking_off);
    assert(guesser_prompt(bonsai_tmpl, user).find("<think>") == std::string::npos);

    // Ministral 3 (#287). With no system message its template injects Mistral's Le Chat assistant
    // prompt; the catalog entry's own system message replaces it, rendered as [SYSTEM_PROMPT]…
    // before the user turn. The 3B and 8B templates differ only in the name inside that default.
    const std::string ministral = read_template("ministral-3-3b-instruct-2512.jinja");
    const std::string translator = "You are a manga translator. You translate Japanese into natural English.";
    const std::string with_system = jinja_prompt(ministral, "<s>", "</s>", user, translator);
    assert(with_system.find("[SYSTEM_PROMPT]" + translator + "[/SYSTEM_PROMPT]") != std::string::npos);
    assert(with_system.find("Le Chat") == std::string::npos);
    assert(with_system.find("[INST]" + user + "[/INST]") != std::string::npos);

    // An entry with no system message renders no system turn of Yomu's — and on this template that
    // is exactly why every Ministral entry carries one.
    assert(jinja_prompt(ministral, "<s>", "</s>", user).find("Le Chat") != std::string::npos);

    // Gemma 4's own turn format, which the pre-#281 guesser did not recognise at all (it fell back
    // to CAT). E2B and E4B ship the same template, byte for byte, in both pinned QAT GGUFs.
    const std::string gemma4 = jinja_prompt(read_template("gemma4.jinja"), "<bos>", "<eos>", user);
    assert(gemma4 == "<bos><|turn>user\n" + user + "<turn|>\n<|turn>model\n");
    // Thinking is on iff <|think|> opens the system turn, so with it off there is no system turn at all.
    assert(gemma4.find("<|think|>") == std::string::npos);
    assert(gemma4.find("<|turn>system") == std::string::npos);
    assert(guesser_prompt(read_template("gemma4.jinja"), user) == cat_prompt(user));

    // The Uncensored entries (#289) run on their base's settings, so each abliterated GGUF's own
    // template must render the base's prompt with thinking off (fixtures read 2026-09-23).
    // mradermacher's 4B (4a5daa6) and 9B (9f646d7) ship the official Qwen3.5 template, which thinks
    // unless enable_thinking is false; the 2B (f36848f) ships the inverse, thinking only when it is true.
    for (const char *name : {"huihui-qwen3.5-2b-abliterated.jinja", "huihui-qwen3.5-abliterated.jinja"}) {
        assert(jinja_prompt(read_template(name), "", "<|im_end|>", user) == qwen35);
    }
    // huihui-ai's E2B (e38a3cd) and E4B (bc37dec) GGUFs share one template that differs from Google's
    // by bytes but renders the same turns.
    assert(jinja_prompt(read_template("huihui-gemma4-abliterated.jinja"), "<bos>", "<eos>", user) == gemma4);

    // Hy-MT2 1.8B (#288). The heuristic guesser reads its <｜hy_begin▁of▁sentence｜> marker as
    // hunyuan-vl and renders the prompt before the role marker, with no assistant cue at all; the
    // Jinja engine renders Tencent's own format. The pinned GGUF (a0c709d) carries this template
    // byte for byte identical to chat_template.jinja in the base repo.
    const std::string hy_mt2_tmpl = read_template("hy-mt2-1.8b.jinja");
    const std::string hy_mt2 = jinja_prompt(hy_mt2_tmpl, "", "", user);
    assert(hy_mt2 == "<｜hy_begin▁of▁sentence｜><｜hy_User｜>" + user + "<｜hy_Assistant｜>");
    assert(guesser_prompt(hy_mt2_tmpl, user) != hy_mt2);

    // Tencent's card: "our models do not have a default system_prompt", so the entry sends none and
    // the template opens straight on the user turn.
    assert(hy_mt2.find("<｜hy_place▁holder▁no▁3｜>") == std::string::npos);

    // TranslateGemma 4B. Its template (mradermacher pin d37868f, read 2026-10-25) takes only a structured
    // user turn — source_lang_code, target_lang_code, text — and raises on a plain string, which would
    // drop the prompt to the CAT format. So it is swapped for Gemma 3's plain turns, and the bridge
    // writes the instruction that template would have rendered.
    const std::string translategemma_tmpl = read_template("translategemma-4b-it.jinja");
    assert(jinja_prompt(translategemma_tmpl, "<bos>", "<eos>", user) == cat_prompt(user));
    const std::string translategemma = jinja_prompt(chat_template_override(translategemma_tmpl), "<bos>", "<eos>", user);
    assert(translategemma == "<bos><start_of_turn>user\n" + user + "<end_of_turn>\n<start_of_turn>model\n");
    // Every other template is kept.
    assert(chat_template_override(read_template("gemma4.jinja")).empty());
    assert(chat_template_override(read_template("qwen3.5.jinja")).empty());

    // No template, or one that fails at render time, keeps the CAT format.
    assert(format_chat_prompt(nullptr, "", user) == cat_prompt(user));
    assert(format_chat_prompt(nullptr, translator, user) == cat_prompt(user));
    assert(jinja_prompt("{{ raise_exception('unsupported') }}", "", "", user) == cat_prompt(user));
}
