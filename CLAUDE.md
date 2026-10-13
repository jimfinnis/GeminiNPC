# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Spigot/Bukkit Minecraft plugin (`org.pale.gemininpc.Plugin`) that adds a Citizens2 NPC trait giving NPCs LLM-driven conversation (Gemini via Google's API, or a local Ollama/Gemma model). It optionally interoperates with the Sentinel trait (guard/follow) and the JCFUtils plugin (region lookup). See README.md for full end-user/admin documentation (commands, persona file format, templating system, actions, waypoints) — it's kept up to date and is the primary reference for behavior, not just setup.

## Build

- `mvn clean install` (this is the Maven `defaultGoal`, so plain `mvn` also works) — produces a shaded jar at `target/gemininpc-0.0.2.jar`.
- `copy.bat` copies the built jar, and everything in `exampleconfig/` except `config.yml`, to a specific developer's local test server (`d:\mc\plugins\` and `d:\mc\plugins\GeminiNPC\`) — not portable, ignore unless asked about it. `exampleconfig/` mirrors the plugin's data folder and is the master copy of the personae, quests and templates: edit them in the repo, not on the server. `config.yml` is skipped because the server's copy holds the real API key; the repo's has a placeholder, so never copy the server's `apikey` into the repo.
- Source layout is non-standard: `<sourceDirectory>` is `src` directly (i.e. `src/org/pale/...`, not `src/main/java/org/pale/...`).
- Compiles against Java 21.

### Local system-scoped dependencies

Three plugin jars are consumed via Maven `system` scope with `systemPath` pointing at the project root, and must be present there to build: `Citizens-2.0.38-b3814.jar`, `Sentinel-2.9.2-SNAPSHOT-b527.jar`, `jcfutils-1.1.2.jar`. These are third-party/companion-plugin jars, not build artifacts — don't delete them, and don't expect them to resolve from Maven Central.

The shade plugin explicitly excludes these (and Spigot/Bukkit) from the final jar, since the target server already provides them — only GeminiNPC's own code and the langchain4j/basis-template/gson etc. dependencies get shaded in.

## Tests

JUnit 5 tests live in `./test` (e.g. `test/StringSplitTest.java`, `test/org/pale/gemininpc/quests/QuestLoadTest.java`), **not** `src/test/java`. The pom sets `<testSourceDirectory>test</testSourceDirectory>`, so `mvn test` runs them, and so does the default `clean install` (a failing test fails the build). Tests that read fixture files use paths relative to the project root, e.g. `exampleconfig/quests/banner.yml`.

Quest loading (`Quest`) doesn't need the plugin running: warnings go into a list passed to the constructor rather than through `Plugin.log`, which would fail outside a server.

## Architecture

### Plugin lifecycle and singletons

`Plugin` (extends `JavaPlugin`) is a hard singleton (constructor throws if instantiated twice) reachable via `Plugin.getInstance()`. On `onEnable()` it: verifies Citizens2 is present, registers `GeminiNPCTrait` with Citizens' trait factory, builds the `Model` (LLM client) from config, loads personae/templates, and installs `ChatEventListener`. It also schedules an infrequent repeating task that nudges one random "chatter" NPC via `updateInfrequent()` so idle NPCs occasionally emit unprompted behavior (rate-limited per-NPC).

Two independent reflection-based registries provide the plugin's two extension points, both scanning methods alphabetically via `CommandRegistry.sortedMethods`:
- **`@Cmd`** (`commands/`) — methods on `Plugin` (and any other registered handler) become `/gemini <subcommand>` commands. `CommandRegistry` handles arg-count/permission/"needs a selected NPC" checks before invoking; `CallInfo` wraps the sender/args/selected-NPC for the handler.
- **`@Action`** (`actions/`) — methods on `Actions` become actions the *LLM itself* can trigger by naming them in its structured response (grouped by `group=`, e.g. `default`, `waypoints`, `sentinel`, `writer`). `ActionRegistry.handleAction` parses `"actionname args..."` out of the model's response and invokes the matching method with an `ActionInfo`. Which groups are even offered to a given NPC is decided by the `{{actions("groupname")}}` template calls inside the `common` template (see Templating below) — e.g. gated behind `{{if isSentinel}}`/`{{if hasWaypoints}}`.

### Per-NPC state: GeminiNPCTrait

