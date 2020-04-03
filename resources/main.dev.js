import '$KRELL_OUTPUT_DIR/krell_repl.js';

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
    if(xs.length === 0) {
        return obj;
    } else {
        return getIn(obj[xs[0]], xs.slice(0));
    }
}

function krellUpdateRoot(cb) {
    waitForCore(function() {
        var xs = main.split(".");
        if(!exists(global, xs)) {
            // then load the main ns
            // use debugloader to get the path from the name of the cs
            // listen for the path to load
            // invoke -main function in the main ns
            // invoke cb
        }
    });
}

module.exports = {
    krellUpdateRoot: krellUpdateRoot
};
