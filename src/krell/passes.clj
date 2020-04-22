(ns krell.passes
  (:require [cljs.analyzer :as ana]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [krell.assets :as assets]
            [krell.util :as util]))

(def ^:dynamic *state* nil)

(defn normalize [s]
  (cond-> s (string/starts-with? s "./") (subs 2)))

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

(defn rewrite-asset-requires [env ast opts]
  (if (js-require-asset? ast)
    (let [new-path
          (let [ns (-> env :ns :name)]
            (util/get-path
              (util/relativize
                (.getAbsoluteFile (io/file (:output-dir opts)))
                (.getAbsoluteFile
                  (io/file
                    (.getParentFile (io/file ana/*cljs-file*))
                    (normalize (-> ast :args first :val)))))))]
      (when *state*
        (swap! *state* update :assets (fnil conj #{}) new-path))
      (update-require-path ast new-path))
    ast))
