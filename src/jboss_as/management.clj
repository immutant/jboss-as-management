(ns jboss-as.management
  (:require [clojure.java.shell   :as shell]
            [clojure.data.json    :as json]
            [clj-http.lite.client :as client]
            [jboss-as.command     :as cmd]))

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
  (let [uri (format "http://localhost:%d/management" (+ cmd/mgmt-port offset))]
    (if domain
      (->Domain uri (cmd/domain opts))
      (->Standalone uri (cmd/standalone opts)))))

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

(defn api
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

(extend-type Standalone
  Server
  (start [this]
    (if (ready? this)
      (throw (Exception. "JBoss is already running!")))
    (println (.command this))
    (future (apply shell/sh (.split (.command this) " +"))))
  (stop [this]
    (api (.uri this), :operation "shutdown"))
  (ready? [this]
    (try (let [response (api (.uri this), :operation "read-attribute" :name "server-state")]
           (and response
                (= "success" (response :outcome))
                (= "running" (response :result))))
         (catch Exception _)))
  (add [this name content-uri]
    (api (.uri this),
         :operation "add"
         :address ["deployment" name]
         :content [{:url (.toExternalForm content-uri)}]))
  (deploy [this name]
    (let [result (api (.uri this), :operation "deploy" :address ["deployment" name])]
      (if (= "success" (:outcome result))
        result
        (throw (Exception. (-> (:failure-description result) first val first val))))))
  (undeploy [this name]
    (api (.uri this), :operation "remove" :address ["deployment" name])))

(defn host-controller [uri]
  (-> (api uri,
           :operation "read-children-resources",
           :child-type "host")
      :result
      first))

(defn server-group [uri]
  (-> (api uri,
           :operation "read-children-names"
           :child-type "server-group")
      :result
      first))

(extend-type Domain
  Server
  (start [this]
    (if (ready? this)
      (throw (Exception. "JBoss is already running!")))
    (println (.command this))
    (future (apply shell/sh (.split (.command this) " +"))))
  (stop [this]
    (api (.uri this),
         :operation "shutdown"
         :address [:host (first (host-controller (.uri this)))]))
  (ready? [this]
    (try
      (> (-> (host-controller (.uri this)) last :server count) 1)
      (catch Exception _)))
  (add [this name content-uri]
    (let [uri (.uri this)
          group (server-group uri)]
      (api (.uri this),
           :operation "add"
           :address ["deployment" name]
           :content [{:url (.toExternalForm content-uri)}])
      (api (.uri this),
           :operation "add"
           :address [{:server-group group} {:deployment name}]
           :content [{:url (.toExternalForm content-uri)}])))
  (deploy [this name]
    (let [uri (.uri this)
          group (server-group uri)
          result (api uri,
                      :operation "deploy"
                      :address [{:server-group group} {:deployment name}])]
      (if (= "success" (:outcome result))
        result
        (throw (Exception. (str result))))))
  (undeploy [this name]
    (let [uri (.uri this)
          group (server-group uri)]
      (api uri, :operation "remove" :address [{:server-group group} {:deployment name}])
      (api uri, :operation "remove" :address ["deployment" name]))))
