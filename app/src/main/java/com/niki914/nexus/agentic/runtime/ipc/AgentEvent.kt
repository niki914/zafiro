package com.niki914.nexus.agentic.runtime.ipc

import android.os.Parcel
import android.os.Parcelable

data class AgentEvent(
    val text: String,
    val isFirst: Boolean = false,
    val isFinal: Boolean = false,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(text)
        dest.writeInt(if (isFirst) 1 else 0)
        dest.writeInt(if (isFinal) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<AgentEvent> {
        override fun createFromParcel(source: Parcel): AgentEvent = AgentEvent(
            text = source.readString() ?: "",
            isFirst = source.readInt() == 1,
            isFinal = source.readInt() == 1,
        )

        override fun newArray(size: Int): Array<AgentEvent?> = arrayOfNulls(size)
    }
}
