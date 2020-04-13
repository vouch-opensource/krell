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
