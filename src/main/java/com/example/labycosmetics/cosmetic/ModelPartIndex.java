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

import java.util.HashMap;
import java.util.Map;

/**
 * Baut aus einem gebauten ModelPart-Baum eine flache Map
 * (Bone-Name -> ModelPart), damit Animator und Faerbung die Bones per Namen
 * ansprechen koennen.
 * <p>
 * WICHTIG: Der {@link BedrockModelBuilder} erzeugt fuer rotierte Cubes
 * zusaetzliche Zwischen-Bones mit Namen {@code <bone>_rcN}. Diese sind NICHT
 * in {@link BedrockGeometry#bones} enthalten, tragen aber die eigentlichen
 * Flaechen. Wir nehmen sie daher explizit mit in die Map auf, indem wir fuer
 * jeden geo-Bone dessen _rcN-Kinder abfragen.
 */
public final class ModelPartIndex {

    private ModelPartIndex() {
    }

    public static Map<String, ModelPart> build(ModelPart root, BedrockGeometry geo) {
        Map<String, ModelPart> map = new HashMap<>();

        for (BedrockGeometry.Bone bone : geo.bones) {
            ModelPart part = resolve(root, geo, bone.name);
            if (part == null) {
                continue;
            }
            map.put(bone.name, part);

            // Zusaetzlich die _rc-Zwischenbones dieses Bones aufnehmen.
            // Sie heissen bone.name + "_rc0", "_rc1", ... - wir probieren
            // aufsteigend, bis keins mehr existiert.
            int rc = 0;
            while (true) {
                String rcName = bone.name + "_rc" + rc;
                ModelPart rcPart;
                try {
                    rcPart = part.getChild(rcName);
                } catch (Exception e) {
                    break; // kein weiterer _rc-Bone
                }
                map.put(rcName, rcPart);
                rc++;
            }
        }
        return map;
    }

    private static ModelPart resolve(ModelPart root, BedrockGeometry geo, String targetName) {
        java.util.List<String> chain = new java.util.ArrayList<>();
        String current = targetName;
        while (current != null) {
            chain.add(0, current);
            current = parentOf(geo, current);
        }

        ModelPart part = root;
        for (String name : chain) {
            try {
                part = part.getChild(name);
            } catch (Exception e) {
                return null;
            }
        }
        return part;
    }

    private static String parentOf(BedrockGeometry geo, String name) {
        for (BedrockGeometry.Bone b : geo.bones) {
            if (b.name.equals(name)) {
                return b.parent;
            }
        }
        return null;
    }
}
