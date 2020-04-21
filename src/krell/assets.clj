(ns krell.assets
  (:require [clojure.string :as string]
            [krell.util :as util])
  (:import [java.io File]))

(defn ignore? [^File f]
  (string/starts-with? (.getName f) "."))

(defn clojure? [^File f]
  (#{".clj" ".cljc" ".cljs"} (util/file-ext f)))

(defn asset? [^File f]
  (not (or (.isDirectory f)
           (ignore? f)
           (clojure? f))))

(defn asset-file-seq [dir]
  (filter asset? (util/file-tree-seq dir)))
