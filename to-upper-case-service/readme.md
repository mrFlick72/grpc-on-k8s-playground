mvn clean package install -DskipTests
docker build -t grpc-sample/to-upper-case-service:1 .

kind load docker-image grpc-sample/to-upper-case-service:1