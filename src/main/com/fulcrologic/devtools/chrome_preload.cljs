(ns com.fulcrologic.devtools.chrome-preload
  (:require
    [cljs.core.async :as async]
    [com.fulcrologic.devtools.chrome.target :as chrome]
    [com.fulcrologic.devtools.message-keys :as mk]
    [com.fulcrologic.devtools.target :as target]
    [taoensso.timbre :as log]))

(deftype ChromeConnection [my-target-uuid active-requests]
  target/DevToolConnection
  (transmit! [this EQL]
    ;; TASK: Implement capturing the return value
    (let [id               (random-uuid)
          response-channel (async/chan)]
      (vswap! active-requests id response-channel)
      (chrome/push! my-target-uuid {mk/eql        EQL
                                    mk/request-id id})
      (async/go
        (let [timeout (async/timeout 10000)
              [r channel] (async/alts! [response-channel timeout] :priority true)]
          (if (= channel timeout)
            (do
              (log/error "Request to devtool timed out" EQL)
              {mk/error "Request timed out"})
            r))))))

(deftype ChromeDevtoolConnectionFactory []
  target/DevToolConnectionFactory
  (-connect! [this target-description async-pathom-processor]
    ;; TASK: It might be nice to relocate the incoming processing to the type above, so it is more clear
    (let [target-id (chrome/target-started! target-description async-pathom-processor)]
      (->ChromeConnection target-id (volatile! {})))))

(target/set-factory! (->ChromeDevtoolConnectionFactory))
(chrome/install!)
