# How to create a new cluster

kind create cluster --config cluster.yml
kubectl apply -f https://raw.githubusercontent.com/metallb/metallb/v0.13.10/config/manifests/metallb-native.yaml
docker network inspect kind | grep Subnet
kubectl apply -f metalb.yml

kubectl cluster-info --context kind-kind

kind delete cluster
