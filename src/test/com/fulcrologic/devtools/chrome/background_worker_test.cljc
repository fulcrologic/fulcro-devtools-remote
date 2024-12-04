(ns com.fulcrologic.devtools.chrome.background-worker-test
  (:require
    [clojure.string :as str]
    [com.fulcrologic.devtools.chrome.background-worker :as subj]
    [com.fulcrologic.devtools.common.constants :as constants]
    [com.fulcrologic.devtools.common.js-support :refer [js log!]]
    [com.fulcrologic.devtools.common.js-wrappers :refer [post-message! add-on-message-listener! remove-on-message-listener!
                                                         add-runtime-on-connect-listener! add-on-disconnect-listener!]]
    [com.fulcrologic.devtools.common.message-keys :as mk]
    [com.fulcrologic.devtools.common.transit :as encode]
    [com.fulcrologic.guardrails.malli.fulcro-spec-helpers :refer [provided! when-mocking!]]
    [fulcro-spec.core :refer [=> assertions component specification]]
    [fulcro-spec.mocking :refer [calls-of]]))

(def content-tab-id 76)
(def dev-port (js {:name (str constants/devtool-port-name ":" content-tab-id)}))
(def content-port (js {:name   constants/content-script-port-name
                       :sender {:tab {:id content-tab-id}}}))

(defn logged? [msg-fragment]
  (some
    (fn [{:syms [args]}]
      (str/includes? (str (first args))
        msg-fragment))
    (calls-of log!)))

