(ns com.fulcrologic.devtools.js-wrappers
  (:require
    [com.fulcrologic.devtools.schemas]
    [taoensso.encore :as enc]
    [com.fulcrologic.devtools.js-support :as jss]
    [com.fulcrologic.guardrails.malli.core :refer [>defn =>]]))

(>defn post-message! [port msg]
  [:chrome/service-worker-port :javascript/object => :nil]
  #?(:cljs (enc/catching (.postMessage port msg)))
  nil)

(>defn runtime-connect! [port-name]
  [:string => :chrome/service-worker-port]
  #?(:clj  (jss/js {:name port-name})
     :cljs (js/chrome.runtime.connect #js {:name port-name})))

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

(>defn add-window-event-message-listener! [listener]
  [fn? => :any]
  #?(:cljs
     (.addEventListener js/window "message" listener false)))

(>defn set-document-attribute! [name value]
  [:string :any => :any]
  #?(:cljs (js/document.documentElement.setAttribute name value)))
