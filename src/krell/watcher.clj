(ns krell.watcher
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Paths]
           [io.methvin.watcher DirectoryChangeEvent DirectoryChangeListener
                               DirectoryWatcher]))

(defn f->l [f]
  (reify
    DirectoryChangeListener
    (onEvent [this e]
      (f e))))

(defn listener* [^DirectoryChangeEvent e]
  (case (. e eventType)
    DirectoryChangeEvent/CREATE (println "CREATE")
    DirectoryChangeEvent/MODIFY (println "MODIFY")
    DirectoryChangeEvent/DELETE (println "DELETE")))

(def listener (f->l listener*))

(defn to-path [& args]
  (Paths/get (first args) (into-array String (rest args))))

(defn create [& paths]
  (-> (DirectoryWatcher/builder)
    (.paths (map to-path paths))
    (.listener listener)
    (.build)))

(defn watch [^DirectoryWatcher watcher]
  (.watchAsync watcher))

(defn stop [^DirectoryWatcher watcher]
  (.close watcher))

(comment

  (def watcher (create "src"))

  (.watchAsync watcher)

  )
