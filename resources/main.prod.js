function krellUpdateRoot(cb) {
    cb($KRELL_MAIN_NS._main());
}

module.exports = {
    krellUpdateRoot: krellUpdateRoot
};
