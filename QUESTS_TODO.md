# Quests: still to do

What remains on `feature/quests`. The example quest is
`D:\mc\plugins\GeminiNPC\quests\test.yml` ("The Ominous Banner").

## Done

- An expression language for conditions in `quests/expr`, covering the lexer, Pratt parser,
  values and evaluator, with tests in `test/org/pale/gemininpc/quests/expr`.
- At load time, `when:` conditions are parsed and each identifier is checked against the
  quest's `facts`.
- Loading of `id`, `title`, `requires`, `facts` (in part) and `participants`.
- A trigger framework (`Trigger`, `ITriggerCondition`, `ITriggerAction`, `TriggerEvent`) with
  one condition, `give`, and one action, `set`. The `set` action does nothing yet.

## The loader doesn't match the example file

Fix these first. As things stand they make the loader silently ignore parts of the quest, or
throw.

- [ ] **Triggers are ignored.** `triggers:` is a YAML list, but `Quest.loadTriggers` reads it
      with `getConfigurationSection`, which returns null for a list. Use `getMapList` instead,
      as `loadNPCData` does.
- [ ] **Rumours are ignored.** The file puts `spreads-to:` and `rumour:` directly under the
      fact. `Fact` expects `rumours: { name: { spreads-to, text } }`. Pick one format.
- [ ] **`scope: player` is ignored.** `Quest` reads a boolean `player_only` instead. Read
      `scope` as `player` or `world`, and reject any other value.
- [ ] **`give "ominous banner" to ladychanter` will throw.** An ominous banner isn't a
      `Material`; it's a white banner with a particular pattern. Either match on the item's
      display name, lore or other item data, or document that `give` takes only a plain
      material name.
- [ ] **`read-book` isn't a trigger condition yet.** Add it, along with a `TriggerEvent` record
      and a listener hook. It probably needs `PlayerEditBookEvent`, or a check on opening a
      book, since there's no direct "read" event.
- [ ] **Facts named in `set:` aren't checked.** Check every fact named by a trigger's or an
      action's `set:` against `facts` at load time, as `when:` identifiers already are.
- [ ] **Validate the rest of the file at load time:**
  - every `objectives[].done` and `complete-when` should parse as an expression against the
    facts;
  - `requires` should name quests that exist; check this after all quests have loaded;
  - participant names should be known NPCs, or at least a typo should produce a warning.
- [ ] Load `objectives`, `complete-when` and `on-complete`; they're read nowhere yet.

## Wiring quests into the plugin

- [ ] **Keep the loaded quests.** `Plugin` builds a `Quest` for each file and then throws it
      away. Store them in a map by `id` and rebuild it on `/gemini reload`.
- [ ] **Decide what happens when a quest file is bad.** One broken quest file should log a
      clear error naming the file and the problem, not stop the plugin from loading. Parse
      errors already have good messages; add the file name and the condition text.
- [ ] **Quest state:**
  - a fact-value map per quest, kept per player for `scope: player` and once for `scope: world`;
  - which quests each player has started or completed, and which are active, so that
    `requires` and `start:` can work;
  - how a quest starts in the first place: automatically when everything it requires is
    complete, or through `on-complete: start:` from another quest, or both.
- [ ] **Persistence.** Save quest state across restarts, either in a YAML file per player or one
      file for everything, under the plugin data folder.
- [ ] **Evaluating conditions.** Build an `Evaluator` from a state's fact map, setting each fact
      to `Value.TRUE` or `Value.FALSE`. Every fact must be set, or the evaluator throws
      `unknown identifier`.
- [ ] **Adding prompts.** For each NPC/player pair the NPC talks to, find the active quests where
      the NPC is a participant and collect the `prompt` of every `when` that's true.
  - Decide where this goes: the system instruction is rendered only once per chat, so quest
    text must go in the per-turn context, probably through `ContextBuilder`, so that changes
    show up through the existing context-diff mechanism.
  - The context goes per NPC, but quest state is per player, so it depends on who the NPC is
    talking to. Think about what happens when several players are nearby.
- [ ] **Rumours.** When a fact with rumours becomes true, add each rumour's text to the context
      of the NPCs in `spreads-to`. The names there can be single NPCs or groups such as `[nuns]`,
      so a group needs defining somewhere, perhaps in the persona or in config.
- [ ] **Firing triggers.**
  - `SetAction.run()` needs the quest state, so the action needs the event, at least
    `player()`, or a context object.
  - Call `Trigger.check` from `ChatEventListener` on the matching Bukkit events, for example
    NPC right-click gives for `give`.
- [ ] **Commands for debugging**, e.g. `/gemini quest list`, `/gemini quest state PLAYER`,
      `/gemini quest set PLAYER QUEST FACT true|false` and `/gemini quest reset`.

## NPC quest actions (`participants.<npc>[].actions`)

These are things the LLM can choose to do, such as Helga's `confess`. They're the hardest
part, because they connect quest data to the existing `@Action` system, which is static and
built by reflection.

- [ ] **Loading.**
  - `NPCQuestCondition` should parse `actions:` as a map from action name to `set:` (one fact or
    a list) plus `desc:`.
  - `desc` is optional; see `reveal_angren`. Decide on a default.
  - Check the facts named in `set:` against the quest's facts.
- [ ] **Offering actions to the LLM.**
  - An action is available only while its `when` is true, and only to that NPC.
  - The current action list comes from `{{actions("group")}}` in the `common` template, built
    once when the system instruction is rendered. So quest actions must either go in the
    per-turn context along with the quest prompts, or the system instruction must be
    regenerated whenever they change. The context is likely to be the better option.
  - Describe them consistently with the existing actions, using a name and a description.
- [ ] **Dispatch.** `ActionRegistry.handleAction` looks up only the reflected `@Action` methods.
  - It needs a fallback: if the name isn't a built-in action, look it up among the quest actions
    currently available to this NPC and player.
  - Look out for name clashes with built-in actions and between quests; prefix the names or
    reject clashes at load time.
- [ ] **Checking the action is valid.** Check the action against the current state when the LLM's
      response arrives, which can be some time after the request was sent. If its `when` no
      longer holds, or the response names an action that was never offered, ignore it.
- [ ] **Which player?** The response's `player` field gives the target. Use it to find the
      per-player state, and decide what to do if that player isn't in the quest.
- [ ] **What actions can do.** Only `set:` exists now. Possible additions: `clear:`, giving
      items, starting another quest, or running a built-in action such as `give`.

## Later / low priority

- [ ] Objectives: show the player their current objectives, through a command, a book or the
      scoreboard.
- [ ] Completion: check `complete-when` after every state change, then run `on-complete`, which
      handles `give ITEM` and `start: QUEST`.
- [ ] Counters or other non-boolean facts. The expression language already handles integers
      and strings, so most of the work is in the state and in the actions, e.g. `add:`.
- [ ] The `x` placeholder for facts with no data. Try `fact: {}` instead.
- [ ] YAML quoting: a condition that starts with a quote, `[`, `{`, `*`, `&`, `!` or `#`, or
      contains `: `, must have the whole value quoted. Document this in the README.
- [ ] README section on quests: file format, the expression language, triggers and actions.
- [ ] Get Maven to run the tests in `./test`, by setting `<testSourceDirectory>` or something
      similar.
