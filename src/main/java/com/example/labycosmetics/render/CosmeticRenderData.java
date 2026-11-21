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

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;

import java.util.UUID;

/**
 * Alles, was der {@link CosmeticRenderLayer} vom Spieler braucht.
 * <p>
 * Seit 1.21.2 bekommen Render-Layer nicht mehr das Entity, sondern nur noch
 * einen {@code AvatarRenderState} - und darin fehlen UUID, onGround und
 * inWater. Deshalb liest {@code AvatarRendererMixin} diese Werte beim
 * Befuellen des Render-States ({@code extractRenderState}) vom Entity ab und
 * haengt sie ueber die Fabric-Render-State-Data-API an den State.
 *
 * @param uuid        Spieler-UUID (Schluessel fuer userdata und Animationszustand)
 * @param crouching   Spieler schleicht
 * @param moving      limbSwingAmount ueber der Bewegungsschwelle
 * @param onGround    steht auf dem Boden
 * @param inWater     ist im Wasser
 * @param timeSeconds (tickCount + partialTicks) / 20 - monotone Animationszeit
 * @param stableLight Licht auf STEH-Augenhoehe (siehe AvatarRendererMixin)
 */
public record CosmeticRenderData(
        UUID uuid,
        boolean crouching,
        boolean moving,
        boolean onGround,
        boolean inWater,
        float timeSeconds,
        int stableLight) {

    public static final RenderStateDataKey<CosmeticRenderData> KEY =
            RenderStateDataKey.create(() -> "labycosbri cosmetic data");
}
