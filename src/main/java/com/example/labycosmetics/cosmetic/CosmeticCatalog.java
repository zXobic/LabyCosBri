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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Laedt einmalig den kompletten LabyMod-Cosmetic-Katalog
 * (https://dl.labymod.net/cosmetics/index.json) und stellt zu jeder
 * Cosmetic-ID die Metadaten bereit (Kategorie, Position, Skalierung,
 * texture_directory usw.).
 */
public final class CosmeticCatalog {

    private static final String INDEX_URL = "https://dl.labymod.net/cosmetics/index.json";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Metadaten eines Cosmetics aus dem Katalog. */
    public record CosmeticMeta(
            int id,
            String category,          // WING, HAT, CLOAK, ...
            String position,          // BACK, HEAD_TOP, ... (kann null sein)
            String textureDirectory,  // z.B. "1460" (kann null sein)
            float scale,
            java.util.List<String> defaultData,   // [textur-uuid, farbe1, ...]
            int frameAspectWidth,     // frame_aspect_ratio.width  (Frame-Seitenverhaeltnis)
            int frameAspectHeight) {  // frame_aspect_ratio.height
    }

    private enum State { NOT_STARTED, LOADING, LOADED, FAILED }

    private static volatile State state = State.NOT_STARTED;
    private static final Map<Integer, CosmeticMeta> CATALOG = new ConcurrentHashMap<>();

    private CosmeticCatalog() {
    }

    /** Startet das Laden des Katalogs, falls noch nicht geschehen. */
    public static void ensureLoading() {
        if (state == State.NOT_STARTED) {
            state = State.LOADING;
            requestIndex();
        }
    }

    /** Metadaten zu einer Cosmetic-ID, oder {@code null} wenn (noch) nicht geladen. */
    public static CosmeticMeta get(int cosmeticId) {
        ensureLoading();
        return CATALOG.get(cosmeticId);
    }

    public static boolean isLoaded() {
        return state == State.LOADED;
    }

    private static void requestIndex() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(INDEX_URL))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        LabyCosmeticsMod.LOGGER.warn(
                                "[LabyCosmetics] index.json lieferte Status {}", response.statusCode());
                        state = State.FAILED;
                        return;
                    }
                    try {
                        parse(response.body());
                        state = State.LOADED;
                        LabyCosmeticsMod.LOGGER.info(
                                "[LabyCosmetics] Katalog geladen: {} Cosmetics bekannt.", CATALOG.size());
                    } catch (Exception e) {
                        LabyCosmeticsMod.LOGGER.warn(
                                "[LabyCosmetics] index.json nicht lesbar: {}", e.getMessage());
                        state = State.FAILED;
                    }
                })
                .exceptionally(t -> {
                    LabyCosmeticsMod.LOGGER.warn(
                            "[LabyCosmetics] index.json-Abruf fehlgeschlagen: {}", t.getMessage());
                    state = State.FAILED;
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

            CATALOG.put(id, new CosmeticMeta(
                    id, category, position, textureDir, scale, defaultData, frameW, frameH));
        }
    }

    private static String getStringOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return obj.get(key).getAsString();
    }
}
