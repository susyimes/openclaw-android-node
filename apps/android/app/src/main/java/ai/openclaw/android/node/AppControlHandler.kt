package ai.openclaw.android.node

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

class AppControlHandler(
  private val appContext: Context,
  private val json: Json,
) {
  suspend fun handleAppLaunch(paramsJson: String?): Result {
    val payload = parseObject(paramsJson)
    val packageName = payload?.get("packageName").asStringOrNull()?.trim().orEmpty()
    val activity = payload?.get("activity").asStringOrNull()?.trim().orEmpty().ifEmpty { null }

    if (packageName.isEmpty()) {
      return Result.error(
        code = "INVALID_REQUEST",
        message = "INVALID_REQUEST: packageName required",
      )
    }

    return try {
      val intent =
        if (activity != null) {
          Intent().setClassName(packageName, activity)
        } else {
          appContext.packageManager.getLaunchIntentForPackage(packageName)
        }

      if (intent == null) {
        Result.error(
          code = "APP_NOT_FOUND",
          message = "APP_NOT_FOUND: no launchable activity for $packageName",
        )
      } else {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        Result.ok(
          buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("packageName", JsonPrimitive(packageName))
            if (activity != null) put("activity", JsonPrimitive(activity))
          }.toString(),
        )
      }
    } catch (err: Throwable) {
      Result.error(
        code = "APP_LAUNCH_FAILED",
        message = "APP_LAUNCH_FAILED: ${err.message ?: "failed to launch"}",
      )
    }
  }

  suspend fun handleScreenTap(paramsJson: String?): Result {
    val payload = parseObject(paramsJson)
    val x = payload?.get("x").asNumberOrNull()
    val y = payload?.get("y").asNumberOrNull()
    val durationMs = payload?.get("durationMs").asNumberOrNull()?.toLong() ?: 60L

    if (x == null || y == null) {
      return Result.error(
        code = "INVALID_REQUEST",
        message = "INVALID_REQUEST: x and y are required",
      )
    }

    if (!OpenClawAccessibilityService.isActive()) {
      return Result.error(
        code = "ACCESSIBILITY_DISABLED",
        message = "ACCESSIBILITY_DISABLED: enable OpenClaw Accessibility service in Android settings",
      )
    }

    val ok = OpenClawAccessibilityService.tap(x = x.toFloat(), y = y.toFloat(), durationMs = durationMs)
    if (!ok) {
      return Result.error(
        code = "TAP_FAILED",
        message = "TAP_FAILED: gesture dispatch failed",
      )
    }

    return Result.ok(
      buildJsonObject {
        put("ok", JsonPrimitive(true))
        put("x", JsonPrimitive(x))
        put("y", JsonPrimitive(y))
        put("durationMs", JsonPrimitive(durationMs))
      }.toString(),
    )
  }

  suspend fun handleTextInput(paramsJson: String?): Result {
    val payload = parseObject(paramsJson)
    val text = payload?.get("text").asStringOrNull()?.takeIf { it.isNotEmpty() }
    val targetQuery = payload?.get("targetQuery").asStringOrNull()?.trim().orEmpty().ifEmpty { null }

    if (text == null) {
      return Result.error(
        code = "INVALID_REQUEST",
        message = "INVALID_REQUEST: text required",
      )
    }

    if (!OpenClawAccessibilityService.isActive()) {
      return Result.error(
        code = "ACCESSIBILITY_DISABLED",
        message = "ACCESSIBILITY_DISABLED: enable OpenClaw Accessibility service in Android settings",
      )
    }

    val ok = OpenClawAccessibilityService.setText(text, targetQuery = targetQuery)
    if (!ok) {
      return Result.error(
        code = "TEXT_INPUT_FAILED",
        message = "TEXT_INPUT_FAILED: no focused editable field or action failed",
      )
    }

    return Result.ok(
      buildJsonObject {
        put("ok", JsonPrimitive(true))
        put("textLength", JsonPrimitive(text.length))
        if (targetQuery != null) put("targetQuery", JsonPrimitive(targetQuery))
      }.toString(),
    )
  }

  suspend fun handleImePaste(paramsJson: String?): Result {
    val payload = parseObject(paramsJson)
    val text = payload?.get("text").asStringOrNull()?.takeIf { it.isNotEmpty() }
    val targetQuery = payload?.get("targetQuery").asStringOrNull()?.trim().orEmpty().ifEmpty { null }

    if (text == null) {
      return Result.error(
        code = "INVALID_REQUEST",
        message = "INVALID_REQUEST: text required",
      )
    }

    if (!OpenClawAccessibilityService.isActive()) {
      return Result.error(
        code = "ACCESSIBILITY_DISABLED",
        message = "ACCESSIBILITY_DISABLED: enable OpenClaw Accessibility service in Android settings",
      )
    }

    val ok = OpenClawAccessibilityService.pasteText(text, targetQuery = targetQuery)
    if (!ok) {
      return Result.error(
        code = "IME_PASTE_FAILED",
        message = "IME_PASTE_FAILED: failed to paste into focused field",
      )
    }

    return Result.ok(
      buildJsonObject {
        put("ok", JsonPrimitive(true))
        put("textLength", JsonPrimitive(text.length))
        if (targetQuery != null) put("targetQuery", JsonPrimitive(targetQuery))
      }.toString(),
    )
  }

  suspend fun handleUiSnapshot(paramsJson: String?): Result {
    val payload = parseObject(paramsJson)
    val maxNodes = payload?.get("maxNodes").asNumberOrNull()?.toInt() ?: 300

    if (!OpenClawAccessibilityService.isActive()) {
      return Result.error(
        code = "ACCESSIBILITY_DISABLED",
        message = "ACCESSIBILITY_DISABLED: enable OpenClaw Accessibility service in Android settings",
      )
    }

    val nodes = OpenClawAccessibilityService.snapshot(maxNodes = maxNodes)
    val arr: JsonArray = buildJsonArray {
      nodes.forEach { n ->
        add(
          buildJsonObject {
            put("path", JsonPrimitive(n.path))
            if (!n.text.isNullOrEmpty()) put("text", JsonPrimitive(n.text))
            if (!n.contentDescription.isNullOrEmpty()) put("contentDescription", JsonPrimitive(n.contentDescription))
            if (!n.hint.isNullOrEmpty()) put("hint", JsonPrimitive(n.hint))
            if (!n.viewId.isNullOrEmpty()) put("viewId", JsonPrimitive(n.viewId))
            put("bounds", JsonPrimitive(n.bounds))
            put("centerX", JsonPrimitive(n.centerX))
            put("centerY", JsonPrimitive(n.centerY))
            put("clickable", JsonPrimitive(n.clickable))
            put("editable", JsonPrimitive(n.editable))
            put("focusable", JsonPrimitive(n.focusable))
            put("focused", JsonPrimitive(n.focused))
            put("enabled", JsonPrimitive(n.enabled))
          },
        )
      }
    }

    return Result.ok(
      buildJsonObject {
        put("ok", JsonPrimitive(true))
        put("count", JsonPrimitive(nodes.size))
        put("nodes", arr)
      }.toString(),
    )
  }

  suspend fun handleUiFind(paramsJson: String?): Result {
    val payload = parseObject(paramsJson)
    val query = payload?.get("query").asStringOrNull()?.trim().orEmpty()

    if (query.isEmpty()) {
      return Result.error(
        code = "INVALID_REQUEST",
        message = "INVALID_REQUEST: query required",
      )
    }
    if (!OpenClawAccessibilityService.isActive()) {
      return Result.error(
        code = "ACCESSIBILITY_DISABLED",
        message = "ACCESSIBILITY_DISABLED: enable OpenClaw Accessibility service in Android settings",
      )
    }

    val found = OpenClawAccessibilityService.findNode(query)
    if (found == null) {
      return Result.error(
        code = "UI_NOT_FOUND",
        message = "UI_NOT_FOUND: no node matched query",
      )
    }

    return Result.ok(
      buildJsonObject {
        put("ok", JsonPrimitive(true))
        put("query", JsonPrimitive(query))
        put("path", JsonPrimitive(found.path))
        if (!found.text.isNullOrEmpty()) put("text", JsonPrimitive(found.text))
        if (!found.contentDescription.isNullOrEmpty()) put("contentDescription", JsonPrimitive(found.contentDescription))
        if (!found.hint.isNullOrEmpty()) put("hint", JsonPrimitive(found.hint))
        if (!found.viewId.isNullOrEmpty()) put("viewId", JsonPrimitive(found.viewId))
        put("bounds", JsonPrimitive(found.bounds))
        put("centerX", JsonPrimitive(found.centerX))
        put("centerY", JsonPrimitive(found.centerY))
        put("clickable", JsonPrimitive(found.clickable))
        put("editable", JsonPrimitive(found.editable))
      }.toString(),
    )
  }

  suspend fun handleUiClick(paramsJson: String?): Result {
    val payload = parseObject(paramsJson)
    val path = payload?.get("path").asStringOrNull()?.trim().orEmpty().ifEmpty { null }
    val query = payload?.get("query").asStringOrNull()?.trim().orEmpty().ifEmpty { null }

    if (path == null && query == null) {
      return Result.error(
        code = "INVALID_REQUEST",
        message = "INVALID_REQUEST: path or query required",
      )
    }
    if (!OpenClawAccessibilityService.isActive()) {
      return Result.error(
        code = "ACCESSIBILITY_DISABLED",
        message = "ACCESSIBILITY_DISABLED: enable OpenClaw Accessibility service in Android settings",
      )
    }

    val ok = OpenClawAccessibilityService.click(path = path, query = query)
    if (!ok) {
      return Result.error(
        code = "UI_CLICK_FAILED",
        message = "UI_CLICK_FAILED: target not found or not clickable",
      )
    }

    return Result.ok(
      buildJsonObject {
        put("ok", JsonPrimitive(true))
        if (path != null) put("path", JsonPrimitive(path))
        if (query != null) put("query", JsonPrimitive(query))
      }.toString(),
    )
  }

  suspend fun handleUiWaitFor(paramsJson: String?): Result {
    val payload = parseObject(paramsJson)
    val query = payload?.get("query").asStringOrNull()?.trim().orEmpty()
    val timeoutMs = payload?.get("timeoutMs").asNumberOrNull()?.toLong()?.coerceIn(100L, 15_000L) ?: 3_000L
    val pollMs = payload?.get("pollMs").asNumberOrNull()?.toLong()?.coerceIn(50L, 1_000L) ?: 150L
    val expectGone = payload?.get("expectGone").asBooleanOrNull() ?: false

    if (query.isEmpty()) {
      return Result.error(
        code = "INVALID_REQUEST",
        message = "INVALID_REQUEST: query required",
      )
    }
    if (!OpenClawAccessibilityService.isActive()) {
      return Result.error(
        code = "ACCESSIBILITY_DISABLED",
        message = "ACCESSIBILITY_DISABLED: enable OpenClaw Accessibility service in Android settings",
      )
    }

    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start <= timeoutMs) {
      val exists = OpenClawAccessibilityService.exists(query)
      val hit = if (expectGone) !exists else exists
      if (hit) {
        return Result.ok(
          buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("query", JsonPrimitive(query))
            put("expectGone", JsonPrimitive(expectGone))
            put("elapsedMs", JsonPrimitive(System.currentTimeMillis() - start))
          }.toString(),
        )
      }
      delay(pollMs)
    }

    return Result.error(
      code = "UI_WAIT_TIMEOUT",
      message = "UI_WAIT_TIMEOUT: condition not reached within timeout",
    )
  }

  private fun parseObject(paramsJson: String?): JsonObject? {
    val trimmed = paramsJson?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed == "{}") return null
    return try {
      json.parseToJsonElement(trimmed).asObjectOrNull()
    } catch (_: Throwable) {
      null
    }
  }

  private fun kotlinx.serialization.json.JsonElement?.asNumberOrNull(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.doubleOrNull ?: primitive.content.toDoubleOrNull()
  }

  private fun kotlinx.serialization.json.JsonElement?.asStringOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.contentOrNull
  }

  private fun kotlinx.serialization.json.JsonElement?.asBooleanOrNull(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.booleanOrNull
  }

  data class Result(val okPayloadJson: String?, val errorCode: String?, val errorMessage: String?) {
    companion object {
      fun ok(payloadJson: String?): Result = Result(payloadJson, null, null)

      fun error(code: String, message: String): Result = Result(null, code, message)
    }
  }
}
