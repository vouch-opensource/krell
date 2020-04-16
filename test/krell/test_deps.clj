(ns krell.test-deps
  (:require [cljs.analyzer.api :as ana-api]
            [clojure.pprint :refer [pprint]]
            [clojure.set :as set]
            [clojure.test :as test :refer [deftest is run-tests]]
            [cljs.module-graph :as mg]
            [krell.deps :as deps]))

(deftest test-topo-sort
  (let [opts   {:output-dir "target"}
        state  (ana-api/empty-state)
        all    (deps/all-deps state 'cljs.core opts)
        graph  (deps/deps->graph all)
        sorted (deps/topo-sort graph)]
    (is (= 'cljs.core (:ns (last sorted))))))

(deftest test-with-out-files
  (let [opts  {:output-dir "target"}
        state (ana-api/empty-state)
        all   (deps/all-deps state 'cljs.core opts)
        all'  (deps/with-out-files
                (deps/topo-sort (deps/deps->graph all))
                opts)]
    (is (== (count all) (count all')))))

