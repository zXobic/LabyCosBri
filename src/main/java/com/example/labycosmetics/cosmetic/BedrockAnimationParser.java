package com.example.labycosmetics.cosmetic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Parst eine LabyMod animation.json (Bedrock-Format 1.8.0) in unsere
 * {@link BedrockAnimation}-Struktur.
 * <p>
 * Wir extrahieren pro Animation und pro Bone die Rotations-Keyframes.
 * Scale-Keyframes (nur "glow_main" mit konstantem 0.8) ignorieren wir hier -
 * die 0.8-Grundskalierung wenden wir bereits fest im Layer an.
 */
public final class BedrockAnimationParser {

    private BedrockAnimationParser() {
    }

    public static BedrockAnimation parse(String json) {
        BedrockAnimation anim = new BedrockAnimation();

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject animations = root.getAsJsonObject("animations");
        if (animations == null) {
            return anim;
        }

        for (String clipName : animations.keySet()) {
            JsonObject clipObj = animations.getAsJsonObject(clipName);
            BedrockAnimation.Clip clip = new BedrockAnimation.Clip();

            if (clipObj.has("loop")) {
                // "loop" kann true/false oder "hold_on_last_frame" sein
                JsonElement loopEl = clipObj.get("loop");
                clip.loop = loopEl.isJsonPrimitive() && loopEl.getAsJsonPrimitive().isBoolean()
                        ? loopEl.getAsBoolean()
                        : true;
            }
            if (clipObj.has("animation_length")) {
                clip.lengthSeconds = clipObj.get("animation_length").getAsFloat();
            }

            // anim_time_update pruefen: Enthaelt es einen "-t"-Befehl mit
            // Zustands-Liste (IDLE, MOVING, SNEAKING, ...), ist die Animation
            // zustandsgesteuert und wird von uns nicht abgespielt (wir koennen
            // die Zustaende nicht korrekt trennen -> wuerde Teile einklappen).
            if (clipObj.has("anim_time_update")) {
                JsonElement atu = clipObj.get("anim_time_update");
                if (atu.isJsonPrimitive()) {
                    String s = atu.getAsString();
                    if (s.contains("-t ") && (s.contains("IDLE") || s.contains("MOVING")
                            || s.contains("SNEAK"))) {
                        clip.stateControlled = true;
                    }
                }
            }

            JsonObject bones = clipObj.getAsJsonObject("bones");
            if (bones != null) {
                for (String boneName : bones.keySet()) {
                    JsonObject boneObj = bones.getAsJsonObject(boneName);
                    if (!boneObj.has("rotation")) {
                        continue; // wir interessieren uns nur fuer Rotation
                    }
                    JsonElement rotEl = boneObj.get("rotation");
                    if (!rotEl.isJsonObject()) {
                        continue; // (konstante Rotation als Array ignorieren wir hier)
                    }

                    List<BedrockAnimation.Keyframe> keyframes = new ArrayList<>();
                    JsonObject rotObj = rotEl.getAsJsonObject();
                    for (String timeKey : rotObj.keySet()) {
                        float time = Float.parseFloat(timeKey);
                        float[] value = extractRotation(rotObj.get(timeKey));
                        keyframes.add(new BedrockAnimation.Keyframe(time, value));
                    }
                    // Keyframes nach Zeit sortieren (Reihenfolge im JSON nicht garantiert)
                    keyframes.sort((a, b) -> Float.compare(a.time, b.time));
                    clip.rotations.put(boneName, keyframes);
                }
            }

            anim.clips.put(clipName, clip);
        }

        return anim;
    }

    /**
     * Ein Keyframe-Wert kann direkt ein Array [x,y,z] sein oder ein Objekt
     * mit "post" (und optional "pre"/"lerp_mode"). Wir nehmen "post" als
     * Zielwert (das ist der Wert AB diesem Keyframe).
     */
    private static float[] extractRotation(JsonElement el) {
        if (el.isJsonArray()) {
            return toFloat3(el.getAsJsonArray());
        }
        JsonObject obj = el.getAsJsonObject();
        if (obj.has("post")) {
            JsonElement post = obj.get("post");
            if (post.isJsonArray()) {
                return toFloat3(post.getAsJsonArray());
            }
        }
        if (obj.has("pre")) {
            JsonElement pre = obj.get("pre");
            if (pre.isJsonArray()) {
                return toFloat3(pre.getAsJsonArray());
            }
        }
        return new float[]{0, 0, 0};
    }

    private static float[] toFloat3(JsonArray arr) {
        float[] out = {0, 0, 0};
        for (int i = 0; i < Math.min(3, arr.size()); i++) {
            out[i] = arr.get(i).getAsFloat();
        }
        return out;
    }
}
