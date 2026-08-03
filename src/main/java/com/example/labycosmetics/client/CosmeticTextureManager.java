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
package com.example.labycosmetics.client;

import com.example.labycosmetics.LabyCosmeticsMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Laedt und cached Cosmetic-Texturen von LabyMod.
 * <p>
 * URL-Schema (per Netzwerk-Analyse ermittelt):
 *   https://dl.labymod.net/textures/{texture_directory}/{texture_uuid}
 * Die Datei wird ohne .png-Endung und als "octet-stream" ausgeliefert,
 * ist inhaltlich aber ein PNG - Java erkennt das am Dateiinhalt.
 *
 * <h3>Animierte Texturen (Sprite-Sheets)</h3>
 * Manche Cosmetics liefern die Textur als vertikal gestapeltes Sprite-Sheet
 * (viele quadratische Frames untereinander, z.B. 963 Cyber Wings: 128x5120 =
 * 40 Frames a 128x128). LabyMod spielt diese Frames ab; belegt am Gold von 963,
 * das in LabyMod pulsiert, weil nur die Gold-Region ueber die Frames variiert.
 * Der Katalog liefert KEINE Frame-Dauer (textureAnimationDelay ist leer) - die
 * Animation ist rein texturform-getrieben, LabyMod nutzt eine feste Default-
 * Kadenz. Siehe {@link #FRAME_DELAY_MS}.
 */
public final class CosmeticTextureManager {

    private static final String TEXTURE_URL = "https://dl.labymod.net/textures/%s/%s";

    /**
     * Millisekunden pro Frame bei animierten Texturen. LabyMod gibt im Katalog
     * keinen Wert her (textureAnimationDelay leer), nutzt also einen internen
     * Default. 100 ms/Frame passt zur Beobachtung an 963 (40 Frames -> 4 s pro
     * Durchlauf, "Pulsieren ueber Sekunden") und zum textureAnimationDelay-
     * Beispielwert der API-Doku. Bei sichtbarem Tempo-Unterschied gegen LabyMod:
     * Pulsperiode in LabyMod messen und durch die Frame-Anzahl teilen.
     */
    private static final long FRAME_DELAY_MS = 100L;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private enum State { LOADING, LOADED, NONE }

    /**
     * Cache-Eintrag. {@code animated} ist nur bei Sprite-Sheets gesetzt und
     * blaettert die {@code texture} pro Render weiter; sonst {@code null}.
     */
    private record Entry(State state, ResourceLocation texture, Animated animated) {
        static Entry loading() {
            return new Entry(State.LOADING, null, null);
        }

        static Entry none() {
            return new Entry(State.NONE, null, null);
        }

        static Entry loaded(ResourceLocation texture, Animated animated) {
            return new Entry(State.LOADED, texture, animated);
        }
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private CosmeticTextureManager() {
    }

    /**
     * Gibt die ResourceLocation der Textur zurueck, sofern schon geladen,
     * sonst {@code null} (und stoesst einmalig das Laden an).
     * <p>
     * Bei animierten Texturen wird hier der zur Zeit passende Frame in die
     * DynamicTexture geschrieben. Der Aufruf kommt aus WingRenderLayer.render,
     * also aus dem Render-Thread - nur dort ist {@code upload()} erlaubt.
     *
     * @param textureDirectory z.B. "1460"
     * @param textureUuid       z.B. "875b4b5a-818e-4a9e-833e-f8ddcf2e7398" (mit Bindestrichen)
     */
    public static ResourceLocation getTexture(String textureDirectory, String textureUuid,
                                              int frameAspectWidth, int frameAspectHeight) {
        String cacheKey = textureDirectory + "/" + textureUuid;
        Entry entry = CACHE.get(cacheKey);

        if (entry == null) {
            CACHE.put(cacheKey, Entry.loading());
            requestTexture(textureDirectory, textureUuid, cacheKey,
                    frameAspectWidth, frameAspectHeight);
            return null;
        }
        if (entry.state() != State.LOADED) {
            return null;
        }
        if (entry.animated() != null) {
            entry.animated().advance();
        }
        return entry.texture();
    }

    private static void requestTexture(String dir, String uuid, String cacheKey,
                                       int frameAspectWidth, int frameAspectHeight) {
        String url = TEXTURE_URL.formatted(dir, uuid);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();

        CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        LabyCosmeticsMod.LOGGER.warn(
                                "[LabyCosmetics] Textur {} lieferte Status {}", url, response.statusCode());
                        CACHE.put(cacheKey, Entry.none());
                        return;
                    }

                    NativeImage image;
                    try {
                        image = NativeImage.read(new ByteArrayInputStream(response.body()));
                    } catch (IOException e) {
                        LabyCosmeticsMod.LOGGER.warn(
                                "[LabyCosmetics] Textur {} konnte nicht dekodiert werden: {}",
                                url, e.getMessage());
                        CACHE.put(cacheKey, Entry.none());
                        return;
                    }

                    // Sprite-Sheet-Erkennung: Manche Cosmetics haben animierte
                    // Texturen - viele quadratische Frames untereinander gestapelt
                    // (z.B. 128x5120 = 40 Frames a 128x128). Ein normales Sheet ist
                    // etwa quadratisch. Ist die Hoehe deutlich groesser als die
                    // Breite (>= 2x), behandeln wir es als Sprite-Sheet und spielen
                    // die Frames ab (Frame 0 zuerst, dann per advance() weiter).
                    // Frame-GROESSE aus dem Katalog-Seitenverhaeltnis ableiten, NICHT
                    // quadratisch annehmen: Frame ist so breit wie das Sheet, die Hoehe
                    // ergibt sich aus frame_aspect_ratio. 963 -> 1:1 -> 128x128;
                    // 1784 -> 128:64 -> 128x64; 493 -> 35:27 -> 140x108. "Quadratisch"
                    // wuerde 493 und 1784 falsch zerschneiden.
                    final int frameWidth  = image.getWidth();
                    final int frameHeight = Math.max(1, frameWidth * frameAspectHeight / frameAspectWidth);
                    if (image.getHeight() >= frameHeight * 2) {
                        final NativeImage sheet = image;          // volle Textur behalten (NICHT schliessen)
                        final int frameCount = sheet.getHeight() / frameHeight;
                        final NativeImage frame0 = extractFrame(sheet, 0, frameWidth, frameHeight);
                        Minecraft.getInstance().execute(() -> {
                            DynamicTexture tex = new DynamicTexture(frame0);
                            ResourceLocation loc = registerTexture(dir, uuid, tex);
                            Animated anim = new Animated(sheet, tex, frameCount, frameWidth, frameHeight);
                            CACHE.put(cacheKey, Entry.loaded(loc, anim));
                        });
                        LabyCosmeticsMod.LOGGER.debug(
                                "[LabyCosmetics] Animierte Textur ({}): {} Frames a {}x{}px, Frame-Animation aktiv.",
                                url, frameCount, frameWidth, frameHeight);
                        return;
                    }

                    final NativeImage finalImage = image;
                    Minecraft.getInstance().execute(() -> {
                        DynamicTexture tex = new DynamicTexture(finalImage);
                        ResourceLocation loc = registerTexture(dir, uuid, tex);
                        CACHE.put(cacheKey, Entry.loaded(loc, null));
                    });
                })
                .exceptionally(t -> {
                    LabyCosmeticsMod.LOGGER.warn(
                            "[LabyCosmetics] Textur-Abruf {} fehlgeschlagen: {}", url, t.getMessage());
                    CACHE.put(cacheKey, Entry.none());
                    return null;
                });
    }

    /** Registriert eine DynamicTexture unter einer stabilen, eindeutigen ResourceLocation. */
    private static ResourceLocation registerTexture(String dir, String uuid, DynamicTexture tex) {
        ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                LabyCosmeticsMod.MOD_ID,
                "cosmetic/" + dir + "_" + uuid.replace("-", ""));
        Minecraft.getInstance().getTextureManager().register(loc, tex);
        return loc;
    }

    /**
     * Kopiert einen quadratischen Frame (Kantenlaenge {@code size}) aus einem
     * vertikalen Sprite-Sheet in ein NEUES NativeImage.
     */
    private static NativeImage extractFrame(NativeImage sheet, int frame, int w, int h) {
        NativeImage out = new NativeImage(w, h, false);
        blitFrame(sheet, frame, w, h, out);
        return out;
    }

    /** Kopiert Frame {@code frame} (Groesse {@code w}x{@code h}) aus {@code sheet} in {@code dst}. */
    private static void blitFrame(NativeImage sheet, int frame, int w, int h, NativeImage dst) {
        int yOffset = frame * h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst.setPixelRGBA(x, y, sheet.getPixelRGBA(x, yOffset + y));
            }
        }
    }

    /**
     * Haelt ein vertikales Sprite-Sheet und stellt die zugehoerige
     * DynamicTexture bei jedem Aufruf auf den zur Wall-Clock-Zeit passenden
     * Frame. Wall-Clock statt Game-Ticks: so laufen alle Betrachter synchron
     * und unabhaengig von der Tickrate.
     */
    private static final class Animated {
        private final NativeImage sheet;
        private final DynamicTexture texture;
        private final int frameCount;
        private final int frameWidth;
        private final int frameHeight;
        private int lastFrame = -1;

        Animated(NativeImage sheet, DynamicTexture texture,
                 int frameCount, int frameWidth, int frameHeight) {
            this.sheet = sheet;
            this.texture = texture;
            this.frameCount = frameCount;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
        }

        /**
         * Auf den aktuellen Frame stellen und nur bei Wechsel hochladen. Muss im
         * Render-Thread laufen (upload() spricht GL an) - Aufruf kommt aus
         * getTexture, also aus WingRenderLayer.render.
         */
        void advance() {
            int frame = (int) ((System.currentTimeMillis() / FRAME_DELAY_MS) % frameCount);
            if (frame == lastFrame) {
                return;
            }
            NativeImage target = texture.getPixels();
            if (target == null) {
                return;
            }
            lastFrame = frame;
            blitFrame(sheet, frame, frameWidth, frameHeight, target);
            texture.upload();
        }
    }
}