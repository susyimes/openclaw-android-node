package ai.openclaw.android.node

import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    val ok = OpenClawAccessibilityService.tap(x = x, y = y, durationMs = durationMs)
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

  data class Result(val okPayloadJson: String?, val errorCode: String?, val errorMessage: String?) {
    companion object {
      fun ok(payloadJson: String?): Result = Result(payloadJson, null, null)

      fun error(code: String, message: String): Result = Result(null, code, message)
    }
  }
}
