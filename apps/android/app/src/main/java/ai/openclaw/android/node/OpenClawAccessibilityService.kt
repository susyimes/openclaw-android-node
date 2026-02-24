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

    suspend fun setText(text: String): Boolean {
      val service = instanceRef.get() ?: return false
      return service.performSetTextInternal(text)
    }

    suspend fun pasteText(text: String): Boolean {
      val service = instanceRef.get() ?: return false
      return service.performPasteInternal(text)
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

  private suspend fun performSetTextInternal(text: String): Boolean {
    val node = findFocusedEditableNode() ?: return false
    val args = Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
  }

  private suspend fun performPasteInternal(text: String): Boolean {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText("openclaw", text))

    val node = findFocusedEditableNode() ?: return false
    if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true

    val args = Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
  }

  private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
    val root = rootInActiveWindow ?: return null
    val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    if (focused != null && focused.isEditable) return focused
    return findFirstEditable(root)
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
