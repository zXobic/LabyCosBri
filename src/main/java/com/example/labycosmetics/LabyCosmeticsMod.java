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
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("[LabyCosmetics] Client-Setup abgeschlossen.");
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
