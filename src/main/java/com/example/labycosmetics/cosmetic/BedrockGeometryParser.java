package com.example.labycosmetics.cosmetic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Parst eine LabyMod geo.json (Bedrock-Format) in unsere
 * {@link BedrockGeometry}-Struktur - inklusive Bone- und Cube-Rotationen
 * sowie Cube-Pivots.
 */
public final class BedrockGeometryParser {

    private BedrockGeometryParser() {
    }

    public static BedrockGeometry parse(String json) {
        BedrockGeometry geo = new BedrockGeometry();

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray geometries = root.getAsJsonArray("minecraft:geometry");
        if (geometries == null || geometries.isEmpty()) {
            return geo;
        }

        JsonObject first = geometries.get(0).getAsJsonObject();

        JsonObject desc = first.getAsJsonObject("description");
        if (desc != null) {
            if (desc.has("texture_width")) {
                geo.textureWidth = desc.get("texture_width").getAsInt();
            }
            if (desc.has("texture_height")) {
                geo.textureHeight = desc.get("texture_height").getAsInt();
            }
        }

        JsonArray bonesJson = first.getAsJsonArray("bones");
        if (bonesJson == null) {
            return geo;
        }

        for (JsonElement boneEl : bonesJson) {
            JsonObject boneObj = boneEl.getAsJsonObject();
            BedrockGeometry.Bone bone = new BedrockGeometry.Bone();

            bone.name = boneObj.get("name").getAsString();
            if (boneObj.has("parent")) {
                bone.parent = boneObj.get("parent").getAsString();
            }
            if (boneObj.has("pivot")) {
                bone.pivot = toFloatArray(boneObj.getAsJsonArray("pivot"));
            }
            if (boneObj.has("rotation")) {
                bone.rotation = toFloatArray(boneObj.getAsJsonArray("rotation"));
            }

            if (boneObj.has("cubes")) {
                for (JsonElement cubeEl : boneObj.getAsJsonArray("cubes")) {
                    JsonObject cubeObj = cubeEl.getAsJsonObject();
                    BedrockGeometry.Cube cube = new BedrockGeometry.Cube();

                    if (cubeObj.has("origin")) {
                        cube.origin = toFloatArray(cubeObj.getAsJsonArray("origin"));
                    }
                    if (cubeObj.has("size")) {
                        cube.size = toFloatArray(cubeObj.getAsJsonArray("size"));
                    }
                    if (cubeObj.has("uv") && cubeObj.get("uv").isJsonArray()) {
                        cube.uv = toFloatArray(cubeObj.getAsJsonArray("uv"));
                    }
                    if (cubeObj.has("mirror")) {
                        cube.mirror = cubeObj.get("mirror").getAsBoolean();
                    }
                    if (cubeObj.has("inflate")) {
                        cube.inflate = cubeObj.get("inflate").getAsFloat();
                    }
                    if (cubeObj.has("rotation")) {
                        cube.hasRotation = true;
                        cube.rotation = toFloatArray(cubeObj.getAsJsonArray("rotation"));
                        // Cube-Pivot: falls angegeben, sonst Fallback auf origin-Mitte
                        if (cubeObj.has("pivot")) {
                            cube.pivot = toFloatArray(cubeObj.getAsJsonArray("pivot"));
                        } else {
                            cube.pivot = cube.origin.clone();
                        }
                    }

                    bone.cubes.add(cube);
                }
            }

            geo.bones.add(bone);
        }

        return geo;
    }

    private static float[] toFloatArray(JsonArray arr) {
        float[] out = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            out[i] = arr.get(i).getAsFloat();
        }
        return out;
    }
}
