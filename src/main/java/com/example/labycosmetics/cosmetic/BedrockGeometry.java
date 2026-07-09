package com.example.labycosmetics.cosmetic;

import java.util.ArrayList;
import java.util.List;

/**
 * Einfache Datencontainer fuer eine geparste Bedrock/Blockbench-Geometrie
 * (das Format der LabyMod geo.json). Bewusst schlank gehalten - nur die
 * Felder, die wir zum Bauen von Minecraft-ModelParts brauchen.
 */
public final class BedrockGeometry {

    public int textureWidth = 64;
    public int textureHeight = 64;
    public final List<Bone> bones = new ArrayList<>();

    /** Ein Knochen (Bone) im Modell - kann Kinder ueber "parent" referenzieren. */
    public static final class Bone {
        public String name;
        public String parent;          // null bei Wurzel-Bones
        public float[] pivot = {0, 0, 0};
        public final List<Cube> cubes = new ArrayList<>();
    }

    /** Ein einzelner Quader (Cube) innerhalb eines Bones. */
    public static final class Cube {
        public float[] origin = {0, 0, 0};   // Ecke des Quaders (Weltkoordinaten im Modellraum)
        public float[] size = {0, 0, 0};     // Breite/Hoehe/Tiefe (eine davon kann 0 sein -> flache Ebene)
        public float[] uv = {0, 0};          // linke obere Ecke des UV-Ausschnitts
        public boolean mirror = false;       // horizontal gespiegeltes UV (fuer die rechte Koerperhaelfte)
    }
}
