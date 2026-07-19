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

import com.example.labycosmetics.render.LabyCapeLayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Einstiegspunkt des Mods.
 * <p>
 * Dieser Mod ist rein clientseitig: er ruft LabyMod-Cosmetics ueber die
 * (inoffizielle, aber oeffentlich dokumentierte) LabyMod-API ab und rendert
 * sie zusaetzlich zum normalen Spieler-Modell, ohne dass der LabyMod-Client
 * selbst installiert sein muss.
 */
@Mod(LabyCosmeticsMod.MOD_ID)
public class LabyCosmeticsMod {

    public static final String MOD_ID = "labycosbri";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public LabyCosmeticsMod(IEventBus modEventBus) {
        // Wird aufgerufen, sobald der Client bereit ist, zusaetzliche
        // Render-Layer an bestehende Entity-Renderer (hier: Spieler) anzuhaengen.
        modEventBus.addListener(this::onAddLayers);
        modEventBus.addListener(this::onClientSetup);

        // Game-Bus (NeoForge.EVENT_BUS), NICHT der Mod-Bus oben: das Join-Event
        // wird dort gefeuert. Beim (Re)Connect die Trage- und Cape-Caches leeren,
        // damit der aktuelle Zustand vom Server gilt statt eines eingefrorenen.
        NeoForge.EVENT_BUS.addListener(LabyCosmeticsMod::onClientLoggingIn);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("[LabyCosmetics] Client-Setup abgeschlossen.");
    }

    // static, weil per Methodenreferenz am Game-Bus registriert (NeoForge-Doku).
    private static void onClientLoggingIn(
            net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        com.example.labycosmetics.cosmetic.UserCosmeticsManager.invalidateAll();
        com.example.labycosmetics.client.CapeTextureManager.invalidateAll();
        com.example.labycosmetics.render.WingRenderLayer.invalidateAll();
    }

    private void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for (var skin : event.getSkins()) {
            var renderer = event.getSkin(skin);
            if (renderer instanceof net.minecraft.client.renderer.entity.player.PlayerRenderer playerRenderer) {
            playerRenderer.addLayer(new LabyCapeLayer(playerRenderer));
            playerRenderer.addLayer(new com.example.labycosmetics.render.WingRenderLayer(playerRenderer));
            LOGGER.info("[LabyCosmetics] Cape-Layer fuer Skin '{}' registriert.", skin);
            }
        }
    }
}
