package com.a1111remote.app.model

import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    @Test fun `txt2img payload with adetailer`() {
        val settings = GenerationSettings(prompt="test", checkpoint="my-model", adetailer=AdetailerSettings(enabled=true))
        val json = settings.toPayload()
        assertEquals("test", json.getString("prompt"))
        assertEquals("my-model", json.getJSONObject("override_settings").getString("sd_model_checkpoint"))
        val args = json.getJSONObject("alwayson_scripts").getJSONObject("ADetailer").getJSONArray("args")
        assertTrue(args.getBoolean(0))
        assertFalse(args.getBoolean(1))
        assertEquals("face_yolov8n.pt", args.getJSONObject(2).getString("ad_model"))
    }

    @Test fun `roundtrip settings`() {
        val start = GenerationSettings(width=832, adetailer=AdetailerSettings(enabled=true, confidence=0.55f))
        assertEquals(start, GenerationSettings.fromJson(start.toJson()))
    }

    @Test fun `adetailer not sent when off`() {
        assertFalse(GenerationSettings().toPayload().has("alwayson_scripts"))
    }
    @Test fun `adetailer face and hand are independently toggleable`() {
        val settings = GenerationSettings(
            adetailer = AdetailerSettings(enabled = true, model = "face_yolov8n.pt"),
            additionalAdetailers = listOf(AdetailerSettings(enabled = false, model = "hand_yolov8n.pt"))
        )
        val args = settings.toPayload().getJSONObject("alwayson_scripts")
            .getJSONObject("ADetailer").getJSONArray("args")
        assertEquals(4, args.length())
        assertTrue(args.getJSONObject(2).getBoolean("ad_tab_enable"))
        assertFalse(args.getJSONObject(3).getBoolean("ad_tab_enable"))
        val handOnly = settings.copy(adetailer = settings.adetailer.copy(enabled = false),
            additionalAdetailers = listOf(settings.additionalAdetailers[0].copy(enabled = true)))
        val handArgs = handOnly.toPayload().getJSONObject("alwayson_scripts")
            .getJSONObject("ADetailer").getJSONArray("args")
        assertFalse(handArgs.getJSONObject(2).getBoolean("ad_tab_enable"))
        assertTrue(handArgs.getJSONObject(3).getBoolean("ad_tab_enable"))
        assertEquals(handOnly, GenerationSettings.fromJson(handOnly.toJson()))
    }

    @Test fun `img2img and Extras settings can be serialized`() {
        val settings = GenerationSettings(img2imgDenoising = .45f)
        assertEquals(settings, GenerationSettings.fromJson(settings.toJson()))
        val extras = ExtrasSettings(upscaler = "Lanczos", scale = 3f, gfpgan = .2f)
        assertEquals(extras, ExtrasSettings.fromJson(extras.toJson()))
    }

}
