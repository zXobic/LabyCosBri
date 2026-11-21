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
package com.example.labycosmetics.render;

import com.example.labycosmetics.client.CosmeticTextureManager;
import com.example.labycosmetics.cosmetic.BedrockAnimation;
import com.example.labycosmetics.cosmetic.BedrockAnimator;
import com.example.labycosmetics.cosmetic.BuiltCosmeticModel;
import com.example.labycosmetics.cosmetic.CosmeticAnimationController;
import com.example.labycosmetics.cosmetic.CosmeticAnimationManager;
import com.example.labycosmetics.cosmetic.CosmeticCatalog;
import com.example.labycosmetics.cosmetic.CosmeticColorRenderer;
import com.example.labycosmetics.cosmetic.CosmeticData;
import com.example.labycosmetics.cosmetic.CosmeticGeometryManager;
import com.example.labycosmetics.cosmetic.UserCosmeticsManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rendert LabyMod-Cosmetics (Wing, Aura, Back, Hat, Underglow) am Spieler.
 *
 * <h3>Was sich gegenueber 1.21.1 grundlegend geaendert hat</h3>
 * Seit 1.21.9 zeichnet ein Layer nicht mehr selbst. {@code submit} meldet
 * Geometrie nur an ("submission"), gezeichnet wird spaeter gesammelt
 * ("render phase"). Fuer diese Mod heisst das:
 * <ul>
 *   <li>Ein ModelPart pro Cosmetic wird von ALLEN Spielern geteilt. Wuerde man
 *       Animationspose und skipDraw schon in {@code submit} setzen, haette beim
 *       spaeteren Zeichnen jeder Spieler die Pose des zuletzt angemeldeten.
 *       Deshalb passiert beides erst im {@code submitCustomGeometry}-Callback:
 *       der laeuft in der Render-Phase, nacheinander, und setzt Pose + skipDraw
 *       direkt vor dem eigenen Zeichnen.</li>
 *   <li>Der Animations-Zustandsautomat (CosmeticAnimationController) laeuft
 *       weiter in {@code submit}, einmal pro Frame. In den Callback wandern nur
 *       die Ergebnisse (Clip + Clip-Zeit) als feste Werte.</li>
 *   <li>Das Entity gibt es hier nicht mehr, nur den AvatarRenderState. UUID,
 *       onGround usw. kommen ueber {@link CosmeticRenderData} (siehe
 *       AvatarRendererMixin).</li>
 * </ul>
 */
public class CosmeticRenderLayer extends RenderLayer<AvatarRenderState, PlayerModel> {

    /**
     * TEST: feste Cosmetic-IDs statt der getragenen; Texturen dann aus meta.defaultData().
     * Kommt aus der System-Property {@code labycosbri.testIds} (kommagetrennt), die die
     * Dev-Run-Config in build.gradle setzt.
     * <p>
     * Wirkt NUR in der Entwicklungsumgebung ({@code ./gradlew runClient}). Im
     * ausgelieferten Jar wird die Property ignoriert - auch wenn jemand sie per
     * JVM-Argument im Launcher setzt. So kann sich niemand selbst Cosmetics anzeigen.
     */
    private static final List<Integer> TEST_COSMETIC_IDS =
            FabricLoader.getInstance().isDevelopmentEnvironment()
                    ? parseTestIds(System.getProperty("labycosbri.testIds"))
                    : List.of();

    //TEST: Animation aus -> Ruhe-Geometrie. Fuer den Vergleich gegen Blockbench
    private static final boolean TEST_NO_ANIMATION = false;

    /** Kategorien, die dieser Layer rendert. UNDERGLOW belegt an 728 (FEET) und
     *  1307 (BACK). HEADGEAR (z.B. Halo 9) und FACE (Masken) neu nach Katalog-Scan.
     *  Ob Kopf- oder Koerper-Pose, entscheidet NICHT die Kategorie, sondern
     *  {@code attached_to} (siehe submitOne). */
    private static final Set<String> SUPPORTED_CATEGORIES =
            Set.of("WING", "AURA", "BACK", "HAT", "HEADGEAR", "FACE", "UNDERGLOW");

    /** GEMESSEN (Live-Justierung im Dev-Client, 1.21.1): 0. Der Layer-Ursprung liegt
     *  bereits am Kopf-Drehpunkt. Entscheidend ist, dass ERST gedreht und DANN
     *  aufgesetzt wird. In 26.1 neu pruefen. */
    private static final double NECK_PIVOT_Y = 0.0D;

    /** GEMESSEN (1.21.1): 0. Negativ waere nach oben. */
    private static final double HEAD_OFFSET_Y = 0.0D;

    /** Wie weit der Kopf beim Sneaken absinkt. Y invertiert: POSITIV senkt nach unten.
     *  KALIBRIERWERT - Startpunkt 0.21 wie in applyBodyPose. */
    private static final double CROUCH_HEAD_DROP = 0.21D;

    /**
     * Ein Controller pro (Spieler + Cosmetic): der Animationszustand liegt in
     * Instanzfeldern. Der Layer existiert nur pro Skin-Modell (slim/default),
     * nicht pro Spieler - deshalb die Map. Wird beim Server-Join geleert.
     */
    private static final Map<ControllerKey, CosmeticAnimationController> CONTROLLERS = new ConcurrentHashMap<>();

