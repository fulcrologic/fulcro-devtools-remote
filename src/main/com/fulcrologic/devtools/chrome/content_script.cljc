(ns com.fulcrologic.devtools.chrome.content-script
  "Generic content script that should be compiled to js and saved into the shells/chrome/js/content-script/main.js.
   Simply ferries messages between service worker and target(s) on the web page."
  (:require
    [com.fulcrologic.devtools.common.constants :as constants]
    [com.fulcrologic.devtools.common.js-support :refer [js log!]]
    [com.fulcrologic.devtools.common.js-wrappers :refer [add-on-message-listener! add-window-event-message-listener! has-document-attribute?
                                                  post-message! post-window-message! runtime-connect!]]
    [com.fulcrologic.devtools.common.schemas :refer [js-map]]
    [com.fulcrologic.devtools.common.utils :refer [isoget]]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]] ))

(>defn send-to-target! [msg]
  [(js-map [constants/content-script->target-key :string]) => :nil]
  (post-window-message! msg)
  nil)

(>defn send-to-background!
  [port msg]
  [:chrome/service-worker-port :transit/encoded-string => :nil]
  (post-message! port msg)
  nil)

(>defn page-has-devtool-target? []
  [=> :boolean]
  (has-document-attribute? constants/chrome-content-script-marker))

(>defn connect-to-background-service-worker! []
  [=> :chrome/service-worker-port]
  (runtime-connect! constants/content-script-port-name))

(>defn listen-to-background-service-worker!
  [background-script-port]
  [:chrome/service-worker-port => :nil]
  (add-on-message-listener! background-script-port
    (fn [msg]
      (send-to-target! (js {constants/content-script->target-key msg}))))
  nil)

(>defn listen-to-target! [service-worker-port]
  [:chrome/service-worker-port => :nil]
  (add-window-event-message-listener!
    (fn [event]
      (when-let [msg (isoget (.-data event) constants/target->content-script-key)]
        (send-to-background! service-worker-port msg))))
  nil)

(defn start! []
  (when (page-has-devtool-target?)
    (let [service-worker-port (connect-to-background-service-worker!)]
      (listen-to-background-service-worker! service-worker-port)
      (listen-to-target! service-worker-port)))
  :ready)

(defonce start (start!))
