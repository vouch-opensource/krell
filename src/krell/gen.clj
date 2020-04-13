(ns krell.gen
  (:require [cljs.util :as util]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(defn write-index-js
  "Write the Krell index.js file which bootstraps the Krell application.
  See resources/index.js"
  [opts]
  (let [source   (slurp (io/resource "index.js"))
        out-file (io/file "index.js")]
    ;; TODO: just writing this out to the top level, can we allow this to be
    ;; in a different location?
    (when-not (.exists out-file)
      (spit out-file
        (-> source
          (string/replace "$KRELL_OUTPUT_TO" (:output-to opts))
          (string/replace "$KRELL_OUTPUT_DIR" (:output-dir opts)))))))

(defn write-repl-js
  "Write out the REPL support code. See resources/krell_repl.js"
  [opts]
  (let [source   (slurp (io/resource "krell_repl.js"))
        out-file (io/file (:output-dir opts) "krell_repl.js")]
    (util/mkdirs out-file)
    (spit out-file source)))

(defn krell-main-js
  "Return the source for build dependent entry point. See resources/main.dev.js
  and resources/main.prod.js"
  [opts]
  (let [source (slurp
                 (if (= :none (:optimizations opts))
                   (io/resource "main.dev.js")
                   (io/resource "main.prod.js")))]
    (-> source
      (string/replace "$KRELL_MAIN_NS" (str (munge (:main opts)))))))
