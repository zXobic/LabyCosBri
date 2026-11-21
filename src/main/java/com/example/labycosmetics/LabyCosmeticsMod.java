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
package com.example.labycosmetics;

import com.example.labycosmetics.client.CapeTextureManager;
import com.example.labycosmetics.cosmetic.UserCosmeticsManager;
import com.example.labycosmetics.render.CosmeticRenderLayer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Einstiegspunkt (Fabric, Client).
 * <p>
 * Rein clientseitig: ruft LabyMod-Cosmetics ueber die (inoffizielle, aber
 * oeffentlich dokumentierte) LabyMod-API ab und rendert sie zusaetzlich zum
 * normalen Spieler-Modell, ohne dass der LabyMod-Client installiert sein muss.
 * <p>
 * Entsprechungen zur NeoForge-Version:
 * <ul>
 *   <li>{@code EntityRenderersEvent.AddLayers} -> {@link LivingEntityRenderLayerRegistrationCallback}</li>
 *   <li>{@code ClientPlayerNetworkEvent.LoggingIn} -> {@link ClientPlayConnectionEvents#JOIN}</li>
 *   <li>{@code FMLClientSetupEvent} -> {@link #onInitializeClient()}</li>
 *   <li>LabyCapeLayer -> entfaellt, siehe AbstractClientPlayerMixin</li>
 * </ul>
 * "environment": "client" in fabric.mod.json sorgt dafuer, dass die Mod auf
 * einem dedizierten Server gar nicht erst geladen wird.
 */
public class LabyCosmeticsMod implements ClientModInitializer {

    public static final String MOD_ID = "labycosbri";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        // Wird fuer jeden LivingEntityRenderer gerufen - fuer Spieler zweimal
        // (Skin-Modelle "wide" und "slim"). Dazu kommen Mannequins (ebenfalls
        // AvatarRenderer); die filtert AvatarRendererMixin ueber "instanceof Player".
        LivingEntityRenderLayerRegistrationCallback.EVENT.register(
                (entityType, entityRenderer, registrationHelper, context) -> {
                    if (entityRenderer instanceof AvatarRenderer<?> avatarRenderer) {
                        registrationHelper.register(new CosmeticRenderLayer(avatarRenderer));
                    }
                });

        // Beim (Re)Connect die Trage-, Cape- und Animations-Caches leeren, damit
        // der aktuelle Zustand vom Server gilt statt eines eingefrorenen.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            UserCosmeticsManager.invalidateAll();
            CapeTextureManager.invalidateAll();
            CosmeticRenderLayer.invalidateAll();
        });

        LOGGER.info("[LabyCosmetics] Client initialisiert.");
    }
}
