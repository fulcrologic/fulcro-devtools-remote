(ns com.fulcrologic.devtools.common.schemas
  (:require
    #?(:cljs [goog.object :as gobj])
    [clojure.core.async.impl.protocols :as asyncp]
    [com.fulcrologic.devtools.common.constants :as constants]
    [com.fulcrologic.devtools.common.js-support :refer [js?]]
    [com.fulcrologic.devtools.common.message-keys :as mk]
    [com.fulcrologic.guardrails.malli.core :refer [>def]]
    [com.fulcrologic.guardrails.malli.registry :as gr.reg]
    [malli.core :as m]))

(defn chan?
  "Check if c is a core.async channel."
  [c]
  (satisfies? asyncp/ReadPort c))

(defn- isoget
  ([obj k]
   (isoget obj k nil))
  ([obj k default]
   #?(:clj  (get obj k default)
      :cljs (or (gobj/get obj (some-> k (name))) default))))

(defn iso-map
  "Returns a Malli spec that can check a JSON OR EDN map."
  [& key-specs]
  [:fn {:error/message "Invalid map content"}
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
             (m/validate schema kv {:registry gr.reg/registry}))))
       key-specs))])

(defn js-map [& key-specs]
  [:and
   :javascript/object
   (apply iso-map key-specs)])

(>def :cljc/map #?(:clj  map?
                   :cljs [:or map? :javascript/object]))
(>def :cljc/map-key [:or :string :keyword])
(>def :javascript/object [:fn {:error/message "not a js object"} js?])
(>def :chrome/service-worker-port #?(:cljs [:fn (fn [x] (boolean (.-name x)))]
                                     :clj (js-map [:name :string])))
(>def :transit/encoded-string :string)
(>def :chrome/service-worker-message (js-map
                                       [:data :transit/encoded-string]
                                       [:tab-id :int]))
(defn atom? [a] #?(:cljs (satisfies? IAtom a) :clj (instance? clojure.lang.Atom a)))
(>def :clojure/atom [:fn {:error/message "Expected an atom"} atom?])
(>def :fulcro/application [:map
                           [:com.fulcrologic.fulcro.application/runtime-atom :clojure/atom]])
(>def :js/event (js-map [:data :any]))
(>def :EQL/expression vector?)
(>def :chrome.event/content-script->target
  (js-map
    [:data
     (js-map
       [constants/content-script->target-key :chrome/service-worker-message])]))

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
   [mk/request vector?]])

(>def ::devtool-message
  [:and
   [:or
    ::devtool-error
    ::devtool-response
    ::devtool-request]
   [:map {:closed true}
    [mk/target-id :uuid]
    [mk/error {:optional true} :string]
    [mk/request {:optional true} :vector]
    [mk/request-id {:optional true} :uuid]
    [mk/active-targets {:optional true} [:map :uuid :string]]
    [mk/timestamp {:optional true} inst?]
    [mk/tab-id {:optional true} :int]
    [mk/response {:optional true} map?]]])

(>def :async/channel [:fn {:error/message "Must be an async channel"} chan?])
