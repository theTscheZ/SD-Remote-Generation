package com.a1111remote.app.model

import org.json.JSONArray
import org.json.JSONObject

/** Section order and expansion presets are independent of generation parameters. */
data class SectionPreferences(
    val order: List<String> = KEYS,
    val defaultExpanded: Map<String, Boolean> = KEYS.associateWith { it != "adetailer" },
    val defaultEnabled: Map<String, Boolean> = mapOf("hires" to false, "adetailer" to false),
    val activationDefaultsConfigured: Boolean = false
) {
    companion object {
        val KEYS = listOf("generation", "loras", "hires", "adetailer")
        val LABELS = mapOf(
            "generation" to "Generierung",
            "loras" to "LoRAs",
            "hires" to "Hires. Fix",
            "adetailer" to "ADetailer"
        )

        fun fromJson(source: JSONObject): SectionPreferences {
            val savedOrder = source.optJSONArray("order")
            val ordered = if (savedOrder == null) emptyList() else
                (0 until savedOrder.length()).map { savedOrder.optString(it) }
                    .filter { it in KEYS }.distinct()
            val expandedJson = source.optJSONObject("defaultExpanded") ?: JSONObject()
            val enabledJson = source.optJSONObject("defaultEnabled") ?: JSONObject()
            val defaults = SectionPreferences()
            return SectionPreferences(
                // Preserve the old order, remove obsolete mode-only categories,
                // and position LoRAs after Generierung on upgrades.
                order = run {
                    val migrated = if ("loras" in ordered || savedOrder == null) ordered
                        else ordered.toMutableList().apply {
                            val generationIndex = indexOf("generation")
                            if (generationIndex >= 0) add(generationIndex + 1, "loras")
                            else add("loras")
                        }
                    (migrated + KEYS).distinct()
                },
                defaultExpanded = KEYS.associateWith { key ->
                    expandedJson.optBoolean(key, defaults.defaultExpanded[key] ?: true)
                },
                defaultEnabled = listOf("hires", "adetailer").associateWith { key ->
                    enabledJson.optBoolean(key, false)
                },
                activationDefaultsConfigured = source.optBoolean("activationDefaultsConfigured", false)
            )
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("order", JSONArray().apply { order.forEach(::put) })
        .put("defaultExpanded", JSONObject().apply {
            defaultExpanded.forEach { (key, value) -> put(key, value) }
        })
        .put("activationDefaultsConfigured", activationDefaultsConfigured)
        .put("defaultEnabled", JSONObject().apply {
            defaultEnabled.forEach { (key, value) -> put(key, value) }
        })

    fun move(key: String, delta: Int): SectionPreferences {
        val position = order.indexOf(key)
        val target = position + delta
        if (position !in order.indices || target !in order.indices) return this
        val next = order.toMutableList()
        next.add(target, next.removeAt(position))
        return copy(order = next)
    }
}
