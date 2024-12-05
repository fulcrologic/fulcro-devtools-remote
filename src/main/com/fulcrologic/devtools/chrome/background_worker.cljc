(ns com.fulcrologic.devtools.chrome.background-worker
  "A middleman facilitating communication between the content script
   injected into the page with your target app(s)
   and your Chrome dev tool panel."
  (:require
    [clojure.set :as set]
    [com.fulcrologic.devtools.constants :as constants]
    [com.fulcrologic.devtools.js-support :refer [js log!]]
    [com.fulcrologic.devtools.message-keys :as mk]
    [com.fulcrologic.devtools.transit :as encode]
    [com.fulcrologic.devtools.utils :refer [isoget isoget-in]]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn ?]]
    [taoensso.encore :as enc :refer [remove-vals]]))

(declare post-message!)

(defonce tab-id->content-script-connection (atom {}))
(defonce tab-id->devtool-connection (atom {}))

(defn clear-connections! []
  (reset! tab-id->content-script-connection {})
  (reset! tab-id->devtool-connection {}))

(>defn connection-status-changed! [ports tab-id connected?]
  [[:vector [:or :nil :chrome/service-worker-port]] :int :boolean => :nil]
  (doseq [p ports
          :when p]
    (post-message! p (encode/write {mk/tab-id     tab-id
                                    mk/connected? connected?})))
  nil)

(>defn notify-connection-status!
  [_ ref old-map new-map]
  [:keyword :clojure/atom [:map-of :int :chrome/service-worker-port] [:map-of :int :chrome/service-worker-port] => :nil]
  (let [key-added          (first (set/difference (set (keys new-map)) (set (keys old-map))))
        key-removed        (first (set/difference (set (keys old-map)) (set (keys new-map))))
        other              (if (= ref tab-id->content-script-connection)
                             tab-id->devtool-connection
                             tab-id->content-script-connection)
        tab-id             (or key-added key-removed)
        just-connected?    (boolean key-added)
        just-disconnected? (boolean key-removed)
        this-port          (get new-map tab-id)
        other-port         (get @other tab-id)
        other-connected?   (some? other-port)]
    (cond
      (and other-connected? just-connected?) (connection-status-changed! [this-port other-port] tab-id true)
      (and other-connected? just-disconnected?) (connection-status-changed! [other-port] tab-id false))
    nil))

(do
  (add-watch tab-id->devtool-connection ::notify notify-connection-status!)
  (add-watch tab-id->content-script-connection ::notify notify-connection-status!)
  :ok)

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
  #?(:cljs (enc/catching (.postMessage port msg)))
  nil)

(>defn remember-content-script-port! [tab-id port]
  [:int :chrome/service-worker-port => :nil]
  (swap! tab-id->content-script-connection assoc tab-id port)
  nil)

(>defn forget-content-script-port! [tab-id]
  [:int => :nil]
  (swap! tab-id->content-script-connection dissoc tab-id)
  nil)

(>defn content-script-port [tab-id]
  [:int => (? :chrome/service-worker-port)]
  (get @tab-id->content-script-connection tab-id))

(>defn remember-devtool-port! [tab-id port]
  [:int :chrome/service-worker-port => :nil]
  (swap! tab-id->devtool-connection assoc tab-id port)
  nil)

(>defn forget-devtool-port! [tab-id]
  [:int => :nil]
  (swap! tab-id->devtool-connection dissoc tab-id)
  nil)

(>defn devtool-port [tab-id]
  [:int => (? :chrome/service-worker-port)]
  (get @tab-id->devtool-connection tab-id))

(>defn handle-devtool-message
  "Handle a message from the DevTools pane"
  [devtool-port message]
  [:chrome/service-worker-port :chrome/service-worker-message => :nil]
  (let [tab-id (isoget message "tab-id")]
    (do
      (log! "Devtool message received by background script:" message)
      (remember-devtool-port! tab-id devtool-port)
      (if-let [target-port (content-script-port tab-id)]
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
  (let [target-port (devtool-port tab-id)]
    (if target-port
      (post-message! target-port message)
      (log! "Unable to find dev tool for tab" tab-id)))
  nil)

(>defn add-runtime-on-connect-listener! [listener]
  [fn? => :any]
  #?(:cljs (js/chrome.runtime.onConnect.addListener listener)))
(>defn add-on-disconnect-listener! [port listener]
  [:chrome/service-worker-port fn? => :any]
  #?(:cljs (.addListener (.onDisconnect ^js port) listener)))
(>defn add-on-message-listener! [port listener]
  [:chrome/service-worker-port fn? => :any]
  #?(:cljs (.addListener (.onMessage ^js port) listener)))

(>defn remove-on-message-listener! [port listener]
  [:chrome/service-worker-port fn? => :any]
  #?(:cljs (.removeListener (.onMessage ^js port) listener)))

(defn on-content-script-disconnect [tab-id listener port]
  (remove-on-message-listener! port listener)
  (swap! tab-id->content-script-connection dissoc tab-id))

(defn on-runtime-connect [port]
  (log! "Service worker detected connection" port)
  (condp = (isoget port "name")
    constants/content-script-port-name
    (do
      (let [tab-id   (isoget-in port ["sender" "tab" "id"])
            listener (partial handle-content-script-message tab-id)]
        (log! "Connection from content script for tab id" tab-id)
        (set-icon-and-popup tab-id)
        (remember-content-script-port! tab-id port)

        (add-on-message-listener! port listener)
        (add-on-disconnect-listener! port (partial on-content-script-disconnect tab-id listener))))

    constants/devtool-port-name
    (let [listener (partial handle-devtool-message port)]
      (log! "Devtool connected")
      (add-on-message-listener! port listener)
      (add-on-disconnect-listener! port
        (fn [port]
          (remove-on-message-listener! port listener)
          (swap! tab-id->devtool-connection (fn [m] (remove-vals #(= % port) m))))))

    (log! "Ignoring connection" (isoget port "name"))))

(defn add-listener []
  (add-runtime-on-connect-listener! on-runtime-connect))

(defn init []
  (add-listener)
  (log! "Fulcro service worker init done"))

