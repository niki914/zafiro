package com.niki914.nexus.agentic.runtime.ipc

import android.os.Parcel
import android.os.Parcelable

data class AgentEvent(
    val sequence: Int,
    val timestamp: Long,
    val eventType: String,
    val text: String? = null,
    val isFirst: Boolean = false,
    val isFinal: Boolean = false,
    val toolName: String? = null,
    val toolLabel: String? = null,
    val toolOutput: String? = null,
    val toolError: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(sequence)
        dest.writeLong(timestamp)
        dest.writeString(eventType)
        dest.writeInt(if (text != null) 1 else 0)
        text?.let { dest.writeString(it) }
        dest.writeInt(if (isFirst) 1 else 0)
        dest.writeInt(if (isFinal) 1 else 0)
        dest.writeInt(if (toolName != null) 1 else 0)
        toolName?.let { dest.writeString(it) }
        dest.writeInt(if (toolLabel != null) 1 else 0)
        toolLabel?.let { dest.writeString(it) }
        dest.writeInt(if (toolOutput != null) 1 else 0)
        toolOutput?.let { dest.writeString(it) }
        dest.writeInt(if (toolError != null) 1 else 0)
        toolError?.let { dest.writeString(it) }
        dest.writeInt(if (errorCode != null) 1 else 0)
        errorCode?.let { dest.writeString(it) }
        dest.writeInt(if (errorMessage != null) 1 else 0)
        errorMessage?.let { dest.writeString(it) }
    }

    companion object CREATOR : Parcelable.Creator<AgentEvent> {
        override fun createFromParcel(source: Parcel): AgentEvent = AgentEvent(
            sequence = source.readInt(),
            timestamp = source.readLong(),
            eventType = source.readString() ?: "",
            text = source.readInt().let { if (it == 1) source.readString() else null },
            isFirst = source.readInt() == 1,
            isFinal = source.readInt() == 1,
            toolName = source.readInt().let { if (it == 1) source.readString() else null },
            toolLabel = source.readInt().let { if (it == 1) source.readString() else null },
            toolOutput = source.readInt().let { if (it == 1) source.readString() else null },
            toolError = source.readInt().let { if (it == 1) source.readString() else null },
            errorCode = source.readInt().let { if (it == 1) source.readString() else null },
            errorMessage = source.readInt().let { if (it == 1) source.readString() else null },
        )

        override fun newArray(size: Int): Array<AgentEvent?> = arrayOfNulls(size)
    }
}
