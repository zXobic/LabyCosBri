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
import com.mojang.logging.LogUtils;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;

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
 *
 * <h3>Aenderungen fuer 26.1</h3>
 * <ul>
 *   <li>Die Klassifizierung (Farbe, Glow, Rainbow, versteckte Layer) haengt nur
 *       vom Modell und der getragenen Textur ab, nicht vom Frame. Frueher wurde
 *       sie JEDEN Frame pro Spieler neu berechnet - inkl. linearer Suche in
 *       {@code parentOf} fuer jeden Ahnen jedes Bones. Jetzt einmal berechnet
 *       und in {@link #CACHE} gehalten ({@link #classify}).</li>
 *   <li>Zeichnen und Glow sind getrennte Methoden ({@link #renderBase},
 *       {@link #renderGlow}), weil sie in 26.1 als zwei getrennte
 *       {@code submitCustomGeometry}-Aufrufe mit verschiedenen RenderTypes
 *       laufen. Beide werden erst in der Render-Phase aufgerufen.</li>
 * </ul>
 */
public final class CosmeticColorRenderer {

    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    /** TEST: Glow-Pass aus -> alles normal beleuchtet. Fuer den A/B-Vergleich
     *  am selben Wing (1460 oder 855) im Dunkeln. */
    private static final boolean TEST_NO_GLOW = false;

    private enum ColorClass { C0, C1, C2, C3, RAINBOW, REST }

    /** Die Farben eines getragenen Cosmetics. c2/c3 duerfen null sein. */
    public record Colors(float[] c0, float[] c1, float[] c2, float[] c3, float[] rest) {

        /**
         * Aus den Modell-Farben (color_0, color_1, ... in Reihenfolge, siehe
         * {@link CosmeticData}). Frueher wurden die Farben fest ab data[1] gelesen -
         * bei Cosmetics mit offset-Option landete der Offset-String als Farbe.
         */
        public static Colors fromColors(java.util.List<String> hex) {
            String hex0 = at(hex, 0);
            String hex1 = at(hex, 1);
            String hex2 = at(hex, 2);
            String hex3 = at(hex, 3);
            float[] c0 = hexToRgb(hex0);
            float[] c1 = hexToRgb(hex1);
            float[] c2 = hex2 != null ? hexToRgb(hex2) : null;
            float[] c3 = hex3 != null ? hexToRgb(hex3) : null;
            // Rest-Farbe fuer nicht-color-Teile (glow, blade, middlepart, ...): die letzte
            // tatsaechlich vorhandene Farbe (idR. die Leuchtfarbe).
            float[] rest = c3 != null ? c3 : c2 != null ? c2 : hex1 != null ? c1 : c0;
            return new Colors(c0, c1, c2, c3, rest);
        }

        private static String at(java.util.List<String> data, int index) {
            return (data != null && index < data.size()) ? data.get(index) : null;
        }

        float[] of(ColorClass c) {
            return switch (c) {
                case C0 -> c0;
                case C1 -> c1;
                case C2 -> c2;
                case C3 -> c3;
                case REST -> rest;
                case RAINBOW -> null;
            };
        }
    }

    /**
     * Ergebnis der Klassifizierung eines Modells fuer EINE getragene Textur.
     * Unveraenderlich nach dem Bauen; {@code classOf == null} fuer einen Bone
     * heisst "versteckter Layer".
     */
    public static final class Classification {
        private final Map<String, ColorClass> classOf = new HashMap<>();
        private final Map<String, Boolean> glowOf = new HashMap<>();
        private final Map<String, Integer> rainbowOf = new HashMap<>();
        private int glowCount;
        private int[] rainbowCycles = new int[0];

        public boolean hasGlow() {
            return glowCount > 0;
        }
    }

    /**
     * Cache: Modell-Wurzel -> (Textur-UUID -> Klassifizierung). Schluessel ist
     * der ModelPart (Identitaets-Hash), nicht der BuiltCosmeticModel-Record,
     * dessen hashCode die ganze Bone-Map durchlaufen wuerde. Nur vom
     * Render-Thread benutzt, daher keine Synchronisierung.
     */
    private static final Map<ModelPart, Map<String, Classification>> CACHE = new WeakHashMap<>();

    private CosmeticColorRenderer() {
    }

    // ------------------------------------------------------------------
    // Klassifizierung (einmal pro Modell + Textur)
    // ------------------------------------------------------------------

    /** Klassifizierung holen oder einmalig berechnen. Render-Thread. */
    public static Classification classify(BuiltCosmeticModel built, String textureUuid) {
        String key = textureUuid == null ? "" : textureUuid;
        return CACHE.computeIfAbsent(built.root(), k -> new HashMap<>())
                .computeIfAbsent(key, k -> compute(built, textureUuid));
    }

    private static Classification compute(BuiltCosmeticModel built, String textureUuid) {
        Map<String, ModelPart> bones = built.bonesByName();
        BedrockGeometry geo = built.geometry();
        Map<String, String> parents = parentIndex(geo);

        // Layers-Effekt: Nur filtern, wenn es tatsaechlich MEHRERE Textur-Varianten
        // gibt. Bei genau einem layer_-Ast (963, 855) gibt es nichts zu waehlen -
        // der muss immer sichtbar bleiben, auch wenn die getragene UUID abweicht
        // (im Dev-Client kommt sie aus defaultData und passt nie).
        Set<String> variants = new HashSet<>();
        for (BedrockGeometry.Bone b : geo.bones) {
            String u = layerUuidOf(b.name);
            if (u != null) {
                variants.add(u);
            }
        }
        boolean filterLayers = variants.size() > 1;

        Classification c = fill(bones, parents, filterLayers ? textureUuid : null);

        // Sicherheitsnetz: Passt die getragene UUID zu KEINER Variante, waere das
        // ganze Cosmetic weg. Belegt an 1809 Fire Underglow (2 Varianten, getragene
        // Textur 3f6d8d8f-... gehoert zu keiner: "79 von 79 ausgeblendet"). Dann auf
        // die erste Variante zurueckfallen - eine zu zeigen ist besser als keine,
        // und besser als alle uebereinander (1309: zerrissene Optik).
        int hidden = countHidden(c, bones);
        if (filterLayers && !bones.isEmpty() && hidden == bones.size()) {
            String fallback = new TreeSet<>(variants).first();
            LOGGER.debug("[LabyCos] Layers: getragene Textur {} passt zu keiner der {} "
                    + "Varianten - falle auf {} zurueck", textureUuid, variants.size(), fallback);
            c = fill(bones, parents, fallback);
            hidden = countHidden(c, bones);
        }

        // Pruefsteine, jetzt einmal pro Modell+Textur statt pro Frame.
        // Glow: >0 fuer Glow-Wings (855, 1460), 0 fuer die Gegenprobe (24, 404).
        // Wurzel-glow-Bones getrennt zaehlen: die werden bewusst NICHT als Effekt
        // gewertet (Struktur-Name wie 1460 "glow_main").
        int rootGlow = 0;
        int rainbowCount = 0;
        for (String name : bones.keySet()) {
            if (name.startsWith("glow") && parents.containsKey(name) && parents.get(name) == null) {
                rootGlow++;
            }
            if (c.classOf.get(name) == ColorClass.RAINBOW) {
                rainbowCount++;
            }
        }
        LOGGER.debug("[LabyCos] {} ({}): {} Bones, {} ausgeblendet, {} gluehen, {} Wurzel-glow verworfen, "
                        + "{} Rainbow (Zyklen {})",
                textureUuid, variants.size() + " Varianten", bones.size(), hidden, c.glowCount, rootGlow,
                rainbowCount, java.util.Arrays.toString(c.rainbowCycles));
        return c;
    }

    /**
     * Fuellt eine Klassifizierung. {@code visibleLayer} = UUID der sichtbaren
     * Layer-Variante, oder null = keine Layer filtern.
     */
    private static Classification fill(Map<String, ModelPart> bones, Map<String, String> parents,
                                       String visibleLayer) {
        Classification c = new Classification();
        Set<Integer> cycles = new TreeSet<>();
        for (String name : bones.keySet()) {
            if (visibleLayer != null && isHiddenLayer(name, parents, visibleLayer)) {
                c.classOf.put(name, null);
                c.glowOf.put(name, false);
                c.rainbowOf.put(name, 0);
                continue;
            }
            ColorClass cls = classifyWithInheritance(name, parents);
            c.classOf.put(name, cls);
            int rainbow = rainbowWithInheritance(name, parents);
            c.rainbowOf.put(name, rainbow);
            if (cls == ColorClass.RAINBOW) {
                cycles.add(rainbow);
            }
            boolean g = !TEST_NO_GLOW && glowsWithInheritance(name, parents);
            c.glowOf.put(name, g);
            if (g) {
                c.glowCount++;
            }
        }
        c.rainbowCycles = cycles.stream().mapToInt(Integer::intValue).toArray();
        return c;
    }

    private static int countHidden(Classification c, Map<String, ModelPart> bones) {
        int hidden = 0;
        for (String name : bones.keySet()) {
            if (c.classOf.get(name) == null) {
                hidden++;
            }
        }
        return hidden;
    }

    /**
     * Bone-Name -> Parent-Name, einmal aufgebaut. Ersetzt die fruehere lineare
     * Suche ueber alle Bones bei jedem Schritt die Ahnenkette hinauf.
     * Wurzel-Bones sind mit Wert null enthalten.
     */
    private static Map<String, String> parentIndex(BedrockGeometry geo) {
        Map<String, String> parents = new HashMap<>();
        for (BedrockGeometry.Bone b : geo.bones) {
            parents.put(b.name, b.parent);
        }
        return parents;
    }

    /** Parent eines Bones, inkl. der _rc-Zwischenbones (die nicht in der geo stehen). */
    private static String parentOf(String boneName, Map<String, String> parents) {
        if (parents.containsKey(boneName)) {
            return parents.get(boneName);
        }
        int idx = boneName.lastIndexOf("_rc");
        if (idx > 0) {
            return boneName.substring(0, idx);
        }
        return null;
    }

    /**
     * Glueht dieser Bone? Wahr, wenn er selbst oder ein VORFAHRE mit "glow"
     * beginnt - dieselbe Ketten-Vererbung wie bei der Farbe, weil der Effekt
     * laut Doku "on its children" wirkt.
     * <p>
     * Das gilt AUCH fuer den Wurzel-Bone. Eine fruehere Regel hat Wurzel-glow
     * (z.B. 1460 "glow_main", Halo 9 "glow_a") als reinen Struktur-Namen
     * ignoriert. WIDERLEGT im Vergleich mit LabyMod 26.3 in einer dunklen Hoehle:
     * Halo und Angel Wings leuchten dort komplett. 27 Cosmetics haben einen
     * glow-Bone als Wurzel (u.a. Fire Arms, Lightning Cloak, Fireworks).
     * <p>
     * Doku vs. Material: Die offizielle Glow-Doku beschreibt einen Vierteiler
     * glow_STAERKE_FARBE_NAME. Ein Katalog-Scan ueber alle 203 glow-Bones zeigt
     * AUSNAHMSLOS den Zweiteiler glow_NAME - deshalb emissiv ohne Parameter.
     */
    private static boolean glowsWithInheritance(String boneName, Map<String, String> parents) {
        String current = boneName;
        int guard = 0;
        while (current != null && guard++ < 100) {
            if (current.startsWith("glow")) {
                return true;
            }
            current = parentOf(current, parents);
        }
        return false;
    }

    private static ColorClass classifyWithInheritance(String boneName, Map<String, String> parents) {
        String current = boneName;
        int guard = 0;
        while (current != null && guard++ < 100) {
            if (current.startsWith("color_rainbow")) return ColorClass.RAINBOW;
            if (current.startsWith("color_0")) return ColorClass.C0;
            if (current.startsWith("color_1")) return ColorClass.C1;
            if (current.startsWith("color_2")) return ColorClass.C2;
            if (current.startsWith("color_3")) return ColorClass.C3;
            current = parentOf(current, parents);
        }
        return ColorClass.REST;
    }

    /**
     * Die Textur-UUID aus einem {@code layer_<uuid>}-Bone-Namen, oder null wenn
     * der Bone kein UUID-Layer ist. Suffixe wie "_rgb" oder "_2021" werden
     * abgeschnitten; layer_slim / layer_right / layer_left liefern null.
     */
    private static String layerUuidOf(String boneName) {
        if (!boneName.startsWith("layer_")) {
            return null;
        }
        String rest = boneName.substring("layer_".length()).toLowerCase(java.util.Locale.ROOT);
        if (rest.length() < 32) {
            return null;
        }
        String uuid = rest.substring(0, 32);
        return uuid.matches("[0-9a-f]{32}") ? uuid : null;
    }

    /**
     * Gehoert dieser Bone zu einer Textur-Variante, die NICHT getragen wird?
     * Belegt an 1309 Witch Hat (drei layer_-Aeste, 48 von 64 Bones fremd).
     * Geprueft wird die ganze Ahnenkette.
     *
     * @param textureUuid sichtbare Textur-UUID (mit oder ohne Bindestriche)
     */
    private static boolean isHiddenLayer(String name, Map<String, String> parents, String textureUuid) {
        String worn = textureUuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        String cur = name;
        int guard = 0;
        while (cur != null && guard++ < 100) {
            String uuid = layerUuidOf(cur);
            if (uuid != null && !uuid.equals(worn)) {
                return true;
            }
            cur = parentOf(cur, parents);
        }
        return false;
    }

    /**
     * Dauer eines Regenbogen-Durchlaufs in ms aus dem Bone-Namen, 0 wenn kein
     * Rainbow-Bone. Format laut Colors-Doku: {@code color_rainbow_<dauer_ms>[_name]}.
     * Fast immer 10000; Cap 19 und Hobby Horse 1160 haben zusaetzlich 10001.
     */
    private static int rainbowDurationOf(String boneName) {
        final String prefix = "color_rainbow_";
        if (!boneName.startsWith(prefix)) {
            return 0;
        }
        String rest = boneName.substring(prefix.length());
        int end = 0;
        while (end < rest.length() && Character.isDigit(rest.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(rest.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Dauer des naechsten Rainbow-VORFAHREN - gleiche Ketten-Vererbung wie Farbe/Glow. */
    private static int rainbowWithInheritance(String boneName, Map<String, String> parents) {
        String current = boneName;
        int guard = 0;
        while (current != null && guard++ < 100) {
            int d = rainbowDurationOf(current);
            if (d > 0) {
                return d;
            }
            current = parentOf(current, parents);
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // Zeichnen (Render-Phase, aus dem submitCustomGeometry-Callback)
    // ------------------------------------------------------------------

    /**
     * Durchgang A - normale Farbe, ganzes Modell (glow-Teile inklusive, damit
     * sie ihre Grundfarbe/Form behalten), danach die Rainbow-Bones.
     * <p>
     * Der consumer wird in einen FlatNormalConsumer gewickelt: der zwingt jede
     * Vertex-Normale auf einen festen Wert, damit die Vanilla-Richtungs-
     * schattierung konstant ist und nicht auf die Sneak-Neigung reagiert.
     */
    public static void renderBase(PoseStack poseStack, VertexConsumer consumer, int packedLight,
                                  BuiltCosmeticModel built, Classification cls, Colors colors) {
        VertexConsumer flat = new FlatNormalConsumer(consumer);
        ModelPart root = built.root();
        Map<String, ModelPart> bones = built.bonesByName();

        for (ColorClass c : new ColorClass[]{ColorClass.C0, ColorClass.C1, ColorClass.C2,
                ColorClass.C3, ColorClass.REST}) {
            float[] rgb = colors.of(c);
            if (rgb == null) {
                continue;
            }
            if (selectBones(bones, cls, c, false, 0)) {
                root.render(poseStack, flat, packedLight, OverlayTexture.NO_OVERLAY, packColor(rgb));
            }
        }

        // Regenbogen: Farbe kommt aus der Uhr, nicht aus den getragenen Daten.
        // Ein Pass je vorkommender Zyklusdauer (Cap 19 hat 10000 UND 10001).
        for (int d : cls.rainbowCycles) {
            if (selectBones(bones, cls, ColorClass.RAINBOW, false, d)) {
                root.render(poseStack, flat, packedLight, OverlayTexture.NO_OVERLAY,
                        packColor(rainbowColor(d)));
            }
        }

        resetSkip(bones);
    }

    /**
     * Durchgang B - GLOW: die gluehenden Bones emissiv, in der Farbe ihrer
     * Farbklasse (blauer Stern strahlt blau, nicht weiss). Gehoert zu einem
     * RenderType wie entityTranslucentEmissive: der respektiert Alpha UND leuchtet
     * (eyes wuerde das ganze Cube-Rechteck ausleuchten).
     * <p>
     * Die Helligkeit regelt {@link #GLOW_STRENGTH}: die Farbe wird damit
     * abgedunkelt, weil der emissive RenderType das Umgebungslicht ignoriert.
     * <p>
     * Rainbow-Bones koennen ebenfalls gluehen - bei 1369 haengen alle 16 unter
     * glow_rainbow1 / glow_rainbow2. Daher ein eigener Durchgang je Zyklusdauer.
     */
    public static void renderGlow(PoseStack poseStack, VertexConsumer consumer,
                                  BuiltCosmeticModel built, Classification cls, Colors colors) {
        VertexConsumer flat = new FlatNormalConsumer(consumer);
        ModelPart root = built.root();
        Map<String, ModelPart> bones = built.bonesByName();

        for (ColorClass c : new ColorClass[]{ColorClass.C0, ColorClass.C1, ColorClass.C2,
                ColorClass.C3, ColorClass.REST}) {
            float[] rgb = colors.of(c);
            if (rgb == null) {
                continue;
            }
            if (selectBones(bones, cls, c, true, 0)) {
                root.render(poseStack, flat, LightCoordsUtil.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY, packColor(glowDimmed(rgb)));
            }
        }
        for (int d : cls.rainbowCycles) {
            if (selectBones(bones, cls, ColorClass.RAINBOW, true, d)) {
                root.render(poseStack, flat, LightCoordsUtil.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY, packColor(glowDimmed(rainbowColor(d))));
            }
        }

        resetSkip(bones);
    }

    /**
     * Glow-Staerke in Prozent (0-100). Die LabyMod-Doku sieht eine Staerke im
     * Bone-Namen vor (glow_STAERKE_FARBE_NAME), aber KEIN Cosmetic im Katalog nutzt
     * das (alle 203 glow-Bones heissen glow_NAME). LabyMod hat also einen internen
     * Standardwert, der nicht oeffentlich ist.
     * KALIBRIERWERT: im Dunkeln neben LabyMod vergleichen und anpassen.
     * 100 = volle Leuchtkraft, kleiner = gedaempfter.
     */
    private static final float GLOW_STRENGTH = 100f;

    /** Farbe mit der Glow-Staerke abgedunkelt. */
    private static float[] glowDimmed(float[] rgb) {
        float k = Math.max(0f, Math.min(100f, GLOW_STRENGTH)) / 100f;
        return new float[]{rgb[0] * k, rgb[1] * k, rgb[2] * k};
    }

    /**
     * Setzt skipDraw so, dass nur die gewuenschten Bones gezeichnet werden.
     * <p>
     * Leuchtende Bones landen NUR im Glow-Durchgang, nicht-leuchtende NUR im
     * normalen. Frueher wurden leuchtende Teile in beiden gezeichnet (Glow obendrauf).
     * Seit 26.2 sortiert Minecraft durchscheinende Teile nach Kamera-Abstand - beide
     * Durchgaenge haben denselben Abstand, die Reihenfolge ist also zufaellig, und
     * der normale Durchgang hat den Glow uebermalt. So ueberlappen sie nie.
     *
     * @param glowing      true = nur leuchtende Bones, false = nur nicht-leuchtende
     * @param rainbowCycle bei RAINBOW: nur Bones dieser Zyklusdauer
     * @return ob ueberhaupt ein Bone gezeichnet wird
     */
    private static boolean selectBones(Map<String, ModelPart> bones, Classification cls,
                                       ColorClass wanted, boolean glowing, int rainbowCycle) {
        boolean any = false;
        for (Map.Entry<String, ModelPart> entry : bones.entrySet()) {
            String name = entry.getKey();
            boolean draw = cls.classOf.get(name) == wanted;
            if (draw && wanted == ColorClass.RAINBOW) {
                draw = cls.rainbowOf.get(name) == rainbowCycle;
            }
            if (draw) {
                draw = Boolean.TRUE.equals(cls.glowOf.get(name)) == glowing;
            }
            entry.getValue().skipDraw = !draw;
            any |= draw;
        }
        return any;
    }

    private static void resetSkip(Map<String, ModelPart> bones) {
        for (ModelPart part : bones.values()) {
            part.skipDraw = false;
        }
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
     * Farbe des Regenbogens zum aktuellen Zeitpunkt. Wall-Clock statt Game-Ticks,
     * damit alle Betrachter synchron laufen.
     * ANNAHME, nicht dokumentiert: voller HSV-Kreis bei Saettigung und Helligkeit 1.
     */
    private static float[] rainbowColor(int durationMs) {
        if (durationMs <= 0) {
            return new float[]{1f, 1f, 1f};
        }
        float hue = (System.currentTimeMillis() % durationMs) / (float) durationMs;
        float h = hue * 6f;
        int sector = (int) h;
        float f = h - sector;
        float q = 1f - f;
        return switch (sector % 6) {
            case 0 -> new float[]{1f, f, 0f};
            case 1 -> new float[]{q, 1f, 0f};
            case 2 -> new float[]{0f, 1f, f};
            case 3 -> new float[]{0f, q, 1f};
            case 4 -> new float[]{f, 0f, 1f};
            default -> new float[]{1f, 0f, q};
        };
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
     * richtungsabhaengige Schattierung wird ueber das ganze Modell konstant und
     * reagiert nicht mehr auf die Model-Drehung (Sneak-Neigung liess die Farben
     * sonst abdunkeln). Die Lichtkarte laeuft ueber setUv2 und bleibt unangetastet.
     * <p>
     * Die durchreichenden Methoden geben {@code this} zurueck, nicht das Delegate -
     * sonst braeche die Aufruf-Kette aus der Huelle aus.
     * <p>
     * 26.3: Methodensatz wie NeoForges VertexConsumerWrapper (26.3.x). Falls der Compiler hier eine fehlende abstrakte Methode meldet, hat
     * Mojang VertexConsumer erweitert - einfach durchreichen wie die anderen.
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
        public VertexConsumer setColor(int argb) {
            delegate.setColor(argb);
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

        // Neu in 26.3 (UV-Koordinaten fuer Sheeted Decals), abstrakt im Interface.
        @Override
        public VertexConsumer setUv3(float u, float v) {
            delegate.setUv3(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
            delegate.setNormal(0.0F, 1.0F, 0.0F);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            delegate.setLineWidth(width);
            return this;
        }
    }
}
