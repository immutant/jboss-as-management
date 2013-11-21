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

(defn deep-merge
  "Recursively merges maps"
  [& vals]
  (if (every? map? vals)
    (apply merge-with deep-merge vals)
    (last vals)))

(defn host-controller [uri]
  "We merge the results of a non-recursive call with a recursive one
   to address a bug where the :server key is absent from the recursive
   result"
  (let [shallow (request uri,
                  :operation "read-children-resources",
                  :child-type "host"
                  :recursive false)
        deep (request uri,
               :operation "read-children-resources",
               :child-type "host"
               :recursive true)
        merged (deep-merge shallow deep)]
    (-> merged :result vals first)))

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
  (if-let [host (host-controller uri)]
    (get-in host [:core-service :platform-mbean :type :runtime :system-properties])
    (-> (request uri :operation "read-children-resources" :child-type "core-service" :recursive true)
      :result :platform-mbean :type :runtime :system-properties)))

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
  (+ (offset uri host-name)
    (if (number? v)
      v
      (expand-to-number
        (:port (socket-binding uri v))
        (system-properties uri)))))
