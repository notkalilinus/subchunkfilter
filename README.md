# SubChunkFilter

A Minecraft plugin that hides underground chunk sections (below Y=0) from players above ground, preventing unfair advantages in stash finding.

## Requirements
- PaperMC 1.21.1+
- ProtocolLib 5.3.0+
- Java 21

## Installation
1. Download the JAR from the releases
2. Drop it into your server's `/plugins/` folder
3. Restart your server

## How it Works
- Intercepts MAP_CHUNK packets sent to players
- If a player is above Y=0, replaces underground sections (0-3) with empty air sections
- Surface players can only see surface-level terrain
