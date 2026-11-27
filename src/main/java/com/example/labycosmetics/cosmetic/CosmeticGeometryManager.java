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
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;

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

    private static final LoadTracker<Integer> STATE = new LoadTracker<>();
    private static final Map<Integer, BuiltCosmeticModel> MODELS = new ConcurrentHashMap<>();

    private CosmeticGeometryManager() {
    }

    /**
     * Gibt das gebaute Modell (inkl. Bone-Map) fuer diese Cosmetic-ID zurueck,
     * sofern bereits geladen; stoesst andernfalls das Laden an und gibt
     * vorerst {@code null} zurueck.
     */
    public static BuiltCosmeticModel getModel(int cosmeticId) {
        if (STATE.tryStart(cosmeticId)) {
            requestGeo(cosmeticId);
            return null;
        }
        return STATE.isLoaded(cosmeticId) ? MODELS.get(cosmeticId) : null;
    }

    private static void requestGeo(int cosmeticId) {
        String url = GEO_URL.formatted(cosmeticId);

        Http.getString(url, Duration.ofSeconds(10))
                .thenAccept(response -> {
                    if (response.statusCode() == 404) {
                        STATE.absent(cosmeticId);
                        return;
                    }
                    if (response.statusCode() != 200) {
                        LabyCosmeticsMod.LOGGER.info(
                                "[LabyCosmetics] geo.json fuer Cosmetic {} lieferte Status {}",
                                cosmeticId, response.statusCode());
                        STATE.failed(cosmeticId);
                        return;
                    }

                    final String body = response.body();
                    // Bauen auf dem Render-Thread: ModelPart-Objekte werden dort
                    // gelesen, so gibt es kein halbfertiges Modell.
                    Minecraft.getInstance().execute(() -> {
                        try {
                            BedrockGeometry geo = BedrockGeometryParser.parse(body);
                            ModelPart model = BedrockModelBuilder.build(geo);
                            Map<String, ModelPart> bones = ModelPartIndex.build(model, geo);
                            MODELS.put(cosmeticId, new BuiltCosmeticModel(model, bones, geo));
                            STATE.loaded(cosmeticId);
                        } catch (Exception e) {
                            // Kaputte Datei wird nicht besser - nicht neu laden.
                            LabyCosmeticsMod.LOGGER.warn(
                                    "[LabyCosmetics] geo.json fuer Cosmetic {} nicht baubar: {}",
                                    cosmeticId, e.getMessage());
                            STATE.absent(cosmeticId);
                        }
                    });
                })
                .exceptionally(t -> {
                    LabyCosmeticsMod.LOGGER.warn(
                            "[LabyCosmetics] geo.json-Abruf fuer Cosmetic {} fehlgeschlagen: {}",
                            cosmeticId, t.getMessage());
                    STATE.failed(cosmeticId);
                    return null;
                });
    }
}
