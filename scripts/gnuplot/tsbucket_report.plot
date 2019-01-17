TIME_STAMP="20_1501326857966"

OUTPUT_FILE="/GITHUB/cuda-tsa/out/tsbucket_report"

INPUT_FOLDER="out/rng_report_".TIME_STAMP
INPUT_FOLDER_LABEL="out/rng-report-".TIME_STAMP


#OUTPUT_FILE="/GITHUB/cuda-tsa/".INPUT_FOLDER."/out/rng_report_".TIME_STAMP

DATA_FILE="/GITHUB/cuda-tsa/".INPUT_FOLDER."/tab_rng.dat"

print OUTPUT_FILE


#set title "TSBucket Report in Semi-log scaling : ".INPUT_FOLDER_LABEL
set title INPUT_FOLDER_LABEL." (NO CUDA)"



set terminal png
set autoscale

set yrange [1:100000]
set output sprintf('%s.png', OUTPUT_FILE)

set datafile separator "\t"

set key left top

set grid

set logscale y
#set logscale x

set ylabel "creation time [ms]"
set xlabel "# of random number"

# 3 rows
# plot DATA_FILE using 1:2 with lines title "Commons Math R", DATA_FILE using 3:4 with lines title "Commons Math S", DATA_FILE using 5:($6 * 1) with lines title "java.util.Random"

# 4 rows
plot DATA_FILE using 1:2 with lines title "Commons Math R", DATA_FILE using 3:4 with lines title "Commons Math S", DATA_FILE using 5:($6 * 1) with lines title "java.util.Random", DATA_FILE using 7:8 with lines title "JCuda"
