# SupaCombat

A lightweight combat-tag and combat-logging protection plugin for Paper/Purpur 1.21.11.

## Features
- Tags both players for 21 seconds after player-vs-player damage, including player-shot projectiles.
- Displays a configurable MiniMessage action bar countdown.
- Blocks configured teleport, home, warp, RTP, spawn, AFK, and team-home commands during combat.
- Punishes combat logging and rewards the last online player who hit the logger.
- Applies a configurable reward cooldown per killer/victim pair (24 hours by default).
- Stores reward cooldowns in YAML so they survive restarts.
- Java and Bedrock players use the same server-side combat rules; no GUI is needed.

## Build
Requires Java 21 and Maven. Run `mvn -U clean package`. Output: `target/SupaCombat.jar`.

## Commands
- `/supacombat reload` — reload configuration.

## Permissions
- `supacombat.admin` — reload configuration (default: op).
- `supacombat.bypass` — bypass blocked commands while tagged (default: op).

## Configuration
Edit `plugins/SupaCombat/config.yml`. MiniMessage supports hex colors such as `<#37BFF9>`. The default reward command matches the supplied Skript's `giveshards give <killer> 10 -s`; install the plugin that provides `/giveshards` or change that command in config.

The original Skript's logout punishment/reward and normal-death handling were consolidated to avoid duplicate rewards. Combat state is memory-only and cleared on server restart, matching the original Skript's `on load` reset behavior.
