(ns com.fulcrologic.devtools.schemas
  (:require
    #?(:cljs [goog.object :as gobj])
    [com.fulcrologic.guardrails.malli.core :refer [>def]]
    [com.fulcrologic.devtools.message-keys :as mk]
    [com.fulcrologic.devtools.constants :as constants]
    [malli.core :as m]))

(defn- isoget
  ([obj k]
   (isoget obj k nil))
  ([obj k default]
   #?(:clj  (get obj k default)
      :cljs (or (gobj/get obj (some-> k (name))) default))))

(defn iso-map
  "Returns a Malli spec that can check a JSON OR EDN map."
  [& key-specs]
  [:fn
   (fn [v]
     (every?
       (fn [kspec]
         (let [data-key (name (first kspec))
               options? (= 3 (count kspec))
               {:keys [optional] :as options} (if options? (second kspec) {})
               schema   (last kspec)
               kv       (isoget v data-key)]
           (if (and optional (nil? kv))
             true
             (m/validate schema kv))))
       key-specs))])

(>def :cljc/map #?(:clj  map?
                   :cljs [:or map? [:fn #(goog/isObject %)]]))
(>def :cljc/map-key [:or :string :keyword])
(>def :chrome/service-worker-port :any)
(>def :transit/encoded-string :string)
(>def :chrome/devtool->service-worker-message (iso-map
                                                [:data :transit/encoded-string]
                                                [:tab-id :int]))
(defn atom? [a] #?(:cljs (satisfies? IAtom a) :clj (instance? clojure.lang.Atom a)))
(>def :clojure/atom [:fn {:error/message "Expected an atom"} atom?])
(>def :fulcro/application [:map
                           [:com.fulcrologic.fulcro.application/runtime-atom :clojure/atom]])
(>def :chrome.event/content-script->target
  (iso-map
    [:data
     (iso-map
       [constants/content-script->target-key
        (iso-map
          [:data :transit/encoded-string])])]))

(>def :chrome.event/target->content-script
  (iso-map
    [:data
     (iso-map
       [constants/target->content-script-key
        (iso-map
          [:data :transit/encoded-string])])]))


(>def ::devtool-error
  [:map
   [mk/request-id {:optional true} :uuid]
   [mk/target-id :uuid]
   [mk/error :string]])

(>def ::devtool-response
  [:map
   [mk/request-id :uuid]
   [mk/target-id :uuid]
   [mk/response map?]])

(>def ::devtool-request
  [:map
   [mk/request-id :uuid]
   [mk/target-id :uuid]
   [mk/eql vector?]])

(>def ::devtool-message
  [:and
   [:or
    ::devtool-error
    ::devtool-response
    ::devtool-request]
   [:map {:closed true}
    [mk/target-id :uuid]
    [mk/error {:optional true} :string]
    [mk/eql {:optional true} :vector]
    [mk/request-id {:optional true} :uuid]
    [mk/active-targets {:optional true} [:map :uuid :string]]
    [mk/timestamp {:optional true} inst?]
    [mk/tab-id {:optional true} :int]
    [mk/response {:optional true} map?]]])
