# FishSkin

**HD skins, an in-game wardrobe, skin blocks and skin zones — with optional Figura and Plasmo Voice support.**

> 🤖 **This mod was made 100% with AI (Claude by Anthropic).** All code, textures and text were written by AI,
> directed by m-catismeow.

## Features

- 🖼️ **HD skins** — square PNG skins of 64 up to **2048 px**, wide or slim arms.
- 👗 **Wardrobe** — press **K** to open it. Keep several outfits, drop PNG files onto the window to add them,
  turn the 3D preview with the mouse.
- 🧱 **Skin blocks** — right-click the *Skin Station*, or any block you choose (a loom by default), to change your skin.
- 📍 **Skin zones** — mark an area with a *Skin Zone* block; inside it a message shows, or the wardrobe opens by itself.
- 🎭 **Figura** *(optional)* — each outfit can carry a Figura avatar. The picker shows every avatar with its own
  colour mark and has a search box.
- 🎙️ **Plasmo Voice** *(optional)* — a second "talking" skin that shows only while you actually speak.
- 🔒 **Private** — skins are never saved to disk, on the server or on other players' PCs.

## Configuration

`config/fishskin.json` is written on first start, with every option explained inside. In multiplayer the
**server's** settings decide who can open the wardrobe where:

- `openKeyAnywhere` — the open key works everywhere, or only in zones / at skin blocks
- `zones.enabled`, `zones.openOnEnter` — skin zones, and whether entering one opens the wardrobe
- `blocks.enabled`, `blocks.skinStation`, `blocks.list` — which blocks open the wardrobe
- `maxOutfits`, `maxSkinSize` — limits per player
- `figura.enabled`, `plasmoVoice.enabled`, `plasmoVoice.minLevelDb`, `plasmoVoice.holdMs` — your own options

## Install

Put the jar for your loader and Minecraft version in `mods`. **Fabric** also needs Fabric API.
For multiplayer, install FishSkin on the server **and** on every player's game.

## Supported versions

| Minecraft | Fabric | Forge |
|---|---|---|
| 1.20.1 | ✅ | ✅ |
| 1.21.1 | ✅ | ✅ |
| 1.21.4 | ✅ | ✅ |

## Links

- Source code and full config reference: https://github.com/m-catismeow/FishSkin
- Bug reports: https://github.com/m-catismeow/FishSkin/issues

License: zlib — use it for anything, no warranty.
