package com.example.labycosmetics.cosmetic;

import com.example.labycosmetics.cosmetic.BedrockAnimation.AnimState;
import com.example.labycosmetics.cosmetic.BedrockAnimation.Clip;
import com.example.labycosmetics.cosmetic.BedrockAnimation.LoopMode;
import com.example.labycosmetics.cosmetic.BedrockAnimation.WorldState;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Random;
import java.util.TreeSet;

/**
 * Zustandsautomat fuer die LabyMod-Animationssteuerung: bestimmt aus dem
 * {@link WorldState} den {@link AnimState} und waehlt den passenden Clip.
 *
 * <p>Regeln belegt durch docs.labymod.net/pages/cosmetics/arguments/ und .../trigger/:
 * -q true = Wechsel wartet auf das Clip-Ende; -s N = Clip laeuft N-fach schneller,
 * solange etwas in der Queue wartet; -c = Liste von Bedingungen, die ALLE zutreffen
 * muessen.
 *
 * <p>Haelt den Zustand EINES Spielers mit EINEM Wing.
 */
public final class WingAnimationController {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** TEST: jeder Zustandswechsel und jede -p Ziehung ins Log. */
    private static final boolean DEBUG = true;

    /** TEST: erzwingt einen Clip fuer alle Dauerzustaende. null = aus. */
    private static final String TEST_FORCE_CLIP = null;

    /** Ab diesem limbSwingAmount gilt der Spieler als "in Bewegung". */
    private static final float MOVE_THRESHOLD = 0.01f;

    private final Random random = new Random();

    private int wingId = -1;
    private AnimState state = null;
    private Clip clip = null;

    /** Zeit im Clip. Wird fortgeschrieben statt aus der Weltzeit gerechnet, weil -s das Tempo mittendrin aendert. */
    private float clipTime = 0f;
    private float lastNow = 0f;

    /**
     * Anzahl voller Zyklen des laufenden LOOP-Clips. Dient nur dazu, die
     * Zyklusgrenze zu erkennen.
     */
    private int lastCycle = 0;

    private boolean prevMoving;
    private boolean prevSneaking;

    /**
     * Angemeldeter, noch nicht ausgefuehrter Wechsel (-q true). Belegt: bei allen
     * 5 geprueften Wings (35, 54, 404, 963, 1460) trifft der Endwert jedes Bones
     * exakt den Startwert des Folgeclips - und NUR am Zyklusende.
     */
    private AnimState pending = null;

    /** Zyklusgrenze in Clip-Zeit, an der {@link #pending} ausgefuehrt wird. */
    private float pendingBoundary = 0f;

    /** Der aktuell zu spielende Clip, oder null wenn keiner passt. */
    public Clip clip() {
        return clip;
    }

    /** Zeit im aktuellen Clip. Das Umbrechen macht der Animator. */
    public float clipTimeSeconds() {
        return clipTime;
    }

    public AnimState state() {
        return state;
    }

    public static boolean isMoving(float limbSwingAmount) {
        return limbSwingAmount > MOVE_THRESHOLD;
    }

