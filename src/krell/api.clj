(ns krell.api
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [krell.repl :as krell]))

(defn- build* [options]
  (krell/krell-compile krell/repl-env {:options options}))

(defn build
  "Run a Krell build. id can be a symbol, keyword, or string. If
  symbol or keyword the classpath will be searched for .edn file
  with a matching name. If a string, must be a relative file path.
  Can be passed extra-opts to override configuration."
  ([id]
   (build id nil))
  ([id extra-opts]
   (cond
     (keyword? id) (recur (str (name id) ".edn") extra-opts)
     (symbol? id)  (recur (str (name id) ".edn") extra-opts)
     (string? id)  (let [rsc (or (some-> id io/resource)
                               (io/file id))]
                     (if-not (nil? rsc)
                       (build*
                         (merge
                           (edn/read-string (slurp rsc))
                           extra-opts))
                       (throw
                         (ex-info (str "Invalid build id: " id) {}))))
     :else
     (throw (ex-info (str "Invalid build id: " id) {})))))
