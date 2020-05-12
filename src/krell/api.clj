(ns krell.api
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [krell.repl :as krell]))

(defn- build* [options]
  (krell/krell-compile krell/repl-env {:options options}))

(defn normalize-ids [x]
  (if (sequential? x)
    (into [] (map normalize-ids x))
    (cond
      (keyword? x) (recur (str (name x) ".edn"))
      (symbol? x)  (recur (str (name x) ".edn"))
      (string? x)  (let [rsc (or (some-> x io/resource)
                                 (io/file x))]
                     (if-not (nil? rsc)
                       (edn/read-string (slurp rsc))
                       (throw
                         (ex-info (str "Invalid build id: " x) {}))))
      (map? x)     x
      :else
      (throw
        (ex-info (str "Invalid build id: " x) {})))))

(defn build
  "Run a Krell build. ids can be a symbol, keyword, string, or map. If
  symbol or keyword the classpath will be searched for .edn config file
  with a matching name. If a string, must be a relative file path."
  ([& ids]
   (build* (apply merge (normalize-ids ids)))))
