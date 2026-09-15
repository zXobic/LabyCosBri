**Deutsch** | [English](README.md)

# Cosmetics Bridge LabyCosBri

> **Hinweis:** Dieses Projekt ist **nicht** von LabyMod / LabyMedia GmbH und
> steht in **keiner** Verbindung zu ihnen. Es handelt sich um ein privates,
> inoffizielles Community-Projekt, das lediglich **kompatibel** mit den
> öffentlich erreichbaren LabyMod-Schnittstellen ist. "LabyMod" ist eine
> Marke der LabyMedia GmbH; die Nennung dient ausschließlich der Beschreibung
> der Kompatibilität.

Es handelt sich um eine clientseitige Mod für Minecraft. Aktuell kompatibel mit
NeoForge 1.21.1. Diese Mod stellt bestimmte Cosmetics im Spiel dar, indem sie
öffentlich erreichbare Schnittstellen abfragt.

## Features

- **Capes / Cloaks** – mit Animation
- **Flügel (Wings) / Aura / Tails / Hats** – vollständig dynamisch: Geometrie, Textur, Animation und
  Färbung werden zur Laufzeit geladen, inkl. FAnimation, Färbung
- **Mehrspieler** – Cosmetics werden auch für andere Spieler dargestellt,
  nicht nur für einen selbst (bisher mit zwei Spielern getestet)

## Technischer Aufbau

Das Projekt fragt mehrere öffentlich erreichbare Datenquellen ab:

- getragene Cosmetics eines Spielers (inkl. Farben/Texturen)
- Katalog-Metadaten zu jedem Cosmetic (Kategorie, Position, Skalierung)
- die 3D-Geometrie eines Cosmetics
- die Textur eines Cosmetics

Ablauf pro Cosmetic: getragene ID ermitteln → Metadaten aus dem Katalog →
Geometrie/Textur/Animation laden → als Render-Layer am passenden Körperteil
zeichnen.

## Rechtliches / Hinweise

- Dieses Projekt ist ein **privates, inoffizielles Lern- und Bastelprojekt**
  und steht in **keiner Verbindung** zu LabyMod / LabyMedia GmbH.
- Es ist lediglich **kompatibel** mit öffentlich erreichbaren Schnittstellen;
  es wird nichts davon offiziell unterstützt oder freigegeben.
- Die dargestellten Cosmetics, Texturen und Modelle sind geistiges Eigentum
  der jeweiligen Rechteinhaber (u. a. LabyMedia GmbH bzw. der Ersteller).
- Die genutzten Schnittstellen sind **inoffiziell** und können sich jederzeit
  ändern.
- "LabyMod" und "LabyMedia" sind Marken der LabyMedia GmbH. Ihre Nennung
  erfolgt ausschließlich beschreibend zur Angabe der Kompatibilität.

## Status

In Entwicklung. Die Oben genannten Cosmetics (Features) funktionieren, für den lokalen Spieler und für
andere Spieler. Der Mehrspieler-Betrieb wurde bisher nur mit zwei Spielern
getestet, nicht mit größeren Zahlen. Weitere Cosmetic-Typen sind ein möglicher
nächster Schritt.

## Lizenz

Der Quellcode dieses Projekts steht unter der
[GNU GPL v3.0](LICENSE) (`GPL-3.0-only`).

Die Lizenz gilt **nur für den Code**. Die dargestellten Cosmetics (Geometrie,
Texturen, Animationen, Capes) sind **nicht** Teil dieses Projekts — sie werden
zur Laufzeit von öffentlich erreichbaren Endpunkten geladen und bleiben
Eigentum der jeweiligen Rechteinhaber (u. a. LabyMedia GmbH bzw. der Ersteller).
Details in [NOTICE](NOTICE).
