package ai.openclaw.android.node

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

class OpenClawAccessibilityService : AccessibilityService() {
  data class UiNodeDump(
    val path: String,
    val text: String?,
    val contentDescription: String?,
    val hint: String?,
    val viewId: String?,
    val bounds: String,
    val centerX: Int,
    val centerY: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val enabled: Boolean,
  )

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

    fun snapshot(maxNodes: Int = 300): List<UiNodeDump> {
      val service = instanceRef.get() ?: return emptyList()
      return service.collectSnapshot(maxNodes)
    }

    fun findNode(query: String): UiNodeDump? {
      val service = instanceRef.get() ?: return null
      return service.findBestNodeByQuery(query)
    }

    fun click(path: String? = null, query: String? = null): Boolean {
      val service = instanceRef.get() ?: return false
      return service.clickNode(path = path, query = query)
    }

    fun exists(query: String): Boolean = findNode(query) != null
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

    val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    if (focused?.isEditable == true) return focused

    if (query != null) {
      val matched = findNodeInfoByQuery(root, query)
      if (matched != null) {
        if (matched.isEditable) {
          matched.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
          matched.performAction(AccessibilityNodeInfo.ACTION_CLICK)
          return matched
        }

        clickNodeOrAncestor(matched)
        val refreshed = rootInActiveWindow
        val refreshedFocused = refreshed?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (refreshedFocused?.isEditable == true) return refreshedFocused

        val inSubtree = findFirstEditable(matched)
        if (inSubtree != null) return inSubtree
      }
    }

    return findFirstEditable(root)
  }

  private fun collectSnapshot(maxNodes: Int): List<UiNodeDump> {
    val root = rootInActiveWindow ?: return emptyList()
    val out = mutableListOf<UiNodeDump>()
    val queue = ArrayDeque<Pair<AccessibilityNodeInfo, String>>()
    queue.add(root to "r")

    while (queue.isNotEmpty() && out.size < maxNodes.coerceAtLeast(1)) {
      val (node, path) = queue.removeFirst()
      out += nodeToDump(node, path)
      for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        queue.add(child to "$path/$i")
      }
    }
    return out
  }

  private fun findBestNodeByQuery(query: String): UiNodeDump? {
    val root = rootInActiveWindow ?: return null
    val q = query.trim().lowercase()
    if (q.isEmpty()) return null

    val queue = ArrayDeque<Pair<AccessibilityNodeInfo, String>>()
    queue.add(root to "r")
    var best: Pair<Int, UiNodeDump>? = null

    while (queue.isNotEmpty()) {
      val (node, path) = queue.removeFirst()
      val score = scoreNode(node, q)
      if (score > 0) {
        val dump = nodeToDump(node, path)
        if (best == null || score > best!!.first) best = score to dump
      }
      for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        queue.add(child to "$path/$i")
      }
    }

    return best?.second
  }

  private fun clickNode(path: String? = null, query: String? = null): Boolean {
    val root = rootInActiveWindow ?: return false

    val target =
      when {
        !path.isNullOrBlank() -> resolvePath(root, path)
        !query.isNullOrBlank() -> findNodeInfoByQuery(root, query)
        else -> null
      } ?: return false

    return clickNodeOrAncestor(target)
  }

  private fun resolvePath(root: AccessibilityNodeInfo, path: String): AccessibilityNodeInfo? {
    val normalized = path.trim()
    if (normalized.isEmpty() || normalized == "r") return root
    val parts = normalized.removePrefix("r/").split('/').filter { it.isNotEmpty() }
    var current: AccessibilityNodeInfo? = root
    for (p in parts) {
      val idx = p.toIntOrNull() ?: return null
      current = current?.getChild(idx) ?: return null
    }
    return current
  }

  private fun nodeToDump(node: AccessibilityNodeInfo, path: String): UiNodeDump {
    val rect = Rect()
    node.getBoundsInScreen(rect)
    return UiNodeDump(
      path = path,
      text = node.text?.toString(),
      contentDescription = node.contentDescription?.toString(),
      hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString() else null,
      viewId = node.viewIdResourceName,
      bounds = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]",
      centerX = rect.centerX(),
      centerY = rect.centerY(),
      clickable = node.isClickable,
      editable = node.isEditable,
      focusable = node.isFocusable,
      focused = node.isFocused,
      enabled = node.isEnabled,
    )
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

  private fun findNodeInfoByQuery(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return null

    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)
    var best: Pair<Int, AccessibilityNodeInfo>? = null
    while (queue.isNotEmpty()) {
      val node = queue.removeFirst()
      val score = scoreNode(node, q)
      if (score > 0 && (best == null || score > best!!.first)) {
        best = score to node
      }
      for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        queue.add(child)
      }
    }
    return best?.second
  }

  private fun scoreNode(node: AccessibilityNodeInfo, q: String): Int {
    fun contains(raw: CharSequence?): Boolean {
      val s = raw?.toString()?.trim().orEmpty()
      return s.isNotEmpty() && s.lowercase().contains(q)
    }

    var score = 0
    if (contains(node.text)) score += 100
    if (contains(node.contentDescription)) score += 80
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && contains(node.hintText)) score += 60
    if (contains(node.viewIdResourceName)) score += 40

    if (node.isEditable) score += 15
    if (node.isClickable) score += 10
    if (node.isEnabled) score += 5
    return score
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
