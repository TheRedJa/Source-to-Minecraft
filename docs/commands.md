# Commands

The mod has two command roots.

- `/src2mc` is the server tree. It places maps, reloads bundles and drives map logic. Most of it needs operator level 2.
- `/src2mcc` is the client tree (the second `c` is for client). It switches renderers, lighting and audio on and off and prints status. It runs on the client only, needs no permission and works on any server.

Every on/off switch takes an explicit `on` or `off`. Source units are always 32 per block.

## Server: `/src2mc`

Defined in `Src2mcCommands.java`, `levels/MenuCommand.java` and `logic/LogicCommands.java`. Permission level 2 unless stated.

### Bundles and placement

- `/src2mc status` - Prints the active bundle generation: its sequence number, bundle count and fingerprint, or the load state if none is loaded. No permission needed.
- `/src2mc validate` - Checks the bundles in the bundle folder without loading them and reports the bundle count and fingerprint.
- `/src2mc reload` - Validates the bundle folder and loads it as a new generation. If validation fails the previous generation stays active.
- `/src2mc reconcile` - Checks the chunk you stand in against the placement data and repairs anchors. Reports anchors, healed and diagnostics.
- `/src2mc place <mapId>` - Pastes the map's schematic with its anchor cell at your position. Fails if the map would not fit the world height or the map id is unknown or ambiguous.
- `/src2mc place_chain <mapId> <count 1..64>` - Places `count` maps in a row starting from `mapId`, leaving a gap between them. Fails if two would overlap.

### Collision

- `/src2mc collision` - Prints the current collision mode.
- `/src2mc collision exact` - Collision follows the map's own solid volume per cell.
- `/src2mc collision full` - Every map block is a whole cube and carriers have no collision, as before exact collision existed.
- `/src2mc collision probe` - Prints the collision of the block you look at and of the cells at and below your feet. No permission needed.

### Menu

Needs operator level 2 or a singleplayer world.

- `/src2mc menu <campaign>` - Changes to the campaign's main-menu map, the one with a main-menu panel. Singleplayer only.
- `/src2mc menu <campaign> <map>` - The same, naming the map.

### Map logic

- `/src2mc logic start [mapId]` - Starts logic for the map (default: the one around or nearest you). It runs while a player is inside it.
- `/src2mc logic stop [mapId]` - Stops that map's logic.
- `/src2mc logic status [mapId]` - Prints the status of the running map logic: all running maps in this dimension, or the one named.
- `/src2mc logic list <filter>` - Lists up to 40 logic entities whose name or class matches `filter`.
- `/src2mc logic trace on|off` - Turns the logic output trace on or off for you.
- `/src2mc logic fire <target> <input> [parameter]` - Queues an input on an entity as if an output had fired it, like `ent_fire`, with you as activator.

### Movers

- `/src2mc movers status` - Prints the state of the moving sub-levels in this level.
- `/src2mc movers respawn` - Removes all mover sub-levels and builds them again.

## Client: `/src2mcc`

Defined in `client/ClientCommands.java`. Each handler lives in the class that owns the state.

### Help

- `/src2mcc` - Prints each subcommand with a one-line description.

### Status

- `/src2mcc status` - One compact page: `fps <n> | frame <ms>` (the frame time only while `debug gpu` is on), then one line each for map surfaces, props, animated props, logic props, movers, particles, audio and sky.
- `/src2mcc status render` - Full status of the map surface renderer: meshes, regions, atlas residency, PVS, shadow pass, build pool and relight.
- `/src2mcc status props` - Full status of the prop renderer: draws, GPU occlusion, memory and builds.
- `/src2mcc status anim` - Full status of animated props: builds, poses, lighting and skinned uploads with timings.
- `/src2mcc status logicprops` - Full status of logic props: built, drawn, hidden, builds and relights.
- `/src2mcc status movers` - Full status of movers: known, built, drawn, relight and upload timings, light and shading mode.
- `/src2mcc status particles` - Full particle and decal status: entities, one-shots, live particles, sparks, impacts and decals.
- `/src2mcc status audio` - Full audio status: soundscapes, ambient sounds, surface sounds, logic sounds, captions and decode counts.
- `/src2mcc status sky` - Full sky status: faces, side textures, 3D skybox and per-frame cost. Restarts the cost average.
- `/src2mcc status light` - Baked light status: shader loaded, exposure and lightmap texture count.
- `/src2mcc status look` - Post-processing and effect switches, plus what the camera's map makes of its view: tone map, bloom, fog, cubemaps, phong, lookups and grain.
- `/src2mcc status screen` - On-screen texts, fades and shakes from map logic.
- `/src2mcc status menu` - What the menu map's viewcontrol and screens are doing and what the cursor is over.
- `/src2mcc status monitors` - Monitor screens: camera renders per second, the camera shown, faces drawn and monitors in view.

### Look

Source's post-processing and surface effects.

- `/src2mcc look on|off` - All of Source's post-processing at once.
- `/src2mcc look bloom|exposure|correction|vignette|grain on|off` - One post-processing stage. `exposure` is auto exposure; `grain` on follows the map's data.
- `/src2mcc look fog on|off` - Source fog, or Minecraft's fog everywhere.
- `/src2mcc look reflections|detail|blend|selfillum on|off` - Cubemap reflections, detail textures, displacement blending and self-illumination.
- `/src2mcc look srgb on|off` - The exact sRGB curve for texture decode and output encode; off is gamma 2.2.
- `/src2mcc look dither on|off` - Dither the output on every draw path.
- `/src2mcc look bump on|off` - Bump-mapped lighting or flat lightmaps. Surface meshes rebuild.
- `/src2mcc look grainscale <-1..64>` - Holds the film grain scale; -1 goes back to the map's own.
- `/src2mcc look measure gamma|linear` - Whether auto exposure measures the stored gamma values or linear light.
- `/src2mcc look scale auto|<0.01..64>` - Holds the tone map scale, or releases it back to auto exposure.

