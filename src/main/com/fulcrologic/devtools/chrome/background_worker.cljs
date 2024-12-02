(ns com.fulcrologic.devtools.chrome.background-worker
  "A middleman facilitating communication between the content script
   injected into the page with your target app(s)
   and your Chrome dev tool panel."
  (:require [com.fulcrologic.devtools.constants :as constants]
            [com.fulcrologic.devtools.utils :refer [isoget isoget-in]]))

(defonce tab-id->content-script-connection (atom {}))
(defonce tab-id->devtool-connection (atom {}))

(defn broadcast [msg] (js/chrome.tabs.sendMessage msg))

(defn handle-devtool-message
  "Handle a message from the DevTools pane"
  [^js devtool-port message]
  (if-let [tab-id (isoget message "tab-id")]
    (do
      (js/console.log "Devtool message received by background script:" message)
      (swap! tab-id->devtool-connection assoc tab-id devtool-port)
      (if-let [^js target-port (get @tab-id->content-script-connection tab-id)]
        (do
          (js/console.log "Forwarding message to content script")
          (.postMessage target-port message))
        (js/console.error "No port to forward incoming message from devtool for tab" tab-id)))
    (js/console.error "Message received with NO TAB ID from dev tool!")))

(defn set-icon-and-popup [tab-id]
  (js/chrome.action.setIcon
    ;; Replace the 'inactive fulcro' w/ 'active fulcro' icon
    #js {:tabId tab-id
         :path  #js {"16"  "/icon-16.png"
                     "32"  "/icon-32.png"
                     "48"  "/icon-48.png"
                     "128" "/icon-128.png"}})
  (js/chrome.action.setPopup
    #js {:tabId tab-id
         :popup "popups/enabled.html"}))

(defn handle-content-script-message
  "Handle a message from the content script"
  [tab-id message]
  (js/console.log "Content script sending message" message "targeted to" tab-id "devtool")
  (if-let [^js target-port (get @tab-id->devtool-connection tab-id)]
    (.postMessage target-port message)
    (js/console.error "Unable to find dev tool for tab" tab-id)))

(defn add-listener []
  (js/chrome.runtime.onConnect.addListener
    (fn [^js port]
      (js/console.log "Service worker detected connection" port)
      (condp = (isoget port "name")
        constants/content-script-port-name
        (do
          (let [tab-id   (isoget-in port ["sender" "tab" "id"])
                listener (partial handle-content-script-message tab-id)]
            (js/console.log "Connection from content script for tab id" tab-id)
            (set-icon-and-popup tab-id)
            (swap! tab-id->content-script-connection assoc tab-id port)

            (.addListener (.-onMessage port) listener)
            (.addListener (.-onDisconnect port)
              (fn [^js port]
                (.removeListener (.-onMessage port) listener)
                (swap! tab-id->content-script-connection dissoc tab-id)))))

        constants/devtool-port-name
        (let [listener (partial handle-devtool-message port)]
          (js/console.log "Devtool connected")
          (.addListener (.-onMessage port) listener)
          (.addListener (.-onDisconnect port)
            (fn [^js port]
              (.removeListener (.-onMessage port) listener)
              (when-let [port-key (->> @tab-id->devtool-connection
                                    (keep (fn [[k v]] (when (= v port) k)))
                                    (first))]
                (swap! tab-id->devtool-connection dissoc port-key)))))

        (js/console.log "Ignoring connection" (isoget port "name"))))))

(defn init []
  (add-listener)
  (js/console.log "Fulcro service worker init done"))

