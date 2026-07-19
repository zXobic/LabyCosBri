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
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Laedt die geo.json einer Cosmetic-ID dynamisch, parst sie, baut das
 * Minecraft-Modell UND die Bone-Namen-Map (fuer Animationen). Cached das
 * Ergebnis als {@link BuiltCosmeticModel}.
 * <p>
 * Quelle: https://dl.labymod.net/cosmetics/{id}/geo.json
 */
public final class CosmeticGeometryManager {

    private static final String GEO_URL = "https://dl.labymod.net/cosmetics/%d/geo.json";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private enum State { LOADING, LOADED, FAILED }

    private static final Map<Integer, State> STATE = new ConcurrentHashMap<>();
    private static final Map<Integer, BuiltCosmeticModel> MODELS = new ConcurrentHashMap<>();

    private CosmeticGeometryManager() {
    }

    /**
     * Gibt das gebaute Modell (inkl. Bone-Map) fuer diese Cosmetic-ID zurueck,
     * sofern bereits geladen; stoesst andernfalls das Laden an und gibt
     * vorerst {@code null} zurueck.
     */
    public static BuiltCosmeticModel getModel(int cosmeticId) {
        State s = STATE.get(cosmeticId);
        if (s == null) {
            STATE.put(cosmeticId, State.LOADING);
            requestGeo(cosmeticId);
            return null;
        }
        return s == State.LOADED ? MODELS.get(cosmeticId) : null;
    }

    private static void requestGeo(int cosmeticId) {
        String url = GEO_URL.formatted(cosmeticId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        LabyCosmeticsMod.LOGGER.info(
                                "[LabyCosmetics] geo.json fuer Cosmetic {} lieferte Status {}",
                                cosmeticId, response.statusCode());
                        STATE.put(cosmeticId, State.FAILED);
                        return;
                    }

                    final String body = response.body();
                    Minecraft.getInstance().execute(() -> {
                        try {
                            BedrockGeometry geo = BedrockGeometryParser.parse(body);
                            ModelPart model = BedrockModelBuilder.build(geo);
                            Map<String, ModelPart> bones = ModelPartIndex.build(model, geo);
                            MODELS.put(cosmeticId, new BuiltCosmeticModel(model, bones, geo));
                            STATE.put(cosmeticId, State.LOADED);
                        } catch (Exception e) {
                            LabyCosmeticsMod.LOGGER.warn(
                                    "[LabyCosmetics] geo.json fuer Cosmetic {} nicht baubar: {}",
                                    cosmeticId, e.getMessage());
                            STATE.put(cosmeticId, State.FAILED);
                        }
                    });
                })
                .exceptionally(t -> {
                    LabyCosmeticsMod.LOGGER.warn(
                            "[LabyCosmetics] geo.json-Abruf fuer Cosmetic {} fehlgeschlagen: {}",
                            cosmeticId, t.getMessage());
                    STATE.put(cosmeticId, State.FAILED);
                    return null;
                });
    }
}
