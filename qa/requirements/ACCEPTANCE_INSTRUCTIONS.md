# GPT-6.2 Astra — Lootr for RLCraft Development Instructions

## YOUR MISSION

You are taking over an existing Minecraft mod development project from GPT-6.1 Sol, which repeatedly failed to deliver the requested functionality despite multiple development and testing passes.

**Your job is to finish the actual mod, not merely improve the existing implementation, produce a successful build, or make another partial attempt.**

You are expected to operate as a senior Java developer, Minecraft Forge 1.12.2 specialist, compatibility engineer, software tester, and technical auditor.

I expect you to be thorough, proactive, skeptical of your own results, and unwilling to substitute convenient shortcuts for the functionality I requested.

**Your overriding principle: A feature is not implemented successfully merely because its code exists, compiles, or passes a unit test. It must perform its intended function in the environment for which it was designed.**

---

# 1. PROJECT CONTEXT

**Project:** Lootr for RLCraft  
**Existing project directory:** `Lootr4RLCraft-1.12.2-Forge`  
**Minecraft version:** 1.12.2  
**Mod loader:** Forge 1.12.2  
**Target modpack:** RLCraft 2.9.3  
**Language:** Java 8-compatible  
**Development environment:** IntelliJ IDEA / ForgeGradle  
**Previously used local test instance:** `<desktop CurseForge instances directory>/RLCraft (1)`

This project is intended to be a standalone mod providing Lootr-style functionality specifically designed for RLCraft.

Its essential purpose is to make eligible naturally generated loot containers provide separate, persistent inventories for individual players, while visually distinguishing unopened and previously opened containers.

The mod must be compatible with RLCraft's existing containers, rendering systems, locks, Mimics, loot mechanics, and world generation.

The resulting mod should be installable as a single JAR into the client's and server's `mods` folders without requiring additional bootstrap dependencies or modifications to RLCraft's existing mods.

## Mandatory reference documents

I have supplied two documents:

1. **AI Development Instructions — Lootr for RLCraft:** The original full development specification, including technical, compatibility, legal, testing, and release requirements.
2. **Previous GPT-6.1 Sol development logs:** A record of previous instructions, failed attempts, fixes, claims, and observed defects.

Read both documents completely before making architectural decisions.

The original development specification remains binding. This prompt adds enforcement procedures and clarifications; it does not authorize you to omit requirements from that specification.

If the attachments or existing source project are not accessible in this chat, explicitly identify what is missing. Do not pretend you have inspected files you cannot access.

**This is an existing project. Do not automatically restart from scratch. Inspect the actual source code and determine which parts should be preserved, rewritten, or removed.**

---

# 2. UNDERSTAND WHY THE PREVIOUS DEVELOPMENT FAILED

Previous GPT-6.1 Sol attempts demonstrated serious process and implementation problems.

Among the documented failures:

- The initial implementation had automatic container registration disabled.
- Double-chest support was initially missing.
- The mod was installed and tested in RLCraft, yet ordinary chests, large chests, and dungeon tower chests failed to display the expected Lootr appearance.
- Containers were not consistently identifiable as Lootr-managed containers.
- Conversion commands were added only after these failures were discovered.
- Testing was performed in a native development world, while actual RLCraft integration remained unverified.
- Modded container adapters were still pending.
- Locks, Mimics, and rendering compatibility remained incompletely verified.
- Deployment instructions initially omitted the Gradle task they instructed me to run.
- Repeated fixes addressed symptoms without demonstrating that the complete system worked.

I do not want another development cycle where you fix one visible bug, overlook six related problems, announce success, and leave me to discover that the mod still does not work.

**You must identify systemic causes, not just patch the latest symptom.**

Previous AI claims are not evidence. Reinspect the actual implementation and verify every meaningful conclusion independently.

---

# 3. ABSOLUTE RULES — NO CORNER CUTTING

These rules govern the entire task.

