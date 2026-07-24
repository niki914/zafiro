package com.niki914.nexus.agentic.chat.agentic.accessibility

data class SemanticToken(val version: String, val index: Int) {
    companion object {
        fun parse(token: String): Result<SemanticToken> {
            val underscoreIndex = token.lastIndexOf('_')
            if (underscoreIndex <= 0 || underscoreIndex == token.lastIndex) {
                return Result.failure(
                    IllegalArgumentException("Invalid token format: $token")
                )
            }
            val version = token.substring(0, underscoreIndex)
            val indexStr = token.substring(underscoreIndex + 1)
            if (version.isEmpty() || indexStr.isEmpty()) {
                return Result.failure(
                    IllegalArgumentException("Invalid token format: $token")
                )
            }
            val index = indexStr.toIntOrNull()
                ?: return Result.failure(
                    IllegalArgumentException("Invalid token format: $token")
                )
            return Result.success(SemanticToken(version, index))
        }

        fun generate(version: String, index: Int): String = "${version}_$index"
    }
}
