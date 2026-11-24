// Loads the compiled add-on (dev or :advanced release) against the mocks and
// drives it through the game's startup sequence.  Catches property renaming,
// missing externs and anything that only breaks after compilation.
//   npm run release && npm run smoke
import assert from "node:assert/strict";
import { __mock as mc, Player, world } from "@minecraft/server";
import { __mock as ui } from "@minecraft/server-ui";

await import(new URL("../target/BP/scripts/main.js", import.meta.url).href);

// Top-level code ran in "early execution": subscriptions only.
assert.ok(mc.subscribers("system.beforeEvents.startup") >= 2, "commands wait for startup");
const { commands } = mc.startup();
assert.deepEqual(Object.keys(commands).sort(), ["demo:heal", "demo:menu"]);
assert.equal(commands["demo:heal"].def.permissionLevel, 1);
assert.deepEqual(commands["demo:heal"].def.mandatoryParameters, [{ name: "targets", type: "PlayerSelector" }]);

// Persistent greeting.
const alex = new Player("Alex");
mc.fire("world.afterEvents.playerSpawn", { player: alex, initialSpawn: true });
mc.fire("world.afterEvents.playerSpawn", { player: alex, initialSpawn: true });
assert.match(alex.messages[0], /Welcome, Alex.*#1/);
assert.match(alex.messages[1], /Welcome back, Alex.*#2/);
assert.equal(mc.dynamic.get("demo:visits"), '{"Alex" 2}');

// Command with a selector argument, deferred to the next tick.
let hp = 5;
const health = { get currentValue() { return hp; }, effectiveMax: 20, setCurrentValue(v) { hp = v; } };
alex.getComponent = (id) => (id === "minecraft:health" ? health : undefined);
assert.equal(commands["demo:heal"].cb({ sourceEntity: alex }, [alex], 7), undefined);
assert.equal(hp, 5, "deferred until next tick");
mc.tick();
assert.equal(hp, 12);

// Menu -> "Who has visited?"
ui.respond({ canceled: false, selection: 1 });
commands["demo:menu"].cb({ sourceEntity: alex });
mc.tick();
await new Promise((r) => setTimeout(r, 10));
assert.equal(ui.shown.at(-1).form.titleText, "CLJS Demo");
assert.equal(alex.messages.at(-1), '{"Alex" 2}');

// Script events.
mc.fire("system.afterEvents.scriptEventReceive", { message: "hi" });
assert.equal(mc.messages.at(-1), "§dpong: hi");

console.log("smoke test passed");
