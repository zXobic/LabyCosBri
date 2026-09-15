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
import com.example.labycosmetics.cosmetic.CosmeticAnimationManager;
import com.example.labycosmetics.cosmetic.CosmeticCatalog;
import com.example.labycosmetics.cosmetic.CosmeticColorRenderer;
import com.example.labycosmetics.cosmetic.CosmeticGeometryManager;
import com.example.labycosmetics.cosmetic.UserCosmeticsManager;
import com.example.labycosmetics.cosmetic.CosmeticAnimationController;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public class CosmeticRenderLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    /**
     * TEST: feste Cosmetic-IDs statt der getragenen; Texturen dann aus meta.defaultData().
     * LEER = aus (Normalbetrieb). Fuer den Dev-Client, der keine getragenen Cosmetics
     * kennt (Offline-UUID -> userdata 404). Mehrere IDs = Mehrfach-Rendering testen,
     * z.B. List.of(1460, 328, 1) fuer Angel Wings V2 + Leaves Aura + Tail.
     * ACHTUNG vor Release wieder leeren - sonst tragen ALLE Spieler diese Cosmetics.
     */
    private static final List<Integer> TEST_COSMETIC_IDS = List.of(963, 1309, 1, 328);

    
    //TEST: Animation aus -> Ruhe-Geometrie. Fuer den Vergleich gegen Blockbench
    private static final boolean TEST_NO_ANIMATION = false;

    /** Kategorien, die dieser Layer rendert. WING/AURA/BACK haengen am Koerper,
     *  HAT am Kopf (eigene Pose, siehe applyHeadPose). UNDERGLOW fehlt noch -
     *  der Layers-Filter ist da, aber ungetestet fuer die Kategorie. */
    private static final java.util.Set<String> SUPPORTED_CATEGORIES = java.util.Set.of("WING", "AURA", "BACK", "HAT");

    /** GEMESSEN (Live-Justierung im Dev-Client): 0. Der Layer-Ursprung liegt bereits
     *  am Kopf-Drehpunkt - es braucht keinen Versatz vor der Drehung. Entscheidend
     *  ist nicht dieser Wert, sondern dass ERST gedreht und DANN aufgesetzt wird. */
    private static final double NECK_PIVOT_Y = 0.0D;

    /** GEMESSEN: 0. Mit korrektem HEAD_SCALE sitzt der Hut ohne zusaetzlichen
     *  Versatz richtig - die frueheren -0.125 waren Kompensation fuer den zu
     *  kleinen Wing-Scale. Negativ waere nach oben. */
    private static final double HEAD_OFFSET_Y = 0.0D;

    /** Wie weit der Kopf beim Sneaken absinkt. Y invertiert: POSITIV senkt nach unten.
     *  KALIBRIERWERT - Startpunkt 0.21 wie in applyBodyPose. */
    private static final double CROUCH_HEAD_DROP = 0.21D;

    /** Grund-Faktor fuer kopfgebundene Cosmetics. GEMESSEN (Sicht-Vergleich gegen
     *  LabyMod-Shop an 7, 16, 19, 113, 1309): 1.0, also der Katalog-Scale
     *  unveraendert. Mit dem Wing-Faktor 0.8 waren Huete zu klein - der Kopf
     *  schaute unter der Krempe hervor. */
    private static final float HEAD_SCALE = 1.0F;

    /**
     * Ein Controller pro Spieler-UUID: der Animationszustand (clip, clipTime,
     * Queue, lastCycle) liegt in Instanzfeldern, also braucht jeder Spieler
     * seine eigene Instanz, sonst ueberschreiben sich die Zyklen gegenseitig.
     * Der Layer selbst existiert nur pro Skin-Modell (slim/default), nicht pro
     * Spieler - deshalb die Map hier und nicht ein Feld pro Layer.
     * Wird beim Server-Join geleert (siehe LabyCosmeticsMod.onClientLoggingIn).
     */
    private static final Map<ControllerKey, CosmeticAnimationController> CONTROLLERS = new ConcurrentHashMap<>();

    /** Schluessel fuer CONTROLLERS: ein Animationszustand je Spieler UND Cosmetic,
     *  damit Wing und Aura sich den Zustand nicht teilen. */
    private record ControllerKey(UUID player, int cosmeticId) {
    }

    /**
     * Verwirft alle Per-Spieler-Controller. Beim naechsten Frame legt
     * computeIfAbsent sie frisch an. Wird beim Server-Join gerufen, damit die
     * Map nicht ueber Serverwechsel hinweg mit toten Spielern volllaeuft.
     * <p>
     * BEWUSST kein Einzel-Aufraeumen im render()-Pfad: der laeuft pro Frame und
     * muesste "lebt der Spieler noch?" raten - bei Fehlgriff wird ein LEBENDER
     * Controller verworfen (Animations-Reset, Zucken). Preis fuers Weglassen:
     * ein toter Controller pro waehrend der Session gegangenem Spieler, weg beim
     * naechsten Join. Winziger Speicher gegen kein Regressionsrisiko.
     */
    public static void invalidateAll() {
        CONTROLLERS.clear();
    }

    public CosmeticRenderLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @SuppressWarnings("null")
    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                       float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {

        CosmeticCatalog.ensureLoading();

        // Welche Cosmetics rendern? Im Test-Modus die feste ID, sonst alle getragenen
        // in den unterstuetzten Kategorien (Wing, Aura). Ein Spieler kann mehrere
        // gleichzeitig tragen - deshalb eine Liste und die Schleife.
        List<Integer> ids;
        if (!TEST_COSMETIC_IDS.isEmpty()) {
            ids = TEST_COSMETIC_IDS;
        } else {
            ids = UserCosmeticsManager.findAllWornByCategories(player.getUUID(), SUPPORTED_CATEGORIES);
        }

        for (int cosmeticId : ids) {
            renderOne(cosmeticId, poseStack, buffer, packedLight, player, limbSwingAmount, partialTicks,
                    netHeadYaw, headPitch);
        }
    }

    /** Rendert genau ein Cosmetic (Wing, Aura, ...). Frueher der Rumpf von render();
     *  jetzt pro getragenem Cosmetic in der Schleife aufgerufen. */
    private void renderOne(int cosmeticId, PoseStack poseStack, MultiBufferSource buffer,
                           int packedLight, AbstractClientPlayer player,
                           float limbSwingAmount, float partialTicks,
                           float netHeadYaw, float headPitch) {

        // Ein Controller je (Spieler + Cosmetic): der Animationszustand liegt in
        // Instanzfeldern, Wing und Aura duerfen sich den nicht teilen.
        CosmeticAnimationController controller = CONTROLLERS.computeIfAbsent(
                new ControllerKey(player.getUUID(), cosmeticId), k -> new CosmeticAnimationController());

        var meta = CosmeticCatalog.get(cosmeticId);
        if (meta == null || meta.textureDirectory() == null) {
            return;
        }

        // Getragene Daten, sonst die Standard-Daten aus dem Katalog (Test-Modus).
        var worn = UserCosmeticsManager.getWorn(player.getUUID(), cosmeticId);
        List<String> data = (worn != null) ? worn.data() : meta.defaultData();
        if (data == null || data.isEmpty()) {
            return;
        }
        String textureUuid = data.get(0);
        if (textureUuid == null) {
            return;
        }

        ResourceLocation texture = CosmeticTextureManager.getTexture(
                meta.textureDirectory(), textureUuid,
                meta.frameAspectWidth(), meta.frameAspectHeight());
        if (texture == null) {
            return;
        }

        BuiltCosmeticModel built = CosmeticGeometryManager.getModel(cosmeticId);
        if (built == null) {
            return;
        }

        // --- Animation anwenden ---
        if (!TEST_NO_ANIMATION) {
            BedrockAnimation anim = CosmeticAnimationManager.getAnimation(cosmeticId);
            float now = (player.tickCount + partialTicks) / 20.0F;
            // limbSwingAmount laeuft weich aus - STOP_MOVING zuendet daher erst ein
            // paar Ticks nach dem Stehenbleiben.
            // onGround und inWater kommen direkt vom Spieler. Die MOTION_*-Werte
            // stehen auf false: kein Cosmetic benutzt MOTION_FORWARD (nur der
            // Tippfehler MOVING_FORWARD bei 1689), MOTION_BACKWARDS nur bei
            // 1689 [ARMS] - kein Wing. Damit UNGEMESSEN und bewusst offen.
            var world = new BedrockAnimation.WorldState(
                    CosmeticAnimationController.isMoving(limbSwingAmount),
                    player.isCrouching(),
                    player.onGround(),
                    player.isInWater(),
                    false,
                    false);
            controller.update(anim, cosmeticId, world, now);
            // apply() setzt auch bei clip == null alle Bones auf die Ruhepose.
            BedrockAnimator.apply(built.root(), controller.clip(),
                    controller.clipTimeSeconds(), built.bonesByName());
        }

        // --- Rendern ---
        poseStack.pushPose();

        // Pose nach Befestigungspunkt: Huete folgen dem Kopf (Yaw/Pitch), alles
        // andere haengt am Koerper. Der Rest der Methode ist fuer beide gleich.
        boolean head = "HAT".equals(meta.category());
        if (head) {
            applyHeadPose(poseStack, player, netHeadYaw, headPitch);
        } else {
            applyBodyPose(poseStack, player);
        }

        // Scale aus dem Katalog (pro Cosmetic verschieden) mit unserem Grund-Faktor.
        // Koerper und Kopf haben eigene Faktoren: 0.8 ist an Wings kalibriert und
        // laesst Huete zu klein wirken (Kopf schaut unter der Krempe hervor).
        float scale = meta.scale() * (head ? HEAD_SCALE : 0.8F);
        poseStack.scale(scale, scale, scale);

        var consumer = buffer.getBuffer(RenderType.entityTranslucent(texture));

        // data[0] ist die Textur-UUID, ab data[1] die Farben fuer color_0, color_1, ...
        String hex0 = colorAt(data, 1);
        String hex1 = colorAt(data, 2);
        String hex2 = colorAt(data, 3);

        float[] color0 = CosmeticColorRenderer.hexToRgb(hex0);
        float[] color1 = CosmeticColorRenderer.hexToRgb(hex1);
        float[] color2 = hex2 != null ? CosmeticColorRenderer.hexToRgb(hex2) : null;

        // Rest-Farbe fuer nicht-color-Teile (glow, blade, middlepart, ...): die letzte
        // tatsaechlich vorhandene Farbe (idR. die Leuchtfarbe).
        float[] restColor;
        if (hex2 != null) {
            restColor = color2;
        } else if (hex1 != null) {
            restColor = color1;
        } else {
            restColor = color0;
        }

        // Umgebungslicht stabil auf STEH-Augenhoehe sampeln, nicht auf der gesneakten:
        // Vanilla sampelt an Fuss + Augenhoehe*0.7; beim Ducken sinkt die Augenhoehe,
        // die Licht-Probe rutscht in einen tieferen (oft dunkleren) Block -> Modell
        // dunkelte beim Sneaken ab. blockPosition().above() ist der Block auf Steh-
        // Augenhoehe: aendert sich beim Ducken nicht, reagiert aber weiter auf
        // Umgebungslicht (Sonne/Hoehle).
        int stableLight = net.minecraft.client.renderer.LevelRenderer.getLightColor(
                player.level(), player.blockPosition().above());

        CosmeticColorRenderer.renderColored(poseStack, consumer, stableLight,
                built.root(), built.bonesByName(), built.geometry(),
                color0, color1, color2, restColor,
                buffer, texture, textureUuid);

        poseStack.popPose();
    }

    /** Sicherer Zugriff auf ein Farb-Element im data-Array (oder null). */
    private String colorAt(List<String> data, int index) {
        return (data != null && index < data.size()) ? data.get(index) : null;
    }

    /** Pose fuer koerpergebundene Cosmetics (Wing, Aura, Back) - wie bisher. */
    private static void applyBodyPose(PoseStack poseStack, AbstractClientPlayer player) {
        if (player.isCrouching()) {
            poseStack.translate(0.0D, 0.21D, 0.0D);
            poseStack.mulPose(Axis.XP.rotationDegrees(28.65F));
        }
        poseStack.translate(0.0D, 0.0625D, 0.01D);
    }

    /**
     * Pose fuer kopfgebundene Cosmetics (HAT). Der Hut muss der Kopfdrehung folgen,
     * sonst schwebt er starr, waehrend sich der Kopf dreht. netHeadYaw/headPitch
     * kommen vom Spieler-Renderer und sind bereits relativ zum Koerper.
     * <p>
     * Drehpunkt und Aufsatzhoehe sind getrennt: NECK_PIVOT_Y bestimmt, WO gedreht
     * wird, HEAD_OFFSET_Y wie weit der Hut darueber sitzt. Beide Kalibrierwerte.
     */
    private static void applyHeadPose(PoseStack poseStack, AbstractClientPlayer player,
                                      float netHeadYaw, float headPitch) {
        // REIHENFOLGE ist entscheidend: erst an den Kopf-Drehpunkt (Halsansatz)
        // fahren, DORT drehen, und erst danach den Hut relativ dazu aufsetzen.
        // Andersherum dreht der Hut um den Layer-Ursprung und beschreibt beim
        // Umschauen einen Bogen - der Abstand zum Kopf ist dann in jeder
        // Blickrichtung anders.

        // 1) Zum Kopf-Drehpunkt. Beim Ducken senkt sich der Kopf, also mitnehmen -
        //    aber NICHT die 28.65-Grad-Neigung aus applyBodyPose: die gilt fuer den
        //    Ruecken, der Kopf bleibt beim Sneaken waagerecht.
        if (player.isCrouching()) {
            poseStack.translate(0.0D, CROUCH_HEAD_DROP, 0.0D);
        }
        poseStack.translate(0.0D, NECK_PIVOT_Y, 0.0D);

        // 2) Am Drehpunkt drehen, wie der Vanilla-Kopf: erst Yaw, dann Pitch.
        poseStack.mulPose(Axis.YP.rotationDegrees(netHeadYaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(headPitch));

        // 3) Hut relativ zum Drehpunkt aufsetzen (dreht jetzt sauber mit).
        poseStack.translate(0.0D, HEAD_OFFSET_Y, 0.0D);
    }
}