// node --import ./test/register.mjs <file>
// Resolves Bedrock's native script modules to the mocks in test/mock/.
import { registerHooks } from "node:module";

const mocks = {
  "@minecraft/server": new URL("./mock/server.mjs", import.meta.url).href,
  "@minecraft/server-ui": new URL("./mock/server-ui.mjs", import.meta.url).href,
};

registerHooks({
  resolve(specifier, context, next) {
    if (mocks[specifier]) return { url: mocks[specifier], shortCircuit: true };
    return next(specifier, context);
  },
});
