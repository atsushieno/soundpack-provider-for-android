package org.androidaudioplugin.soundpack.client

import org.androidaudioplugin.soundpack.Sf2Presets
import org.json.JSONObject

/** Decodes the presets that SF2 providers put in [SoundpackClient.Entry.metadata], so a
 * browser can list them without opening the file. */
object Sf2Metadata {
    fun presets(entry: SoundpackClient.Entry): List<Sf2Presets.Preset> {
        val json = entry.metadata ?: return emptyList()
        return try {
            val presets = JSONObject(json).getJSONArray("presets")
            (0 until presets.length()).map { i ->
                presets.getJSONObject(i).let { Sf2Presets.Preset(it.getInt("bank"), it.getInt("program"), it.getString("name")) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
