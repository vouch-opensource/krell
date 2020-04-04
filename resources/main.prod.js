var main = '$KRELL_MAIN_NS';

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
    // TODO: won't work in :advanced w/o exporting
    cb(getMainFn(main)());
}

module.exports = {
    krellUpdateRoot: krellUpdateRoot
};
