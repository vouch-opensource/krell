(ns cljs.repl.rn.rt
  (:require [cljs.build.api :as api]
            [clojure.string :as string]
            [clojure.set :as set]))

(defn export-lib [lib]
  (str "\""lib "\": require('" lib "')" ))

(defn gen-rt-libs [lib-set]
  (str
    "module.exports = {\n"
    "  npmDeps: {\n"
    (string/join ",\n" (map (comp #(str "    " %) export-lib) lib-set))
    "  }\n"
    "};\n"))

(defn npm-nses
  [opts]
  (let [cljs-libs (-> (:main opts)
                    api/compilable->ijs
                    api/add-dependency-sources)
        npm-libs  (api/index-ijs (api/node-modules opts))]
    (set/intersection
      ;; node lib names will be strings
      (into #{} (mapcat #(map str %)) (map :requires cljs-libs))
      (into #{} (keys npm-libs)))))

(defn gen-rt-js [opts]
  (let [npm-deps (opts)
        rt-js    (gen-rt-libs npm-deps)]
    (spit (io/file "rt.js") rt-js)))

(comment

  (def opts
    {:main 'hello-world.core
     :target :nodejs
     :npm-deps true})

  (println (gen-rt-libs (npm-nses opts)))

  )
