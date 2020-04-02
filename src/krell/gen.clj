(ns krell.gen
  (:require [cljs.build.api :as api]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as string]))

(defn export-lib [lib]
  (str "\""lib "\": require('" lib "')" ))

(defn rt-js [lib-set]
  (str
    "module.exports = {\n"
    "  npmDeps: {\n"
    (string/join ",\n" (map (comp #(str "    " %) export-lib) lib-set))
    "  }\n"
    "};\n"))

(defn npm-requires
  [opts]
  (let [cljs-libs (-> (:main opts)
                    api/compilable->ijs
                    api/add-dependency-sources)
        npm-libs  (api/index-ijs (api/node-modules opts))]
    (set/intersection
      ;; node lib names will be strings
      (into #{} (mapcat #(map str %)) (map :requires cljs-libs))
      (into #{} (keys npm-libs)))))

(defn write-rt-js [opts]
  (let [npm-deps (npm-requires opts)
        rt-js    (gen-rt-libs npm-deps)]
    (spit (io/file "rt.js") rt-js)))

(defn write-index-js [opts]
  (let [js (slurp (io/resource "index.j"))]
    (spit (io/file "index.js")
      (-> js
        (string/replace "$KRELL_OUTPUT_TO" (:output-to opts))
        (string/replace "$KRELL_OUTPUT_DIR" (:output-dir opts))))))

(comment

  (def opts
    {:main 'hello-world.core
     :output-to "out/main.js"
     :output-dir "out"
     :target :nodejs
     :npm-deps true})

  (println (gen-rt-libs (npm-nses opts)))

  (gen-rt-js opts)

  (write-index-js opts)

  )
