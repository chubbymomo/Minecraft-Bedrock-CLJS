// A small, deliberately strict stand-in for @minecraft/server.
// Only what the library and tests touch is implemented; anything else is
// undefined, so a property renamed by :advanced compilation fails loudly.

class Signal {
  constructor(name) { this.name = name; this.subs = []; }
  subscribe(cb, opts) { this.subs.push({ cb, opts }); return cb; }
  unsubscribe(cb) { this.subs = this.subs.filter((s) => s.cb !== cb); }
}

const signals = new Map();
const signalGroup = (prefix) =>
  new Proxy({}, {
    get(_, prop) {
      if (typeof prop !== "string") return undefined;
      const key = `${prefix}.${prop}`;
      if (!signals.has(key)) signals.set(key, new Signal(key));
      return signals.get(key);
    },
  });

export const CommandPermissionLevel = { Any: 0, GameDirectors: 1, Admin: 2, Host: 3, Owner: 4 };
export const CustomCommandStatus = { Success: 0, Failure: 1 };
export const CustomCommandParamType = Object.fromEntries(
  ["BlockType", "Boolean", "EntitySelector", "EntityType", "Enum", "Float", "Integer",
   "ItemType", "Location", "PlayerSelector", "String"].map((k) => [k, k]));

const messages = [];
const dynamic = new Map();

export class Player {
  constructor(name) {
    this.name = name;
    this.typeId = "minecraft:player";
    this.messages = [];
    this.nameTag = name;
  }
  sendMessage(m) { this.messages.push(m); }
}

let earlyExecution = true;
const requireWorld = (what) => {
  if (earlyExecution) throw new Error(`${what} cannot be used in early-execution`);
};

export const world = {
  afterEvents: signalGroup("world.afterEvents"),
  beforeEvents: signalGroup("world.beforeEvents"),
  players: [],
  sendMessage(m) { messages.push(m); },
  getAllPlayers() { return this.players; },
  getDynamicProperty(id) { requireWorld("getDynamicProperty"); return dynamic.get(id); },
  setDynamicProperty(id, v) {
    requireWorld("setDynamicProperty");
    if (v === undefined) dynamic.delete(id); else dynamic.set(id, v);
  },
};

let tick = 0;
let nextId = 1;
const runs = new Map();

export const system = {
  afterEvents: signalGroup("system.afterEvents"),
  beforeEvents: signalGroup("system.beforeEvents"),
  get currentTick() { return tick; },
  run(cb) { return this.runTimeout(cb, 0); },
  runTimeout(cb, delay = 1) {
    const id = nextId++;
    runs.set(id, { cb, at: tick + Math.max(1, delay) });
    return id;
  },
  runInterval(cb, every = 1) {
    const id = nextId++;
    runs.set(id, { cb, at: tick + Math.max(1, every), every: Math.max(1, every) });
    return id;
  },
  clearRun(id) { runs.delete(id); },
  waitTicks(n) { return new Promise((res) => this.runTimeout(res, n)); },
};

// ---- test controls -------------------------------------------------------

const fire = (key, ev) => {
  const sig = signals.get(key);
  for (const { cb } of sig ? [...sig.subs] : []) cb(ev);
};

export const __mock = {
  messages,
  dynamic,
  signals,
  subscribers: (key) => (signals.get(key)?.subs ?? []).length,
  fire,
  tick(n = 1) {
    for (let i = 0; i < n; i++) {
      tick++;
      for (const [id, r] of [...runs]) {
        if (r.at <= tick) {
          if (r.every) r.at = tick + r.every; else runs.delete(id);
          r.cb();
        }
      }
    }
  },
  // Simulate the game's startup sequence: startup (early execution),
  // then world load.  Returns the registered custom commands.
  startup() {
    const commands = {};
    const enums = {};
    const registry = {
      registerCommand(def, cb) { commands[def.name] = { def, cb }; },
      registerEnum(name, values) { enums[name] = values; },
    };
    fire("system.beforeEvents.startup", { customCommandRegistry: registry });
    earlyExecution = false;
    fire("world.afterEvents.worldLoad", {});
    return { commands, enums };
  },
  reset() {
    messages.length = 0;
    dynamic.clear();
    signals.clear();
    runs.clear();
    world.players = [];
    earlyExecution = true;
  },
};
