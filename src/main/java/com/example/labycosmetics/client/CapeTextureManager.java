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
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Haelt pro Spieler-UUID den Ladezustand und die daraus registrierte
 * Textur fuer das LabyMod-Cape vor, damit wir nicht bei jedem Frame
 * erneut einen Netzwerk-Request feuern.
 */
public final class CapeTextureManager {

    /** Ladezustand pro Spieler, mit Neuversuch nach Fehlern (siehe LoadTracker). */
    private static final LoadTracker<UUID> STATE = new LoadTracker<>();
    private static final Map<UUID, ResourceLocation> TEXTURES = new ConcurrentHashMap<>();

    private CapeTextureManager() {
    }

    /**
     * Verwirft den Ladezustand aller Capes. Der naechste getCapeTexture-
     * Aufruf laedt dann pro Spieler frisch. Wird beim Server-Join gerufen,
     * damit nach einem (Re)Connect nicht die Cape-Textur vom vorigen Server
     * haengenbleibt.
     *
     * Die bereits beim Minecraft-TextureManager registrierten DynamicTextures
     * werden bewusst NICHT einzeln freigegeben: das duerfte nur auf dem
     * Render-Thread passieren, hier laufen wir aber im Join-Event. Ein neues
     * Cape registriert sich unter derselben ResourceLocation (capes/{uuid})
     * und ueberschreibt die alte Registrierung - kein wachsendes Leck.
     */
    public static void invalidateAll() {
        STATE.clear();
        TEXTURES.clear();
        LabyCosmeticsMod.LOGGER.info("[LabyCosmetics] Cape-Cache geleert (Server-Join).");
    }

    /**
     * Gibt die ResourceLocation der Cape-Textur zurueck, falls bereits geladen.
     * Stoesst andernfalls das Herunterladen an (bzw. einen Neuversuch, wenn der
     * letzte fehlgeschlagen ist) und gibt {@code null} zurueck.
     */
    public static ResourceLocation getCapeTexture(UUID playerUuid) {
        if (STATE.tryStart(playerUuid)) {
            requestCape(playerUuid);
            return null;
        }
        return STATE.isLoaded(playerUuid) ? TEXTURES.get(playerUuid) : null;
    }

    private static void requestCape(UUID playerUuid) {
        LabyModApiClient.fetchCape(playerUuid).thenAccept(result -> {
            if (result.bytes() == null) {
                // 404 = kein Cape -> nicht neu fragen; sonst spaeter erneut versuchen
                if (result.absent()) {
                    STATE.absent(playerUuid);
                } else {
                    STATE.failed(playerUuid);
                }
                return;
            }

            NativeImage image;
            try {
                image = NativeImage.read(new ByteArrayInputStream(result.bytes()));
            } catch (IOException e) {
                LabyCosmeticsMod.LOGGER.warn(
                        "[LabyCosmetics] Konnte Cape-Bild fuer {} nicht dekodieren: {}",
                        playerUuid, e.getMessage());
                STATE.absent(playerUuid);
                return;
            }

            Minecraft.getInstance().execute(() -> registerTexture(playerUuid, image));
        });
    }

    private static void registerTexture(UUID playerUuid, NativeImage image) {
        DynamicTexture texture = new DynamicTexture(image);
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                LabyCosmeticsMod.MOD_ID, "capes/" + playerUuid);

        Minecraft.getInstance().getTextureManager().register(location, texture);
        TEXTURES.put(playerUuid, location);
        STATE.loaded(playerUuid);
    }
}
