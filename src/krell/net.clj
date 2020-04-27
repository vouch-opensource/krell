(ns krell.net
  (:require [clojure.java.io :as io])
  (:import [java.io BufferedReader BufferedWriter IOException]
           [java.net InetAddress Inet4Address NetworkInterface ServerSocket Socket]))

(defn create-server-socket ^ServerSocket [port]
  (ServerSocket. port))

(defn socket->socket-map [socket]
  (let [in  (io/reader socket)
        out (io/writer socket)]
    {:socket socket :in in :out out}))

(defn create-socket [^String host port]
  (socket->socket-map (Socket. host (int port))))

(defn close-socket [s]
  (.close (:in s))
  (.close (:out s))
  (.close (:socket s)))

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

(defn get-ip []
  (-> (filter
        (fn [^InetAddress ia]
          (and (not (.isLinkLocalAddress ia))
               (not (.isLoopbackAddress ia))
               (instance? Inet4Address ia)))
        (mapcat
          (fn [^NetworkInterface ni]
            (when (.isUp ni)
              (enumeration-seq (.getInetAddresses ni))))
          (enumeration-seq (NetworkInterface/getNetworkInterfaces))))
    first (.getHostAddress)))
