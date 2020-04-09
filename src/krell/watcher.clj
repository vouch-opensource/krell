(ns krell.watcher
  (:require [clojure.java.io :as io])
  (:import [java.io IOException]
           [java.nio.file Paths]
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

(comment

  (-> (DirectoryWatcher/builder)
    (.path (to-path "src"))
    (.listener listener)
    (.build))


  )
