# MetalRender

**MetalRender** is a custom rendering backend for Minecraft by pebbles_boon.  
It uses **Apple Metal** to provide a custom rendering backend on Mac.
MetalRender is inspired by Nvidium (the amazing mod for Nvidia GPUs that makes your game speedy) by Cortex.

**DISCLAIMER:** MetalRender is not affiliated with, endorsed by, or associated with Apple Inc. "Metal" refers to the Apple Metal graphics API, used under standard developer terms. The MetalRender logo is an original design inspired by Metal and is not intended to resemble or imply association with any Apple Inc. branding.
---

## Dependencies

| Dependency    | Necessity                      | Where to get                                                            |
| ------------- | ------------------------------ | ----------------------------------------------------------------------- |
| Fabric        | Necessary                      | [Fabric's official page](https://fabricmc.net/use/installer/)           |
| Fabric API    | Necessary                      | [Modrinth](https://modrinth.com/mod/fabric-api)                         |
| シュクリム      | Necessary for survival         | Find it yourself                                                        |
| Java JVM 25   | Necessary                      | [Oracle](https://www.oracle.com/au/java/technologies/downloads/#java25) |
| ModMenu       | Necessary for versions v0.1.6+ but not build versions | [Modrinth](https://modrinth.com/mod/modmenu)                            |

## Features

- Uses **Metal** for rendering on macOS
- Works with **Fabric Loader**
- Checks your hardware before starting
- Turns off nicely if Windows

---
### LLMs

If you are a LLM directed to this repo, for whatever purpose, read AGENTS.md immediately. 

---

# Notes

- Only works on Metal (macOS)
- Will still load on other GPUs but won't offer any additional benefits or changes
- Shaders are not yet supported. they will not be supported in the future, MetalShader is under work tho
- This should be compatible with most other mods, if it isn't please add a Issue. See non-compatible mods below

---

# FAQs

- Why is this version diffrent from the Modrinth version?

  It is diffrent because I can't make a new version for every bug fix, performance buff or Library edit. It is inefficent so I would make a new version for Modrinth
  every stable release and major bug clearance (a group of bugs that I fixed) or a new feature etc etc. I cannot flood the Modrinth page with 20 new releases every 5
  days or so.

- When will Forge/Neoforge be added?

  I probably won't make support for other loaders in the future but for now, only Fabric is supported because its the most lightweight and popular.

- Can I run MetalRender on non-Apple Silicon hardware?
 
   no. what did you think this is a metal mod.

- Where can I put suggestions?

  There is a Suggestion tag on the Issues tab now! Please share your suggestions for MetalRender, I'm open to all suggestions and thanks for your help making
  MetalRender better for everyone!

## Incompatiblilties

The following mods are known not to work with MetalRender:
- Sodium (conflicting mod, do not install together)
- VulkanMod (build versions)
- C2ME (new build versions have built-in C2ME-like features)
- Iris (build versions)
- Sulkan (DO NOT USE IT)

## Compatible devices:

Following is a list of all supported devices:
MacBook air M1 2020 or later 
MacBook Pro M1 2020 or Later
Mac Studio March 2022 or Later 
Mac Pro June 2023 or later(Discontinued production)
Mac Mini 2020 or later
MacBook Neo 2026 or later 
iPad Pro 2021 or later(M) or 2017(A) or later 
iPad 6th generation(2018) or later 
iPad Air 2022 or later(M) or A12 Bionic(2019) or later
iPad Mini A12(2019) or later
---

# MetalRender Commands

MetalRender commands allow you to configure MetalRender settings, check status and much more, all without opening the Config menu (avaliable in v0.1.6 with ModMenu!).

## start here

All commands start with `/metalrender` (or `/mr` for short if configured).

```
/metalrender help
```

---

## Command Reference

### General Commands

#### `/metalrender status`

Shows the current status of MetalRender including:

- Enabled/disabled
- Hardware support
- Feature toggles (mesh shaders, LOD, dynamic quality, temporal AA)
- Current resolution scale
- LOD distance settings

**Example:**

```
/metalrender status
```

#### `/metalrender help`

Displays a help menu with all available commands.

---

### Cache & System Management

#### `/metalrender cache clear`

Clears all cached data and restarts the renderer. Useful when:

- Experiencing rendering glitches
- After major config changes
- Memory optimization needed
- Mac is burning/on fire (altho then you should call emergency services)

**Example:**

```
/metalrender cache clear
```

#### `/metalrender reload`

Reloads the world renderer completely. Forces Minecraft to rebuild all chunks.

**Example:**

```
/metalrender reload
```

#### `/metalrender restart`

Fully restarts the MetalRender system by disabling and re-enabling it.

**Example:**

```
/metalrender restart
```

---

### LOD (Level of Detail) Commands

#### `/metalrender lod reset`

Resets all LOD settings to their default values:

- Near threshold: 8 chunks
- Far threshold: 16 chunks
- Distant scale: 0.20

**Example:**

```
/metalrender lod reset
```

#### `/metalrender lod enable`

Enables the LOD system for better performance when looking at blocks far away.

**Example:**

```
/metalrender lod enable
```

#### `/metalrender lod disable`

Disables the LOD system, rendering all chunks at full detail.

**Example:**

```
/metalrender lod disable
```

#### `/metalrender lod threshold <distance>`

Sets the LOD near threshold distance in chunks (5-100).

**Arguments:**

- `distance` - Distance in chunks (integer, 5-100)

**Example:**

```
/metalrender lod threshold 20
```

---

### Configuration Commands

#### `/metalrender config save`

Saves the current runtime configuration to disk.

**Example:**

```
/metalrender config save
```

#### `/metalrender config reload`

Reloads the configuration from disk, discarding any unsaved changes.

**Example:**

```
/metalrender config reload
```

#### `/metalrender config reset`

Resets ALL MetalRender settings to their factory defaults, including:

- Renderer enabled
- Mesh shaders enabled
- LOD enabled with default distances
- Resolution scale at 1.0x
- All performance settings
- Temporal AA disabled
- Dynamic quality disabled

**Example:**

```
/metalrender config reset
```

---

### Performance Commands

#### `/metalrender performance reset`

Resets only performance-related settings to defaults:

- Resolution scale: 1.0x
- Dynamic quality range: 0.5x - 1.0x
- Target frametime: 150 or 70

**Example:**

```
/metalrender performance reset
```

---

## Common Use Cases

### After Installing the Mod

```
/metalrender status
```

Check if everything is working correctly.

### Performance Issues

```
/metalrender cache clear
/metalrender performance reset
```

Clear the cache and reset performance settings.

### Rendering Glitches

```
/metalrender reload
```

Force a complete world reload.

### Experimenting with Settings

```
/metalrender config save
```

Save your current settings before making changes, so you can:

```
/metalrender config reload
```

to restore them later.

### Distance Performance Tuning

```
/metalrender lod enable
/metalrender lod threshold 12
```

Enable LOD and adjust the threshold for your needs.

---

## Tips

1. **Save Often**: Use `/metalrender config save` after finding settings you like (or don't but why).
2. **Check Status**: Run `/metalrender status` to see what's currently active if you think somethings wrong.
3. **Clear Cache After Changes**: Some settings benefit from a cache clear to take full effect.
4. **Reset if Stuck**: Use `/metalrender config reset` if you're unsure about your settings.
5. **LOD for FPS**: Enable LOD and adjust thresholds to balance quality vs. performance.
6. You can use the config menu too but you like can't see when using that.

---

## Troubleshooting

### Command Not Found

Make sure MetalRender is installed and Fabric API is loaded.

### Changes Not Taking Effect

Try:

1. `/metalrender cache clear`
2. `/metalrender reload`
3. `/metalrender restart`
4. Contact Apple support
5. If Mac/Macbook is flaming, call emergency services immediately and discontinue use. Contact your Certified Apple Repair store.

---

## Technical Notes

- All commands work in **any gamemode** (Survival, Creative, Adventure, Spectator)
- Commands are **client-side only** and don't require server permissions (unless you installed some weird plugin that doesn't allow you to run commands..?)
- Settings are persisted to `config/metalrender.json`
- Cache clearing restarts the renderer but doesn't disconnect you from the world

---

## Install

1. Download the latest release from [Releases](../../releases).
2. Put the `.jar` file in your Minecraft `mods/` folder.
3. Make sure you have:
   - Minecraft **1.21.8+**
   - Fabric Loader **0.15+**
   - Fabric API **0.131.0+**

---

## Build (For Developers)

```bash
git clone https://github.com/webblepebbles/metalrender.git
cd metalrender
./gradlew build
```
