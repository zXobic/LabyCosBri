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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Gemeinsamer HTTP-Client fuer alle Abrufe. Frueher hatte jeder Manager
 * seinen eigenen HttpClient (5 Stueck) - einer reicht und teilt sich die
 * Verbindungen.
 * <p>
 * Setzt einen User-Agent, damit LabyMod die Anfragen dieser Mod zuordnen
 * kann, statt sie als anonymen Traffic pauschal zu blocken.
 */
public final class Http {

    // Bei Versionswechsel mitziehen (oder aus FabricLoader lesen).
    private static final String USER_AGENT = "labycosbri/0.3.0 (Fabric 26.3; +github.com/zXobic/LabyCosBri)";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Http() {
    }

    public static CompletableFuture<HttpResponse<String>> getString(String url, Duration timeout) {
        return CLIENT.sendAsync(request(url, timeout), HttpResponse.BodyHandlers.ofString());
    }

    public static CompletableFuture<HttpResponse<byte[]>> getBytes(String url, Duration timeout) {
        return CLIENT.sendAsync(request(url, timeout), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static HttpRequest request(String url, Duration timeout) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
    }
}
