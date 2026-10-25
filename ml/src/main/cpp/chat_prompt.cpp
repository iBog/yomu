#include "chat_prompt.h"

#include <exception>

std::string cat_prompt(const std::string &user_prompt) {
    return "<|user|>" + user_prompt + "</s><|assistant|>";
}

std::string chat_template_override(const std::string &embedded) {
    if (embedded.find("source_lang_code") == std::string::npos) return "";
    return "{{ bos_token }}{%- for message in messages -%}"
           "{{ '<start_of_turn>' + ('model' if message['role'] == 'assistant' else 'user') + '\\n' }}"
           "{{ message['content'] | trim }}{{ '<end_of_turn>\\n' }}"
           "{%- endfor -%}{%- if add_generation_prompt -%}{{ '<start_of_turn>model\\n' }}{%- endif -%}";
}

std::string format_chat_prompt(const common_chat_templates *templates,
                               const std::string &system_prompt,
                               const std::string &user_prompt) {
    if (!templates) return cat_prompt(user_prompt);

    // CAT-Translate carries its instruction in the user turn and was trained with no system
    // prompt (model card). Injecting one made the 0.8b echo/refuse the instruction instead of
    // translating (#68). So the system turn is opt-in, per catalog entry: Ministral is the first
    // that needs one, because with no system message its template injects Mistral's Le Chat
    // assistant prompt (#287).
    common_chat_templates_inputs inputs;
    if (!system_prompt.empty()) {
        common_chat_msg system;
        system.role = "system";
        system.content = system_prompt;
        inputs.messages.push_back(system);
    }

    common_chat_msg user;
    user.role = "user";
    user.content = user_prompt;
    inputs.messages.push_back(user);

    // The assistant cue is what tells the model to answer now rather than keep completing the user's turn.
    inputs.add_generation_prompt = true;
    // Explicit: llama.cpp defaults it to true, and a thinking model spends the token budget reasoning.
    inputs.enable_thinking = false;
    // Render the template as-is and skip building an output parser: Yomu decodes and parses its own
    // replies, under its own grammar (ADR-0013).
    inputs.force_pure_content = true;

    try {
        return common_chat_templates_apply(templates, inputs).prompt;
    } catch (const std::exception &) {
        return cat_prompt(user_prompt);
    }
}
