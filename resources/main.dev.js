import {
    evaluate,
    getSocket,
    onSourceLoad,
    onKrellReload,
    onKrellCacheInvalidate
} from './krell_repl.js';

var main = '$KRELL_MAIN_NS';

function toPath(path) {
    return CLOSURE_BASE_PATH.replace("goog/", "") + path;
}

function nsToPath(ns) {
    let segs = ns.split("."),
        last = segs[segs.length-1];
    segs[segs.length-1] = last + ".js";
    return toPath(segs.join("/"));
}

function bootstrap() {
    try {
        evaluate(KRELL_CACHE.get(toPath("goog/base.js")).source);
        evaluate(KRELL_CACHE.get(toPath("goog/deps.js")).source);
        evaluate(KRELL_CACHE.get(toPath("cljs_deps.js")).source);
        evaluate(KRELL_CACHE.get(toPath("krell_repl_deps.js").source));
        console.log("Bootstrapped from cache");
    } catch(e) {
        console.log("Bootstrap from cache failed:", e);
    }
}

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

function waitForCore(cb) {
    // we only care if goog/base.js is actually in the cache, that's enough
    // to bootstrap regardless whether some things must be refetched
    if(KRELL_CACHE.ready &&
       KRELL_CACHE.has(toPath("goog/base.js")) &&
       KRELL_CACHE.has(toPath("goog/deps.js")) &&
       KRELL_CACHE.has(toPath("cljs_deps.js")) &&
       KRELL_CACHE.has(toPath("krell_repl_deps.js"))) {
        bootstrap();
        cb();
    } else if(typeof cljs !== 'undefined') {
        cb();
    } else {
        setTimeout(function() { waitForCore(cb); }, 250);
    }
}

function exists(obj, xs) {
    if(xs.length >= 1) {
        let key = xs[0],
            hasKey = obj.hasOwnProperty(key);
        if (xs.length === 1) {
            return hasKey;
        } else {
            if(hasKey) {
                return exists(obj[key], xs.slice(1));
            }
        }
        return false;
    } else {
        return false;
    }
}

function getIn(obj, xs) {
    if(obj == null) {
        return null;
    } else if(xs.length === 0) {
        return obj;
    } else {
        return getIn(obj[xs[0]], xs.slice(1));
    }
}

function getMainFn(ns) {
    let xs = ns.split(".").concat(["_main"]),
        fn = getIn(global, xs);
    if(fn) {
        return fn;
    } else {
        throw new Error("Could not find -main fn in namespace " + ns);
    }
}

function krellUpdateRoot(cb) {
    waitForCore(function() {
        let xs = main.split(".");
        if(!exists(global, xs)) {
            let path = goog.debugLoader_.getPathFromDeps_(main);
            console.log("Namespace", main, "not loaded, fetching:", path);
            onSourceLoad(path, function() {
                bootstrapRepl();
                cb((props) => {
                    return getMainFn(main)(props);
                });
            });
            global.CLOSURE_UNCOMPILED_DEFINES = $CLOSURE_DEFINES;
            $CLJS_PRELOADS
            goog.require(main);
        } else {
            console.log("Namespace", main, "already loaded")
            bootstrapRepl();
            cb((props) => {
                return getMainFn(main)(props);
            });
        }
    });
}

function krellStaleRoot(cb) {
    onKrellCacheInvalidate(cb);
}

module.exports = {
    krellStaleRoot: krellStaleRoot,
    krellUpdateRoot: krellUpdateRoot,
    onKrellReload: onKrellReload
};
