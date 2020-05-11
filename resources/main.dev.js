import {evaluate, onSourceLoad, onKrellReload} from './krell_repl.js';

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
    evaluate(KRELL_CACHE.get(toPath("goog/base.js")));
    evaluate(KRELL_CACHE.get(toPath("goog/deps.js")));
    evaluate(KRELL_CACHE.get(toPath("cljs_deps.js")));
    evaluate(KRELL_CACHE.get(toPath("krell_repl_deps.js")));
};

function waitForCore(cb) {
    //console.log("wait for core, cache ready", nsToPath(main), KRELL_CACHE.has(nsToPath(main)));
    if(typeof cljs !== 'undefined') {
        cb();
    } else {
        setTimeout(function() { waitForCore(cb); }, 250);
    }
}

function exists(obj, xs) {
    if(xs.length >= 1) {
        var key = xs[0],
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
    var xs = ns.split(".").concat(["_main"]),
        fn = getIn(global, xs);
    if(fn) {
        return fn;
    } else {
        throw new Error("Could not find -main fn in namespace " + ns);
    }
}

function krellUpdateRoot(cb) {
    waitForCore(function() {
        var xs = main.split(".");
        if(!exists(global, xs)) {
            var path = goog.debugLoader_.getPathFromDeps_(main);
            onSourceLoad(path, function() {
                cb((props) => {
                    return getMainFn(main)(props);
                });
            });
            global.CLOSURE_UNCOMPILED_DEFINES = $CLOSURE_DEFINES;
            $CLJS_PRELOADS
            goog.require(main);
        } else {
            cb((props) => {
                return getMainFn(main)(props);
            });
        }
    });
}

module.exports = {
    krellUpdateRoot: krellUpdateRoot,
    onKrellReload: onKrellReload
};
