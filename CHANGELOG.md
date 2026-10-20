# Changelog

## 1.0.1

- New config defaults: only the open key is on (`openKeyAnywhere`); skin zones and skin blocks start off.
- `blocks.skinStation` is gone: the Skin Station now follows `blocks.enabled`.
- `maxOutfits` defaults to `-1` (unlimited).
- `figura.enabled` and `plasmoVoice.enabled` are gone: Figura and talking skins turn on when those mods are installed, and their buttons only show then.

## 1.0.0

First release, for Minecraft 1.20.1 (branch `main`) and 1.21.1 / 1.21.4 (branch `1.21.x`), Fabric and Forge.

- HD skins up to 2048 px, a wardrobe of outfits, wide or slim arms.
- Skin Station block, any listed block, and skin zones open the wardrobe.
- `config/fishskin.json` with comments; the server's settings are used in multiplayer.
- Figura avatars per outfit, with a searchable picker that shows each avatar's colour.
- Plasmo Voice talking skins that follow your actual voice level.
- Skins stay in memory only, on the server and on clients.
