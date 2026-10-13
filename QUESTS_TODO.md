# Quests: still to do

What remains on `feature/quests`. The example quest ("The Ominous Banner") is
`test/org/pale/gemininpc/quests/banner.yml`. Edit it there: `copy.bat` copies it to the test
server's `quests` folder under the same name.

## Done

- An expression language for conditions in `quests/expr`, covering the lexer, Pratt parser,
  values and evaluator, with tests in `test/org/pale/gemininpc/quests/expr`.
- At load time, `when:`, `done:` and `complete-when` are parsed and each identifier is checked
  against the quest's `facts`, as are the facts named in `set:`.
- Loading of `id`, `title`, `scope`, `requires`, `facts`, `triggers`, `participants` (including
  their actions), `objectives` and `complete-when`, with tests in `QuestLoadTest`.
- A trigger framework (`Trigger`, `ITriggerCondition`, `TriggerEvent`) with two conditions,
  `give` and `read-book`. A trigger has a condition (`on:`) and a list of actions (`do:`).
- Reusable action lists in `quests/actions`. `ActionList` parses a list of one-key maps
  (`- set: f`) and runs them in order. Each action is an `IQuestAction` and gets an
  `ActionContext` holding the instance, the player and the triggering event (null when no
  trigger is involved). The only action so far is `set`, which sets one or more facts. Add new
  action types in `ActionList.parseAction`.

## The loader doesn't match the example file

Fix these first. As things stand they make the loader silently ignore parts of the quest, or
throw.

- [x] **Triggers are ignored.** `triggers:` is a YAML list, but `Quest.loadTriggers` reads it
      with `getConfigurationSection`, which returns null for a list. Use `getMapList` instead,
      as `loadNPCData` does.
- [x] **Rumours are ignored.** The example file now uses the format `Fact` expects:
      `rumours: { name: { spreads-to, text } }`.
- [x] **`scope: player` is ignored.** `Quest` reads a boolean `player_only` instead. Read
      `scope` as `player` or `world`, and reject any other value.
- [x] **`give "ominous banner" to ladychanter` will throw.** An ominous banner isn't a
      `Material`; it's a white banner with a particular pattern. Either match on the item's
      display name, lore or other item data, or document that `give` takes only a plain
      material name.
  - [ ] Test in game that the ominous banner matches. The plan is to set its display name to
        "Ominous Banner" (e.g. with `/jcf dname`), since the vanilla name is a translatable
        component that `getItemName()` won't return as plain text. Matching the banner pattern
        instead isn't worth the effort, but it does mean any item renamed in an anvil will match.
  - [x] Colour codes in the display name (`/jcf dname &6Ominous Banner`) no longer stop it
        matching: `GiveCondition` and `ReadBookCondition` strip them from both names.
- [x] **`read-book` trigger condition.** `ReadBookCondition` (`read-book NAME`) matches a
      `TriggerEvent.ReadBook` on the book's display name or title, ignoring case. `ChatEventListener`
      detects reading through `PlayerInteractEvent` (right-clicking a written book or book and
      quill in hand) and `InventoryOpenEvent` (opening a lectern), both of which call
      `bookRead`. Still to do:
  - [ ] Join them up: `bookRead` only logs; it should fire a `TriggerEvent.ReadBook` (see
        "Firing triggers" below).
  - [x] `ReadBookCondition.check` calls `name.equalsIgnoreCase(...)` on the event's name, so a
        book with no display name will throw if `bookRead` passes null. Swap the comparison
        round or pass `""`.
  - [x] Colour codes are stripped from both names before comparing.
  - [x] A written book's title also matches: `TriggerEvent.ReadBook` carries both the display
        name and the title (null for a book and quill), and either can match. `bookRead` will
        need to pass both when it's joined up.
  - [ ] Remove the debug code: the `snark` helper (unused), the logging in `bookRead`, and the
        unused imports (`Material`, `InventoryEvent`, `PlayerTakeLecternBookEvent`).
  - [x] The `read-book` parse error doesn't include the condition text; the `give` one does.
  - [ ] Test in game: books in either hand, sneak-clicking a block while holding a book, and
        lecterns. `PlayerInteractEvent` fires once per hand, so one click may fire twice; that's
        harmless while the only action is `set`.
