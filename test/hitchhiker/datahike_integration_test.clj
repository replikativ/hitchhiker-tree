(ns hitchhiker.datahike-integration-test
  #_(:require [clojure.test :refer :all]
              [datahike.integration-test :as it]))

(def config {:store {:backend :file :path "/tmp/file-test-1"}})

#_(defn config-record-file-test-fixture [f]
    (it/integration-test-fixture config)
    (f))

#_(use-fixtures :once config-record-file-test-fixture)

#_(deftest ^:integration config-record-file-test []
    (it/integration-test config))
