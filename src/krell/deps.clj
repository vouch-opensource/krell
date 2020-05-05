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
  "Given a sequence of namespace descriptor maps, returns a map representing
  the dependency graph. Because some libraries can have multiple provides the
  entries will often represent the same dependency. Deduplication may be
  required."
  [deps]
  (reduce
    (fn [acc dep]
      (reduce
        (fn [acc provide]
          (assoc acc provide dep))
        acc (:provides dep)))
    {} deps))

(defn topo-sort
  "Give a dep graph return the topologically sorted sequence of inputs."
  [graph]
  (let [sorted-keys (mg/topo-sort graph :requires)]
    (distinct (map graph sorted-keys))))

(defn sorted-deps
  "Given a compiler state, a ns symbol, and ClojureScript compiler options,
  return a topologically sorted sequence of all the dependencies."
  [state ns opts]
  (let [all   (all-deps state ns opts)
        graph (deps->graph all)]
    (topo-sort graph)))

(defn get-out-file ^File [dep opts]
  (io/file
    (if (:ns dep)
      (build-api/src-file->target-file (:source-file dep) opts)
      (io/file (:output-dir opts) (closure/rel-output-path dep)))))

(defn add-out-file [dep opts]
  (assoc dep :out-file (get-out-file dep opts)))

(defn with-out-files
  "Given a list of deps return a new list of deps with :out-file property
   on each value."
  [deps opts]
  (into [] (map #(add-out-file % opts)) deps))

(defn ^:dynamic dependents*
  ([ns graph]
   (dependents* ns graph :direct))
  ([ns graph mode]
   (let [graph' (->> (filter
                       (fn [[k v]]
                         (some #{ns} (:requires v)))
                       graph)
                  (into {}))]
    (condp = mode
      :direct graph'

      :all
      (reduce
        (fn [ret x]
          (merge ret (dependents* x graph)))
        graph' (keys graph'))

      (throw (ex-info (str "Unsupported :mode, " mode) {}))))))

(defn dependents
  "Given an ns symbol and a dependency graph return a topologically sorted
  sequence of all ancestors."
  ([ns graph]
   (dependents ns graph :direct))
  ([ns graph mode]
   (topo-sort
     (binding [dependents* (memoize dependents*)]
       (dependents* ns graph mode)))))
