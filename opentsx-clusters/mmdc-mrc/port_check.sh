echo "########################"
echo "#  MDC1                #"
echo "########################"

nc -z -v -w5 $mdc1 9091
nc -z -v -w5 $mdc1 9092
nc -z -v -w5 $mdc1 9093
nc -z -v -w5 $mdc1 8091
nc -z -v -w5 $mdc1 8092
nc -z -v -w5 $mdc1 8093
nc -z -v -w5 $mdc1 1234
nc -z -v -w5 $mdc1 1235
nc -z -v -w5 $mdc1 1236



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




