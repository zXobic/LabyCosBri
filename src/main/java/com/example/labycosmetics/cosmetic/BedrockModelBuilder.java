package com.example.labycosmetics.cosmetic;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

import java.util.HashMap;
import java.util.Map;

/**
 * Wandelt eine geparste {@link BedrockGeometry} in einen fertig "gebackenen"
 * Minecraft-{@link ModelPart} um (die Wurzel, deren Kinder man einzeln fuer
 * die Animation ansprechen kann).
 *
 * <h3>Wichtige Koordinaten-Unterschiede Bedrock -> Minecraft</h3>
 * <ul>
 *   <li>Bedrock-Y zeigt nach OBEN, Minecraft-Model-Y nach UNTEN.
 *       Wir spiegeln daher Y (negieren) bei Pivot und Origin.</li>
 *   <li>Bedrock-Origin ist die Ecke mit den kleinsten Koordinaten;
 *       nach der Y-Spiegelung wird daraus die andere Ecke, deshalb muss
 *       bei der addBox-Position die (gespiegelte) Y-Groesse beruecksichtigt
 *       werden.</li>
 *   <li>ModelPart-Positionen sind relativ zum Pivot des Eltern-Bones.</li>
 * </ul>
 *
 * Hinweis: Dieser Builder deckt den von den LabyMod-Wings genutzten
 * Funktionsumfang ab (Bones mit pivot, cubes mit origin/size/uv/mirror,
 * flache Cubes mit einer 0-Dimension). Rotationen auf Bone-Ebene im geo.json
 * kommen bei den Wings nicht vor und werden hier (noch) ignoriert.
 */
public final class BedrockModelBuilder {

    private BedrockModelBuilder() {
    }

    public static ModelPart build(BedrockGeometry geo) {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        // Wir muessen die PartDefinitions in Hierarchie-Reihenfolge anlegen:
        // ein Kind kann erst hinzugefuegt werden, wenn sein Parent existiert.
        Map<String, PartDefinition> defs = new HashMap<>();

        // Mehrfach durchlaufen, bis alle Bones platziert sind (einfacher als
        // topologisches Sortieren und fuer die kleine Bone-Zahl unkritisch).
        boolean progress = true;
        boolean[] placed = new boolean[geo.bones.size()];
        int remaining = geo.bones.size();

        while (remaining > 0 && progress) {
            progress = false;
            for (int i = 0; i < geo.bones.size(); i++) {
                if (placed[i]) continue;
                BedrockGeometry.Bone bone = geo.bones.get(i);

                PartDefinition parentDef;
                if (bone.parent == null) {
                    parentDef = root;
                } else {
                    parentDef = defs.get(bone.parent);
                    if (parentDef == null) {
                        // Parent noch nicht angelegt -> spaeter erneut versuchen
                        continue;
                    }
                }

                PartDefinition def = addBone(parentDef, bone, geo);
                defs.put(bone.name, def);
                placed[i] = true;
                remaining--;
                progress = true;
            }
        }

        LayerDefinition layer = LayerDefinition.create(mesh, geo.textureWidth, geo.textureHeight);
        return layer.bakeRoot();
    }

    private static PartDefinition addBone(PartDefinition parentDef,
                                          BedrockGeometry.Bone bone,
                                          BedrockGeometry geo) {
        CubeListBuilder cubes = CubeListBuilder.create();

        // Pivot des Parents ermitteln, denn ModelPart-Positionen sind
        // relativ zum Eltern-Pivot.
        float[] parentPivot = findParentPivot(bone, geo);

        for (BedrockGeometry.Cube cube : bone.cubes) {
            addCube(cubes, cube, bone);
        }

        // Position dieses Bones relativ zum Parent (Y gespiegelt).
        float px = bone.pivot[0] - parentPivot[0];
        float py = -(bone.pivot[1] - parentPivot[1]); // Y-Spiegelung
        float pz = bone.pivot[2] - parentPivot[2];

        return parentDef.addOrReplaceChild(
                bone.name,
                cubes,
                PartPose.offset(px, py, pz));
    }

    private static void addCube(CubeListBuilder cubes,
                                BedrockGeometry.Cube cube,
                                BedrockGeometry.Bone bone) {
        float sx = cube.size[0];
        float sy = cube.size[1];
        float sz = cube.size[2];

        // Position der addBox relativ zum Bone-Pivot.
        // Bedrock: origin ist die minimale Ecke. Nach Y-Spiegelung wird die
        // obere Kante zu (origin_y + size_y) im negierten Raum.
        float x = cube.origin[0] - bone.pivot[0];
        float y = -(cube.origin[1] + sy) + bone.pivot[1]; // Y gespiegelt inkl. Hoehe
        float z = cube.origin[2] - bone.pivot[2];

        cubes.texOffs((int) cube.uv[0], (int) cube.uv[1]);
        if (cube.mirror) {
            cubes.mirror();
        }

        cubes.addBox(x, y, z, sx, sy, sz, CubeDeformation.NONE);

        if (cube.mirror) {
            cubes.mirror(false); // zuruecksetzen fuer nachfolgende Cubes
        }
    }

    private static float[] findParentPivot(BedrockGeometry.Bone bone, BedrockGeometry geo) {
        if (bone.parent == null) {
            return new float[]{0, 0, 0};
        }
        for (BedrockGeometry.Bone b : geo.bones) {
            if (b.name.equals(bone.parent)) {
                return b.pivot;
            }
        }
        return new float[]{0, 0, 0};
    }
}
