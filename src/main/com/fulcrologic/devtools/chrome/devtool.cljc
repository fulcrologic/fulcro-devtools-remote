(ns com.fulcrologic.devtools.chrome.devtool
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.devtools.common.built-in-mutations :as bi]
    [com.fulcrologic.devtools.common.connection :as cc]
    [com.fulcrologic.devtools.common.devtool-default-mutations]
    [com.fulcrologic.devtools.common.fulcro-devtool-remote :as fdr]
    [com.fulcrologic.devtools.common.js-wrappers :refer [add-runtime-on-message-listener! send-message!]]
    [com.fulcrologic.devtools.common.message-keys :as mk]
    [com.fulcrologic.devtools.common.protocols :as dp]
    [com.fulcrologic.devtools.common.resolvers :as resolvers]
    [com.fulcrologic.devtools.common.schemas]
    [com.fulcrologic.devtools.common.transit :as encode]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(def current-tab-id
  "The TAB ID under which your dev tool is running in Chrome Dev Tools"
  #?(:cljs js/chrome.devtools.inspectedWindow.tabId
     :clj  76))

(defonce targets-connected (volatile! #{}))

(>defn service-worker-message-handler [^clj conn msg]
  [::dp/DevToolConnection :string => :any]
  (let [vconfig          (.-vconfig conn)
        {:keys [async-processor active-requests send-ch]} (cc/connection-config conn)
        decoded-message  (enc/catching (encode/read msg))
        request-id       (mk/request-id decoded-message)
        target-id        (mk/target-id decoded-message)
        request          (mk/request decoded-message)
        response-channel (when request-id
                           (get active-requests request-id))]
    (when (and target-id (not (contains? @targets-connected target-id)))
      (vswap! targets-connected conj target-id)
      (vswap! vconfig assoc :connected? true)
      (async-processor [(bi/devtool-connected {:connected? true
                                               :target-id  target-id})]))
    (when (false? (mk/connected? decoded-message))
      (doseq [t @targets-connected]
        (async-processor [(bi/devtool-connected {:connected? false
                                                 :target-id  t})]))
      (vreset! targets-connected #{}))
    (if response-channel
      (async/go
        (vswap! vconfig update :active-requests dissoc request-id)
        (async/>! response-channel (or decoded-message {mk/error "Message empty or decode failed."}))
        (async/close! response-channel))
      (async/go

        (when (and request-id request)
          (enc/try*
            (let [result (async/<! (async-processor request))]
              (if (map? result)
                (async/>! send-ch {mk/request-id request-id
                                   mk/target-id  target-id
                                   mk/response   result})
                (async/>! send-ch {mk/request-id request-id
                                   mk/target-id  target-id
                                   mk/error      (ex-message result)})))
            (catch :any e
              (log/error e "Failed to handle incoming request")
              (async/>! send-ch {mk/error     (ex-message e)
                                 mk/target-id target-id}))))))))

(defn new-chrome-extension-connection
  "Creates a DevtoolConnection from the Chrome dev tool extension itself. In other words, this is used by your devtool.
   in order to construct a devtool-remote."
  [async-processor]
  (let [send-ch (async/chan (async/dropping-buffer 10000))
        conn    (cc/->Connection (volatile! {:connected?      false
                                             :send-ch         send-ch
                                             :async-processor async-processor
                                             :active-requests {}}))]
    (async/go-loop []
      ;; This loop is here in case the web page itself changes. The before unload event from the content script will clear
      ;; the targets, and the new page will start unconnected. We send a connected message once per second until the
      ;; content script acks with some kind of message
      (when (empty? @targets-connected)
        (send-message! {:target "content_script" :tabId current-tab-id :data (encode/write {mk/connected? true})}))
      (async/<! (async/timeout 1000))
      (recur))
    (async/go-loop []
      (let [msg (async/<! send-ch)]
        (send-message! {:target "content_script" :tabId current-tab-id :data (encode/write msg)}))
      (recur))
    (add-runtime-on-message-listener!
      #?(:clj nil
         :cljs
         (fn [^js msg]
           (do
             (when (and (= (.-target msg) "devtools") (= current-tab-id (.-tabId msg)))
               (service-worker-message-handler conn (.-data ^js msg)))))))
    conn))

(defn add-devtool-remote! [app]
  (let [c    (atom nil)
        conn (new-chrome-extension-connection
               (fn [msg] (resolvers/process-async-request {:devtool/connection @c
                                                           :fulcro/app         app} msg)))]
    (reset! c conn)
    (app/set-remote! app :devtool-remote (fdr/devtool-remote conn))
    app))
