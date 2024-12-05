(ns com.fulcrologic.devtools.chrome.background-worker-test
  (:require
    [clojure.string :as str]
    [com.fulcrologic.devtools.chrome.background-worker :as subj]
    [com.fulcrologic.devtools.constants :as constants]
    [com.fulcrologic.devtools.js-support :refer [js log!]]
    [com.fulcrologic.guardrails.malli.fulcro-spec-helpers :refer [provided! when-mocking!]]
    [fulcro-spec.core :refer [=> assertions component specification]]
    [fulcro-spec.mocking :refer [calls-of]]))

(def content-tab-id 76)
(def dev-port (js {:name constants/devtool-port-name}))
(def content-port (js {:name   constants/content-script-port-name
                       :sender {:tab {:id content-tab-id}}}))

(defn logged? [msg-fragment]
  (some
    (fn [{:syms [args]}]
      (str/includes? (str (first args))
        msg-fragment))
    (calls-of log!)))

(specification "Handle-devtool-message"
  (let [msg (js {:tab-id 1
                 :data   "foo"})]
    (provided! "There is no connected content script for the tab"
      (log! & args) => nil
      (subj/content-script-port t) => nil

      (subj/handle-devtool-message dev-port msg)

      (assertions
        "Logs that there is no connection"
        (logged? "No port") => true))

    (provided! "There is a connected content script for the tab"
      (log! & args) => nil
      (subj/content-script-port t) => content-port
      (subj/post-message! p msg) => nil
      (subj/remember-devtool-port! t p) => nil

      (subj/handle-devtool-message dev-port msg)

      (assertions
        "Remembers the devtool port"
        (calls-of subj/remember-devtool-port!) => [{'t 1
                                                    'p dev-port}]
        "Forwards the message to the content script port"
        (calls-of subj/post-message!) => [{'p   content-port
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
      (subj/post-message! p msg) => nil

      (subj/handle-content-script-message 1 msg)

      (assertions
        "Forwards the message to the dev port"
        (calls-of subj/post-message!) => [{'p   dev-port
                                           'msg msg}]))))

(specification "on-runtime-connect"
  (component "content port connect"
    (when-mocking!
      (subj/set-icon-and-popup t) => nil
      (subj/remember-content-script-port! t p) => nil
      (subj/add-on-message-listener! port-listened-to generated-listener) => nil
      (subj/add-on-disconnect-listener! p disconnect-listener) => nil
      (subj/on-content-script-disconnect tid l p) => nil

      (subj/on-runtime-connect content-port)

      (let [{:syms [port-listened-to generated-listener]} (first (calls-of subj/add-on-message-listener!))
            {:syms [disconnect-listener]} (first (calls-of subj/add-on-disconnect-listener!))]
        (assertions
          "Turns on the icon and popup in Chrome"
          (calls-of subj/set-icon-and-popup) => [{'t content-tab-id}]
          "Saves the content script port for that tab id"
          (calls-of subj/remember-content-script-port!) => [{'t content-tab-id
                                                             'p content-port}]
          "Adds a message listener to the port"
          port-listened-to => content-port)

        (disconnect-listener content-port)

        (let [{:syms [tid l p]} (first (calls-of subj/on-content-script-disconnect))]
          (assertions
            "Sets up disconnections to removes the added listener"
            l => generated-listener)))))

  (component "dev port connect"
    (when-mocking!
      (subj/add-on-message-listener! port-listened-to generated-listener) => nil
      (subj/add-on-disconnect-listener! p disconnect-listener) => nil
      (subj/remove-on-message-listener! p l) => nil

      (subj/on-runtime-connect dev-port)

      (let [{:syms [port-listened-to generated-listener]} (first (calls-of subj/add-on-message-listener!))
            {:syms [disconnect-listener]} (first (calls-of subj/add-on-disconnect-listener!))]
        (assertions
          "Adds a message listener to the port"
          port-listened-to => dev-port )

        (disconnect-listener dev-port)

        (let [{:syms [tid l p]} (first (calls-of subj/remove-on-message-listener!))]
          (assertions
            "Sets up disconnections to remove the added listener"
            l => generated-listener))))))
