(ns com.fulcrologic.devtools.chrome.background-worker
  "A middleman facilitating communication between the content script
   injected into the page with your target app(s)
   and your Chrome dev tool panel."
  (:require [cljs.core.async :as async :refer [<! >! chan go go-loop put!]]
            [com.fulcrologic.devtools.constants :as constants]
            [com.fulcrologic.devtools.utils :refer [isoget isoget-in]]))

(defonce content-script-connections (atom {}))
(defonce devtool-pane-connections (atom {}))

(defn handle-devtool-message
  "Handle a message from the DevTools pane"
  [devtool-port message _port]
  (cond
    (= "init" (isoget message "name"))
    (let [tab-id (isoget message "tab-id")]
      (js/console.log "Devtool connected to background script with tab id" tab-id)
      (swap! devtool-pane-connections assoc tab-id devtool-port))

    (isoget message "fulcro-inspect-devtool-message")
    (let [tab-id      (isoget message "tab-id")
          remote-port (get @content-script-connections tab-id)]
      (when-not remote-port
        (println "WARN: No stored remote port for this sender tab"
          tab-id
          "Known tabs:" (keys @content-script-connections))) ; FIXME rm
      (some-> remote-port (.postMessage message))))
  (js/Promise.resolve))

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

(defn handle-remote-message
  "Handle a message from the content script"
  [ch message port]
  (js/console.log "Backgroud worker received message" message)
  (cond
    ; Forward message to devtool
    (isoget message "fulcro-inspect-remote-message")
    (let [tab-id (isoget-in port ["sender" "tab" "id"])]
      (put! ch {:tab-id tab-id :message message})

      ; ack message received
      (when-let [id (isoget message "__fulcro-insect-msg-id")]
        (.postMessage port #js {:ack "ok" "__fulcro-insect-msg-id" id})))

    ; set icon and popup
    (isoget message "fulcro-inspect-fulcro-detected")
    (let [tab-id (isoget-in port ["sender" "tab" "id"])]
      (set-icon-and-popup tab-id)))
  (js/Promise.resolve))

(defn add-listener []
  #_(js/chrome.runtime.onMessage.addListener (fn [msg _sender _respond]
                                               (js/console.log "Content script message" msg)
                                               ;; respect api contract, call the callback
                                               (js/Promise.resolve)))
  (js/chrome.runtime.onConnect.addListener
    (fn [^js port]
      (js/console.log "Connected!" (.-name port))
      (condp = (isoget port "name")
        constants/content-script-port-name
        (let [background->devtool-chan (chan (async/sliding-buffer 50000))
              listener                 (partial handle-remote-message background->devtool-chan)
              tab-id                   (isoget-in port ["sender" "tab" "id"])]
          (swap! content-script-connections assoc tab-id port)

          (println "DEBUG onConn listener msg="
            (isoget port "name")
            "for tab-id" tab-id "storing the port...")      ; FIXME rm


          (.addListener (isoget port "onMessage") listener)
          (.addListener (isoget port "onDisconnect")
            (fn [port]
              (.removeListener (isoget port "onMessage") listener)
              (swap! content-script-connections dissoc tab-id)))

          (go-loop []
            (when-let [{:keys [tab-id message] :as data} (<! background->devtool-chan)]
              ; send message to devtool
              (if (contains? @devtool-pane-connections tab-id)
                (do
                  (.postMessage (get @devtool-pane-connections tab-id) message)
                  (recur))
                (recur)))))

        constants/devtool-port-name
        (let [listener (partial handle-devtool-message port)]
          (.addListener (isoget port "onMessage") listener)
          (.addListener (isoget port "onDisconnect")
            (fn [port]
              (.removeListener (isoget port "onMessage") listener)
              (when-let [port-key (->> @devtool-pane-connections
                                    (keep (fn [[k v]] (when (= v port) k)))
                                    (first))]
                (swap! devtool-pane-connections dissoc port-key)))))

        (js/console.log "Ignoring connection" (isoget port "name"))))))

(defn init []
  (add-listener)
  (js/console.log "Fulcro service worker init done"))

