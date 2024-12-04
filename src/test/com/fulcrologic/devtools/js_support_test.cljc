(ns com.fulcrologic.devtools.js-support-test
  (:require
    [com.fulcrologic.devtools.common.js-support :refer [js js?]]
    [com.fulcrologic.devtools.common.utils :refer [isoget isoget-in]]
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "Convert to js objects"
  (assertions
    "Converts a CLJC data structure to a js one."
    (js? (js {:a 1})) => true
    "isoget works on the js object using string or keyword keys"
    (isoget (js {:a 1}) "a") => 1
    (isoget (js {:a 1}) :a) => 1
    (isoget (js {"a" 1}) :a) => 1
    (isoget (js {"a" 1}) "a") => 1
    (isoget-in (js {:a 1}) ["a"]) => 1
    (isoget-in (js {:a 1}) [:a]) => 1))
