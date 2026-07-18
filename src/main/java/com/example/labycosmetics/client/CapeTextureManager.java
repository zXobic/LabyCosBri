package com.example.labycosmetics.client;

import com.example.labycosmetics.LabyCosmeticsMod;
import com.example.labycosmetics.api.LabyModApiClient;
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

    private enum State { NOT_REQUESTED, LOADING, LOADED, NONE, ERROR }

    private record Entry(State state, ResourceLocation texture) {
    }

    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();

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
        CACHE.clear();
        LabyCosmeticsMod.LOGGER.info("[LabyCosmetics] Cape-Cache geleert (Server-Join).");
    }

    /**
     * Gibt die ResourceLocation der Cape-Textur zurueck, falls bereits geladen.
     * Stoesst andernfalls (einmalig) das Herunterladen an und gibt currently
     * {@code null} zurueck, bis der Download abgeschlossen ist.
     */
    public static ResourceLocation getCapeTexture(UUID playerUuid) {
        Entry entry = CACHE.get(playerUuid);

        if (entry == null) {
            CACHE.put(playerUuid, new Entry(State.LOADING, null));
            requestCape(playerUuid);
            return null;
        }

        return entry.state() == State.LOADED ? entry.texture() : null;
    }

    private static void requestCape(UUID playerUuid) {
        LabyModApiClient.fetchCapeBytes(playerUuid).thenAccept(bytes -> {
            if (bytes == null) {
                CACHE.put(playerUuid, new Entry(State.NONE, null));
                return;
            }

            NativeImage image;
            try {
                image = NativeImage.read(new ByteArrayInputStream(bytes));
            } catch (IOException e) {
                LabyCosmeticsMod.LOGGER.warn(
                        "[LabyCosmetics] Konnte Cape-Bild fuer {} nicht dekodieren: {}",
                        playerUuid, e.getMessage());
                CACHE.put(playerUuid, new Entry(State.ERROR, null));
                return;
            }

            // Muss auf dem Render-Thread passieren, da OpenGL-Texturen
            // nicht thread-sicher erzeugt werden koennen. DynamicTexture
            // uebernimmt danach den Besitz von "image" und schliesst es
            // spaeter selbst (z.B. beim Neuladen von Ressourcen).
            Minecraft.getInstance().execute(() -> registerTexture(playerUuid, image));
        });
    }

    private static void registerTexture(UUID playerUuid, NativeImage image) {
        DynamicTexture texture = new DynamicTexture(image);
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                LabyCosmeticsMod.MOD_ID, "capes/" + playerUuid);

        Minecraft.getInstance().getTextureManager().register(location, texture);
        CACHE.put(playerUuid, new Entry(State.LOADED, location));
    }
}
