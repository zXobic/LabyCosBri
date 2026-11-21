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

import com.example.labycosmetics.client.CapeTextureManager;
import com.example.labycosmetics.cosmetic.UserCosmeticsManager;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Ersetzt den alten LabyCapeLayer: tauscht das Cape im PlayerSkin aus, wenn
 * der Spieler ein LabyMod-Cape TRAEGT. Der Vanilla-CapeLayer zeichnet es
 * dann mit Weh-Physik, Sneak-Haltung und "Umhang anzeigen"-Option.
 * <p>
 * Vorbild: das Capes-Mod (CaelTheColher/Capes, 26.1) macht dasselbe ueber
 * {@code PlayerInfo#getSkin}. Hier haengen wir an {@code AbstractClientPlayer#getSkin},
 * weil dort die UUID direkt verfuegbar ist.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {

    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void labycosbri$useLabyCape(CallbackInfoReturnable<PlayerSkin> cir) {
        UUID uuid = ((Entity) (Object) this).getUUID();

        // Nur wenn der Cloak (Cosmetic id 0) GETRAGEN wird. /capes/{uuid} liefert
        // das BESESSENE Cape unabhaengig davon - die Trage-Wahrheit steht allein in
        // userdata."c". Belegt an Account 3d61e681: getragen -> "c":[{"i":0,...}],
        // aus -> "c":[].
        if (UserCosmeticsManager.getWorn(uuid, 0) == null) {
            return;
        }
        ClientAsset.Texture cape = CapeTextureManager.getCape(uuid);
        if (cape == null) {
            return;
        }

        PlayerSkin old = cir.getReturnValue();
        cir.setReturnValue(new PlayerSkin(old.body(), cape, old.elytra(), old.model(), old.secure()));
    }
}
