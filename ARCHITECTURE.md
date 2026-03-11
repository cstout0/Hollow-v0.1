# Architecture Notes

Rough overview of how everything fits together. Written for myself mostly.

## Package layout

```
noctfield/
  Main.java           -- entry point, just boots GameApp
  core/
    GameApp.java      -- game loop, input handling, all the game logic glue
    InputState.java   -- keyboard/mouse state snapshot per frame
    WorldProfile.java -- world name, seed, metadata
  render/
    Renderer.java     -- the big one. entities, terrain, UI, post-fx, everything visible
    SimpleShader.java -- GLSL shader wrapper (uniforms, compile, bind)
    TerrainAtlas.java -- texture atlas UV lookup for block faces
    StreamMesh.java   -- resizable GPU mesh for dynamic geometry
    GpuMesh.java      -- static GPU mesh
    TextureLoader.java
    ObjMesh.java / FbxLoader.java -- model loading (used for NUN and THING entities)
  world/
    ChunkGenerator.java   -- terrain gen, biomes, caves, structures
    VoxelChunk.java       -- raw block storage
    MeshedChunk.java      -- chunk + its GPU mesh
    TerrainMesher.java    -- greedy mesh builder
    AsyncChunkPipeline.java -- loads/unloads chunks on worker threads
    FreeCamera.java       -- player camera + physics (movement, collision, gravity)
    Raycast.java          -- block picking
    BlockId.java          -- block type definitions and properties
  audio/
    AudioSystem.java      -- OpenAL wrapper, procedural SFX generation, ambient layering
    AudioFileLoader.java  -- WAV/OGG file loading via javax + STBVorbis
  debug/
    DebugHud.java         -- F3 overlay
```

## How a frame works

GameApp runs the loop. Each tick:
1. Poll input
2. Update camera/physics
3. Tick game state (AI, events, timers)
4. AsyncChunkPipeline uploads any completed meshes
5. Renderer draws everything
6. Swap buffers

Chunk gen and meshing happen on background threads. Main thread just picks up finished results each frame with a budget cap so it doesn't stall.

## Rendering

Single-pass terrain shader with Reinhard tonemapping. Fog is distance-based and color-shifts based on time of day / zone / horror state. Lighting is a combination of ambient + directional sun + per-entity lamp contributions. Lamps are collected each frame within a radius and passed as uniforms.

Emissive objects (crystals, fire) use a boosted ambient trick so they stay bright after tonemapping.

## World generation

Layered noise. Biome determined by combination of moisture + temperature noise. Caves carve out below terrain using a separate noise pass. Structures (ruins, dungeon rooms, relic monuments, void gate) placed deterministically based on chunk-local hash.

## Zones

Zone 0 is the overworld. Zone 1+ are liminal spaces loaded separately — self-contained room layouts with their own geometry, lighting, and enemy spawns. Zone transitions show a loading screen and swap the active geometry.

## Audio

Procedural SFX for footsteps, mining, entity sounds — generated at runtime with sine/noise synthesis and convolution reverb. External ambient files (klankbeeld CC packs) layer in as horror level rises. Two channels: a main ambient crossfader and an event overlay for things like blood rain and dead fog.

## Known messiness

- Renderer.java is huge. It started as a quick prototype and kept growing. Should probably split entity logic out.
- Some Mojibake in string literals in Renderer.java from an encoding issue — left alone since it works.
- AI state machines are inline inner classes in Renderer. Not ideal but it worked.
