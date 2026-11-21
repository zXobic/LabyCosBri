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
package com.example.labycosmetics.api;

import com.example.labycosmetics.LabyCosmeticsMod;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Minimaler Client fuer die (inoffizielle) LabyMod-API.
 * <p>
 * Quelle der Endpunkte: https://github.com/bribes/LabyMod-API-Docs
 * Diese API ist nicht offiziell von LabyMedia dokumentiert - Endpunkte
 * koennen sich jederzeit aendern oder verschwinden.
 */
public final class LabyModApiClient {

    private static final String CAPE_URL = "https://dl.labymod.net/capes/%s";

    /** Ergebnis eines Cape-Abrufs: Bytes, "gibt es nicht" (404) oder Fehler. */
    public record CapeResult(byte[] bytes, boolean absent) {
        static CapeResult ok(byte[] b) { return new CapeResult(b, false); }
        static CapeResult none() { return new CapeResult(null, true); }
        static CapeResult error() { return new CapeResult(null, false); }
    }

    private LabyModApiClient() {
    }

    /** Laedt das Cape-Bild (PNG) fuer eine Spieler-UUID asynchron herunter. */
    public static CompletableFuture<CapeResult> fetchCape(UUID playerUuid) {
        String url = CAPE_URL.formatted(playerUuid.toString());

        return Http.getBytes(url, Duration.ofSeconds(8))
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return CapeResult.ok(response.body());
                    }
                    // 404 = kein Cape gesetzt, ist kein Fehler
                    if (response.statusCode() == 404) {
                        return CapeResult.none();
                    }
                    LabyCosmeticsMod.LOGGER.warn(
                            "[LabyCosmetics] Unerwarteter Statuscode {} beim Laden des Capes fuer {}",
                            response.statusCode(), playerUuid);
                    return CapeResult.error();
                })
                .exceptionally(throwable -> {
                    LabyCosmeticsMod.LOGGER.warn(
                            "[LabyCosmetics] Cape-Abruf fuer {} fehlgeschlagen: {}",
                            playerUuid, throwable.getMessage());
                    return CapeResult.error();
                });
    }
}
