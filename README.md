# Laby Cosmetics Bridge – Fabric 26.3

Variante des 26.1-Ports für **Minecraft 26.3**. Der Code ist bis auf die unten aufgeführten Stellen identisch mit `labycosbri-fabric` (26.1.x).

## Bauen und testen

Voraussetzungen: **JDK 25**. Wenn du IntelliJ nutzt, brauchst du 2025.3 oder neuer.

```
./gradlew build          # Jar liegt danach in build/libs/
./gradlew runClient      # Dev-Client mit Testmodus (Cosmetic 1369)
```

Zum Installieren brauchst du **Fabric Loader 0.19.3+** und die **Fabric API für 26.3** (0.166.2+26.3 oder neuer).

Der Testmodus (`-Dlabycosbri.testIds=1369` in `build.gradle`) gilt nur für `runClient`. Im gebauten Jar ist er aus. Wenn du mit deinem echten Account auf einem Server testen willst, nimm das gebaute Jar.

## Neu nach dem Katalog-Scan (Halo & Co.)

- **Kategorien `HEADGEAR` und `FACE`**: Der Halo (ID 9) ist `HEADGEAR`, nicht `HAT`, und wurde deshalb übersprungen.
- **Pose nach `attached_to` statt nach Kategorie**: `HEAD` bekommt die Kopf-Pose, `BODY` die Körper-Pose. `ARM` und `LEG` werden vorerst übersprungen.
- **Daten nach `options` lesen** (`CosmeticData`): Das d-Array folgt der Reihenfolge in `options`. Bisher wurde ein Offset-String wie `"0.0;0.0;0.0"` als Farbe gelesen (betrifft 113 Cosmetics), und bei `shoulder_side`/`side` war d[0] gar nicht die Textur.
- **Offset-Option** (z. B. Halo-Höhe) wird angewendet. *Annahme:* Einheit Pixel, +y = oben. Kalibrierung in `CosmeticRenderLayer`.

Noch offen (siehe Chat):
- `headgravity_*`-Bones (Halo bleibt waagerecht, wenn man nach unten schaut) sind nicht umgesetzt.
- Die Regel "Wurzel-glow zählt nicht" steht in Frage: 27 Cosmetics haben einen glow-Bone als Wurzel, darunter der Halo, Fire Arms und Lightning Cloak.

## Unterschiede zu 26.1

| Stelle | 26.1 | 26.3 |
|---|---|---|
| Licht (`AvatarRendererMixin`) | `LevelRenderer.getLightCoords(...)` | `LightCoordsUtil.getLightCoords(...)` (seit 26.2) |
| Drehen (`CosmeticRenderLayer`) | `poseStack.mulPose(Axis.XP.rotationDegrees(x))` | `poseStack.rotateDegrees(Axis.XP, x)` |
| `stackOf()` | `stack.mulPose(pose.pose())` | `stack.last().pose().set(pose.pose())` |
| `FlatNormalConsumer` | – | zusätzlich `setUv3(float, float)` (neu und abstrakt in 26.3) |
| Versionen | Loom 1.15, Loader 0.18.4, Gradle 9.4 | Loom 1.17, Loader 0.19.5, Gradle 9.6 |

## Wie geprüft

Wie bei 26.1 konnte ich **nicht gegen das echte Minecraft kompilieren**, weil die Fabric- und Mojang-Server von meiner Umgebung aus gesperrt sind. Geprüft habe ich gegen 26.3-Signatur-Stubs. Die Quellen dafür:
- Fabric API (Branch 26.3): Layer-Callback, Render-State-Data, `extractRenderState`-Signatur, `rotate(Axis, …)`, `LightCoordsUtil.getLightCoords`
- NeoForge 26.3.x: `VertexConsumerWrapper` (Methodensatz inkl. `setUv3`), `rotateDegrees`, `pose.pose()` als `Matrix4f`
- Capes-Mod (26.3): `DynamicTexture`, `TextureManager.register`, `PlayerSkin`, `ClientAsset.ResourceTexture` unverändert
- NeoForge-Migration-Primer 26.2 und 26.3

Die Stub-Version kompiliert ohne Warnungen, und die Logiktests (Retry, Glow-Erkennung, Farben, Cape-Format) bestehen.

## Falls `./gradlew build` Fehler meldet

Am wahrscheinlichsten an diesen Stellen:
1. **`RenderTypes.entityTranslucentEmissive(texture)`** in `CosmeticRenderLayer`: 26.3 hat die Variante mit zwei Parametern entfernt. Wenn auch die mit einem Parameter fehlt, als Ersatz `RenderTypes.eyes(texture)` testen. Der Glow blutet dann evtl. etwas über die Form hinaus.
2. **`ModelPart#render(PoseStack, VertexConsumer, int, int, int)`**: In den Primern steht keine Änderung, aber 26.2 hat `Model#renderToBuffer` entfernt. Wenn die Methode fehlt, schick mir die Fehlermeldung.
3. **`AbstractClientPlayerMixin`**: Falls `getSkin` dort nicht deklariert ist (Fehler erst beim Start, nicht beim Kompilieren), auf `PlayerInfo#getSkin` umziehen.

## Bitte im Spiel prüfen

- Starten ohne Absturz, Log-Zeile `[LabyCosmetics] Client initialisiert.`
- Testmodus: Cosmetic 1369 (Rainbow + Glow) erscheint am Spieler und animiert
- Wings, Hüte, Auren: Sitz im Stehen und beim **Sneaken** (Kalibrierwerte stammen aus 1.21.1)
- LabyMod-Cape: richtig herum, weht, verschwindet mit Elytra
- **"Verbesserte Transparenz" (OIT)** in den Grafikeinstellungen ein- und ausschalten. Das ist neu in 26.3 und betrifft durchscheinende Geometrie wie die Cosmetics.
- Nachts oder in einer Höhle: Glow leuchtet, der Rest wird dunkler
