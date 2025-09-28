# CraftAudit – Block/Entity Audit Logger & Rollback Tool (Forge)

## Overview
CraftAudit is a server-side audit mod/plugin for Minecraft Forge that records world changes made by players and the environment (block place/break, container I/O, ignition, item-frame/painting actions, bucket usage, kills, etc.). It provides in-place inspection, area queries, rollback/restore and undo features, supporting SQLite or MySQL storage.

## Features
- **Block Change Logging**
  - Player break/place (with optional BlockState/NBT snapshots)
  - Environment breaks (natural_break): explosion, fluid, gravity

- **Interaction Logging**
  - Container put/take (menu session-based, accurate block position)
  - Sign editing (logs text)
  - Ignition (campfire/candle/TNT/fire)
  - Redstone interactions: button/lever/door
  - Item frame/painting: put/take/rotate/place/break
  - Bucket: fill/empty/catch/milk
  - Kill: logs killer, victim, reason, projectile, weapon, distance, etc.

- **Query**
  - In audit mode, left/right-click a block to view logs for that position (paged)
  - Area+time queries (`near` command)

- **Rollback/Restore/Undo**
  - Rollback a player’s place/break within radius & time window (`rollback`)
  - Restore all breaks (player & environment) or filtered by type (`restore`)
  - Restore kills: respawn non-player entities at log positions (`restore ... kill`)
  - Undo last rollback/restore (`undo`): revert blocks or remove spawned entities

- **Storage**
  - SQLite (default) or MySQL backend
  - Configurable file location and MySQL parameters

- **Other**
  - Localized names for items/blocks
  - Audit mode exempt from logging (auditor actions ignored)

## Commands
_All `craftaudit` commands have `/ca` alias. OP level 2 required._

### Basic
- `/craftaudit status`  
  Show database/mode status
- `/craftaudit inspect` or `/ca i`  
  Toggle audit mode (left-click: block logs, right-click: interaction logs)
- `/craftaudit log [page]`  
  Show interaction logs for last right-clicked block (paged)
- `/craftaudit blocklog [page]`  
  Show block logs for last left-clicked block (paged)

### Area Queries
- `/craftaudit near <radius> <time> [page]`  
  Search logs near player (by radius & time)
  - Time format: Ns/Nm/Nh/Nd (e.g., `30m`, `12h`, `5d`)

### Rollback/Restore/Undo
- `/craftaudit rollback <player> <time> [radius=10]`  
  Roll back a player’s place/break within area & time
- `/craftaudit restore <time> [radius=10] [type]`  
  Restore breaks or kills, optionally filtered by type:
  - No type: all breaks (player & environment)
  - `break`: only player breaks
  - `natural`/`natural_break`: only environment breaks
  - `explosion`/`fluid`/`gravity`: environment breaks by cause
  - `kill` or `kill:<entity_id>`: restore kills (respawn non-player entities)
- `/craftaudit undo`  
  Undo last rollback/restore (revert blocks, remove spawned entities)
- `/craftaudit purge <time>`  
  Purge logs older than time (irreversible)

### Time Format
- Examples: `30s`, `15m`, `12h`, `7d`

## Installation
- Place the mod jar in your Forge server’s `mods` folder  
- Requires Java 17 (Forge 1.19+)
- Config file is generated after first run

## Configuration
- Database:
  - SQLite: default at `gameDir/craftaudit/craftaudit.db`
  - MySQL: configurable host, port, database, user, password, SSL, params

## Data & Privacy
- Only logs essential events, coordinates, item/block/entity IDs for audit/rollback
- BlockEntity NBT is stored (compressed, size-limited, opt-in) for BE blocks on break
- Use `purge` regularly to clean up old logs

## Build
- Requires JDK 17 and Forge MDK
- Typical steps: import Gradle, sync dependencies, build jar

## Contributing
- Issues, PRs, and feature requests welcome

## License
Licensed under Creative Commons Attribution 4.0 International (CC BY 4.0)  
See [LICENSE](LICENSE).

Copyright (c) 2025 CraftAudit contributors
