(ns krell.watcher
  (:require [clojure.java.io :as io])
  (:import [io.methvin.watcher DirectoryChangeEvent DirectoryChangeListener
                               DirectoryWatcher]
           [java.nio.file Paths]
           [org.slf4j LoggerFactory]))

(def logger (LoggerFactory/getLogger "krell"))

(defn f->l [f]
  (reify
    DirectoryChangeListener
    (onEvent [this e]
      (f e))))

(defn listener* [^DirectoryChangeEvent e]
  (. logger (info (str "EVENT " (. e eventType))))
  (case (. e eventType)
    DirectoryChangeEvent/CREATE (. logger (info "CREATE"))
    DirectoryChangeEvent/MODIFY (. logger (info "MODIFY"))
    DirectoryChangeEvent/DELETE (. logger (info "DELETE"))))

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

  (watch watcher)

  )
