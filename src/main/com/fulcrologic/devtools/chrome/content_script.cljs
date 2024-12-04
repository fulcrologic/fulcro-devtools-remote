(ns com.fulcrologic.devtools.chrome.content-script
  "Generic content script that should be compiled to js and saved into the shells/chrome/js/content-script/main.js.
   Simply ferries messages between service worker and target(s) on the web page."
  (:require
    [com.fulcrologic.devtools.constants :as constants]
    [com.fulcrologic.devtools.schemas]
    [com.fulcrologic.devtools.utils :refer [isoget isoget-in]]
    [com.fulcrologic.guardrails.malli.core :refer [=> >def >defn ?]]))

(>defn send-to-target! [msg]
  [[:or
    (iso-map [:describe-targets :any])
    (iso-map [constants/content-script->target-key :any])] => :nil]
  (.postMessage js/window msg "*")
  nil)

(>defn send-to-background!
  [^js port msg]
  [:chrome/service-worker-port :transit/encoded-string => :nil]
  (.postMessage port msg)
  nil)

(>defn page-has-devtool-target? []
  [=> :boolean]
  (boolean (js/document.documentElement.getAttribute constants/chrome-content-script-marker)))

(>defn connect-to-background-service-worker! []
  [=> :chrome/service-worker-port]
  (js/chrome.runtime.connect #js {:name constants/content-script-port-name}))

(>defn listen-to-background-service-worker!
  [^js background-script-port]
  [:chrome/service-worker-port => :nil]
  (.addListener (.-onMessage background-script-port)
    (fn [^js msg]
      ;; msg contains "data" and "tab-id", but only because we put it there
      (js/console.log "Content script received message from service worker" msg)
      (send-to-target! (clj->js {constants/content-script->target-key msg}))))
  nil)

(>defn listen-to-target! [^js service-worker-port]
  [:chrome/service-worker-port => :nil]
  (.addEventListener js/window "message"
    (fn [^js event]
      (js/console.log "Content script saw window event" event)
      (when-let [msg (isoget (.-data event) constants/target->content-script-key)]
        (js/console.log "Content script forwarded message to service worker" msg)
        (send-to-background! service-worker-port msg))))
  (send-to-target! #js {"describe-targets" true})
  nil)

(defn start! []
  (js/console.log "Devtools Content script injected")
  (when (page-has-devtool-target?)
    (js/console.log "There are applications to connect to on the page!")
    (let [service-worker-port (connect-to-background-service-worker!)]
      (listen-to-background-service-worker! service-worker-port)
      (listen-to-target! service-worker-port)))
  :ready)

(defonce start (start!))
