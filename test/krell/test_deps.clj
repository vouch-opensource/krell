(ns krell.test-deps
  (:require [cljs.analyzer.api :as ana-api]
            [clojure.pprint :refer [pprint]]
            [clojure.test :as test :refer [deftest is run-tests]]
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

(deftest test-dependents
  (let [opts   {:output-dir "target"}
        state  (ana-api/empty-state)
        graph  (deps/deps->graph (deps/all-deps state 'cljs.core opts))
        direct (map :provides (deps/dependents 'goog.assert graph))
        all    (map :provides (deps/dependents "goog.asserts" graph :all))]
    (is (= (into #{} direct))
           (->> (filter
                  (fn [[_ x]]
                    (some #{"goog.asserts"} (:requires x)))
                  graph)
             (map (comp :provides second))
             (into #{})))
    (is (< (count direct) (count all)))
    (is (= ["cljs.core"] (last all)))))
