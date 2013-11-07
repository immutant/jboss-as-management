(ns jboss-as.command
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

(defn jvm-options []
  ["-Xms64m"
   "-Xmx1024m"
   "-XX:MaxPermSize=1024m"
   "-XX:+UseConcMarkSweepGC"
   "-XX:+UseParNewGC"
   "-XX:+CMSClassUnloadingEnabled"])

(defn debug-opt [opt]
  (if opt
    (if (true? opt)
      "-Xrunjdwp:transport=dt_socket,address=8787,server=y,suspend=y"
      opt)))

(defn sysprop [name default]
  (format "-D%s=%s"
          name
          (if-let [val (System/getProperty name)]
            val
            (str default))))

(defn standalone
  [{:keys [jboss-home base-dir debug offset] :or {offset 0}}]
  (let [java-home (System/getProperty "java.home")
        base-dir (or base-dir (io/file jboss-home "standalone"))]
    (->> (concat
          (cons (str java-home "/bin/java")
                (jvm-options))
          [(debug-opt debug)
           (sysprop "jboss.home.dir" jboss-home)
           (sysprop "logging.configuration"
                    (format "file:%s/standalone/configuration/logging.properties" jboss-home))
           (sysprop "org.jboss.boot.log.file"
                    (format "%s/log/boot.log" base-dir))
           (format "-jar %s/jboss-modules.jar" jboss-home)
           (format "-mp %s/modules" jboss-home)
           "-jaxpmodule javax.xml.jaxp-provider"
           "org.jboss.as.standalone"
           (sysprop "jboss.server.base.dir" base-dir)
           (sysprop "jboss.socket.binding.port-offset" offset)])
         (str/join " "))))

(defn domain
  [{:keys [jboss-home base-dir debug offset] :or {offset 0}}]
  (let [java-home (System/getProperty "java.home")
        base-dir (or base-dir (io/file jboss-home "standalone"))]
    (->> (concat
          (cons (str java-home "/bin/java")
                (jvm-options))
          [(debug-opt debug)
           (sysprop "jboss.home.dir" jboss-home)
           (sysprop "logging.configuration"
                    (format "file:%s/standalone/configuration/logging.properties" jboss-home))
           (sysprop "org.jboss.boot.log.file"
                    (format "%s/log/boot.log" base-dir))
           (format "-jar %s/jboss-modules.jar" jboss-home)
           (format "-mp %s/modules" jboss-home)
           "-jaxpmodule javax.xml.jaxp-provider"
           "org.jboss.as.standalone"
           (sysprop "jboss.server.base.dir" base-dir)
           (sysprop "jboss.socket.binding.port-offset" offset)])
         (str/join " "))))

