package com.a1111remote.app.model

import org.json.JSONArray
import org.json.JSONObject

/** Simple runtime configuration; the password is saved separately in Android Keystore. */
data class ConnectionSettings(
    val lanUrl: String = "http://192.168.1.100:7860",
    val vpnUrl: String = "",
    val username: String = "",
    val password: String = ""
)

data class AdetailerSettings(
    val enabled: Boolean = false,
    val model: String = "face_yolov8n.pt",
    val prompt: String = "",
    val negativePrompt: String = "",
    val confidence: Float = 0.3f,
    val denoising: Float = 0.4f,
    val maskBlur: Int = 4,
    val padding: Int = 32,
    val dilation: Int = 4,
    val onlyMasked: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ad_model", model.trim())
        .put("ad_tab_enable", true)
        .put("ad_prompt", prompt)
        .put("ad_negative_prompt", negativePrompt)
        .put("ad_confidence", confidence.toDouble())
        .put("ad_denoising_strength", denoising.toDouble())
        .put("ad_mask_blur", maskBlur)
        .put("ad_inpaint_only_masked_padding", padding)
        .put("ad_dilate_erode", dilation)
        .put("ad_inpaint_only_masked", onlyMasked)
}

data class GenerationSettings(
    val prompt: String = "",
    val negativePrompt: String = "",
    val sampler: String = "Euler",
    val scheduler: String = "Automatic",
    val checkpoint: String = "",
    val width: Int = 512,
    val height: Int = 768,
    val steps: Int = 25,
    val cfgScale: Float = 7f,
    val seed: Long = -1,
    val batchSize: Int = 1,
    val hiresEnabled: Boolean = false,
    val hiresScale: Float = 1.5f,
    val hiresDenoising: Float = 0.4f,
    val adetailer: AdetailerSettings = AdetailerSettings()
) {
    fun toPayload(): JSONObject {
        val o = JSONObject()
            .put("prompt", prompt)
            .put("negative_prompt", negativePrompt)
            .put("sampler_name", sampler)
            .put("scheduler", scheduler)
            .put("steps", steps.coerceIn(1, 150))
            .put("cfg_scale", cfgScale.coerceIn(1f, 30f).toDouble())
            .put("width", width.coerceIn(64, 2048))
            .put("height", height.coerceIn(64, 2048))
            .put("seed", seed)
            .put("batch_size", batchSize.coerceIn(1, 8))
            .put("send_images", true)
            .put("save_images", true)
            .put("enable_hr", hiresEnabled)
        if (hiresEnabled) {
            o.put("hr_scale", hiresScale.coerceIn(1f, 4f).toDouble())
            o.put("denoising_strength", hiresDenoising.coerceIn(0f, 1f).toDouble())
        }
        if (checkpoint.isNotBlank()) {
            o.put("override_settings", JSONObject().put("sd_model_checkpoint", checkpoint))
        }
        if (adetailer.enabled) {
            // Current Bing-su ADetailer API: true = enabled, false = do not skip img2img.
            val args = JSONArray().put(true).put(false).put(adetailer.toJson())
            o.put("alwayson_scripts", JSONObject().put("ADetailer", JSONObject().put("args", args)))
        }
        return o
    }

    fun toJson(): JSONObject = JSONObject()
        .put("prompt", prompt)
        .put("negativePrompt", negativePrompt)
        .put("sampler", sampler)
        .put("scheduler", scheduler)
        .put("checkpoint", checkpoint)
        .put("width", width)
        .put("height", height)
        .put("steps", steps)
        .put("cfgScale", cfgScale.toDouble())
        .put("seed", seed)
        .put("batchSize", batchSize)
        .put("hiresEnabled", hiresEnabled)
        .put("hiresScale", hiresScale.toDouble())
        .put("hiresDenoising", hiresDenoising.toDouble())
        .put("adetailer", JSONObject()
            .put("enabled", adetailer.enabled)
            .put("model", adetailer.model)
            .put("prompt", adetailer.prompt)
            .put("negativePrompt", adetailer.negativePrompt)
            .put("confidence", adetailer.confidence.toDouble())
            .put("denoising", adetailer.denoising.toDouble())
            .put("maskBlur", adetailer.maskBlur)
            .put("padding", adetailer.padding)
            .put("dilation", adetailer.dilation)
            .put("onlyMasked", adetailer.onlyMasked)
        )

    companion object {
        fun fromJson(json: JSONObject): GenerationSettings {
            val a = json.optJSONObject("adetailer") ?: JSONObject()
            return GenerationSettings(
                prompt = json.optString("prompt"),
                negativePrompt = json.optString("negativePrompt"),
                sampler = json.optString("sampler", "Euler"),
                scheduler = json.optString("scheduler", "Automatic"),
                checkpoint = json.optString("checkpoint"),
                width = json.optInt("width", 512),
                height = json.optInt("height", 768),
                steps = json.optInt("steps", 25),
                cfgScale = json.optDouble("cfgScale", 7.0).toFloat(),
                seed = json.optLong("seed", -1),
                batchSize = json.optInt("batchSize", 1),
                hiresEnabled = json.optBoolean("hiresEnabled", false),
                hiresScale = json.optDouble("hiresScale", 1.5).toFloat(),
                hiresDenoising = json.optDouble("hiresDenoising", 0.4).toFloat(),
                adetailer = AdetailerSettings(
                    enabled = a.optBoolean("enabled", false),
                    model = a.optString("model", "face_yolov8n.pt"),
                    prompt = a.optString("prompt"),
                    negativePrompt = a.optString("negativePrompt"),
                    confidence = a.optDouble("confidence", 0.3).toFloat(),
                    denoising = a.optDouble("denoising", 0.4).toFloat(),
                    maskBlur = a.optInt("maskBlur", 4),
                    padding = a.optInt("padding", 32),
                    dilation = a.optInt("dilation", 4),
                    onlyMasked = a.optBoolean("onlyMasked", true)
                )
            )
        }
    }
}
