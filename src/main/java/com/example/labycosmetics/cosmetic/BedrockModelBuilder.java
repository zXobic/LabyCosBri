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
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

import java.util.HashMap;
import java.util.Map;

/**
 * Wandelt eine geparste {@link BedrockGeometry} in einen fertig gebackenen
 * Minecraft-{@link ModelPart} um.
 *
 * <p>Rotierte Cubes bekommen je einen Zwischen-Bone ("_rc") am Cube-Pivot,
 * da Minecraft-Cubes selbst nicht rotieren koennen.
 *
 * <p>Nur Y-POSITIONEN werden gespiegelt. Rotationen gehen unveraendert durch -
 * fuer Bones und Cubes gleichermassen; Details siehe
 * {@link #bedrockToMcRotation}. Der BedrockAnimator nutzt dieselbe Konvention.
 */
public final class BedrockModelBuilder {

    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private BedrockModelBuilder() {
    }

    public static ModelPart build(BedrockGeometry geo) {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        Map<String, PartDefinition> defs = new HashMap<>();
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
        float[] parentPivot = findParentPivot(bone, geo);

        // Position relativ zum Parent-Pivot (Y gespiegelt).
        float px = bone.pivot[0] - parentPivot[0];
        float py = -(bone.pivot[1] - parentPivot[1]);
        float pz = bone.pivot[2] - parentPivot[2];

        // Bone-Rotation: Bedrock-Werte gehen unveraendert durch.
        float[] br = bedrockToMcRotation(bone.rotation[0], bone.rotation[1], bone.rotation[2]);
        float rx = br[0];
        float ry = br[1];
        float rz = br[2];

        // Cubes OHNE eigene Rotation direkt in diesen Bone legen.
        CubeListBuilder plainCubes = CubeListBuilder.create();
        boolean hasPlain = false;
        for (BedrockGeometry.Cube cube : bone.cubes) {
            if (!cube.hasRotation) {
                addCube(plainCubes, cube, bone.pivot);
                hasPlain = true;
            }
        }

        PartDefinition def = parentDef.addOrReplaceChild(
                bone.name,
                hasPlain ? plainCubes : CubeListBuilder.create(),
                PartPose.offsetAndRotation(px, py, pz, rx, ry, rz));

        // Cubes MIT eigener Rotation bekommen je einen eigenen Kind-Bone,
        // der am Cube-Pivot sitzt und die Cube-Rotation traegt.
        int rotIndex = 0;
        for (BedrockGeometry.Cube cube : bone.cubes) {
            if (cube.hasRotation) {
                addRotatedCube(def, cube, bone.pivot, bone.name + "_rc" + rotIndex);
                rotIndex++;
            }
        }

        return def;
    }
/**
     * Rechnet eine Bedrock-Rotation [xDeg, yDeg, zDeg] in Minecraft-
     * ModelPart-Winkel (Radiant) um.
     *
     * Bedrock verwendet ZYX - dieselbe Reihenfolge wie Minecraft. Blockbench-
     * Raum und MC-Modellraum unterscheiden sich nur um eine 180-Grad-Drehung
     * um Z; dabei heben sich alle Vorzeichen auf. Die JSON-Werte gehen also
     * unveraendert durch. Gilt fuer Bones UND Cubes gleichermassen
     * (Blockbench-Codec bedrock.js 631-632 und 706-708).
     */
    private static float[] bedrockToMcRotation(float xDeg, float yDeg, float zDeg) {
        return new float[]{xDeg * DEG_TO_RAD, yDeg * DEG_TO_RAD, zDeg * DEG_TO_RAD};
    }

    private static void addRotatedCube(PartDefinition parentDef,
                                       BedrockGeometry.Cube cube,
                                       float[] bonePivot,
                                       String childName) {
        // Zwischen-Bone sitzt am Cube-Pivot (relativ zum Bone-Pivot, Y gespiegelt).
        float px = cube.pivot[0] - bonePivot[0];
        float py = -(cube.pivot[1] - bonePivot[1]);
        float pz = cube.pivot[2] - bonePivot[2];

        // Cube-Rotation: gleiche Umrechnung wie Bone.
        float[] cr = bedrockToMcRotation(cube.rotation[0], cube.rotation[1], cube.rotation[2]);

        float rx = cr[0];
        float ry = cr[1];
        float rz = cr[2];

        CubeListBuilder cubes = CubeListBuilder.create();
        // Innerhalb des Zwischen-Bones ist der Cube relativ zum Cube-Pivot.
        addCube(cubes, cube, cube.pivot);

        parentDef.addOrReplaceChild(
                childName,
                cubes,
                PartPose.offsetAndRotation(px, py, pz, rx, ry, rz));
    }

    private static void addCube(CubeListBuilder cubes,
                                BedrockGeometry.Cube cube,
                                float[] referencePivot) {
        float sx = cube.size[0];
        float sy = cube.size[1];
        float sz = cube.size[2];

        // Cube-Position relativ zum Referenz-Pivot (Bone-Pivot oder Cube-Pivot),
        // Y gespiegelt inkl. Hoehe.
        float x = cube.origin[0] - referencePivot[0];
        float y = -(cube.origin[1] + sy) + referencePivot[1];
        float z = cube.origin[2] - referencePivot[2];

        cubes.texOffs((int) cube.uv[0], (int) cube.uv[1]);
        if (cube.mirror) {
            cubes.mirror();
        }
        CubeDeformation deformation = cube.inflate != 0f
                ? new CubeDeformation(cube.inflate)
                : CubeDeformation.NONE;
        cubes.addBox(x, y, z, sx, sy, sz, deformation);
        if (cube.mirror) {
            cubes.mirror(false);
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