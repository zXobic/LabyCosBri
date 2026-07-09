package com.example.labycosmetics.cosmetic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Parst eine LabyMod geo.json (Bedrock-Format 1.12.0) in unsere schlanke
 * {@link BedrockGeometry}-Struktur.
 * <p>
 * Gson ist bereits Teil von Minecraft/NeoForge, daher keine extra Dependency.
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
