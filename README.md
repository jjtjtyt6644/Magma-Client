<p align="center"><img src="logo.png" width="128" alt="Magma Client logo"></p>

# Magma Client v1.12.0

A client-side utility mod for **Minecraft 1.21.1** on **NeoForge** (21.1.259+) and **Fabric** (Fabric API required): an auto miner that never digs into lava, safe Goto, a structure finder, X-Ray and more, with a clean menu on **Right Shift**.

> Use it in single-player or on servers that allow it. X-Ray and automation break the rules on most public servers.

## Which jar?
Each release has two jars:
- `magmaclient-1.21.1-x.y.z.jar` - **NeoForge** (CurseForge modpacks, NeoForge instances)
- `magmaclient-fabric-1.21.1-x.y.z.jar` - **Fabric** (Prism/MultiMC/Modrinth Fabric instances; needs [Fabric API](https://modrinth.com/mod/fabric-api))

Both work on any server (vanilla, Paper, Fabric...) - the server doesn't need Magma.

## Install (CurseForge)
1. Download the latest `magmaclient-1.21.1-x.y.z.jar` from **[Releases](https://github.com/jjtjtyt6644/Magma-Client/releases/latest)** into your **Downloads** folder.
2. Open **PowerShell** and paste this one line. It lists your CurseForge modpacks, asks which one, and installs the jar (replacing any older Magma):

```powershell
$jar = Get-ChildItem "$env:USERPROFILE\Downloads\magmaclient-*.jar" | Sort-Object LastWriteTime | Select-Object -Last 1; $inst = @(Get-ChildItem "$env:USERPROFILE\curseforge\minecraft\Instances" -Directory); for ($n = 0; $n -lt $inst.Count; $n++) { Write-Host "[$n] $($inst[$n].Name)" }; $pick = $inst[[int](Read-Host 'Type the number of the modpack')]; Get-ChildItem (Join-Path $pick.FullName 'mods') -Filter 'magmaclient-*.jar' | Remove-Item; Copy-Item $jar.FullName (Join-Path $pick.FullName 'mods'); Write-Host "Installed $($jar.Name) into $($pick.Name)"
```

Or drag the jar into your modpack's `mods` folder (CurseForge → right-click the modpack → **Open Folder**).

## Updating
Magma updates itself from this repo's Releases. When a new version is out, a banner on the menu's **Home** page has an **Update & restart** button (it saves, closes Minecraft and swaps the jar; then press Play). With **Auto Update** on (the default), it downloads by itself. Either way it installs when you **restart Minecraft**. Settings → Updates has **Check now**.

## The menu (Right Shift)
- **Home**: quick actions (**Start mining**, **Go to coordinates**, **Find a structure**), the cheats that are on right now, and live status.
- **Safety / World / Movement / Combat / Automation / Render / Client**: one row per module. Click to toggle, ▾ for its settings, middle-click to bind a key. Each module shows only its key settings; **Show advanced settings** reveals the rest. **Add-ons** suggests modules that work well together.
- **Settings**: theme, menu style, menu key and updates.

Start typing anywhere to search. Tab switches pages. In chat, `.toggle <module>` (or `.t fly`) switches any module.

## Modules
| Module | What it does |
|---|---|
| **LavaGuard** | Warns about lava and cancels clicks on blocks that would let it in or drop you into it. |
| **SafeWalk** | Stops you walking into lava, fire or magma, or off big drops. |
| **AutoMiner** (`K`) | Mines the ores you tick, including **modded ores**, found through the whole height of loaded chunks. It mines everything in reach before moving and finishes the vein before moving on. Routes avoid lava, bridge and pillar with junk blocks, use the right tool for each block, defend against mobs (walls off creepers), eat, and pick up loot. |
| **Goto** | Walks to X Y Z safely (or type `.goto x y z` in chat). Sprints on safe stretches, shows a beam, the distance and an ETA, and converts Overworld/Nether coordinates. **Last Death** fills in where you died. |
| **Base** | Saves your base location (**Set here** or type X/Y/Z). Turn it on, or type `.base`, to walk home. Goto's **Base** button and `.goto base` use it, and AutoMiner can **Return To Base When Full**. `.base set` saves where you stand. |
| **AutoFind** | Finds a fortress, bastion, village, stronghold and more by their blocks, then walks there. Explores in a spiral, skips structures you've visited, and has **Find Next**. |
| **Builder** | Builds schematics for you. `.build <name>` loads a `.schem` / `.litematic` / `.nbt` from `.minecraft/schematics` as a see-through ghost. **Hold a stick:** right-click moves it, left-click rotates it, arrow keys (or Shift+scroll) nudge it, PgUp/PgDn raise/lower it. Then `.build start`: it builds bottom-up, faces every block the right way, pillars or (in creative) flies into reach, scaffolds floating blocks and removes the scaffolding. The panel shows missing materials. It places everything in reach at once and steps out of the way if you stand where a block goes. The schematic stays put when you leave the world or restart, and a build in progress carries on when you're back. `.build stop` pauses, `.build clear` removes it. |
| **Builder Studio** | `.build editor` (or Builder → Open editor) opens a website on your PC. **Picture tab:** paste/drop a picture, pick size, wall or floor and which blocks to use (real block colours), adjust brightness/contrast/colour, remove the background, choose Flat/Pattern/Smooth shading, then paint/fill/swap to touch it up. **3D Editor tab:** edit in 3D like Minecraft with every block (glass, stairs, slabs, fences, plants...) — open any schematic from your game, turn the picture into blocks, or start empty; right-click place, left-click break, middle-click pick, blocks face the way you look; **Box / Walls / Line / Replace** tools fill floors, ceilings and walls in two clicks; hotbar 1-9, a layer slider to see inside, undo, and a **Fly** mode (WASD + mouse). **Send to Minecraft** shows it in your game ready to place; **Download** saves a `.schem`. Everything you do in the studio saves by itself (to `config/magma_studio.json`) and comes back next time. |
| **Fly** | Fly in any game mode with a speed slider (1 = vanilla creative). Landing after you switch it off is safe. |
| **NoFall** | No fall damage. |
| **Freecam** | Detach the camera and fly around (through walls) to look at your surroundings; your character stays put and is outlined. |
| **X-Ray** | See-through ore blocks with a glow, for all vanilla and modded ores. |
| **Scaffold** | Places blocks under your feet as you walk off edges (safe, fast bridging); hold jump to tower up. |
| **FastPlace** | No 4-tick delay between block placements. |
| **VeinMiner** | Break one ore or log and the whole vein or tree drops at once (singleplayer; on servers it mines the rest for you). |
| **PlayerESP** | Other players through walls: boxes (red when close) and tracers. |
| **AutoCombat** | Hit-and-run fighting: steps in when your attack is fully charged, hits at the edge of reach, backs off out of reach and circles. Players and/or monsters. |
| **InvCleaner** | Caps junk stacks and throws extras out so ores fit. |
| **VillagerRoller** | Re-rolls any villager (it reads the job block: lectern, composter, smoker...) until it offers what you want: tick as many books as you like for librarians, or type items for any job. |
| **AutoTool / AutoEat / FastBreak / AutoWalk / Fullbright / HUD** | Quality-of-life helpers. |

## Build
```
./build-all.sh     # needs JDK 21; builds both jars into latest-jars/
```
The Fabric build (`fabric/`) compiles the same source as the NeoForge one, with a small Fabric layer.
