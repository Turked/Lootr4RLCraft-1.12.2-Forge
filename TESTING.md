# RLCraft verification plan

## Development checks

The Java 8 storage and packet regressions cover 100 synthetic records with three player inventories each, compressed NBT, retained item data, empty inventories, failed-roll retries, update-tag privacy, packet validation, fixed templates, and isolation of mutable item metadata. These checks are distinct from the 100 actual generated containers required below.

`chestIntegrationTest` creates a temporary native Minecraft WorldServer with JSON loot tables and three server players. It checks default registration settings, generation registration for table/fixed/double chests, unchanged preexisting storage, conversion commands, native 27/54-slot menus and their exact `lootr` titles, guaranteed table items, slot-preserving fixed contents, and player withdrawals after tile NBT reload. It also checks repeated/busy conversion refusals and unloaded targets. The fixture invokes the adapter's interaction hooks explicitly because the plain test JVM does not run Forge's event-class transformer. This verifies the native inventory paths while leaving full event-bus ordering, mod interactions and rendering for the pack tests. Both development test tasks run as part of `build`.

The November 18 regression adds a real resource-pack check for format 3 and lowercase locale resources. The native-world fixture attaches a test chunk capability implementing Locks 3.0.0's integer phase contract. It verifies that original fixed items and pending tables stay untouched through phases 0 and 1, cross-chunk pairs wait for both chunks, changed inventory contents stop conversion, and eligibility survives tile NBT reload. This tests the adapter's timing and persistence, with actual Locks probability, key mechanics and rendering still requiring pack verification.

The 0.2.0 development suite automatically registers 100 native-world fixture containers across five inventory shapes, opens each for three simultaneous server players, and verifies native luck-dependent bonus rolls of 4, 8 and 12 items. It checks original tile retention, private item NBT, compressed private chunk-capability reload, fixed furnace and capability-only inventories, read-only menu padding, live furnace state changes, and absence of private records from tile update tags. This is a native-world integration test with fixture loot sources, separate from full RLCraft structure exploration.

Discovery tests exercise a 211-by-171 structure footprint, replacement at an existing position, disk-loaded ordinary storage, newly created terrain tiles, aborted scopes and additional generators without a population-start event. Mimic transaction tests exercise first-roll reuse, independent native automation buffers, duplicate completion, cross-chunk anchor migration, survivor persistence, preparation rollback and sneaking item bypass prevention.

An optional probe ran against development-remapped copies of the seven installed Quark, Rustic, Charm and Varied Commodities tile classes. Saved contents, layout, item NBT, physical source sealing and native table replay passed. Native Charm loot-size behavior was retained. Separate real-block probes verified Quark matching and mismatched wood pairing and Rustic's vertical cabinet pairing. These test copies stay outside the distributable and are used only on the test classpath. In-game rendering and full pack event ordering remain outside these headless checks.

A Forge-only dedicated-server baseline reached Minecraft's EULA check before mod initialization. The user's initial `RLCraft (1)` run loaded the old build and exposed the disabled-registration default, missing conversion commands, ordinary GUI titles, and unsupported double chests. The revised build addresses those paths. This paragraph records the early investigation. The November 23–25 dedicated and rendered results are indexed in [the checkpoint](DEVELOPMENT_CHECKPOINT.md).

## Pack acceptance

