(ns jboss-as.api
  (:require [clojure.data.json    :as json]
            [clj-http.lite.client :as client]))

;;; We assume the default management-http port
(def port 9990)

(defn mark [& msg]
  (let [ts (.format (java.text.SimpleDateFormat. "HH:mm:ss,SSS")
                 (java.util.Date.))]
    (apply println ts msg)))

(def ^:dynamic *log-request* false)

(defn request
  "Params assembled into a hash that is passed to the JBoss management
   interface via the passed URI and returns the JBoss response as a
   clojure map"
  [uri & params]
  (when *log-request* (mark (format "TC: (request %s %s)" uri (apply hash-map params))))
  (let [body (json/write-str (apply hash-map params))
        response (client/post uri {:body body
                                   :headers {"Content-Type" "application/json"}
                                   :throw-exceptions false})]
    (if-let [body (:body response)]
      (let [data (json/read-str body :key-fn keyword)]
        (when *log-request* (mark "TC: api data" data))
        data)
      (when *log-request* (mark "TC: api call returned nil")))))

(defn request-or-toss
  "Throws an Exception if the request isn't successful"
  [uri & params]
  (let [result (apply request uri params)]
    (if (= "success" (:outcome result))
      result
      (throw (Exception. (str result))))))

(defn host-controller [uri]
  (-> (request uri,
        :operation "read-children-resources",
        :child-type "host"
        :recursive true)
    :result vals first))

(defn servers [uri]
  (->> uri host-controller :server-config vals (filter :auto-start)))

(defn server-started? [uri host server]
  (let [response (request uri
                   :operation "read-attribute"
                   :name "server-state"
                   :address [{:host host} {:server server}])]
    (and
      (= "success" (:outcome response))
      (= "running" (:result response)))))

(defn all-servers-started? [uri]
  (every? (partial server-started? uri (:name (host-controller uri)))
    (map :name (servers uri))))

(defn server-group [uri]
  (-> (request uri,
        :operation "read-children-names"
        :child-type "server-group")
    :result
    first))

(defn stop-server [uri name]
  (request uri
    :operation "stop"
    :address [{:host (:name (host-controller uri))}
              {:server-config name}]))

(defn start-server [uri name]
  (request uri
    :operation "start"
    :address [{:host (:name (host-controller uri))}
              {:server-config name}]))

(defn server-status [uri name]
  (-> (request uri
        :operation "read-resource"
        :address [{:host (:name (host-controller uri))}
                  {:server-config name}]
        :include-runtime true)
    :result
    :status))

(defn system-properties [uri]
  (let [host (host-controller uri)
        addr (cond-> '({:core-service "platform-mbean"} {:type "runtime"})
               host (conj {:host (:name host)}))]
    (-> (request uri :operation "read-attribute",
          :address addr
          :name "system-properties")
      :result)))

(defn expand [val props]
  "Account for values like ${property:default}"
  (if-let [expr (:EXPRESSION_VALUE val)]
    (if-let [match (re-find #"\$\{(.*):(.*)\}" expr)]
      (or (get props (keyword (get match 1)))
        (get match 2))
      expr)
    val))

(defn expand-to-number [val props]
  (if-let [x (expand val props)]
    (Integer. x)))

(defn offset
  "Determine the port offset for either a single standalone server or
   one in a domain"
  [uri & [server-name]]
  (mark (format "TC: (offset %s %s)" uri server-name))
  (let [server-name (keyword server-name)]
    (expand-to-number
      (if server-name
        (-> (host-controller uri)         ; domain
          :server-config
          server-name
          :socket-binding-port-offset)
        (-> (request uri,                 ; standalone
              :operation "read-children-resources"
              :child-type "socket-binding-group")
          :result
          :standard-sockets
          :port-offset))
      (system-properties uri))))

(defn socket-binding [uri name]
  (let [name (keyword name)]
    (-> (request uri
          :operation "read-children-resources"
          :child-type "socket-binding-group"
          :recursive true)
      :result :standard-sockets :socket-binding name)))

(defn resolve-port
  [uri v & [host-name]]
  (mark (format "TC: (resolve-port %s %s %s)" uri v host-name))
  (binding [*log-request* true]
    (+ (offset uri host-name)
      (if (number? v)
        v
        (expand-to-number
          (:port (socket-binding uri v))
          (system-properties uri))))))
