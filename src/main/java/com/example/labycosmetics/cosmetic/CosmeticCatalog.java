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
            float scale) {
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

            CATALOG.put(id, new CosmeticMeta(id, category, position, textureDir, scale));
        }
    }

    private static String getStringOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return obj.get(key).getAsString();
    }
}
