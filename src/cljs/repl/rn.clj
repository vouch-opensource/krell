(ns cljs.repl.rn
  (:require [clojure.string :as string]
            [cljs.repl.rn.mdns :as mdns]))

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