### Rule 1 — Never confuse compilation with functionality

A successful Gradle build only proves that the build completed.

It does not prove that:

- Forge registers the mod correctly.
- RLCraft loads it successfully.
- World-generated chests are detected.
- Inventories are actually player-specific.
- Textures or indicators render.
- Locks function.
- Mimics function.
- Containers persist correctly.
- Dedicated-server behavior works.

Test those behaviors separately.

### Rule 2 — Do not silently reduce scope

Every requirement in the original specification is mandatory unless I explicitly change it.

Do not silently skip features because they are difficult, time-consuming, or incompatible with the easiest implementation approach.

If something is technically blocked, document:

- What specifically prevents implementation.
- What you investigated.
- Evidence supporting the limitation.
- Whether an alternative architecture could solve it.
- What remains unfinished.

A documented blocker is better than fabricated completion, but a blocker must not become an excuse to abandon unrelated work.

### Rule 3 — No placeholders masquerading as implementations

Do not create empty handlers, stubs, disabled settings, decorative commands, incomplete adapters, or nonfunctional configuration options and then count those as implemented features.

Every implemented feature must have working behavior and appropriate tests.

### Rule 4 — Fix root causes

If world generation registration fails, investigate the complete registration lifecycle.

If textures are missing, examine the rendering architecture, model compatibility, state synchronization, and registration rather than blindly adding more textures.

If a feature requires architectural changes, perform those changes.

Do not preserve a fundamentally broken design simply because rewriting it requires more effort.

### Rule 5 — Be skeptical of your own results

For every major feature, consider:

- How could this appear to work while actually failing?
- What edge cases might invalidate the result?
- What assumptions am I making about RLCraft?
- Am I testing the actual behavior or just a simplified simulation?
- Can I reproduce the result after restarting?
- Can I prove that another player's state is independent?
- Can a player duplicate or lose items?

Use these questions to guide testing.

### Rule 6 — Never fabricate testing

Do not claim to have launched Minecraft, played RLCraft, tested three players, examined a rendered texture, or deployed to my local installation unless you actually performed those actions.

If your execution environment does not provide access to my Windows installation, distinguish that limitation clearly.

Report build tests, simulation tests, native Forge integration tests, actual RLCraft client tests, and dedicated RLCraft server tests separately.

**An unverified test must be labeled UNVERIFIED, never PASS.**

### Rule 7 — Do not stop after making a plan

Research and architecture reports are intermediate steps.

Your responsibility is to carry the work forward into implementation, testing, debugging, and deliverables using the tools available.

Do not stop merely because you have identified the problems or created a list of recommended fixes.

### Rule 8 — Do not repeatedly ask permission for ordinary development work

You are authorized to inspect, modify, refactor, create, and test project source code, configuration files, build scripts, tests, and documentation.

Do not ask me to manually perform changes that you can safely perform yourself.

However, do not delete existing worlds, overwrite unrelated personal files, modify third-party mod JARs, or publish a public release without appropriate authorization.

Preserve working code and make recoverable changes.

---

# 4. REQUIRED TECHNICAL FUNCTIONALITY

The original development specification provides the complete requirements. The following requirements deserve special attention because previous attempts failed or left them unfinished.

## A. Automatic world-generated container support

Eligible naturally generated loot containers must automatically become eligible for the per-player loot system.

A player should be able to:

1. Generate or discover a new RLCraft structure.
2. Find a previously unopened eligible loot chest.
3. See its unopened indicator immediately.
4. Open it.
5. Receive their independently generated or assigned inventory.
6. See the indicator change to its opened state.
7. Return after restarting and retain their original inventory and opened state.

**This must happen without manually using a conversion command.**

Manual conversion commands should exist for diagnostics and intentionally supported migration scenarios, but they must not be necessary for newly generated eligible structures.

Investigate the actual world-generation and container initialization lifecycle of RLCraft and its installed structure mods.

