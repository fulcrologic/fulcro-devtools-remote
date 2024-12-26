(ns com.fulcrologic.devtools.chrome.content-script
  "Generic content script that should be compiled to js and saved into the shells/chrome/js/content-script/main.js.
   Simply ferries messages between service worker and target(s) on the web page."
  (:require
    [com.fulcrologic.devtools.common.constants :as constants]
    [com.fulcrologic.devtools.common.js-support :refer [js]]
    [com.fulcrologic.devtools.common.js-wrappers :refer [add-runtime-on-message-listener! add-window-event-message-listener!
                                                         has-document-attribute? post-window-message!
                                                         send-message!]]
    [com.fulcrologic.devtools.common.message-keys :as mk]
    [com.fulcrologic.devtools.common.transit :as encode]
    [com.fulcrologic.devtools.common.utils :refer [isoget]]
    [taoensso.encore :as enc]))

(defn send-to-target! [msg]
  (post-window-message! msg)
  nil)

(defn send-to-devtool!
  [msg]
  (send-message! {:target "devtools" :data msg})
  nil)

(defn page-has-devtool-target? []
  (has-document-attribute? constants/chrome-content-script-marker))

(defn listen-to-background-service-worker!
  []
  (add-runtime-on-message-listener!
    (fn [msg]
      (when (= "content_script" (.-target msg))
        (send-to-target! (js {constants/content-script->target-key (.-data msg)})))))
  nil)

(defn listen-to-target! []
  (add-window-event-message-listener!
    (fn [event]
      #?(:cljs
         (when-let [msg (enc/catching (isoget (.-data ^js event) constants/target->content-script-key))]
           (send-to-devtool! msg)))))
  nil)

(defn start! []
  (when (page-has-devtool-target?)
    (listen-to-background-service-worker!)
    (listen-to-target!)
    #?(:cljs
       (js/window.addEventListener "beforeunload"
         (fn []
           (send-to-devtool! (encode/write {mk/connected? false}))))))
  :ready)

(defonce start (start!))
