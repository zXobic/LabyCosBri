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
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
 * Rendert das getragene WING-Cosmetic des lokalen Spielers dynamisch,
 * inklusive Animation und pro-Federreihe-Faerbung aus der userdata.
 */
public class WingRenderLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

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
        if (wingId == null) {
            return;
        }

        var meta = CosmeticCatalog.get(wingId);
        var worn = UserCosmeticsManager.getWorn(player.getUUID(), wingId);
        if (meta == null || worn == null || meta.textureDirectory() == null) {
            return;
        }

        String textureUuid = worn.textureUuid();
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

        // --- Animation anwenden (falls vorhanden) ---
        BedrockAnimation anim = CosmeticAnimationManager.getAnimation(wingId);
        if (anim != null) {
            BedrockAnimation.Clip clip = anim.findClipBySuffix("idle");
            if (clip != null) {
                float rawTime = (player.tickCount + partialTicks) / 20.0F;
                float clipLength = clip.lengthSeconds > 0f ? clip.lengthSeconds : 6.0F;
                float phase = (rawTime % clipLength) / clipLength;
                float timeSeconds = phase * clipLength;
                BedrockAnimator.apply(built.root(), clip, timeSeconds, built.bonesByName());
            }
        }

        // --- Rendern ---
        poseStack.pushPose();

        if (player.isCrouching()) {
            poseStack.translate(0.0D, 0.21D, 0.0D);
            poseStack.mulPose(Axis.XP.rotationDegrees(28.65F));
        }

        poseStack.translate(0.0D, 0.0625D, 0.01D);

        // Scale aus dem Katalog (pro Cosmetic unterschiedlich) mit unserem
        // Grund-Faktor kombinieren.
        float scale = meta.scale() * 0.8F;
        poseStack.scale(scale, scale, scale);

        var consumer = buffer.getBuffer(RenderType.entityTranslucent(texture));

        // Farben aus der userdata holen. data[0] ist die Textur-UUID,
        // ab data[1] kommen die Farben fuer color_0, color_1, color_2 ...
        List<String> data = worn.data();
        String hex0 = colorAt(data, 1);
        String hex1 = colorAt(data, 2);
        String hex2 = colorAt(data, 3);

        float[] color0 = CosmeticColorRenderer.hexToRgb(hex0);
        float[] color1 = CosmeticColorRenderer.hexToRgb(hex1);
        float[] color2 = hex2 != null ? CosmeticColorRenderer.hexToRgb(hex2) : null;

        // Rest-Farbe fuer nicht-color-Teile (glow, blade, middlepart, ...):
        // die letzte tatsaechlich vorhandene Farbe (idR. die Leuchtfarbe).
        float[] restColor;
        if (hex2 != null) {
            restColor = color2;
        } else if (hex1 != null) {
            restColor = color1;
        } else {
            restColor = color0;
        }

        // Komplettes Modell mit korrekter Faerbung aller Teile rendern.
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