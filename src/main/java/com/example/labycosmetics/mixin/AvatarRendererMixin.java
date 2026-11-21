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
package com.example.labycosmetics.mixin;

import com.example.labycosmetics.cosmetic.CosmeticAnimationController;
import com.example.labycosmetics.render.CosmeticRenderData;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Kopiert beim Befuellen des Render-States die Spielerdaten, die der
 * Cosmetic-Layer braucht, in den State (siehe {@link CosmeticRenderData}).
 * <p>
 * Aufbau 1:1 nach dem Fabric-API-Testmod {@code AvatarRendererMixin} (26.1.2).
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin<AvatarlikeEntity extends Avatar & ClientAvatarEntity> {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("RETURN"))
    private void labycosbri$extractCosmeticData(AvatarlikeEntity entity, AvatarRenderState state,
                                                float partialTicks, CallbackInfo ci) {
        // Render-States werden wiederverwendet: fuer Nicht-Spieler (z.B. Mannequins,
        // seit 1.21.9 ebenfalls Avatare) explizit leeren, sonst erbt eine
        // Mannequin die Cosmetics des zuletzt gezeichneten Spielers.
        if (!(entity instanceof Player)) {
            state.setData(CosmeticRenderData.KEY, null);
            return;
        }

        // Umgebungslicht stabil auf STEH-Augenhoehe sampeln, nicht auf der gesneakten:
        // beim Ducken rutscht die Vanilla-Licht-Probe in einen tieferen (oft dunkleren)
        // Block -> Modell dunkelte beim Sneaken ab. blockPosition().above() aendert sich
        // beim Ducken nicht, reagiert aber weiter auf Umgebungslicht.
        // 26.2+: LevelRenderer#getLightCoords ist nach LightCoordsUtil umgezogen.
        int stableLight = LightCoordsUtil.getLightCoords(entity.level(), entity.blockPosition().above());

        // limbSwingAmount laeuft weich aus - STOP_MOVING zuendet daher erst ein
        // paar Ticks nach dem Stehenbleiben (wie in der NeoForge-Version).
        float limbSwingAmount = entity.walkAnimation.speed(partialTicks);

        state.setData(CosmeticRenderData.KEY, new CosmeticRenderData(
                entity.getUUID(),
                entity.isCrouching(),
                CosmeticAnimationController.isMoving(limbSwingAmount),
                entity.onGround(),
                entity.isInWater(),
                (entity.tickCount + partialTicks) / 20.0F,
                stableLight));
    }
}
