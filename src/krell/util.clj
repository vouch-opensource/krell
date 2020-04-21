(ns krell.util
  (:import [java.io File]
           [java.nio.file Path]))

(defn now
  "Returns System/currentTimeMillis"
  ^long []
  (System/currentTimeMillis))

(defn elapsed
  "Give a long representing some instant in milliseconds, returns elapsed."
  ^long [t]
  (- (now) t))

(defn to-file ^File [^Path path]
  (.toFile path))

(defn file-ext [^File f]
  (let [path (.getPath f)
        idx  (.lastIndexOf path ".")]
    (when (pos? idx) (subs path idx))))

(defn file-tree-seq [dir]
  (tree-seq
    (fn [^File f]
      (. f (isDirectory)))
    (fn [^File d]
      (seq (. d (listFiles))))
    dir))
