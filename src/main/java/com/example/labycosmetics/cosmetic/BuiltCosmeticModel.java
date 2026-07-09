package com.example.labycosmetics.cosmetic;

import net.minecraft.client.model.geom.ModelPart;

import java.util.Map;

/**
 * Buendelt ein gebautes Modell mit seiner Bone-Namen-Map und der zugrunde
 * liegenden Geometrie. So hat der Render-Layer alles zusammen, was er zum
 * Animieren braucht.
 */
public record BuiltCosmeticModel(
        ModelPart root,
        Map<String, ModelPart> bonesByName,
        BedrockGeometry geometry) {
}