    /**
     * Einmal pro Frame aufrufen.
     *
     * @param now Zeit in Sekunden, monoton (tickCount + partialTicks) / 20
     */
    public void update(BedrockAnimation anim, int wingId, WorldState w, float now) {
        if (anim == null) {
            clip = null;
            return;
        }

        if (wingId != this.wingId || state == null) {
            this.wingId = wingId;
            prevMoving = w.moving();
            prevSneaking = w.sneaking();
            clip = null;
            pending = null;
            lastNow = now;
            lastCycle = 0;
            enter(steady(w), anim, w);
            return;
        }

        // -s greift nur bei gefuellter Queue (Doku: "Speed up the animation when
        // another animation is waiting in the queue").
        float dt = Math.max(0f, now - lastNow);
        lastNow = now;
        float factor = (pending != null && clip != null) ? clip.speedFactor() : 1f;
        clipTime += dt * factor;

        // Nur LOOP-Clips haben Zyklen. clipTime laeuft weiter und wird nicht
        // umgebrochen (das macht der Animator), also zaehlt der ganzzahlige
        // Anteil die vollen Durchlaeufe.
        int cycle = (clip != null && clip.lengthSeconds > 0f && clip.loopMode == LoopMode.LOOP)
                ? (int) (clipTime / clip.lengthSeconds)
                : 0;
        boolean cycleEnded = cycle != lastCycle;
        lastCycle = cycle;

        AnimState next = null;
        if (w.moving() != prevMoving) {
            next = w.moving() ? AnimState.START_MOVING : AnimState.STOP_MOVING;
        } else if (w.sneaking() != prevSneaking) {
            // Aendern sich beide im selben Frame, gewinnt die Bewegung. Willkuerlich.
            next = w.sneaking() ? AnimState.START_SNEAKING : AnimState.STOP_SNEAKING;
        } else if (pending == null && isTransient(state) && transientDone()) {
            next = steady(w);
        }

        prevMoving = w.moving();
        prevSneaking = w.sneaking();

        if (next != null) {
            if (pending == null) {
                // Grenze nur beim ERSTEN Anmelden bestimmen.
                pendingBoundary = boundaryFrom(clipTime);
                if (DEBUG) {
                    LOGGER.info("[LabyCos] QUEUE {} angemeldet bei t={} -> wartet bis t={} (x{})",
                            next, fmt(clipTime), fmt(pendingBoundary),
                            clip != null ? clip.speedFactor() : 1f);
                }
            }
            pending = next;
        }

        if (pending != null && clipTime >= pendingBoundary) {
            AnimState s = pending;
            pending = null;
            // Die Lage kann sich waehrend des Wartens gedreht haben (losgelaufen und
            // vor der Zyklusgrenze wieder stehengeblieben).
            if (isTransient(s) && !stillWanted(s, w)) {
                s = steady(w);
            }
            enter(s, anim, w);
        } else if (cycleEnded && !isTransient(state)) {
            // Doku (Trigger-Seite): "IDLE: Triggered IN A LOOP when standing
            // still" - Dauerzustaende feuern am Zyklusende erneut. Damit wird
            // -p neu gezogen und -c neu geprueft.
            //
            // ABGELEITET, nicht woertlich belegt: dass "in a loop" genau die
            // Zyklusgrenze meint, sagt die Doku nicht. Dafuer spricht, dass -p
            // auf Idle-Clips sonst sinnlos waere (963: 7:1 koennte beim
            // Stehenbleiben nie greifen) und dass -q sonst nichts zu warten
            // haette. Referenz waere nur der echte LabyMod-Client.
            enter(steady(w), anim, w);
        }
    }

    /** Hat ein Uebergangsclip (HOLD_LAST/ONCE) sein Ende erreicht? */
    private boolean transientDone() {
        return clip == null || clip.lengthSeconds <= 0f || clipTime >= clip.lengthSeconds;
    }

    /** Naechste Zyklusgrenze des laufenden Clips, in Clip-Zeit. */
    private float boundaryFrom(float ct) {
        if (clip == null || clip.lengthSeconds <= 0f) {
            return ct;
        }
        if (clip.loopMode == LoopMode.LOOP) {
            int k = Math.max(1, (int) Math.ceil(ct / clip.lengthSeconds));
            return k * clip.lengthSeconds;
        }
        // HOLD_LAST / ONCE laufen genau einmal; danach sofort.
        return Math.max(ct, clip.lengthSeconds);
    }

    /** Passt der angemeldete Uebergang noch zur aktuellen Lage? Eigene Erfindung, nicht dokumentiert. */
    private static boolean stillWanted(AnimState s, WorldState w) {
        return switch (s) {
            case START_MOVING -> w.moving();
            case STOP_MOVING -> !w.moving();
            case START_SNEAKING -> w.sneaking();
            case STOP_SNEAKING -> !w.sneaking();
            default -> true;
        };
    }

