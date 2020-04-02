(ns krell.repl
  (:require [cljs.analyzer :as ana]
            [cljs.cli :as cli]
            [cljs.closure :as closure]
            [cljs.compiler :as comp]
            [cljs.repl :as repl]
            [cljs.repl.bootstrap :as bootstrap]
            [cljs.util :as util]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [krell.gen :as gen]
            [krell.mdns :as mdns])
  (:import [java.io File BufferedReader BufferedWriter IOException]
           [java.net Socket]
           [java.util.concurrent LinkedBlockingQueue]))

(def eval-lock (Object.))
(def results-queue (LinkedBlockingQueue.))
(def load-queue (LinkedBlockingQueue.))

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

(declare load-queued-files)

(defn rn-eval
  "Evaluate a JavaScript string in the React Native REPL"
  [repl-env js]
  (locking eval-lock
    (let [{:keys [out]} @(:socket repl-env)]
      (write out (json/write-str {:type "eval" :form js}))
      (let [result (.take results-queue)
            ret    (condp = (:status result)
                     "success"
                     {:status :success
                      :value  (:value result)}

                     "exception"
                     {:status :exception
                      :value  (:value result)})]
        ;; load any queued files now to simulate sync loads
        (load-queued-files repl-env)
        ret))))

(defn load-queued-files [repl-env]
  (loop [{:keys [value] :as load-file-req} (.poll load-queue)]
    (when load-file-req
      (rn-eval repl-env (slurp (io/file value)))
      (recur (.poll load-queue)))))

(defn load-javascript
  "Load a Closure JavaScript file into the React Native REPL"
  [repl-env provides url]
  (rn-eval repl-env
    (str "goog.require('" (comp/munge (first provides)) "')")))

(defn event-loop [{:keys [state] :as repl-env} in]
  (while (not (:done @state))
    (try
      (let [res (read-response in)]
        (try
          (let [{:keys [type value] :as event}
                (json/read-str res :key-fn keyword)]
            (case type
              "load-file" (.offer load-queue event)
              "result"    (.offer results-queue event)
              (when-let [stream (if (= type "out") *out* *err*)]
                (.write stream value 0 (.length ^String value))
                (.flush stream))))
          (catch Throwable _
            (.write *out* res 0 (.length res))
            (.flush *out*))))
      (catch IOException e
        (.printStackTrace e *err*)
        ;; TODO: switch to ex-info?
        (repl/tear-down repl-env)))))

(defn setup
  ([repl-env] (setup repl-env nil))
  ([{:keys [host port socket state] :as repl-env} opts]
   (when-not @socket
     (loop [r nil]
       (when-not (= r "ready")
         (Thread/sleep 50)
         (try
           (reset! socket (create-socket host port))
           (catch Exception e
             (println e)))
         (if @socket
           (recur (read-response (:in @socket)))
           (recur nil))))
     (.start (Thread. (bound-fn [] (event-loop repl-env (:in @socket)))))
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
           env        (ana/empty-env)
           repl-deps  (io/file output-dir "rn_repl_deps.js")
           base-path  (.getPath (io/file (:output-dir opts) "goog"))]
       ;; output unoptimized code and the deps file
       ;; for all compiled namespaces
       (apply closure/output-unoptimized
         (assoc opts
           :output-to (.getPath repl-deps))
         deps)
       ;; prevent auto-loading of deps.js - not really necessary since
       ;; we write our own and it will override google's dep.js entries
       (rn-eval repl-env
         "var CLOSURE_NO_DEPS = true;")
       (rn-eval repl-env
         (str "var CLOSURE_BASE_PATH = \"" base-path File/separator "\";"))
       (rn-eval repl-env
         (slurp (io/resource "goog/base.js")))
       (rn-eval repl-env
         (slurp (io/resource "goog/deps.js")))
       (rn-eval repl-env (slurp repl-deps))
       ; load cljs.core, setup printing
       (repl/evaluate-form repl-env env "<cljs repl>"
         '(do
            (.require js/goog "cljs.core")))
       ;; TODO: we can't merge this with the above, but note this doesn't work
       ;; in general (even with plain Closure JavaScript), require runs a bunch of
       ;; async loads and the following JS expression won't have access to any defs.
       ;; it only works in the Node.js REPL because we have the option for sync
       ;; loads - this is not possible in React Native
       (repl/evaluate-form repl-env env "<cljs repl>"
         '(enable-console-print!))
       (bootstrap/install-repl-goog-require repl-env env)
       (rn-eval repl-env
         (str "goog.global.CLOSURE_UNCOMPILED_DEFINES = "
           (json/write-str (:closure-defines opts)) ";"))))))

(defn krell-compile
  [repl-env {:keys [options] :as cfg}]
  (gen/write-rt-js options)
  ;; TODO: generate index.js
  ;; TODO: handle :optimizations higher than :none
  (cli/default-compile repl-env cfg))

(defrecord ReactNativeEnv [host port path socket state]
  repl/IReplEnvOptions
  (-repl-options [this]
    {:nodejs-rt     false
     :npm-deps      true
     :output-dir    ".krell_repl"
     :target        :nodejs
     ;; TODO: add :target-fn
     ::cli/commands {:groups
                     {::cli/main&compile
                      {:desc "init options"
                       :pseudos {["-re" "--repl-env"]
                                 {:arg "env"
                                  :doc (str "Defaults to the only supported value - krell.repl")}}}}
                     :init
                     {["-t" "--target"]
                      {:group ::cli/main&compile
                       :fn (fn [cfg target]
                             (assert (#{"node" "nodejs"} target) "Invalid --target, only nodejs supported")
                             cfg)
                       :arg "name"
                       :doc (str "The JavaScript target. Supported values: node or nodejs.")}}
                     :main
                     {["-s" "--serve"]
                      {:fn (fn [cfg opt]
                             (throw "--serve not supported"))
                       :arg "N/A"
                       :doc (str "NOT SUPPORTED")}}}
     ::cli/compile  krell-compile})
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
      (swap! state assoc :done true)
      (when-not (.isClosed (:socket sock))
        (write (:out sock) ":cljs/quit")
        (close-socket sock)))))

(defn repl-env* [options]
  (let [ep-map  (atom {})]
    (mdns/setup
      {:type         "http"
       :protocol     "tcp"
       :domain       "local."
       :endpoint-map ep-map
       :match-name   mdns/rn-repl?})
    (let [default (mdns/choose-default @ep-map)
          {:keys [host port path]}
          (merge
            {:host "localhost"
             :port 5002}
            default
            options)]
      (ReactNativeEnv. host port path (atom nil) (atom nil)))))

(defn repl-env
  "Construct a React Native evaluation environment."
  [& {:as options}]
  (repl-env* options))

(defn -main [& args]
  (apply cli/main repl-env args))

(comment

  (def ep-map (atom {}))

  (mdns/setup
    {:type "http"
     :protocol "tcp"
     :domain "local."
     :endpoint-map ep-map
     :match-name mdns/rn-repl?})

  (mdns/choose-default @ep-map)

  (cljs.repl/repl* (repl-env) {})

  )
