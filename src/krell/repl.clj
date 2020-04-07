(ns krell.repl
  (:require [cljs.analyzer :as ana]
            [cljs.cli :as cli]
            [cljs.closure :as closure]
            [cljs.compiler :as comp]
            [cljs.repl :as repl]
            [cljs.repl.bootstrap :as bootstrap]
            [cljs.util :as cljs-util]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [krell.gen :as gen]
            [krell.mdns :as mdns]
            [krell.util :as util])
  (:import [clojure.lang ExceptionInfo]
           [java.io BufferedReader BufferedWriter File IOException]
           [java.net Socket]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

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

(defn rn-eval*
  "Evaluate a JavaScript string in the React Native REPL"
  ([repl-env js]
   (rn-eval* repl-env js nil))
  ([{:keys [options] :as repl-env} js req]
   (locking eval-lock
     (let [{:keys [out]} @(:socket repl-env)]
       (write out (json/write-str
                    (merge {:type "eval" :form js}
                      ;; if there was client driven request then pass on this
                      ;; information back to the client
                      (when req {:request req}))))
       (let [ack (.poll results-queue
                   ^long (:ack-timeout options) TimeUnit/MILLISECONDS)]
         (if (nil? ack)
           (throw (ex-info "No ack" {:error :no-ack}))
           (let [result (.take results-queue)
                 ret (condp = (:status result)
                       "success"
                       {:status :success
                        :value  (:value result)}

                       "exception"
                       {:status :exception
                        :value  (:value result)})]
             ;; load any queued files now to simulate sync loads
             (load-queued-files repl-env)
             ret)))))))

(declare rn-eval)

(defn load-queued-files [repl-env]
  (loop [{:keys [value] :as load-file-req} (.poll load-queue)]
    (when load-file-req
      (rn-eval repl-env
        (slurp (io/file value)) load-file-req)
      (recur (.poll load-queue)))))

(defn load-javascript
  "Load a Closure JavaScript file into the React Native REPL"
  [repl-env provides url]
  (rn-eval repl-env
    (str "goog.require('" (comp/munge (first provides)) "')")))

(defn event-loop [{:keys [state socket] :as repl-env}]
  (while (not (:done @state))
    (try
      (let [res (read-response (:in @socket))]
        (try
          (let [{:keys [type value] :as event}
                (json/read-str res :key-fn keyword)]
            (case type
              "ack"       (.offer results-queue event)
              "load-file" (.offer load-queue event)
              "result"    (.offer results-queue event)
              (when-let [stream (if (= type "out") *out* *err*)]
                (.write stream value 0 (.length ^String value))
                (.flush stream))))
          (catch Throwable _
            (.write *out* res 0 (.length res))
            (.flush *out*))))
      (catch IOException e
        ;; sleep a bit, no need to spin while we wait for a reconnect
        (Thread/sleep 500)))))

(defn base-loaded? [repl-env]
  (= "true"
     (:value
       (rn-eval repl-env
         "(function(){return (typeof goog !== 'undefined');})()"))))

(defn core-loaded? [repl-env]
  (= "true"
     (:value
       (rn-eval repl-env
         "(function(){return (typeof cljs !== 'undefined');})()"))))

(defn init-js-env
  ([repl-env]
   (init-js-env repl-env repl/*repl-opts*))
  ([repl-env opts]
   (let [output-dir (io/file (cljs-util/output-directory opts))
         env        (ana/empty-env)
         cljs-deps  (io/file output-dir "cljs_deps.js")
         repl-deps  (io/file output-dir "krell_repl_deps.js")
         base-path  (.getPath (io/file (:output-dir opts) "goog"))]
     ;; prevent auto-loading of deps.js - not really necessary since
     ;; we write our own and it will override google's dep.js entries
     (rn-eval repl-env
       "var CLOSURE_NO_DEPS = true;")
     (rn-eval repl-env
       (str "var CLOSURE_BASE_PATH = \"" base-path File/separator "\";"))
     ;; Only ever load goog base *once*, all the dep
     ;; graph stuff is there an it needs to be preserved
     (when-not (base-loaded? repl-env)
       (rn-eval repl-env
         (slurp (io/resource "goog/base.js")))
       (rn-eval repl-env
         (slurp (io/resource "goog/deps.js"))))
     (rn-eval repl-env (slurp repl-deps))
     (when (.exists cljs-deps)
       (rn-eval repl-env (slurp cljs-deps)))
     (when-not (core-loaded? repl-env)
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
       (bootstrap/install-repl-goog-require repl-env env))
     (rn-eval repl-env
       (str "goog.global.CLOSURE_UNCOMPILED_DEFINES = "
         (json/write-str (:closure-defines opts)) ";")))))

(defn connect [{:keys [options socket state] :as repl-env}]
  (let [start (util/now)
        {:keys [host port]} @state]
    (loop [r nil]
      (if (< (:connect-timeout options) (util/elapsed start))
        (throw
          (ex-info "Could not connect Krell REPL" {}))
        (when-not (= r "ready")
          (Thread/sleep 250)
          (try
            (reset! socket (create-socket host port))
            (catch Exception e
              (println e)))
          (if @socket
            (recur (read-response (:in @socket)))
            (recur nil)))))))

(defn reconnect [repl-env]
  ;; clear anything pending in the queues
  (.clear load-queue)
  (.clear results-queue)
  (connect repl-env)
  (init-js-env repl-env))

(defn rn-eval
  ([repl-env js]
   (rn-eval repl-env js nil))
  ([repl-env js req]
   (try
     (rn-eval* repl-env js req)
     (catch ExceptionInfo e
       (if (= :no-ack (:type (ex-data e)))
         (do
           (reconnect repl-env)
           {:status :exception
            :value  "Connection was reset by React Native"})
         (throw e))))))

(defn setup
  ([repl-env] (setup repl-env nil))
  ([{:keys [state socket] :as repl-env} opts]
   (let [[bonjour-name {:keys [host port] :as ep}]
         (mdns/discover (boolean (:choose-first (:options repl-env))))
         host (mdns/local-address-if host)]
     (swap! state merge {:host host :port port})
     (println
       (str "\nConnecting to " (mdns/bonjour-name->display-name bonjour-name) " (" host ":" port ")" " ...\n"))
     (when-not @socket
       (connect repl-env)
       (.start (Thread. (bound-fn [] (event-loop repl-env))))
       ;; compile cljs.core & its dependencies, goog/base.js must be available
       ;; for bootstrap to load, use new closure/compile as it can handle
       ;; resources in JARs
       (let [output-dir (io/file (cljs-util/output-directory opts))
             core       (io/resource "cljs/core.cljs")
             core-js    (closure/compile core
                          (assoc opts :output-file
                            (closure/src-file->target-file
                              core (dissoc opts :output-dir))))
             deps       (closure/add-dependencies opts core-js)
             repl-deps  (io/file output-dir "krell_repl_deps.js")]
         ;; output unoptimized code and the deps file
         ;; for all compiled namespaces
         (apply closure/output-unoptimized
           (assoc opts
             :output-to (.getPath repl-deps)) deps)
         (init-js-env repl-env opts))))))

(defn krell-choose-first-opt
  [cfg value]
  (assoc-in cfg [:repl-env-options :choose-first] (= value "true")))

(defn krell-compile
  [repl-env {:keys [options] :as cfg}]
  (gen/write-rt-js options)
  (gen/write-index-js options)
  (gen/write-repl-js options)
  (let [opt-level (:optimizations options)]
    (cli/default-compile repl-env
      (cond-> cfg
        (not (or (= :none opt-level) (nil? opt-level)))
        (assoc-in [:options :output-wrapper]
          (fn [source] (str source (gen/krell-main-js options))))))))

(defrecord KrellEnv [options socket state]
  repl/IReplEnvOptions
  (-repl-options [this]
    {:nodejs-rt     false
     :npm-deps      true
     :output-dir    ".krell_repl"
     :target        :nodejs
     :target-fn     'krell.gen/krell-main-js
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
                       :doc (str "The JavaScript target. Supported values: node or nodejs.")}
                      ["-f" "--choose-first"]
                      {:group ::cli/main
                       :fn krell-choose-first-opt
                       :arg "bool"
                       :doc (str "Choose the first discovered available REPL service.")}}
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
      (when (and (:socket sock)
                 (not (.isClosed (:socket sock))))
        (write (:out sock) ":cljs/quit")
        (close-socket sock)))))

(defn repl-env* [options]
  (KrellEnv.
    (merge
      {:ack-timeout     3000
       :connect-timeout 30000
       :eval-timeout    30000}
      options)
    (atom nil) (atom nil)))

(defn repl-env
  "Construct a React Native evaluation environment."
  [& {:as options}]
  (repl-env* options))

(defn -main [& args]
  (apply cli/main repl-env args))
