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
import com.example.labycosmetics.cosmetic.WingAnimationController;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Rendert das getragene WING-Cosmetic des lokalen Spielers dynamisch, inklusive
 * Animation und pro-Federreihe-Faerbung aus der userdata.
 */
public class WingRenderLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    /** TEST: feste Wing-ID statt der getragenen; Textur dann aus meta.defaultData(). 0 = aus. */
    private static final int TEST_WING_ID = 963;

    /**
     * TEST: Animation aus -> Ruhe-Geometrie. Fuer den Vergleich gegen Blockbench
     * zwingend, sonst vergleicht man die eigene animierte Pose gegen dessen Ruhepose.
     */
    private static final boolean TEST_NO_ANIMATION = false;

    /** Nur der lokale Spieler wird gerendert, eine Instanz reicht. Sonst: Map<UUID, ...>. */
    private final WingAnimationController controller = new WingAnimationController();

    public WingRenderLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @SuppressWarnings("null")
    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                       float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {

        var localPlayer = Minecraft.getInstance().player;
        if (localPlayer == null || !player.getUUID().equals(localPlayer.getUUID())) {
            return;
        }

        CosmeticCatalog.ensureLoading();

        Integer wingId = UserCosmeticsManager.findWornByCategory(player.getUUID(), "WING");
        if (TEST_WING_ID != 0) {
            wingId = TEST_WING_ID;
        }
        if (wingId == null) {
            return;
        }

        var meta = CosmeticCatalog.get(wingId);
        if (meta == null || meta.textureDirectory() == null) {
            return;
        }

        // Getragene Daten, sonst die Standard-Daten aus dem Katalog (Test-Modus).
        var worn = UserCosmeticsManager.getWorn(player.getUUID(), wingId);
        List<String> data = (worn != null) ? worn.data() : meta.defaultData();
        if (data == null || data.isEmpty()) {
            return;
        }
        String textureUuid = data.get(0);
        if (textureUuid == null) {
            return;
        }

        ResourceLocation texture = CosmeticTextureManager.getTexture(meta.textureDirectory(), textureUuid);
        if (texture == null) {
            return;
        }

        BuiltCosmeticModel built = CosmeticGeometryManager.getModel(wingId);
        if (built == null) {
            return;
        }

        // --- Animation anwenden ---
        if (!TEST_NO_ANIMATION) {
            BedrockAnimation anim = CosmeticAnimationManager.getAnimation(wingId);
            float now = (player.tickCount + partialTicks) / 20.0F;
            // limbSwingAmount laeuft weich aus - STOP_MOVING zuendet daher erst ein
            // paar Ticks nach dem Stehenbleiben.
            // onGround und inWater kommen direkt vom Spieler. Die MOTION_*-Werte
            // stehen auf false: kein Cosmetic benutzt MOTION_FORWARD (nur der
            // Tippfehler MOVING_FORWARD bei 1689), MOTION_BACKWARDS nur bei
            // 1689 [ARMS] - kein Wing. Damit UNGEMESSEN und bewusst offen.
            var world = new BedrockAnimation.WorldState(
                    WingAnimationController.isMoving(limbSwingAmount),
                    player.isCrouching(),
                    player.onGround(),
                    player.isInWater(),
                    false,
                    false);
            controller.update(anim, wingId, world, now);
            // apply() setzt auch bei clip == null alle Bones auf die Ruhepose.
            BedrockAnimator.apply(built.root(), controller.clip(),
                    controller.clipTimeSeconds(), built.bonesByName());
        }

        // --- Rendern ---
        poseStack.pushPose();

        if (player.isCrouching()) {
            poseStack.translate(0.0D, 0.21D, 0.0D);
            poseStack.mulPose(Axis.XP.rotationDegrees(28.65F));
        }

        poseStack.translate(0.0D, 0.0625D, 0.01D);

        // Scale aus dem Katalog (pro Cosmetic verschieden) mit unserem Grund-Faktor.
        float scale = meta.scale() * 0.8F;
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

        CosmeticColorRenderer.renderColored(poseStack, consumer, packedLight,
                built.root(), built.bonesByName(), built.geometry(),
                color0, color1, color2, restColor);

        poseStack.popPose();
    }

    /** Sicherer Zugriff auf ein Farb-Element im data-Array (oder null). */
    private String colorAt(List<String> data, int index) {
        return (data != null && index < data.size()) ? data.get(index) : null;
    }
}