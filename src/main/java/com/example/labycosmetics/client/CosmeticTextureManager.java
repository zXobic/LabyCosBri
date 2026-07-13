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
 */
public final class CosmeticTextureManager {

    private static final String TEXTURE_URL = "https://dl.labymod.net/textures/%s/%s";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private enum State { LOADING, LOADED, NONE }

    private record Entry(State state, ResourceLocation texture) {
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private CosmeticTextureManager() {
    }

    /**
     * Gibt die ResourceLocation der Textur zurueck, sofern schon geladen,
     * sonst {@code null} (und stoesst einmalig das Laden an).
     *
     * @param textureDirectory z.B. "1460"
     * @param textureUuid       z.B. "875b4b5a-818e-4a9e-833e-f8ddcf2e7398" (mit Bindestrichen)
     */
    public static ResourceLocation getTexture(String textureDirectory, String textureUuid) {
        String cacheKey = textureDirectory + "/" + textureUuid;
        Entry entry = CACHE.get(cacheKey);

        if (entry == null) {
            CACHE.put(cacheKey, new Entry(State.LOADING, null));
            requestTexture(textureDirectory, textureUuid, cacheKey);
            return null;
        }
        return entry.state() == State.LOADED ? entry.texture() : null;
    }

    private static void requestTexture(String dir, String uuid, String cacheKey) {
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
                        CACHE.put(cacheKey, new Entry(State.NONE, null));
                        return;
                    }

                    NativeImage image;
                    try {
                        image = NativeImage.read(new ByteArrayInputStream(response.body()));
                    } catch (IOException e) {
                        LabyCosmeticsMod.LOGGER.warn(
                                "[LabyCosmetics] Textur {} konnte nicht dekodiert werden: {}",
                                url, e.getMessage());
                        CACHE.put(cacheKey, new Entry(State.NONE, null));
                        return;
                    }

                    // Sprite-Sheet-Erkennung: Manche Cosmetics haben animierte
                    // Texturen - viele quadratische Frames untereinander gestapelt
                    // (z.B. 128x5120 = 40 Frames a 128x128). Ein normales Sheet ist
                    // etwa quadratisch. Ist die Hoehe deutlich groesser als die
                    // Breite (>= 2x), behandeln wir es als Sprite-Sheet und nehmen
                    // vorerst nur den OBERSTEN Frame (quadratisch, Kantenlaenge =
                    // Breite). Die volle Frame-Animation kann spaeter folgen.
                    if (image.getHeight() >= image.getWidth() * 2) {
                        NativeImage firstFrame = extractFirstFrame(image);
                        image.close();
                        image = firstFrame;
                        LabyCosmeticsMod.LOGGER.info(
                                "[LabyCosmetics] Animierte Textur erkannt ({}): zeige ersten Frame.", url);
                    }

                    final NativeImage finalImage = image;
                    Minecraft.getInstance().execute(() -> {
                        DynamicTexture tex = new DynamicTexture(finalImage);
                        ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                                LabyCosmeticsMod.MOD_ID,
                                "cosmetic/" + dir + "_" + uuid.replace("-", ""));
                        Minecraft.getInstance().getTextureManager().register(loc, tex);
                        CACHE.put(cacheKey, new Entry(State.LOADED, loc));
                    });
                })
                .exceptionally(t -> {
                    LabyCosmeticsMod.LOGGER.warn(
                            "[LabyCosmetics] Textur-Abruf {} fehlgeschlagen: {}", url, t.getMessage());
                    CACHE.put(cacheKey, new Entry(State.NONE, null));
                    return null;
                });
    }

    /**
     * Schneidet aus einem Sprite-Sheet (viele quadratische Frames
     * untereinander) den obersten Frame aus. Die Frame-Kantenlaenge
     * entspricht der Breite der Textur.
     */
    private static NativeImage extractFirstFrame(NativeImage sheet) {
        int size = sheet.getWidth();
        NativeImage frame = new NativeImage(size, size, false);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                frame.setPixelRGBA(x, y, sheet.getPixelRGBA(x, y));
            }
        }
        return frame;
    }
}