(specification "Handle-devtool-message"
  (let [msg "foo"]
    (provided! "There is no connected content script for the tab"
      (log! & args) => nil
      (subj/content-script-port t) => nil

      (subj/handle-devtool-message content-tab-id msg)

      (assertions
        "Logs that there is no connection"
        (logged? "No port") => true))

    (provided! "There is a connected content script for the tab"
      (subj/content-script-port t) => content-port
      (post-message! p msg) => nil

      (subj/handle-devtool-message content-tab-id msg)

      (assertions
        "Forwards the message to the content script port"
        (calls-of post-message!) => [{'p   content-port
                                      'msg msg}]))))

(specification "Handle-content-script-message"
  (let [msg "foo"]
    (provided! "There is no connected dev tool"
      (log! & args) => nil
      (subj/devtool-port t) => nil

      (subj/handle-content-script-message 1 msg)

      (assertions
        "Logs that there is no connection"
        (logged? "Unable to find dev tool") => true))

    (provided! "There is a connected dev tool for the tab"
      (subj/devtool-port t) => dev-port
      (post-message! p msg) => nil

      (subj/handle-content-script-message 1 msg)

      (assertions
        "Forwards the message to the dev port"
        (calls-of post-message!) => [{'p   dev-port
                                      'msg msg}]))))

(specification "on-runtime-connect"
  (component "content port connect"
    (when-mocking!
      (subj/set-icon-and-popup t) => nil
      (subj/remember-content-script-port! t p) => nil
      (add-on-message-listener! port-listened-to generated-listener) => nil
      (add-on-disconnect-listener! p disconnect-listener) => nil
      (subj/on-content-script-disconnect tid l p) => nil

      (subj/on-runtime-connect content-port)

      (let [{:syms [port-listened-to generated-listener]} (first (calls-of add-on-message-listener!))
            {:syms [disconnect-listener]} (first (calls-of add-on-disconnect-listener!))]
        (assertions
          "Turns on the icon and popup in Chrome"
          (calls-of subj/set-icon-and-popup) => [{'t content-tab-id}]
          "Saves the content script port for that tab id"
          (calls-of subj/remember-content-script-port!) => [{'t content-tab-id
                                                             'p content-port}]
          "Adds a message listener to the port"
          port-listened-to => content-port)

        (when disconnect-listener (disconnect-listener content-port))

        (let [{:syms [tid l p]} (first (calls-of subj/on-content-script-disconnect))]
          (assertions
            "Sets up disconnections to removes the added listener"
            l => generated-listener)))))

  (component "dev port connect"
    (when-mocking!
      (add-on-message-listener! port-listened-to generated-listener) => nil
      (add-on-disconnect-listener! p disconnect-listener) => nil
      (remove-on-message-listener! p l) => nil
      (subj/remember-devtool-port! t p) => nil

      (subj/on-runtime-connect dev-port)

      (let [{:syms [port-listened-to generated-listener]} (first (calls-of add-on-message-listener!))
            {:syms [disconnect-listener]} (first (calls-of add-on-disconnect-listener!))]
        (assertions
          "Remembers the tabid->devtool port"
          (calls-of subj/remember-devtool-port!) => [{'t content-tab-id
                                                      'p dev-port}]
          "Adds a message listener to the port"
          port-listened-to => dev-port)

        (disconnect-listener dev-port)

        (let [{:syms [tid l p]} (first (calls-of remove-on-message-listener!))]
          (assertions
            "Sets up disconnections to remove the added listener"
            l => generated-listener))))))

(specification "Connect/disconnect notifications" :focus
  (subj/clear-connections!)

  (component "Connecting"
    (when-mocking!
      (subj/connection-status-changed! ps tab-id up?) => nil

      (subj/remember-devtool-port! content-tab-id dev-port)

      (assertions
        "Connection of just one side does nothing"
        (calls-of subj/connection-status-changed!) => [])

      (subj/remember-content-script-port! 42 content-port)

      (assertions
        "Connection of a different content tab does nothing"
        (calls-of subj/connection-status-changed!) => [])

      (subj/remember-content-script-port! content-tab-id content-port)

      (let [{:syms [ps up?]} (first (calls-of subj/connection-status-changed!))
            ports-notified (set ps)]
        (assertions
          "notifies both when the connection is established"
          (count (calls-of subj/connection-status-changed!)) => 1
          up? => true
          ports-notified => #{content-port dev-port}))))

  (component "Disconnecting one side"
    (subj/clear-connections!)
    (when-mocking!
      (subj/connection-status-changed! ps tab-id up?) => nil

      (subj/remember-devtool-port! content-tab-id dev-port)
      (subj/remember-content-script-port! content-tab-id content-port))

    (when-mocking!
      (subj/connection-status-changed! ps tab-id up?) => nil

      (subj/forget-devtool-port! content-tab-id)

      (let [{:syms [ps up?]} (first (calls-of subj/connection-status-changed!))
            ports-notified (set ps)]
        (assertions
          "Sends a disconnect to the remaining port"
          up? => false
          (count (calls-of subj/connection-status-changed!)) => 1
          ports-notified => #{content-port}))))

  (component "Disconnecting the other side"
    (subj/clear-connections!)
    (when-mocking!
      (subj/connection-status-changed! ps tab-id up?) => nil

      (subj/remember-devtool-port! content-tab-id dev-port)
      (subj/remember-content-script-port! content-tab-id content-port))

    (when-mocking!
      (subj/connection-status-changed! ps tab-id up?) => nil

      (subj/forget-content-script-port! content-tab-id)

      (let [{:syms [ps up?]} (first (calls-of subj/connection-status-changed!))
            ports-notified (set ps)]
        (assertions
          "Sends a disconnect to the remaining port"
          up? => false
          (count (calls-of subj/connection-status-changed!)) => 1
          ports-notified => #{dev-port})))))

(specification "connection-status-changed!"
  (let [status-sent (rand-nth [true false])]
    (when-mocking!
      (post-message! p msg) => nil

      (subj/connection-status-changed! [dev-port] content-tab-id status-sent)

      (let [{:syms [p msg]} (first (calls-of post-message!))
            decoded-msg (encode/read msg)]
        (assertions
          "Posts to the port(s)"
          p => dev-port

          "The message is a transit encoded message with the tab ID and up-status"
          decoded-msg => {mk/tab-id     content-tab-id
                          mk/connected? status-sent})))))
