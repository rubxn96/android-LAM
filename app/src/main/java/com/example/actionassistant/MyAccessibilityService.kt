// IMPORTANT: Make sure this package name matches your project
package com.example.actionassistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class MyAccessibilityService : AccessibilityService() {

    companion object {
        // A static instance so MainActivity can easily access the running service
        var instance: MyAccessibilityService? = null
    }

    // A map to keep track of UI elements currently on screen
    private val nodeMap = mutableMapOf<Int, AccessibilityNodeInfo>()
    private val TAG = "AccessService" // Tag for filtering in Logcat

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility Service has been connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for this project's logic
    }

    override fun onInterrupt() {
        // Called when the service is interrupted
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "Accessibility Service has been destroyed.")
    }

    /**
     * Reads the entire screen layout and converts it into a compact JSON string.
     */
    fun getScreenLayoutAsJson(): String {
        val rootNode = rootInActiveWindow ?: return "{}"
        nodeMap.clear() // Clear old nodes before reading the new layout
        val screenJson = serializeNode(rootNode)
        rootNode.recycle()
        // Return a compact, single-line JSON string to avoid parsing errors
        return screenJson.toString()
    }

    /**
     * Recursively travels through UI elements to build the JSON description.
     */
    private fun serializeNode(node: AccessibilityNodeInfo?): JSONObject {
        val jsonNode = JSONObject()
        if (node == null) return jsonNode

        val nodeId = node.hashCode()
        nodeMap[nodeId] = node // Store the node for later action

        jsonNode.put("id", nodeId)
        jsonNode.put("class", node.className)
        jsonNode.put("text", node.text)
        jsonNode.put("contentDescription", node.contentDescription)
        jsonNode.put("isClickable", node.isClickable)

        val children = JSONArray()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null && child.isVisibleToUser) {
                children.put(serializeNode(child))
            }
        }
        jsonNode.put("children", children)
        return jsonNode
    }

    /**
     * Parses a JSON command from the AI and performs the specified action.
     */
    // Replace your entire performActionFromJson function with this one
    fun performActionFromJson(actionJsonString: String) {
        try {
            val responseJson = JSONObject(actionJsonString) // The full response from the AI

            // ## THE FIX IS HERE ##
            // We now get the nested "action" object first.
            val actionObject = responseJson.getJSONObject("action")

            val actionType = actionObject.getString("action")

            if (actionType.equals("NONE", ignoreCase = true)) {
                Log.d(TAG, "Received 'NONE' action. No operation to perform.")
                return
            }

            val elementId = actionObject.getInt("element_id")
            val targetNode = nodeMap[elementId]

            if (targetNode == null) {
                Log.e(TAG, "Node with ID $elementId not found in map. The UI may have changed.")
                return
            }

            when (actionType.uppercase()) {
                "CLICK" -> {
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "Successfully performed CLICK on element $elementId")
                }
                "TYPE" -> {
                    // Also read the text from the nested action object
                    val textToType = actionObject.getString("text")
                    val arguments = Bundle()
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    Log.i(TAG, "Successfully performed TYPE on element $elementId with text: '$textToType'")
                }
                else -> Log.w(TAG, "Unknown action type received: $actionType")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse or perform action from JSON: $actionJsonString", e)
        }
    }
}