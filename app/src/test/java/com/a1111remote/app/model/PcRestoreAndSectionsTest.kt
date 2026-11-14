package com.a1111remote.app.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PcRestoreAndSectionsTest {
    @Test fun `ADetailer model prompt mask and top values are restored`() {
        val tabs = JSONArray().put(JSONObject()
            .put("model", "face_yolov8n.pt")
            .put("prompt", "detailed face")
            .put("negative_prompt", "blur")
            .put("top_k", 2)
            .put("confidence", 0.6)
            .put("dilation", 8))
        val previous = GenerationSettings()
        val updated = PcParameterMapper.restoreAdetailers(previous, JSONObject().put("adetailer", tabs))
        assertTrue(updated.adetailer.enabled)
        assertEquals("detailed face", updated.adetailer.prompt)
        assertEquals("blur", updated.adetailer.negativePrompt)
        assertEquals(2, updated.adetailer.topK)
        assertEquals(0.6f, updated.adetailer.confidence, 0.001f)
        assertEquals(8, updated.adetailer.dilation)
        assertEquals("hand_yolov8n.pt", updated.additionalAdetailers[0].model)
        assertEquals(2, updated.toPayload().getJSONObject("alwayson_scripts")
            .getJSONObject("ADetailer").getJSONArray("args")
            .getJSONObject(2).getInt("ad_mask_k"))
    }

    @Test fun `missing ADetailer keys preserve existing settings`() {
        val base = GenerationSettings(adetailer = AdetailerSettings(
            prompt = "keep this", topK = 5, enabled = true
        ))
        val changed = PcParameterMapper.restoreAdetailers(base,
            JSONObject().put("adetailer", JSONArray().put(JSONObject().put("negative_prompt", "new"))))
        assertEquals("keep this", changed.adetailer.prompt)
        assertEquals(5, changed.adetailer.topK)
        assertTrue(changed.adetailer.enabled)
        assertEquals("new", changed.adetailer.negativePrompt)
        assertEquals(base, PcParameterMapper.restoreAdetailers(base, JSONObject()))
    }

    @Test fun `order and expand presets are persisted`() {
        val preferences = SectionPreferences().move("adetailer", -1)
            .copy(defaultExpanded = SectionPreferences().defaultExpanded + ("hires" to false),
                defaultEnabled = mapOf("hires" to true, "adetailer" to false))
        assertEquals(preferences, SectionPreferences.fromJson(preferences.toJson()))
        val original = SectionPreferences.fromJson(JSONObject().put("order",
            JSONArray().put("adetailer").put("adetailer").put("bogus")))
        assertEquals(SectionPreferences.KEYS.size, original.order.size)
        assertEquals("adetailer", original.order.first())
    }

    @Test fun `legacy layout removes editor modes and inserts independent LoRAs`() {
        val previous = JSONObject().put("order", JSONArray()
            .put("adetailer").put("img2img").put("generation")
            .put("extras").put("hires"))
        val migrated = SectionPreferences.fromJson(previous)
        assertEquals(
            listOf("adetailer", "generation", "loras", "hires"),
            migrated.order
        )
        assertFalse(migrated.order.contains("img2img"))
        assertFalse(migrated.order.contains("extras"))
        assertEquals(migrated, SectionPreferences.fromJson(migrated.toJson()))
    }

    @Test fun `ADetailer top key defaults to all detections`() {
        val default = AdetailerSettings()
        assertEquals(0, default.topK)
        assertEquals(0, default.toJson().optInt("ad_mask_k"))
        assertEquals(10, default.copy(topK = 25).toJson().optInt("ad_mask_k"))
    }
}
