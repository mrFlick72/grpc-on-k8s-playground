while [ true ]; do
  echo $(date) >> result.txt
  echo $(curl http://172.25.255.200:8085/hello/valerio )>> result.txt

  sleep 0.2
done