function krellUpdateRoot(cb) {
    cb($KRELL_MAIN_NS._main);
}

module.exports = {
    krellStaleRoot: function(cb) {},
    krellUpdateRoot: krellUpdateRoot,
    onKrellReload: function(cb) {}
};
