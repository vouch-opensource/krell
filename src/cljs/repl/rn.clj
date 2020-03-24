(ns cljs.repl.rn
  (:require [cljs.analyzer :as ana]
            [cljs.closure :as closure]
            [cljs.compiler :as comp]
            [cljs.repl :as repl]
            [cljs.repl.bootstrap :as bootstrap]
            [cljs.repl.rn.mdns :as mdns]
            [cljs.util :as util]
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
  "Evaluate a JavaScript string in the React Native REPL"
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

(defn load-javascript
  "Load a Closure JavaScript file into the React Native REPL"
  [repl-env provides url]
  (rn-eval repl-env
    (str "goog.require('" (comp/munge (first provides)) "')")))

(defn event-loop [in]
  (try
    (let [res (read-response in)]
      (try
        (let [{:keys [type repl value] :or {repl "main"} :as event}
              (json/read-str res :key-fn keyword)]
          (case type
            "result"
            (.offer results event)
            (when-let [stream (if (= type "out") *out* *err*)]
              (.write stream value 0 (.length ^String value))
              (.flush stream))))
        (catch Throwable _
          (.write *out* res 0 (.length res))
          (.flush *out*))))
    (catch IOException e
      (.printStackTrace e *err*))))

(defn setup
  ([repl-env] (setup repl-env nil))
  ([{:keys [host port socket state] :as repl-env} opts]
   (when-not @socket
     (loop [r nil]
       (when-not (= r "ready")
         (Thread/sleep 50)
         (try
           (reset! socket (create-socket host port))
           (catch Exception e))
         (if @socket
           (recur (read-response (:in @socket)))
           (recur nil))))
     (.start (Thread. (bound-fn [] (event-loop (:in @socket)))))
     ;; compile cljs.core & its dependencies, goog/base.js must be available
     ;; for bootstrap to load, use new closure/compile as it can handle
     ;; resources in JARs
     (let [output-dir (io/file (util/output-directory opts))
           core       (io/resource "cljs/core.cljs")
           core-js    (closure/compile core
                        (assoc opts :output-file
                          (closure/src-file->target-file
                            core (dissoc opts :output-dir))))
           deps       (closure/add-dependencies opts core-js)
           env        (ana/empty-env)]
       ;; output unoptimized code and the deps file
       ;; for all compiled namespaces
       (apply closure/output-unoptimized
         (assoc opts
           :output-to (.getPath (io/file output-dir "rn_repl_deps.js")))
         deps)
       ;; TODO: React Native Import
       ;(rn-eval repl-env
       ;  (str "require("
       ;    (platform-path (conj root-path "rn_repl_deps.js"))
       ;    ")"))
       ;; monkey-patch isProvided_ to avoid useless warnings - David
       (rn-eval repl-env
         (str "goog.isProvided_ = function(x) { return false; };"))
       ;; load cljs.core, setup printing
       (repl/evaluate-form repl-env env "<cljs repl>"
         '(do
            (.require js/goog "cljs.core")
            (enable-console-print!)))
       (bootstrap/install-repl-goog-require repl-env env)
       (rn-eval repl-env
         (str "goog.global.CLOSURE_UNCOMPILED_DEFINES = "
           (json/write-str (:closure-defines opts)) ";"))))))

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
