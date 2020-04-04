(ns krell.gen
  (:require [cljs.build.api :as api]
            [cljs.util :as util]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as string]))

(defn export-lib [lib]
  (str "\""lib "\": require('" lib "')" ))

(defn rt-js
  "Returns the JavaScript code to support runtime require of React Native
  modules."
  [lib-set]
  (str
    "module.exports = {\n"
    "  npmDeps: {\n"
    (string/join ",\n" (map (comp #(str "    " %) export-lib) lib-set))
    "  }\n"
    "};\n"))

(defn node-modules
  "Caching logic for npm-requires."
  [opts]
  (let [pkg-cache (io/file ".krellcache" "node_modules.edn")
        pkg-lock  (io/file "package-lock.json")]
    (if (or (not (.exists pkg-cache))
            (< (util/last-modified pkg-cache) (util/last-modified pkg-lock)))
      (let [modules (api/node-modules opts)]
        (util/mkdirs pkg-cache)
        (spit pkg-cache (pr-str modules))
        modules)
      (edn/read-string (slurp pkg-cache)))))

(defn npm-requires
  "Return the set of ClojureScript requires that are node_modules libraries."
  [opts]
  (let [cljs-libs (-> (:main opts)
                    api/compilable->ijs
                    api/add-dependency-sources)
        npm-libs  (api/index-ijs (node-modules opts))]
    (set/intersection
      ;; node lib names will be strings
      (into #{} (mapcat #(map str %)) (map :requires cljs-libs))
      (into #{} (keys npm-libs)))))

(defn write-rt-js
  "Write the runtime module support file. This generated file allows REPLs
   to require node_module libraries at runtime."
  [opts]
  (let [npm-deps (npm-requires opts)
        source   (rt-js npm-deps)
        out-file (io/file (:output-dir opts) "rt.js")]
    (util/mkdirs out-file)
    (spit out-file source)))

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
  (let [source (slurp (io/resource "krell_repl.js"))]
    (spit (io/file (:output-dir opts) "krell_repl.js")
      source)))

(defn krell-main-js
  "Write out the build dependent entry point. See resources/main.dev.js
  and resources/main.prod.js"
  [opts]
  (let [source (slurp
                 (if (= :none (:optimizations opts))
                   (io/resource "main.dev.js")
                   (io/resource "main.prod.js")))]
    (-> source
      (string/replace "$KRELL_MAIN_NS" (str (munge (:main opts)))))))
