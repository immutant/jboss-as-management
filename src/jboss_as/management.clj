(ns jboss-as.management
  (:refer-clojure :exclude [remove])
  (:require [clojure.data.json :as json]
            [clj-http.client :as client]))

(def ^{:dynamic true} *api-endpoint* "http://localhost:9990/management")

(defn api
  "Params assembled into a hash that is passed to the JBoss management interface as a
   request to *api-endpoint* and returns the JBoss response as a hash"
  [& params]
  (let [body (json/json-str (apply hash-map params))
        response (client/post *api-endpoint* {:body body
                                              :headers {"Content-Type" "application/json"}
                                              :throw-exceptions false})]
    (if-let [body (:body response)]
      (json/read-json body))))

(defn ready?
  "Returns true if JBoss is ready for action"
  []
  (try (let [response (api :operation "read-attribute" :name "server-state")]
         (and response
              (= "success" (response :outcome))
              (= "running" (response :result))))
       (catch Exception _)))

(defn add
  "Adds deployment content to the AS at *api-endpoint*"
  [name content-url]
  (api :operation "add"
       :address ["deployment" name]
       :content [{:url (.toExternalForm content-url)}]))

(defn remove
  "Removes a deployment from the AS at *api-endpoint*"
  [name]
  (api :operation "remove" :address ["deployment" name]))

(defn deploy
  "Tell the AS at *api-endpoint* to deploy the content added under name"
  [name]
  (let [result (api :operation "deploy" :address ["deployment" name])]
    (if (= "success" (:outcome result))
      result
      (throw (Exception. (-> (:failure-description result) first val first val))))))

(defn shutdown
  "Shut down whatever JBoss instance is responding to *api-endpoint*"
  []
  (api :operation "shutdown"))