- [x] **Facts named in `set:` are checked**, for both triggers and NPC actions, by
      `Quest.factNames`. `set:` can be a single fact or a list in both places.
- [ ] **Validate the rest of the file at load time:**
  - [x] every `objectives[].done` and `complete-when` parses as an expression against the
    facts (`Quest.parseExpression`, which `when:` also uses);
  - [ ] `requires` should name quests that exist; check this after all quests have loaded;
  - [ ] participant names should be known NPCs, or at least a typo should produce a warning.
- [x] `objectives` and `complete-when` are loaded into `Quest.objectives` and
      `Quest.completeWhen`. `complete-when` is optional; without it a quest is open-ended.
- [ ] Load `on-complete` as an `ActionList` (it's already written in that format in
      `banner.yml`); it's read nowhere yet. It needs the `give` and `start` action types, which
      need the plugin. `ActionList` is constructed from a section and a key, so it can load
      the top-level `on-complete` directly.

## Wiring quests into the plugin

- [ ] **Keep the loaded quests.** `Plugin` builds a `Quest` for each file and then throws it
      away. Store them in a map by `id` and rebuild it on `/gemini reload`.
- [x] **Decide what happens when a quest file is bad.** A bad file is now skipped with a
      warning such as `Quest file test.yml not loaded: participants.helga #2: when: expected a
      value but the expression ended, in "not"`, and the plugin carries on. As a
      `QuestException` passes up through the loader, `QuestException.in` adds where the problem
      is to its message. Also:
  - YAML syntax errors are reported with their line number. `loadConfiguration` used to
    swallow them, and the result was a misleading "no id".
  - `section` and `mapList` in `Quest` check the shape of values, which Bukkit used to drop
    silently. For example, `- give x` instead of `- on: give x` was ignored.
  - Unknown keys produce a warning, but the quest still loads.
  - Triggers with no action, and NPC conditions with neither `prompt:` nor `actions:`, are
    reported.
  - Unexpected exceptions, which are probably loader bugs, are logged with a stack trace.
  - Only `.yml` and `.yaml` files are loaded, so editor backups are skipped.
  - [x] Tests for broken files, in `test/org/pale/gemininpc/quests/QuestLoadTest.java`. It also
        checks that the example quest, `banner.yml`, loads with no warnings.
  - [ ] Duplicate quest `id`s across files aren't detected. Do this when quests are kept in a
        map (see the item above).
  - [x] `Plugin.processFilesInConfigDirectory`, which personae and templates also use: a missing
        directory no longer stops the whole config loading, and the `~` backup check now tests
        each file rather than the directory name.
- [ ] **Quest state.** Started: `Instance` holds a fact-value map, and `Quest.start` /
      `getInstance` keep one per player UUID for `scope: player` and one for `scope: world`.
      Still to do:
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
- [ ] **Firing triggers.** `Trigger.check(player, event)` finds the instance and runs the
      actions on it. Still to do:
  - `Trigger.check` is package-private, so add a public entry point (on `Quest` or a quest
    manager) that checks an event against every loaded quest's triggers.
  - Call `Trigger.check` from `ChatEventListener` on the matching Bukkit events: NPC
    right-click gives for `give`, and `bookRead` for `read-book`.
- [ ] **Commands for debugging**, e.g. `/gemini quest list`, `/gemini quest state PLAYER`,
      `/gemini quest set PLAYER QUEST FACT true|false` and `/gemini quest reset`.

## NPC quest actions (`participants.<npc>[].actions`)

These are things the LLM can choose to do, such as Helga's `confess`. They're the hardest
part, because they connect quest data to the existing `@Action` system, which is static and
built by reflection.

