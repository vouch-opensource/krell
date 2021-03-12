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
            [krell.net :as net]
            [krell.passes :as passes]
            [krell.util :as util]
            [krell.watcher :as watcher])
  (:import [java.io File IOException]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def eval-lock (Object.))
(def results-queue (LinkedBlockingQueue.))
(def load-queue (LinkedBlockingQueue.))

(declare load-queued-files)

;; TODO: refactor this into send-message later
(defn rn-eval
  "Evaluate a JavaScript string in the React Native REPL"
  ([repl-env js]
   (rn-eval repl-env js nil))
  ([{:keys [options] :as repl-env} js request]
   (locking eval-lock
     (when (and (= "load-file" (:type request))
                (:krell/verbose options))
       (println "Load file:" (:value request)))
     (let [{:keys [out]} @(:socket repl-env)]
       (net/write out
         (json/write-str
           (merge {:type "eval" :form js}
             ;; if there was client driven request then pass on this
             ;; information back to the client
             (when request {:request request}))))
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
                         (pr-str (:status result)))
                       {:queue-value result})))]
         ret)))))

(defn send-file
  ([repl-env f opts]
   (send-file repl-env f nil opts))
  ([repl-env ^File f request opts]
   (if (.exists f)
     (rn-eval repl-env (slurp f)
       (merge
         {:type     "load-file"
          :value    (util/url-path f)
          :modified (util/last-modified f)}
         request))
     (throw (ex-info (str "File " f " does not exist") {:krell/error :file-missing})))))

(defn send-file-loop
  [{:keys [state] :as repl-env}]
  (while (not (:done @state))
    (try
      (when-let [{:keys [value] :as load-file-req} (.take load-queue)]
        (send-file repl-env
          (io/file (util/platform-path value)) load-file-req))
      (catch Throwable e
        (println e)))))

(defn last-modified-index
  [opts]
  (if-let [main (:main opts)]
    (into {}
      (map (fn [[k v]] [(util/url-path (:out-file v)) (:modified v)]))
      (deps/deps->graph
        (deps/with-out-files
          (deps/all-deps (ana-api/current-state) main opts) opts)))
    (throw (ex-info ":main namespace not supplied in build configuration"
             {:krell/error :main-ns-missing}))))

(defn cache-compare
  ([repl-env opts]
   (rn-eval repl-env nil
     (merge
       {:type "cache-compare"
        :index (last-modified-index opts)}))))

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
    (send-file repl-env (:out-file ijs)
      {:ns (-> ijs :provides first)})))

