#!/bin/sh
sed 's/,/./g' $1 > ./tmp.dat
/usr/local/bin/gawk -f $2/kstest.awk -v min=-100.0 -v max=100 -v typ=default ./tmp.dat
