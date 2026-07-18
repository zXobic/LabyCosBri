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
 * -q true = Wechsel wartet auf das Clip-Ende; -f true = laufender Clip wird
 * abgebrochen, der neue spielt sofort; weder -q noch -f = Trigger wird verworfen,
 * wenn schon etwas laeuft; -s N = Clip laeuft N-fach schneller, solange etwas in
 * der Queue wartet; -c = Liste von Bedingungen, die ALLE zutreffen muessen.
 *
 * <p>-q und -f haengen am ZIEL-Clip, nicht am laufenden. Welcher Clip das Ziel
 * wird, weiss erst die -p-Ziehung - deshalb haelt die Queue einen Clip und keinen
 * Zustand. Doku zu -s stuetzt das: "when another ANIMATION is waiting in the queue".
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
     * Angemeldeter, noch nicht ausgefuehrter Clip (-q true). Belegt: bei allen
     * 5 geprueften Wings (35, 54, 404, 963, 1460) trifft der Endwert jedes Bones
     * exakt den Startwert des Folgeclips - und NUR am Zyklusende.
     */
    private Clip pendingClip = null;

    /** Zustand, in dem {@link #pendingClip} gezogen wurde. */
    private AnimState pendingState = null;

    /** Zyklusgrenze in Clip-Zeit, an der {@link #pendingClip} ausgefuehrt wird. */
    private float pendingBoundary = 0f;

    /** Ergebnis einer Ziehung: der Clip und der Zustand, in dem er gilt. */
    private record Pick(AnimState state, Clip clip) {
    }

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
            pendingClip = null;
            pendingState = null;
            lastNow = now;
            lastCycle = 0;
            enter(steady(w), anim, w);
            return;
        }

        // -s greift nur bei gefuellter Queue (Doku: "Speed up the animation when
        // another animation is waiting in the queue").
        float dt = Math.max(0f, now - lastNow);
        lastNow = now;
        float factor = (pendingClip != null && clip != null) ? clip.speedFactor() : 1f;
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
        } else if (pendingClip == null && isTransient(state) && transientDone()) {
            next = steady(w);
        }

        prevMoving = w.moving();
        prevSneaking = w.sneaking();

        // Hat dieser Frame schon einen Clip gestartet? Dann ist die Zyklusgrenze
        // des ABGELOESTEN Clips verbraucht. Ohne die Sperre feuert der Neu-Trigger
        // unten ein zweites Mal: cycleEnded gehoert zum alten Clip, state ist da
        // aber schon der neue - beides zugleich wahr. Gemessen (Wing 24): zwei
        // Ziehungen im selben Frame. Am Boden harmlos (Lose gesamt=1), in der Luft
        // nicht (IDLE ONGROUND vs IDLE INAIR = 2 Lose -> die zweite Ziehung kann
        // die erste ueberstimmen und den Clip neu starten).
        boolean entered = false;

        if (next != null) {
            // Ziehung JETZT statt erst an der Zyklusgrenze: -q/-f haengen am Ziel,
            // und welcher Clip das Ziel wird, weiss erst die -p-Ziehung.
            //
            // FOLGE, nicht belegt: -c wird damit beim Anmelden geprueft statt an der
            // Grenze. Bei Wing 24 ist der Effekt null (-f fuehrt sofort aus), fuer
            // -q-Clips ist es eine echte Verhaltensaenderung ohne Pruefstein.
            Pick p = pick(anim, next, w);
            boolean wasRunning = running();

            if (p.clip() == null || !wasRunning || p.clip().force) {
                // -f schlaegt -q. ENTSCHEIDUNG, keine Doku-Aussage: beides zugleich
                // gibt es nur 2x (Droid 1524 [PETS]) - kein Wing, kein Pruefstein.
                if (DEBUG) {
                    LOGGER.info("[LabyCos] {} -> SOFORT ({})", p.state(),
                            p.clip() == null ? "kein Clip"
                                    : !wasRunning
                                        ? (clip == null ? "nichts laeuft" : "Uebergang ist fertig")
                                        : "-f true");
                }
                enterClip(p.state(), p.clip(), anim);
                entered = true;
            } else if (p.clip().queued) {
                if (pendingClip == null) {
                    // Grenze nur beim ERSTEN Anmelden bestimmen.
                    pendingBoundary = boundaryFrom(clipTime);
                }
                if (DEBUG) {
                    LOGGER.info("[LabyCos] QUEUE {} -> '{}' bei t={} -> wartet bis t={} (x{})",
                            p.state(), nameOf(anim, p.clip()), fmt(clipTime),
                            fmt(pendingBoundary), clip.speedFactor());
                }
                pendingClip = p.clip();
                pendingState = p.state();
            } else if (DEBUG) {
                // Doku: weder -q noch -f -> die Animation wird nicht gespielt.
                // 570 Clips im Katalog, kein bekannter Wing.
                LOGGER.info("[LabyCos] {} -> VERWORFEN (weder -q noch -f, '{}' laeuft)",
                        p.state(), nameOf(anim, clip));
            }
        }

        if (pendingClip != null && clipTime >= pendingBoundary) {
            AnimState s = pendingState;
            Clip c = pendingClip;
            pendingClip = null;
            pendingState = null;
            // Die Lage kann sich waehrend des Wartens gedreht haben (losgelaufen und
            // vor der Zyklusgrenze wieder stehengeblieben). Eigene Erfindung, steht
            // auf der Offene-Punkte-Liste - hier bewusst unveraendert gelassen.
            if (isTransient(s) && !stillWanted(s, w)) {
                Pick p = pick(anim, steady(w), w);
                s = p.state();
                c = p.clip();
            }
            enterClip(s, c, anim);
        } else if (cycleEnded && !entered && !isTransient(state)) {
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

    /**
     * Laeuft gerade noch ein Clip? Nur dann greift die -q/-f-Unterscheidung
     * ueberhaupt: -q wartet auf "the current animation", -f unterbricht sie,
     * ohne beides wird verworfen - alle drei setzen voraus, dass etwas laeuft.
     * LOOP-Clips laufen immer, HOLD_LAST/ONCE nur bis zu ihrer Laenge.
     */
    private boolean running() {
        if (clip == null) {
            return false;
        }
        // Ein Uebergang, der durchgelaufen ist, laeuft nicht mehr - auch wenn sein
        // Clip LOOP ist und technisch weiterdreht. Doku zu -q: gewartet wird, bis
        // "the current animation HAS FINISHED" - ist sie hier.
        //
        // Ohne das wartet der Wechsel auf boundaryFrom(), und dessen Math.ceil
        // rundet auf die UEBERNAECHSTE Grenze auf, weil transientDone() mit ">="
        // prueft und clipTime beim Trigger immer knapp HINTER der Grenze steht.
        // Gemessen (Wing 24): STOP_MOVING -> IDLE lief bei t=2.50 sofort, bei
        // t=2.51 erst 2,49s spaeter - dieselbe Lage, zwei Ergebnisse, entschieden
        // von der Fliesskomma-Rundung. STARTMOVING lief dadurch immer zwei Zyklen.
        if (isTransient(state) && transientDone()) {
            return false;
        }
        if (clip.loopMode == LoopMode.LOOP) {
            return true;
        }
        return clip.lengthSeconds > 0f && clipTime < clip.lengthSeconds;
    }

    /**
     * Zieht den Clip fuer einen Zustand, ohne ihn zu starten.
     * <p>
     * Kein endender Clip fuer den Uebergang (54 hat nichts fuer START_SNEAKING,
     * 35 listet STOP_MOVING nur auf seinem loopenden idle) -> in den Ruhezustand
     * durchfallen. Deshalb kann der zurueckgegebene Zustand vom angefragten
     * abweichen.
     */
    private Pick pick(BedrockAnimation anim, AnimState s, WorldState w) {
        Clip c = select(anim, s, w, isTransient(s));
        if (isTransient(s) && c == null) {
            s = steady(w);
            c = select(anim, s, w, false);
        }
        return new Pick(s, c);
    }

    private void enter(AnimState s, BedrockAnimation anim, WorldState w) {
        Pick p = pick(anim, s, w);
        enterClip(p.state(), p.clip(), anim);
    }

    /** Startet einen gezogenen Clip. Einziger Ort, an dem {@link #clip} wechselt. */
    private void enterClip(AnimState s, Clip c, BedrockAnimation anim) {
        if (DEBUG) {
            LOGGER.info("[LabyCos] {} -> Clip '{}' (len {}s, {}){}",
                    s, nameOf(anim, c),
                    c != null ? c.lengthSeconds : 0f,
                    c != null ? c.loopMode : "-",
                    c == clip ? " [laeuft weiter]" : " [Neustart]");
        }

        state = s;

        // Ein angemeldeter Wechsel gehoerte zum abgeloesten Clip und faellt mit ihm.
        // ENTSCHEIDUNG, keine Doku-Aussage: was -f mit gefuellter Queue macht, steht
        // nirgends. Ohne das Loeschen wuerde pendingBoundary aus der Uhr des alten
        // Clips gegen die des neuen geprueft.
        pendingClip = null;
        pendingState = null;

        // Uhr NUR bei echtem Clip-Wechsel zuruecksetzen - sonst springt Wing 35 bei
        // jedem Wechsel idle->idle an den Anfang. Ob ein -f-Clip sich selbst neu
        // startet, ist damit UNGEMESSEN - kein Wing-Fall.
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
     * @param isTransition true fuer Uebergaenge. Steuert NUR, was bei leerer
     *                     Ziehung passiert: null (Aufrufer faellt auf steady
     *                     durch) statt fallback().
     *                     <p>
     *                     Frueher filterte das zusaetzlich LOOP-Clips heraus
     *                     ("ein LOOP-Clip kann keinen Uebergang bedienen").
     *                     WIDERLEGT durch Scan ueber 594 Cosmetics: von 715
     *                     Clips mit START_/STOP_-Trigger sind 567 LOOP, 133
     *                     ONCE, nur 15 hold_on_last_frame. Die Regel hat 79%
     *                     aller Uebergaenge verworfen, bei Wing 24 alle fuenf
     *                     Clips. Sie stammte vermutlich von Wing 54, dessen
     *                     Uebergaenge zufaellig hold_on_last_frame sind.
     *                     <p>
     *                     Ein LOOP-Uebergang endet stattdessen dadurch, dass
     *                     der Dauerzustand am Zyklusende neu triggert und ihn
     *                     mit -q abloest (Wing 24: nach 2,65s).
     */
    private Clip select(BedrockAnimation anim, AnimState s, WorldState w,
                        boolean isTransition) {
        // Nur Dauerzustaende erzwingen - einen Uebergang wuerde TEST_FORCE_CLIP
        // gegen den Zustandsautomaten festnageln.
        if (TEST_FORCE_CLIP != null && !isTransition) {
            Clip forced = anim.clips.get(TEST_FORCE_CLIP);
            if (forced != null) {
                return forced;
            }
        }

        int total = 0;
        for (Clip c : anim.clips.values()) {
            if (c.matches(s, w)) {
                total += Math.max(1, c.probability);
            }
        }
        if (total <= 0) {
            // Frueher stumm. Das Schweigen hat den -f-Test blind gemacht: kein
            // Clip zulaessig sah im Log genauso aus wie "kein Trigger".
            if (DEBUG) {
                LOGGER.info("[LabyCos] -p Ziehung fuer {}: KEIN zulaessiger Clip (-t/-c){}",
                        s, isTransition ? " -> faellt auf steady durch" : " -> fallback");
            }
            return isTransition ? null : fallback(anim);
        }
        int roll = random.nextInt(total);
        if (DEBUG) {
            LOGGER.info("[LabyCos] -p Ziehung fuer {}: Lose gesamt={}, gezogen={}", s, total, roll);
        }
        // Sortiert durchlaufen, damit die Ziehung nicht von der HashMap-Reihenfolge abhaengt.
        for (String name : new TreeSet<>(anim.clips.keySet())) {
            Clip c = anim.clips.get(name);
            if (!c.matches(s, w)) {
                continue;
            }
            roll -= Math.max(1, c.probability);
            if (roll < 0) {
                return c;
            }
        }
        return isTransition ? null : fallback(anim); // unerreichbar
    }

    /** Sicherheitsnetz fuer Wings ganz ohne -t. Bei 35/54/963 greift das nie. */
    private static Clip fallback(BedrockAnimation anim) {
        Clip idle = anim.findClipBySuffix("idle");
        return (idle != null && idle.states.isEmpty()) ? idle : null;
    }
}