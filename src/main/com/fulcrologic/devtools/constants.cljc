(ns com.fulcrologic.devtools.constants)

(def chrome-content-script-marker
  "Marker set on chrome document to indicate dev tooling is present to web pages that support a devtools chrome extension."
  "__fulcrologic-devtool-comms-installed__")

(def devtool-js-message-event-name
  "JS Event posted to the target web page that all targets listen for in order to consume incoming devtool messages."
  "fulcrologic-devtool-message")

(def devtool-js-start-consuming-event-name
  "JS Event name posted to the target web page that requests the tooling to start"
  "fulcrologic-devtool-start-consume")

(def target->content-script-key
  "JS Event object key that is used when sending events to the window for the content script."
  "fulcrologic-target-to-content-script")

(def content-script->target-key
  "JS Event object key that is used when sending events from the content script back to the target."
  "fulcrologic-target-from-content-script")

(def content-script-port-name
  "The name used for the port that can communicate between the content script and the background script"
  "fulcrologic-content-script-port")

(def devtool-port-name
  "The name used for the port that can communicate between the devtool and the background script"
  "fulcrologic-content-script-port")

(def devtool->background-script-key
  "JS Event object key that is used when sending events to the window for the content script."
  "fulcrologic-devtool-to-background-script")

