(defproject jboss-as-management "0.1.3-SNAPSHOT"
  :description "A thin wrapper around the JBoss AS7 management API."
  :url "https://github.com/immutant/jboss-as-management"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/data.json "0.1.1"]
                 [clj-http "0.2.7"]]
  :lein-release {:deploy-via :clojars})
