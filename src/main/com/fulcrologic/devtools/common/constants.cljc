(ns com.fulcrologic.devtools.common.constants)

(def chrome-content-script-marker
  "Marker set on chrome document to indicate dev tooling is present to web pages that support a devtools chrome extension."
  "__fulcrologic-devtool-comms-installed__")

(def target->content-script-key
  "JS Event object key that is used when sending events to the window for the content script."
  "fulcrologic-target-to-content-script")

(def content-script->target-key
  "JS Event object key that is used when sending events from the content script back to the target."
  "fulcrologic-content-script-to-target")

(def content-script-port-name
  "The name used for the port that can communicate between the content script and the background script"
  "fulcrologic-content-script-port")

(def devtool-port-name
  "The name used for the port that can communicate between the devtool and the background script"
  "fulcrologic-devtool-port")
