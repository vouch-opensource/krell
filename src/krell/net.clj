(ns krell.net
  (:require [clojure.java.io :as io])
  (:import [java.io Reader BufferedReader BufferedWriter IOException]
           [java.net ServerSocket Socket]))

(defn create-socket [^String host port]
  (let [socket (Socket. host (int port))
        in     (io/reader socket)
        out    (io/writer socket)]
    {:socket socket :in in :out out}))

(defn close-socket [s]
  (.close ^Reader (:in s))
  (.close ^Reader (:out s))
  (.close ^Socket (:socket s)))

(defn write [^BufferedWriter out ^String js]
  (.write out js)
  (.write out (int 0)) ;; terminator
  (.flush out))

(defn ^String read-response [^BufferedReader in]
  (let [sb (StringBuilder.)]
    (loop [sb sb c (.read in)]
      (case c
        -1 (throw (IOException. "Stream closed"))
        0 (str sb)
        (do
          (.append sb (char c))
          (recur sb (.read in)))))))
