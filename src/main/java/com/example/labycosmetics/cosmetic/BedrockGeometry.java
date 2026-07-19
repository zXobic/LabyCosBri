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
 * Datencontainer fuer eine geparste Bedrock/Blockbench-Geometrie.
 * <p>
 * Erweitert um Rotationen: sowohl Bones als auch einzelne Cubes koennen
 * eine eigene Rotation (und die Cubes einen eigenen Pivot) haben. Das ist
 * noetig fuer Cosmetics wie die Elf Wings, deren Form fast ausschliesslich
 * ueber rotierte Flaechen definiert ist.
 */
public final class BedrockGeometry {

    public int textureWidth = 64;
    public int textureHeight = 64;
    public final List<Bone> bones = new ArrayList<>();

    /** Ein Knochen (Bone) im Modell. */
    public static final class Bone {
        public String name;
        public String parent;                 // null bei Wurzel-Bones
        public float[] pivot = {0, 0, 0};
        public float[] rotation = {0, 0, 0};  // Bone-Rotation in Grad (x,y,z)
        public final List<Cube> cubes = new ArrayList<>();
    }

    /** Ein einzelner Quader (Cube) innerhalb eines Bones. */
    public static final class Cube {
        public float[] origin = {0, 0, 0};
        public float[] size = {0, 0, 0};
        public float[] uv = {0, 0};
        public boolean mirror = false;

        // Optionale eigene Rotation des Cubes um seinen eigenen Pivot.
        public boolean hasRotation = false;
        public float[] rotation = {0, 0, 0};  // Grad (x,y,z)
        public float[] pivot = {0, 0, 0};     // Drehpunkt des Cubes
        public float inflate = 0f;            // Cube-Deformation (aufblasen/schrumpfen)
    }
}
