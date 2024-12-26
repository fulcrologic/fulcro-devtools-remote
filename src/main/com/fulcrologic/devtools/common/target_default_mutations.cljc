(ns com.fulcrologic.devtools.common.target-default-mutations
  (:require
    [com.fulcrologic.devtools.common.built-in-mutations :as bi]
    [com.fulcrologic.devtools.common.message-keys :as mk]
    [com.fulcrologic.devtools.common.protocols :as dp]
    [com.fulcrologic.devtools.common.resolvers :as resolvers]
    [com.fulcrologic.fulcro.application :as app]
    [com.wsscode.pathom.connect :as pc]
    [taoensso.timbre :as log]))

(resolvers/defmutation connected [{:devtool/keys [connection]
                                   :fulcro/keys  [app]} input]
  {::pc/sym `bi/devtool-connected}
  (let [id (::app/id app)
        nm (:devtool/name app)]
    (log/info "Target detected connection" id nm)
    (dp/transmit! connection id [(bi/target-started {mk/target-id          id
                                                     mk/target-description (or nm (str id))})])))
