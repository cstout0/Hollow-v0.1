@echo off
echo NOCTFIELD v2 runnable checkpoints:
echo.
echo   run_step.bat m1      ^(Milestone 1: window/input/camera/FPS title^)
echo   run_step.bat m2      ^(Milestone 2: chunk terrain renderer^)
echo   run_step.bat m3      ^(Milestone 3: frame-time HUD + culling + budgets^)
echo   run_step.bat m4      ^(Milestone 4: async chunk worker pipeline^)
echo   run_step.bat m5      ^(Milestone 5: mesher CPU reduction pass^)
echo   run_step.bat m6      ^(Milestone 6: true voxel chunks + cave carve pipeline^)
echo   run_step.bat m7      ^(Milestone 7: grounded collision + block raycast debug^)
echo   run_step.bat m8      ^(Milestone 8: break/place + remesh invalidation^)
echo   run_step.bat m9      ^(Milestone 9: save/load edit deltas + interaction polish^)
echo   run_step.bat m10     ^(Milestone 10: block selection without full hotbar UI^)
echo   run_step.bat m11     ^(Milestone 11: world profiles + per-world edit files^)
echo   run_step.bat m12     ^(Milestone 12: lightweight world-select overlay/menu^)
echo   run_step.bat m13     ^(Milestone 13: foundational lighting pass + day/night tuning^)
echo   run_step.bat m14     ^(Milestone 14: emissive lantern block + local light contribution^)
echo   run_step.bat m15     ^(Milestone 15: richer cave generation + live cave tuning^)
echo   run_step.bat m16     ^(Milestone 16: mesh safety + memory backpressure stability^)
echo   run_step.bat m17     ^(Milestone 17: health/fall damage + night hostility + inventory loop^)
echo   run_step.bat m18     ^(Milestone 18: fog watcher AI (distant observer, no approach)^)
echo   run_step.bat m19     ^(Milestone 19: watcher fear director (timed peek/vanish/reposition)^)
echo   run_step.bat m20     ^(Milestone 20: horror event scheduler with watcher event variety^)
echo   run_step.bat m21     ^(Milestone 21: screen distortion pass tied to horror events^)
echo   run_step.bat m22     ^(Milestone 22: traces + safe zones + cooldown governance + hidden sanity^)
echo   run_step.bat m23     ^(Milestone 23: sky cycle foundation - sun/moon billboards + halos^)
echo   run_step.bat m24     ^(Milestone 24: procedural clouds + stars + time-of-day sky tint^)
echo   run_step.bat m25     ^(Milestone 25: crafting (lantern/ward) + biome speed/sanity multipliers^)
echo   run_step.bat m26     ^(Milestone 26: biome visual blending + aggro multipliers per biome^)
echo   run_step.bat m27     ^(Milestone 27: fog auto-by-render-distance + fog user multiplier^)
echo   run_step.bat m28     ^(Milestone 28: sky cycle polish - cloud drift, moon/sun contrast, dark night^)
echo   run_step.bat m29     ^(Milestone 29: ESC pause menu + options menu + persistence^)
echo   run_step.bat m30     ^(Milestone 30: field-improv building - temp structures, decay, no-build zones^)
echo   run_step.bat m31     ^(Milestone 31: on-screen HUD - crosshair, HP bar, stability pips^)
echo   run_step.bat m32     ^(Milestone 32: entity visual polish - fog fade at distance + idle sway animation^)
echo   run_step.bat m33     ^(Milestone 33: OpenAL procedural audio - biome drone, heartbeat, stings, footsteps^)
echo   run_step.bat m34     ^(Milestone 34: sanity visual system - vignette, stalker pulse, dark flicker^)
echo   run_step.bat m35     ^(Milestone 35: underground relic objective - glowing RELIC block + proximity finder^)
echo   run_step.bat m36     ^(Milestone 36: relic escalation - AI pressure scales with relics found, convergence event^)
echo   run_step.bat m37     ^(Milestone 37: underground atmosphere - cave drone audio, ambient dim underground^)
echo   run_step.bat m38     ^(Milestone 38: sprint stamina - bar, exhaustion, breath SFX, biome drain penalty^)
echo   run_step.bat m39     ^(Milestone 39: weather events - rain thickens fog, lightning flash, thunder crack^)
echo   run_step.bat m40     ^(Milestone 40: journal fragments - JOURNAL block in caves, left-click reads horror text^)
echo   run_step.bat m41     ^(Milestone 41: THE THING - OBJ mesh entity, silhouette render, relentless approach AI; F key debug spawn^)
echo   run_step.bat m42     ^(Milestone 42: THE THING - vertex shader procedural walk animation; leg stride + body sway + head bob^)
echo   run_step.bat m43     ^(Milestone 43: THE THING - texture mapping + specular gloss + white eye glow; S-wave undulation anim fix^)
echo   run_step.bat m44     ^(Milestone 44: stalker visibility pass - halved engagement distances, fog cap 0.94^>0.55^)
echo   run_step.bat m45     ^(Milestone 45: firefly system + campfire block - ambient night lights, flee horror cue, flickering lamps^)
echo   run_step.bat m46     ^(Milestone 46: Will-o'-Wisps - predatory AI blue-white lights, IDLE/LURE/EVADE states, lure toward THE THING^)
echo   run_step.bat m47     ^(Milestone 47: longer day/night cycle - 300s day + 300s night, was 60s each^)
echo   run_step.bat m48     ^(Milestone 48: firefly light emission - nearest fireflies inject as warm point lamps, flicker-synced^)
echo   run_step.bat m49     ^(Milestone 49: environmental storytelling - BONES/BLOODSTAIN in DEAD biome surface, COBWEB in PINE canopy^)
echo   run_step.bat m50     ^(Milestone 50: underground dungeon rooms - vaults around relics + bone/lantern/journal rooms per 64x64 area^)
echo   run_step.bat m51     ^(Milestone 51: weather visuals - 150 rain drops, wind on fireflies+wisps, lightning illuminates 3D world^)
echo   run_step.bat m52     ^(Milestone 52: THE THING voxel humanoid - blocky cubes replace OBJ model, Y-lift walk anim, red glowing eyes^)
echo   run_step.bat m53     ^(Milestone 53: inventory expansion - 8-slot hotbar, scroll wheel cycling, CAMPFIRE/BONES/COBWEB save-load^)
echo   run_step.bat m54     ^(Milestone 54: The Figure - friendly wanderer morphs into monster at close range, voxel rotation fix^)
echo   run_step.bat m55     ^(Milestone 55: debug spawn keys - Q spawns The Figure, FIG status in title bar^)
echo   run_step.bat m56     ^(Milestone 56: DREAD_STALKER fix - XZ-only hit detection, overshoot guard, no camera-clipping^)
echo   run_step.bat m57     ^(Milestone 57: Q key spawns The Figure, FIG state in title bar^)
echo   run_step.bat m58     ^(Milestone 58: The Figure MONSTER fix - XZ-only hit detection, overshoot guard, force retreat after hit^)
echo   run_step.bat m59     ^(Milestone 59: Figure smoke burst on hit - despawn with dark purple puff particles, respawn after delay^)
echo   run_step.bat m60     ^(Milestone 60: DREAD_STALKER overhaul - proximity charge from behind, smoke+despawn on hit, 25s respawn^)
echo   run_step.bat m61     ^(Milestone 61: camera shake on hit - trauma system, FOV warp +6deg, positional jitter, 0.3s decay^)
echo   run_step.bat m62     ^(Milestone 62: title screen - NOCTFIELD logo, pulsing flicker, NEW GAME/CONTINUE/QUIT, world renders behind^)
echo   run_step.bat m63     ^(Milestone 63: graphics presets - MINIMAL/LOW/MEDIUM/HIGH in options menu with saved profile^)
echo   run_step.bat m64     ^(Milestone 64: inventory+crafting overhaul - backpack panel, recipe list, unlocks by relics, craft one/max^)
echo   run_step.bat m65     ^(Milestone 65: slot backpack v2 - drag/drop stacks, split stacks, categories + hand stack^)
echo   run_step.bat m66     ^(Milestone 66: inventory UX pass - mouse support + MC-style right-click place-one, high-contrast cleanup, rarer Figure spawns^)
echo   run_step.bat m67     ^(Milestone 67: inventory visual pass - block icons + hover tooltip, cleaner high-contrast MC-style slots^)
echo   run_step.bat m68     ^(Milestone 68: MC polish - count at slot corner, red missing ingredients, left-drag distribute stacks^)
echo   run_step.bat m69     ^(Milestone 69: inventory MC pass - hotbar row, shift-click quick move, 2x2 crafting grid + output, held-item cursor icon^)
echo   run_step.bat m70     ^(Milestone 70: inventory UI cleanup - removed recipes panel, 2x2 crafting-only flow, MC-style layout^)
echo   run_step.bat m71     ^(Milestone 71: inventory visual polish - MC-like light panel, beveled slots, tighter spacing, tooltip styling^)
echo   run_step.bat m72     ^(Milestone 72: inventory layout pass - 2x2 crafting moved top-right, rows fill leftÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢right, hotbar attached to inventory^)
echo   run_step.bat m73     ^(Milestone 73: inventory fit pass - denser MC-like spacing, 9-wide row layout, top-right craft input->output alignment^)
echo   run_step.bat m74     ^(Milestone 74: inventory geometry pass - 9-slot hotbar/inventory mapping, resized panel fit, clearer top-right craft alignment^)
echo   run_step.bat m75     ^(Milestone 75: readability pass - built-in craft arrow, clearer hint text, removed SEL/HAND clutter^)
echo   run_step.bat m76     ^(Milestone 76: inventory centering pass - moved top-left inventory block toward middle like Minecraft UI^)
echo   run_step.bat m77     ^(Milestone 77: inventory fill pass - dynamic slot sizing fills full panel width, hotbar/inventory span entire UI, crafting top-right^)
echo   run_step.bat m78     ^(Milestone 78: inventory bug fix - left-click pickup no longer leaves 1 item behind due to drag-distribute race condition^)
echo   run_step.bat m79     ^(Milestone 79: pickup fix v2 + larger crafting grid - pickupSlot marked after dragVisited reset; csz matches slotW^)
echo   run_step.bat m80     ^(Milestone 80: HUD hotbar visual match - redrawn as MC-style bevel slots with block icons to match inventory UI^)
echo   run_step.bat m81     ^(Milestone 81: HUD hotbar data fix - now reads slotItem/slotCount arrays directly instead of HOTBAR+inv() lookup^)
echo   run_step.bat m82     ^(Milestone 82: hotbar selection overhaul - selectedHotbarSlot index drives selection/scroll/keys/placement; selectedPlaceBlock derived from active slot^)
echo   run_step.bat m83     ^(Milestone 83: cave atmosphere - dense cave fog smoothly closes in underground; procedural cave drip one-shots every 3-9 seconds^)
echo   run_step.bat m84     ^(Milestone 84: bioluminescent FUNGUS block - underground cave floors, blue-green emissive glow, dense in crystal zones^)
echo   run_step.bat m85     ^(Milestone 85: underground cave biome zones - FLOODED/CRYSTAL/DEAD; zone effects on movement speed and sanity drain^)
echo   run_step.bat m86     ^(Milestone 86: Ceiling Lurker AI - hangs on cave ceilings, drops when player approaches, hunts on ground, 20 damage + smoke on hit^)
echo   run_step.bat m87     ^(Milestone 87: CONTINUE first in title menu; New Game uses randomized seed via currentTimeMillis^)
echo   run_step.bat m88     ^(Milestone 88: VOIDSTONE bedrock layer Y=0-2 - unique block, near-black purple tint, unbreakable^)
echo   run_step.bat m89     ^(Milestone 89: expanded cave system - deeper band Y=3-42, wide chamber carving, stalactites and stalagmites^)
echo   run_step.bat m90     ^(Milestone 90: world depth fix - SIZE_Y expanded to 128, terrain raised to Y~70-80, caves Y=3-65, ~55 blocks of real depth^)
echo   run_step.bat m91     ^(Milestone 91: WATER block + cave pools - FLOODED zones fill with water; buoyancy + swimming physics, SPACE to swim up^)
echo   run_step.bat m92     ^(Milestone 92: darkness mechanic - underground fog 2.8x thicker without lantern; nearby lanterns restore visibility; enemies aggroed^)
echo   run_step.bat m93     ^(Milestone 93: stalactite drops - creak warning 1.2s before impact; 15 damage + crash audio if player stays underneath^)
echo   run_step.bat m94     ^(Milestone 94: CRYSTAL block - glowing cyan crystals in CRYSTAL zones; wall veins, floor clusters, crystal stalactites, emissive lamp^)
echo   run_step.bat m95     ^(Milestone 95: deep cave loot caches - rare crystal-ringed altars with relic, bones, lanterns, journal stone, fungus patches^)
echo   run_step.bat m95.1   ^(Milestone 95.1: cave echo audio - footsteps underground use echo buffer with stone resonance + two echo copies at 130ms/300ms^)
echo   run_step.bat m96     ^(Milestone 96: balance + visibility pass - stalactite cones, Lurker crawl-up, bone-white Stalker, 15s stala cooldown, 3-6min Lurker respawn, darkness aggro capped^)

echo   run_step.bat m97     ^(Milestone 97: debug tools - R=God Mode toggle ^(invincible, HP bar turns gold^), T=force Lurker spawn; all damage sources respect godMode^)

echo   run_step.bat m98     ^(Milestone 98: sound bug fix + 3D positional audio - stalker sting filtered to notable events only; hit thud SFX; listener + entity source world positions; stalker breathing + lurker hiss directional loops^)

echo   run_step.bat m99     ^(Milestone 99: surface world events - random FOG_BANK/WIND_STORM/EMBER_SHOWER events every 2-4min; coloured event messages; fog/movement/particle effects per type^)

echo   run_step.bat m100    ^(Milestone 100: audio debug fix -- entity presence sounds audible only within 12 blocks; ALT+A audio debug overlay showing all source gains/states^)

echo   run_step.bat m101    ^(Milestone 101: cave ambient fix -- gain 0.30-^>0.13 underground; tremolo freq 0.18-^>0.20Hz for clean 5s loop; overtone 0.40Hz clean loop^)

echo   run_step.bat m102    ^(Milestone 102: audio fix attempt 2 -- remove alListenerfv orientation update per-frame; ALT+Z mute-test cave ambient toggle^)

echo   run_step.bat m103    ^(Milestone 103: audio isolation debug -- ALT+S stops ALL 11 sources to test if noise is OpenAL-managed; overlay shows mute state^)

echo   run_step.bat m104    ^(Milestone 104: stalactite isolation test -- disabled stalactite scan/drop damage ^+ creak/crash triggers and stalactite cone overlay render^)

echo   run_step.bat m105    ^(Milestone 105: interaction feel pass -- visible held block hand overlay, hold-to-break mining with per-block break times, crosshair break progress bar, punch swing feedback^)

echo   run_step.bat m106    ^(Milestone 106: mining polish pass -- 3D-style held block, crack texture overlay while mining, and tool-tier mining speed multipliers ^(HAND/WOOD/STONE/CRYSTAL^)^)

echo   run_step.bat m107    ^(Milestone 107: mining/crafting pass -- crack visual moved onto targeted world block, added craftable tool items ^(WOOD/STONE/CRYSTAL picks^), enhanced crafting recipes + tool-speed integration^)

echo   run_step.bat m108    ^(Milestone 108: crack visibility fix -- world-space crack strokes now render offset on multiple block faces at full-bright so they are clearly visible while mining^)

echo   run_step.bat m109    ^(Milestone 109: crack pattern pass -- replaced '+' style crack with full-span jagged diagonals across block faces^)

echo   run_step.bat m110    ^(Milestone 110: material crack styles -- stone branching, dirt/mud chunk fractures, wood splinters, crystal spiderweb style^)

echo   run_step.bat m111    ^(Milestone 111: texture atlas foundation -- generated/loaded terrain_atlas.png, per-face UV meshing path with safe tint fallback^)

echo   run_step.bat m112    ^(Milestone 112: true tool system -- dedicated equipped tool slot, durability, harvest gating, speed tiers, auto-equip key ^(G^)^)

echo   run_step.bat m113    ^(Milestone 113: first-person polish -- better hand/item transform arcs, equip animation, and block-type hit particles^)

echo   run_step.bat m114    ^(Milestone 114: atlas lighting readability fix -- textured terrain now has minimum light floor and stronger lantern response to prevent near-black caves^)

echo   run_step.bat m115    ^(Milestone 115: lantern lighting overhaul -- long-range lamp collection, stronger attenuation/power, emissive lantern texture response, fog-resistant glow^)

echo   run_step.bat m116    ^(Milestone 116: lantern balancing + presets -- increased active lamp budget/range, toned hotspot, persistent distant influence, ALT+L cycles NORMAL/STRONG/HORROR_BRIGHT^)

echo   run_step.bat m117    ^(Milestone 117: lighting correction -- remove emissive overbright hack, far lower local blowout, smoother long-range lantern falloff, default preset set to NORMAL^)

echo   run_step.bat m118    ^(Milestone 118: texture contrast + lantern response rebalance -- reduce washout, restore atlas detail contrast, increase lantern emission without hotspot blowout^)

echo   run_step.bat m119    ^(Milestone 119: atlas detail pass -- generated tile noise/detail so textures are visible, reduced warm lantern wash that flattened colours^)

echo   run_step.bat m120    ^(Milestone 120: hand-authored atlas pass -- per-block multi-color pixel palettes/patterns ^(grass/dirt/stone/wood/etc.^), forced atlas regeneration each run so updates apply immediately^)

echo   run_step.bat m121    ^(Milestone 121: atlas clarity fix -- switched to crisp nearest sampling ^(no mipmap averaging^) so block textures no longer collapse into flat grey tones^)

echo   run_step.bat m122    ^(Milestone 122: entity overexposure fix -- disable lamp point-light contribution on voxel entities so ceiling lurker no longer turns white while terrain lighting remains^)

echo   run_step.bat m123    ^(Milestone 123: atlas UV orientation fix -- corrected V mapping for flipped upload so terrain samples intended tiles instead of dark/incorrect rows^)

echo   run_step.bat m124    ^(Milestone 124: reduced player-centered ambient floor -- lowered textured minimum light to remove constant glow around player^)

echo   run_step.bat m125    ^(Milestone 125: handheld torch pass -- Minecraft-inspired torch proportions, torch item/recipe/icon, and dynamic hand-held light source tied to held torch^)

echo   run_step.bat m126    ^(Milestone 126: crafting input + cave brightness fix -- inventory clicks no longer eat crafting-grid placement; reduced base textured light floor in caves^)

echo   run_step.bat m127    ^(Milestone 127: crafting/torch expansion -- centered crafting icons, added crafting table block + proximity gating for advanced crafts, and wall-placed torch variant with ~22.5ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â° lean style + side/top placement rules^)

echo   run_step.bat m128    ^(Milestone 128: crafting table UI pass -- right-click table opens 3x3 top-of-panel crafting UI, larger output slot, centered craft item icons, darker baseline player light, and wall/standing torch world rendering rules^)

echo   run_step.bat m129    ^(Milestone 129: fix underground cave blowout -- directional sun light now fully suppressed when underground via undergroundDirectMul smooth transition; ambient was only being reduced before, direct was hitting 1.08 on cave ceilings causing white blowout^)

echo   run_step.bat m130    ^(Milestone 130: fix lamp accumulation blowout -- Reinhard tonemapping added to fragment shader; lit clamp 1.6-^>1.0; lamp scalar 0.30-^>0.18; attenuation tightened linear 0.10-^>0.22 quad 0.018-^>0.040; crystal blocks were accumulating 10+ lamp contributions to 3x overbright^)

echo   run_step.bat m131    ^(Milestone 131: shaped crafting system -- position matters like Minecraft; 1 item per craft slot; bounding-box normalization matching; new recipe shapes; table UI requires right-click on placed crafting table^)

echo   run_step.bat m132    ^(Milestone 132: wood plank economy + wood axe -- 1 log-^>4 planks recipe; all tool handles use planks; new WOOD_PLANK block ID 25 + TOOL_WOOD_AXE ID 26; stone requires pick to harvest; axe 2.8x speed on wood/leaves; atlas tile for planks^)

echo   run_step.bat m133    ^(Milestone 133: remove player ambient floor + new world select screen -- shader floor removed for true cave darkness; full-screen styled world select replaces old panel; N=new save, D=delete, ENTER=load; saveWorldProfiles persists worlds.txt^)

echo   run_step.bat m134    ^(Milestone 134: lighting overhaul + world select UI fixes -- removed lit clamp so direct sun reaches full HDR; underground ambient fully zeroed ^(was -0.14 offset, daytime still left 0.38 ambient^); lamp scalar 0.18-^>0.45; presets boosted; held torch 4.4-^>6.0; slot label no longer overlaps world name; [R] rename tooltip on selected card^)

echo   run_step.bat m135    ^(Milestone 135: fix firefly/wisp emissive rendering post-Reinhard -- old setLight(1.2f,0,0) emissive trick gave 0.49 output after Reinhard; boosted to 4.5f for 0.82 output; firefly injected lamp power 1.1-^>2.8 so terrain lights up visibly around them^)

echo   run_step.bat m136    ^(Milestone 136: fix world-load freeze from title screen -- closeWorldMenu(true) now calls closeTitleScreen() so titleScreenOpen is cleared and renderer.setPaused(false); game loop no longer gets stuck in the title-screen continue branch after loading a save^)

echo   run_step.bat m137    ^(Milestone 137: firefly lamp pool fix + title screen cursor lock -- NORMAL preset attenuation raised 0.035-^>0.10 linear/0.0012-^>0.015 quad for 6:1 falloff ratio creating visible ground pool; firefly power 2.8-^>12.0; shader lamp radius 18-^>40; title screen ENTER now calls closeWorldMenu which locks cursor^)

echo   run_step.bat m138    ^(Milestone 138: inventory UI redesign -- bottom panel anchored to screen bottom with hotbar+3inv rows; separate right-side crafting panel (2x2/3x3); no fullscreen dim overlay; world shows through; transparent background; shared layout constants via UI_SZ/UI_GAP/UI_MARG helpers^)
echo   run_step.bat m139    ^(Milestone 139: inventory centered on screen; crafting panel to right of inventory; game unpaused during inventory/crafting; crafting table top face distinct cross-hatch workbench texture; DOOR_CLOSED+DOOR_OPEN blocks; 1/4-thick plank slab with visible gaps; right-click toggle open/close; wall-detect facing; isMovementBlocker+isTargetable in BlockId; recipe 2x3 planks = 1 door^)
echo   run_step.bat m140    ^(Milestone 140: door is now 2 blocks tall; placement places bottom+top block pair; right-click toggle flips both halves simultaneously; breaking either half removes both+drops 1 door item; player HALF_W=0.30 (0.60 wide); 2-tall door cannot be stepped over^)
echo   run_step.bat m141    ^(Milestone 141: door rail lines now visible -- 3 plank bands + 2 separated darker rail bands; eliminated Z-fighting from rails overlapping plank geometry; rail color 0.20/0.12/0.05 (near-black)^)
echo   run_step.bat m142    ^(Milestone 142: fix inventory carry-over between worlds -- starter items (16 stone + 6 lanterns) only granted on fresh world with no save file; existing saves load cleanly without double-counting starters^)
echo   run_step.bat m143    ^(Milestone 143: feedback-build polish -- (A) controls screen as 5th pause menu item; (C) death screen 2.8s fade+horror text before respawn; (D) target block wireframe outline 12-edge cube; (E) F3 toggles XYZ/world/biome info overlay^)
echo   run_step.bat m144    ^(Milestone 144: item name flash on hotbar change; journal right-click read without destroying^)
echo   run_step.bat m145    ^(Milestone 145: biome atmosphere events -- DEAD whispers+distortion pulse, SWAMP fog spike+whispers, low-sanity visual flicker at sanity < 30^)
echo   run_step.bat m146    ^(Milestone 146: surface stone ruins -- 5x5 stone walls+door gap, JOURNAL centrepiece, CAMPFIRE in pine ruins, BONES in dead ruins; 18% per 80x80 area^)
echo   run_step.bat m147    ^(Milestone 147: auto-save every 5 minutes with SAVED HUD flash; delete world now also removes player save file^)
echo   run_step.bat m148    ^(Milestone 148: gray fog colour + FOG DISTANCE option in settings; TORCH_WALL/TORCH_STAND break drops TOOL_TORCH; blocks spawn as physics drops on ground, pickup by walking near^)
echo   run_step.bat m149    ^(Milestone 149: torch hitbox fix ^(TORCH_STAND + TORCH_WALL now targetable/breakable^); COAL ore block generated in stone veins; COAL + WOOD PLANK = TORCH x8 recipe^)
echo   run_step.bat m150    ^(Milestone 150: pig animals spawn on grass surface; 4-hit kill; drop RAW_PORK x1-2; RMB to eat food restores 20 HP; pigs have IDLE/WANDER/FLEE AI and leg animation^)
echo   run_step.bat m151    ^(Milestone 151: FPS overhaul -- StreamMesh persistent VBO eliminates ~30 GpuMesh alloc/dealloc per frame; lamp scan cache skips full chunk scan each frame; torch/door geometry cache; removed duplicate raycast; cached lamp model uniforms^)
echo   run_step.bat m152    ^(Milestone 152: DEAD_FOG near-blackout event at 60%%+ horror; 260-drop heavier blood rain starts at 30%% horror; fog figures walk toward player; monster spawn timers scale with horrorProgression 0-1; escalation milestone messages at 25/50/75/100%%^))

