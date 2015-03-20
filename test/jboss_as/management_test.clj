(ns jboss-as.management-test
  (:require [clojure.test :refer :all]
            [jboss-as.management :refer :all]
            [clojure.string :as str]))

(if (nil? (System/getenv "JBOSS_HOME"))
  (throw (Exception. "Tests require JBOSS_HOME to be set")))

(defn disable-management-security [config]
  (str/replace config
    #"(<http-interface )(security-realm=['\"]ManagementRealm['\"])"
    "$1"))

(defn enable-port-offset [config]
  (when-not (re-find #"port-offset:0" config)
    (str/replace config #"(<server name=\"server-one\" group=\"main-server-group\">)"
      "$1\n<socket-bindings port-offset=\"\\${jboss.socket.binding.port-offset:0}\"/>")))

(deftest standalone-with-offset
  (let [srv (create-server
              :jboss-home (System/getenv "JBOSS_HOME")
              :offset 29)]
    (alter-config! srv disable-management-security)
    (try
      (start srv)
      (is (wait-for-ready? srv))
      (is (= (+ 29 8080) (port srv :http)))
      (is (= (+ 29 8081) (port srv 8081)))
      (finally
        (stop srv)))))

(deftest domain-with-offset
  (let [srv (create-server
              :domain true
              :jboss-home (System/getenv "JBOSS_HOME")
              :offset 42)]
    (alter-config! srv disable-management-security "host.xml")
    ;; domain mode doesn't support offsetting by default
    (alter-config! srv enable-port-offset "host.xml")
    (try
      (start srv)
      (is (wait-for-ready? srv))
      (is (= (+ 42 8080) (port srv :http "server-one")))
      (is (= (+ 42 8080) (port srv 8080 "server-one")))
      (is (= (+ 150 8080) (port srv :http "server-two")))
      (finally
        (stop srv)))))
