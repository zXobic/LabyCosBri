package com.example.labycosmetics.cosmetic;

import com.example.labycosmetics.LabyCosmeticsMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ruft die von einem Spieler GETRAGENEN Cosmetics ab.
 * <p>
 * Quelle: https://dl.labymod.net/userdata/{dashed-uuid}.json
 * Antwort-Format (gekuerzt):
 *   { "c": [ { "i": 1460, "d": ["875b4b5a-...","8c8989","aaaaaa",...] }, ... ] }
 * wobei "i" die Cosmetic-ID ist und "d" die getragenen Daten
 * (erste Eintraege je nach Typ: Textur-UUID, dann Farben etc.).
 */
public final class UserCosmeticsManager {

    private static final String USERDATA_URL = "https://dl.labymod.net/userdata/%s.json";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Ein getragenes Cosmetic: die ID und die zugehoerigen Daten (d-Array). */
    public record WornCosmetic(int id, List<String> data) {
        /** Erster Daten-Eintrag ist bei texturbasierten Cosmetics die Textur-UUID. */
        public String textureUuid() {
            return data.isEmpty() ? null : data.get(0);
        }
    }

    private enum State { LOADING, LOADED, FAILED }

    private static final Map<UUID, State> STATE = new ConcurrentHashMap<>();
    // Pro Spieler: Map von Cosmetic-ID -> WornCosmetic (schneller Zugriff nach ID)
    private static final Map<UUID, Map<Integer, WornCosmetic>> WORN = new ConcurrentHashMap<>();

    private UserCosmeticsManager() {
    }

    /**
     * Gibt das getragene Cosmetic dieser ID zurueck, falls der Spieler es
     * traegt und die Daten schon geladen sind. Stoesst andernfalls einmalig
     * das Laden an und gibt vorerst {@code null} zurueck.
     */
    public static WornCosmetic getWorn(UUID playerUuid, int cosmeticId) {
        State state = STATE.get(playerUuid);
        if (state == null) {
            STATE.put(playerUuid, State.LOADING);
            requestUserData(playerUuid);
            return null;
        }
        if (state != State.LOADED) {
            return null;
        }
        Map<Integer, WornCosmetic> map = WORN.get(playerUuid);
        return map == null ? null : map.get(cosmeticId);
    }

    /**
     * Sucht unter den getragenen Cosmetics des Spielers das erste, dessen
     * Katalog-Kategorie der gesuchten entspricht (z.B. "WING"). Gibt die
     * Cosmetic-ID zurueck oder {@code null}, wenn nichts passendes getragen
     * wird bzw. die Daten (userdata oder Katalog) noch nicht geladen sind.
     */
    public static Integer findWornByCategory(UUID playerUuid, String category) {
        State state = STATE.get(playerUuid);
        if (state == null) {
            STATE.put(playerUuid, State.LOADING);
            requestUserData(playerUuid);
            return null;
        }
        if (state != State.LOADED) {
            return null;
        }
        Map<Integer, WornCosmetic> map = WORN.get(playerUuid);
        if (map == null) {
            return null;
        }
        for (Integer id : map.keySet()) {
            CosmeticCatalog.CosmeticMeta meta = CosmeticCatalog.get(id);
            if (meta != null && category.equals(meta.category())) {
                return id;
            }
        }
        return null;
    }

    private static void requestUserData(UUID playerUuid) {
        String url = USERDATA_URL.formatted(playerUuid.toString());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();

        CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        LabyCosmeticsMod.LOGGER.info(
                                "[LabyCosmetics] userdata fuer {} lieferte Status {} (evtl. kein LabyMod-Nutzer)",
                                playerUuid, response.statusCode());
                        STATE.put(playerUuid, State.FAILED);
                        return;
                    }
                    try {
                        parseAndStore(playerUuid, response.body());
                        STATE.put(playerUuid, State.LOADED);
                    } catch (Exception e) {
                        LabyCosmeticsMod.LOGGER.warn(
                                "[LabyCosmetics] userdata fuer {} nicht lesbar: {}", playerUuid, e.getMessage());
                        STATE.put(playerUuid, State.FAILED);
                    }
                })
                .exceptionally(t -> {
                    LabyCosmeticsMod.LOGGER.warn(
                            "[LabyCosmetics] userdata-Abruf fuer {} fehlgeschlagen: {}", playerUuid, t.getMessage());
                    STATE.put(playerUuid, State.FAILED);
                    return null;
                });
    }

    private static void parseAndStore(UUID playerUuid, String json) {
        Map<Integer, WornCosmetic> worn = new ConcurrentHashMap<>();

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray cosmetics = root.getAsJsonArray("c");
        if (cosmetics != null) {
            for (JsonElement el : cosmetics) {
                JsonObject obj = el.getAsJsonObject();
                int id = obj.get("i").getAsInt();

                List<String> data = new ArrayList<>();
                if (obj.has("d") && obj.get("d").isJsonArray()) {
                    for (JsonElement d : obj.getAsJsonArray("d")) {
                        data.add(d.getAsString());
                    }
                }
                worn.put(id, new WornCosmetic(id, data));
            }
        }

        WORN.put(playerUuid, worn);
        LabyCosmeticsMod.LOGGER.info(
                "[LabyCosmetics] {} traegt {} Cosmetics.", playerUuid, worn.size());
    }
}
