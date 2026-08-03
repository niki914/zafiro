package com.niki914.libterm

class TerminalBytes private constructor(private val data: ByteArray) {
    val size: Int
        get() = data.size

    val isEmpty: Boolean
        get() = data.isEmpty()

    fun toByteArray(): ByteArray = data.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalBytes) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()

    companion object {
        val EMPTY: TerminalBytes = TerminalBytes(ByteArray(0))

        fun of(bytes: ByteArray): TerminalBytes = TerminalBytes(bytes.copyOf())
    }
}
