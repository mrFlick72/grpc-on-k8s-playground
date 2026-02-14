# How to create a new cluster

kind create cluster --config cluster.yml
kubectl apply -f https://raw.githubusercontent.com/metallb/metallb/v0.13.10/config/manifests/metallb-native.yaml
docker network inspect kind | grep Subnet
kubectl apply -f metalb.yml

kubectl cluster-info --context kind-kind

kind delete cluster

## Prometheus

kubectl create namespace prometheus-system
helm upgrade --install prometheus oci://ghcr.io/prometheus-community/charts/prometheus -n prometheus-system

## KEDA

helm repo add kedacore https://kedacore.github.io/charts
helm repo update

kubectl create namespace keda
helm install keda kedacore/keda --namespace keda --version 2.19.0
