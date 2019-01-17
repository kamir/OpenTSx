set terminal png

set datafile separator ","

set output "/GITHUB/cuda-tsa/out/plot.png"

set title "TSBucket in Semi-log scaling"

set grid

set autoscale

#set xrange [0:10000]

set logscale y

plot '/GITHUB/cuda-tsa/out/rng_report_1501172822142/tab_rng.dat' using 1:2 with lines title "RNGModuleCUDA", '/GITHUB/cuda-tsa/out/rng_report_1501172822142/tab_rng.dat' using 1:3 with lines title "RNGModuleACMR", '/GITHUB/cuda-tsa/out/rng_report_1501172822142/tab_rng.dat' using 1:4 with lines title "RNGModuleACMS", '/GITHUB/cuda-tsa/out/rng_report_1501172822142/tab_rng.dat' using 1:5 with lines title "RNGModuleJUR"