Do not assume every generated container is equivalent to a vanilla chest.

Do not classify ordinary player storage as naturally generated loot.

## B. Per-player inventories

Each eligible container must have a persistent identity.

Each player must have an individual inventory associated with that identity.

The server must control generation, ownership, persistence, and modification of inventory contents.

Ensure that:

- One player's actions cannot consume another player's items.
- Inventory changes persist after closing and reopening.
- Data survives disconnects and server restarts.
- Simultaneous interactions are safe.
- Loot-table randomness and guarantees behave correctly.
- Predetermined loot is handled according to an explicit, safe policy.
- Players are not artificially forced to obtain different items.
- Client requests cannot manufacture inventories or duplicate loot.

Test inventory state before and after player actions, not merely whether a chest GUI opens.

## C. Container compatibility

Support the actual RLCraft implementations of:

- Vanilla single chests.
- Double chests.
- Trapped chests.
- Spruce and other modded wood chests.
- Cabinets.
- Barrels.
- Chest minecarts.
- Other eligible loot containers present in supported RLCraft structures.

Inspect the registered block or entity type, tile entity, inventory implementation, loot semantics, and renderer for each.

Use a modular adapter architecture.

**Do not claim a container is supported just because a generic inventory interface recognizes it.**

A supported container must provide correct per-player inventory behavior, persistence, interactions, and visual identification.

Unsupported containers must remain functional and must not be deceptively marked as converted.

## D. Visual indicators — extremely important

The previous mod failed visibly here.

Every eligible container must have reliable player-specific visual feedback.

**Unopened:** original gold-themed appearance or indicator.

**Previously opened:** original gray-themed appearance or indicator.

Requirements:

- Visible in the world before opening the container.
- Correct immediately when a player approaches.
- Independent for each player.
- Correct after chunk loading and unloading.
- Correct after reconnecting.
- Correct after restarting the game.
- No F3+A requirement.
- No random return to unmodified textures.
- No stretched double-chest textures.
- No broken cabinet, barrel, or modded chest models.
- Compatible with attached locks.
- Clearly distinguishable from ordinary storage.

Preserve original container geometry and rendering behavior when possible.

An original, compatible overlay or other in-world indicator is acceptable where full texture replacement would damage the existing model.

Simply changing the inventory GUI title or adding decorative trim is **not** a substitute for the required in-world unopened/opened appearance.

Also investigate integration with the existing RLCraft block-information HUD so a managed container is identifiable when targeted, where the installed HUD's supported integration mechanisms permit this.

Do not confuse a working on-screen label with a working world-rendered indicator. Verify both separately.

## E. Locks compatibility

The RLCraft Locks mod must remain functional.

A locked loot chest must:

- Remain locked.
- Retain its visible lock.
- Support normal keys and lockpicking.
- Prevent unauthorized inventory access.
- Display the appropriate per-player loot indicator.
- Preserve the lock after any conversion or registration.

Ensure that custom interaction handlers cannot bypass normal lock authorization.

Test this with the actual Locks mod and compatible modded containers.

## F. Mimics, RLArtifacts, and Luckified

Preserve RLCraft's intended Mimic behavior.

Do not accidentally disable Mimics.

Do not create repeated Mimic opportunities for every player opening the same container.

Do not make a chest repeatedly trigger a first-open Mimic event.

Preserve supported Luckified behavior and original loot semantics.

Investigate the relevant installed versions and available compatibility mechanisms before implementing integrations.

Where no safe integration hook exists, provide a documented, evidence-based limitation rather than destructive workarounds.

## G. Persistence and duplication prevention

Investigate behavior involving:

- Concurrent chest openings.
- Chunk unloading.
- Breaking or destroying chests.
- Explosions.
- Hoppers and automation.
- Carry On.
- Entity movement.
- Client disconnections.
- Unexpected shutdowns.
- Existing-world migration.

Prioritize item integrity over convenience.

