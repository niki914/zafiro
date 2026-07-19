package com.niki914.nexus.agentic.chat.agentic.accessibility

object PruningRules {

    fun mapSemanticType(className: String, parentType: SemanticType?): SemanticType {
        val exactMatch = exactTypes[className]
        if (exactMatch != null) return exactMatch

        val suffixMatch = suffixTypes.entries.firstOrNull { (suffix, _) ->
            className.endsWith(suffix)
        }
        if (suffixMatch != null) return suffixMatch.value

        if (className.contains("Dialog")) return SemanticType.DIALOG

        if (parentType == SemanticType.LIST) return SemanticType.LIST_ITEM

        return SemanticType.CONTAINER
    }

    fun isCompletelyOffScreen(bounds: Rect, screenHeight: Int): Boolean {
        return bounds.bottom <= 0 || bounds.top >= screenHeight
    }

    fun isEmptyShell(node: NodeInfo): Boolean {
        return node.text.isEmpty() &&
                node.contentDesc.isEmpty() &&
                !node.isClickable &&
                !node.isLongClickable &&
                !node.isEditable &&
                !node.isScrollable &&
                !node.isChecked &&
                node.children.isEmpty()
    }

    fun needsQuoting(text: String): Boolean {
        return text.any { c ->
            c in ":{}[],#&*?|-><!%@'\"" || c.isWhitespace()
        }
    }

    fun normalizeText(text: String): String {
        val escaped = text
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return if (escaped.length > 30) {
            escaped.take(30) + "…"
        } else {
            escaped
        }
    }

    private val exactTypes = mapOf(
        "android.widget.Button" to SemanticType.BUTTON,
        "com.google.android.material.button.MaterialButton" to SemanticType.BUTTON,
        "androidx.appcompat.widget.AppCompatButton" to SemanticType.BUTTON,
        "android.widget.EditText" to SemanticType.INPUT,
        "com.google.android.material.textfield.TextInputEditText" to SemanticType.INPUT,
        "android.widget.AutoCompleteTextView" to SemanticType.INPUT,
        "android.widget.MultiAutoCompleteTextView" to SemanticType.INPUT,
        "android.widget.TextView" to SemanticType.TEXT,
        "android.widget.ImageView" to SemanticType.IMAGE,
        "android.widget.ImageButton" to SemanticType.IMAGE,
        "android.widget.RecyclerView" to SemanticType.LIST,
        "android.widget.ListView" to SemanticType.LIST,
        "android.widget.GridView" to SemanticType.LIST,
        "android.widget.ScrollView" to SemanticType.LIST,
        "android.widget.HorizontalScrollView" to SemanticType.LIST,
        "androidx.viewpager2.widget.ViewPager2" to SemanticType.LIST,
        "android.widget.Switch" to SemanticType.SWITCH,
        "androidx.appcompat.widget.SwitchCompat" to SemanticType.SWITCH,
        "android.widget.ToggleButton" to SemanticType.SWITCH,
        "android.widget.CheckBox" to SemanticType.CHECKBOX,
        "android.widget.CheckedTextView" to SemanticType.CHECKBOX,
        "com.google.android.material.tabs.TabLayout\$Tab" to SemanticType.TAB,
        "com.google.android.material.bottomnavigation.BottomNavigationItemView" to SemanticType.TAB,
        "com.google.android.material.chip.Chip" to SemanticType.CHIP,
        "com.google.android.material.chip.FilterChip" to SemanticType.CHIP,
        "androidx.appcompat.widget.Toolbar" to SemanticType.TOOLBAR,
        "com.google.android.material.appbar.MaterialToolbar" to SemanticType.TOOLBAR,
    )

    private val suffixTypes = mapOf(
        ".Button" to SemanticType.BUTTON,
        ".CheckBox" to SemanticType.CHECKBOX,
    )
}
