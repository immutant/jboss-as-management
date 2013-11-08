(ns jboss-as.management
  (:require [clojure.java.shell   :as shell]
            [jboss-as.command     :as cmd])
  (:use [jboss-as.api :only (request host-controller server-group port)]))

(defprotocol Server
  "Things you can do to a JBoss server. Names should always have a
   valid suffix, e.g. clj, ima, war"
  (start [_] "Start the server")
  (stop [_] "Stop the server")
  (ready? [_] "Is the server up?")
  (deploy [_ name uri] [_ uri] "Deploy the content at uri")
  (deployed? [_ name] "Currently deployed?")
  (undeploy [_ name] "Undeploy by removing associated content"))

(deftype Standalone [uri, command])
(deftype Domain [uri, command])

(defn create-server
  "Returns an appropriate implementation of Server"
  [& {:keys [domain offset jboss-home base-dir debug]
      :or {offset 0} :as opts}]
  (let [uri (format "http://localhost:%d/management" (+ port offset))]
    (if domain
      (->Domain uri (cmd/domain opts))
      (->Standalone uri (cmd/standalone opts)))))

(def common
  {:start     (fn [this]
                (if (ready? this)
                  (throw (Exception. "JBoss is already running!")))
                (println (.command this))
                (future (apply shell/sh (.split (.command this) " +"))))
   :deployed? (fn [this name]
                (->> (request (.uri this)
                              :operation "read-children-names"
                              :child-type "deployment")
                     :result
                     (some #{name})
                     boolean))})

(def standalone
  {:stop     (fn [this]
               (request (.uri this), :operation "shutdown"))
   :ready?   (fn [this]
               (try (let [response (request (.uri this)
                                            :operation "read-attribute"
                                            :name "server-state")]
                      (and response
                           (= "success" (response :outcome))
                           (= "running" (response :result))))
                    (catch Exception _)))
   :deploy   (fn [this name content-uri]
               (if (deployed? this name) (undeploy this name))
               (request (.uri this),
                        :operation "add"
                        :address ["deployment" name]
                        :content [{:url (.toExternalForm content-uri)}])
               (let [result (request (.uri this),
                                     :operation "deploy"
                                     :address ["deployment" name])]
                 (if (= "success" (:outcome result))
                   result
                   (throw (Exception. (str result))))))
   :undeploy (fn [this name]
               (request (.uri this),
                        :operation "remove"
                        :address ["deployment" name]))})

(def domain
  {:stop     (fn [this]
               (request (.uri this),
                        :operation "shutdown"
                        :address [:host (first (host-controller (.uri this)))]))
   :ready?   (fn [this]
               (try
                 (> (-> (host-controller (.uri this)) last :server count) 1)
                 (catch Exception _)))
   :deploy   (fn [this name content-uri]
               (if (deployed? this name) (undeploy this name))
               (let [uri (.uri this)
                     group (server-group uri)]
                 (request (.uri this),
                          :operation "add"
                          :address ["deployment" name]
                          :content [{:url (.toExternalForm content-uri)}])
                 (request (.uri this),
                          :operation "add"
                          :address [{:server-group group} {:deployment name}]
                          :content [{:url (.toExternalForm content-uri)}])
                 (let [result (request uri,
                                       :operation "deploy"
                                       :address [{:server-group group} {:deployment name}])]
                   (if (= "success" (:outcome result))
                     result
                     (throw (Exception. (str result)))))))
   :undeploy (fn [this name]
               (let [uri (.uri this)
                     group (server-group uri)]
                 (request uri,
                          :operation "remove"
                          :address [{:server-group group} {:deployment name}])
                 (request uri,
                          :operation "remove"
                          :address ["deployment" name])))})

(extend Standalone
  Server
  (merge common standalone))

(extend Domain
  Server
  (merge common domain))

(defn wait-for-ready?
  "Returns true if the server is up. Otherwise, sleeps for one second
   and then retries, effectively blocking the current thread until the
   server becomes ready or 'attempts' number of seconds has elapsed"
  ([server]
     (wait-for-ready? server 30))
  ([server attempts]
     (or (ready? server)
         (when (> attempts 0)
           (Thread/sleep 1000)
           (recur server (dec attempts))))))