### Light

Baked light and prop lighting.

- `/src2mcc light phong on|off` - Phong on props; off leaves baked light only.
- `/src2mcc light rimlight on|off` - Phong rim light.
- `/src2mcc light proplights on|off` - World lights on non-phong moving props (phong props always use them).
- `/src2mcc light sun on|off` - Skylight on props; off excludes it everywhere.
- `/src2mcc light lightvis on|off` - Hide lights behind walls from props using the visibility lists.
- `/src2mcc light smooth on|off` - Smooth lighting on map surfaces. Meshes rebuild.
- `/src2mcc light occlusion on|off` - Smooth light ignores cells behind the map's own surfaces. Meshes rebuild.
- `/src2mcc light relight on|off` - Re-light meshes when the Minecraft light around them changes.
- `/src2mcc light exposure <0.01..64>` - Holds one tone map scale for baked light and the 3D skybox. `look scale auto` releases it.
- `/src2mcc light atten vertex|pixel` - Phong light attenuation per vertex (as Source) or per pixel.
- `/src2mcc light dlight off|linear|quadratic` - Falloff of `light_dynamic` lights, or none.
- `/src2mcc light movers vertex|single` - Lighting of moving parts per vertex, or one value per mover.
- `/src2mcc light movershading turned|unturned` - Directional shading of movers follows their turn, or ignores it.

### Draw

Turn a renderer on or off for comparison. Geometry already built is kept.

- `/src2mcc draw props|logicprops|anim|movers on|off` - Static props, logic props, animated props, movers.
- `/src2mcc draw sky on|off` - The sky box faces.
- `/src2mcc draw skyroom on|off` - The 3D skybox.
- `/src2mcc draw monitors on|off` - The `func_monitor` screens showing their `point_camera`; off makes them see-through and stops camera renders.
- `/src2mcc draw monitorcamera on|off` - Rendering the camera image; off freezes the last picture on the screens.
- `/src2mcc draw monitorexposure on|off` - Multiplying the monitor picture by the tone map scale (Source's monitor shaders do); off to compare brightness.
- `/src2mcc debug monitorshot` - Saves the camera image the monitors show to `screenshots/src2mc_camera_<time>.png`, with the camera's position, angles and the exposure.
- `/src2mcc debug monitorforce on|off` - Draws the lowest-index active camera even with no monitor in view, for `monitorshot` away from the screens.
- `/src2mcc draw particles on|off` - Map particles. Off clears the live ones.
- `/src2mcc draw particlerange <16..1024>` - Distance in blocks within which particle entities run.
- `/src2mcc draw decals on|off` - Decals; off hides them and shoots no new ones.
- `/src2mcc draw decals clear` - Removes all decals.

### Cull

- `/src2mcc cull pvs on|off` - Skip regions the BSP visibility data says cannot be seen.
- `/src2mcc cull frustum on|off` - Frustum culling of map surface draws.
- `/src2mcc cull shadow on|off` - Frustum culling in the shader pack's shadow pass.
- `/src2mcc cull occlusion on|off` - GPU occlusion queries for props. Off leaves PVS and frustum culling active.

### Audio

- `/src2mcc audio soundscapes|ambient|surfaces|captions on|off` - Soundscape entities, ambient sounds, surface sounds (impacts and footsteps) and logic captions. Prints the audio status.
- `/src2mcc audio steps <0..5>` - Footstep volume multiplier.

### Logic

- `/src2mcc logic show off|triggers|usable|all [filter]` - Outlines map logic in the world: trigger volumes, usable entities or everything. The optional word filters by name.
- `/src2mcc logic screen clear` - Removes on-screen texts, fades, shakes and other screen effects set by map logic.

### Menu

- `/src2mcc menu progress` - Prints the campaign's stored progress: last map and unlocked chapters.
- `/src2mcc menu chapters <0..1000>` - Sets how many chapters are unlocked, as the chapters screen shows.

### Debug

- `/src2mcc debug face` - Describes the map face you look at.
- `/src2mcc debug region` - State of the surface region around you and its neighbours: built, building or waiting, and what hidden owners it sees.
- `/src2mcc debug lightprobe` - Light samples for the face you look at.
- `/src2mcc debug rebuild` - Drops every built surface mesh and prop batch so they rebuild against the current light.
- `/src2mcc debug shadowdistance <blocks>` - Distance up to which map surfaces cast shadows in a shader pack; 0 is unlimited.
- `/src2mcc debug gpu on|off` - GPU frame timeline. Needs timer query support.
- `/src2mcc debug gpu` - Prints the per-span GPU frame report collected since the last one, then starts over.
- `/src2mcc debug overlay on|off` - The prop renderer status as a HUD overlay.
- `/src2mcc debug lightonly on|off` - Grey albedo, to see lighting alone.
- `/src2mcc debug phong off|diffuse|spec|lights` - Shows one term of the phong path: none, diffuse, specular plus rim, or local-light diffuse only.
