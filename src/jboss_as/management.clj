(ns jboss-as.management
  (:require [clojure.java.shell   :as shell]
            [jboss-as.command     :as cmd]
            [jboss-as.api         :as api]))

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
  (let [uri (format "http://localhost:%d/management" (+ api/port offset))]
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

(def port
  "Resolve port from server's offset. Argument may either be a number
   like 8080, or a keyword like :http. The optional host-name only
   applies to a Domain server."
  (memoize
    (fn [server port & [host-name]]
      (api/resolve-port (.uri server) port host-name))))

(def common
  "Base functionality in common with both Standalone and Domain"
  {:start
   (fn [this]
     (if (ready? this)
       (throw (Exception. "JBoss is already running!")))
     (println (.command this))
     (future (apply shell/sh (.split (.command this) " +"))))
   :deployed?
   (fn [this name]
     (->> (api/request (.uri this)
            :operation "read-children-names"
            :child-type "deployment")
       :result
       (some #{name})
       boolean))})

(def standalone
  "Server impls for Standalone mode"
  {:stop
   (fn [this]
     (api/request (.uri this), :operation "shutdown"))
   :ready?
   (fn [this]
     (try (let [response (api/request (.uri this)
                           :operation "read-attribute"
                           :name "server-state")]
            (and response
              (= "success" (response :outcome))
              (= "running" (response :result))))
          (catch Exception _)))
   :deploy
   (fn [this name content-uri]
     (if (deployed? this name) (undeploy this name))
     (api/request (.uri this),
       :operation "add"
       :address [:deployment name]
       :content [{:url (.toExternalForm content-uri)}])
     (let [result (api/request (.uri this),
                    :operation "deploy"
                    :address [:deployment name])]
       (if (= "success" (:outcome result))
         result
         (throw (Exception. (str result))))))
   :undeploy
   (fn [this name]
     (api/request (.uri this),
       :operation "remove"
       :address [:deployment name]))})

(def domain
  "Server impls for Domain mode"
  {:stop
   (fn [this]
     (api/request (.uri this),
       :operation "shutdown"
       :address [:host (:name (api/host-controller (.uri this)))]))
   :ready?
   (fn [this]
     (try
       (> (-> (api/host-controller (.uri this)) :server count) 1)
       (catch Exception _)))
   :deploy
   (fn [this name content-uri]
     (if (deployed? this name) (undeploy this name))
     (let [uri (.uri this)
           group (api/server-group uri)]
       (api/request (.uri this),
         :operation "add"
         :address [:deployment name]
         :content [{:url (.toExternalForm content-uri)}])
       (api/request (.uri this),
         :operation "add"
         :address [{:server-group group} {:deployment name}]
         :content [{:url (.toExternalForm content-uri)}])
       (let [result (api/request uri,
                      :operation "deploy"
                      :address [{:server-group group} {:deployment name}])]
         (if (= "success" (:outcome result))
           result
           (throw (Exception. (str result)))))))
   :undeploy
   (fn [this name]
     (let [uri (.uri this)
           group (api/server-group uri)]
       (api/request uri,
         :operation "remove"
         :address [{:server-group group} {:deployment name}])
       (api/request uri,
         :operation "remove"
         :address [:deployment name])))})

(extend Standalone
  Server
  (merge common standalone))

(extend Domain
  Server
  (merge common domain))
