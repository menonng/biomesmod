# The Uyuni mirror: `biomesmod:salt_mirror`

In the wet season a few centimetres of still water lie on Salar de Uyuni and the flat turns into a mirror: towards the
horizon the sky and the ground can hardly be told apart. This page describes how the mod reproduces that, what it
depends on, and what it does not do.

## What is in the mod

* **Block** `biomesmod:salt_mirror` ("Salt Mirror"): a solid full block, not a fluid and not the vanilla water block.
  Top face: the mirror material. Sides and bottom: the salt crust texture. It never ripples, flows or freezes; you walk
  on it, as people walk on the flooded salar.
* **Terrain**: the `flatten` shaper's `film_block` setting paves the flooded part of the pan (about 90% of Uyuni) flush
  with this block; the dry patches keep `salt_crust` with their polygon ridges. Only `salar_de_uyuni_pan` sets
  `film_block`, so no other biome changes.
* **Config** `config/biomesmod.json` → `"uyuni": {"mirror": "block"}`. `"water"` restores the old one-block film of
  vanilla water for new chunks (server side, world generation only).

## The material (LabPBR 1.3)

| Map | Content |
|---|---|
| `salt_mirror.png` (albedo) | pale sky blue with the polygon seams of the crust showing faintly through: what you see without a shader pack |
| `salt_mirror_n.png` | almost flat normals, a trace of the seams (height amplitude 3% of a normal block texture): a calm surface that still carries fine salt detail in its reflection |
| `salt_mirror_s.png` | R smoothness 0.98 (roughness (1-s)² ≈ 0.0004), G F0 0.35 (dielectric), B porosity 0, A no emission |

**Fresnel.** With a dielectric F0 the shader pack's Fresnel term does the Uyuni look by itself: looking straight down
about a third of the light is reflected and the salt shows through; at the grazing angles of the far flat the
reflectance goes to 1 and the surface is a full mirror, which is where the horizon disappears. Real water has F0 0.02;
0.35 is a deliberate artistic lift so the mirror already reads at a few blocks' distance at Minecraft's eye height.

## Two ways to get a reflection, and why the mod uses the first

| | Screen-space reflections through the shader pack (LabPBR material) | Planar reflection rendered by the mod |
|---|---|---|
| How | The block only ships maps. The shader pack reads smoothness and F0 and traces the reflection in screen space, with its own sky/cloud/sun reflection as fallback where the ray leaves the screen | The mod renders the world a second time, mirrored at the pan's Y, into a texture and draws the block with its own shader sampling it |
| Quality | Sky, clouds, sun and everything on screen reflect. Things off screen (behind the camera, above the top edge) fall back to the pack's sky reflection; reflections fade at the screen borders | Geometrically exact for one plane, including off-screen terrain; no screen-edge fade |
| Stability when the camera moves | Screen-edge fade and disocclusion noise depend on the pack (temporal filtering helps); the sky part, which is nearly all of the picture on an empty salt flat, is stable | Stable |
| Cost | Whatever the pack's specular reflections cost; nothing without a pack | A second terrain + sky pass every frame while the block is visible: roughly doubles the world render cost |
| Works with Iris shader packs | Yes, that is what the maps are for | No: under Iris the pack's programs draw terrain; a mod shader on a terrain block is replaced by the pack's `gbuffers_terrain`, and the mirrored pass would itself have to run the pack's whole pipeline |
| Works without Iris | No reflection (plain texture) | Possible in principle |
| Difficulty | Low: textures and one block | Very high: a second level render in 26.2's render pipeline, clip plane, Sodium chunk rendering and (optionally) Voxy LODs, per Minecraft version |

**Decision: screen-space reflections through the LabPBR material.** The goal is the look under Iris shader packs, and
there a planar pass cannot be combined with the pack's pipeline; without Iris the requirement is only that the block
renders with its normal texture. The planar route is left as a possible, separate, vanilla-only feature (see
`NEXT_IMPROVEMENTS.md`). It is **not** implemented.

## What you need to see the mirror

* Iris (or another LabPBR-capable loader) and a shader pack that draws **specular reflections on terrain from resource
  pack materials**. Without a pack the block is its plain pale-blue texture.
* The pack's LabPBR reflections must be switched on. In **Bliss v2.1.2** they are **off by default**: enable
  *LabPBR → Specular Reflections*, *Screen Space Reflections* and *Sky Reflection* (in `shaderpacks/<pack>.txt`:
  `Specular_Reflections=true`, `Screen_Space_Reflections=true`, `Sky_reflection=true`).
* A shader pack decides by itself what it reflects. The mod cannot make a pack reflect clouds, the sun disc or terrain
  if the pack's reflection code does not.

### Recognising the block in a shader pack

The block has its own id, so a pack (or your edit of it) can treat it specially in `shaders/block.properties`, for
example to give it the pack's water-style reflection without the waves:

```properties
# example: put the salt mirror into an id of your own
block.10900 = biomesmod:salt_mirror
```

Iris reads `block.properties` from the shader pack only; a mod cannot add entries to it. The LabPBR maps need no such
entry.

## Verified / not verified

Checked in 0.6.0-beta.5 on Minecraft 26.2 with Sodium 0.9.2, Iris 1.11.4 and Bliss v2.1.2, 1920x1080, seed 0, by the
dev screenshot director (camera 3 and 12 blocks over the flat, 10:00 game time):

* **Seen:** with the three LabPBR options on, the clouds and the blue of the sky are mirrored in the flat and the
  surface is calm; with them off the block shows its plain texture. The gallery view of Salar de Uyuni is such a shot.
* **Frame rate:** 531 and 539 fps with the reflections on, 536 and 510 fps with them off (one sample per camera, an
  almost empty scene on the development machine). The difference is inside the noise of this measurement, so the cost
  could not be measured here; a scene with more geometry or a weaker GPU may differ.
* **Not checked:** stability while the camera moves (only still frames were taken), reflection of the sun disc and of
  terrain (the test scene has neither in view), other shader packs, Minecraft 1.21.1 with shaders (there only world
  generation was tested), and the look beyond the render distance with LOD mods.
* A grey haze band stays on the horizon in the test shots: that is the pack's distance fog, not the block.
