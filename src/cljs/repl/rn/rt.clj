(ns cljs.repl.rn.rt
  (:require [cljs.build.api :as api]
            [clojure.set :as set]))

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

(comment

  (def opts
    {:main 'hello-world.core
     :target :nodejs
     :npm-deps true})

  (npm-nses opts)

  )