    private record ControllerKey(UUID player, int cosmeticId) {
    }

    /**
     * Verwirft alle Per-Spieler-Controller (beim Server-Join). BEWUSST kein
     * Einzel-Aufraeumen im Render-Pfad - siehe Begruendung in der 1.21.1-Version:
     * ein toter Controller pro gegangenem Spieler, weg beim naechsten Join.
     */
    public static void invalidateAll() {
        CONTROLLERS.clear();
    }

    public CosmeticRenderLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
                       AvatarRenderState state, float yRot, float xRot) {
        CosmeticRenderData data = state.getData(CosmeticRenderData.KEY);
        if (data == null) {
            return;
        }
        CosmeticCatalog.ensureLoading();

        // Welche Cosmetics? Im Test-Modus die festen IDs, sonst alle getragenen in den
        // unterstuetzten Kategorien. Ein Spieler kann mehrere gleichzeitig tragen.
        List<Integer> ids = !TEST_COSMETIC_IDS.isEmpty()
                ? TEST_COSMETIC_IDS
                : UserCosmeticsManager.findAllWornByCategories(data.uuid(), SUPPORTED_CATEGORIES);

        for (int cosmeticId : ids) {
            submitOne(cosmeticId, poseStack, collector, data, yRot, xRot);
        }
    }

    /** Meldet genau ein Cosmetic (Wing, Aura, ...) zum Zeichnen an. */
    private void submitOne(int cosmeticId, PoseStack poseStack, SubmitNodeCollector collector,
                           CosmeticRenderData data, float netHeadYaw, float headPitch) {
        var meta = CosmeticCatalog.get(cosmeticId);
        if (meta == null || meta.textureDirectory() == null) {
            return;
        }

        // ARM/LEG brauchen die Pose der Gliedmassen - noch nicht unterstuetzt.
        boolean head = meta.attachedToHead();
        if (!head && meta.attachedTo() != null && !"BODY".equals(meta.attachedTo())) {
            return;
        }

        // Getragene Daten, sonst die Standard-Daten aus dem Katalog (Test-Modus).
        // Die Bedeutung jedes Eintrags steht in meta.options() - siehe CosmeticData.
        var worn = UserCosmeticsManager.getWorn(data.uuid(), cosmeticId);
        List<String> wornData = (worn != null) ? worn.data() : meta.defaultData();
        CosmeticData parsed = CosmeticData.parse(meta.options(), wornData);
        String textureUuid = parsed.textureUuid();
        if (textureUuid == null) {
            return;
        }

        Identifier texture = CosmeticTextureManager.getTexture(
                meta.textureDirectory(), textureUuid,
                meta.frameAspectWidth(), meta.frameAspectHeight());
        if (texture == null) {
            return;
        }

        BuiltCosmeticModel built = CosmeticGeometryManager.getModel(cosmeticId);
        if (built == null) {
            return;
        }

        // --- Animationszustand fortschreiben (einmal pro Frame, hier in submit) ---
        BedrockAnimation.Clip clip = null;
        float clipTime = 0f;
        if (!TEST_NO_ANIMATION) {
            CosmeticAnimationController controller = CONTROLLERS.computeIfAbsent(
                    new ControllerKey(data.uuid(), cosmeticId), k -> new CosmeticAnimationController());
            BedrockAnimation anim = CosmeticAnimationManager.getAnimation(cosmeticId);
            // Die MOTION_*-Werte stehen auf false: kein Cosmetic benutzt MOTION_FORWARD
            // (nur der Tippfehler MOVING_FORWARD bei 1689), MOTION_BACKWARDS nur bei
            // 1689 [ARMS] - kein Wing. Damit UNGEMESSEN und bewusst offen.
            var world = new BedrockAnimation.WorldState(
                    data.moving(), data.crouching(), data.onGround(), data.inWater(), false, false);
            controller.update(anim, cosmeticId, world, data.timeSeconds());
            clip = controller.clip();
            clipTime = controller.clipTimeSeconds();
        }
        final BedrockAnimation.Clip finalClip = clip;
        final float finalClipTime = clipTime;

        // Klassifizierung ist gecacht (einmal pro Modell + Textur), Farben pro Spieler.
        CosmeticColorRenderer.Classification cls = CosmeticColorRenderer.classify(built, textureUuid);
        CosmeticColorRenderer.Colors colors = CosmeticColorRenderer.Colors.fromColors(parsed.colors());
        int light = data.stableLight();

        // --- Pose ---
        poseStack.pushPose();
        // Kopf-Cosmetics (attached_to = HEAD: Huete, Halo, Masken) folgen Yaw/Pitch,
        // alles andere haengt am Koerper.
        if (head) {
            applyHeadPose(poseStack, data.crouching(), netHeadYaw, headPitch);
        } else {
            applyBodyPose(poseStack, data.crouching());
        }
        // Vom Spieler eingestellter Offset (offset-Option, z.B. Halo-Hoehe: Bereich
        // y=-1..3 laut option_list "offset[y=-1<3]").
        // ANNAHME: Einheit Pixel (1/16 Block), +y = nach oben. Modellraum hat Y nach
        // unten, daher das Minus. Bei falscher Richtung/Groesse hier kalibrieren.
        if (parsed.hasOffset()) {
            poseStack.translate(parsed.offsetX() / 16.0D, -parsed.offsetY() / 16.0D, parsed.offsetZ() / 16.0D);
        }
        // Scale direkt aus dem Katalog, OHNE Grundfaktor (der Animations-Scale steckt
        // in der animation.json und wird vom Parser gelesen).
        float scale = meta.scale();
        poseStack.scale(scale, scale, scale);

        // --- Anmelden. Der Callback laeuft ERST in der Render-Phase. ---
        // Pose + skipDraw werden darin gesetzt, nicht hier (geteilter ModelPart).
        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(texture),
                (pose, consumer) -> {
                    BedrockAnimator.apply(built.root(), finalClip, finalClipTime, built.bonesByName());
                    CosmeticColorRenderer.renderBase(stackOf(pose), consumer, light, built, cls, colors);
                });

        if (cls.hasGlow()) {
            collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(texture),
                    (pose, consumer) -> {
                        BedrockAnimator.apply(built.root(), finalClip, finalClipTime, built.bonesByName());
                        CosmeticColorRenderer.renderGlow(stackOf(pose), consumer, built, cls, colors);
                    });
        }

        poseStack.popPose();
    }

    /**
     * Baut aus der im Callback uebergebenen Pose einen PoseStack, weil
     * {@code ModelPart#render} einen PoseStack erwartet. Die Matrix wird direkt
     * kopiert (JOML {@code Matrix4f#set}), statt {@code mulPose} zu nutzen, das in
     * 26.3 umgebaut wurde. Die Normalen-Matrix bleibt Identitaet - egal, weil
     * FlatNormalConsumer die Normalen ohnehin ueberschreibt.
     */
    private static PoseStack stackOf(PoseStack.Pose pose) {
        PoseStack stack = new PoseStack();
        stack.last().pose().set(pose.pose());
        return stack;
    }

    /**
     * Pose fuer koerpergebundene Cosmetics (Wing, Aura, Back, Underglow).
     * <p>
     * Die Sneak-Neigung gilt auch fuer bodengebundene Cosmetics: Creator
     * rechnen damit. Belegt an 928 Ocean und 728 Flower Underglow - deren
     * Sneak-Clips setzen auf den layer_-Wurzeln konstant rotation [-27.5,0,0]
     * und position [0,4,5.1], also genau eine Gegenbewegung zur Koerperneigung.
     * <p>
     * 26.1: Werte stammen aus 1.21.1. Mojang hat zwischendurch die Sneak-Haltung
     * der Spielermodelle nicht geaendert (Stand Primer), trotzdem im Spiel pruefen.
     */
    private static void applyBodyPose(PoseStack poseStack, boolean crouching) {
        if (crouching) {
            poseStack.translate(0.0D, 0.21D, 0.0D);
            poseStack.rotateDegrees(Axis.XP, 28.65F);
        }
    }

    /**
     * Pose fuer kopfgebundene Cosmetics (HAT). REIHENFOLGE ist entscheidend: erst
     * an den Kopf-Drehpunkt fahren, DORT drehen, und erst danach den Hut relativ
     * dazu aufsetzen. Andersherum beschreibt der Hut beim Umschauen einen Bogen.
     */
    private static void applyHeadPose(PoseStack poseStack, boolean crouching,
                                      float netHeadYaw, float headPitch) {
        // 1) Zum Kopf-Drehpunkt. Beim Ducken senkt sich der Kopf, also mitnehmen -
        //    aber NICHT die 28.65-Grad-Neigung: der Kopf bleibt beim Sneaken waagerecht.
        if (crouching) {
            poseStack.translate(0.0D, CROUCH_HEAD_DROP, 0.0D);
        }
        poseStack.translate(0.0D, NECK_PIVOT_Y, 0.0D);

        // 2) Am Drehpunkt drehen, wie der Vanilla-Kopf: erst Yaw, dann Pitch.
        // 26.3: PoseStack#mulPose(Quaternion) -> rotate/rotateDegrees(Axis, Winkel)
        poseStack.rotateDegrees(Axis.YP, netHeadYaw);
        poseStack.rotateDegrees(Axis.XP, headPitch);

        // 3) Hut relativ zum Drehpunkt aufsetzen (dreht jetzt sauber mit).
        poseStack.translate(0.0D, HEAD_OFFSET_Y, 0.0D);
    }

    private static List<Integer> parseTestIds(String prop) {
        if (prop == null || prop.isBlank()) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (String part : prop.split(",")) {
            try {
                ids.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // ungueltige Eintraege ueberspringen
            }
        }
        if (!ids.isEmpty()) {
            com.example.labycosmetics.LabyCosmeticsMod.LOGGER.warn(
                    "[LabyCosmetics] TESTMODUS aktiv: alle Spieler tragen {}", ids);
        }
        return List.copyOf(ids);
    }
}