- [x] **Loading.** `NPCQuestCondition.actions` maps each action name to an `Action` record of
      the description and an `ActionList` loaded from `do:`, the same kind of list a trigger
      has. Both `desc:` and `do:` are required. When the action runs, its `ActionContext`
      should have the NPC whose LLM chose it, and the player from the reply.
  - The NPC's `prompt` decides whether and when the NPC does something (Prune reveals
    Angren's identity "only if the player shows they know his name"). The `desc` says what
    event the action records, so that the LLM includes the action in the reply in which that
    event happens. Write it as one visible event, e.g. "You tell the player that Angren the
    tomb-keeper is Lofgar", not as a condition or a vague state. The plan is for the template
    to supply the "use this when..." instruction, so authors don't have to; it doesn't yet
    (see "Offering actions to the LLM" below).
- [ ] **Offering actions to the LLM.**
  - An action is available only while its `when` is true, and only to that NPC.
  - The current action list comes from `{{actions("group")}}` in the `common` template, built
    once when the system instruction is rendered. So quest actions must either go in the
    per-turn context along with the quest prompts, or the system instruction must be
    regenerated whenever they change. The context is likely to be the better option.
  - Describe them consistently with the existing actions, using a name and a description.
  - Introduce them with a standing instruction in the template, roughly: "These actions record
    important story events. If your reply does what an action describes, you must include
    that action in the same reply. Don't do the action without the event, or the event without
    the action." The likely failure is the NPC revealing something in its text but leaving
    `action` empty. The fact then never gets set, and the player can be stuck (e.g. Lofgar
    never gets his prompt).
- [ ] **Only one action per reply.** `Chat.Response.action` is a single string, so a reply
      that needs a quest action and a built-in one (e.g. Helga confesses and hands over an
      item) loses one of them. Since quest actions are what move the story on, make `action`
      a list, or give quest actions their own field, before wiring quest actions in. This
      affects the JSON shape in the `common` template, `Chat.Response`, `processResponse` and
      the README.
  - A quest action's `do:` list already covers combinations the author can foresee: Helga's
    `confess` could set facts and give an item in one action. This item is for the
    combinations they can't.
- [ ] **Recovering from a missed action.** The NPC's `when` stays true after it reveals
      something in text without the action, so it will probably reveal it again when asked,
      which gives the LLM another chance; that's not guaranteed, though. The admin escape hatch
      is `/gemini quest set` (see "Commands for debugging"). If testing shows actions being
      missed often, consider a cheap second check, such as a small LLM call asking "did this
      reply reveal X?".
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
  - [x] NPC quest actions have a `do:` list too, so they get every action type.
  - [ ] If an action type turns out to be unsafe for the LLM to fire, since an LLM may choose
        an action by mistake, let `ActionList` take a set of allowed types.
- [ ] **Trigger action that tells nearby NPCs** (e.g. `tell-nearby: TEXT`): add text to the
      context of LLM NPCs that can see the triggering event, so that they can react to it. It
      would use `ActionContext.event` and `player` to find where the event happened, and needs
      the plugin.

## Later / low priority

- [ ] Objectives: show the player their current objectives, through a command, a book or the
      scoreboard.
- [ ] Completion: check `complete-when` after every state change, then run `on-complete`, which
      handles `give ITEM` and `start: QUEST`.
- [ ] Counters or other non-boolean facts. The expression language already handles integers
      and strings, so most of the work is in the state and in the actions, e.g. `add:`.
- [x] The `x` placeholder for facts with no data. The example now uses `fact: {}` instead.
- [ ] YAML quoting: a condition that starts with a quote, `[`, `{`, `*`, `&`, `!` or `#`, or
      contains `: `, must have the whole value quoted. Document this in the README.
- [ ] README section on quests: file format, the expression language, triggers and actions.
- [x] Get Maven to run the tests in `./test`, by setting `<testSourceDirectory>`. Note that
      `mvn` (`clean install`) now fails if a test fails.
