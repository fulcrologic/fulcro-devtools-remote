(ns common.tool-impl
  (:require
    [com.fulcrologic.devtools.common.message-keys :as mk]
    [com.fulcrologic.devtools.common.resolvers :as res]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.wsscode.pathom.connect :as pc]))

(res/defmutation counter-added [{:fulcro/keys [app]} {id ::mk/target-id}]
  {::pc/sym `common.tool-api/counter-added}
  (df/load! app :counter/stats nil {:params {mk/target-id id}
                                    :remote :devtool-remote
                                    :target [:tool/id id :tool/counter-stats]})
  nil)

