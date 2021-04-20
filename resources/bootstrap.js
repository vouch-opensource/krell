import { npmDeps } from "./npm_deps.js";
import { krellNpmDeps } from "./krell_npm_deps.js";
import { assets } from "./krell_assets.js";

const evaluate = eval;
global.CLOSURE_BASE_PATH = "$CLOSURE_BASE_PATH";
global.CLOSURE_NO_DEPS = true;

evaluate($CLOSURE_BASE_JS);
evaluate($CLOSURE_DEPS_JS);
//evaluate($CLJS_DEPS_JS);
//evaluate($KRELL_REPL_DEPS_JS);

// NOTE: CLOSURE_LOAD_FILE_SYNC not needed as ClojureScript now transpiles
// offending goog.module files that would need runtime transpiler support
global.CLOSURE_IMPORT_SCRIPT = function(path, optContents) {
    if (optContents) {
        try {
            evaluate(optContents);
        } catch (e) {
            console.error("Could not eval ", path, ":", e);
            throw e;
        }
        return true;
    } else {
        loadFile(loadFileSocket, path, optContents);
        return true;
    }
};

global.require = function(x) {
    return npmDeps[x] || krellNpmDeps[x] || assets[x];
};


