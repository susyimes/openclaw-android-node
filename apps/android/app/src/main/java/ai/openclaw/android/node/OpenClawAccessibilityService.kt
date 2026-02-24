package ai.openclaw.android.node

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

class OpenClawAccessibilityService : AccessibilityService() {
  companion object {
    private val instanceRef = AtomicReference<OpenClawAccessibilityService?>(null)

    fun isActive(): Boolean = instanceRef.get() != null

    suspend fun tap(x: Float, y: Float, durationMs: Long): Boolean {
      val service = instanceRef.get() ?: return false
      return service.performTapInternal(x = x, y = y, durationMs = durationMs)
    }

    suspend fun setText(text: String, targetQuery: String? = null): Boolean {
      val service = instanceRef.get() ?: return false
      return service.performSetTextInternal(text = text, targetQuery = targetQuery)
    }

    suspend fun pasteText(text: String, targetQuery: String? = null): Boolean {
      val service = instanceRef.get() ?: return false
      return service.performPasteInternal(text = text, targetQuery = targetQuery)
    }
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    instanceRef.set(this)
  }

  override fun onDestroy() {
    instanceRef.compareAndSet(this, null)
    super.onDestroy()
  }

  override fun onUnbind(intent: android.content.Intent?): Boolean {
    instanceRef.compareAndSet(this, null)
    return super.onUnbind(intent)
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

  override fun onInterrupt() = Unit

  private suspend fun performTapInternal(x: Float, y: Float, durationMs: Long): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

    val clampedDuration = durationMs.coerceIn(40L, 1_000L)
    val path = Path().apply { moveTo(x, y) }
    val stroke = GestureDescription.StrokeDescription(path, 0L, clampedDuration)
    val gesture = GestureDescription.Builder().addStroke(stroke).build()

    return suspendCancellableCoroutine { cont ->
      val dispatched =
        dispatchGesture(
          gesture,
          object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
              if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
              if (cont.isActive) cont.resume(false)
            }
          },
          null,
        )

      if (!dispatched && cont.isActive) cont.resume(false)
    }
  }

  private suspend fun performSetTextInternal(text: String, targetQuery: String?): Boolean {
    val node = resolveEditableTarget(targetQuery) ?: return false
    val args = Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
  }

  private suspend fun performPasteInternal(text: String, targetQuery: String?): Boolean {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText("openclaw", text))

    val node = resolveEditableTarget(targetQuery) ?: return false
    if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true

    val args = Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
  }

  private fun resolveEditableTarget(targetQuery: String?): AccessibilityNodeInfo? {
    val query = targetQuery?.trim().orEmpty().ifEmpty { null }

    val root = rootInActiveWindow ?: return null

    // 1) Existing focused editable wins.
    val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    if (focused?.isEditable == true) return focused

    // 2) If query provided, find relevant node and click/focus it first.
    if (query != null) {
      val matched = findNodeByQuery(root, query)
      if (matched != null) {
        if (matched.isEditable) {
          matched.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
          matched.performAction(AccessibilityNodeInfo.ACTION_CLICK)
          return matched
        }

        // Click match or a clickable ancestor, then re-resolve focused editable.
        clickNodeOrAncestor(matched)
        val refreshed = rootInActiveWindow
        val refreshedFocused = refreshed?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (refreshedFocused?.isEditable == true) return refreshedFocused

        // If matched subtree contains editable, use it.
        val inSubtree = findFirstEditable(matched)
        if (inSubtree != null) return inSubtree
      }
    }

    // 3) Fallback: first editable in tree.
    return findFirstEditable(root)
  }

  private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
    var current: AccessibilityNodeInfo? = node
    while (current != null) {
      if (current.isClickable && current.isEnabled) {
        if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
      }
      current = current.parent
    }
    return false
  }

  private fun findNodeByQuery(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
    val q = query.lowercase()
    val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
    queue.add(root)

    while (queue.isNotEmpty()) {
      val node = queue.removeFirst()
      if (nodeMatchesQuery(node, q)) return node
      for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        queue.add(child)
      }
    }
    return null
  }

  private fun nodeMatchesQuery(node: AccessibilityNodeInfo, queryLower: String): Boolean {
    fun hit(raw: CharSequence?): Boolean {
      val s = raw?.toString()?.trim().orEmpty()
      return s.isNotEmpty() && s.lowercase().contains(queryLower)
    }

    if (hit(node.text)) return true
    if (hit(node.contentDescription)) return true
    if (hit(node.viewIdResourceName)) return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hit(node.hintText)) return true

    return false
  }

  private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
    node ?: return null
    if (node.isEditable) return node
    for (i in 0 until node.childCount) {
      val found = findFirstEditable(node.getChild(i))
      if (found != null) return found
    }
    return null
  }
}