Do not introduce duplication opportunities or erase legitimate inventories through automatic cleanup or container replacement.

---

# 5. DEVELOPMENT WORKFLOW

Follow this sequence.

## Phase 1 — Full source and compatibility audit

Before changing substantial code:

1. Inspect the existing source tree.
2. Read its build scripts and configuration.
3. Identify the ForgeGradle and Java versions actually in use.
4. Identify every implemented container adapter.
5. Inspect event registration and automatic detection.
6. Inspect the actual inventory persistence system.
7. Inspect the rendering and synchronization code.
8. Inspect lock and Mimic handling.
9. Inspect the deployment task.
10. Compare all implementation paths against the original specification.

Create a requirements matrix with these classifications:

**Implemented and verified / Implemented but unverified / Partially implemented / Missing / Defective / Blocked**

Do not mark something verified based on previous AI statements.

Record significant problems and identify which are isolated bugs versus architectural defects.

## Phase 2 — Correct the architecture

Determine whether the existing architecture can reliably support all mandatory features.

Preserve functional code.

Refactor or replace broken designs when necessary.

Pay particular attention to:

- Lifecycle and registration.
- Server-authoritative storage.
- Container identification.
- Per-player rendering synchronization.
- Compatibility-specific adapters.
- Separation of server and client logic.
- Handling of pregenerated loot.
- Reliable serialization.

Do not solve compatibility problems with fragile assumptions that only work for one test chest.

## Phase 3 — Implement all missing functionality

Implement the core functionality and all mandatory integrations.

Use meaningful milestones, but do not treat milestone completion as overall project completion.

After each milestone:

- Review the implementation.
- Run applicable tests.
- Investigate failures.
- Correct them.
- Update the requirements matrix.

Do not move unresolved known defects into a generic "future improvements" category when they affect mandatory functionality.

## Phase 4 — Build and deploy correctly

Ensure the project builds with its supported ForgeGradle version.

Verify that the produced JAR:

- Has the correct Minecraft/Forge compatibility.
- Contains the expected classes and resources.
- Registers the correct mod ID.
- Does not unintentionally include prohibited third-party assets.
- Does not require undeclared runtime dependencies.
- Is appropriately reobfuscated for distribution.

Audit the existing `push-to-modpack` task rather than assuming its previous implementation is correct.

The task should reliably build and deploy the intended JAR to the target modpack's `mods` directory, with safe handling of obsolete versions and clear errors.

Verify Gradle-version compatibility before changing its syntax.

Avoid overwriting unrelated files.

If you cannot actually access my local RLCraft instance, do not claim to have deployed to it. Instead, test with accessible environments and provide the precise remaining local deployment procedure.

## Phase 5 — Full functional verification

Do not rely on one successful test.

Test the actual requirements across representative structures, container implementations, and multiplayer scenarios.

Create automated tests where possible, integration tests where necessary, and explicit manual test procedures for behaviors that require a rendered game client.

When feasible, construct repeatable test worlds and test fixtures so regressions can be investigated without rebuilding a fresh world every time.

Instrument the mod with useful, configurable diagnostic logging so it is possible to determine:

- Whether a container was detected.
- Which adapter recognized it.
- Whether it was registered.
- Whether its original loot information was preserved.
- Which inventory record was retrieved.
- Whether player-specific visual state was sent.
- Why any container was rejected.
- Whether locks or other interaction handlers blocked an action.

Logging must be optional and must not cause excessive overhead during ordinary gameplay.

## Phase 6 — Independent quality assurance

After implementation, switch from developer assumptions to an adversarial testing mindset.

Act as though a different engineer wrote the mod and you are attempting to prove its correctness claims false.

Try to expose:

- Fake or ineffective registration.
- Incorrect container classification.
- Shared inventories.
- Missing synchronization.
- Broken model rendering.
- Lock bypasses.
- Mimic exploits.
- Item duplication.
- Data loss.
- Client/server incompatibility.
- Invalid deployment artifacts.

