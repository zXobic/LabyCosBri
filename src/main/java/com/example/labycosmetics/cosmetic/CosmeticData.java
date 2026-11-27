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

import java.util.ArrayList;
import java.util.List;

/**
 * Die getragenen Daten eines Cosmetics ("d"-Array aus userdata bzw.
 * default_data aus dem Katalog), nach ihrer BEDEUTUNG aufgeschluesselt.
 *
 * <h3>Warum es das gibt</h3>
 * Frueher galt fest: d[0] = Textur, ab d[1] = Farben. Der Katalog-Scan zeigt,
 * dass die Reihenfolge stattdessen vom {@code options}-Feld des Cosmetics
 * vorgegeben wird:
 * <ul>
 *   <li>113 Cosmetics haben eine {@code offset}-Option, z.B. Halo 9
 *       ({@code [texture, rgb, offset]} -> {@code ["4b20...", "ffc100", "0.0;0.0;0.0"]})
 *       und 1460 ({@code [texture, rgb, rgb, rgb, offset]}). Der Offset-String wurde
 *       bisher als Farbe gelesen -> weiss, und landete als Rest-Farbe auf den
 *       nicht-color-Teilen.</li>
 *   <li>82 Cosmetics beginnen mit {@code shoulder_side}, {@code side} oder
 *       {@code mojang_uuid} - dort ist d[0] gar nicht die Textur (v.a. PETS, ARMS).</li>
 *   <li>Nametag-Cosmetics haben nach {@code nametag} weitere rgb-Eintraege fuer den
 *       Namensschild-Rahmen. Die gehoeren nicht zum Modell.</li>
 * </ul>
 * Fehlt {@code options} (2 Eintraege im Katalog), gilt das alte Schema.
 *
 * @param textureUuid Textur-UUID oder null
 * @param colors      Modell-Farben in Reihenfolge (color_0, color_1, ...), Hex ohne #
 * @param offsetX     Offset aus der offset-Option (Einheit: Pixel, ANNAHME)
 * @param offsetY     "
 * @param offsetZ     "
 */
public record CosmeticData(String textureUuid, List<String> colors,
                           float offsetX, float offsetY, float offsetZ) {

    public static CosmeticData parse(List<String> options, List<String> data) {
        if (data == null || data.isEmpty()) {
            return new CosmeticData(null, List.of(), 0f, 0f, 0f);
        }
        if (options == null || options.isEmpty() || options.stream().allMatch(String::isEmpty)) {
            // Altes Schema als Fallback: d[0] Textur, danach Farben.
            return new CosmeticData(data.get(0), new ArrayList<>(data.subList(1, data.size())), 0f, 0f, 0f);
        }

        String texture = null;
        List<String> colors = new ArrayList<>();
        float[] offset = {0f, 0f, 0f};
        boolean inNametag = false;
        for (int i = 0; i < options.size() && i < data.size(); i++) {
            String opt = options.get(i);
            String value = data.get(i);
            switch (opt) {
                case "texture" -> texture = value;
                case "rgb" -> {
                    if (!inNametag) {
                        colors.add(value);
                    }
                }
                case "offset" -> offset = parseOffset(value);
                case "nametag_enabled", "nametag" -> inNametag = true;
                default -> {
                    // side, shoulder_side, mojang_uuid, border_enabled: fuer das Modell egal
                }
            }
        }
        return new CosmeticData(texture, colors, offset[0], offset[1], offset[2]);
    }

    /** "x;y;z" -> float[3]; ungueltige Teile zaehlen als 0. */
    private static float[] parseOffset(String value) {
        float[] out = {0f, 0f, 0f};
        if (value == null) {
            return out;
        }
        String[] parts = value.split(";");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                out[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException ignored) {
                // bleibt 0
            }
        }
        return out;
    }

    public boolean hasOffset() {
        return offsetX != 0f || offsetY != 0f || offsetZ != 0f;
    }
}
