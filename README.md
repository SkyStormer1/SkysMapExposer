<p align="center"><img src="src/main/resources/assets/skysmapexposer/icon.png" width="128" alt="Sky's Map Exposer icon"></p>

# Sky's Map Exposer

A client-side Fabric mod for Minecraft 26.2 that brings a server's **BlueMap** into **Xaero's
World Map** and **Xaero's Minimap**.

**[Download the latest version](https://github.com/SkyStormer1/SkysMapExposer/releases/latest)**:
put the `.jar` in your `mods` folder.

- **Terrain:** BlueMap's map fills in wherever yours is missing, including gaps inside areas you
  explored, or older than 7 days (when BlueMap's picture is newer), or from before a date you set,
  for a map left over from a previous season.
- **Borders and zones:** BlueMap's outlines, such as the world border, are drawn on both maps in
  their own colours. They update when the server changes them.
- **Markers:** shops, banners and other BlueMap markers show as icons on the world map and minimap
  only, never in the world. Hover one for its name; right-click it on the world map and choose
  **Save as waypoint** to make it an ordinary, permanent Xaero waypoint.
- **Players:** everyone BlueMap reports is shown on the world map of the dimension they are in,
  with their face, and moves as they do.
- **Live:** tiles on screen are re-checked every minute, markers every 30 seconds, players every 2.

Xaero's saved map files are never changed: remove the mod and your map is exactly as it was.
When you go back to a backfilled area, Xaero maps it again and your own map takes over.

## Requirements

- Minecraft 26.2, Fabric Loader 0.19.3+, Fabric API, Fabric Language Kotlin
- Xaero's World Map 1.44 or newer (hook points checked in 1.44.2 and 1.46.1)
- Optional: Xaero's Minimap (26.4+) for the minimap overlay, minimap markers and saving waypoints
- Optional: Mod Menu, for the settings screen

## Settings

Nothing is set up at first. Join a server with a BlueMap, open the settings from Mod Menu (the cog
next to Sky's Map Exposer), type the BlueMap address (the page you open in a browser) and press
**Find maps**. The settings open on the server you are connected to.

| Setting | What it does |
|:--|:--|
| Terrain / Markers / Borders / Players | Switch each part on or off |
| Replace after (days) | How old your map must be before BlueMap may replace it |
| Addresses | Every name you reach the server by |
| BlueMap address | The BlueMap page you open in a browser |
| Overworld / Nether / End map | Which BlueMap map to use; the switch beside each turns its **terrain** on or off. Borders, markers and players still show with terrain off. |
| Cover overworld before | Your overworld map from before this date and time is covered by BlueMap regardless of age. **Now** fills in the current time. Blank to turn it off. |
| Find maps | Asks the BlueMap which maps it has |

Many servers render the nether's BlueMap from the roof, which is not what Xaero's nether map shows,
so a new server has its nether terrain off. Its world border, markers and players are still shown.

Everything is also in `config/skysmapexposer.json`. `/mapexposer` in chat shows a status line.

## How "old" is decided

- **Your map:** from the moment the mod is installed it records when each chunk was last loaded,
  which is when Xaero last redrew it. For areas mapped before that, the date on Xaero's region file
  (512×512 blocks) is used instead.
- **BlueMap's map:** BlueMap does not publish when a tile was rendered, so each tile is dated by the
  first time the mod saw its current contents.

## Privacy

Everything stays on your computer. The mod only downloads from the BlueMap address you enter; it
sends nothing to the Minecraft server or anywhere else. Map data, dates and downloaded tiles are
kept in `skysmapexposer/` in your game folder.

## Building

```
./gradlew build
```

The jar is in `build/libs/`. The live-server tests run only with `BLUEMAP_URL` set to a BlueMap
address that has a map called `world`.

## Limits

- Surface only: nothing is drawn in Xaero's cave mode.
- Terrain is always drawn from BlueMap's full-resolution tiles and decided per chunk, so zooming only
  changes sharpness. Zoomed out, tiles are averaged down to match Xaero's zoom.
- Gaps inside explored areas are found by asking Xaero, which can only answer while it has that
  region open. Once seen, a gap is remembered for the session.
- A waypoint is saved to the dimension you are standing in, so the world map must be showing it.

## Originality

Written from scratch; no code was copied from any other project.

- [MapLink](https://github.com/thebuildcraft/MapLink) (GPL-3.0) does similar things. It was looked
  at only to see what it does, not how.
- BlueMap (MIT) is used only as a web server: its tile, marker and player files were checked
  against a live server.
- Xaero's World Map and Minimap are closed source. They are compiled against, never bundled, and
  only their public method, field and local-variable names were read, to place the mixins and use
  their element and waypoint systems.

## License

MIT © 2026 SkyStormer1
