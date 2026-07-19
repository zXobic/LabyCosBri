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

import net.minecraft.client.model.geom.ModelPart;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Wendet eine {@link BedrockAnimation.Clip} auf die Bones (ModelParts) eines
 * Modells an. Berechnet fuer die aktuelle Animationszeit je Bone die
 * interpolierte Rotation und setzt sie auf den ModelPart.
 *
 * <h3>Wichtige Umrechnungen</h3>
 * <ul>
 *   <li>Bedrock-Rotationen sind in GRAD, ModelPart erwartet RADIANT.</li>
 *   <li>Die Winkel gehen unveraendert durch - gleiche Konvention wie im
 *       BedrockModelBuilder. Bei POSITIONEN wird Y gespiegelt, X und Z
 *       nicht.</li>
 *   <li>Interpolation: "lerp_mode" pro Keyframe entscheidet zwischen linear
 *       (Bedrock-Default) und catmullrom. Nicht global - Wing 54 und 963
 *       mischen beides innerhalb einer Datei.</li>
 *   <li>Vor jedem Anwenden werden ALLE Bones auf ihre Grund-Pose
 *       zurueckgesetzt. Sonst bliebe ein Bone, den der vorige Clip animiert
 *       hat und der neue nicht kennt, fuer immer in seiner letzten Pose
 *       stehen.</li>
 * </ul>
 */
public final class BedrockAnimator {

    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    // Merkt sich pro ModelPart die Grund-Rotation aus der Geometrie
    // (die statische Pose), damit die Animation sie nicht ueberschreibt,
    // sondern DAZU addiert. WeakHashMap, damit alte Modelle vom GC entsorgt
    // werden koennen.
    private static final Map<ModelPart, float[]> BASE_ROTATION = new WeakHashMap<>();

    /** Dasselbe fuer die Grund-Position aus der Geometrie. */
    private static final Map<ModelPart, float[]> BASE_POSITION = new WeakHashMap<>();

    private BedrockAnimator() {
    }

    /**
     * Setzt fuer alle animierten Bones die Rotation entsprechend der
     * aktuellen Zeit. Bones, die die Animation nicht kennt, bleiben
     * unveraendert (behalten also ihre Grund-Pose aus der Geometrie).
     *
     * @param root         Wurzel des gebauten Modells
     * @param clip         die abzuspielende Animation
     * @param timeSeconds  Zeit seit Beginn dieses Clips (NICHT vorgeloopt -
     *                     das Umbrechen macht diese Methode je nach LoopMode)
     * @param bonesByName  Map Bone-Name -> ModelPart (siehe ModelPartIndex)
     */
    public static void apply(ModelPart root, BedrockAnimation.Clip clip,
                             float timeSeconds, Map<String, ModelPart> bonesByName) {
        // Immer zuruecksetzen - auch bei clip == null. Sonst friert das
        // Modell in der letzten animierten Pose ein statt in die Ruhepose
        // zurueckzukehren.
        resetToBase(bonesByName);
        if (clip == null) {
            return;
        }

        float t = clipTime(clip, timeSeconds);

        for (Map.Entry<String, List<BedrockAnimation.Keyframe>> entry : clip.rotations.entrySet()) {
            ModelPart part = bonesByName.get(entry.getKey());
            if (part == null) {
                continue;
            }
            float[] rot = sample(entry.getValue(), t);
            float[] base = baseOf(part);

            // Animationswert (Grad -> Radiant), konsistent zum Model-Builder:
            // Werte gehen unveraendert durch. Auf die Grund-Rotation ADDIEREN.
            part.xRot = base[0] + (rot[0] * DEG_TO_RAD);
            part.yRot = base[1] + (rot[1] * DEG_TO_RAD);
            part.zRot = base[2] + (rot[2] * DEG_TO_RAD);
        }

        for (Map.Entry<String, List<BedrockAnimation.Keyframe>> entry : clip.positions.entrySet()) {
            ModelPart part = bonesByName.get(entry.getKey());
            if (part == null) {
                continue;
            }
            float[] pos = sample(entry.getValue(), t);
            float[] base = basePosOf(part);

            // NUR Y wird gespiegelt - dieselbe Konvention wie im
            // BedrockModelBuilder (py = -(pivot[1] - parentPivot[1])).
            // X und Z gehen unveraendert durch.
            part.x = base[0] + pos[0];
            part.y = base[1] - pos[1];
            part.z = base[2] + pos[2];
        }
    }

