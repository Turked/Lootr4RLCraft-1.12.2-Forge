# AI Development Instructions — Lootr for RLCraft

## Primary Objective

Develop a complete, standalone Minecraft Forge 1.12.2 mod providing Lootr-style, per-player loot functionality specifically optimized for RLCraft.

**Working project name:** Lootr for RLCraft

The mod must provide every player with their own persistent loot inventory when opening naturally generated loot containers, with reliable visual indicators showing whether each player has previously opened them.

This must be an independent mod, NOT an addon to the original Lootr, NOT a modification of the existing Lootr: RLCraft Edition, and NOT a fork of either.

The final mod should function as a single distributable JAR within an otherwise unmodified, supported RLCraft installation.

## 1. Strict Legal and Intellectual Property Requirements

Minimize legal exposure through independent development and documented provenance.

1. Write all implementation code independently using the Minecraft Forge 1.12.2 API, Java standard libraries, and appropriately licensed development resources.
2. Do not copy, decompile, reverse-engineer, or extract code or assets from Lootr: RLCraft Edition.
3. Do not copy source code, artwork, textures, models, sound effects, branding, or other assets from the original Lootr. Although its code is MIT-licensed, this project should be independently implemented.
4. Do not duplicate Minecraft, RLCraft, or third-party mod assets inside the distributable JAR. Reference installed game assets through their normal resource systems when permitted.
5. Create original textures and visual designs. Gold and gray may be used as colors, but do not reproduce Lootr's exact texture artwork.
6. Avoid dependencies requiring redistribution of third-party source code or assets.
7. Review and record the licenses of all libraries, build tools, and external resources used. Include required notices where applicable.
8. Maintain `THIRD_PARTY_NOTICES.md`, `ASSET_PROVENANCE.md`, and `LEGAL_REVIEW.md`.
9. Do not imply endorsement, sponsorship, or official association with the Lootr or RLCraft creators.
10. Treat "Lootr for RLCraft" as a provisional development name. Before public distribution, verify permission to use the Lootr name or choose legally cleared, distinct branding. Use a unique internal mod ID regardless.
11. Do not claim the project has zero legal risk or is legally approved. Flag unresolved licensing and branding questions for human review.

Reference public documentation and supported APIs for understanding expected behavior. Implement independently rather than reproducing another mod's protected expression.

## 2. Technical Requirements

- Minecraft: Java Edition 1.12.2
- Mod loader: Forge 1.12.2
- Language: Java 8-compatible source and bytecode
- Primary environment: RLCraft 2.9.3
- Build system: ForgeGradle compatible with Minecraft 1.12.2
- Client and dedicated server support
- Multiplayer compatible
- No original Lootr dependency
- No Lootr: RLCraft Edition dependency
- No new MixinBooter dependency
- No new FermiumBooter dependency
- No mandatory coremod, mixin framework, or custom launcher
- No external runtime dependencies beyond Forge and the mods already present in the supported RLCraft installation

Use a unique internal mod ID and Java package namespace that do not collide with existing mods.

Inspect the actual RLCraft installation and its mod versions before writing compatibility integrations. Do not assume that every RLCraft version uses the same storage implementations.

Do not modify other mods' JAR files or require users to replace existing RLCraft components.

## 3. Per-Player Loot System

Implement an authoritative server-side inventory system.

Required behavior:

- Every eligible naturally generated loot container has one persistent identity.
- Each player has their own inventory associated with that identity.
- Opening a container for the first time generates or retrieves that player's inventory.
- Other players must never consume or alter that player's allocated loot.
- A player's inventory must preserve their changes across reopening, disconnecting, world saving, and server restarting.
- Different players may receive different items using the original loot table's rules.
- The system must respect deliberately fixed loot, guaranteed items, loot-table seeds where applicable, player luck, and modded loot behavior.
- Do not artificially force all players to receive different items.
- Do not simply copy one player's generated inventory to all other players.

Use Minecraft's native loot table mechanisms wherever technically possible.

For pregenerated inventories that do not retain loot-table information, preserve their original loot semantics instead of inventing random loot.

Provide configurable policies for special pregenerated inventories.

Ensure loot is generated or assigned exactly once per container/player combination, even during simultaneous interactions.

Never trust the client to determine inventory ownership, item generation, or inventory contents.

## 4. Supported Containers

Support all eligible RLCraft loot containers, prioritizing:

1. Standard single chests
2. Double chests
3. Spruce and other modded wood chests
4. Trapped chests
5. Modded cabinets
6. Barrels
7. Chest minecarts
8. Other loot-bearing inventories commonly generated in RLCraft structures

For every container, determine its actual block, tile entity, inventory, loot-table behavior, and renderer rather than assuming it is a vanilla chest.

