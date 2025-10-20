(ns com.fulcrologic.devtools.chrome.background-worker
  "Stateless message forwarding between devtool pane and content script")

(js/chrome.runtime.onMessage.addListener
  (fn [^js msg ^js sender _]
    (let [target (.-target msg)
          tab-id (or (.-tabId msg) (.. sender -tab -id))]
      (cond
        (= target "devtools")
        (do
          (set! (.-tabId msg) tab-id)
          (.catch (js/chrome.runtime.sendMessage msg) identity))

        (and (= target "content_script") tab-id)
        (.catch (js/chrome.tabs.sendMessage tab-id msg) identity)))))
