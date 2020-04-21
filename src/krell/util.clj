(ns krell.util
  (:import [java.io File]
           [java.net URL]
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

(defn relativize [^File source ^File target]
  (to-file (.relativize (to-path source) (to-path target))))

(defn get-path [^File f]
  (.getPath f))

(defn file-ext [f]
  (let [path (if (file? f) (.getPath ^File f) f)]
    (let [idx (.lastIndexOf path ".")]
      (when (pos? idx) (subs path idx)))))

(defn file-tree-seq [dir]
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
