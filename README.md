# jboss-as-management

A Clojure wrapper around the JBoss AS7 "REST" management API.

## Usage

Right now, the support is fairly basic. You can check to see if the server is up,
shut it down, add a deployment, deploy said deployment, or remove said deployment.

You can also use the `api` function to call other management operations. It will
connect to whatever endpoint that `*api-endpoint*` is bound to, which is 
"http://localhost:9990/management" by default.

Examples:

    (require '[jboss-as.management :as mgt])
    
    ;; see if the server is up
    (mgt/ready?)
    
    ;; add deployment content
    (mgt/add "my-deployment" (.toUrl some-descriptor-file))

    ;; deploy the added content
    (mgt/deploy "my-deployment")

    ;; remove (undeploy) the deployment
    (mgt/remove "my-deployment")

    ;; shut it down, shut it down now
    (mgt/shutdown)
    
    ;; talk to a different endpoint
    (binding [mgt/*api-endpoint* "http://somewhere:9990/management"]
      (mgt/ready?))
      
## License

Copyright © 2012 Red Hat, Inc.

Distributed under the Eclipse Public License.