Each NPC with the trait gets its own `GeminiNPCTrait` instance holding, lazily created on first use and torn down by `reset()` (called on `/gemini reload` or a persona change):
- `Chat` — the langchain4j session (`ai/Chat.java`): wraps `AiServices` over a `ChatModel` with a `MessageWindowChatMemory`, seeded with the NPC's fully-rendered system instruction. The model is expected to return structured JSON matching `Chat.Response` (`player`, `text`, `action` fields) — this is what lets one LLM turn both "say something" and "perform an action" at once.
- `ContextBuilder` (`ai/ContextBuilder.java`) — builds the JSON "context" object sent with every turn (time/weather, JCFUtils region, waypoint proximity, nearby real players, light levels, combat/Sentinel health+guard state, inventory, and the persona's `current-state` template). Only the **diff** since the last turn is actually sent (`getContextDiffs`, via `utils/Json.getDifferences`) to save tokens — most turns send an almost-empty context.
- `TemplateFunctions` (`utils/TemplateFunctions.java`) — per-NPC template function bindings (`choose`, `pick`, `exclude`, `random`, `get`/`set` on a private NPC map, `getother`/`setother` on another NPC's map, `map`/`mapset`/`list`/`listadd` on plugin-global collections, `has`, `at`, `actions`). Holds a PRNG seeded from the NPC's name (or an explicit seed) so randomized persona choices are stable across restarts.
- `Waypoints` (`waypoints/`) — named locations + descriptions, used both for context ("you are near X") and for LLM-driven navigation (the `go` action / `/gemini go`).

`respondTo(player, utterance)` is the single entry point that talks to the LLM: it's invoked from chat proximity+facing checks (`Plugin.handleMessage`), NPC-right-click gives, shop purchases (batched via a short timer, `checkPurchaseTimer`), damage/kill events, arrival greets (`processGreet`), and the infrequent idle nudge. It runs the actual model call on a background `Thread` and pushes the parsed `Chat.Response` onto a `ConcurrentLinkedQueue`; the per-tick `update()` (called from `run()`, throttled to ~0.5 Hz via `MAXTICKINT`) drains that queue back on the main thread and calls `processResponse` to speak the text and dispatch any `action` through `ActionRegistry`. This queue hop is what keeps the blocking LLM call off the server's main thread.

`ChatEventListener` (not `GeminiNPCTrait`) owns all Bukkit event hooks that aren't NPC-trait callbacks — chat, right-click, damage/death tracking (including re-attributing arrow damage to the shooter), navigation complete/cancel, and shop purchases.

### Personae and templating

Persona system instructions are rendered **once**, when a `Chat` is first created for an NPC (or after a persona/gender change resets it) — not per-turn. The `current-state` template (optional, YAML personae only) is the one exception: it's re-rendered on every turn as part of the context, for personality aspects that should drift.

Templating is the `io.marioslab.basis.template` engine (`ai/Persona.java`). A single static `TemplateLoader.MapTemplateLoader` (`Persona.tl`) is preloaded at config-load time with every file from `common-template-directories` plus `common-templates` config entries, so persona strings can `{{include "name"}}` them. Persona files are plain text or YAML, decided by the file name: only a `.yml` file is YAML (`string`, `default-gender`, `template-values`, `npc-map` initial values, `current-state`, `yaml-files` for extra data pulled from the plugin data folder); anything else, e.g. `personae/prune`, is used as the persona string as it stands, so it mustn't contain YAML syntax. After rendering the persona string itself, it's re-injected as the `persona` variable and rendered again through the `common` template — that's where the JSON envelope (including the `actions` map, built from whichever `{{actions("group")}}` calls fire) gets assembled around it. See README.md's "Templating" and "What is sent" sections for the exact variable/function list and JSON shape — don't reverse-engineer it from code when the README already documents it.

### Quests (in progress on `feature/quests`)

`QUESTS_TODO.md` tracks what's done and what's left; it's the place to look before working on quests. The example quest is `exampleconfig/quests/banner.yml`. In outline:
- `quests/Quest.java` loads one quest file (facts, triggers, participants with their `when:`/`prompt:`/`actions:`, objectives, `complete-when`). Errors are `QuestException`s whose message says where in the file the problem is; warnings go into a list passed in, so loading doesn't need the plugin.
- `quests/expr/` is the expression language used by `when:`, `done:` and `complete-when`.
- `quests/triggers/` holds triggers (an `on:` condition plus a `do:` list) and the `TriggerEvent`s they respond to. `quests/actions/` holds `ActionList`, the reusable `do:` list (one-key maps such as `- set: f`) used by triggers and NPC quest actions, and later `on-complete`.
- `QuestRegistry` holds the loaded quests by ID (`Plugin.quests`, rebuilt on each config load) and does the checks that need several quests or the NPC list. `QuestNPCs` resolves a name used in a quest to an NPC: its name, or failing that a persona used by exactly one NPC.
- Persona/quest split: personae hold only an NPC's public face and cover stories; each secret goes in the quest's stage prompt where the NPC may use it, because an LLM can't reliably keep a secret that's in its prompt. See the comment above `participants:` in `banner.yml`.

### Config

`config.yml` (loaded into `exampleconfig/config.yml` as a documented example) drives model selection (`model.model` — presence of `"gemini"` vs `"gemma"` in the name picks `GoogleAiGeminiChatModel` vs `OllamaChatModel` in `ai/Model.java`), persona/template/common-template/quest directory lists, default gender/respond-probability, and the `texts` map used for user-facing strings (`Plugin.getText`). Only some of config is reloadable via `/gemini reload` (model + personae + templates + quests); see the comment in `Plugin.loadConfigAndCreateModel`/`loadConfig` for the split.