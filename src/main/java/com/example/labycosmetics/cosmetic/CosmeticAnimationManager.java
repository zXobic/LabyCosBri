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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private enum State { LOADING, LOADED, NONE }

    private static final Map<Integer, State> STATE = new ConcurrentHashMap<>();
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
        State s = STATE.get(cosmeticId);
        if (s == null) {
            STATE.put(cosmeticId, State.LOADING);
            requestAnim(cosmeticId);
            return null;
        }
        return s == State.LOADED ? ANIMATIONS.get(cosmeticId) : null;
    }

    private static void requestAnim(int cosmeticId) {
        String url = ANIM_URL.formatted(cosmeticId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        // 404 = kein Animations-File -> statisch rendern
                        STATE.put(cosmeticId, State.NONE);
                        return;
                    }
                    try {
                        BedrockAnimation anim = BedrockAnimationParser.parse(response.body());
                        ANIMATIONS.put(cosmeticId, anim);
                        STATE.put(cosmeticId, State.LOADED);
                    } catch (Exception e) {
                        LabyCosmeticsMod.LOGGER.warn(
                                "[LabyCosmetics] animation.json fuer Cosmetic {} nicht lesbar: {}",
                                cosmeticId, e.getMessage());
                        STATE.put(cosmeticId, State.NONE);
                    }
                })
                .exceptionally(t -> {
                    LabyCosmeticsMod.LOGGER.warn(
                            "[LabyCosmetics] animation.json-Abruf fuer Cosmetic {} fehlgeschlagen: {}",
                            cosmeticId, t.getMessage());
                    STATE.put(cosmeticId, State.NONE);
                    return null;
                });
    }
}
