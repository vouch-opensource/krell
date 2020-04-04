function krellUpdateRoot(cb) {
    // TODO: won't work in :advanced w/o exporting
    cb($KRELL_MAIN_NS._main());
}

module.exports = {
    krellUpdateRoot: krellUpdateRoot
};
