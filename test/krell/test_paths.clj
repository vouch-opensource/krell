(ns krell.test-paths
  (:require [clojure.java.io :as io]
            [clojure.test :as test :refer [deftest is run-tests]]
            [krell.util :as util]))

(deftest test-closure-relative-path
  (let [{:keys [output-dir] :as opts} {:output-dir "target"}]
    (is (= "base.js"
           (util/closure-relative-path
             (io/file output-dir "goog/base.js")
             opts)))
    (is (= "object/object.js"
          (util/closure-relative-path
            (io/file output-dir "goog/object/object.js")
            opts)))
    (is (= "../cljs/core.js"
          (util/closure-relative-path
            (io/file output-dir "cljs/core.js")
            opts)))))
