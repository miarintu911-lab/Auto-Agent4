package com.example.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.agent.ScreenBounds
import com.example.agent.ScreenNode

object NodeTreeParser {

    private const val MAX_NODES = 85
    private const val MAX_DEPTH = 20

    fun extractNodes(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        excludedPackage: String
    ): List<ScreenNode> {
        if (root == null) return emptyList()
        val results = mutableListOf<ScreenNode>()
        var nextId = 1

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH || results.size >= MAX_NODES) return

            val pkg = node.packageName?.toString().orEmpty()
            if (pkg == excludedPackage) return

            if (node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val clampedLeft = rect.left.coerceIn(0, screenWidth)
                val clampedTop = rect.top.coerceIn(0, screenHeight)
                val clampedRight = rect.right.coerceIn(0, screenWidth)
                val clampedBottom = rect.bottom.coerceIn(0, screenHeight)

                val hasArea = (clampedRight - clampedLeft) > 4 && (clampedBottom - clampedTop) > 4
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val viewId = node.viewIdResourceName?.trim().orEmpty()

                val isMeaningful = hasArea && (
                    node.isClickable ||
                        node.isEditable ||
                        node.isScrollable ||
                        node.isCheckable ||
                        text.isNotEmpty() ||
                        desc.isNotEmpty()
                    )

                if (isMeaningful) {
                    results.add(
                        ScreenNode(
                            id = nextId++,
                            className = node.className?.toString().orEmpty(),
                            text = text.take(120),
                            contentDesc = desc.take(120),
                            viewId = viewId,
                            bounds = ScreenBounds(
                                left = clampedLeft,
                                top = clampedTop,
                                right = clampedRight,
                                bottom = clampedBottom
                            ),
                            isClickable = node.isClickable,
                            isEditable = node.isEditable,
                            isScrollable = node.isScrollable,
                            isChecked = node.isChecked,
                            isFocused = node.isFocused
                        )
                    )
                }
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                if (results.size >= MAX_NODES) break
                val child = node.getChild(i)
                if (child != null) {
                    traverse(child, depth + 1)
                }
            }
        }

        traverse(root, 0)
        return results
    }

    fun formatNodesForPrompt(nodes: List<ScreenNode>): String {
        if (nodes.isEmpty()) return "(No visible UI nodes exposed by the active window.)"
        return nodes.joinToString("\n") { node ->
            val shortClass = node.className.substringAfterLast('.').ifBlank { "View" }
            val shortId = node.viewId.substringAfterLast('/')
            val flags = buildList {
                if (node.isClickable) add("clickable")
                if (node.isEditable) add("editable")
                if (node.isScrollable) add("scrollable")
                if (node.isChecked) add("checked")
                if (node.isFocused) add("focused")
            }.joinToString(",")

            buildString {
                append("#${node.id} [$shortClass]")
                if (node.text.isNotBlank()) append(" text=\"${node.text.replace("\n", " ")}\"")
                if (node.contentDesc.isNotBlank()) append(" desc=\"${node.contentDesc.replace("\n", " ")}\"")
                if (shortId.isNotBlank()) append(" id=\"$shortId\"")
                append(" bounds=${node.bounds.toCompactString()}")
                append(" center=(${node.bounds.centerX},${node.bounds.centerY})")
                if (flags.isNotBlank()) append(" flags=$flags")
            }
        }
    }

    fun findAccessibilityNodeByBounds(
        root: AccessibilityNodeInfo?,
        targetBounds: ScreenBounds,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val rect = Rect()
        root.getBoundsInScreen(rect)
        if (rect.left.coerceIn(0, screenWidth) == targetBounds.left &&
            rect.top.coerceIn(0, screenHeight) == targetBounds.top &&
            rect.right.coerceIn(0, screenWidth) == targetBounds.right &&
            rect.bottom.coerceIn(0, screenHeight) == targetBounds.bottom
        ) {
            return root
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val match = findAccessibilityNodeByBounds(child, targetBounds, screenWidth, screenHeight)
            if (match != null) return match
        }
        return null
    }
}
