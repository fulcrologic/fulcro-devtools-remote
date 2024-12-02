(ns com.fulcrologic.devtools.utils
  (:require
    #?@(:cljs [[goog.object :as gobj]])
    [clojure.walk :refer [prewalk]]))

(defn strip-lambdas
  "Replace functions in data structures from data structure x with their name or `\"FN\"`. "
  [x] (prewalk (fn [ele] (if (fn? ele)
                           (or
                             (:name (meta ele))
                             "FN")
                           ele)) x))

(defn isoget
  "Like get, but for js objects, and in CLJC. In clj, it is just `get`. In cljs it is
  `gobj/get`."
  ([obj k] (isoget obj k nil))
  ([obj k default]
   #?(:clj  (get obj k default)
      :cljs (or (gobj/get obj (some-> k (name))) default))))

(defn isoget-in
  "Like get-in, but for js objects, and in CLJC. In clj, it is just get-in. In cljs it is
  gobj/getValueByKeys."
  ([obj kvs]
   (isoget-in obj kvs nil))
  ([obj kvs default]
   #?(:clj (get-in obj kvs default)
      :cljs
      (let [ks (mapv (fn [k] (some-> k name)) kvs)]
        (or (apply gobj/getValueByKeys obj ks) default)))))

(comment


  (isoget-in #js {"a" #js {"b" 1}} ["a" "b"])
  )

