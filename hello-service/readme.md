mvn clean package install -DskipTests
docker build -t grpc-sample/hello-service:1 .

kind load docker-image grpc-sample/hello-service:1