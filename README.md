[Deutsch](README.de.md) | **English**

# Cosmetics Bridge LabyCos

> **Notice:** This project is **not** affiliated with LabyMod / LabyMedia GmbH
> and has **no** connection to them. It is a private, unofficial community
> project that is merely **compatible** with the publicly accessible LabyMod
> interfaces. "LabyMod" is a trademark of LabyMedia GmbH; it is mentioned
> solely to describe compatibility.

A client-side mod for Minecraft, currently compatible with NeoForge 1.21.1.
This mod displays certain cosmetics in-game by querying publicly accessible
interfaces.

## Features

- **Capes / Cloaks** – with animations
- **Wings** – fully dynamic: geometry, texture, animation and coloring are
  loaded at runtime. Including:
  - wing-flap animation with dynamic acceleration
  - per-feather-row coloring based on the worn colors
  - correct positioning even while sneaking
  - works for differently structured wing models

Currently only the local player's cosmetics are displayed.

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

In development. Capes and wings are working; additional cosmetic types and
displaying other players are possible next steps.
