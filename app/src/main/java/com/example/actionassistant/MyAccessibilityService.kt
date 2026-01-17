package com.example.actionassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

class MyAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MyAccessibilityService? = null
    }

    private val nodeMap = mutableMapOf<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // FEATURE: HTML Generation + Pruning
    fun getScreenLayoutAsHtml(): String {
        val rootNode = rootInActiveWindow ?: return "<p>No Data</p>"
        nodeMap.clear()
        val sb = StringBuilder()
        traverseNodeAsHtml(rootNode, sb)
        return sb.toString()
    }

    private fun traverseNodeAsHtml(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        if (!node.isVisibleToUser) return

        // Pruning: Skip elements that are just layout containers
        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isScrollable = node.isScrollable
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val hasContent = !text.isNullOrEmpty() || !desc.isNullOrEmpty()

        if (!isClickable && !isEditable && !isScrollable && !hasContent) {
            for (i in 0 until node.childCount) {
                traverseNodeAsHtml(node.getChild(i), sb)
            }
            return
        }

        val id = node.hashCode()
        nodeMap[id] = node

        val tagName = when {
            isEditable -> "input"
            isClickable -> "button"
            isScrollable -> "scroller"
            else -> "p"
        }

        sb.append("<$tagName id=\"$id\"")
        if (!text.isNullOrEmpty()) sb.append(" text=\"$text\"")
        if (!desc.isNullOrEmpty()) sb.append(" desc=\"$desc\"")
        sb.append("/>\n")

        for (i in 0 until node.childCount) {
            traverseNodeAsHtml(node.getChild(i), sb)
        }
    }

    fun performActionFromJson(actionJsonString: String) {
        try {
            val responseJson = JSONObject(actionJsonString)
            val actionObject = responseJson.optJSONObject("action") ?: return
            val actionType = actionObject.optString("action").uppercase()
            val elementId = actionObject.optInt("element_id")

            if (actionType == "HOME") {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }

            // FEATURE: Scroll Support
            if (actionType == "SCROLL") {
                val targetNode = nodeMap[elementId]
                if (targetNode != null) {
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                } else {
                    performBlindScroll()
                }
                return
            }

            val targetNode = nodeMap[elementId] ?: return

            when (actionType) {
                "CLICK" -> {
                    if (!targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        targetNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                }
                "TYPE" -> {
                    val textToType = actionObject.optString("text")
                    val args = Bundle()
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
                "ENTER" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        targetNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    }
                }
            }
        } catch (e: Exception) { }
    }

    private fun performBlindScroll() {
        val displayMetrics = resources.displayMetrics
        val height = displayMetrics.heightPixels
        val width = displayMetrics.widthPixels
        val path = Path()
        path.moveTo((width / 2).toFloat(), (height * 0.8).toFloat())
        path.lineTo((width / 2).toFloat(), (height * 0.2).toFloat())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        dispatchGesture(gesture, null, null)
    }
}