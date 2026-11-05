package com.a1111remote.app.model

import org.json.JSONArray
import org.json.JSONObject

enum class GenerationMode { TXT2IMG, IMG2IMG, EXTRAS }

/** Extras is post-processing, not diffusion generation. */
data class ExtrasSettings(
    val upscaler: String = "Lanczos",
    val scale: Float = 2f,
    val gfpgan: Float = 0f,
    val codeformer: Float = 0f,
    val codeformerWeight: Float = 0.5f
) {
    fun toJson(): JSONObject = JSONObject()
        .put("upscaler", upscaler).put("scale", scale)
        .put("gfpgan", gfpgan).put("codeformer", codeformer)
        .put("codeformerWeight", codeformerWeight)
    companion object {
        fun fromJson(o: JSONObject) = ExtrasSettings(
            upscaler = o.optString("upscaler", "Lanczos"),
            scale = o.optDouble("scale", 2.0).toFloat(),
            gfpgan = o.optDouble("gfpgan", 0.0).toFloat(),
            codeformer = o.optDouble("codeformer", 0.0).toFloat(),
            codeformerWeight = o.optDouble("codeformerWeight", 0.5).toFloat()
        )
    }
}

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
        .put("ad_tab_enable", enabled)
        .put("ad_prompt", prompt)
        .put("ad_negative_prompt", negativePrompt)
        .put("ad_confidence", confidence.toDouble())
        .put("ad_denoising_strength", denoising.toDouble())
        .put("ad_mask_blur", maskBlur)
        .put("ad_inpaint_only_masked_padding", padding)
        .put("ad_dilate_erode", dilation)
        .put("ad_inpaint_only_masked", onlyMasked)
    fun toStoredJson(): JSONObject = JSONObject()
        .put("enabled", enabled).put("model", model)
        .put("prompt", prompt).put("negativePrompt", negativePrompt)
        .put("confidence", confidence.toDouble()).put("denoising", denoising.toDouble())
        .put("maskBlur", maskBlur).put("padding", padding)
        .put("dilation", dilation).put("onlyMasked", onlyMasked)
    companion object {
        fun fromStoredJson(a: JSONObject) = AdetailerSettings(
            enabled = a.optBoolean("enabled", false),
            model = a.optString("model", "face_yolov8n.pt"),
            prompt = a.optString("prompt"), negativePrompt = a.optString("negativePrompt"),
            confidence = a.optDouble("confidence", 0.3).toFloat(),
            denoising = a.optDouble("denoising", 0.4).toFloat(),
            maskBlur = a.optInt("maskBlur", 4), padding = a.optInt("padding", 32),
            dilation = a.optInt("dilation", 4), onlyMasked = a.optBoolean("onlyMasked", true)
        )
    }
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
    val batchCount: Int = 1,
    val hiresEnabled: Boolean = false,
    val hiresScale: Float = 1.5f,
    val hiresDenoising: Float = 0.4f,
    val adetailer: AdetailerSettings = AdetailerSettings(),
    val additionalAdetailers: List<AdetailerSettings> = listOf(AdetailerSettings(model = "hand_yolov8n.pt")),
    val img2imgDenoising: Float = 0.7f
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
            .put("n_iter", batchCount.coerceIn(1, 100))
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
        val units = listOf(adetailer) + additionalAdetailers
        if (units.any { it.enabled }) {
            // API: global enabled flag, skip_img2img, then each independently enabled tab.
            val args = JSONArray().put(true).put(false)
            units.forEach { args.put(it.toJson()) }
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
        .put("batchCount", batchCount)
        .put("hiresEnabled", hiresEnabled)
        .put("hiresScale", hiresScale.toDouble())
        .put("hiresDenoising", hiresDenoising.toDouble())
        .put("img2imgDenoising", img2imgDenoising.toDouble())
        .put("additionalAdetailers", JSONArray().apply { additionalAdetailers.forEach { put(it.toStoredJson()) } })
        .put("adetailer", adetailer.toStoredJson())

    companion object {
        fun fromJson(json: JSONObject): GenerationSettings {
            val a = json.optJSONObject("adetailer") ?: JSONObject()
            val extras = json.optJSONArray("additionalAdetailers")
            val additional = if (extras != null) (0 until extras.length())
                .mapNotNull { extras.optJSONObject(it)?.let(AdetailerSettings::fromStoredJson) }
            else listOf(AdetailerSettings(model = "hand_yolov8n.pt"))
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
                batchCount = json.optInt("batchCount", 1),
                hiresEnabled = json.optBoolean("hiresEnabled", false),
                hiresScale = json.optDouble("hiresScale", 1.5).toFloat(),
                hiresDenoising = json.optDouble("hiresDenoising", 0.4).toFloat(),
                img2imgDenoising = json.optDouble("img2imgDenoising", 0.7).toFloat(),
                additionalAdetailers = additional,
                adetailer = AdetailerSettings.fromStoredJson(a)
            )
        }
    }
}
