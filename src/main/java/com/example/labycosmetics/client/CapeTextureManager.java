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
import com.example.labycosmetics.api.LabyModApiClient;
import com.example.labycosmetics.api.LoadTracker;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Laedt pro Spieler das LabyMod-Cape und stellt es als {@link ClientAsset.Texture}
 * bereit.
 *
 * <h3>Neu in der Fabric-26.1-Version</h3>
 * Es gibt keinen eigenen Cape-Layer mehr. Stattdessen tauscht
 * {@code AbstractClientPlayerMixin} das Cape im {@code PlayerSkin} des Spielers
 * aus - der Vanilla-{@code CapeLayer} zeichnet es dann mit seiner eigenen
 * Physik (Wehen, Sneaken, Elytra-Ausblendung, "Umhang anzeigen"-Option).
 * Das spart die nachgebaute Weh-Animation und passt automatisch zu jeder
 * Vanilla-Aenderung.
 * <p>
 * Dafuer muss das Bild das Vanilla-Layout haben: der Cape-Quader liegt bei
 * (0,0) in einer 64x32-Textur. LabyMod liefert dagegen nur den Quader selbst
 * (Seitenverhaeltnis 22:17, so wie der alte LabyCapeLayer ihn mit
 * {@code LayerDefinition.create(mesh, 22, 17)} gemappt hat). Wir kopieren das
 * Bild deshalb links oben in eine passend skalierte 64:32-Leinwand.
 */
public final class CapeTextureManager {

    private static final LoadTracker<UUID> STATE = new LoadTracker<>();
    private static final Map<UUID, ClientAsset.Texture> TEXTURES = new ConcurrentHashMap<>();

    private CapeTextureManager() {
    }

    /**
     * Verwirft den Ladezustand aller Capes (beim Server-Join). Bereits
     * registrierte Texturen werden beim Neuladen unter demselben Identifier
     * ueberschrieben - der TextureManager schliesst die alte dabei selbst.
     */
    public static void invalidateAll() {
        STATE.clear();
        TEXTURES.clear();
    }

    /**
     * Das Cape als ClientAsset, falls bereits geladen. Stoesst andernfalls
     * (einmalig) das Herunterladen an und gibt {@code null} zurueck.
     */
    public static ClientAsset.Texture getCape(UUID playerUuid) {
        if (STATE.tryStart(playerUuid)) {
            requestCape(playerUuid);
            return null;
        }
        return STATE.isLoaded(playerUuid) ? TEXTURES.get(playerUuid) : null;
    }

    private static void requestCape(UUID playerUuid) {
        LabyModApiClient.fetchCape(playerUuid).thenAccept(result -> {
            if (result.bytes() == null) {
                if (result.absent()) {
                    STATE.absent(playerUuid);
                } else {
                    STATE.failed(playerUuid);
                }
                return;
            }

            NativeImage image;
            try {
                image = toVanillaLayout(NativeImage.read(new ByteArrayInputStream(result.bytes())));
            } catch (IOException e) {
                LabyCosmeticsMod.LOGGER.warn(
                        "[LabyCosmetics] Konnte Cape-Bild fuer {} nicht dekodieren: {}",
                        playerUuid, e.getMessage());
                STATE.absent(playerUuid);
                return;
            }

            // GPU-Texturen nur auf dem Render-Thread anlegen. DynamicTexture
            // uebernimmt den Besitz von "image" und schliesst es selbst.
            Minecraft.getInstance().execute(() -> registerTexture(playerUuid, image));
        });
    }

    private static void registerTexture(UUID playerUuid, NativeImage image) {
        Identifier id = Identifier.fromNamespaceAndPath(LabyCosmeticsMod.MOD_ID, "capes/" + playerUuid);
        Minecraft.getInstance().getTextureManager()
                .register(id, new DynamicTexture(() -> "labycosbri cape " + playerUuid, image));
        TEXTURES.put(playerUuid, new ClientAsset.ResourceTexture(id, id));
        STATE.loaded(playerUuid);
    }

    /**
     * Bringt ein LabyMod-Cape ins Vanilla-Layout (64:32, Quader links oben).
     * Bilder, die schon 2:1 sind, gelten als Vanilla-Layout und bleiben so.
     * Schliesst {@code src}, falls ein neues Bild entsteht.
     */
    private static NativeImage toVanillaLayout(NativeImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w == h * 2) {
            return src;
        }
        // Breite so waehlen, dass die 22 Quader-Pixel genau die Quellbreite sind,
        // und die Leinwand exakt 2:1 halten (Vanilla rechnet UVs auf 64x32).
        int outW = (int) Math.round(64.0 * w / 22.0);
        if ((outW & 1) == 1) {
            outW++;
        }
        int outH = Math.max(h, outW / 2);
        outW = outH * 2;
        NativeImage out = new NativeImage(outW, outH, true);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setPixel(x, y, src.getPixel(x, y));
            }
        }
        src.close();
        return out;
    }
}
