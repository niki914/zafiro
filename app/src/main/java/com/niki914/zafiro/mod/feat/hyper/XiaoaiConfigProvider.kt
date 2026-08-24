package com.niki914.zafiro.mod.feat.hyper

import com.niki914.zafiro.mod.feat.BaseConfigProvider
import com.niki914.zafiro.mod.feat.HookTarget
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** XiaoAi 云配置提供者，按 action 路径暴露各 Hook 所需的类名、方法名、参数索引等配置项。 */
object XiaoaiConfigProvider : BaseConfigProvider() {

    object CaptureInput {
        private const val P = "actions.capture_input"
        val hookTarget: HookTarget?
            get() = parseHookTarget("$P.target")
        val dialogIdArgIndex: Int
            get() = getInt("$P.business.dialog_id_arg_index")
        val queryArgIndex: Int
            get() = getInt("$P.business.query_arg_index")
    }

    object CaptureResponseTarget {
        private const val P = "actions.capture_response_target"
        val hookTarget: HookTarget?
            get() = parseHookTarget("$P.target")
        val targetDialogIdGetter: String
            get() = getString("$P.business.target_dialog_id_getter")
        val instructionDialogIdGetter: String
            get() = getString("$P.business.instruction_dialog_id_getter")
        val optionalHasValueMethod: String
            get() = getString("$P.business.optional_has_value_method")
        val optionalValueGetter: String
            get() = getString("$P.business.optional_value_getter")
    }

    object ResetConversationSignal {
        private const val P = "actions.reset_conversation_signal"
        val hookTarget: HookTarget?
            get() = parseHookTarget("$P.target")
    }

    object FloatScreenDetach {
        private const val P = "actions.float_screen_detach"
        val detachTarget: HookTarget?
            get() = parseHookTarget("$P.target")
        val resumeTarget: HookTarget?
            get() = parseHookTarget("$P.business.resume_target")
    }

    object BlockNativeTextStream {
        private const val P = "actions.block_native_text_stream"
        val hookTarget: HookTarget?
            get() = parseHookTarget("$P.target")
        val targetDialogIdGetter: String
            get() = getString("$P.business.target_dialog_id_getter")
        val instructionFullName: String
            get() = getString("$P.business.instruction_full_name")
        val instructionFullNameGetter: String
            get() = getString("$P.business.instruction_full_name_getter")
        val instructionDialogIdGetter: String
            get() = getString("$P.business.instruction_dialog_id_getter")
        val optionalHasValueMethod: String
            get() = getString("$P.business.optional_has_value_method")
        val optionalValueGetter: String
            get() = getString("$P.business.optional_value_getter")
    }

    object BlockNativeTtsStream {
        private const val P = "actions.block_native_tts_stream"
        val hookTarget: HookTarget?
            get() = parseHookTarget("$P.target")
        val targetDialogIdGetter: String
            get() = getString("$P.business.target_dialog_id_getter")
        val instructionFullName: String
            get() = getString("$P.business.instruction_full_name")
        val instructionFullNameGetter: String
            get() = getString("$P.business.instruction_full_name_getter")
        val instructionDialogIdGetter: String
            get() = getString("$P.business.instruction_dialog_id_getter")
        val optionalHasValueMethod: String
            get() = getString("$P.business.optional_has_value_method")
        val optionalValueGetter: String
            get() = getString("$P.business.optional_value_getter")
    }

    object BlockNativeInstructionWhitelist {
        private const val P = "actions.block_native_instruction_whitelist"
        val hookTarget: HookTarget?
            get() = parseHookTarget("$P.target")
        val targetDialogIdGetter: String
            get() = getString("$P.business.target_dialog_id_getter")
        val instructionFullNameGetter: String
            get() = getString("$P.business.instruction_full_name_getter")
        val instructionDialogIdGetter: String
            get() = getString("$P.business.instruction_dialog_id_getter")
        val optionalHasValueMethod: String
            get() = getString("$P.business.optional_has_value_method")
        val optionalValueGetter: String
            get() = getString("$P.business.optional_value_getter")
        val allowedInstructionFullNames: Set<String>
            get() = getList("$P.business.allowed_instruction_full_names")
                .mapNotNull { it.jsonPrimitive.contentOrNull }
                .filter { it.isNotBlank() }
                .toSet()
    }

    object BlockNativeTtsPlayback {
        private const val P = "actions.block_native_tts_playback"
        val hookTarget: HookTarget?
            get() = parseHookTarget("$P.target")
        val targetDialogIdGetter: String
            get() = getString("$P.business.target_dialog_id_getter")
    }

    object RenderTextStreamCard {
        private const val P = "actions.render_text_stream_card"
        val hookTarget: HookTarget?
            get() = parseHookTarget("$P.target")
        val instructionNamespace: String
            get() = getString("$P.business.instruction_namespace")
        val instructionName: String
            get() = getString("$P.business.instruction_name")
        val instructionClass: String
            get() = getString("$P.business.instruction_class")
        val instructionConstructorParamTypes: List<String>
            get() = getList("$P.business.instruction_constructor_param_types")
                .mapNotNull { it.jsonPrimitive.contentOrNull }
        val instructionHeaderClass: String
            get() = getString("$P.business.instruction_header_class")
        val instructionHeaderConstructorParamTypes: List<String>
            get() = getList("$P.business.instruction_header_constructor_param_types")
                .mapNotNull { it.jsonPrimitive.contentOrNull }
        val textStreamPayloadClass: String
            get() = getString("$P.business.text_stream_payload_class")
        val textStreamPayloadConstructorParamTypes: List<String>
            get() = getList("$P.business.text_stream_payload_constructor_param_types")
                .mapNotNull { it.jsonPrimitive.contentOrNull }
        val instructionHeaderIdSetter: String
            get() = getString("$P.business.instruction_header_id_setter")
        val instructionHeaderDialogIdSetter: String
            get() = getString("$P.business.instruction_header_dialog_id_setter")
        val instructionHeaderSetter: String
            get() = getString("$P.business.instruction_header_setter")
        val instructionPayloadSetter: String
            get() = getString("$P.business.instruction_payload_setter")
        val instructionIdPrefix: String
            get() = getString("$P.business.instruction_id_prefix")
        val finalChunkText: String
            get() = getString("$P.business.final_chunk_text")
        val injectedFlagKey: String
            get() = getString("$P.business.injected_flag_key")
    }

    object RenderTts {
        val hookTarget: HookTarget?
            get() = parseHookTarget("actions.render_tts.target")
    }
}
