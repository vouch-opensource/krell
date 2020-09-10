(ns krell.util
  (:require [cljs.build.api :as build-api]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import [java.io File]
           [java.net URL URLDecoder]
           [java.nio.file Path]))

(defn now
  "Returns System/currentTimeMillis"
  ^long []
  (System/currentTimeMillis))

(defn elapsed
  "Give a long representing some instant in milliseconds, returns elapsed."
  ^long [t]
  (- (now) t))

(defn file? [f]
  (instance? File f))

(defn url? [f]
  (instance? URL f))

(defn last-modified [src]
  (cond
    (file? src) (.lastModified ^File src)
    (url? src)
    (let [conn (.openConnection ^URL src)]
      (try
        (.getLastModified conn)
        (finally
          (let [ins (.getInputStream conn)]
            (when ins
              (.close ins))))))
    :else
    (throw
      (IllegalArgumentException. (str "Cannot get last modified for " src)))))

(defn changed? [a b]
  (not (== (last-modified a) (last-modified b))))

(defn to-file ^File [^Path path]
  (.toFile path))

(defn to-path ^Path [^File f]
  (.toPath f))

(defn relativize ^File [^File source ^File target]
  (to-file (.relativize (to-path source) (to-path target))))

(defn get-path [^File f]
  (.getPath f))

(defn file-ext [f]
  (let [path (if (file? f) (.getPath ^File f) f)]
    (let [idx (.lastIndexOf path ".")]
      (when (pos? idx) (subs path idx)))))

(defn files-seq [dir]
  (tree-seq
    (fn [^File f]
      (. f (isDirectory)))
    (fn [^File d]
      (seq (. d (listFiles))))
    dir))

(defn mkdirs
  "Create all parent directories for the passed file."
  [^File f]
  (.mkdirs (.getParentFile (.getCanonicalFile f))))

(defn ns->cache-file [ns {:keys [output-dir] :as opts}]
  (let [f    (build-api/target-file-for-cljs-ns ns output-dir)
        path (-> ns build-api/ns->location :relative-path)]
    (io/file (str (string/replace (.getPath f) #".js$" "") (file-ext path) ".cache.json"))))

(defn closure-relative-path [file-path opts]
  (.getPath (relativize (io/file (:output-dir opts) "goog") (io/file file-path))))

(defn url-path [f]
  (string/replace (get-path f) File/separator "/"))

(defn platform-path [rel-url-frag]
  (string/replace rel-url-frag "/" File/separator))
