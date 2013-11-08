(ns jboss-as.management
  (:require [clojure.java.shell   :as shell]
            [clojure.data.json    :as json]
            [clj-http.lite.client :as client]
            [jboss-as.command     :as cmd])
  (:use [jboss-as.api :only (request host-controller server-group port)]))

(defprotocol Server
  "Things you can do to a JBoss server"
  (start [_] "Start the server")
  (stop [_] "Stop the server")
  (ready? [_] "Is the server up?")
  (add [_ name content-uri] "Identify deployment content")
  (deploy [_ name] "Deploy the added content")
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

(extend-type Standalone
  Server
  (start [this]
    (if (ready? this)
      (throw (Exception. "JBoss is already running!")))
    (println (.command this))
    (future (apply shell/sh (.split (.command this) " +"))))
  (stop [this]
    (request (.uri this), :operation "shutdown"))
  (ready? [this]
    (try (let [response (request (.uri this), :operation "read-attribute" :name "server-state")]
           (and response
                (= "success" (response :outcome))
                (= "running" (response :result))))
         (catch Exception _)))
  (add [this name content-uri]
    (request (.uri this),
             :operation "add"
             :address ["deployment" name]
             :content [{:url (.toExternalForm content-uri)}]))
  (deploy [this name]
    (let [result (request (.uri this), :operation "deploy" :address ["deployment" name])]
      (if (= "success" (:outcome result))
        result
        (throw (Exception. (-> (:failure-description result) first val first val))))))
  (undeploy [this name]
    (request (.uri this), :operation "remove" :address ["deployment" name])))

(extend-type Domain
  Server
  (start [this]
    (if (ready? this)
      (throw (Exception. "JBoss is already running!")))
    (println (.command this))
    (future (apply shell/sh (.split (.command this) " +"))))
  (stop [this]
    (request (.uri this),
             :operation "shutdown"
             :address [:host (first (host-controller (.uri this)))]))
  (ready? [this]
    (try
      (> (-> (host-controller (.uri this)) last :server count) 1)
      (catch Exception _)))
  (add [this name content-uri]
    (let [uri (.uri this)
          group (server-group uri)]
      (request (.uri this),
               :operation "add"
               :address ["deployment" name]
               :content [{:url (.toExternalForm content-uri)}])
      (request (.uri this),
               :operation "add"
               :address [{:server-group group} {:deployment name}]
               :content [{:url (.toExternalForm content-uri)}])))
  (deploy [this name]
    (let [uri (.uri this)
          group (server-group uri)
          result (request uri,
                          :operation "deploy"
                          :address [{:server-group group} {:deployment name}])]
      (if (= "success" (:outcome result))
        result
        (throw (Exception. (str result))))))
  (undeploy [this name]
    (let [uri (.uri this)
          group (server-group uri)]
      (request uri, :operation "remove" :address [{:server-group group} {:deployment name}])
      (request uri, :operation "remove" :address ["deployment" name]))))

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
