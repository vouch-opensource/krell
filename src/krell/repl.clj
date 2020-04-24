(ns krell.repl
  (:require [cljs.analyzer.api :as ana-api]
            [cljs.build.api :as build-api]
            [cljs.cli :as cli]
            [cljs.closure :as closure]
            [cljs.compiler.api :as comp-api]
            [cljs.repl :as repl]
            [cljs.repl.bootstrap :as bootstrap]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [krell.deps :as deps]
            [krell.gen :as gen]
            [krell.mdns :as mdns]
            [krell.net :as net]
            [krell.passes :as passes]
            [krell.util :as util]
            [krell.watcher :as watcher])
  (:import [clojure.lang ExceptionInfo]
           [java.io File IOException]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def eval-lock (Object.))
(def results-queue (LinkedBlockingQueue.))
(def load-queue (LinkedBlockingQueue.))

(declare load-queued-files)

(defn rn-eval
  "Evaluate a JavaScript string in the React Native REPL"
  ([repl-env js]
   (rn-eval repl-env js nil))
  ([{:keys [options] :as repl-env} js client-req]
   (locking eval-lock
     (when (and (= "load-file" (:type client-req))
                (:verbose (ana-api/get-options)))
       (println "Load file:" (:value client-req)))
     (let [{:keys [out]} @(:socket repl-env)]
       (net/write out
         (json/write-str
           (merge {:type "eval" :form js}
             ;; if there was client driven request then pass on this
             ;; information back to the client
             (when client-req {:request client-req}))))
       ;; assume transfer won't be slower than 100K/s on a local network
       (let [ack (.poll results-queue
                   (max 1 (quot (count js) (* 100 1024))) TimeUnit/SECONDS)]
         (if (or (nil? ack) (not= "ack" (:type ack)))
           (throw (ex-info "Connection lost" {:type :no-ack :queue-value ack}))
           (let [result (.take results-queue)
                 ret (condp = (:status result)
                       "success"
                       {:status :success
                        :value  (:value result)}

                       "exception"
                       {:status :exception
                        :value  (:value result)}
                       (throw
                         (ex-info
                           (str "Unexpected message type: "
                             (pr-str (:status result)) )
                           {:queue-value result})))]
             ;; load any queued files now to simulate sync loads
             (load-queued-files repl-env)
             ret)))))))

(defn load-queued-files [repl-env]
  (loop [{:keys [value] :as load-file-req} (.poll load-queue)]
    (when load-file-req
      (let [f (io/file value)]
       (rn-eval repl-env (slurp f) load-file-req))
      (recur (.poll load-queue)))))

(defn load-javascript
  "Load a Closure JavaScript file into the React Native REPL"
  [repl-env provides url]
  (rn-eval repl-env (slurp url)))

(defn event-loop
  "Event loop that listens for responses from the client."
  [{:keys [state socket] :as repl-env}]
  (while (not (:done @state))
    (try
      (let [res (net/read-response (:in @socket))]
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
        ;; TODO: we should probably log something here
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

(defn load-core [repl-env opts]
  (doseq [ijs (-> (deps/sorted-deps
                    (ana-api/current-state) 'cljs.core opts)
                (deps/with-out-files opts))]
    (let [out-file (:out-file ijs)
          contents (slurp out-file)]
      (rn-eval repl-env contents
        {:type  "load-file"
         :ns    (-> ijs :provides first)
         :value (.getPath ^File out-file)}))))

(defn init-js-env
  ([repl-env]
   (init-js-env repl-env repl/*repl-opts*))
  ([repl-env opts]
   (let [output-dir (io/file (:output-dir opts))
         env        (ana-api/empty-env)
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
       ;; We cannot rely on goog.require because the debug loader assumes
       ;; you can load script synchronously which isn't possible in React
       ;; Native. Push core to the client and wait till everything is received
       ;; before proceeding
       (load-core repl-env opts)
       (repl/evaluate-form repl-env env "<cljs repl>"
         '(enable-console-print!))
       (bootstrap/install-repl-goog-require repl-env env))
     (rn-eval repl-env
       (str "goog.global.CLOSURE_UNCOMPILED_DEFINES = "
         (json/write-str (:closure-defines opts)) ";"))
     ;; setup printing
     (repl/evaluate-form repl-env env "<cljs repl>"
       '((fn []
           (fn redirect-output [out]
             (set! *print-newline* true)
             (set! *print-fn*
               (fn [str]
                 (->> (js-obj "type" "out" "value" str)
                   (.stringify js/JSON)
                   (.write out))
                 (.write out "\0")))
             (set! *print-err-fn*
               (fn [str]
                 (->> (js-obj "type" "err" "value" str)
                   (.stringify js/JSON)
                   (.write out))
                 (.write out "\0"))))))))))

(defn modified-source? [{:keys [type path]}]
  (or (= :modify type)
      (and (= :create type)
           (not (.isDirectory (util/to-file path))))))

(defn collecting-warning-handler [state]
  (fn [warn-type env info]
    (let [msg (str (ana-api/warning-message warn-type info)
                (when-let [line (:line env)] (str " at line " line)))]
      (swap! state conj msg))))

(defn warn-client [repl-env s]
  (rn-eval repl-env (str "console.warn(" (pr-str s) ")")))

(defn recompile
  "Recompile the ClojureScript file specified by :path key in the first
  parameter. This is called by the watcher off the main thread."
  [repl-env {:keys [path] :as evt} opts]
  (let [src      (util/to-file path)
        path-str (.getPath src)]
    (when (and (modified-source? evt)
            (#{".cljc" ".cljs"} (subs path-str (.lastIndexOf path-str "."))))
      (let [state   (ana-api/current-state)
            ns-info (ana-api/parse-ns src)
            dest    (build-api/target-file-for-cljs-ns
                      (:ns ns-info) (:output-dir opts))
            warns   (atom [])
            handler (collecting-warning-handler warns)]
        (try
          ;; we need to compute js deps so that requires from node_modules
          ;; won't fail
          (build-api/handle-js-modules state
            (build-api/dependency-order
              (build-api/add-dependency-sources [ns-info] opts))
            opts)
          (ana-api/with-warning-handlers [handler]
            (ana-api/with-passes
              ;; TODO: touch index.js? or do something else?
              (conj ana-api/default-passes passes/rewrite-asset-requires)
              (comp-api/compile-file state
                (:source-file ns-info)
                (build-api/target-file-for-cljs-ns
                  (:ns ns-info) (:output-dir opts)) opts)))
          (if (empty? @warns)
            (rn-eval repl-env (slurp dest)
              {:type   "load-file"
               :reload true
               :value  (.getPath src)})
            ;; TODO: it may be that warns strings have chars that will break
            ;; console.warn ?
            (let [pre (str "Could not reload " (:ns ns-info) ":")]
              (warn-client repl-env
                (string/join "\n" (concat [pre] @warns)))))
          (catch Throwable t
            (println t)
            (warn-client repl-env
              (str (:ns ns-info)
                " compilation failed with exception: "
                (.getMessage t)))))))))

(defn server-loop
  [{:keys [socket state] :as repl-env} server-socket]
  (when-let [conn (try (.accept server-socket) (catch Throwable _))]
    (.setKeepAlive conn true)
    ;; TODO: just ignoring new connections for now, maybe we want to do
    ;; something else? i.e. the current socket was closed because of a RN
    ;; reload
    (when-not @socket
      (reset! socket (net/socket->socket-map conn)))
    (when-not (:done @state)
      (recur repl-env server-socket))))

(defn setup
  ([repl-env] (setup repl-env nil))
  ([{:keys [options state socket] :as repl-env} opts]
   (let [port (:port options)]
     (println "\nWaiting for device connection on port" port)
     ;; TODO: put mdns into the state so we can cleanup in REPL teardown
     (mdns/register-service (mdns/jmdns) (mdns/krell-service-info port))
     (.start
       (Thread.
         (bound-fn []
           (server-loop repl-env (net/create-server-socket port))))))
   (while (not @socket)
     (Thread/sleep 500))
   (.start (Thread. (bound-fn [] (event-loop repl-env))))
   ;; create and start the watcher
   (swap! state assoc :watcher
     (doto
       (apply watcher/create
         ;; have to pass the processed opts
         ;; the compiler one are the original ones
         (bound-fn [e] (recompile repl-env e opts))
         (:watch-dirs options))
       (watcher/watch)))
   ;; compile cljs.core & its dependencies, goog/base.js must be available
   ;; for bootstrap to load, use new closure/compile as it can handle
   ;; resources in JARs
   (let [output-dir (io/file (:output-dir opts))
         core       (io/resource "cljs/core.cljs")
         core-js    (closure/compile core
                      (assoc opts
                        :output-file
                        (closure/src-file->target-file
                          core (dissoc opts :output-dir))))
         deps       (closure/add-dependencies opts core-js)
         repl-deps  (io/file output-dir "krell_repl_deps.js")]
     ;; output unoptimized code and the deps file
     ;; for all compiled namespaces
     (apply closure/output-unoptimized
       (assoc opts
         :output-to (.getPath repl-deps)) deps)
     (init-js-env repl-env opts))))

(defn choose-first-opt
  [cfg value]
  (assoc-in cfg [:repl-env-options :choose-first] (= value "true")))

(defn port-opt
  [cfg value]
  (assoc-in cfg [:repl-env-options :port] value))

(defn krell-compile
  [repl-env-var {:keys [repl-env-options options] :as cfg}]
  (gen/write-index-js options)
  (gen/write-repl-js (apply repl-env-var repl-env-options) options)
  (let [opt-level (:optimizations options)
        state     (atom {})]
    (binding [passes/*state* state]
      (ana-api/with-passes
        (conj ana-api/default-passes passes/rewrite-asset-requires)
        (cli/default-compile repl-env-var
          (cond-> (assoc cfg :post-compile-fn #(gen/write-assets-js (:assets @state) options))
            (not (or (= :none opt-level) (nil? opt-level)))
            (assoc-in [:options :output-wrapper]
              (fn [source] (str source (gen/krell-main-js options))))))))))

(defrecord KrellEnv [options socket state]
  repl/IReplEnvOptions
  (-repl-options [this]
    {
     :output-dir    ".krell_repl"

     ;; RN target defaults
     :process-shim  false
     :target        :bundle
     :target-fn     'krell.gen/krell-main-js

     ;; cljs.cli extension points
     ::repl/fast-initial-prompt? :after-setup
     ::cli/commands {:groups
                     {::cli/main&compile
                      {:desc "init options"
                       :pseudos {["-re" "--repl-env"]
                                 {:arg "env"
                                  :doc (str "Defaults to the only supported value - krell.repl")}}}}
                     :init
                     {["-t" "--target"]
                      {:group ::cli/main&compile
                       :fn    (fn [cfg target]
                                (assert (#{"node" "nodejs"} target) "Invalid --target, only nodejs supported")
                                cfg)
                       :arg   "name"
                       :doc   (str "The JavaScript target. Supported values: node or nodejs.")}
                      ["-f" "--choose-first"]
                      {:group ::cli/main
                       :fn    choose-first-opt
                       :arg   "bool"
                       :doc   (str "Choose the first discovered available REPL service.")}
                      ["-p" "--port"]
                      {:group ::cli/main
                       :fn    port-opt
                       :arg   "number"
                       :doc   (str "When ---mdns is false, sets port for target clients to bind to.")}}
                     :main
                     {["-s" "--serve"]
                      {:fn (fn [cfg opt]
                             (throw "--serve not supported"))
                       :arg "N/A"
                       :doc (str "NOT SUPPORTED")}}}
     ::cli/compile  krell-compile
     })
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
      (when-let [w (:watcher @state)]
        (watcher/stop w))
      ;; the socket might have been destroyed by a RN refresh so do this
      ;; off the main thread
      (future
        (when (and (:socket sock)
                   (not (.isClosed (:socket sock))))
          (net/close-socket sock))))))

(defn repl-env* [options]
  (KrellEnv.
    (merge
      {:port            5001
       :watch-dirs      ["src"]
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
