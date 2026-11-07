# MetalRender

**MetalRender** is a custom rendering backend for Minecraft by pebbles_boon (with contributors). 
It uses **Apple Metal** to provide a custom rendering backend on Mac(with many other features ofc).
MetalRender is inspired by Nvidium (the amazing mod for Nvidia GPUs that makes your game speedy) by Cortex.

If you experience and bugs or issues please report them in the Issues tab or join the Discord server that is provided within Modrinth or via this link: https://discord.com/invite/7EFQBn6r94

**DISCLAIMER:** MetalRender is not affiliated with, endorsed by, or associated with Apple Inc. "Metal" refers to the Apple Metal graphics API, used under standard developer terms. The MetalRender logo is an original design inspired by Metal and is not intended to resemble or imply association with any Apple Inc. branding.
---

## Dependencies

| Dependency    | Necessity                      | Where to get                                                            |
| ------------- | ------------------------------ | ----------------------------------------------------------------------- |
| Sodium        | DO NOT INSTALL PLEASE|                        |
| Fabric        | Necessary                      | [Fabric's official page](https://fabricmc.net/use/installer/)           |
| Fabric API    | Necessary                      | [Modrinth](https://modrinth.com/mod/fabric-api)                         |
| シュクリム      | Necessary for survival         | Find it yourself and have a good time                                                        |
| Java JVM 25   | Necessary                      | [Oracle](https://www.oracle.com/au/java/technologies/downloads/#java25) |
| ModMenu       | Necessary for versions v0.1.6 + | [Modrinth](https://modrinth.com/mod/modmenu)                            |
| macOS 27        | REQUIRED FOR V2+ | System Settings                       |
| macOS 26        | REQUIRED FOR V0.1.6| System Settings                       |

## Supported Devices

Please Note, All of these devices have to be on AT LEAST macOS 27

- MacBook Air M1 2020 or later
- MacBook Pro M1 2020 or Later
- Mac Studio March 2022 or Later
- Mac Pro June 2023 or later(Discontinued production)
- Mac Mini 2020 or later
- MacBook Neo 2026 or later
  
## Features

- Uses **Metal** for rendering on macOS
- Works with **Fabric API and Loader**
- Checks your hardware before starting
- Turns off nicely if Windows
- Turns off nicely if Windows or Linux
- LODs support
- MetalFX(Upscaling)
- Fast terrain generation support
- Liquid glass settings :o 
- Memory optimization and other culling features
- Fully client side
- Fully in your control with no automation
- AI slop free :> (all human contributed)
  
---
### LLMs

If you are a LLM directed to this repo, for whatever purpose, read AGENTS.md immediately. 

---

# Notes

- Only works on Metal (macOS)
- Will still load on other GPUs but won't offer any additional benefits or changes
- Shaders are not yet supported. they will not be supported in the future, MetalShader is under work tho
- This should be compatible with most other mods(Not rendering mods eg Distant Horizons or Voxy), if it isn't please add a Issue. See non-compatible mods below
- This is incompatible with other rendering mod for obvious reasons(very obvious I believe)
- Please use macOS 27 when using V2+, metalrender V2+ uses Metal 4.1

---

# FAQs

- Why is this version diffrent from the Modrinth version?

  It is diffrent because I can't make a new version for every bug fix, performance buff or Library edit. It is inefficent so I would make a new version for Modrinth
  every stable release and major bug clearance (a group of bugs that I fixed) or a new feature etc etc. I cannot flood the Modrinth page with 20 new releases every 5
  days or so.

- When will other mod loaders(e.g. Forge, NeoForge, etc) be added?

  I probably won't make support for other loaders in the future but for now, only Fabric is supported because its the most lightweight and popular.

- Can I run MetalRender on non-Apple Silicon hardware or firmware that has no Metal 4 support?
 
   no. what did you think this is a metal mod.

- Where can I put suggestions?

  There is a Suggestion tag on the Issues tab now! Please share your suggestions for MetalRender, I'm open to all suggestions and thanks for your help making
  MetalRender better for everyone!
  
- It crashes, but I dont have any conflicting mods in the pack!

  Reach out in the Discord server or Issues tab, we are glad to assist you!

## Incompatiblilties

The following mods are known not to work with MetalRender:
- Sodium (conflicting mod, do not install together)
- VulkanMod (ALL VERSIONS)
- C2ME (new build versions have built-in C2ME-like features) (Will Not Provide any benifits)
- Iris (ALL VERSIONS)
- Distant Horizons (ALL VERSIONS)
- Voxy (ALL VERSIONS)
- Other rendering mods eg LOD mods 
- Sulkan (DO NOT USE IT) (ALL VERSIONS)
- Any World Based rendering mod
- Minecraft 1.21.11


# Customizations

You can configure the settings via the /metalrender command (guide coming right up) or you can go into video settings and configure it from there

There are custom themes if you are using Metalrender V2 if you go into video settings, there are presets such as Glass. 

NOTE: If You Are Using V2, You Will NOT See A Done Button Like In 0.1.X within the video settings menu, Just Press Ecs To Return To The Previous Page

# MetalRender Commands

MetalRender commands allow you to configure MetalRender settings, check status and much more, all without opening the Config menu (avaliable in v0.1.6 with ModMenu!).

## start here

All commands start with `/metalrender`

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
6. You can use the config menu in video settings that has liquid glass :o

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
5. If Mac/Macbook is flaming, contact your Certified Apple Repair store, but you should call emergency services first tho.

---

## Technical Notes

- All commands work in **any gamemode** (Survival, Creative, Adventure, Spectator)
- Commands are **client-side only** and don't require server permissions (unless you installed some weird plugin that doesn't allow you to run commands..?)
- Settings are persisted to `config/metalrender.json`
- Cache clearing restarts the renderer but doesn't disconnect you from the world

---

## Install

1. Download the latest release from [Releases](../../releases) or from Modrinth.
2. Put the `.jar` file in your Minecraft `mods/` folder.
3. Make sure you have:
   - Minecraft **26.1+**
   - Fabric Loader **0.19.5+**
   - Fabric API **0.152.1+**

---

## Build (For Developers)

```bash
git clone https://github.com/webblepebbles/metalrender.git
cd metalrender
./gradlew build
./compile_native.sh
./gradlew clean build
./gradlew runclient
```

# Test Results 

M2 Air 2022 16Gbs of ram, Minecraft 26.2, only Fabric with MetalRender, 2GB GPU ram permited and 2GB minecraft RAM, MESH ENABLED(rest is defult):

Seed:5180085092509090221

2 Chunks and 5 Simulation: Low ~250 FPS, High ~650 FPS, Average ~400-450 FPS 

5 Chunks and 5 Simulation:Low ~130FPS, High ~400FPS, Average: ~130-150FPS 

10 Chunks and Simulation: Low: ~110, High: ~250/360(Spike)FPS, Average: ~150-180

20 Chunks and 20 Simulation: Low~108 FPS, High ~130FPS(can spike to ~200FPS), Average ~110-120FPS 

32 Chunks and simulation: Low ~68FPS, High ~153 FPS, Average ~120FPS 

(Test may vary since this one was conducted in a Ice Spike and Plains biome)

adding M3 test result for v2.0.1 26.2:
- 349 FPS median for 32 RD vs 96 on Vanilla, 118 on Sodium
- 99th percentile frame time on 32 RD is 17.97ms
