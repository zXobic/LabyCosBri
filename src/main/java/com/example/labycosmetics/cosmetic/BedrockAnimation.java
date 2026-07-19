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

import java.util.ArrayList;
import java.util.EnumSet;
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

    /**
     * Spieler-Zustaende, ueber die LabyMod Animationen steuert (Feld
     * "anim_time_update", Flag -t). Belegt in den animation.json von
     * Wing 35, 54 und 963.
     */
    public enum AnimState {
        IDLE, MOVING, START_MOVING, STOP_MOVING,
        SNEAK_IDLE, SNEAK_MOVING, START_SNEAKING, STOP_SNEAKING
    }

    /** Was am Ende der Animation passiert (JSON-Feld "loop"). */
    public enum LoopMode {
        /** "loop": true - von vorne beginnen. */
        LOOP,
        /** "loop": "hold_on_last_frame" - auf dem letzten Frame stehenbleiben. */
        HOLD_LAST,
        /** "loop": false - einmal abspielen. */
        ONCE
    }

    /**
     * Bedingungen aus Flag -c. Alle 9 Werte laut
     * docs.labymod.net/pages/cosmetics/arguments/ - "List of requirements
     * that has to match that the animation triggers".
     * <p>
     * Kein -c mehr = leere Menge (frueher: ANY).
     */
    public enum Condition {
        MOTION_FORWARD, MOTION_BACKWARDS, NO_MOTION,
        SNEAKING, NOT_SNEAKING,
        IN_WATER, NOT_IN_WATER,
        ON_GROUND, IN_AIR
    }

    /**
     * Weltzustand des Spielers, soweit -c ihn braucht. Gebuendelt, damit die
     * Parameterliste des Controllers nicht mit jeder Bedingung waechst.
     *
     * @param motionForward   UNGEMESSEN: kein Cosmetic benutzt MOTION_FORWARD,
     *                        nur der Tippfehler MOVING_FORWARD bei 1689.
     * @param motionBackwards UNGEMESSEN: nur Toy Minime 1689 [ARMS], kein Wing.
     */
    public record WorldState(boolean moving, boolean sneaking, boolean onGround,
                             boolean inWater, boolean motionForward,
                             boolean motionBackwards) {
    }

    /** Eine einzelne Animation. */
    public static final class Clip {
        public float lengthSeconds = 0f;
        public LoopMode loopMode = LoopMode.LOOP;

        /** Zustaende aus -t. Leer = Clip ist nicht zustandsgesteuert. */
        public final EnumSet<AnimState> states = EnumSet.noneOf(AnimState.class);

        /**
         * Bedingungen aus -c. Leer = keine Bedingung. Mehrere muessen ALLE
         * zutreffen (Doku: "List of requirements that has to match").
         * Belegt: Wing 24 hat "-c ON_GROUND,NOT_SNEAKING".
         */
        public final EnumSet<Condition> conditions = EnumSet.noneOf(Condition.class);

        /**
         * Anzahl Lose aus -p: gewichteter ZUFALL, kein Vorrang.
         * <p>
         * Dokumentiert: docs.labymod.net/pages/cosmetics/arguments/ -
         * "Play a random entry from a pool of animations with the same
         * trigger. A number must be specified to indicate how often the
         * animation will be thrown in a pool."
         * <p>
         * Wing 963: idle mit 7 Losen, idle2 mit 1 Los. Default 1, wenn -p
         * fehlt.
         */
        public int probability = 1;

        /**
         * -q true: der Wechsel wartet auf das Ende des laufenden Clips.
         * <p>
         * Dokumentiert: docs.labymod.net/pages/cosmetics/arguments/ -
         * "The animation will be queued and played after the current
         * animation has finished."
         * <p>
         * Katalog: 931x true, 2x false, 1x "True" (grosses T, Tree Spirit
         * 1680 - dieselbe Datei hat auch andere Schlampigkeiten, daraus
         * wird kein Verhalten abgeleitet).
         */
        public boolean queued = false;

        /**
         * -f true: bricht den laufenden Clip ab und spielt sofort.
         * <p>
         * Dokumentiert: docs.labymod.net/pages/cosmetics/arguments/ -
         * "Force the animation to play immediately and interrupt the
         * current animation."
         * <p>
         * 163x im Katalog, u.a. Wing 24 / STARTMOVING. Weil abgebrochen
         * wird, startet der neue Clip NICHT an der Zyklusgrenze - die
         * nahtlosen Nahtstellen (alle 5 Wings nachgerechnet) gelten dort
         * also nicht. Ein sichtbarer Sprung ist die BELEGTE Folge von -f,
         * kein Fehler und kein Grund fuer eine Ueberblendung.
         * <p>
         * Weder -q noch -f: der Trigger wird verworfen, wenn schon etwas
         * laeuft ("the animation will not be played").
         */
        public boolean force = false;

        /**
         * Faktor aus -s. Beschleunigt den Clip, SOLANGE eine andere
         * Animation in der Queue wartet - sonst gar nicht.
         * <p>
         * Dokumentiert: docs.labymod.net/pages/cosmetics/arguments/ -
         * "Speed up the animation when another animation is waiting in the
         * queue. If you set the speed to -s 2 the animation will play twice
         * as fast when another animation is added to the queue."
         * <p>
         * Deshalb steht -s nur auf IDLE-Clips (4-6s) und nie auf einem
         * MOVING/START/STOP-Clip (0,28-1,48s): nur bei langen Clips ist die
         * Queue-Wartezeit spuerbar.
         */
        public Float speed = null;

        /** Faktor aus -s, oder 1 wenn kein -s gesetzt ist. */
        public float speedFactor() {
            return (speed != null && speed > 0f) ? speed : 1f;
        }

        /** Passt dieser Clip zu Zustand + Weltzustand? Alle -c muessen zutreffen. */
        public boolean matches(AnimState state, WorldState w) {
            if (!states.contains(state)) {
                return false;
            }
            for (Condition c : conditions) {
                if (!test(c, w)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * IN_AIR ist als !onGround gedeutet, NOT_IN_WATER als !inWater usw.
         * Die Doku sagt dazu nichts; Wing 24 stuetzt es, weil "IDLE ONGROUND"
         * und "IDLE INAIR" sich dort gegenseitig ausschliessen.
         */
        private static boolean test(Condition c, WorldState w) {
            return switch (c) {
                case SNEAKING -> w.sneaking();
                case NOT_SNEAKING -> !w.sneaking();
                case ON_GROUND -> w.onGround();
                case IN_AIR -> !w.onGround();
                case IN_WATER -> w.inWater();
                case NOT_IN_WATER -> !w.inWater();
                case NO_MOTION -> !w.moving();
                case MOTION_FORWARD -> w.motionForward();
                case MOTION_BACKWARDS -> w.motionBackwards();
            };
        }

        /** Bone-Name -> Liste von Rotations-Keyframes (Grad). */
        public final Map<String, List<Keyframe>> rotations = new HashMap<>();

        /**
         * Bone-Name -> Liste von Positions-Keyframes, in BEDROCK-Koordinaten
         * (Y zeigt nach oben). Das Spiegeln macht der Animator, wie der
         * BedrockModelBuilder es auch tut.
         */
        public final Map<String, List<Keyframe>> positions = new HashMap<>();
    }

    /**
     * Ein Keyframe: Zeitpunkt + Zielwert (Rotation in Grad, Position in
     * Bedrock-Einheiten).
     */
    public static final class Keyframe {
        public float time;
        public float[] value = {0, 0, 0};

        /**
         * true, wenn der Keyframe "lerp_mode": "catmullrom" traegt.
         * Bedrock-Default ist LINEAR - ein nacktes [x,y,z]-Array ist immer
         * linear.
         */
        public boolean catmullrom;

        public Keyframe(float time, float[] value, boolean catmullrom) {
            this.time = time;
            this.value = value;
            this.catmullrom = catmullrom;
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
