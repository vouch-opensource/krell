import { npmDeps } from "./npm_deps.js";
import { krellNpmDeps } from "./krell_npm_deps.js";
import { assets } from "./krell_assets.js";

var METRO_IP = "$METRO_SERVER_IP";
var METRO_PORT = $METRO_SERVER_PORT;

const evaluate = eval;

global.CLOSURE_BASE_PATH = "$CLOSURE_BASE_PATH";
global.CLOSURE_NO_DEPS = true;

function toPath(path) {
    return CLOSURE_BASE_PATH.replace("goog/", "") + path;
}

var loadQueue = [];
var isLoading = false;

const loadFile = (path) => {
    return fetch("http://" + METRO_IP + ":" + METRO_PORT + "/" + path)
        .then(function(res) {
            evaluate(res.text);
        });
};

const loadPending = async () => {
    for(;;) {
        if(loadQueue.length === 0) {
            break;
        }
        let next = loadQueue.unshift();
        await loadFile(next);
    }
    isLoading = false;
};

const queueLoad = (path) => {
    loadQueue.push(path);
    if(!isLoading) {
        isLoading = true;
        setTimeout(loadPending, 250);
    }
};

var libLoadListeners = {};

const notifyListeners = (request) => {
    let path = request.value,
        xs = libLoadListeners[path] || [];

    xs.forEach(function (x) {
        x();
    });
};

const onSourceLoad = (path, cb) => {
    if(typeof libLoadListeners[path] === "undefined") {
        libLoadListeners[path] = [];
    }
    libLoadListeners[path].push(cb);
};

// =============================================================================
// Bootstrap Files

evaluate($CLOSURE_BASE_JS);
evaluate($CLOSURE_DEPS_JS);

// =============================================================================
// ClojureScript Dev Dependency Graph

evaluate($CLJS_DEPS_JS);

// =============================================================================
// Closure Load Customization and Monkey-patching

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
        queueLoad(path);
        return true;
    }
};

global.require = function(x) {
    return npmDeps[x] || krellNpmDeps[x] || assets[x];
};

// should be called after the main namespace is loaded
function bootstrapRepl() {
    // patch goog.isProvided to allow reloading namespaces at the REPL
    if(!goog.isProvided__) goog.isProvided__ = goog.isProvided_;
    goog.isProvided_ = (x) => false;
    if(!goog.require__) goog.require__ = goog.require;
    goog.require = (src, reload) => {
        if(reload === "reload-all") {
            goog.cljsReloadAll_ = true
        }
        if(reload || goog.cljsReloadAll_) {
            if(goog.debugLoader_) {
                let path = goog.debugLoader_.getPathFromDeps_(src);
                goog.object.remove(goog.debugLoader_.written_, path);
                goog.object.remove(goog.debugLoader_.written_, goog.basePath + path);;
            } else {
                let path = goog.object.get(goog.dependencies_.nameToPath, src);
                goog.object.remove(goog.dependencies_.visited, path);
                goog.object.remove(goog.dependencies_.written, path);
                goog.object.remove(goog.dependencies_.visited, goog.basePath + path);
            }
        }
        let ret = goog.require__(src);
        if(reload === "reload-all") {
            goog.cljsReloadAll_ = false
        }
        if(goog.isInModuleLoader_()) {
            return goog.module.getInternal_(src);
        } else {
            return ret;
        }
    };

    // enable printing
    cljs.core.enable_console_print_BANG_();
    let socket = getSocket();
    cljs.core._STAR_print_newline_STAR_ = true;
    cljs.core._STAR_print_fn_STAR_ = (str) => {
        socket.write(JSON.stringify({
            type: "out",
            value: str
        }));
        socket.write("\0");
    };
    cljs.core._STAR_print_err_fn_STAR_ = (str) => {
        socket.write(JSON.stringify({
            type: "err",
            value: str
        }));
        socket.write("\0");
    };
}

module.exports = {
    evaluate: evaluate,
    loadFile: loadFile,
    bootstrapRepl: bootstrapRepl,
    onSourceLoad: onSourceLoad
};
