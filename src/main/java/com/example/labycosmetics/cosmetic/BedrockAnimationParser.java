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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parst eine LabyMod animation.json (Bedrock-Format 1.8.0) in unsere
 * {@link BedrockAnimation}-Struktur.
 * <p>
 * Wir extrahieren pro Animation und pro Bone die Rotations-Keyframes.
 * Scale-Keyframes (nur "glow_main" mit konstantem 0.8) ignorieren wir hier -
 * die 0.8-Grundskalierung wenden wir bereits fest im Layer an.
 */
public final class BedrockAnimationParser {

    private static final Logger LOGGER = LogUtils.getLogger();

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

            clip.loopMode = parseLoopMode(clipObj, clipName);

            if (clipObj.has("animation_length")) {
                clip.lengthSeconds = clipObj.get("animation_length").getAsFloat();
            }

            if (clipObj.has("anim_time_update")) {
                JsonElement atu = clipObj.get("anim_time_update");
                if (atu.isJsonPrimitive()) {
                    parseAnimTimeUpdate(atu.getAsString(), clip, clipName);
                }
            }

            JsonObject bones = clipObj.getAsJsonObject("bones");
            if (bones != null) {
                for (String boneName : bones.keySet()) {
                    JsonObject boneObj = bones.getAsJsonObject(boneName);
                    putChannel(boneObj, "rotation", clip.rotations, boneName, clipName);
                    putChannel(boneObj, "position", clip.positions, boneName, clipName);
                    // "scale" bleibt bewusst aussen vor: kommt nur bei
                    // 1460/glow_main vor und ist dort konstant 0.8 - das
                    // wendet der Layer bereits fest an.
                }
            }

