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

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.util.HashMap;
import java.util.Map;

/**
 * Rendert Cosmetic-Teile mit den passenden Farben.
 * <p>
 * LabyMod-Cosmetics ordnen Farben ueber Bone-Namens-Praefixe zu:
 * {@code color_0_*} -> Farbe 1, {@code color_1_*} -> Farbe 2,
 * {@code color_2_*} -> Farbe 3, alles andere -> Rest-Farbe (z.B. glow).
 *
 * <h3>Farb-VERERBUNG ueber die Hierarchie</h3>
 * Ein color-Bone hat oft keine eigenen Cubes, sondern seine KINDER tragen
 * die Flaechen. Deshalb bestimmt sich die Farbe eines Bones aus dem naechsten
 * color-VORFAHREN in der Bone-Kette (inkl. der _rc-Zwischenbones fuer
 * rotierte Cubes).
 *
 * <h3>Technik: skipDraw</h3>
 * Pro Farbe rendern wir das ganze Modell einmal und ueberspringen per
 * {@link ModelPart#skipDraw} alle Bones, die nicht zu dieser Farbe gehoeren.
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

    private enum ColorClass { C0, C1, C2, REST }

    private static ColorClass classifyWithInheritance(String boneName, BedrockGeometry geo) {
        String current = boneName;
        int guard = 0;
        while (current != null && guard++ < 100) {
            if (current.startsWith("color_0")) return ColorClass.C0;
            if (current.startsWith("color_1")) return ColorClass.C1;
            if (current.startsWith("color_2")) return ColorClass.C2;
            current = parentOf(current, geo);
        }
        return ColorClass.REST;
    }

    private static String parentOf(String boneName, BedrockGeometry geo) {
        for (BedrockGeometry.Bone b : geo.bones) {
            if (b.name.equals(boneName)) {
                return b.parent;
            }
        }
        int idx = boneName.lastIndexOf("_rc");
        if (idx > 0) {
            return boneName.substring(0, idx);
        }
        return null;
    }

    public static void renderColored(PoseStack poseStack, VertexConsumer consumer,
                                     int packedLight, ModelPart root,
                                     Map<String, ModelPart> bones, BedrockGeometry geo,
                                     float[] color0, float[] color1,
                                     float[] color2, float[] restColor) {

        Map<String, ColorClass> classOf = new HashMap<>();
        for (String name : bones.keySet()) {
            classOf.put(name, classifyWithInheritance(name, geo));
        }

        renderPass(poseStack, consumer, packedLight, root, bones, classOf, ColorClass.C0, color0);
        renderPass(poseStack, consumer, packedLight, root, bones, classOf, ColorClass.C1, color1);
        if (color2 != null) {
            renderPass(poseStack, consumer, packedLight, root, bones, classOf, ColorClass.C2, color2);
        }
        renderPass(poseStack, consumer, packedLight, root, bones, classOf, ColorClass.REST, restColor);

        for (ModelPart part : bones.values()) {
            part.skipDraw = false;
        }
    }

    private static void renderPass(PoseStack poseStack, VertexConsumer consumer,
                                   int packedLight, ModelPart root,
                                   Map<String, ModelPart> bones,
                                   Map<String, ColorClass> classOf,
                                   ColorClass wanted, float[] rgb) {
        if (rgb == null) {
            return;
        }
        for (Map.Entry<String, ModelPart> entry : bones.entrySet()) {
            boolean draw = classOf.get(entry.getKey()) == wanted;
            entry.getValue().skipDraw = !draw;
        }
        int packedColor = packColor(rgb);
        root.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY, packedColor);
    }

    private static int packColor(float[] rgb) {
        int a = 255;
        int r = Math.round(rgb[0] * 255f);
        int g = Math.round(rgb[1] * 255f);
        int b = Math.round(rgb[2] * 255f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
