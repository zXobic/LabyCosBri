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
import com.example.labycosmetics.api.Http;
import com.example.labycosmetics.api.LoadTracker;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
 * (viele Frames untereinander, z.B. 963 Cyber Wings: 128x5120 = 40 Frames
 * a 128x128). Der Katalog liefert KEINE Frame-Dauer - siehe {@link #FRAME_DELAY_MS}.
 *
 * <h3>Aenderungen fuer 26.1</h3>
 * {@code ResourceLocation} heisst jetzt {@code Identifier}, {@code DynamicTexture}
 * braucht ein Label, und {@code NativeImage#getPixelRGBA/setPixelRGBA} sind
 * privat - ersetzt durch {@code getPixel/setPixel} (ARGB). Fuers reine
 * Umkopieren von Frames ist das Format egal.
 */
public final class CosmeticTextureManager {

    private static final String TEXTURE_URL = "https://dl.labymod.net/textures/%s/%s";

    /**
     * Millisekunden pro Frame bei animierten Texturen. LabyMod gibt im Katalog
     * keinen Wert her (textureAnimationDelay leer), nutzt also einen internen
     * Default. 100 ms/Frame passt zur Beobachtung an 963 (40 Frames -> 4 s pro
     * Durchlauf). Bei sichtbarem Tempo-Unterschied gegen LabyMod:
     * Pulsperiode in LabyMod messen und durch die Frame-Anzahl teilen.
     */
    private static final long FRAME_DELAY_MS = 100L;

    private static final LoadTracker<String> STATE = new LoadTracker<>();

    /**
     * Cache-Eintrag. {@code animated} ist nur bei Sprite-Sheets gesetzt und
     * blaettert die {@code texture} pro Render weiter; sonst {@code null}.
     */
    private record Entry(Identifier texture, Animated animated) {
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private CosmeticTextureManager() {
    }

    /**
     * Gibt den Identifier der Textur zurueck, sofern schon geladen,
     * sonst {@code null} (und stoesst einmalig das Laden an).
     * <p>
     * Bei animierten Texturen wird hier der zur Zeit passende Frame in die
     * DynamicTexture geschrieben. Der Aufruf kommt aus CosmeticRenderLayer.submit,
     * also vom Render-Thread - nur dort ist {@code upload()} erlaubt.
     *
     * @param textureDirectory z.B. "1460"
     * @param textureUuid       z.B. "875b4b5a-818e-4a9e-833e-f8ddcf2e7398" (mit Bindestrichen)
     */
    public static Identifier getTexture(String textureDirectory, String textureUuid,
                                        int frameAspectWidth, int frameAspectHeight) {
        String cacheKey = textureDirectory + "/" + textureUuid;
        if (STATE.tryStart(cacheKey)) {
            requestTexture(textureDirectory, textureUuid, cacheKey,
                    frameAspectWidth, frameAspectHeight);
            return null;
        }
        Entry entry = STATE.isLoaded(cacheKey) ? CACHE.get(cacheKey) : null;
        if (entry == null) {
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

        Http.getBytes(url, Duration.ofSeconds(8))
                .thenAccept(response -> {
                    if (response.statusCode() == 404) {
                        STATE.absent(cacheKey);
                        return;
                    }
                    if (response.statusCode() != 200) {
                        LabyCosmeticsMod.LOGGER.warn(
                                "[LabyCosmetics] Textur {} lieferte Status {}", url, response.statusCode());
                        STATE.failed(cacheKey);
                        return;
                    }

                    NativeImage image;
                    try {
                        image = NativeImage.read(new ByteArrayInputStream(response.body()));
                    } catch (IOException e) {
                        LabyCosmeticsMod.LOGGER.warn(
                                "[LabyCosmetics] Textur {} konnte nicht dekodiert werden: {}",
                                url, e.getMessage());
                        STATE.absent(cacheKey);
                        return;
                    }

                    // Sprite-Sheet-Erkennung: Frame ist so breit wie das Sheet, die
                    // Hoehe ergibt sich aus frame_aspect_ratio. 963 -> 1:1 -> 128x128;
                    // 1784 -> 128:64 -> 128x64; 493 -> 35:27 -> 140x108.
                    // Ist das Sheet mindestens 2 Frames hoch, spielen wir es ab.
                    final int frameWidth  = image.getWidth();
                    final int frameHeight = Math.max(1, frameWidth * frameAspectHeight / frameAspectWidth);
                    if (image.getHeight() >= frameHeight * 2) {
                        final NativeImage sheet = image;          // volle Textur behalten (NICHT schliessen)
                        final int frameCount = sheet.getHeight() / frameHeight;
                        final NativeImage frame0 = extractFrame(sheet, 0, frameWidth, frameHeight);
                        Minecraft.getInstance().execute(() -> {
                            DynamicTexture tex = new DynamicTexture(() -> "labycosbri " + cacheKey, frame0);
                            Identifier loc = registerTexture(dir, uuid, tex);
                            Animated anim = new Animated(sheet, tex, frameCount, frameWidth, frameHeight);
                            CACHE.put(cacheKey, new Entry(loc, anim));
                            STATE.loaded(cacheKey);
                        });
                        LabyCosmeticsMod.LOGGER.debug(
                                "[LabyCosmetics] Animierte Textur ({}): {} Frames a {}x{}px.",
                                url, frameCount, frameWidth, frameHeight);
                        return;
                    }

                    final NativeImage finalImage = image;
                    Minecraft.getInstance().execute(() -> {
                        DynamicTexture tex = new DynamicTexture(() -> "labycosbri " + cacheKey, finalImage);
                        Identifier loc = registerTexture(dir, uuid, tex);
                        CACHE.put(cacheKey, new Entry(loc, null));
                        STATE.loaded(cacheKey);
                    });
                })
                .exceptionally(t -> {
                    LabyCosmeticsMod.LOGGER.warn(
                            "[LabyCosmetics] Textur-Abruf {} fehlgeschlagen: {}", url, t.getMessage());
                    STATE.failed(cacheKey);
                    return null;
                });
    }

    /** Registriert eine DynamicTexture unter einem stabilen, eindeutigen Identifier. */
    private static Identifier registerTexture(String dir, String uuid, DynamicTexture tex) {
        Identifier loc = Identifier.fromNamespaceAndPath(
                LabyCosmeticsMod.MOD_ID,
                "cosmetic/" + dir + "_" + uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT));
        Minecraft.getInstance().getTextureManager().register(loc, tex);
        return loc;
    }

    /** Kopiert Frame {@code frame} aus einem vertikalen Sprite-Sheet in ein NEUES NativeImage. */
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
                dst.setPixel(x, y, sheet.getPixel(x, yOffset + y));
            }
        }
    }

    /**
     * Haelt ein vertikales Sprite-Sheet und stellt die zugehoerige
     * DynamicTexture bei jedem Aufruf auf den zur Wall-Clock-Zeit passenden
     * Frame. Wall-Clock statt Game-Ticks: so laufen alle Betrachter synchron.
     * <p>
     * Hinweis: {@code sheet} wird nie geschlossen (nativer Speicher). Okay,
     * solange der Cache nie geleert wird - bei einem spaeteren Cache-Reset
     * hier {@code sheet.close()} nachziehen.
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

        /** Auf den aktuellen Frame stellen und nur bei Wechsel hochladen (Render-Thread). */
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
