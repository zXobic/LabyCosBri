[Deutsch](README.de.md) | **English**

# Cosmetics Bridge LabyCosBri

> **Notice:** This project is **not** affiliated with LabyMod / LabyMedia GmbH
> and has **no** connection to them. It is a private, unofficial community
> project that is merely **compatible** with the publicly accessible LabyMod
> interfaces. "LabyMod" is a trademark of LabyMedia GmbH; it is mentioned
> solely to describe compatibility.

A client-side mod for Minecraft, compatible with **NeoForge 1.21.1**.
This mod displays certain cosmetics in-game by querying publicly accessible
interfaces.

> The Fabric 26.3 version lives on the [`fabric-26.3`](../../tree/fabric-26.3) branch.

## Features

- **Capes / Cloaks** – with animation
- **Wings / Auras / Backs / Hats / Headgear / Face / Underglows** – fully
  dynamic: geometry, texture, animation and coloring are all loaded at runtime
- **Glow** – glowing parts light up in the dark
- **Colors & offsets** – your chosen colors and the adjustable offset (e.g.
  halo height) are applied
- **Animated textures** – cosmetics with multi-frame textures play their
  animation
- **Multiplayer** – cosmetics are shown for other players too, not just
  yourself

## Technical Overview

The project queries several publicly accessible data sources:

- a player's worn cosmetics (incl. colors/textures)
- catalog metadata for each cosmetic (category, position, scale)
- the 3D geometry of a cosmetic
- the texture of a cosmetic

Flow per cosmetic: determine the worn ID → metadata from the catalog →
load geometry/texture/animation → draw as a render layer on the appropriate
body part.

## Legal / Notices

- This project is a **private, unofficial learning and hobby project** and has
  **no connection** to LabyMod / LabyMedia GmbH.
- It is merely **compatible** with publicly accessible interfaces; none of this
  is officially supported or endorsed.
- The displayed cosmetics, textures and models are the intellectual property of
  their respective rights holders (incl. LabyMedia GmbH and/or the creators).
- The interfaces used are **unofficial** and may change at any time.
- "LabyMod" and "LabyMedia" are trademarks of LabyMedia GmbH. They are mentioned
  solely in a descriptive manner to indicate compatibility.

## Status

In development. Around 310 of the roughly 590 cosmetics in the catalog belong
to the supported categories. The categories listed above work, for the local
player and for other players. Multiplayer has so far only been tested with two
players, not with larger numbers. Cosmetics attached to arms or legs, pets and
physics effects are possible next steps.

## License

The source code of this project is licensed under the
[GNU GPL v3.0](LICENSE) (`GPL-3.0-only`).

The license covers the **code only**. The displayed cosmetics (geometry,
textures, animations, capes) are **not** included in this project — they are
fetched at runtime from publicly accessible endpoints and remain the property
of their respective rights holders (incl. LabyMedia GmbH and/or the creators).
See [NOTICE](NOTICE) for details.