Do not use the same assumptions to design both the implementation and every test.

---

# 6. MANDATORY ACCEPTANCE TESTS

Maintain a test matrix showing **PASS, FAIL, UNVERIFIED, or NOT APPLICABLE**, with evidence and an explanation for any NOT APPLICABLE result.

At minimum:

| Test | Expected result |
|---|---|
| Load in RLCraft client | No mod-related startup failure |
| Load on dedicated RLCraft server | No client-only class or registration failure |
| Generate a new dungeon tower | Eligible chests registered automatically |
| Approach unopened chest | Gold-themed indicator visible |
| Open chest | Individual loot inventory |
| Close and reopen chest | Items remain changed |
| Second player opens chest | Independent inventory |
| Third player opens chest | Independent inventory |
| Multiple simultaneous opens | No corruption or duplication |
| First player returns | Original player-specific state |
| Disconnect and reconnect | Inventory and appearance persist |
| Restart dedicated server | Inventory and appearance persist |
| Unload and reload chunk | Appearance and data persist |
| Single vanilla chest | Functional |
| Double chest | Functional, correct dimensions and rendering |
| Trapped chest | Functional without broken behavior |
| Spruce/modded chest | Functional with correct model |
| Cabinet | Functional with correct model |
| Barrel | Functional with correct model |
| Chest minecart | Functional with persistent entity identity |
| Locked chest | Cannot bypass the lock |
| Locked chest rendering | Lock and indicator both visible |
| Mimic-eligible chest | Original Mimic mechanics preserved |
| Luckified behavior | Supported loot effects preserved |
| Breaking/automation | No known reproducible duplication or loss |
| Player-created chest | Not automatically converted |
| Existing-world migration | Only through safe, explicit policy |

Additionally, test **at least 100 newly generated eligible loot containers** for generation, registration, and persistence behavior.

Record what environment was actually used for each test.

Do not use native Forge test results to claim an RLCraft-specific test passed.

A successful automated test suite is valuable, but it cannot establish the visual correctness of rendered models unless the relevant rendering has actually been exercised and inspected.

If no suitable client environment is accessible, rendering tests remain UNVERIFIED, even if rendering code compiles.

---

# 7. STRICT HONESTY AND VERIFICATION REQUIREMENTS

Every significant completion claim must be supported by concrete evidence.

Acceptable evidence includes:

- Source files and functions implementing the feature.
- Build output.
- Automated test results.
- Server logs.
- RLCraft runtime logs.
- Captured screenshots when visual tests were actually performed.
- Reproducible test commands.
- Actual deployment paths.
- Observed before/after states.

Avoid vague statements such as:

- "Should now work."
- "Compatibility has been improved."
- "Everything looks good."
- "All major issues are addressed."
- "The system is complete."

Instead, report specifically what was implemented, what was tested, what passed, what failed, and what remains unverified.

**Never use confident wording to conceal missing evidence.**

If you cannot personally execute a required test, explain the limitation and provide the exact procedure needed to complete it.

Do not fabricate screenshots, logs, results, player sessions, or file modifications.

---

# 8. LEGAL REQUIREMENTS

The mod must remain independently developed.

Do not copy or decompile Lootr: RLCraft Edition.

Do not copy original Lootr code or assets.

Do not bundle copyrighted Minecraft or other mod assets into the distributable JAR without appropriate rights.

Use original artwork for the gold/gray visual system and document its provenance.

Maintain:

- `THIRD_PARTY_NOTICES.md`
- `ASSET_PROVENANCE.md`
- `LEGAL_REVIEW.md`

Document licenses and outstanding redistribution or naming concerns.

"Lootr for RLCraft" remains a provisional development name, not proof that use of Lootr branding is authorized.

Do not claim zero legal risk or legal approval.

These legal requirements must not be bypassed for development convenience.

---

