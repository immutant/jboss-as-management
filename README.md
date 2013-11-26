# jboss-as-management

A Clojure wrapper around the JBoss AS7 "REST" management API.

## Usage

Right now, the support is fairly basic. You can check to see if the server is up,
shut it down, add a deployment, deploy said deployment, or remove said deployment.

You can use `jboss-as.api/request` to call other management
operations. Pass it a URI like "http://localhost:9990/management".

Examples:

    (require '[jboss-as.management :as mgt])

    ;;; Create a server
    (def server (mgt/create-server :jboss-home (System/getenv "JBOSS_HOME")))

    ;;; Start it up
    (mgt/start server)
    
    ;;; Wait for it
    (mgt/wait-for-ready? server)

    ;;; Deploy an app
    (mgt/deploy server "my-deployment.clj" (.toUrl some-descriptor-file))

    ;;; See if it's deployed
    (mgt/deployed? server "my-deployment.clj")

    ;;; Undeploy it
    (mgt/undeploy server "my-deployment.clj")

    ;;; Shutdown the server
    (mgt/stop server)

## License

Copyright © 2012 Red Hat, Inc.

Distributed under the Eclipse Public License.
