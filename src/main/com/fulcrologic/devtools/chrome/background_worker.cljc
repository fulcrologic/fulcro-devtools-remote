(ns com.fulcrologic.devtools.chrome.background-worker
  "A middleman facilitating communication between the content script
   injected into the page with your target app(s)
   and your Chrome dev tool panel."
  (:require
    [com.fulcrologic.devtools.constants :as constants]
    [com.fulcrologic.devtools.js-support :refer [js log!]]
    [com.fulcrologic.devtools.message-keys :as mk]
    [com.fulcrologic.devtools.schemas :refer [js-map]]
    [com.fulcrologic.devtools.transit :as encode]
    [com.fulcrologic.devtools.utils :refer [isoget isoget-in]]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]))

(defonce tab-id->content-script-connection (atom {}))
(defonce tab-id->devtool-connection (atom {}))
(defonce tab-id->targets (atom {}))

(>defn chrome-set-icon!
  [icon-descriptions]
  [:javascript/object => :nil]
  #?(:cljs (js/chrome.action.setIcon icon-descriptions))
  nil)

(>defn chrome-set-popup! [popup-description]
  [:javascript/object => :nil]
  #?(:cljs (js/chrome.action.setPopup popup-description))
  nil)

(>defn post-message! [port msg]
  [:chrome/service-worker-port :javascript/object => :nil]
  #?(:cljs (.postMessage ^js port msg))
  nil)

(>defn handle-devtool-message
  "Handle a message from the DevTools pane"
  [^js devtool-port ^js message]
  [:chrome/service-worker-port (js-map
                                 [:tab-id :int]) => :nil]
  (let [tab-id (isoget message "tab-id")]
    (do
      (log! "Devtool message received by background script:" message)
      (swap! tab-id->devtool-connection assoc tab-id devtool-port)
      (if-let [target-port (get @tab-id->content-script-connection tab-id)]
        (do
          (log! "Forwarding message to content script")
          (post-message! target-port message))
        (log! "No port to forward incoming message from devtool for tab" tab-id))))
  nil)

(defn set-icon-and-popup [tab-id]
  (chrome-set-icon!
    (js {:tabId tab-id
         :path  {"16"  "/icon-16.png"
                 "32"  "/icon-32.png"
                 "48"  "/icon-48.png"
                 "128" "/icon-128.png"}}))
  (chrome-set-popup!
    (js {:tabId tab-id
         :popup "popups/enabled.html"})))

(>defn handle-content-script-message
  "Handle a message from the content script"
  [tab-id message]
  [:int :transit/encoded-string => :nil]
  ;; Message through the port is NOT wrapped in extra js crap
  (log! "Content script sending message" message "targeted to" tab-id "devtool")
  (let [target-port (get @tab-id->devtool-connection tab-id)
        decoded-msg (encode/read message)]
    (let [target-descriptors (mk/active-targets decoded-msg)]
      (when target-descriptors
        (log! "Service worker tracking targets" target-descriptors)
        (swap! tab-id->targets assoc tab-id target-descriptors)))

    (if target-port
      (post-message! target-port (encode/write
                                   (assoc decoded-msg mk/active-targets (get @tab-id->targets tab-id))))
      (log! "Unable to find dev tool for tab" tab-id)))
  nil)

(defn add-listener []
  (js/chrome.runtime.onConnect.addListener
    (fn [^js port]
      (log! "Service worker detected connection" port)
      (condp = (isoget port "name")
        constants/content-script-port-name
        (do
          (let [tab-id   (isoget-in port ["sender" "tab" "id"])
                listener (partial handle-content-script-message tab-id)]
            (log! "Connection from content script for tab id" tab-id)
            (set-icon-and-popup tab-id)
            (swap! tab-id->content-script-connection assoc tab-id port)

            (.addListener (.-onMessage port) listener)
            (.addListener (.-onDisconnect port)
              (fn [^js port]
                (.removeListener (.-onMessage port) listener)
                (swap! tab-id->targets dissoc tab-id)
                (swap! tab-id->content-script-connection dissoc tab-id)))))

        constants/devtool-port-name
        (let [listener (partial handle-devtool-message port)]
          (log! "Devtool connected")
          (.addListener (.-onMessage port) listener)
          (.addListener (.-onDisconnect port)
            (fn [^js port]
              (.removeListener (.-onMessage port) listener)
              (when-let [port-key (->> @tab-id->devtool-connection
                                    (keep (fn [[k v]] (when (= v port) k)))
                                    (first))]
                (swap! tab-id->devtool-connection dissoc port-key)))))

        (log! "Ignoring connection" (isoget port "name"))))))

(defn init []
  (add-listener)
  (log! "Fulcro service worker init done"))

