(ns krell.passes
  (:require [cljs.analyzer :as ana]
            [cljs.analyzer.api :as ana-api]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [krell.assets :as assets]
            [krell.util :as util])
  (:import [java.io File]))

(def ^:dynamic *nses-with-requires* nil)

(defn normalize [s]
  (cond-> s (string/starts-with? s "./") (subs 2)))

(defn asset? [s]
  (and (not (nil? (util/file-ext s)))
       (not (assets/js? s))))

(defn lib? [s]
  (not (asset? s)))

(defn js-require? [ast]
  (and (= :invoke (:op ast))
       (= 'js/require (-> ast :fn :name))
       (= :const (-> ast :args first :op))))

(defn js-require-asset? [ast]
  (and (js-require? ast)
       (asset? (-> ast :args first :val))))

(defn update-require-path [ast new-path]
  (update-in ast [:args 0] merge
    {:val new-path :form new-path}))

(defn rewrite-asset-requires [env ast opts]
  (if (js-require-asset? ast)
    (let [new-path
          (let [ns (-> env :ns :name)]
            (string/replace
              (util/get-path
                (util/relativize
                  (.getAbsoluteFile (io/file (:output-dir opts)))
                  (.getAbsoluteFile
                    (io/file
                      (.getParentFile (io/file (ana-api/current-file)))
                      (normalize (-> ast :args first :val))))))
              File/separator "/"))
          cur-ns (ana-api/current-ns)]
      (when *nses-with-requires*
        (swap! *nses-with-requires* conj cur-ns))
      (swap! (ana-api/current-state) update-in
        [::ana/namespaces cur-ns ::assets] (fnil conj #{}) new-path)
      (update-require-path ast new-path))
    ast))

(defn js-require-lib? [ast]
  (and (js-require? ast)
       (lib? (-> ast :args first :val))))

(defn collect-lib-requires [env ast opts]
  (when (js-require-lib? ast)
    (let [lib (-> ast :args first :val)
          cur-ns (ana-api/current-ns)]
      (when *nses-with-requires*
        (swap! *nses-with-requires* conj cur-ns))
      (swap! (ana-api/current-state) update-in
        [::ana/namespaces cur-ns ::requires] (fnil conj #{}) lib)))
  ast)

(defn cache-krell-requires [nses opts]
  ;; NOTE: just additive for now, can revisit later if someone finds a
  ;; performance issue
  (let [out-file (io/file (:output-dir opts) "krell_requires.edn")
        nses'    (cond-> nses
                   (.exists out-file)
                   (into (edn/read-string (slurp out-file))))]
    (util/mkdirs out-file)
    (spit out-file (pr-str nses'))
    nses'))

(defn load-analysis [nses opts]
  (reduce
    (fn [ret ns]
      (assoc-in ret [::ana/namespaces ns]
        (ana-api/read-analysis-cache (util/ns->cache-file ns opts))))
    {} nses))

(defn all-assets [analysis]
  (into #{} (mapcat (comp ::assets val) (get-in analysis [::ana/namespaces]))))

(defn all-requires [analysis]
  (into #{} (mapcat (comp ::requires val) (get-in analysis [::ana/namespaces]))))

(def custom-passes [rewrite-asset-requires collect-lib-requires])
