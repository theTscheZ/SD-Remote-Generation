package com.a1111remote.app.model

import org.json.JSONObject

/** Apply only ADetailer fields found in PC infotext; don't overwrite unsaved values. */
object PcParameterMapper {
    fun restoreAdetailers(previous: GenerationSettings, data: JSONObject): GenerationSettings {
        val tabs = data.optJSONArray("adetailer") ?: return previous
        if (tabs.length() == 0) return previous
        val original = listOf(previous.adetailer) + previous.additionalAdetailers
        val restored = (0 until tabs.length().coerceAtMost(5)).map { index ->
            val current = original.getOrElse(index) { AdetailerSettings() }
            val obj = tabs.optJSONObject(index) ?: JSONObject()
            fun str(name: String, default: String) = if (obj.has(name)) obj.optString(name, default) else default
            fun integer(name: String, default: Int) = if (obj.has(name))
                obj.optString(name).toDoubleOrNull()?.toInt() ?: default else default
            fun decimal(name: String, default: Float) = if (obj.has(name))
                obj.optString(name).toFloatOrNull() ?: default else default
            fun boolean(name: String, default: Boolean) = if (obj.has(name)) {
                when (obj.optString(name).lowercase()) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> default
                }
            } else default
            val model = str("model", current.model)
            current.copy(
                enabled = if (obj.has("model")) model.isNotBlank() &&
                    !model.equals("None", ignoreCase = true) else current.enabled,
                model = model,
                prompt = str("prompt", current.prompt),
                negativePrompt = str("negative_prompt", current.negativePrompt),
                confidence = decimal("confidence", current.confidence).coerceIn(0f, 1f),
                topK = integer("top_k", current.topK).coerceIn(0, 10),
                denoising = decimal("denoising", current.denoising).coerceIn(0f, 1f),
                maskBlur = integer("mask_blur", current.maskBlur),
                padding = integer("padding", current.padding),
                dilation = integer("dilation", current.dilation),
                onlyMasked = boolean("only_masked", current.onlyMasked)
            )
        }
        return previous.copy(
            adetailer = restored.first(),
            additionalAdetailers = restored.drop(1) + original.drop(restored.size)
        )
    }
}
