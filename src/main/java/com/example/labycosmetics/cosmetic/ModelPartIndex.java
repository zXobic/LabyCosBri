package com.example.labycosmetics.cosmetic;

import net.minecraft.client.model.geom.ModelPart;

import java.util.HashMap;
import java.util.Map;

/**
 * Baut aus einem gebauten ModelPart-Baum eine flache Map
 * (Bone-Name -> ModelPart), damit der Animator die Bones per Namen
 * ansprechen kann.
 * <p>
 * ModelPart speichert seine Kinder intern in einer Map; wir laufen sie
 * rekursiv ab. Da das children-Feld nicht oeffentlich ist, nutzen wir die
 * offizielle getAllParts()-Methode, die alle Nachfahren liefert - inklusive
 * der Namen ueber getChild(). Weil getAllParts() aber keine Namen mitliefert,
 * indexieren wir stattdessen ueber bekannte Bone-Namen aus der Geometrie.
 */
public final class ModelPartIndex {

    private ModelPartIndex() {
    }

    /**
     * Erzeugt die Namens-Map. Da ModelPart die Kind-Namen nicht oeffentlich
     * herausgibt, uebergeben wir die Bone-Namen aus der geparsten Geometrie
     * und holen sie einzeln per getChild() (rekursiv ueber die Hierarchie).
     */
    public static Map<String, ModelPart> build(ModelPart root, BedrockGeometry geo) {
        Map<String, ModelPart> map = new HashMap<>();
        // Wir laufen die Bone-Liste ab und folgen fuer jeden Bone dem Pfad
        // von der Wurzel ueber seine Eltern-Kette bis zu ihm selbst.
        for (BedrockGeometry.Bone bone : geo.bones) {
            ModelPart part = resolve(root, geo, bone.name);
            if (part != null) {
                map.put(bone.name, part);
            }
        }
        return map;
    }

    /**
     * Loest einen Bone-Namen zu seinem ModelPart auf, indem die Parent-Kette
     * von der Wurzel aus durchlaufen wird (root -> ... -> bone).
     */
    private static ModelPart resolve(ModelPart root, BedrockGeometry geo, String targetName) {
        // Parent-Kette von targetName bis zur Wurzel aufbauen.
        java.util.List<String> chain = new java.util.ArrayList<>();
        String current = targetName;
        while (current != null) {
            chain.add(0, current);
            current = parentOf(geo, current);
        }

        // Kette von der Wurzel aus abklappern.
        ModelPart part = root;
        for (String name : chain) {
            try {
                part = part.getChild(name);
            } catch (Exception e) {
                return null; // Kind nicht gefunden
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