echo   run_step.bat m153    ^(Milestone 153: relic hunt objective -- collect 5 RELICS to open Void Gate at origin; VOIDSTONE octagon shrine at world start; escape sequence fades to white; all monster spawn timers toned down; figure 480s-200s, lurker 300s-150s min, THE THING min 120s^)
echo   run_step.bat m154    ^(Milestone 154: relic surface monuments -- VOIDSTONE obelisk + CRYSTAL cap + BONES cross above each relic; 9th hotbar slot + keys 1-9; craft grid left-click places full stack, right-click places 1^)
echo   run_step.bat m155    ^(Milestone 155: file-based ambient audio system (WAV/OGG loader via LWJGL STB), crossfading ambience pools, blood rain/dead fog event layers^)
echo   run_step.bat m156    ^(Milestone 156: weather clears on title screen; wall torch flush to wall; bgm 0.55-0.85; psych horror system -- peripheral flash, torch flicker, ghost footstep, overhead creak^)
echo   run_step.bat m157    ^(Milestone 157: spawn tuning -- figure/lurker/thing rarer; NIGHT_SENTINEL scales 1-3 watchers with horror; DREAD_STALKER rare/late-game only; pickaxe craft no relic gate; WORKBENCH rename; torch hotbar light fix^)
echo   run_step.bat m158    ^(Milestone 158: pickaxe mining speed boost -- wood 1.6x, stone 2.2x, crystal 3.0x on stone/coal/crystal^)
echo   run_step.bat m159    ^(Milestone 159: 4 jumpscares -- THE FACE (white flash + screaming face, horror 70pct), FALSE CHARGE (audio+blood flash, 50pct), IT'S BEHIND YOU (text + instant sentinel, 60pct), DEAD SILENCE (8s mute + heartbeat slam, 30pct)^)
echo   run_step.bat m160    ^(Milestone 160: cave entrances -- tapered surface shafts connect to underground caves; one per ~96x96 area with 18%% chance; radius 2.6 at surface tapering to 1.2 at depth 24^)
echo   run_step.bat m161    ^(Milestone 161: debug cleanup -- F6/F7/F8/F9 set sanity 0/33/66/100; N keeps night toggle; all other debug keys removed; RELIC_GOAL 5->3; stale biome+crafting hotkeys removed^)
echo   run_step.bat m162    ^(Milestone 162: fix sanity debug keys -- hoisted F6-F9 to top-level so they fire in any game state; biome whisper interval 18-43s to 45-90s^)
echo   run_step.bat m163    ^(Milestone 163: more cave entrances -- grid 96x96->64x64 + probability 18%%->40%%, roughly 5x denser^)
echo   run_step.bat m164    ^(Milestone 164: rename game to Hollow -- window title, title screen, world select watermark, build.gradle, settings.gradle^)
echo   run_step.bat m165    ^(Milestone 165: cave entrances -- wide bowl+funnel shape with stone collar rim, density reduced 64/40%% to 96/25%%; firefly light fix -- MAX_LAMPS 24->32, reserve 8 slots for dynamic lights^)
echo   run_step.bat m166    ^(Milestone 166: journal lore expansion 25 entries; LCTRL walk/sneak mode -- silent+slow; THE DEEP blind cave floor predator hunts by sound; dim fireflies 12->3.5 power; dim crystals 1.35->0.55^)
echo   run_step.bat m167    ^(Milestone 167: music quieter 0.85->0.40 + 15-50s silence gaps between tracks; weather initial timer randomised; save slot naming fix max+1 not size+1; delete world clears edits+player files^)
echo   run_step.bat m168    ^(Milestone 168: compass HUD+void-gate mode; THE DEEP 3D positional sound; armor 3-tier BONE/STONE/CRYSTAL 20/35/50%% dmg reduce; ending screen stats; first-person hand render^)
echo   run_step.bat m169    ^(Milestone 169: 70-block purple emissive void gate beacon at origin; screen-edge crosshair+distance waypoint when relics complete; compass auto-switches to void gate bearing^)
echo   run_step.bat m170    ^(Milestone 170: pig facing fix (WANDER/FLEE angle corrected); new-world intro text sequence showing objectives on fresh worlds^)
echo   run_step.bat m171    ^(Milestone 171: tools auto-equip on hotbar selection (G key removed); inventory 3-row->2-row (INV_SLOTS 36->27); water untargetable - no outline, no mining, no phantom break^)
echo   run_step.bat m209    ^(Milestone 209: NUN arm fix - remove left arm render, NUN_FACING_OFF=PI, NUN_SHOULDER_Y=1.60
echo   run_step.bat m210    ^(Milestone 210: revert NUN to M201 blocky voxel style - remove M205-M209 OBJ/FBX redesign^)
echo   run_step.bat m211    ^(Milestone 211: voice line system - SRC_VOICE; taunts at sanity 85/50/30; esc_1-4 on horror milestones; slower sanity drain^)
echo   run_step.bat m212    ^(Milestone 212: Options menu MUSIC VOLUME + MASTER VOLUME sliders; 10 rows; saves to options.txt^)
echo   run_step.bat m213    ^(Milestone 213: FOV slider in Options 60-110deg range +-5 per press saves to options.txt^)
echo   run_step.bat m214    ^(Milestone 214: block break/place reach reduced from 6.0 to 4.0 blocks^)
echo   run_step.bat m215    ^(Milestone 215: Hide and Seek event - screamer PATROL, thick fog, event_hide.wav + event_hide_over.wav, 30s duration^), ARM_Z_MIN corrected 0.097-^>0.60 actual shoulder, no cursor*0.3 bug in blender_reexport.py^)
echo   run_step.bat m216    ^(Milestone 216: remove compass needle - ring + distance display + checklist remain^)
echo   run_step.bat m217    ^(Milestone 217: ending screen returns to title screen via openTitleScreen - fixes loop^)
echo   run_step.bat m218    ^(Milestone 218: fix hide and seek phases; sanity drain 0.28 stalked 0.02 night^)
echo   run_step.bat m219    ^(Milestone 219: LOS sight checks + wall collision for all monsters^)
echo   run_step.bat m220    ^(Milestone 220: remove DREAD_STALKER entirely - FSM, render, API all gone^)
echo   run_step.bat m221    ^(Milestone 221: FUNGUS mushroom + BLOODSTAIN splat + LANTERN cage as custom 3D geometry^)
echo   run_step.bat m222    ^(Milestone 222: BONES bone-pile + COBWEB web-mass as custom 3D geometry^)
echo   run_step.bat m223    ^(Milestone 223: fix M221/M222 lag - decal blocks capped 24-block render radius; BONES/BLOODSTAIN/COBWEB rates cut 60-75 pct^)
echo   run_step.bat m224    ^(Milestone 224: remove cobwebs from PINE tree canopy entirely^)
echo   run_step.bat m225    ^(Milestone 225: liminal zone portal - LIMINAL_PORTAL block, VOIDSTONE arch, corridor grid gen, zone-switch logic, fluorescent ambient^)
echo   run_step.bat m226    ^(Milestone 226: title screen on launch - removed M185 auto-start, first launch starts with empty world list, D key deletes last world and returns to title screen^)
echo   run_step.bat m227    ^(Milestone 227: structure save/load tool - G key capture mode mark corner A+B, F10 name and save .struct file, H key paste mode cycle and RMB stamp at target block, worlds/structs/ storage^)
echo   run_step.bat m228    ^(Milestone 228: builder.bat + builder mode - no enemies, frozen noon, noclip, all blocks^)
echo   run_step.bat m229    ^(Milestone 229: two-zone liminal - zone1 meadow flat grass+grid trees+oak building, zone2 dark static finite room with NUN, portals near world boundary walls^)
echo   run_step.bat m208    ^(Milestone 208: fix NUN facing - atan2^(dx,dz^) for +Z OBJ model; left arm support - loadTexturedRaw^(^) no Y-shift, nunLeftArmMesh, arm_left.obj at mirrored shoulder with opposite-phase sway; blender_left_arm.py exports body+arm_right+arm_left^)
echo   run_step.bat m207    ^(Milestone 207: NUN rigid body-part animation - body.obj+arm_right.obj split via Blender script; shoulder pivot 0.272/1.097/0; body rendered static, arm animated per AI state - PATROL forward, HUNT knife up+idle sway, STRIKE windup sweep back, RETREAT follow-through arc^)
echo   run_step.bat m206    ^(Milestone 206: FBX model support for THE NUN - lwjgl-assimp added to build.gradle; FbxLoader.java ^(Assimp multi-mesh Y-shifted UV-flipped loader^); TextureLoader.java ^(STBImage JPEG/PNG with mipmaps^); NUN_FBX_PATH/TEX_PATH/SCALE/FACING_OFF constants; loadNunMesh^(^) lazy on first updateNuns^(^); renderNuns prefers FBX model-matrix render falling back to voxel; cleanup on shutdown^)
echo   run_step.bat m205    ^(Milestone 205: THE NUN full voxel redesign - authentic Nun Massacre recreation: bell habit wide flared hem, white wimple wrapping face ^(sides+chin+brow^), chalky pale face, void-black oversized eyes, blood tear streaks x2 per eye, orange-red lips, black rosary+cross, veil side drapes, RIGHT ARM RAISED knife pointing straight up iconic silhouette, 3-segment elbow-bent arm animation per state^)
echo   run_step.bat m204    ^(Milestone 204: F11 debug fix ^(updateNuns cleared list every frame on non-night^); surface relics - 2 above-ground buildSurfaceRelicPos+isSurfaceRelicPos in ChunkGenerator, baseBlockAt checks isSurfaceRelicPos; getRelicPositions returns surface relics first then underground; compass UI rewrite - add >/+/./! glyphs to font, 3-row checklist RELIC1/RELIC2/DEEP, correct markers, clean header RELICS X/3 + distance^)
echo   run_step.bat m203    ^(Milestone 203: fixes - debug key F10-^>F11 ^(F10 = Windows OS menu key, intercepted before GLFW^); compass getRelicPositions rewritten to use relicPosForArea algo matching isRelicPos so needle actually points to real relic blocks; dynamic relic checklist replaces hardcoded FOREST/DEAD/CAVERNS labels^)
echo   run_step.bat m202    ^(Milestone 202: debug F10 key - force-spawn THE NUN 6 blocks ahead of player, bypasses night/horror gates, clears existing nuns; controls menu shows F6-F9 and F10 debug entries^)
echo   run_step.bat m201    ^(Milestone 201: THE NUN - tall black-habit killer inspired by Nun Massacre ^(Puppet Combo 2018^); long black robe+white guimpe+pale face+knife; PATROL 1.5f-^>HUNT 2.4f-^>STRIKE 0.55s windup 35dmg-^>RETREAT; heel-clop footstep + knife strike audio; SRC_NUN=16 SRC_COUNT=17; horrorProgression^>=0.20 night-only spawn^)
echo   run_step.bat m200    ^(Milestone 200: fireflies - restore warm body color ^(0.95/0.82/0.22^), darker emitted lamp power 3.5-^>1.4^)
echo   run_step.bat m199    ^(Milestone 199: Screamer - 1 at a time, 90s first spawn + 2-3min respawn timer, dark gray body ^(0.22/0.22/0.24^), no shadow quad, despawns after scream ends^)
echo   run_step.bat m198    ^(Milestone 198: THE THING DRAG state - reaches player, picks random dark dest 50-80 blocks, runs at 9f carrying player ^(fires consumeThingDrag teleport after 0.3s^), deals 1 dmg + retreats on arrival; isThingDragging/consumeThingDrag API; GameApp teleports camera + shows YOU HAVE BEEN TAKEN + jumpscare audio; fireflies darker: flicker 0.45+0.55, RGB 0.38/0.55/0.22, ambient 1.8-^>0.45^)
echo   run_step.bat m197    ^(Milestone 197: Screamer chases player during SCREAM state at 6.5 blocks/sec; scream duration extended 3.5-^>5s^)
echo   run_step.bat m196    ^(Milestone 196: THE SCREAMER entity - white flailing humanoid; WANDER runs 3.2f; within 18 blocks triggers STARE 2s facing player; then SCREAM 3.5s ^(arms wide+shaking, red eyes^) + plays sounds/scream.wav via SRC_SCREAMER 3D positional; teleports away after scream; 2 active at night; consumeScreamerSoundPos API^)
echo   run_step.bat m195    ^(Milestone 195: watcher floating face-only no body, always faces player ^(fdx/fdz right/fwd vectors^); spawn 22-35 blocks, vanish 26-40 blocks; WORLD_RADIUS 400-^>200; relic positions clamped inside boundary; one weather msg at a time ^(surfaceEventMsg only shows if thickFogMsg+boundaryMsg inactive^)^)
echo   run_step.bat m194    ^(Milestone 194: NIGHT_SENTINEL visual overhaul - fog-piercing white-blue eyes + warm grin ^(5-cube curved smile^); dedicated head block; eyeFf=ff*0.12 so face features stay bright through fog; spawn radius 16-28-^>9-15 blocks, vanish respawn 20-32-^>11-20 blocks^)
echo   run_step.bat m193    ^(Milestone 193: master volume control - AudioSystem.masterGain=0.80 init via alListenerf^(AL_GAIN^); setMasterVolume/adjustMasterVolume/getMasterVolume API; Options row 6 MASTER VOLUME +-5^% per left/right key; menu rows/mod 8-^>9; saves/loads master_volume in options.txt^)
echo   run_step.bat m192    ^(Milestone 192: pig wall collision - check isMovementBlocker at pgY for X and Z separately, cliff check ghX/ghZ +0.55, redirect facing on block; ChickenEntity class - 2 HP white body peck anim, drops RAW_CHICKEN 1-2; COOKED_CHICKEN recipe RC+CO; RAW_CHICKEN=37 COOKED_CHICKEN=38 in BlockId isFood/heal/nameOf; tryHitChicken API; saves/loads inv_raw_chicken inv_cooked_chicken^)
echo   run_step.bat m191    ^(Milestone 191: HUD compact status panel scale=1-^>2 for legibility; panelW=300 panelH=88 green accent bar; removed compass recipe hint from HUD (was CRYSTAL + WOOD PLANK 3x3 TABLE); intro Phase C sub changed to PRESS R FOR RECIPES; Save-1 auto-start verified M185 intact^)
echo   run_step.bat m190    ^(Milestone 190: campfire 3D geometry enabled - excluded from isSolid^() so chunk mesh skips full-block render; added to isTargetable^() for breakability; renderCampfires^() was already implemented with ash base + 2 crossed logs + 3 animated flame layers^)
echo   run_step.bat m189    ^(Milestone 189: relic lore journals - RELIC_JOURNAL_LINES[3] each biome-specific; placed as JOURNAL block 3 blocks east on relic pickup; secretJournalMessage^() returns relic lore within 8 blocks; JOURNAL_LINES expanded 26-^>37 entries^)
echo   run_step.bat m188    ^(Milestone 188: relic pickup ceremony - 2s amber flash then dark overlay + RELIC X/3 ABSORBED in scale=3 text; recipe book R key - panel with all recipes 2-col name+ingredients, [T]=table needed, ESC/R close; night progressively darker with horror - renderAmbient -= nightFactor*horrorProgression*0.022^)
echo   run_step.bat m187    ^(Milestone 187: pause menu adds NEW WORLD/SWITCH WORLD item (6 items now, opens worldMenu); biome indicator on compass status list (FOREST/DEAD LANDS/SWAMP text); boundary vignette - dark red screen edges grow within 50 blocks of WORLD_RADIUS^)
echo   run_step.bat m186    ^(Milestone 186: guarantee compass on load - old saves had no inv_compass entry; replaced stale lantern fallback (3 lanterns if save had none) with compass guarantee (1 compass if save has none)^)
echo   run_step.bat m185    ^(Milestone 185: auto-start at Save-1 on launch - closeTitleScreen() called after applyWorld(0) in init(), bypasses title screen and world select menu entirely^)
echo   run_step.bat m184    ^(Milestone 184: world boundary WORLD_RADIUS=400 in ChunkGenerator (VOIDSTONE wall outside radius) + GameApp clamp (setPosition pushback) + horror boundary message (red tint + THE VOID DEVOURS ALL); compass y-formula fixed (cy+cos instead of cy-cos so N is truly at top); COOKED_PORK id=36 (RAW_PORK over COAL no-table recipe, heals 50HP, save/load added)^)
echo   run_step.bat m183    ^(Milestone 183: compass true-north fixed ring - N/S/E/W labels always fixed at cardinals; player-facing yellow notch rotates on ring; needle uses absolute world bearing (no yaw subtraction); ordered stages Forest/green->Dead/orange->Caverns/purple, auto-advance on collect; status list [x][>][ ] right of compass; moved to bottom-left cx=88 cy=230 to clear hotbar+HP bar^)
echo   run_step.bat m182    ^(Milestone 182: compass needle fix - use isBlockEdited() instead of getBlock() to detect collected relics; getBlock returns AIR for unloaded chunks which falsely appeared as collected; redesigned needle: solid 2px shaft + arrowhead diamond + bright tip pixel; 48pt ring; no question mark in center^)
echo   run_step.bat m181    ^(Milestone 181: compass is now a permanent passive item - removed from isTool() so it never auto-equips or drains durability; added to isSolid exclusion; right-click place guard prevents accidental placement; compass HUD still shows when active hotbar slot^)
echo   run_step.bat m180    ^(Milestone 180: fix compass-only start (was giving STONE+LANTERN on new world); cutscene intro 24s dark overlay + big font scale 3-6 + SPACE skip; 4 phases: HOLLOW title / FIND 3 RELICS / CRAFT A COMPASS / YOU ARE NOT ALONE^)
echo   run_step.bat m179    ^(Milestone 179: 5 relics in world [2 surface biome-targeted + 3 underground scattered]; UNDERGROUND_RELIC_START=2; computeUndergroundRelic(seed,idx) with distinct seeds per index; only first underground relic counts toward RELIC_GOAL [extras removed + message "THE DEPTHS YIELD NO MORE SECRETS"]; undergroundRelicsFound field saved/loaded; compass uses getRelicPositions filtered to uncollected; proximity indicator also filters^)
echo   run_step.bat m178    ^(Milestone 178: campfire non-solid custom geometry [ash base+2 crossed logs+2 teepee stumps+3-layer animated fire with HDR Reinhard warmth]; CAMPFIRE added to isTargetable, removed from isSolid; crystal geodes [4% per 96x96 CRYSTAL zone: hollow sphere r=2.2 AIR + r=3.5 CRYSTAL shell]; horror chasms [7% per 48x48 DEAD zone: 1-2 block wide shaft carved to VOIDSTONE floor + BLOODSTAIN rim]^)
echo   run_step.bat m177    ^(Milestone 177: player starts with compass only [was stone+lanterns]; save/load fixed for compass/coal/food/armor items; cave content - FLOODED zone MUD floors at low Y, DEAD zone scattered BONES+BLOODSTAIN+COBWEB walls+ceiling, stalactites extended to 2-3 blocks, stalagmites extended to 2-3 blocks, underground encampments [22% per 64x64: CAMPFIRE+BONES ring+JOURNAL+LANTERN]^)
echo   run_step.bat m176    ^(Milestone 176: Weather gated underground - no RAIN/DEAD_FOG transitions, no rain rendering, no blood rain sky, no dead fog sky, no lightning when underground; entity ground-snap - entityGroundY() block scan helper replaces surface heightAt() for stalker Y and THE DEEP Y; cave floor constant for deep spawn^)
echo   run_step.bat m175    ^(Milestone 175: ambient music -35% quieter; water blocks removed from world gen + physics; intro rewritten to show clear objective first [FIND 3 RELICS/CRAFT COMPASS/RETURN TO ORIGIN] then atmospheric; compass craft hint on HUD until first relic found; relic pickup shows RELIC X/3 ABSORBED^)
echo   run_step.bat m174    ^(Milestone 174: max 3 save files; 3 biome-targeted world relics via getRelicPositions(seed) - [0]=PINE spiral search, [1]=DEAD/SWAMP spiral search, [2]=underground hash; vault+monument+isRelicPos all updated; proximity indicator uses nearest of 3; underground relic has no surface monument^)
echo   run_step.bat m173    ^(Milestone 173: removed name from secret journals; slower enemy/pig/hallucination spawns; CAVE_MUSHROOM food item (id=35, drops from FUNGUS, heals 10 HP); fungus passive heal 1 HP/4s; rarer crystal spawn (0.07->0.04 floor, 0.04->0.022 stalactite, 0.06->0.032 wall)^)
echo   run_step.bat m172    ^(Milestone 172: easter eggs - ghost pig 2%% spawn (spectral/white, despawns ~10s); 666 underground VOIDSTONE chamber; idle scare (stand still 45s underground = TURN AROUND); special journal messages near origin and 666; dev signature JOURNAL_LINES entry^)
echo   run_step.bat latest  ^(Current branch state^)
echo.
echo Current branch:
git rev-parse --abbrev-ref HEAD
echo.
git log --oneline --decorate -n 6

call :step m230 "G key builder-only, portal teleport fix, portal non-blocking"

call :step m231 "portal panel fix, meadow arch portal, dynamic lighting, trees wider+shorter"
call :step m232 "Holloway Manor zone2 mansion, torch glow fix, NUN in zone2, loading screen, cave effects gated to overworld"
call :step m233 "zone2 grass exterior, warm ambient, TORCH_WALL decorative, NUN y-fix patrol/hunt/retreat, randomized doors+scatter"
call :step m234 "zone2 2-storey sealed mansion, no exits, NUN immediate spawn, firefly yellow-orange HDR, lanterns work in flat mode, fall-dmg reset on zone entry"
call :step m235 "fall damage triggers hit sound + screen shake scaled to severity"
call :step m236 "fullscreen F11, EA version stamp, recipe book 2-col redesign, sounds/ in package.bat, README rewrite"
call :step m237 "blocks permanent (no decay), door faces player camera direction + saved to edits, screamer visual-only in hide-and-seek"
call :step m238 "re-enable hide-seek screamer AI; spawns 15-25 blocks away, not on player"
call :step m239 "entity wall collision: 4-corner bbox (ENTITY_HALF_W=0.28f); NUN HUNT/STRIKE Y-guard (abs(dy)<2.5f)"
call :step m240 "Zone 2: 3rd floor (TFC=18 ROOF=19), upright random wall portal, lanterns replace torches, screamer zone2 y-fix"
call :step m241 "bugfix: smooth THING drag (per-frame carry, no teleport), death respawns in overworld, audio listener sync on drag"
