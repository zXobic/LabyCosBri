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