The tables below preserve the original test plan and its November 18 baseline. See [the current evidence matrix](VERIFICATION.md) and [the interruption checkpoint](DEVELOPMENT_CHECKPOINT.md) for the subsequent results. The compatibility audit records what was found in the existing client profile. The acceptance environment is a clean RLCraft 2.9.3 client and matching dedicated server, Minecraft 1.12.2, Forge 14.23.5.2860, and Java 8. Run with the same Lootr4RLCraft build on the client and server. Keep the stock pack configuration for baseline runs and use a separate disposable copy for diagnostics. [RLCraft client and matching server distribution](https://www.curseforge.com/minecraft/modpacks/rlcraft/files/4612979/additional-files)

## Set up and record

1. Start separate clean client and dedicated-server installations. Record the build name and checksum, Java version, mod list, seed, Forge version, and config differences. Keep the logs and original worlds outside the disposable copies used for destructive tests.
2. Boot the clean pack without Lootr4RLCraft and record sample vanilla and modded behavior. Add the development build to both sides. Test initial launch and joining again without other Lootr implementations or newly added bootstrap mods.
3. Test default settings in a new world and in a copy of a previously played world. Then opt into supported experimental options in a separate new world. Generate previously unvisited areas in both new and older worlds.
4. Record each investigated container by world, dimension, coordinates or entity UUID, structure, native loot table and seed if available, and block and tile class. Use spectator or server diagnostics to observe metadata without consuming pending loot. Keep a data snapshot before exercising first-open behavior.
5. For every case, record expected and observed results, pass/fail/blocked, and any diagnostic logs, screenshots, or save snapshots. Record cases separately for the integrated and dedicated server paths.

## Containers and persistence

| Scenario | Pass condition | Status |
| --- | --- | --- |
| New and previously played worlds | Existing containers and player storage are preserved by default. Supported newly generated containers in both worlds receive stable identities. | Unexecuted on Nov 18 |
| At least 100 newly generated eligible containers | Exercise actual structure loot across the supported types and sources. Log first rolls for three players where possible. Compare against table entries, conditions, guaranteed items, slot layout, luck, and explicit seeds. Equal outcomes are valid where the table permits them. | Unexecuted on Nov 18 |
| Standard and trapped single chests | Each eligible chest has private inventories. The chest keeps its shape and interaction rules. A trapped chest retains activation and redstone behavior. | Unexecuted on Nov 18 |
| Double chests | Both halves resolve to one logical 54-slot inventory per viewer. Repeat from either half and at a chunk boundary. Obstruction, lock, and break behavior honor both halves. | Unexecuted on Nov 18 |
| Quark wood chests and trapped variants | Exercise spruce and at least one other variant, including matched double chests and adjacent unlike variants. Pairing, loot, and textures follow the original variant. | Unexecuted on Nov 18 |
| Rustic cabinets and barrels | Exercise single and double cabinets and item barrels with legitimate generated loot. Brewing and liquid barrels keep their normal behavior. | Unexecuted on Nov 18 |
| Charm barrels, bookshelf chests and abandoned crates | Confirm table handling and contents against Charm’s own generator. Crates and other unsupported storage retain native behavior. | Unexecuted on Nov 18 |
| Varied Commodities barrels and other pregenerated containers | Test configured policies with known fixed contents and partially looted examples. Guaranteed and fixed contents remain consistent with the original policy. | Unexecuted on Nov 18 |
| Chest minecarts | Private inventory follows the same entity across movement and restarts. A replacement has a different identity. Removal or hopper extraction cannot duplicate loot. | Unexecuted on Nov 18 |
| Ordinary storage | Test placed chests, trapped chests, double chests, chests with items, and an administrator-created storage chest with a vanilla loot table. All retain native behavior unless deliberately enrolled through a supported migration. | Unexecuted on Nov 18 |
| Existing-world migration | With a backup and explicit opt-in, confirm only selected, verifiably eligible containers migrate, while previously opened and ordinary containers remain intact. Run the migration twice and compare inventories and identity. | Unexecuted on Nov 18 |

## Locks, Mimics, and other mods

| Scenario | Pass condition | Status |
| --- | --- | --- |
| Locked and unlocked container matrix | For every applicable container above, check lock attachment, both visual states, bare-hand denial, wrong and correct keys, key ring, lockpick success and failure, ownership, and interaction from the connected half. Repeat on two clients and confirm shared lock state. | Unexecuted on Nov 18 |
| Vanilla obstruction | Test a solid block and a sitting cat above chests, and obstruction over the opposite half of a double chest. Attempt opens with both hands, as spectator, and with sneaking or item interactions. | Unexecuted on Nov 18 |
| Mimic success | In a disposable world only, use a deterministic test setting for the native Mimic chance and verify server config reload or restart. Open an eligible unopened chest, then repeat with a separate chest broken before opening. Observe native replacement and spawn, and verify the original chest grants no private loot. | Unexecuted on Nov 18 |
| Mimic failure and repeated interaction | With deterministic no-spawn and stock-probability settings, compare first-open and first-break behavior with the stock pack. Verify retries, other players, and subsequent breaks cannot trigger new checks on an already opened container. Include locked and obstructed chests. | Unexecuted on Nov 18 |
| Luck and modded table hooks | Exercise changed luck values, loot-table modifications, and a known deterministic table. In a profile with the optional Luckified version installed, record which effects apply and compare with the unchanged pack. | Unexecuted on Nov 18 |
| Automation and movement | Try hoppers before any player and after players have used the chest, Forge item handlers, comparators, Carry On with its default and a custom key, pistons, and relevant container movement systems. Confirm actions cannot extract, move, or clone private loot and ordinary storage remains usable. | Unexecuted on Nov 18 |
| Container removal | Test breakage by a player, explosion, replacement and any supported movement. Record what happens to pending loot, allocated private loot, and deposits. Confirm there are no duplicates, stale indicators, or unintended reassignment when a new container appears at that position. | Unexecuted on Nov 18 |

## Multiplayer, visuals, and stability

| Scenario | Pass condition | Status |
| --- | --- | --- |
| Three simultaneous opens | Coordinate three distinct accounts to open one unopened chest in a narrow window. Check that each account has one allocation, that opening twice rerolls nothing, and that all contents remain private. Use deposits and removals with unique stacks to detect cross-player writes. | Unexecuted on Nov 18 |
| Disconnect and restart | Disconnect one client during slot transfers and reconnect after another client changes their own contents. Save and restart server and client. Verify the same private allocations and exact slot changes after each transition. | Unexecuted on Nov 18 |
| Visibility on first sight | For each supported container, compare gold before opening and gray after. Two clients looking at the same tile may see different states. Geometry, wood variants, barrels, minecarts, and Locks' attachment remain correct without interaction or a client chunk refresh. | Unexecuted on Nov 18 |
| Tracking transitions | Test join, dimension change, chunk reload, travel beyond view distance, return, disconnect/reconnect, and complete restart. Repeat for a locked tile, a paired tile, and a moving chest minecart. | Unexecuted on Nov 18 |
| Save and crash consistency | With backed-up disposable worlds, test clean saves and abrupt interruption at generation, initial allocation, an item transfer, chunk unload, and world save. Audit chest and player totals after recovery. A reproducible loss or duplicate blocks release under the supplied acceptance criteria. | Unexecuted on Nov 18 |
| Dedicated-server load and performance | Explore many new structures, open repeatedly, and unload and reload populated chunks. Compare startup and tick times with the pack baseline, look for unbounded state or packet traffic, and check no client-only code loads on the server. | Unexecuted on Nov 18 |

Once the functionality and compatibility runs pass, inspect the candidate JAR for its mod ID, owner metadata, Java 8 bytecode, required notices, and project-owned resources. Record its checksum against the tested build.
