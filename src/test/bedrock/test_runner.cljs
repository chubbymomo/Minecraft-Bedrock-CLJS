(ns bedrock.test-runner
  (:require [bedrock.core-test]
            [cljs.test :as t]))

(defmethod t/report [::t/default :end-run-tests] [m]
  (js/process.exit (if (t/successful? m) 0 1)))

(defn main []
  (t/run-tests 'bedrock.core-test))
