## Time Series Generator in a POD
This scenario uses the Time Series Generator in a Docker container, which is started
as a Kubernetes application.

### Setup
We have to provide an absolute path to the configuration folder and to the script folder 
which contains the script used by the "container as entry-point".
 
```bash 
kubectl apply -f lg-demo.yaml
kubectl get pods 
kubectl delete pod ...
``` 