Use a modular adapter system for different container implementations.

An adapter should be responsible for identifying supported containers, determining their persistent identity, accessing original loot behavior, and implementing compatibility-safe interaction and rendering.

Unsupported containers must retain their normal functionality. They must not be relabeled as Lootr containers unless the per-player behavior is actually working.

Never apply per-player loot automatically to ordinary player-created storage.

Double chests must behave as one consistent inventory where appropriate.

Minecart inventories need entity-safe persistent identifiers rather than relying on block positions.

## 5. Visual Rendering — Critical Requirement

The existing RLCraft-specific Lootr implementation has serious visual bugs. Avoid reproducing them.

Required visual behavior:

- An unopened eligible container displays an original gold-themed visual indicator.
- An opened eligible container displays an original gray-themed indicator.
- The visual state is specific to the viewing player.
- The correct appearance must display as soon as the player can see the container.
- Players must not have to interact with the container to make its texture appear.
- `F3 + A` must not be required.
- Chunk unloading and reloading must preserve the correct appearance.
- Reconnecting and restarting the game must preserve the correct appearance.
- A chest must never randomly revert to its vanilla appearance because of missing synchronization.

**IMPORTANT:** Do not simply replace an existing chest's model with a stretched custom texture.

Preserve the underlying modded container models and geometry whenever possible, including properly rendered double chests, spruce chests, cabinets, and barrels.

Use an original visual overlay, material treatment, or other appropriate technique that is compatible with each container's renderer.

For containers whose rendering system cannot safely be modified, implement a reliable alternative indicator rather than producing broken textures.

Do not label rendering support complete until the actual container has been visually tested in RLCraft.

## 6. Locks Compatibility — Critical Requirement

Integrate with the existing RLCraft Locks mod.

Required behavior:

- Locks remain visually attached to their containers.
- The underlying container still displays its appropriate unopened/opened Lootr state.
- A lock must not hide or permanently prevent the visual indicator.
- A locked container must not allow players to bypass the lock by interacting with the custom loot system.
- All existing key and lockpicking mechanics must remain functional.
- Do not replace a locked chest in a way that destroys or disconnects its lock.
- Lock states and ownership must remain consistent for all players.

Ensure the lock system controls authorization before any per-player inventory is opened.

Test lock interaction with single chests, double chests, modded chests, cabinets, and other compatible containers.

Do not modify the Locks mod itself.

## 7. RLCraft-Specific Compatibility

Support and preserve relevant behavior from:

- RLArtifacts and Mimics
- Luckified, when installed
- Locks
- RLCraft structure generation
- Modded loot tables
- Modded storage containers
- Inventory automation and interaction systems where applicable

Mimic handling is particularly important.

Do not accidentally eliminate Mimic spawning or create additional Mimic farming opportunities by independently triggering the first-open Mimic check for every player.

Respect the original gameplay conditions and probabilities wherever possible.

Ensure that Mimic events, loot assignment, and chest interactions cannot occur repeatedly due to network retries or concurrent player interactions.

Preserve Luckified's intended loot effects where the mod exposes compatible supported behavior.

If compatibility cannot be established safely through public hooks, isolate that limitation, document it, and avoid destructive fallback behavior.

## 8. Safe World Generation and Existing Worlds

Implement reliable container conversion or registration for newly generated structures.

An eligible structure container must retain correct loot behavior even when its inventory is first accessed by another mod or system.

Support newly generated chunks automatically.

For existing worlds, provide a safe, explicitly opt-in migration process. Migration must not silently overwrite, duplicate, reroll, or delete pre-existing loot.

Never assume a container is world-generated merely because it has a loot table.

Keep player-owned storage separate from naturally generated loot containers.

Persist container identities and loot state through chunk saves, world saves, and server restarts.

Investigate and prevent item duplication involving:

- Breaking containers
- Explosions
- Hoppers
- Pistons and movement systems
- Carry On
- Container replacement
- Concurrent inventory access
- Client disconnects
- Unexpected server shutdowns

Prioritize data integrity over automatic conversion.

Provide backup instructions before enabling migration on existing worlds.

## 9. Multiplayer State Synchronization

The server must be authoritative.

Ensure the client receives the correct visual state when:

- Joining the server
- Entering a dimension
- Loading or reloading a chunk
- Approaching a container
- Opening a container
- Reconnecting after disconnecting
- Restarting the game

Do not leak one player's private inventory contents to other clients.

Support three or more players opening the same container simultaneously.

Avoid excessive network traffic, permanent per-tick polling, unnecessary full-world scans, or uncontrolled memory growth.

Use reliable, versioned network packets and synchronization mechanisms compatible with Forge 1.12.2.

## 10. Configuration

