(ns jboss-as.management-test
  (:use clojure.test
        jboss-as.management))

(if (nil? (System/getenv "JBOSS_HOME"))
  (throw (Exception. "Tests require JBOSS_HOME to be set")))

(deftest standalone-with-offset
  (let [srv (create-server
              :jboss-home (System/getenv "JBOSS_HOME")
              :offset 29)]
    (try
      (start srv)
      (wait-for-ready? srv)
      (is (= (+ 29 8080) (port srv :http)))
      (is (= (+ 29 8081) (port srv 8081)))
      (finally
        (stop srv)))))

(deftest domain-with-offset
  (let [srv (create-server
              :domain true
              :jboss-home (System/getenv "JBOSS_HOME")
              :offset 42)]
    (try
      (start srv)
      (wait-for-ready? srv)
      (is (= (+ 42 8080) (port srv :http "server-one")))
      (is (= (+ 42 8080) (port srv 8080 "server-one")))
      (is (= (+ 150 8080) (port srv :http "server-two")))
      (finally
        (stop srv)))))