            anim.clips.put(clipName, clip);
        }

        return anim;
    }

    /**
     * "loop" ist true, false ODER der String "hold_on_last_frame"
     * (Wing 54: start_moving / stop_moving).
     * <p>
     * Fehlt das Feld, ist der Bedrock-Default "einmal abspielen".
     */
    private static BedrockAnimation.LoopMode parseLoopMode(JsonObject clipObj, String clipName) {
        if (!clipObj.has("loop")) {
            return BedrockAnimation.LoopMode.ONCE;
        }
        JsonElement el = clipObj.get("loop");
        if (!el.isJsonPrimitive()) {
            LOGGER.warn("[LabyCos] Clip '{}': unerwartetes loop-Feld {}", clipName, el);
            return BedrockAnimation.LoopMode.LOOP;
        }
        if (el.getAsJsonPrimitive().isBoolean()) {
            return el.getAsBoolean()
                    ? BedrockAnimation.LoopMode.LOOP
                    : BedrockAnimation.LoopMode.ONCE;
        }
        String s = el.getAsString().toLowerCase(Locale.ROOT);
        if (s.equals("hold_on_last_frame")) {
            return BedrockAnimation.LoopMode.HOLD_LAST;
        }
        LOGGER.warn("[LabyCos] Clip '{}': unbekannter loop-Wert '{}'", clipName, s);
        return BedrockAnimation.LoopMode.LOOP;
    }

    /**
     * Zerlegt das Feld "anim_time_update", z.B.
     * "-t IDLE,SNEAK_IDLE -q true -s 5" oder "-t MOVING -q true -c NOT_SNEAKING".
     * <p>
     * Flags laut docs.labymod.net/pages/cosmetics/arguments/:
     * -t Zustandsliste, -c Bedingungsliste (alle muessen zutreffen),
     * -p Lose fuer die gewichtete Ziehung, -s Speedup bei gefuellter Queue,
     * -q Wechsel wartet aufs Clip-Ende, -f bricht den laufenden Clip ab und
     * spielt sofort. Weder -q noch -f: Trigger wird verworfen, wenn schon
     * etwas laeuft (570 Clips im Katalog, kein bekannter Wing).
     * Unbekannte Flags werden geloggt statt still verschluckt.
     */
    private static void parseAnimTimeUpdate(String s, BedrockAnimation.Clip clip, String clipName) {
        String[] tok = s.trim().split("\\s+");
        for (int i = 0; i < tok.length - 1; i++) {
            String value = tok[i + 1];
            switch (tok[i]) {
                case "-t" -> {
                    for (String raw : value.split(",")) {
                        String name = raw.trim().toUpperCase(Locale.ROOT);
                        if (name.isEmpty()) {
                            continue;
                        }
                        try {
                            clip.states.add(BedrockAnimation.AnimState.valueOf(name));
                        } catch (IllegalArgumentException e) {
                            LOGGER.warn("[LabyCos] Clip '{}': unbekannter Zustand '{}'", clipName, name);
                        }
                    }
                }
                case "-c" -> {
                    // Kommaliste, belegt: Wing 24 hat "-c ON_GROUND,NOT_SNEAKING".
                    // Alle Teile muessen zutreffen (Doku: "List of requirements
                    // that has to match").
                    // Gross-/Kleinschreibung ist NICHT einheitlich:
                    // Wing 35 hat "-c sneaking" und "-c NOT_SNEAKING".
                    for (String raw : value.split(",")) {
                        String c = raw.trim().toUpperCase(Locale.ROOT);
                        if (c.isEmpty()) {
                            continue;
                        }
                        try {
                            clip.conditions.add(BedrockAnimation.Condition.valueOf(c));
                        } catch (IllegalArgumentException e) {
                            // Kommt im echten Material vor: 1689 schreibt
                            // "MOVING_FORWARD" statt "MOTION_FORWARD".
                            LOGGER.warn("[LabyCos] Clip '{}': unbekannte -c Bedingung '{}'", clipName, c);
                        }
                    }
                }
                case "-p" -> {
                    try {
                        clip.probability = Integer.parseInt(value.trim());
                    } catch (NumberFormatException e) {
                        LOGGER.warn("[LabyCos] Clip '{}': -p ist keine Zahl: '{}'", clipName, value);
                    }
                }
                case "-s" -> {
                    try {
                        clip.speed = Float.parseFloat(value.trim());
                    } catch (NumberFormatException e) {
                        LOGGER.warn("[LabyCos] Clip '{}': -s ist keine Zahl: '{}'", clipName, value);
                    }
                }
                case "-q" -> clip.queued = parseFlagBool(value, "-q", clipName);
                case "-f" -> clip.force = parseFlagBool(value, "-f", clipName);
                default -> {
                    /* Werte-Token (z.B. "IDLE,MOVING", "true", "5") landen hier
                     ebenfalls - die beginnen aber nie mit "-<Buchstabe>". Ein
                     Token, das mit "-" und einem Buchstaben anfaengt, ist dagegen
                     ein echtes, hier nicht behandeltes Flag. Bewusst length() >= 2
                     statt == 2, damit auch mehrbuchstabige Flags gemeldet werden:
                     "-lr" (Lizard 1905) fiel bei == 2 still durch und strafte den
                     Methodenkommentar oben Luegen ("werden geloggt").*/
                    if (tok[i].length() >= 2 && tok[i].charAt(0) == '-'
                            && Character.isLetter(tok[i].charAt(1))) {
                        LOGGER.warn("[LabyCos] Clip '{}': unbekanntes Flag '{}' ({})",
                                clipName, tok[i], s);
                    }
                }
            }
        }
    }

    /**
     * Liest einen boolschen Flag-Wert. Gemeldet statt still zu false gemacht:
     * Boolean.parseBoolean schluckt jeden Unsinn als false, und stilles
     * Verschlucken ist genau das, was dieser Parser sonst nirgends tut.
     * <p>
     * Case-insensitive, weil "-q True" (grosses T) einmal im Katalog steht
     * (Tree Spirit 1680 - dieselbe Datei hat auch andere Schlampigkeiten).
     */
    private static boolean parseFlagBool(String value, String flag, String clipName) {
        String v = value.trim();
        if (v.equalsIgnoreCase("true")) {
            return true;
        }
        if (v.equalsIgnoreCase("false")) {
            return false;
        }
        LOGGER.warn("[LabyCos] Clip '{}': {} ist kein true/false: '{}'", clipName, flag, value);
        return false;
    }

    /**
     * Liest einen Keyframe-Kanal eines Bones ("rotation" oder "position").
     * Fehlt der Kanal, passiert nichts.
     */
    private static void putChannel(JsonObject boneObj, String channel,
                                   Map<String, List<BedrockAnimation.Keyframe>> target,
                                   String boneName, String clipName) {
        if (!boneObj.has(channel)) {
            return;
        }
        JsonElement el = boneObj.get(channel);
        if (!el.isJsonObject()) {
            // Konstanter Wert als Array statt Keyframe-Liste. Kommt in den
            // fuenf bekannten Dateien nicht vor - deshalb melden statt raten.
            LOGGER.warn("[LabyCos] Clip '{}', Bone '{}': {} ist kein Keyframe-Objekt ({})",
                    clipName, boneName, channel, el);
            return;
        }
        List<BedrockAnimation.Keyframe> keys = new ArrayList<>();
        JsonObject obj = el.getAsJsonObject();
        for (String timeKey : obj.keySet()) {
            JsonElement kf = obj.get(timeKey);
            keys.add(new BedrockAnimation.Keyframe(
                    Float.parseFloat(timeKey), extractVec3(kf), isCatmullRom(kf)));
        }
        // Reihenfolge im JSON ist nicht garantiert.
        keys.sort((a, b) -> Float.compare(a.time, b.time));
        target.put(boneName, keys);
    }

    /**
     * Ein Keyframe-Wert kann direkt ein Array [x,y,z] sein oder ein Objekt
     * mit "post" (und optional "pre"/"lerp_mode"). Wir nehmen "post" als
     * Zielwert (das ist der Wert AB diesem Keyframe).
     */
    private static float[] extractVec3(JsonElement el) {
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

    /**
     * Bedrock-Default ist LINEAR. Weich interpoliert wird nur ein
     * Keyframe-Objekt mit "lerp_mode": "catmullrom".
     * <p>
     * Verteilung in den bekannten Dateien: 404 zu 100% linear, 35 und 1460
     * zu 100% catmullrom, 54 und 963 gemischt.
     */
    private static boolean isCatmullRom(JsonElement el) {
        if (!el.isJsonObject()) {
            return false;
        }
        JsonObject obj = el.getAsJsonObject();
        return obj.has("lerp_mode")
                && "catmullrom".equalsIgnoreCase(obj.get("lerp_mode").getAsString());
    }

    private static float[] toFloat3(JsonArray arr) {
        float[] out = {0, 0, 0};
        for (int i = 0; i < Math.min(3, arr.size()); i++) {
            out[i] = arr.get(i).getAsFloat();
        }
        return out;
    }
}