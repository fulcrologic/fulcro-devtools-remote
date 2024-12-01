(ns com.fulcrologic.devtools.chrome.content-script
  "Generic content script that should be compiled to js and saved into the shells/chrome/js/content-script/main.js.
   Simply ferries messages between service worker and target(s) on the web page."
  (:require
    [com.fulcrologic.devtools.constants :as constants]
    [com.fulcrologic.devtools.utils :refer [isoget isoget-in]]))

(defn send-to-target! [msg] (.postMessage js/window msg "*"))
(defn send-to-background! [port msg] (.postMessage port msg))
(defn page-has-devtool-target? [] (js/document.documentElement.getAttribute constants/chrome-content-script-marker))
(defn connect-to-background-service-worker! [] (js/chrome.runtime.connect #js {:name constants/content-script-port-name}))

(defn listen-to-background-service-worker!
  [^js background-script-port]
  (.addListener (isoget background-script-port "onMessage")
    (fn [msg]
      (send-to-target! #js {constants/content-script->target-key msg}))))

(defn listen-to-target! [^js service-worker-port]
  (.addEventListener js/window "message"
    (fn [^js event]
      (when-let [msg (isoget event constants/target->content-script-key)]
        (send-to-background! service-worker-port msg)))))

(defn start! []
  (when (page-has-devtool-target?)
    (let [service-worker-port (connect-to-background-service-worker!)]
      (listen-to-background-service-worker! service-worker-port)
      (listen-to-target! service-worker-port)))
  :ready)

(defonce start (start!))
