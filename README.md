<p align="center"><img src="logo.png" width="128" alt="Magma Client logo"></p>

# Magma Client v1.4.3

A client-side utility mod for **Minecraft 1.21.1 (NeoForge 21.1.259+)**: an auto miner that never digs into lava, safe Goto, a structure finder, X-Ray and more, with a clean menu on **Right Shift**.

> Use it in single-player or on servers that allow it. X-Ray and automation break the rules on most public servers.

## Install (CurseForge)
1. Download the latest `magmaclient-1.21.1-x.y.z.jar` from **[Releases](https://github.com/jjtjtyt6644/Magma-Client/releases/latest)** into your **Downloads** folder.
2. Open **PowerShell** and paste this one line. It lists your CurseForge modpacks, asks which one, and installs the jar (replacing any older Magma):

```powershell
$jar = Get-ChildItem "$env:USERPROFILE\Downloads\magmaclient-*.jar" | Sort-Object LastWriteTime | Select-Object -Last 1; $inst = @(Get-ChildItem "$env:USERPROFILE\curseforge\minecraft\Instances" -Directory); for ($n = 0; $n -lt $inst.Count; $n++) { Write-Host "[$n] $($inst[$n].Name)" }; $pick = $inst[[int](Read-Host 'Type the number of the modpack')]; Get-ChildItem (Join-Path $pick.FullName 'mods') -Filter 'magmaclient-*.jar' | Remove-Item; Copy-Item $jar.FullName (Join-Path $pick.FullName 'mods'); Write-Host "Installed $($jar.Name) into $($pick.Name)"
```

Or drag the jar into your modpack's `mods` folder (CurseForge → right-click the modpack → **Open Folder**).

## Updating
Magma updates itself from this repo's Releases. When a new version is out, a banner on the menu's **Home** page has an **Update** button. With **Auto Update** on (the default), it downloads by itself. Either way it installs when you **restart Minecraft**. Settings → Updates has **Check now**.

## The menu (Right Shift)
- **Home**: quick actions (**Start mining**, **Go to coordinates**, **Find a structure**), the cheats that are on right now, and live status.
- **Safety / World / Automation / Render / Client**: one row per module. Click to toggle, ▾ for its settings, middle-click to bind a key. Each module shows only its key settings; **Show advanced settings** reveals the rest. **Add-ons** suggests modules that work well together.
- **Settings**: theme, menu style, menu key and updates.

Start typing anywhere to search. Tab switches pages.

## Modules
| Module | What it does |
|---|---|
| **LavaGuard** | Warns about lava and cancels clicks on blocks that would let it in or drop you into it. |
| **SafeWalk** | Stops you walking into lava, fire or magma, or off big drops. |
| **AutoMiner** (`K`) | Mines the ores you tick, including **modded ores**, found through the whole height of loaded chunks. It mines everything in reach before moving and finishes the vein before moving on. Routes avoid lava, bridge and pillar with junk blocks, use the right tool for each block, defend against mobs (walls off creepers), eat, and pick up loot. |
| **Goto** | Walks to X Y Z safely (or type `.goto x y z` in chat). Sprints on safe stretches, shows a beam, the distance and an ETA, and converts Overworld/Nether coordinates. **Last Death** fills in where you died. |
| **Base** | Saves your base location (**Set here** or type X/Y/Z). Turn it on, or type `.base`, to walk home. Goto's **Base** button and `.goto base` use it, and AutoMiner can **Return To Base When Full**. `.base set` saves where you stand. |
| **AutoFind** | Finds a fortress, bastion, village, stronghold and more by their blocks, then walks there. Explores in a spiral, skips structures you've visited, and has **Find Next**. |
| **X-Ray** | See-through ore blocks with a glow, for all vanilla and modded ores. |
| **InvCleaner** | Caps junk stacks and throws extras out so ores fit. |
| **VillagerRoller** | Re-rolls a librarian until it sells the book you want. |
| **AutoTool / AutoEat / FastBreak / AutoWalk / Fullbright / HUD** | Quality-of-life helpers. |

## Build
```
gradlew build      # needs JDK 21; jar ends up in build/libs
```