(defn init-js-env
  ([repl-env]
   (init-js-env repl-env repl/*repl-opts*))
  ([{:keys [options] :as repl-env} opts]
   (let [output-dir (io/file (:output-dir opts))
         env        (ana-api/empty-env)
         cljs-deps  (io/file output-dir "cljs_deps.js")
         repl-deps  (io/file output-dir "krell_repl_deps.js")]
     ;; Only ever load goog base *once*, all the dep
     ;; graph stuff is there an it needs to be preserved
     (if-not (base-loaded? repl-env)
       (do (when (:krell/verbose options)
             (println "BOOTSTRAP: Load GCL base files"))
           (send-file repl-env (io/file output-dir "goog/base.js") opts)
           (send-file repl-env (io/file output-dir "goog/deps.js") opts))
       (when (:krell/verbose options)
         (println "BOOTSTRAP: GCL base files already present")))
     (send-file repl-env repl-deps opts)
     (when (.exists cljs-deps)
       (send-file repl-env cljs-deps opts))
     (if-not (core-loaded? repl-env)
       (do
         (when (:krell/verbose options)
           (println "BOOTSTRAP: Load cljs.core"))
         ;; We cannot rely on goog.require because the debug loader assumes
         ;; you can load script synchronously which isn't possible in React
         ;; Native. Push core to the client and wait till everything is received
         ;; before proceeding
         (load-core repl-env opts)
         (repl/evaluate-form repl-env env "<cljs repl>"
           '(enable-console-print!))
         (when (:krell/verbose options)
           (println "BOOTSTRAP: Install REPL-friendly goog.require"))
         (bootstrap/install-repl-goog-require repl-env env))
       (when (:krell/verbose options)
         (println "BOOTSTRAP: cljs.core already present")))
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

(defn modified-source?
  [{:keys [file-index] :as repl-env} {:keys [type path]}]
  (or (= :modify type)
      (and (= :create type)
           (let [f (.getAbsoluteFile (util/to-file path))]
             (and (not (.isDirectory f))
                  (< (get file-index f) (util/last-modified f)))))))

(defn collecting-warning-handler [state]
  (fn [warn-type env info]
    (when (warn-type (ana-api/enabled-warnings))
      (let [msg (str (ana-api/warning-message warn-type info)
                  (when-let [line (:line env)] (str " at line " line)))]
        (swap! state conj msg)))))

(defn warn-client [repl-env s]
  (rn-eval repl-env (str "console.warn(" (pr-str s) ")")))

(defn recompile
  "Recompile the ClojureScript file specified by :path key in the first
  parameter. This is called by the watcher off the main thread."
  [repl-env {:keys [path] :as evt} opts]
  (when-not (:main opts)
    (throw (ex-info (str ":main namespace not supplied in build configuration")
             {:krell/error :main-ns-missing})))
  (let [src      (util/to-file path)
        path-str (.getPath src)]
    (when (and (modified-source? repl-env evt)
               (#{".cljc" ".cljs"} (subs path-str (.lastIndexOf path-str "."))))
      (try
        (let [state   (ana-api/current-state)
              ns-info (ana-api/parse-ns src)
              the-ns  (:ns ns-info)
              ancs    (deps/dependents the-ns
                        (deps/deps->graph
                          (deps/all-deps state (:main opts) opts))
                        (-> repl-env :options :recompile))
              all     (concat [ns-info] ancs)]
          (try
            ;; we need to compute js deps so that requires from node_modules won't fail
            (build-api/handle-js-modules state
              (build-api/dependency-order
                (build-api/add-dependency-sources all opts))
              opts)
            (loop [xs all deps-js ""]
              (if-let [ijs (first xs)]
                (let [warns   (atom [])
                      handler (collecting-warning-handler warns)
                      dest    (build-api/target-file-for-cljs-ns
                                (:ns ijs) (:output-dir opts))
                      ijs'    (try
                                (ana-api/with-warning-handlers [handler]
                                  (ana-api/with-passes
                                    (into ana-api/default-passes passes/custom-passes)
                                    (comp-api/compile-file state
                                      (:source-file ijs)
                                      (build-api/target-file-for-cljs-ns
                                        (:ns ijs) (:output-dir opts)) opts)))
                                (catch Throwable t
                                  (println t)
                                  (warn-client repl-env
                                    (str (:ns ns-info)
                                      " compilation failed with exception: "
                                      (.getMessage t)))
                                  nil))]
                  (if (empty? @warns)
                    (send-file repl-env dest {:reload true})
                    ;; TODO: it may be that warns strings have chars that will break console.warn ?
                    ;; TODO: also warn at REPL
                    (let [pre (str "Could not reload " (:ns ns-info) ":")]
                      (warn-client repl-env
                        (string/join "\n" (concat [pre] @warns)))))
                  (recur
                    (next xs)
                    ;; dep string must computed from a *compiled* file,
                    ;; thus ijs' and not ijs
                    (if (and ijs' (empty? @warns))
                      (str deps-js (build-api/goog-dep-string opts ijs'))
                      deps-js)))
                (rn-eval repl-env deps-js)))
            ;; notify client we're done
            (rn-eval repl-env nil {:type "reload"})))
        (catch Throwable t
          (println t))))))

(defn server-loop
  [{:keys [socket state] :as repl-env} server-socket]
  (when-let [conn (try (.accept server-socket) (catch Throwable _))]
    (.setKeepAlive conn true)
    (when-let [sock @socket]
      (future (net/close-socket sock)))
    (reset! socket (net/socket->socket-map conn))
    (when-not (:done @state)
      (recur repl-env server-socket))))

(defn setup
  ([repl-env] (setup repl-env nil))
  ([{:keys [options state socket] :as repl-env} opts]
   (let [port (:port options)]
     (println "\nWaiting for device connection on port" port)
     (.start
       (Thread.
         (bound-fn []
           (server-loop repl-env (net/create-server-socket port)))))
     (.start
       (Thread.
         (bound-fn []
           (send-file-loop repl-env)))))
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
   ;; Compare cache w/ client
   (cache-compare repl-env opts)
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
     ;; output unoptimized code and only the deps file for all compiled
     ;; namespaces, we don't need the bootstrap target file
     (apply closure/output-unoptimized
       (assoc (assoc opts :target :none)
         :output-to (.getPath repl-deps)) deps)
     (init-js-env repl-env opts))))

(defn choose-first-opt
  [cfg value]
  (assoc-in cfg [:repl-env-options :choose-first] (= value "true")))

(defn port-opt
  [cfg value]
  (assoc-in cfg [:repl-env-options :port] value))

(defn recompile-opt
  [cfg value]
  (assoc-in cfg [:repl-env-options :recompile] (keyword value)))

(defn watch-dirs-opt
  [cfg value]
  (assoc-in cfg [:repl-env-options :watch-dirs]
    (into [] (string/split value (re-pattern (str File/pathSeparatorChar))))))

(defn krell-compile
  [repl-env-var {:keys [repl-env-options options] :as cfg}]
  (gen/write-index-js options)
  (gen/write-repl-js (apply repl-env-var (mapcat identity repl-env-options)) options)
  (let [opt-level (:optimizations options)]
    (ana-api/with-passes
      (into ana-api/default-passes passes/custom-passes)
      (binding [passes/*nses-with-requires* (atom #{})]
        (cli/default-compile repl-env-var
          (cond->
            (assoc cfg
              :post-compile-fn
              #(let [nses (passes/cache-krell-requires @passes/*nses-with-requires* options)
                     analysis (passes/load-analysis nses options)]
                 (gen/write-assets-js (passes/all-assets analysis) options)
                 (gen/write-krell-npm-deps-js (passes/all-requires analysis) options)))
            (not (or (= :none opt-level) (nil? opt-level)))
            (assoc-in [:options :output-wrapper]
              (fn [source] (str source (gen/krell-main-js options))))))))))

(defrecord KrellEnv [options file-index socket state]
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
                      ["-wd" "--watch-dirs"]
                      {:group ::cli/main
                       :fn    watch-dirs-opt
                       :arg   "files"
                       :doc   (str "A platform separated list of directories to watch for REPL hot-reloading")}
                      ["-p" "--port"]
                      {:group ::cli/main
                       :fn    port-opt
                       :arg   "number"
                       :doc   (str "Sets port for target clients to bind to.")}
                      ["-rc" "--recompile"]
                      {:group ::cli/main
                       :fn    recompile-opt
                       :arg   "string"
                       :doc   (str "Flag for recompile strategy. Supported values: direct, all. If direct, Krell will only"
                                   "recompile namespaces that directly depend on the changed one. Defaults to \"direct\".")}}
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

(defn file-index [dirs]
  (reduce
    (fn [ret ^File f]
      (assoc ret (.getAbsoluteFile f) (util/last-modified f)))
    {} (mapcat (comp util/files-seq io/file) dirs)))

(defn repl-env* [options]
  (let [watch-dirs (:watch-dirs options ["src"])
        index      (file-index watch-dirs)]
    (KrellEnv.
      (merge
        {:port            5001
         :watch-dirs      watch-dirs
         :connect-timeout 30000
         :eval-timeout    30000
         :recompile       :direct}
        options)
      index (atom nil) (atom nil))))

(defn repl-env
  "Construct a React Native evaluation environment."
  [& {:as options}]
  (repl-env* options))

(defn -main [& args]
  (apply cli/main repl-env args))