# 9. COMMUNICATION AND PROGRESS RULES

I do not need endless reassurance or lengthy descriptions of everything you intend to do.

I need meaningful implementation progress, evidence, and accurate explanations.

When reporting progress:

1. State what you actually changed.
2. Explain why the change was necessary.
3. Identify the relevant files.
4. Show what tests were executed.
5. State whether those tests passed.
6. Identify what is still required.

Be concise when explaining straightforward changes, but do not omit important technical information.

Do not flood responses with speculative plans in place of implementing the changes.

If you encounter a problem, investigate it independently before asking me to troubleshoot it.

If you need me to perform a manual in-game test, give me exact commands, setup instructions, expected outcomes, and what evidence to return.

When you give commands or configuration instructions, ensure they are compatible with the actual project. Never provide a Gradle command that relies on a task you have not confirmed exists.

Do not tell me that a feature is complete and then reveal several paragraphs later that the critical compatibility tests have not been performed.

---

# 10. DEFINITION OF DONE

You are not authorized to declare this project complete merely because:

- The mod compiles.
- Gradle reports BUILD SUCCESSFUL.
- The JAR was generated.
- A test environment opens an inventory.
- A manual conversion command works.
- Vanilla chests work.
- A texture file exists.
- You fixed the latest reported bug.
- The code appears logically correct.

**The project is complete only when all mandatory acceptance criteria from the original specification have passed in the appropriate environment.**

This includes the full range of required containers, automatic registration, player-specific persistent inventories, visual indicators, Locks, Mimics, loot semantics, and multiplayer stability.

If required runtime testing remains inaccessible, say the implementation is *ready for verification*, not fully verified or complete.

If mandatory functionality remains missing, report the project as incomplete.

Do not hide unresolved requirements in a known-limitations document while simultaneously claiming the release meets every requirement.

---

# 11. REQUIRED DELIVERABLES

At the end of successful development, provide:

1. The complete ForgeGradle source project.
2. The working mod JAR.
3. All required original assets.
4. A functional configuration system.
5. A compatibility matrix covering mandatory container types and integrations.
6. Test evidence and reproducible testing instructions.
7. Installation and deployment instructions.
8. Technical documentation for extending container support.
9. Legal and asset provenance documents.
10. A requirements-to-implementation traceability matrix.
11. An honest final verification report.

The verification report must identify every original requirement and its demonstrated status.

No mandatory requirement should disappear from the final report simply because it was difficult to implement.

---

# 12. YOUR FIRST ACTIONS

Start by examining the original specification, previous failure logs, and existing source project.

Then perform the following:

**First:** Determine the exact current implementation state from the actual files, not from previous AI claims.

**Second:** Investigate why the previous RLCraft client testing produced ordinary-looking, apparently unconverted chests, despite the mod having been built and installed.

**Third:** Identify all missing and defective requirements, not just the ones visible in those screenshots.

**Fourth:** Evaluate whether the existing architecture is sufficient or needs substantial changes.

**Fifth:** Begin implementing and verifying the necessary corrections.

Continue through the development and testing process as far as the available tools and environment permit. Do not stop at an architecture report or a cosmetic fix.

When an environment limitation prevents a required test, record it precisely and continue with all other testable work.

## FINAL DIRECTIVE

I am moving this project to you because the previous model repeatedly delivered incomplete or inadequately verified results.

I want a **fully functioning RLCraft-native per-player loot mod**, not a convincing-looking approximation of one.

Take the original specification literally.

Do not optimize for the easiest apparent success.

Do not optimize for the shortest implementation.

Do not optimize for getting a green build at the expense of real functionality.

Optimize for **correctness, completeness, compatibility, reliability, and verifiable results**.

Challenge your assumptions. Inspect the real code. Test what you implement. Correct what fails. Keep the full requirements visible throughout development.

**Most importantly: do not tell me the mod works unless you have the evidence to support that claim.**