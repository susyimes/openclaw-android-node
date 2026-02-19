package ai.openclaw.android.node

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
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
}
