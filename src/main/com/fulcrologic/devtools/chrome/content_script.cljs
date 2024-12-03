(ns com.fulcrologic.devtools.chrome.content-script
  "Generic content script that should be compiled to js and saved into the shells/chrome/js/content-script/main.js.
   Simply ferries messages between service worker and target(s) on the web page."
  (:require
    [com.fulcrologic.devtools.constants :as constants]
    [com.fulcrologic.devtools.transit :as encode]
    [com.fulcrologic.devtools.utils :refer [isoget isoget-in]]))

(defn send-to-target! [msg] (.postMessage js/window msg "*"))
(defn send-to-background! [^js port msg] (.postMessage port msg))
(defn page-has-devtool-target? [] (js/document.documentElement.getAttribute constants/chrome-content-script-marker))
(defn connect-to-background-service-worker! [] (js/chrome.runtime.connect #js {:name constants/content-script-port-name}))

(defn listen-to-background-service-worker!
  [^js background-script-port]
  (.addListener (.-onMessage background-script-port)
    (fn [^js msg]
      (js/console.log "Content script received message from service worker" msg)
      (send-to-target! (clj->js {constants/content-script->target-key msg})))))

(defn listen-to-target! [^js service-worker-port]
  (.addEventListener js/window "message"
    (fn [^js event]
      (js/console.log "Content script saw window event" event)
      (when-let [msg (isoget (.-data event) constants/target->content-script-key)]
        (js/console.log "Content script forwarded message to service worker" msg)
        (send-to-background! service-worker-port msg))))
  (send-to-target! #js {"describe-targets" true}))

(defn start! []
  (js/console.log "Devtools Content script injected")
  (when (page-has-devtool-target?)
    (js/console.log "There are applications to connect to on the page!")
    (let [service-worker-port (connect-to-background-service-worker!)]
      (listen-to-background-service-worker! service-worker-port)
      (listen-to-target! service-worker-port)))
  :ready)

(defonce start (start!))
