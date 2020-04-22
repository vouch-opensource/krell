(ns krell.mdns
  (:require [clojure.string :as string])
  (:import [java.net InetAddress Inet4Address NetworkInterface]
           [javax.jmdns JmDNS ServiceListener]))

(def krell-prefix "krell.repl")

(defn rn-repl? [name]
  (string/starts-with? name krell-prefix))

(defn setup
  "Sets up mDNS to populate atom supplied in name-endpoint-map with discoveries.
  Returns a function that will tear down mDNS."
  [{:keys [type protocol domain endpoint-map match-name]}]
  (let [reg-type (str "_" type "._" protocol "." domain)
        mdns-service (JmDNS/create (InetAddress/getLocalHost))
        service-listener
        (reify ServiceListener
          (serviceAdded [_ service-event]
            (let [type (.getType service-event)
                  name (.getName service-event)]
              (when (and (= reg-type type) (match-name name))
                (.requestServiceInfo mdns-service type name 1))))
          (serviceRemoved [_ service-event]
            (swap! endpoint-map dissoc (.getName service-event)))
          (serviceResolved [_ service-event]
            (let [type (.getType service-event)
                  name (.getName service-event)]
              (when (and (= reg-type type) (match-name name))
                (let [entry {name (let [info (.getInfo service-event)]
                                    {:host (.getHostAddress (.getAddress info))
                                     :port (.getPort info)})}]
                  (swap! endpoint-map merge entry))))))]
    (.addServiceListener mdns-service reg-type service-listener)
    (fn []
      (.removeServiceListener mdns-service reg-type service-listener)
      (.close mdns-service))))

(defn ip-address->inet-addr
  "Take a string representation of an IP address and returns a Java InetAddress
  instance, or nil if the conversion couldn't be completed."
  [ip]
  (try
    (InetAddress/getByName ip)
    (catch Throwable _
      nil)))

(defn local?
  "Takes an IP address and returns a truthy value iff the address is local
  to the machine running this code."
  [ip]
  (or
    ;; Android
    (string/starts-with? ip "10.0.2")
    (some-> ip
      ip-address->inet-addr
      NetworkInterface/getByInetAddress)))

(defn address-type
  "Takes an IP address and returns a keyword in #{:ipv4 :ipv6}
  indicating the type of the address, or nil if the type could not
  be determined"
  [ip]
  (if-let [inet-address (ip-address->inet-addr ip)]
    (if (instance? Inet4Address inet-address)
      :ipv4
      :ipv6)))

(defn address-type->localhost-address
  "Given an address type, returns the localhost address."
  [address-type]
  (address-type {:ipv4 "127.0.0.1" :ipv6 "::1"}))

(defn local-address-if
  "Takes an IP address and returns the localhost address if the
  address happens to be local to this machine."
  [ip]
  (if (local? ip)
    (-> ip
      address-type
      address-type->localhost-address)
    ip))

(defn bonjour-name->display-name
  "Converts an Krell Bonjour service name to a display name
  (stripping off krell-prefix)."
  [bonjour-name]
  (subs bonjour-name (count krell-prefix)))

(defn ep-map->choice-list [ep-map]
  "Takes a name to endpoint map, and converts into an indexed list."
  (map vector
    (iterate inc 1)
    (sort-by (juxt (comp (complement local?) :host second) first)
      ep-map)))

(defn print-discovered [ep-map]
  "Prints the set of discovered devices given a name endpoint map."
  (if (empty? ep-map)
    (println "(No devices)")
    (doseq [[choice-number [bonjour-name _]] (ep-map->choice-list ep-map)]
      (println
        (str "[" choice-number "] "
          (bonjour-name->display-name bonjour-name))))))

(defn discover [choose-first?]
  (let [ep-map (atom {})
        mdn-cfg {:type         "http"
                 :protocol     "tcp"
                 :domain       "local."
                 :endpoint-map ep-map
                 :match-name   rn-repl?}
        tear-down-mdns
        (loop [count 0
               tear-down-mdns (setup mdn-cfg)]
          (if (empty? @ep-map)
            (do
              (Thread/sleep 100)
              (when (= 20 count)
                (println "\nSearching for devices ..."))
              (if (zero? (rem (inc count) 100))
                (do
                  (tear-down-mdns)
                  (recur (inc count) (setup mdn-cfg)))
                (recur (inc count) tear-down-mdns)))
            tear-down-mdns))]
    (try
      ;; Sleep a little more to catch stragglers
      (Thread/sleep 500)
      (loop [cep-map @ep-map]
        (when-not choose-first?
          (print-discovered cep-map)
          (println "\n[R] Refresh\n")
          (print "Choice: ")
          (flush))
        (let [choice (if choose-first? "1" (read-line))]
          (if (= "r" (.toLowerCase choice))
            (recur @ep-map)
            (let [choices (ep-map->choice-list cep-map)
                  choice-ndx (try
                               (dec (Long/parseLong choice))
                               (catch NumberFormatException _ -1))]
              (if (< -1 choice-ndx (count choices))
                (second (nth choices choice-ndx))
                (recur cep-map))))))
      (finally
        (.start (Thread. tear-down-mdns))))))