Create a readable Forge configuration file with documented options for:

- Enable/disable per-player loot
- Enable/disable visual indicators
- Gold/gray state colors
- Supported and excluded container types
- Container adapter overrides
- Behavior for pregenerated inventories
- Existing-world migration policy
- Lock compatibility handling
- Mimic compatibility handling
- Debug logging
- Storage cleanup policy
- Administrator diagnostic settings

Choose safe defaults optimized for RLCraft.

Do not expose configuration options that cannot actually be implemented reliably.

## 11. Testing Requirements

Create repeatable tests and a clear manual testing matrix.

Testing must include:

**Single-player tests**
- New worlds
- Existing worlds
- Newly generated chunks
- Saving and reloading
- Every supported chest/container type
- Locked and unlocked containers
- Unopened/opened visual state
- Mimic-related behavior

**Multiplayer tests**
- Three players opening the same chest
- Different inventories when supported by the loot table
- Guaranteed loot behaving correctly
- Inventory persistence for each player
- Simultaneous interactions
- Disconnecting while an inventory is open
- Reconnecting after an inventory is modified
- Reopening containers after server restart

**Rendering tests**
- Vanilla chests
- Spruce/modded chests
- Double chests
- Cabinets
- Barrels
- Locked containers
- Chest minecarts
- Chunk reloads
- Long-distance chunk unloading
- Client reconnects

**Stability tests**
- Dedicated server startup
- RLCraft client startup
- World generation
- Structure exploration
- Large quantities of containers
- Repeated saving and loading
- No item duplication
- No loss of inventories
- No persistent server-tick performance problems

Use at least 100 newly generated eligible containers for loot-generation and persistence testing. Evaluate outcomes against the actual loot tables rather than requiring guaranteed differences between players.

Run tests in a clean RLCraft environment and a dedicated-server environment matching the supported version.

A successful Gradle build alone does not count as successful functionality testing.

If testing cannot be executed in the available environment, clearly mark it as unverified. Do not claim complete compatibility.

## 12. Development Process

Follow these stages:

**Stage 1 — Research and compatibility audit**

Inspect the supported RLCraft mod list, relevant API hooks, container implementations, and licensing.

Produce an architecture and compatibility report.

**Stage 2 — Core implementation**

Implement persistent per-container/per-player inventories and server-side loot generation.

Verify save/load behavior before implementing visuals.

**Stage 3 — Container integration**

Implement and test adapters for each required RLCraft container type.

**Stage 4 — Visual system**

Implement original gold/gray visual indicators, state synchronization, and correct model behavior.

**Stage 5 — RLCraft compatibility**

Integrate Locks, Mimics, Luckified, and structure-generation behavior.

**Stage 6 — Comprehensive testing**

Run integration tests and eliminate reproducible crashes, duplication exploits, synchronization defects, and rendering inconsistencies.

**Stage 7 — Legal and release audit**

Review implementation provenance, licenses, resource ownership, package contents, branding, and redistribution requirements.

Build the final release JAR only after all required acceptance criteria have passed.

## 13. Required Deliverables

Provide:

1. Complete Java source code.
2. Compilable ForgeGradle project.
3. Original textures and models.
4. Configuration file.
5. Final standalone mod JAR.
6. README with installation instructions.
7. Compatibility matrix.
8. Test results and known limitations.
9. License and third-party notices.
10. Asset provenance documentation.
11. Public-release legal review checklist.
12. Developer documentation explaining how additional RLCraft containers can be supported.

The mod must install by placing its JAR in the `mods` folder on both the client and server.

Do not package copyrighted third-party mod files inside the release.

## 14. Acceptance Criteria

The project is not complete until:

- The mod launches successfully with the supported RLCraft installation.
- Neither the original Lootr nor Lootr: RLCraft Edition is installed or required.
- No additional mixin/bootstrap dependency is required.
- All mandatory container types have functioning per-player loot.
- Visual indicators consistently show the correct player-specific state.
- Locked containers retain their lock rendering and security behavior.
- Double-chest textures and geometry are correct.
- Per-player inventories survive server restarts.
- Loot generation follows original loot-table semantics.
- Existing RLCraft mechanics remain functional.
- No known reproducible duplication or data-loss defect remains.
- All included code and assets have documented legal provenance.
- Any remaining technical limitation is explicitly documented.
- Naming and public-distribution issues are reviewed before publishing.

**Important:** Do not silently omit difficult requirements, implement placeholders, or claim success based only on compilation.

If a feature cannot be implemented safely or reliably, describe the technical blocker, provide evidence, and propose the safest alternative. Continue implementing independently testable portions of the project.

The goal is a stable, polished, genuinely RLCraft-native per-player loot mod, not another partially functional Lootr workaround.