    /**
     * Rechnet die verstrichene Zeit auf die Zeit innerhalb des Clips um.
     * <p>
     * HOLD_LAST und ONCE verhalten sich hier gleich: beide klemmen am Ende
     * fest. Der Unterschied ist Sache des Aufrufers - bei ONCE soll er
     * weiterschalten, bei HOLD_LAST stehenbleiben.
     */
    private static float clipTime(BedrockAnimation.Clip clip, float timeSeconds) {
        if (clip.lengthSeconds <= 0f) {
            return timeSeconds;
        }
        if (clip.loopMode == BedrockAnimation.LoopMode.LOOP) {
            return timeSeconds % clip.lengthSeconds;
        }
        return Math.min(timeSeconds, clip.lengthSeconds);
    }

    /** Setzt alle Bones des Modells auf ihre Grund-Pose aus der Geometrie. */
    private static void resetToBase(Map<String, ModelPart> bonesByName) {
        for (ModelPart part : bonesByName.values()) {
            float[] rot = baseOf(part);
            part.xRot = rot[0];
            part.yRot = rot[1];
            part.zRot = rot[2];

            float[] pos = basePosOf(part);
            part.x = pos[0];
            part.y = pos[1];
            part.z = pos[2];
        }
    }

    /** Grund-Position eines Bones aus der Geometrie, beim ersten Mal gemerkt. */
    private static float[] basePosOf(ModelPart part) {
        return BASE_POSITION.computeIfAbsent(part,
                p -> new float[]{p.x, p.y, p.z});
    }

    /**
     * Grund-Rotation eines Bones - beim ersten Aufruf aus dem frisch
     * gebauten ModelPart gelesen und gemerkt. Ohne das wuerde die Animation
     * die statische Pose loeschen (z.B. die spreizende Grund-Rotation der
     * Elf Wings).
     */
    private static float[] baseOf(ModelPart part) {
        return BASE_ROTATION.computeIfAbsent(part,
                p -> new float[]{p.xRot, p.yRot, p.zRot});
    }

    /**
     * Ermittelt den interpolierten Rotationswert zur Zeit t aus einer nach
     * Zeit sortierten Keyframe-Liste.
     */
    private static float[] sample(List<BedrockAnimation.Keyframe> keys, float t) {
        if (keys.isEmpty()) {
            return new float[]{0, 0, 0};
        }
        if (keys.size() == 1 || t <= keys.get(0).time) {
            return keys.get(0).value.clone();
        }
        if (t >= keys.get(keys.size() - 1).time) {
            return keys.get(keys.size() - 1).value.clone();
        }

        // Segment finden: keys[i].time <= t < keys[i+1].time
        int i = 0;
        while (i < keys.size() - 1 && keys.get(i + 1).time <= t) {
            i++;
        }

        BedrockAnimation.Keyframe p1 = keys.get(i);
        BedrockAnimation.Keyframe p2 = keys.get(i + 1);

        float span = p2.time - p1.time;
        float localT = span > 0f ? (t - p1.time) / span : 0f;

        float[] out = new float[3];

        // Bedrock-Default ist LINEAR. Weich wird nur interpoliert, wenn
        // BEIDE Enden des Segments catmullrom sagen.
        //
        // Welches Ende die Regel vorgibt, ist NICHT belegt - denkbar waeren
        // auch "linkes Ende entscheidet" oder "eines reicht". Gemessen ueber
        // alle fuenf bekannten Wings sind die drei Regeln bei 35, 54, 404 und
        // 1460 exakt deckungsgleich und unterscheiden sich nur bei
        // 963 idle2/color_0_a3 um 8,5 Grad. Deshalb die konservative Wahl.
        // Wer es belegt: hier eintragen.
        if (!p1.catmullrom || !p2.catmullrom) {
            for (int c = 0; c < 3; c++) {
                out[c] = p1.value[c] + (p2.value[c] - p1.value[c]) * localT;
            }
            return out;
        }

        // Catmull-Rom braucht die zwei aeusseren Stuetzpunkte p0 und p3.
        BedrockAnimation.Keyframe p0 = keys.get(Math.max(0, i - 1));
        BedrockAnimation.Keyframe p3 = keys.get(Math.min(keys.size() - 1, i + 2));

        for (int c = 0; c < 3; c++) {
            out[c] = catmullRom(p0.value[c], p1.value[c], p2.value[c], p3.value[c], localT);
        }
        return out;
    }

    /**
     * Catmull-Rom-Interpolation zwischen v1 und v2 (v0/v3 sind die
     * benachbarten Stuetzpunkte, die die Kurvenform bestimmen).
     */
    private static float catmullRom(float v0, float v1, float v2, float v3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5f * (
                (2f * v1)
                + (-v0 + v2) * t
                + (2f * v0 - 5f * v1 + 4f * v2 - v3) * t2
                + (-v0 + 3f * v1 - 3f * v2 + v3) * t3
        );
    }
}
