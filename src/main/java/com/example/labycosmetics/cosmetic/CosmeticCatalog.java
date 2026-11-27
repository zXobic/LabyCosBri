/*
 * Cosmetic Bridge LabyCosBri - displays LabyMod cosmetics without the LabyMod client
 * Copyright (C) 2026  zXobic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.example.labycosmetics.cosmetic;

import com.example.labycosmetics.LabyCosmeticsMod;
import com.example.labycosmetics.api.Http;
import com.example.labycosmetics.api.LoadTracker;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Laedt den kompletten LabyMod-Cosmetic-Katalog
 * (https://dl.labymod.net/cosmetics/index.json) und stellt zu jeder
 * Cosmetic-ID die Metadaten bereit (Kategorie, Position, Skalierung,
 * texture_directory usw.).
 * <p>
 * Schlaegt der Abruf fehl, wird mit wachsendem Abstand neu versucht (siehe
 * {@link LoadTracker}) - frueher blieb der Katalog dann bis zum Neustart leer.
 */
public final class CosmeticCatalog {

    private static final String INDEX_URL = "https://dl.labymod.net/cosmetics/index.json";
    private static final String KEY = "index";

    /** Metadaten eines Cosmetics aus dem Katalog. */
    public record CosmeticMeta(
            int id,
            String category,          // WING, HAT, CLOAK, ...
            String position,          // BACK, HEAD_TOP, ... (kann null sein)
            String textureDirectory,  // z.B. "1460" (kann null sein)
            float scale,
            java.util.List<String> defaultData,   // [textur-uuid, farbe1, ...]
            int frameAspectWidth,     // frame_aspect_ratio.width  (Frame-Seitenverhaeltnis)
            int frameAspectHeight,    // frame_aspect_ratio.height
            String attachedTo,        // HEAD, BODY, ARM, LEG (kann null sein)
            java.util.List<String> options) { // Bedeutung der d-Eintraege, z.B. [texture, rgb, offset]

        /** Haengt am Kopf (dreht mit Yaw/Pitch mit)? Katalog-Scan: alle HAT, FACE und
         *  66 von 68 HEADGEAR sind HEAD - die Kategorie allein reicht also nicht. */
        public boolean attachedToHead() {
            return "HEAD".equals(attachedTo);
        }
    }

    private static final LoadTracker<String> STATE = new LoadTracker<>();
    private static final Map<Integer, CosmeticMeta> CATALOG = new ConcurrentHashMap<>();

    private CosmeticCatalog() {
    }

    /** Startet das Laden des Katalogs, falls noch nicht geschehen (oder Retry faellig). */
    public static void ensureLoading() {
        if (STATE.tryStart(KEY)) {
            requestIndex();
        }
    }

    /** Metadaten zu einer Cosmetic-ID, oder {@code null} wenn (noch) nicht geladen. */
    public static CosmeticMeta get(int cosmeticId) {
        ensureLoading();
        return CATALOG.get(cosmeticId);
    }

    public static boolean isLoaded() {
        return STATE.isLoaded(KEY);
    }

    private static void requestIndex() {
        Http.getString(INDEX_URL, Duration.ofSeconds(15))
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        LabyCosmeticsMod.LOGGER.warn(
                                "[LabyCosmetics] index.json lieferte Status {}", response.statusCode());
                        STATE.failed(KEY);
                        return;
                    }
                    try {
                        parse(response.body());
                        STATE.loaded(KEY);
                        LabyCosmeticsMod.LOGGER.info(
                                "[LabyCosmetics] Katalog geladen: {} Cosmetics bekannt.", CATALOG.size());
                    } catch (Exception e) {
                        LabyCosmeticsMod.LOGGER.warn(
                                "[LabyCosmetics] index.json nicht lesbar: {}", e.getMessage());
                        STATE.failed(KEY);
                    }
                })
                .exceptionally(t -> {
                    LabyCosmeticsMod.LOGGER.warn(
                            "[LabyCosmetics] index.json-Abruf fehlgeschlagen: {}", t.getMessage());
                    STATE.failed(KEY);
                    return null;
                });
    }

    private static void parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject cosmetics = root.getAsJsonObject("cosmetics");
        if (cosmetics == null) {
            return;
        }

        for (String key : cosmetics.keySet()) {
            JsonObject obj = cosmetics.getAsJsonObject(key);

            int id = obj.get("id").getAsInt();
            String category = getStringOrNull(obj, "category");
            String position = getStringOrNull(obj, "position");
            String textureDir = getStringOrNull(obj, "texture_directory");
            float scale = obj.has("scale") ? obj.get("scale").getAsFloat() : 1.0F;

            java.util.List<String> defaultData = new java.util.ArrayList<>();
            if (obj.has("default_data") && obj.get("default_data").isJsonArray()) {
                for (var el : obj.getAsJsonArray("default_data")) {
                    defaultData.add(el.isJsonNull() ? null : el.getAsString());
                }
            }

            // frame_aspect_ratio gibt die FORM eines einzelnen Animations-Frames an
            // (z.B. 128:64 = Frame doppelt so breit wie hoch). Fehlt es, nehmen wir 1:1
            // (quadratisch). Wird zum korrekten Zerschneiden von Sprite-Sheets gebraucht
            // - siehe CosmeticTextureManager.
            int frameW = 1, frameH = 1;
            if (obj.has("frame_aspect_ratio") && obj.get("frame_aspect_ratio").isJsonObject()) {
                JsonObject far = obj.getAsJsonObject("frame_aspect_ratio");
                if (far.has("width"))  { frameW = far.get("width").getAsInt(); }
                if (far.has("height")) { frameH = far.get("height").getAsInt(); }
                if (frameW <= 0) { frameW = 1; }
                if (frameH <= 0) { frameH = 1; }
            }

            String attachedTo = getStringOrNull(obj, "attached_to");
            java.util.List<String> options = new java.util.ArrayList<>();
            if (obj.has("options") && obj.get("options").isJsonArray()) {
                for (var el : obj.getAsJsonArray("options")) {
                    options.add(el.isJsonNull() ? "" : el.getAsString());
                }
            }

            CATALOG.put(id, new CosmeticMeta(
                    id, category, position, textureDir, scale, defaultData, frameW, frameH,
                    attachedTo, java.util.List.copyOf(options)));
        }
    }

    private static String getStringOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return obj.get(key).getAsString();
    }
}
