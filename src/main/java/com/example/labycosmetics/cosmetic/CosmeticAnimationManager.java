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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Laedt die animation.json einer Cosmetic-ID dynamisch herunter, parst sie
 * und cached das Ergebnis. Nicht jedes Cosmetic hat eine Animation - fehlt
 * sie (404), merken wir uns "keine Animation" und rendern eben statisch.
 * <p>
 * Quelle: https://dl.labymod.net/cosmetics/{id}/animation.json
 */
public final class CosmeticAnimationManager {

    private static final String ANIM_URL = "https://dl.labymod.net/cosmetics/%d/animation.json";

    private static final LoadTracker<Integer> STATE = new LoadTracker<>();
    private static final Map<Integer, BedrockAnimation> ANIMATIONS = new ConcurrentHashMap<>();

    private CosmeticAnimationManager() {
    }

    /**
     * Gibt die geparste Animation zurueck, sofern geladen; stoesst sonst das
     * Laden an und gibt vorerst {@code null} zurueck. {@code null} heisst
     * auch dauerhaft "keine Animation vorhanden" (dann rendert der Aufrufer
     * statisch weiter).
     */
    public static BedrockAnimation getAnimation(int cosmeticId) {
        if (STATE.tryStart(cosmeticId)) {
            requestAnim(cosmeticId);
            return null;
        }
        return STATE.isLoaded(cosmeticId) ? ANIMATIONS.get(cosmeticId) : null;
    }

    private static void requestAnim(int cosmeticId) {
        String url = ANIM_URL.formatted(cosmeticId);

        Http.getString(url, Duration.ofSeconds(10))
                .thenAccept(response -> {
                    if (response.statusCode() == 404) {
                        // kein Animations-File -> statisch rendern
                        STATE.absent(cosmeticId);
                        return;
                    }
                    if (response.statusCode() != 200) {
                        STATE.failed(cosmeticId);
                        return;
                    }
                    try {
                        BedrockAnimation anim = BedrockAnimationParser.parse(response.body());
                        ANIMATIONS.put(cosmeticId, anim);
                        STATE.loaded(cosmeticId);
                    } catch (Exception e) {
                        LabyCosmeticsMod.LOGGER.warn(
                                "[LabyCosmetics] animation.json fuer Cosmetic {} nicht lesbar: {}",
                                cosmeticId, e.getMessage());
                        STATE.absent(cosmeticId);
                    }
                })
                .exceptionally(t -> {
                    LabyCosmeticsMod.LOGGER.warn(
                            "[LabyCosmetics] animation.json-Abruf fuer Cosmetic {} fehlgeschlagen: {}",
                            cosmeticId, t.getMessage());
                    STATE.failed(cosmeticId);
                    return null;
                });
    }
}
