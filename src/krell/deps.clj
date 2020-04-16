(ns krell.deps
  (:require [cljs.analyzer.api :as ana-api]
            [cljs.build.api :as build-api]
            [cljs.closure :as closure]
            [cljs.module-graph :as mg]
            [cljs.repl :as repl]
            [clojure.java.io :as io])
  (:import [java.io File]))

(defn all-deps
  "Returns a unsorted sequence of all dependencies for a namespace."
  [state ns opts]
  (let [ijs (mg/normalize-input (repl/ns->input ns opts))]
    (ana-api/with-state state
      (map mg/normalize-input
        (closure/add-js-sources
          (build-api/add-dependency-sources state [ijs] opts)
          opts)))))

(defn deps->graph
  "Returns a map representing the dependency graph. Because some libraries
  can have multiple provides the result will need to be deduplicated."
  [deps]
  (reduce
    (fn [acc dep]
      (reduce
        (fn [acc provide]
          (assoc acc provide dep))
        acc (:provides dep)))
    {} deps))

(defn topo-sort [graph]
  (let [sorted-keys (mg/topo-sort graph :requires)]
    (dedupe (map graph sorted-keys))))

(defn get-out-file ^File [dep opts]
  (io/file
    (if (:ns dep)
      (build-api/src-file->target-file (:source-file dep) opts)
      (io/file (:output-dir opts) (closure/rel-output-path dep)))))

(defn add-out-file [dep opts]
  (assoc dep :out-file (get-out-file dep opts)))

(defn with-out-files [deps opts]
  (into [] (map #(add-out-file % opts)) deps))

(comment
  (require '[clojure.pprint :refer [pprint]])

  (let [opts {:output-dir "target"}
        state (ana-api/empty-state)]
    (pprint
      (map :out-file
        (with-out-files
          (dedupe (topo-sort (deps->graph (all-deps state 'cljs.core opts))))
          opts))))

  )
