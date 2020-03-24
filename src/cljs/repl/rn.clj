(ns cljs.repl.rn
  (:require [cljs.repl :as repl]
            [cljs.repl.rn.mdns :as mdns]
            [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [java.io File BufferedReader BufferedWriter IOException]
           [java.net Socket]
           [java.util.concurrent LinkedBlockingQueue]))

(def results (LinkedBlockingQueue.))

(defn create-socket [^String host port]
  (let [socket (Socket. host (int port))
        in     (io/reader socket)
        out    (io/writer socket)]
    {:socket socket :in in :out out}))

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

(defn rn-eval
  "Evaluate a JavaScript string in the React Native REPL process."
  [repl-env js]
  (let [{:keys [out]} @(:socket repl-env)]
    (write out (json/write-str {:type "eval" :form js}))
    (let [result (.take results)]
      (condp = (:status result)
        "success"
        {:status :success
         :value (:value result)}

        "exception"
        {:status :exception
         :value (:value result)}))))

(defrecord ReactNativeEnv [host port path socket proc state]
  repl/IReplEnvOptions
  (-repl-options [this]
    {:output-dir ".cljs_rn_repl"
     :target :nodejs})
  repl/IParseError
  (-parse-error [_ err _]
    (assoc err :value nil))
  repl/IJavaScriptEnv
  (-setup [this opts]
    (setup this opts))
  (-evaluate [this filename line js]
    (rn-eval this js))
  (-load [this provides url]
    (load-javascript this provides url))
  (-tear-down [this]
    (let [sock @socket]
      (when-not (.isClosed (:socket sock))
        (write (:out sock) ":cljs/quit")
        (while (alive? @proc) (Thread/sleep 50))
        (close-socket sock)))))

(comment

  (def ep-map (atom {}))

  (mdns/setup
    {:type "http"
     :protocol "tcp"
     :domain "local."
     :endpoint-map ep-map
     :match-name rn-repl?})

  @ep-map

  )
