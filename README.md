# Hollow

A first-person horror game built from scratch in Java using LWJGL. No engine. Just raw OpenGL, a chunk system, and too many late nights.

You wake up in a procedurally generated world. Collect relics. Survive. Escape.

---

## What it is

Hollow is a voxel survival-horror game I've been building as a personal project. The goal was to learn low-level graphics programming and end up with something actually playable and scary. It's gotten pretty far.

Features so far:
- Procedural terrain with biomes (pine forest, dead wastelands, swamp)
- Cave systems with water, crystals, and darkness mechanics
- Dynamic lighting — torches actually matter underground
- Multiple enemies with chase/patrol/screamer AI
- Inventory, crafting, food, tools
- Multi-zone liminal spaces (Zone 2 is a 3-storey manor)
- Ambient audio system with horror soundscape layering
- Relic hunt objective with a void gate ending

## Stack

- Java 21
- LWJGL 3.3.3 (OpenGL, OpenAL, STB)
- JOML for math
- Gradle build

No game engine. Everything from the window creation to the terrain mesher is written by hand.

## Building

```
./gradlew shadowJar
java -jar build/libs/hollow.jar
```

Requires Java 21+.

## Controls

| Key | Action |
|-----|--------|
| WASD | Move |
| Mouse | Look |
| Left click | Break block / attack |
| Right click | Place block / interact |
| 1–9 | Hotbar |
| E | Inventory |
| F3 | Debug overlay |
| F11 | Fullscreen |
| ESC | Pause |

## Notes

- Audio files not included in this repo (too large). The game uses procedurally generated sound effects for most things; external ambient files are loaded from an `audio/` folder if present.
- World saves are local and not tracked.
- This is a solo project — code quality varies wildly depending on how late it was when I wrote it.
