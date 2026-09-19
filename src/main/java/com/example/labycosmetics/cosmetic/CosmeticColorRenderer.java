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
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.util.HashMap;
import java.util.Map;

/**
 * Rendert Cosmetic-Teile mit den passenden Farben.
 * <p>
 * LabyMod-Cosmetics ordnen Farben ueber Bone-Namens-Praefixe zu:
 * {@code color_0_*} -> Farbe 1, {@code color_1_*} -> Farbe 2,
 * {@code color_2_*} -> Farbe 3, {@code color_3_*} -> Farbe 4, alles andere ->
 * Rest-Farbe (z.B. glow). Vier Farben sind belegt: 1304 Reef, 1410 Train und
 * 1893 Zodiac haben "options": texture,rgb,rgb,rgb,rgb (also 4 RGB-Werte).
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

    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    /** TEST: Glow-Pass aus -> alles normal beleuchtet. Fuer den A/B-Vergleich
     *  am selben Wing (1460 oder 855) im Dunkeln. */
    private static final boolean TEST_NO_GLOW = false;

    /** TEMP-DIAGNOSE: merkt sich, fuer welche Texturen schon geloggt wurde,
     *  damit die Layers-Zeile nicht pro Frame feuert. Wieder entfernen. */
    private static final java.util.Set<String> DIAG_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Glueht dieser Bone? Wahr, wenn er selbst oder ein VORFAHRE mit "glow"
     * beginnt - dieselbe Ketten-Vererbung wie bei der Farbe, weil der Effekt
     * laut Doku "on its children" wirkt (855: glow_plane7 -> color_0_a, der
     * FLAECHEN-tragende color-Bone haengt UNTER dem glow-Bone).
     * <p>
     * WICHTIG - Doku vs. Material: Die offizielle Glow-Doku beschreibt einen
     * Vierteiler glow_STAERKE_FARBE_NAME. Ein Katalog-Scan ueber alle 203
     * glow-Bones zeigt AUSNAHMSLOS den Zweiteiler glow_NAME - Staerke und Farbe
     * sind im Material NICHT kodiert. Deshalb emissiv ohne Parameter. Glow ist
     * orthogonal zur Farbe: der Bone behaelt seine Farbklasse, nur das Licht
     * geht auf Maximum.
     */
    private static boolean glowsWithInheritance(String boneName, BedrockGeometry geo) {
        String current = boneName;
        int guard = 0;
        while (current != null && guard++ < 100) {
            if (current.startsWith("glow") && !isRootBone(current, geo)) {
                return true;
            }
            current = parentOf(current, geo);
        }
        return false;
    }

    private CosmeticColorRenderer() {
    }

    /**
     * Ein glow-Bone zaehlt NUR als Effekt, wenn er nicht die Wurzel ist.
     * <p>
     * Beleg (geo.json von zwei Wings): 855 hat die glow-Bones MITTEN in der
     * Kette (bone7 -> glow_plane7 -> color_0_a), dort ist die Vererbung auf die
     * Kinder gewollt. 1460 dagegen heisst der WURZEL-Bone "glow_main" - das ist
     * ein Struktur-Name, kein Effekt; wuerde er zaehlen, gluehte das ganze
     * Modell (Log zeigte "33 von 33"). Regel: Wurzel-glow ignorieren.
     * <p>
     * UNGEMESSEN/BEWUSST: nur an 855 + 1460 geo-belegt. Ein Wing mit ECHTEM
     * Glow direkt am Wurzel-Bone bliebe dunkel - kein bekannter Fall. Der
     * verworfene Wurzel-glow wird in renderColored geloggt, damit ein solcher
     * Fall im Log auffaellt statt still falsch zu sein.
     */
    private static boolean isRootBone(String boneName, BedrockGeometry geo) {
        for (BedrockGeometry.Bone b : geo.bones) {
            if (b.name.equals(boneName)) {
                return b.parent == null;
            }
        }
        return false;
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

    private enum ColorClass { C0, C1, C2, C3, REST }

    private static ColorClass classifyWithInheritance(String boneName, BedrockGeometry geo) {
        String current = boneName;
        int guard = 0;
        while (current != null && guard++ < 100) {
            if (current.startsWith("color_0")) return ColorClass.C0;
            if (current.startsWith("color_1")) return ColorClass.C1;
            if (current.startsWith("color_2")) return ColorClass.C2;
            if (current.startsWith("color_3")) return ColorClass.C3;
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
                                     float[] color2, float[] color3, float[] restColor,
                                     net.minecraft.client.renderer.MultiBufferSource buffer,
                                     net.minecraft.resources.ResourceLocation texture,
                                     String textureUuid) {

        // Layers-Effekt: Nur filtern, wenn es tatsaechlich MEHRERE Textur-Varianten
        // gibt. Bei genau einem layer_-Ast (963, 855) gibt es nichts zu waehlen -
        // der muss immer sichtbar bleiben, auch wenn die getragene UUID abweicht
        // (im Dev-Client kommt sie aus defaultData und passt nie).
        java.util.Set<String> variants = new java.util.HashSet<>();
        for (BedrockGeometry.Bone b : geo.bones) {
            String u = layerUuidOf(b.name);
            if (u != null) {
                variants.add(u);
            }
        }
        boolean filterLayers = variants.size() > 1;

        Map<String, ColorClass> classOf = new HashMap<>();
        Map<String, Boolean> glowOf = new HashMap<>();
        int glowCount = 0;
        int hidden = 0;
        for (String name : bones.keySet()) {
            if (filterLayers && isHiddenLayer(name, geo, textureUuid)) {
                classOf.put(name, null);
                glowOf.put(name, false);
                hidden++;
                continue;
            }
            classOf.put(name, classifyWithInheritance(name, geo));
            boolean g = !TEST_NO_GLOW && glowsWithInheritance(name, geo);
            glowOf.put(name, g);
            if (g) {
                glowCount++;
            }
        }
        if (hidden > 0 && DIAG_LOGGED.add(textureUuid)) {
            LOGGER.info("[LabyCos] Layers: {} von {} Bones ausgeblendet ({} Varianten), getragen = {}",
                    hidden, bones.size(), variants.size(), textureUuid);
        }
        // Pruefstein: >0 fuer Glow-Wings (855, 1460), 0 fuer die Gegenprobe
        // (24, 404). "kein Glow" und "Glow-Erkennung kaputt" saehen sonst
        // gleich aus. Einmal pro render() - okay fuers Debuggen, spaeter still.
        // Wurzel-glow-Bones getrennt zaehlen: die werden bewusst NICHT als
        // Effekt gewertet (Struktur-Name wie 1460 "glow_main"). Taucht hier
        // eine Zahl >0 auf und der Wing bleibt trotzdem komplett dunkel, ist
        // das der Hinweis auf einen echten Wurzel-glow, den die Regel verwirft.
        int rootGlow = 0;
        for (String name : bones.keySet()) {
            if (name.startsWith("glow") && isRootBone(name, geo)) {
                rootGlow++;
            }
        }
        LOGGER.debug("[LabyCos] Glow: {} von {} Bones gluehen, {} Wurzel-glow verworfen",
                glowCount, bones.size(), rootGlow);

        // Durchgang A - normale Farbe, ganzes Modell (glow-Teile inklusive, damit
        // sie ihre Grundfarbe/Form behalten).
        // consumer in einen FlatNormalConsumer wickeln: der zwingt jede Vertex-Normale
        // auf einen festen Wert, damit die Vanilla-Richtungsschattierung KONSTANT ist
        // und nicht mehr auf die Sneak-Neigung reagiert (Flaechen wurden sonst dunkler).
        // Lichtkarte (packedLight) bleibt unberuehrt -> Hoehle/Nacht dunkeln weiterhin.
        VertexConsumer flat = new FlatNormalConsumer(consumer);
        renderPass(poseStack, flat, packedLight, root, bones, classOf, ColorClass.C0, color0);
        renderPass(poseStack, flat, packedLight, root, bones, classOf, ColorClass.C1, color1);
        if (color2 != null) {
            renderPass(poseStack, flat, packedLight, root, bones, classOf, ColorClass.C2, color2);
        }
        if (color3 != null) {
            renderPass(poseStack, flat, packedLight, root, bones, classOf, ColorClass.C3, color3);
        }
        renderPass(poseStack, flat, packedLight, root, bones, classOf, ColorClass.REST, restColor);

        // Durchgang B - GLOW: die gluehenden Bones nochmal emissiv obendrauf.
        // entityTranslucentEmissive, NICHT eyes: eyes ignoriert das Alpha der
        // Textur und leuchtet das ganze Cube-Rechteck aus - das Glow blutet dann
        // ueber die Feder-Form hinaus (an 855 gesehen). entityTranslucentEmissive
        // respektiert Alpha UND leuchtet. Eigener Consumer aus demselben buffer,
        // Farbe der jeweiligen Farbklasse (blauer Stern strahlt blau, nicht weiss).
        if (glowCount > 0) {
            VertexConsumer glowConsumer = new FlatNormalConsumer(
                    buffer.getBuffer(net.minecraft.client.renderer.RenderType.entityTranslucentEmissive(texture)));
            glowPass(poseStack, glowConsumer, root, bones, classOf, glowOf, ColorClass.C0, color0);
            glowPass(poseStack, glowConsumer, root, bones, classOf, glowOf, ColorClass.C1, color1);
            if (color2 != null) {
                glowPass(poseStack, glowConsumer, root, bones, classOf, glowOf, ColorClass.C2, color2);
            }
            if (color3 != null) {
                glowPass(poseStack, glowConsumer, root, bones, classOf, glowOf, ColorClass.C3, color3);
            }
            glowPass(poseStack, glowConsumer, root, bones, classOf, glowOf, ColorClass.REST, restColor);
        }

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
    /**
     * Zeichnet NUR die gluehenden Bones der gewuenschten Farbklasse, ueber den
     * emissiven Consumer. packedLight ist hier egal, weil der emissive Typ das
     * Licht ohnehin ignoriert - wir geben FULL_BRIGHT der Form halber.
     * Laeuft zusaetzlich zum normalen Durchgang, daher "strahlt" der Teil auf,
     * statt ihn nur zu ersetzen.
     */
    private static void glowPass(PoseStack poseStack, VertexConsumer glowConsumer,
                                 ModelPart root, Map<String, ModelPart> bones,
                                 Map<String, ColorClass> classOf,
                                 Map<String, Boolean> glowOf,
                                 ColorClass wanted, float[] rgb) {
        if (rgb == null) {
            return;
        }
        boolean any = false;
        for (Map.Entry<String, ModelPart> entry : bones.entrySet()) {
            String name = entry.getKey();
            boolean draw = classOf.get(name) == wanted && Boolean.TRUE.equals(glowOf.get(name));
            entry.getValue().skipDraw = !draw;
            if (draw) {
                any = true;
            }
        }
        if (any) {
            int packedColor = packColor(rgb);
            root.render(poseStack, glowConsumer, LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY, packedColor);
        }
    }

    /**
     * Die Textur-UUID aus einem {@code layer_<uuid>}-Bone-Namen, oder null wenn
     * der Bone kein UUID-Layer ist. Suffixe wie "_rgb" oder "_2021" werden
     * abgeschnitten; layer_slim / layer_right / layer_left liefern null (andere
     * Faelle des Effekts, hier nicht behandelt).
     */
    private static String layerUuidOf(String boneName) {
        if (!boneName.startsWith("layer_")) {
            return null;
        }
        String rest = boneName.substring("layer_".length()).toLowerCase();
        if (rest.length() < 32) {
            return null;
        }
        String uuid = rest.substring(0, 32);
        return uuid.matches("[0-9a-f]{32}") ? uuid : null;
    }

    /**
     * Gehoert dieser Bone zu einer Textur-Variante, die NICHT getragen wird?
     * <p>
     * Layers-Effekt: Ein Cosmetic kann mehrere Varianten als getrennte
     * {@code layer_<textur-uuid>}-Aeste enthalten (1309 Witch Hat hat drei -
     * 48 von 64 Bones gehoeren zu fremden Varianten). Sichtbar ist nur der Ast,
     * dessen UUID der getragenen Textur entspricht - belegt an 1309:
     * default_data[0] = 921de76d-2de9-49b3-85fb-1c512e8ca740, Wurzel-Bone =
     * layer_921de76d2de949b385fb1c512e8ca740 (gleiche UUID ohne Bindestriche,
     * ggf. mit Suffix wie "_rgb" oder "_2021").
     * <p>
     * Geprueft wird die ganze Ahnenkette: Kinder eines fremden Astes muessen
     * ebenfalls verschwinden. Wird nur aufgerufen, wenn es MEHRERE Varianten
     * gibt - bei genau einem Ast (963, 855) gaebe es sonst nichts mehr zu sehen.
     *
     * @param textureUuid getragene Textur-UUID (mit Bindestrichen), darf null sein
     */
    private static boolean isHiddenLayer(String name, BedrockGeometry geo, String textureUuid) {
        if (textureUuid == null) {
            return false;
        }
        String worn = textureUuid.replace("-", "").toLowerCase();

        String cur = name;
        int guard = 0;
        while (cur != null && guard++ < 100) {
            String uuid = layerUuidOf(cur);
            if (uuid != null && !uuid.equals(worn)) {
                return true;
            }
            cur = parentOf(cur, geo);
        }
        return false;
    }

    private static int packColor(float[] rgb) {
        int a = 255;
        int r = Math.round(rgb[0] * 255f);
        int g = Math.round(rgb[1] * 255f);
        int b = Math.round(rgb[2] * 255f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * VertexConsumer-Huelle, die alles unveraendert durchreicht - AUSSER der Normale:
     * die wird auf einen festen Wert (0,1,0) gezwungen. Wirkung: Minecrafts
     * richtungsabhaengige Schattierung (Diffuse) wird ueber das ganze Modell konstant
     * und reagiert nicht mehr auf die Model-Drehung (Sneak-Neigung liess die Farben
     * sonst abdunkeln). Die Lichtkarte (Umgebungshelligkeit) laeuft ueber setUv2 und
     * bleibt unangetastet - Hoehle/Nacht dunkeln also weiterhin normal.
     *
     * Nur fuer Durchgang A (Basisfarbe). Der Glow-Pass ist emissiv und ohnehin
     * schattierungsfrei.
     *
     * Wichtig: die durchreichenden Methoden geben {@code this} (die Huelle) zurueck,
     * nicht das Delegate - sonst braeche die Aufruf-Kette aus der Huelle aus und
     * setNormal wuerde nicht mehr abgefangen.
     */
    private record FlatNormalConsumer(VertexConsumer delegate) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
            delegate.setNormal(0.0F, 1.0F, 0.0F);
            return this;
        }

        // ModelPart nutzt die Pose-Ueberladung, die die Normale VOR dem Schreiben durch
        // die Pose-Matrix (inkl. Sneak-Drehung) transformiert. Auch die abfangen und fest
        // (0,1,0) schreiben - sonst dreht die Sneak-Neigung die Normale doch wieder mit.
        @Override
        public VertexConsumer setNormal(PoseStack.Pose pose, float normalX, float normalY, float normalZ) {
            delegate.setNormal(0.0F, 1.0F, 0.0F);
            return this;
        }
    }
}