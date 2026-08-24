package com.niki914.zafiro.mod.feat.oppo

import com.niki914.zafiro.mod.feat.BaseConfigProvider
import com.niki914.zafiro.mod.feat.HookTarget
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Breeno 云配置提供者，按 action 路径暴露各 Hook 所需的类名、方法名、参数索引等配置项。 */
object BreenoConfigProvider : BaseConfigProvider() {

    object CaptureInput {
        private const val P = "actions.capture_input"
        val hookTarget: HookTarget?
            get() = parseHookTarget("$P.target")
        val queryArgIndex: Int
            get() = getInt("$P.business.query_arg_index")
        val dialogIdArgIndex: Int
            get() = getInt("$P.business.dialog_id_arg_index")
        val chatTypeQuery: Int
            get() = getInt("$P.business.chat_type_query")
        val beanGetChatTypeMethod: String
            get() = getString("$P.business.bean_get_chat_type_method")
        val beanGetRoomIdMethod: String
            get() = getString("$P.business.bean_get_room_id_method")
        val beanGetContentMethod: String
            get() = getString("$P.business.bean_get_content_method")
    }

    object CaptureResponseTarget {
        private const val P = "actions.capture_response_target"
        val hookTarget: HookTarget?
            get() = parseHookTarget("$P.target")
        val chatTypeAnswer: Int
            get() = getInt("$P.business.chat_type_answer")
        val selfInjectedFlagKey: String
            get() = getString("$P.business.self_injected_flag_key")
        val beanGetChatTypeMethod: String
            get() = getString("$P.business.bean_get_chat_type_method")
        val beanGetClientLocalDataMethod: String
            get() = getString("$P.business.bean_get_client_local_data_method")
        val beanGetRoomIdMethod: String
            get() = getString("$P.business.bean_get_room_id_method")
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

    object SuppressCleanup {
        private const val P = "actions.suppress_cleanup"
        val hookTarget: HookTarget?
            get() = parseHookTarget("$P.target")
        val cleanOperationClass: String
            get() = getString("$P.business.clean_operation_class")
        val doNothingOperationClass: String
            get() = getString("$P.business.do_nothing_operation_class")
    }

    object RenderCard {
        private const val P = "actions.render_card"
        val viewBeanClass: String
            get() = getString("$P.business.view_bean_class")
        val dataCenterInsertMessageMethod: String
            get() = getString("$P.business.data_center_insert_message_method")
        val dataCenterUpdateMessageMethod: String
            get() = getString("$P.business.data_center_update_message_method")
        val beanSetChatTypeMethod: String
            get() = getString("$P.business.bean_set_chat_type_method")
        val beanSetRoomIdMethod: String
            get() = getString("$P.business.bean_set_room_id_method")
        val beanSetContentMethod: String
            get() = getString("$P.business.bean_set_content_method")
        val beanSetRecordIdMethod: String
            get() = getString("$P.business.bean_set_record_id_method")
        val beanSetFinalMethod: String
            get() = getString("$P.business.bean_set_final_method")
        val beanSetFirstSliceMethod: String
            get() = getString("$P.business.bean_set_first_slice_method")
        val beanAddClientLocalDataMethod: String
            get() = getString("$P.business.bean_add_client_local_data_method")
        val chatTypeAnswer: Int
            get() = getInt("$P.business.chat_type_answer")
        val hideFeedbackViewLocalDataKey: String
            get() = getString("$P.business.hide_feedback_view_local_data_key")
        val mockBeanMethodsUnit: List<Pair<String, Any>>
            get() = readConfigPairs("$P.business.mock_bean_methods")
        val mockBeanMethodsReplayOnUpdate: Set<String>
            get() = getList("$P.business.mock_bean_methods_replay_on_update")
                .mapNotNull { it.jsonPrimitive.contentOrNull }
                .filter { it.isNotBlank() }
                .toSet()
        val mockBeanLocalDataUnit: List<Pair<String, Any>>
            get() = readConfigPairs("$P.business.mock_bean_local_data")
        val feedbackInfoClass: String
            get() = getString("$P.business.feedback_info_class")
        val footerInfoClass: String
            get() = getString("$P.business.footer_info_class")
        val beanSetFeedbackInfoMethod: String
            get() = getString("$P.business.bean_set_feedback_info_method")
        val feedbackSetFooterInfoMethod: String
            get() = getString("$P.business.feedback_set_footer_info_method")
        val footerInfoSetCopyFlagMethod: String
            get() = getString("$P.business.footer_info_set_copy_flag_method")
        val footerInfoSetUpvoteFlagMethod: String
            get() = getString("$P.business.footer_info_set_upvote_flag_method")
        val feedbackCopyFlagEnabled: Boolean
            get() = getBoolean("$P.business.feedback_copy_flag_default")
        val feedbackUpvoteFlagEnabled: Boolean
            get() = getBoolean("$P.business.feedback_upvote_flag_default")
    }

    // ---- private helpers ----

    private fun readConfigPairs(objectPath: String): List<Pair<String, Any>> =
        getObject(objectPath).mapNotNull { (key, value) ->
            value.toConfigValue()?.let { key to it }
        }

    private fun JsonElement.toConfigValue(): Any? {
        val primitive = jsonPrimitive
        return primitive.intOrNull ?: primitive.booleanOrNull ?: primitive.contentOrNull
    }
}
