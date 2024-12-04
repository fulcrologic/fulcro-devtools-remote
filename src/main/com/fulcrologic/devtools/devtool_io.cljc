(ns com.fulcrologic.devtools.devtool-io
  (:require
    [com.fulcrologic.devtools.common.message-keys :as mk]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [edn-query-language.core :as eql]))

(defn transact! [app-ish target-id txn]
  (let [ast (eql/query->ast txn)
        ast (update ast :children
              (fn [cs]
                (mapv
                  (fn [c]
                    (update c :params assoc mk/target-id target-id))
                  cs)))
        txn (eql/ast->query ast)]
    (comp/transact! app-ish txn)))

(defn load!
  ([app-ish target-id root-key component]
   (load! app-ish target-id root-key component {}))
  ([app-ish target-id root-key component options]
   (let [options (assoc options
                   mk/target-id target-id
                   :remote :devtool-remote)]
     (df/load! app-ish root-key component options))))
