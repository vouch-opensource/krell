(ns krell.mdns
  (:import [java.net InetAddress]
           [javax.jmdns JmDNS ServiceInfo]))

(defn config->type
  [{:keys [type protocol domain]}]
  (str "_" type "._" protocol "." domain))

(defn jmdns []
  (let [ip (InetAddress/getLocalHost)]
    (JmDNS/create ip (.getHostName ip))))

(defn krell-service-info ^ServiceInfo
  ([]
   (krell-service-info 5001))
  ([port]
   (ServiceInfo/create
     (config->type {:type "http" :protocol "tcp" :domain "local."})
     (str "Krell-REPL-Server:" port) port "Krell REPL Server")))

(defn register-service [^JmDNS jmdns ^ServiceInfo service-info]
  (.registerService jmdns service-info))
