(ns krell.watcher
  (:require [clojure.java.io :as io])
  (:import [java.io IOException]
           [java.nio.file Paths]
           [io.methvin.watcher DirectoryWatcher]))

(defn to-path [& args]
  (Paths/get (first args) (into-array String (rest args))))

(comment

  (-> (DirectoryWatcher/builder)
    (.path (to-path "src"))
    )

  )
