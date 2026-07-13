package com.example.labycosmetics.cosmetic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Datencontainer fuer eine geparste Bedrock-Animation (animation.json).
 * Enthaelt mehrere benannte Animationen (z.B. "Idle", "Moving"), jede mit
 * pro-Bone-Keyframes fuer Rotation (und optional Scale).
 */
public final class BedrockAnimation {

    /** Alle Animationen dieser Datei, nach Name (z.B. "Idle"). */
    public final Map<String, Clip> clips = new HashMap<>();

    /** Eine einzelne Animation. */
    public static final class Clip {
        public boolean loop = true;
        public float lengthSeconds = 0f;
        public boolean stateControlled = false;  // true wenn -t Zustands-Steuerung (IDLE/MOVING/...)
        /** Bone-Name -> Liste von Rotations-Keyframes. */
        public final Map<String, List<Keyframe>> rotations = new HashMap<>();
    }

    /** Ein Keyframe: Zeitpunkt + Zielwert (Rotation in Grad, x/y/z). */
    public static final class Keyframe {
        public float time;
        public float[] value = {0, 0, 0};

        public Keyframe(float time, float[] value) {
            this.time = time;
            this.value = value;
        }
    }

    /**
     * Sucht einen Clip, dessen Name (case-insensitive) auf das angegebene
     * Suffix endet - z.B. "idle" findet "Idle" UND "elf_wings_idle".
     * Fällt zurück auf exakte Übereinstimmung, sonst null.
     */
    public Clip findClipBySuffix(String suffix) {
        String want = suffix.toLowerCase();
        // Erst exakte Übereinstimmung bevorzugen
        for (String key : clips.keySet()) {
            if (key.equalsIgnoreCase(suffix)) {
                return clips.get(key);
            }
        }
        // Dann Endungs-Übereinstimmung
        for (String key : clips.keySet()) {
            if (key.toLowerCase().endsWith(want)) {
                return clips.get(key);
            }
        }
        return null;
    }

    /** Hilfsmethode: leere, sichere Keyframe-Liste. */
    public static List<Keyframe> emptyList() {
        return new ArrayList<>();
    }
}
