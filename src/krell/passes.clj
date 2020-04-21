(ns krell.passes
  (:require [cljs.analyzer.api :as api]
            [cljs.compiler.api :as comp-api]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [krell.assets :as assets]
            [krell.util :as util])
  (:import [java.io File]))

(defn normalize [s]
  (cond-> s (string/starts-with? s "./") (subs 2)))

(defn ns->path [ns]
  (string/replace (str (comp-api/munge ns)) "." File/separator))

(defn ns->parent-path [ns]
  (let [xs (string/split (-> ns comp-api/munge str) #"\.")]
    (string/join File/separator (butlast xs))))

(defn to-asset-path [ns rel-path]
  (io/file (ns->path ns) (normalize rel-path)))

(defn asset? [s]
  (and (not (nil? (util/file-ext s)))
       (not (assets/clojure? s))))

(defn js-require-asset? [ast]
  (and (= :invoke (:op ast))
       (= 'js/require (-> ast :fn :name))
       (= :const (-> ast :args first :op))
       (asset? (-> ast :args first :val))))

(defn update-require-path [ast new-path]
  (update-in ast [:args 0] merge
    {:val new-path :form new-path}))

(defn collect-requires [watch-dir]
  (fn [env ast opts]
    (if (js-require-asset? ast)
      (update-require-path ast
        (let [ns   (-> env :ns :name)
              path (to-asset-path ns (-> ast :args first :val))]
          (util/get-path
            (util/relativize
              (io/file (:output-dir opts) (ns->parent-path ns))
              (io/file watch-dir path)))))
      ast)))

(comment

  (require '[clojure.pprint :refer [pprint]])

  (let [state (api/empty-state)
        env   (api/empty-env)]
    (->
      (api/with-passes (conj api/default-passes (collect-requires "src"))
        (api/analyze state env '(js/require "./foo.png") nil
          {:output-dir "target"}))
      :args first))

  ;; works
  (util/relativize
    (io/file "target/cljs/user")
    (io/file "src/cljs/user/foo.png"))

  )
