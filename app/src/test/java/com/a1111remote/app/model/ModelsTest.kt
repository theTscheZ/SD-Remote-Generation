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
}
