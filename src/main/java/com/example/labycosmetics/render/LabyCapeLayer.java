package com.example.labycosmetics.render;

import com.example.labycosmetics.client.CapeTextureManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerModelPart;

/**
 * Zeichnet das ueber die LabyMod-API abgerufene Cape - mit Weh-Animation
 * nach dem Vorbild des Vanilla-Cape-Layers (CapeLayer#render).
 */
public class LabyCapeLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    private final ModelPart capeModel;

    public LabyCapeLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);

        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild(
                "cape",
                CubeListBuilder.create()
                        .texOffs(0, 0)
                        .addBox(-5.0F, 0.0F, -1.0F, 10.0F, 16.0F, 1.0F),
                PartPose.offset(0.0F, 0.0F, 0.0F));

        LayerDefinition layerDefinition = LayerDefinition.create(mesh, 22, 17);
        this.capeModel = layerDefinition.bakeRoot().getChild("cape");
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                        AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                        float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {

        if (!player.isModelPartShown(PlayerModelPart.CAPE)) {
            return;
        }

        ResourceLocation capeTexture = CapeTextureManager.getCapeTexture(player.getUUID());
        if (capeTexture == null) {
            return;
        }

        poseStack.pushPose();

        // Beim Schleichen kippt der Vanilla-Koerper nach vorne und senkt
        // sich ab - das Cape muss entsprechend mitgeschoben werden, sonst
        // haengt es in der Steh-Position in der Luft.
        boolean crouching = player.isCrouching();
        if (crouching) {
            poseStack.translate(0.0D, 0.16D, -0.045D);
        }

        poseStack.translate(0.0D, 0.01D, 0.125D);

        // --- Weh-Animation, berechnet aus der "Cloak"-Bewegung des Spielers ---
        // Minecraft speichert eine leicht verzoegerte Position des Umhangs
        // (xCloak/yCloak/zCloak), aus deren Differenz zur echten Position
        // sich der Schwung ergibt.
        double dX = Mth.lerp(partialTicks, player.xCloakO, player.xCloak)
                  - Mth.lerp(partialTicks, player.xo, player.getX());
        double dY = Mth.lerp(partialTicks, player.yCloakO, player.yCloak)
                  - Mth.lerp(partialTicks, player.yo, player.getY());
        double dZ = Mth.lerp(partialTicks, player.zCloakO, player.zCloak)
                  - Mth.lerp(partialTicks, player.zo, player.getZ());

        float bodyYaw = Mth.rotLerp(partialTicks, player.yBodyRotO, player.yBodyRot);
        double sinYaw = Mth.sin(bodyYaw * ((float) Math.PI / 180F));
        double cosYaw = -Mth.cos(bodyYaw * ((float) Math.PI / 180F));

        // vertikaler Anteil (Springen/Fallen), begrenzt
        float swingY = (float) dY * 10.0F;
        swingY = Mth.clamp(swingY, -6.0F, 32.0F);

        // Anteil in Laufrichtung -> Cape weht nach hinten hoch
        float swingBack = (float) (dX * sinYaw + dZ * cosYaw) * 100.0F;
        swingBack = Mth.clamp(swingBack, 0.0F, 150.0F);

        // seitlicher Anteil (beim Drehen/Strafen)
        float swingSide = (float) (dX * cosYaw - dZ * sinYaw) * 100.0F;
        swingSide = Mth.clamp(swingSide, -20.0F, 20.0F);

        // leichtes Wippen im Lauftakt, damit es lebendig wirkt
        float bob = limbSwingAmount * Mth.sin(limbSwing * 0.6F) * 3.0F;

        // Beim Ducken zusaetzliche Vorwaerts-Neigung, damit das Cape der
        // gebeugten Koerperhaltung folgt statt gerade herunterzuhaengen.
        float crouchPitch = crouching ? 25.0F : 0.0F;

        // Grundneigung 6 Grad + Bewegungsanteile + evtl. Duck-Neigung
        float pitch = 6.0F + swingBack / 2.0F + swingY + bob + crouchPitch;

        poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
        poseStack.mulPose(Axis.ZP.rotationDegrees(swingSide / 2.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - swingSide / 2.0F));

        var vertexConsumer = buffer.getBuffer(RenderType.entitySolid(capeTexture));
        capeModel.render(poseStack, vertexConsumer, packedLight, OverlayTexture.NO_OVERLAY);

        poseStack.popPose();
    }
}