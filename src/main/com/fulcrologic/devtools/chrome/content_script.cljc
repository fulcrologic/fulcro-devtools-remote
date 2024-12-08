(ns com.fulcrologic.devtools.chrome.content-script
  "Generic content script that should be compiled to js and saved into the shells/chrome/js/content-script/main.js.
   Simply ferries messages between service worker and target(s) on the web page."
  (:require
    [com.fulcrologic.devtools.constants :as constants]
    [com.fulcrologic.devtools.js-support :refer [js log!]]
    [com.fulcrologic.devtools.schemas :refer [iso-map js-map]]
    [com.fulcrologic.devtools.utils :refer [isoget isoget-in]]
    [com.fulcrologic.guardrails.malli.core :refer [=> >def >defn ?]]))

(>defn send-to-target! [msg]
  [[:or
    (iso-map [:describe-targets :any])
    (iso-map [constants/content-script->target-key :any])] => :nil]
  #?(:cljs
     (.postMessage js/window msg "*"))
  nil)

(>defn send-to-background!
  [port msg]
  [:chrome/service-worker-port :transit/encoded-string => :nil]
  #?(:cljs
     (.postMessage ^js port msg))
  nil)

(>defn page-has-devtool-target? []
  [=> :boolean]
  #?(:cljs
     (boolean (js/document.documentElement.getAttribute constants/chrome-content-script-marker)))
  false)

(>defn connect-to-background-service-worker! []
  [=> :chrome/service-worker-port]
  #?(:cljs
     (js/chrome.runtime.connect #js {:name constants/content-script-port-name})))

(>defn listen-to-background-service-worker!
  [background-script-port]
  [:chrome/service-worker-port => :nil]
  #?(:cljs
     (.addListener (.-onMessage background-script-port)
       (fn [^js msg]
         ;; msg contains "data" and "tab-id", but only because we put it there
         (log! "Content script received message from service worker" msg)
         (send-to-target! (clj->js {constants/content-script->target-key msg})))))
  nil)

(>defn listen-to-target! [service-worker-port]
  [:chrome/service-worker-port => :nil]
  #?(:cljs
     (.addEventListener js/window "message"
       (fn [^js event]
         (log! "Content script saw window event" event)
         (when-let [msg (isoget (.-data event) constants/target->content-script-key)]
           (log! "Content script forwarded message to service worker" msg)
           (send-to-background! service-worker-port msg)))))
  (send-to-target! (js {"describe-targets" true}))
  nil)

(defn start! []
  (log! "Devtools Content script injected")
  (when (page-has-devtool-target?)
    (log! "There are applications to connect to on the page!")
    (let [service-worker-port (connect-to-background-service-worker!)]
      (listen-to-background-service-worker! service-worker-port)
      (listen-to-target! service-worker-port)))
  :ready)

(defonce start (start!))
