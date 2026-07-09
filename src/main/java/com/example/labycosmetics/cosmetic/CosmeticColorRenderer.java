package com.example.labycosmetics.cosmetic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.util.Map;

/**
 * Hilfsfunktionen fuer die farbige Darstellung von Cosmetic-Teilen.
 * <p>
 * LabyMod-Cosmetics koennen pro "color_X"-Bone-Gruppe eine eigene Farbe
 * haben (die X-te Farbe aus den userdata). Diese Klasse rendert gezielt
 * einzelne Bones (und ihre Kinder) mit einer bestimmten Farbe und wandelt
 * Hex-Strings in RGB um.
 */
public final class CosmeticColorRenderer {

    private CosmeticColorRenderer() {
    }

    /** Wandelt einen Hex-String wie "8c8989" in ein float[3] mit r,g,b in 0..1. */
    public static float[] hexToRgb(String hex) {
        if (hex == null) {
            return new float[]{1f, 1f, 1f};
        }
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() < 6) {
            return new float[]{1f, 1f, 1f};
        }
        try {
            int r = Integer.parseInt(h.substring(0, 2), 16);
            int g = Integer.parseInt(h.substring(2, 4), 16);
            int b = Integer.parseInt(h.substring(4, 6), 16);
            return new float[]{r / 255f, g / 255f, b / 255f};
        } catch (NumberFormatException e) {
            return new float[]{1f, 1f, 1f};
        }
    }

    /**
     * Rendert das GESAMTE Modell von der Wurzel aus (damit alle Pivot-
     * Versaetze der Eltern-Bones stimmen), aber nur die Bones einer
     * Farbgruppe sind sichtbar - der Rest wird kurzzeitig ausgeblendet.
     * So bekommt jede Federreihe ihre Farbe an der richtigen Position.
     *
     * @param root         Wurzel des Modells (wird gerendert)
     * @param bones        alle Bones nach Name
     * @param colorPrefix  z.B. "color_0" - nur diese Gruppe wird sichtbar
     * @param rgb          Farbe fuer diese Gruppe
     */
    public static void renderColorGroup(PoseStack poseStack, VertexConsumer consumer,
                                        int packedLight, ModelPart root,
                                        Map<String, ModelPart> bones,
                                        String colorPrefix, float[] rgb) {
        // 1. Alle color-Bones ausblenden.
        for (Map.Entry<String, ModelPart> entry : bones.entrySet()) {
            if (entry.getKey().startsWith("color_")) {
                entry.getValue().visible = false;
            }
        }
        // 2. Nur die gewuenschte Gruppe wieder einblenden.
        for (Map.Entry<String, ModelPart> entry : bones.entrySet()) {
            if (entry.getKey().startsWith(colorPrefix)) {
                entry.getValue().visible = true;
            }
        }
        // 3. Von der Wurzel rendern (nur die sichtbare Gruppe erscheint).
        int packedColor = packColor(rgb);
        root.render(poseStack, consumer, packedLight,
                OverlayTexture.NO_OVERLAY, packedColor);
    }
    /**
     * Packt r,g,b (0..1) plus volle Deckkraft in einen ARGB-Integer,
     * wie ModelPart#render ihn in dieser Version erwartet.
     */
    private static int packColor(float[] rgb) {
        int a = 255;
        int r = Math.round(rgb[0] * 255f);
        int g = Math.round(rgb[1] * 255f);
        int b = Math.round(rgb[2] * 255f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