    private void enter(AnimState s, BedrockAnimation anim, WorldState w) {
        Clip c = select(anim, s, w, isTransient(s));

        // Kein endender Clip fuer den Uebergang (54 hat nichts fuer START_SNEAKING,
        // 35 listet STOP_MOVING nur auf seinem loopenden idle) -> in den Ruhezustand
        // durchfallen.
        if (isTransient(s) && c == null) {
            s = steady(w);
            c = select(anim, s, w, false);
        }

        if (DEBUG) {
            LOGGER.info("[LabyCos] {} -> Clip '{}' (len {}s, {}){}",
                    s, nameOf(anim, c),
                    c != null ? c.lengthSeconds : 0f,
                    c != null ? c.loopMode : "-",
                    c == clip ? " [laeuft weiter]" : " [Neustart]");
        }

        state = s;

        // Uhr NUR bei echtem Clip-Wechsel zuruecksetzen - sonst springt Wing 35 bei
        // jedem Wechsel idle->idle an den Anfang.
        if (c != clip) {
            clip = c;
            clipTime = 0f;
            lastCycle = 0;
        }
    }

    /** Nur fuer die Debug-Ausgabe: Clip-Objekt zurueck auf seinen Namen. */
    private static String nameOf(BedrockAnimation anim, Clip c) {
        if (c == null) {
            return "(keiner)";
        }
        for (String n : anim.clips.keySet()) {
            if (anim.clips.get(n) == c) {
                return n;
            }
        }
        return "?";
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** Der Dauerzustand, der sich allein aus Bewegung + Sneak ergibt. */
    private static AnimState steady(WorldState w) {
        if (w.sneaking()) {
            return w.moving() ? AnimState.SNEAK_MOVING : AnimState.SNEAK_IDLE;
        }
        return w.moving() ? AnimState.MOVING : AnimState.IDLE;
    }

    private static boolean isTransient(AnimState s) {
        return s == AnimState.START_MOVING || s == AnimState.STOP_MOVING
                || s == AnimState.START_SNEAKING || s == AnimState.STOP_SNEAKING;
    }

    /**
     * Zieht einen zum Zustand passenden Clip - gewichtet mit -p (963: idle 7 Lose,
     * idle2 1 Los). Gezogen wird einmal pro Trigger, nicht pro Frame.
     *
     * @param needsEnding true fuer Uebergaenge: nur Clips, die auch enden. Ein
     *                    LOOP-Clip kann keinen Uebergang bedienen.
     */
    private Clip select(BedrockAnimation anim, AnimState s, WorldState w,
                        boolean needsEnding) {
        // Nur Dauerzustaende erzwingen - ein Uebergang braucht einen Clip, der endet.
        if (TEST_FORCE_CLIP != null && !needsEnding) {
            Clip forced = anim.clips.get(TEST_FORCE_CLIP);
            if (forced != null) {
                return forced;
            }
        }

        int total = 0;
        for (Clip c : anim.clips.values()) {
            if (eligible(c, s, w, needsEnding)) {
                total += Math.max(1, c.probability);
            }
        }
        if (total <= 0) {
            return needsEnding ? null : fallback(anim);
        }
        int roll = random.nextInt(total);
        if (DEBUG) {
            LOGGER.info("[LabyCos] -p Ziehung fuer {}: Lose gesamt={}, gezogen={}", s, total, roll);
        }
        // Sortiert durchlaufen, damit die Ziehung nicht von der HashMap-Reihenfolge abhaengt.
        for (String name : new TreeSet<>(anim.clips.keySet())) {
            Clip c = anim.clips.get(name);
            if (!eligible(c, s, w, needsEnding)) {
                continue;
            }
            roll -= Math.max(1, c.probability);
            if (roll < 0) {
                return c;
            }
        }
        return needsEnding ? null : fallback(anim); // unerreichbar
    }

    private static boolean eligible(Clip c, AnimState s, WorldState w, boolean needsEnding) {
        if (!c.matches(s, w)) {
            return false;
        }
        return !needsEnding || c.loopMode != LoopMode.LOOP;
    }

    /** Sicherheitsnetz fuer Wings ganz ohne -t. Bei 35/54/963 greift das nie. */
    private static Clip fallback(BedrockAnimation anim) {
        Clip idle = anim.findClipBySuffix("idle");
        return (idle != null && idle.states.isEmpty()) ? idle : null;
    }
}