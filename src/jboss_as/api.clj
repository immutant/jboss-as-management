(ns jboss-as.api
  (:require [clojure.data.json    :as json]
            [clj-http.lite.client :as client]))

;;; We assume the default management-http port
(def port 9990)

(defn request
  "Params assembled into a hash that is passed to the JBoss management
   interface via the passed URI and returns the JBoss response as a
   clojure map"
  [uri & params]
  (let [body (json/write-str (apply hash-map params))
        response (client/post uri {:body body
                                   :headers {"Content-Type" "application/json"}
                                   :throw-exceptions false})]
    (if-let [body (:body response)]
      (json/read-str body :key-fn keyword))))

(defn host-controller [uri]
  (-> (request uri,
               :operation "read-children-resources",
               :child-type "host")
      :result
      first))

(defn server-group [uri]
  (-> (request uri,
               :operation "read-children-names"
               :child-type "server-group")
      :result
      first))

