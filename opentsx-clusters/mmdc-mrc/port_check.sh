export mdc1=192.168.0.9
export mdc1=192.168.0.6
export mdc1=192.169.0.11



echo
echo "########################"
echo "#  MDC1                #"
echo "########################"

nc -z -v -w5 $mdc1 9091
nc -z -v -w5 $mdc1 9092
nc -z -v -w5 $mdc1 9093
nc -z -v -w5 $mdc1 8091
nc -z -v -w5 $mdc1 8092
nc -z -v -w5 $mdc1 8093
nc -z -v $mdc1 1234
nc -z -v $mdc1 1235
nc -z -v $mdc1 1236



echo
echo "########################"
echo "#  MDC3                #"
echo "########################"

nc -z -v -w5 $mdc3 9091
nc -z -v -w5 $mdc3 9092
nc -z -v -w5 $mdc3 9093
nc -z -v -w5 $mdc3 8091
nc -z -v -w5 $mdc3 8092
nc -z -v -w5 $mdc3 8093
nc -z -v -w5 $mdc3 1234
nc -z -v -w5 $mdc3 1235
nc -z -v -w5 $mdc3 1236



echo
echo "########################"
echo "#  MDC2                #"
echo "########################"

nc -z -v -w5 $mdc2 2181
nc -z -v -w5 $mdc2 1236
nc -z -v -w5 $mdc2 2182
nc -z -v -w5 $mdc2 1235
nc -z -v -w5 $mdc2 2183
nc -z -v -w5 $mdc2 1234
nc -z -v -w5 $mdc2 9091
nc -z -v -w5 $mdc2 8091
nc -z -v -w5 $mdc2 1237
nc -z -v -w5 $mdc2 9092
nc -z -v -w5 $mdc2 8092
nc -z -v -w5 $mdc2 1238
nc -z -v -w5 $mdc2 9093
nc -z -v -w5 $mdc2 8093
nc -z -v -w5 $mdc2 1239
nc -z -v -w5 $mdc2 9094
nc -z -v -w5 $mdc2 8094
nc -z -v -w5 $mdc2 1240
nc -z -v -w5 $mdc2 8081
nc -z -v -w5 $mdc2 8082
nc -z -v -w5 $mdc2 8088
nc -z -v -w5 $mdc2 8083
nc -z -v -w5 $mdc2 9021
nc -z -v -w5 $mdc2 9090
nc -z -v -w5 $mdc2 3000

