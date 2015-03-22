(ns jboss-as.management
  (:require [clojure.java.shell :as shell]
            [clojure.java.io    :as io]
            [jboss-as.command   :as cmd]
            [jboss-as.api       :as api]))

(defprotocol Server
  "Things you can do to a JBoss server. Names should always have a
   valid suffix, e.g. clj, ima, war"
  (alter-config! [_ f] [_ f file]
    "Applies f to the content of the config xml, and writes it back to
     the file unless f returns nil. If file is provided, that file
     from the configuration dir will be altered, otherwise the default
     config will be altered.")
  (start [_] "Start the server")
  (stop [_] "Stop the server")
  (ready? [_] "Is the server up?")
  (deploy [_ name uri] [_ uri] "Deploy the content at uri")
  (deployed? [_ name] "Currently deployed?")
  (undeploy [_ name] "Undeploy by removing associated content"))

(defrecord Standalone [uri options cmd-fn])
(defrecord Domain [uri options cmd-fn])

(defn create-server
  "Returns an appropriate implementation of Server"
  [& {:keys [domain offset]
      :or {offset 0} :as opts}]
  (let [uri (format "http://localhost:%d/management" (+ api/port offset))]
    (if domain
      (->Domain uri (merge {:config "domain.xml"} opts) cmd/domain)
      (->Standalone uri (merge {:config "standalone-full.xml"} opts) cmd/standalone))))

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
      (api/resolve-port (:uri server) port host-name))))

(def common
  "Base functionality in common with both Standalone and Domain"
  {:start
   (fn [{:keys [cmd-fn options] :as this}]
     (if (ready? this)
       (throw (Exception. "JBoss is already running!")))
     (let [cmd (cmd-fn options)]
       (println cmd)
       (future (apply shell/sh (.split cmd " +")))))
   :deployed?
   (fn [{:keys [uri]} name]
     (->> (api/request uri
            :operation "read-children-names"
            :child-type "deployment")
       :result
       (some #{name})
       boolean))})

(defn alter-config*
  ([prefix server f]
     (alter-config* prefix server f nil))
  ([prefix {:keys [options]} f file]
     (let [dir (apply io/file
                  (if-let [base-dir (:base-dir options)]
                    [base-dir "configuration"]
                    [(:jboss-home options) prefix "configuration"]))
           file (io/file dir (or file (:config options)))]
       (when-let [result (f (slurp file))]
         (spit file result)))))

(def standalone
  "Server impls for Standalone mode"
  {:alter-config!
   (partial alter-config* "standalone")
   :stop
   (fn [{:keys [uri]}]
     (api/request uri :operation "shutdown"))
   :ready?
   (fn [{:keys [uri]}]
     (try (let [response (api/request uri
                           :operation "read-attribute"
                           :name "server-state")]
            (and response
              (= "success" (response :outcome))
              (= "running" (response :result))))
          (catch Exception _)))
   :deploy
   (fn [{:keys [uri] :as this} name content-uri]
     (if (deployed? this name) (undeploy this name))
     (api/request-or-toss uri
       :operation "add"
       :address [:deployment name]
       :content [{:url (.toExternalForm content-uri)}])
     (api/request-or-toss uri
       :operation "deploy"
       :address [:deployment name]))
   :undeploy
   (fn [{:keys [uri]} name]
     (api/request uri
       :operation "remove"
       :address [:deployment name]))})

(def domain
  "Server impls for Domain mode"
  {:alter-config!
   (partial alter-config* "domain")
   :stop
   (fn [{:keys [uri]}]
     (api/request uri
       :operation "shutdown"
       :address [:host (:name (api/host-controller uri))]))
   :ready?
   (fn [{:keys [uri]}]
     (try
       (api/all-servers-started? uri)
       (catch Exception _)))
   :deploy
   (fn [{:keys [uri] :as this} name content-uri]
     (if (deployed? this name) (undeploy this name))
     (let [group (api/server-group uri)]
       (api/request-or-toss uri
         :operation "add"
         :address [:deployment name]
         :content [{:url (.toExternalForm content-uri)}])
       (api/request-or-toss uri
         :operation "add"
         :address [{:server-group group} {:deployment name}]
         :content [{:url (.toExternalForm content-uri)}])
       (api/request-or-toss uri
         :operation "deploy"
         :address [{:server-group group} {:deployment name}])))
   :undeploy
   (fn [{:keys [uri]} name]
     (let [group (api/server-group uri)]
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
