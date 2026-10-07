package com.niki914.zafiro.runtime.ipc

import android.os.Parcel
import android.os.Parcelable

enum class ToolStatus {
    RUNNING,
    SUCCESS,
    FAILED;

    companion object {
        fun fromInt(value: Int): ToolStatus = entries.getOrElse(value) { RUNNING }
    }
}

data class ToolItem(
    val name: String,
    val status: ToolStatus,
) : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(name)
        dest.writeInt(status.ordinal)
    }

    companion object CREATOR : Parcelable.Creator<ToolItem> {
        override fun createFromParcel(source: Parcel): ToolItem = ToolItem(
            name = source.readString() ?: "",
            status = ToolStatus.fromInt(source.readInt()),
        )

        override fun newArray(size: Int): Array<ToolItem?> = arrayOfNulls(size)
    }
}

data class RenderFrame(
    val content: String = "",
    val thinking: String? = null,
    val isThinkingComplete: Boolean = false,
    val tools: List<ToolItem> = emptyList(),
    val isFirst: Boolean = false,
    val isFinal: Boolean = false,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(content)
        dest.writeString(thinking)
        dest.writeInt(if (isThinkingComplete) 1 else 0)
        dest.writeTypedList(tools)
        dest.writeInt(if (isFirst) 1 else 0)
        dest.writeInt(if (isFinal) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<RenderFrame> {
        override fun createFromParcel(source: Parcel): RenderFrame {
            val content = source.readString() ?: ""
            val thinking = source.readString()
            val isThinkingComplete = source.readInt() == 1
            val tools = mutableListOf<ToolItem>().also {
                source.readTypedList(it, ToolItem.CREATOR)
            }
            val isFirst = source.readInt() == 1
            val isFinal = source.readInt() == 1
            return RenderFrame(
                content = content,
                thinking = thinking,
                isThinkingComplete = isThinkingComplete,
                tools = tools,
                isFirst = isFirst,
                isFinal = isFinal,
            )
        }

        override fun newArray(size: Int): Array<RenderFrame?> = arrayOfNulls(size)
    }
}
