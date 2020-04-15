import {onSourceLoad, onKrellReload} from './krell_repl.js';

var main = '$KRELL_MAIN_NS';

function waitForCore(cb) {
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
                cb(() => {
                    return getMainFn(main)();
                });
            });
            goog.require(main);
        } else {
            cb(() => {
                return getMainFn(main)();
            });
        }
    });
}

module.exports = {
    krellUpdateRoot: krellUpdateRoot,
    onKrellReload: onKrellReload
};
