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
 *   <li>Bedrock-Y und -Z sind gegenueber Minecraft gespiegelt (weil wir das
 *       Modell beim Bau in Y gespiegelt haben) -> wir negieren Y und Z.</li>
 *   <li>Interpolation: Bedrock nutzt hier "catmullrom" (weiche Kurve). Wir
 *       implementieren eine Catmull-Rom-Interpolation ueber die vier
 *       umliegenden Keyframes; bei Randfaellen faellt sie auf lineare
 *       Interpolation zurueck.</li>
 * </ul>
 */
public final class BedrockAnimator {

    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    // Merkt sich pro ModelPart die Grund-Rotation aus der Geometrie
    // (die statische Pose), damit die Animation sie nicht ueberschreibt,
    // sondern DAZU addiert. WeakHashMap, damit alte Modelle vom GC entsorgt
    // werden koennen.
    private static final Map<ModelPart, float[]> BASE_ROTATION = new WeakHashMap<>();

    private BedrockAnimator() {
    }

    /**
     * Setzt fuer alle animierten Bones die Rotation entsprechend der
     * aktuellen Zeit. Bones, die die Animation nicht kennt, bleiben
     * unveraendert (behalten also ihre Grund-Pose aus der Geometrie).
     *
     * @param root         Wurzel des gebauten Modells
     * @param clip         die abzuspielende Animation
     * @param timeSeconds  aktuelle Zeit (bereits ggf. geloopt)
     * @param bonesByName  Map Bone-Name -> ModelPart (siehe ModelPartIndex)
     */
    public static void apply(ModelPart root, BedrockAnimation.Clip clip,
                             float timeSeconds, Map<String, ModelPart> bonesByName) {
        if (clip == null) {
            return;
        }

        float t = timeSeconds;
        if (clip.loop && clip.lengthSeconds > 0f) {
            t = t % clip.lengthSeconds;
        }

        for (Map.Entry<String, List<BedrockAnimation.Keyframe>> entry : clip.rotations.entrySet()) {
            ModelPart part = bonesByName.get(entry.getKey());
            if (part == null) {
                continue;
            }
            float[] rot = sample(entry.getValue(), t);

            // Grund-Rotation dieses Bones merken (beim ersten Mal), damit wir
            // die statische Pose als Basis behalten. Sonst wuerde die Animation
            // z.B. bei den Elf Wings die spreizende Grund-Rotation loeschen.
            float[] base = BASE_ROTATION.computeIfAbsent(part,
                    p -> new float[]{p.xRot, p.yRot, p.zRot});

            // Animationswert (Grad -> Radiant), konsistent zum Model-Builder:
            // X und Y negieren, Z bleibt. Auf die Grund-Rotation ADDIEREN.
            part.xRot = base[0] + (-rot[0] * DEG_TO_RAD);
            part.yRot = base[1] + (rot[1] * DEG_TO_RAD);
            part.zRot = base[2] + (rot[2] * DEG_TO_RAD);
        }
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

        // Catmull-Rom braucht die zwei aeusseren Stuetzpunkte p0 und p3.
        BedrockAnimation.Keyframe p0 = keys.get(Math.max(0, i - 1));
        BedrockAnimation.Keyframe p3 = keys.get(Math.min(keys.size() - 1, i + 2));

        float[] out = new float[3];
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
