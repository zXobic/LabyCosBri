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
 * Minecraft-{@link ModelPart} um - jetzt inklusive Bone- und Cube-Rotationen.
 *
 * <h3>Wie Rotationen umgesetzt werden</h3>
 * <ul>
 *   <li><b>Bone-Rotation:</b> wird direkt in die PartPose des Bones gelegt
 *       (PartPose kann Position UND Rotation).</li>
 *   <li><b>Cube-Rotation:</b> Minecraft-Cubes koennen selbst nicht rotieren.
 *       Wir legen daher fuer jeden rotierten Cube einen eigenen Zwischen-Bone
 *       an, der am Cube-Pivot sitzt und die Rotation traegt; der Cube haengt
 *       dann rotationsfrei darunter.</li>
 * </ul>
 *
 * <h3>Koordinaten</h3>
 * Bedrock-Y zeigt nach oben, Minecraft-Model-Y nach unten -> wir spiegeln Y.
 * Rotationswinkel um X bleiben, um Y und Z kehren sich durch die Spiegelung
 * das Vorzeichen um (analog zum Animator).
 */
public final class BedrockModelBuilder {

    // Auf true setzen, um beim Modellbau alle Cube-Umrechnungen in die
    // Konsole zu dumpen (zum Debuggen der Geometrie).
    private static final boolean DEBUG_CUBES = false;

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

        // Bone-Rotation: Bedrock -> MC-ZYX umrechnen (loest Reihenfolge-
        // Problem bei mehrachsigen Rotationen).
        float[] br = bedrockToMcRotation(bone.rotation[0], bone.rotation[1], bone.rotation[2], bone.name);
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
     * Rechnet eine Bedrock-Rotation [xDeg, yDeg, zDeg] in die Minecraft-
     * ZYX-Konvention um und liefert {xRot, yRot, zRot} in Radiant.
     * Loest das Reihenfolge-Problem bei mehrachsigen Rotationen, ohne die
     * Bone-Hierarchie zu veraendern.
     */
    private static float[] bedrockToMcRotation(float xDeg, float yDeg, float zDeg, String boneName) {
        // === JUSTIER-REGLER (nur Flügel-Wurzeln right2/left2) ===
        // Extra-Rotation zum Messen des fehlenden Winkels. In kleinen
        // Schritten aendern und neu bauen, bis die Klingen exakt passen.
        double rx = Math.toRadians(-xDeg);  // X negieren (Y-Spiegelung)
        double ry = Math.toRadians(yDeg);
        double rz = Math.toRadians(zDeg);

        // Ziel-Matrix in Bedrock-Reihenfolge XYZ: M = Rx * Ry * Rz
        double[][] mx = rotXm(rx);
        double[][] my = rotYm(ry);
        double[][] mz = rotZm(rz);
        double[][] m = mul(mul(mx, my), mz);

        // Aus M die MC-ZYX-Winkel extrahieren (M = Rz*Ry*Rx).
        double sy = -m[2][0];
        double ex, ey, ez;
        if (Math.abs(sy) < 0.99999) {
            ex = Math.atan2(m[2][1], m[2][2]);
            ey = Math.asin(sy);
            ez = Math.atan2(m[1][0], m[0][0]);
        } else {
            ex = Math.atan2(-m[1][2], m[1][1]);
            ey = Math.asin(sy);
            ez = 0;
        }
        return new float[]{(float) ex, (float) ey, (float) ez};
    }

    private static double[][] rotXm(double r) {
        return new double[][]{{1,0,0},{0,Math.cos(r),-Math.sin(r)},{0,Math.sin(r),Math.cos(r)}};
    }
    private static double[][] rotYm(double r) {
        return new double[][]{{Math.cos(r),0,Math.sin(r)},{0,1,0},{-Math.sin(r),0,Math.cos(r)}};
    }
    private static double[][] rotZm(double r) {
        return new double[][]{{Math.cos(r),-Math.sin(r),0},{Math.sin(r),Math.cos(r),0},{0,0,1}};
    }
    private static double[][] mul(double[][] a, double[][] b) {
        double[][] c = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                for (int k = 0; k < 3; k++)
                    c[i][j] += a[i][k] * b[k][j];
        return c;
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
        float[] cr = bedrockToMcRotation(cube.rotation[0], cube.rotation[1], cube.rotation[2], null);
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

        // DEBUG: Original- und umgerechnete Werte ausgeben.
        if (DEBUG_CUBES) {
            System.out.println(String.format(
                "[LabyCos-CUBE] origin[%.2f,%.2f,%.2f] size[%.2f,%.2f,%.2f] "
                + "refPivot[%.2f,%.2f,%.2f] inflate=%.2f rot=%s "
                + "-> addBox xyz[%.2f,%.2f,%.2f] size[%.2f,%.2f,%.2f]",
                cube.origin[0], cube.origin[1], cube.origin[2],
                cube.size[0], cube.size[1], cube.size[2],
                referencePivot[0], referencePivot[1], referencePivot[2],
                cube.inflate,
                (cube.hasRotation ? java.util.Arrays.toString(cube.rotation) : "none"),
                x, y, z, sx, sy, sz));
        }

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
