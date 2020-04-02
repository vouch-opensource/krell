import '$KRELL_OUTPUT_DIR/krell_repl.js';

function krellUpdateRoot(cb) {
    // REPL CASE
    // wait for goog, base and core

    // then load the main ns
    // use debugloader to get the path from the name of the cs
    // listen for the path to load
    // invoke -main function in the main ns
    // invoke cb

    // PROD CASE
    // invoke -main function in the main ns
    // invoke cb
}

module.exports = {
    krellUpdateRoot: krellUpdateRoot
};
