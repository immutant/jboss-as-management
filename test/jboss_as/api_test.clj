(ns jboss-as.api-test
  (:use clojure.test
        jboss-as.api))

(deftest property-expansion
  (are [expect actual] (= expect actual)
       13   (expand 13 nil)
       "13" (expand "13" nil)
       "13" (expand {:EXPRESSION_VALUE "${foo:13}"} nil)
       "13" (expand {:EXPRESSION_VALUE "${foo:42}"} {:foo "13"})))
