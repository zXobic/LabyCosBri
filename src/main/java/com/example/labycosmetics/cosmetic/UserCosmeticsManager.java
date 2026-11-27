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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>
 * 404 = kein LabyMod-Nutzer -> bis zum naechsten Join nicht neu fragen.
 * Timeout/5xx -> mit Abstand neu versuchen (siehe {@link LoadTracker}).
 */
public final class UserCosmeticsManager {

    private static final String USERDATA_URL = "https://dl.labymod.net/userdata/%s.json";

    /** Ein getragenes Cosmetic: die ID und die zugehoerigen Daten (d-Array). */
    public record WornCosmetic(int id, List<String> data) {
        /** Erster Daten-Eintrag ist bei texturbasierten Cosmetics die Textur-UUID. */
        public String textureUuid() {
            return data.isEmpty() ? null : data.get(0);
        }
    }

    private static final LoadTracker<UUID> STATE = new LoadTracker<>();
    // Pro Spieler: Map von Cosmetic-ID -> WornCosmetic (schneller Zugriff nach ID)
    private static final Map<UUID, Map<Integer, WornCosmetic>> WORN = new ConcurrentHashMap<>();

    private UserCosmeticsManager() {
    }

    /**
     * Verwirft alle geladenen Trage-Daten. Der naechste Zugriff stoesst dann
     * pro Spieler ein frisches Laden an. Wird beim Server-Join gerufen, damit
     * nach einem (Re)Connect der aktuelle Trage-Zustand gilt.
     */
    public static void invalidateAll() {
        STATE.clear();
        WORN.clear();
        LabyCosmeticsMod.LOGGER.info("[LabyCosmetics] Trage-Cache geleert (Server-Join).");
    }

    /** Liefert die Trage-Map des Spielers, falls geladen; stoesst sonst das Laden an. */
    private static Map<Integer, WornCosmetic> wornMap(UUID playerUuid) {
        if (STATE.tryStart(playerUuid)) {
            requestUserData(playerUuid);
            return null;
        }
        return STATE.isLoaded(playerUuid) ? WORN.get(playerUuid) : null;
    }

    /**
     * Gibt das getragene Cosmetic dieser ID zurueck, falls der Spieler es
     * traegt und die Daten schon geladen sind, sonst {@code null}.
     */
    public static WornCosmetic getWorn(UUID playerUuid, int cosmeticId) {
        Map<Integer, WornCosmetic> map = wornMap(playerUuid);
        return map == null ? null : map.get(cosmeticId);
    }

    /**
     * Alle getragenen Cosmetics des Spielers, deren Kategorie in {@code categories}
     * liegt. Fuer Layer, die mehrere Cosmetics gleichzeitig rendern (Wing + Aura + ...).
     * Leere Liste, solange die Daten noch laden.
     */
    public static List<Integer> findAllWornByCategories(UUID playerUuid, Set<String> categories) {
        Map<Integer, WornCosmetic> map = wornMap(playerUuid);
        if (map == null) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        for (Integer id : map.keySet()) {
            CosmeticCatalog.CosmeticMeta meta = CosmeticCatalog.get(id);
            if (meta != null && categories.contains(meta.category())) {
                result.add(id);
            }
        }
        return result;
    }

    private static void requestUserData(UUID playerUuid) {
        String url = USERDATA_URL.formatted(playerUuid.toString());

        Http.getString(url, Duration.ofSeconds(8))
                .thenAccept(response -> {
                    if (response.statusCode() == 404) {
                        // kein LabyMod-Nutzer - kein Fehler, nicht neu fragen
                        STATE.absent(playerUuid);
                        return;
                    }
                    if (response.statusCode() != 200) {
                        LabyCosmeticsMod.LOGGER.info(
                                "[LabyCosmetics] userdata fuer {} lieferte Status {}",
                                playerUuid, response.statusCode());
                        STATE.failed(playerUuid);
                        return;
                    }
                    try {
                        parseAndStore(playerUuid, response.body());
                        STATE.loaded(playerUuid);
                    } catch (Exception e) {
                        LabyCosmeticsMod.LOGGER.warn(
                                "[LabyCosmetics] userdata fuer {} nicht lesbar: {}", playerUuid, e.getMessage());
                        STATE.failed(playerUuid);
                    }
                })
                .exceptionally(t -> {
                    LabyCosmeticsMod.LOGGER.warn(
                            "[LabyCosmetics] userdata-Abruf fuer {} fehlgeschlagen: {}", playerUuid, t.getMessage());
                    STATE.failed(playerUuid);
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
                        data.add(d.isJsonNull() ? null : d.getAsString());
                    }
                }
                worn.put(id, new WornCosmetic(id, data));
            }
        }

        WORN.put(playerUuid, worn);
        LabyCosmeticsMod.LOGGER.debug(
                "[LabyCosmetics] {} traegt {} Cosmetics.", playerUuid, worn.size());
    }
}
