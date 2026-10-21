package com.niki914.zafiro.app.ui.settings.form

import androidx.annotation.StringRes

/**
 * 表单字段规格描述符。
 *
 * 将字段标识、多语言标题与值提取逻辑声明为单一真理源，
 * 供 UI 标题展示、焦点控制以及未保存挽留弹窗条目提取复用。
 *
 * 字段值建议使用不可变类型或只读集合（如 [List]、[Set]、[Map]）。
 *
 * @param id 字段标识符，通常使用内聚于页面的 Field 枚举。
 * @param labelRes 字段在界面和挽留弹窗中展示的多语言字符串资源 ID。
 * @param getter 从表单状态中提取当前字段值的读取器。
 */
data class FormFieldSpec<State, Field>(
    val id: Field,
    @param:StringRes val labelRes: Int,
    val getter: (State) -> Any?,
)

/**
 * 表单规格集合。
 *
 * 集中管理一组 [FormFieldSpec]，提供基线对比与已修改字段提取能力。
 *
 * 基线使用约定：
 * - [baseline] 为 null 严格代表“持久化数据正在异步加载（Loading 未就绪）”。
 * - 新建态（isCreating = true）应传入初始默认空表单状态，确保用户录入内容后能够正常触发挽留弹窗。
 */
class FormSpec<State, Field>(
    val fields: List<FormFieldSpec<State, Field>>,
) {
    /**
     * 对比当前状态与基线状态，判断是否有任一声明字段发生变化。
     * 基线为 null 时代表数据尚未就绪，判定为无未保存改动。
     */
    fun hasChanges(baseline: State?, current: State): Boolean {
        if (baseline == null) return false
        return fields.any { !areValuesEqual(it.getter(baseline), it.getter(current)) }
    }

    /**
     * 提取已发生变化的字段字符串资源 ID 列表。
     * 仅返回资源 ID，不持有 Android Context，可在弹窗需要展示时按需执行。
     */
    fun dirtyFieldLabelRes(baseline: State?, current: State): List<Int> {
        if (baseline == null) return emptyList()
        return fields.filter { !areValuesEqual(it.getter(baseline), it.getter(current)) }
            .map { it.labelRes }
            .distinct()
    }

    private fun areValuesEqual(a: Any?, b: Any?): Boolean {
        return when {
            a === b -> true
            a == null || b == null -> false
            a is Array<*> && b is Array<*> -> a.contentDeepEquals(b)
            a is BooleanArray && b is BooleanArray -> a.contentEquals(b)
            a is ByteArray && b is ByteArray -> a.contentEquals(b)
            a is CharArray && b is CharArray -> a.contentEquals(b)
            a is ShortArray && b is ShortArray -> a.contentEquals(b)
            a is IntArray && b is IntArray -> a.contentEquals(b)
            a is LongArray && b is LongArray -> a.contentEquals(b)
            a is FloatArray && b is FloatArray -> a.contentEquals(b)
            a is DoubleArray && b is DoubleArray -> a.contentEquals(b)
            else -> a == b
        }
    }
}

/**
 * 用于声明式构建 [FormSpec] 的 DSL 构建器。
 */
class FormSpecBuilder<State, Field> {
    private val fieldList = mutableListOf<FormFieldSpec<State, Field>>()

    fun field(
        id: Field,
        @StringRes labelRes: Int,
        getter: (State) -> Any?,
    ) {
        fieldList.add(FormFieldSpec(id = id, labelRes = labelRes, getter = getter))
    }

    fun build(): FormSpec<State, Field> = FormSpec(fieldList.toList())
}

/**
 * 声明式构建 [FormSpec] 的便利入口。
 */
inline fun <State, Field> buildFormSpec(
    builderAction: FormSpecBuilder<State, Field>.() -> Unit,
): FormSpec<State, Field> {
    return FormSpecBuilder<State, Field>().apply(builderAction).build()
}
