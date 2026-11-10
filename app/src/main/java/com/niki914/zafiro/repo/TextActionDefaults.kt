package com.niki914.zafiro.repo

import java.io.File

/** Extra text actions shipped with Zafiro Autonomous. Prompts only: no permissions, no tools. */
object TextActionDefaults {
    private const val KEEP = "Reply with only the result, in the same language as the text, with no preface."

    val all: List<RuntimeTextAction> = listOf(
        RuntimeTextAction("Summarize", "Summarize the following text in a few short bullet points. $KEEP"),
        RuntimeTextAction("Improve writing", "Improve the clarity and flow of the following text without changing its meaning. $KEEP"),
        RuntimeTextAction("Fix grammar", "Fix spelling and grammar in the following text. Change nothing else. $KEEP"),
        RuntimeTextAction("Make shorter", "Rewrite the following text to be about half as long, keeping every key point. $KEEP"),
        RuntimeTextAction("Make longer", "Expand the following text with useful detail, keeping the same tone. $KEEP"),
        RuntimeTextAction("Friendlier tone", "Rewrite the following text in a warm, friendly tone. $KEEP"),
        RuntimeTextAction("Formal tone", "Rewrite the following text in a polite, professional tone. $KEEP"),
        RuntimeTextAction("Reply to this", "Write a concise, polite reply to the following message. $KEEP"),
        RuntimeTextAction("Extract action items", "List the concrete action items, owners and deadlines found in the following text as a checklist. $KEEP"),
        RuntimeTextAction(
            "Make a task from this",
            "Turn the following text into a scheduled task for me: say what the task would do, how often it should run, and what limits it needs. Ask me before creating anything.",
        ),
    )

    /** Defaults that are neither already present nor offered before; each default is offered once so a deleted one stays deleted. */
    fun toAdd(existingNames: Set<String>, alreadyOffered: Set<String>): List<RuntimeTextAction> =
        all.filter { it.name !in existingNames && it.name !in alreadyOffered }

    fun readOffered(marker: File): Set<String> =
        runCatching { marker.readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())

    fun writeOffered(marker: File, names: Set<String>) {
        runCatching { marker.parentFile?.mkdirs(); marker.writeText(names.sorted().joinToString("\n")) }
    }
}
