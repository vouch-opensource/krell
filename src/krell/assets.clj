(ns krell.assets
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [krell.util :as util])
  (:import [java.io File]
           [java.nio.file Files]))

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

(defn copy-asset [^File source opts]
  (let [target (io/file (:output-dir opts))]
    (when (util/changed? source target)
      (Files/copy
        (util/to-path source)
        (util/to-path target))
      (.setLastModified target (util/last-modified source)))))
