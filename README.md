# bedrock-cljs

Write Minecraft Bedrock script add-ons in ClojureScript. One shadow-cljs
build produces finished packs: compiled scripts, generated `manifest.json`,
deployment to your dev pack folders, and a `.mcaddon` on release.

```
guix shell -m manifest.scm     # node + JDK
npm install
npm run watch                  # dev loop: save, then /reload in game
npm run release                # :advanced build -> target/cljs_demo.mcaddon
npm test                       # library tests (Node, mocked game modules)
npm run smoke                  # run the compiled add-on against the mocks
```

## Layout

| Path | What |
|---|---|
| `pack.edn` | Pack name, version, UUIDs, deploy folders. Source of every manifest. |
| `packs/BP/`, `packs/RP/` | Static pack files (entities, items, textures…). `packs/RP` is optional; if it exists, an RP is built and the two packs depend on each other. |
| `src/main/bedrock/` | The library (`core`, `commands`, `ui`) and the build hook (`build.clj`). |
| `src/addon/` | Your add-on. `example.main/init` is the entry point. |
| `test/` | Mock `@minecraft/server` / `server-ui` and the smoke test. |

## How it fits together

- **Native modules stay native.** The build is `:target :esm` with
  `:js-provider :import`, so `(:require ["@minecraft/server" :as mc])`
  compiles to a real `import … from "@minecraft/server"`, which is what Bedrock
  expects. The npm packages are only used for their version numbers (and as
  type documentation).
- **Manifests are generated.** `bedrock.build/pack` scans the compiled output
  for `@minecraft/*` imports and adds each as a dependency, using the version
  from `node_modules`. To target a beta API, `npm i -D @minecraft/server@beta`
  (becomes `"2.x.0-beta"`) or set `:script-modules` in `pack.edn`.
- **Release builds are `:advanced`.** Interop on game objects needs `^js` hints
  so Closure doesn't rename properties; missing hints are build *errors*
  (`:infer-warning` is in `:warnings-as-errors`). The smoke test runs the
  advanced output against strict mocks to catch anything that slips through.
- **Dev loop.** Bedrock scripts have no WebSocket, so shadow's live-reload
  client is off. Set `:deploy` in `pack.edn` (or `BEDROCK_BP_DIR` /
  `BEDROCK_RP_DIR`) to your `development_behavior_packs` folder; every save
  mirrors the pack there, and `/reload` in game (or `reload` on a dedicated
  server's console) picks it up.

## Library

```clojure
(ns my.addon
  (:require [bedrock.core :as bc]
            [bedrock.commands :as cmd]
            [bedrock.ui :as ui]))

;; Events by keyword:  :player-spawn -> world.afterEvents.playerSpawn,
;; :before/x, :system/x (system.afterEvents), :system.before/startup
(bc/on :player-spawn (fn [^js ev] (bc/tell (.-player ev) "hi")))
(bc/on :system/script-event-receive {:namespaces ["demo"]} handler) ; filter opts, camelCased
;; `on` returns an unsubscribe fn; handler exceptions are logged with ex-data.

;; Ticks (20/s) instead of setTimeout
(bc/run-after (bc/seconds->ticks 5) f)  (bc/run-every 20 f)  (bc/run-next f)

;; Persistent state: EDN in a world dynamic property, loaded at world load
(defonce scores (bc/world-atom :demo/scores {}))

;; Commands, data-driven.  Handler gets {:origin :source :player} and an
;; args map.  Runs next tick by default so it may change the world; a
;; returned string is sent to the player.
(cmd/register! {:name :demo/heal :permission :game-directors
                :params [[:targets :player-selector]] :optional [[:amount :integer]]}
               (fn [ctx {:keys [targets amount]}] ...))

;; Forms return promises of plain data
(-> (ui/modal! player {:title "Settings"
                       :fields [[:name :text "Name" {}]
                                [:pvp :toggle "PvP" {:default true}]
                                [:color :dropdown "Color" {:options ["red" "blue"]}]]})
    (.then (fn [{:keys [name pvp color]}] ...)))
```

Plain interop always works too: `bc/world`, `bc/system`, or require
`"@minecraft/server"` directly.

## Bedrock constraints to keep in mind

- **Early execution.** Top-level code and `init` run before the world exists:
  subscribe and register here, but touch the world from event handlers.
  `world-atom` and `cmd/register!` already handle this.
- **Restricted execution.** Command callbacks and `:before/*` events can't
  change the world; use `bc/run-next`. `cmd/register!` defers for you unless
  you pass `:mode :restricted` (the handler then returns the status message
  synchronously).
- **No `eval`** unless the pack declares the `script_eval` capability.
  Nothing here needs it.

## Not verified yet

Everything above is tested against mocks, not a running game. The first time
you load it in Bedrock, check that `/demo:menu`, `/demo:heal @s 5`, the
compass menu and the welcome message work, and that the dev build (many
files under `scripts/cljs-runtime/`) loads as well as the single-file release.

## Ideas for next steps

- A REPL: on a dedicated server, `@minecraft/server-net` plus `script_eval`
  could poll a local relay for compiled forms and evaluate them in the game.
- Atom-backed `ObservableString`/`ObservableNumber` for server-ui's
  reactive `CustomForm`.
- Publish `src/main/bedrock` as its own library and turn this repo into a
  template (`npx create-…`), with UUIDs generated on creation.
