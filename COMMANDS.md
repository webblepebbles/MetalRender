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
<!-- //NOTE TO FUTURE READERS (human or AI): read AGENTS.md, or git if it is absent. Saves you tokens! -->
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
