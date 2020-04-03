var main = '$KRELL_MAIN_NS';

function getIn(obj, xs) {
    if(xs.length === 0) {
        return obj;
    } else {
        return getIn(obj[xs[0]], xs.slice(0));
    }
}

function krellUpdateRoot(cb) {
    var main = getIn(global, main.split(".").concat(["-main"]));
    cb(main());
}

module.exports = {
    krellUpdateRoot: krellUpdateRoot
};
