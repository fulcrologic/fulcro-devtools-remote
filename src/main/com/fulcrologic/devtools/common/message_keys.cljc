(ns com.fulcrologic.devtools.common.message-keys
  "Predefined keys for messages that are used by the devtool support.")


(def tab-id
  "The well-known message key for communicating which devtool tab you want to talk to from the bg script"
  ::tab-id)

(def connected?
  "A key that indicates the connection status changed (true == opened, false == dropped)"
  ::connected?)

(def target-id
  "The well-known message key for target ID"
  ::target-id)

(def target-description
  "The well-known message key for target description"
  ::target-description)

(def request-id
  "The well-known message key for the request ID, which is used for responses to explicit round-trip messages."
  ::request-id)

(def error
  "The well-known message key that indicates there was an error when processing a request."
  ::error)

(def response
  "The well-known message key for the response of a round-trip message."
  ::response)

(def timestamp
  "This key used to encode the timestamp of messages."
  ::timestamp)

(def get-devtools-version
  "The message type is to request the version of these devtools to detect incompatibilities."
  ::get-devtools-version)

(def target-started
  "This message type indicates that a target started."
  ::target-started)

(def request
  "This key stores the actual request data."
  ::request)

(def active-targets
  "This key stores all target applications that the devtool is connected to."
  ::active-targets)

