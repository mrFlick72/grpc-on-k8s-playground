while [ true ]; do
  echo $(date) >> result.txt
  echo $(curl http://grpc.hello-service.local/hello/valerio )>> result.txt

  sleep 0.2
done